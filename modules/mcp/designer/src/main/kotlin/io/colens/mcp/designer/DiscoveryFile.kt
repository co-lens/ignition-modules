package io.colens.mcp.designer

import io.colens.mcp.common.McpJson
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.put
import org.slf4j.LoggerFactory
import java.io.IOException
import java.io.RandomAccessFile
import java.net.InetAddress
import java.nio.channels.FileChannel
import java.nio.channels.FileLock
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions
import java.security.SecureRandom
import java.time.Instant

/**
 * Publishes this Designer's MCP endpoint so an external client can find it.
 *
 * Two files per Designer process, both owner-only:
 *
 *  - `designer-<pid>.json` — port, secret, project, gateway, and where the endpoint is actually
 *    reachable from. Written atomically so a reader never sees a half-written file.
 *  - `designer-<pid>.lock` — held under an exclusive [FileLock] for the Designer's lifetime.
 *
 * The split matters: an exclusive lock on the data file itself would block readers on some
 * platforms (notably WSL). Keeping liveness in a separate lock file means anyone can read the
 * JSON while the lock still answers "is that Designer alive?" — if a lock can be acquired, its
 * owner is gone and both files are stale.
 */
class DiscoveryFile(private val directory: Path = defaultDirectory()) {

    private val logger = LoggerFactory.getLogger("mcp.Designer.Discovery")

    private val pid: Long = ProcessHandle.current().pid()
    private val dataPath: Path = directory.resolve("designer-$pid.json")
    private val lockPath: Path = directory.resolve("designer-$pid.lock")

    val secret: String = generateSecret()

    private var lockFile: RandomAccessFile? = null
    private var lockChannel: FileChannel? = null
    private var lock: FileLock? = null

    val path: Path get() = dataPath

    /** Acquires the liveness lock and sweeps files left behind by dead Designers. */
    fun initialize(): Boolean {
        return try {
            Files.createDirectories(directory)
            sweepStale()

            val raf = RandomAccessFile(lockPath.toFile(), "rw")
            val channel = raf.channel
            val acquired = channel.tryLock()
            if (acquired == null) {
                logger.error("Could not lock {}; another process holds it", lockPath)
                raf.close()
                return false
            }

            lockFile = raf
            lockChannel = channel
            lock = acquired
            restrictPermissions(lockPath)
            true
        } catch (e: IOException) {
            logger.error("Failed to initialise discovery directory {}", directory, e)
            false
        }
    }

    fun write(port: Int, host: String, loopbackOnly: Boolean, project: String?, gatewayAddress: String?) {
        val json = jsonObject {
            put("pid", pid)
            put("port", port)
            put("host", host)
            put("url", "http://$host:$port/mcp")
            // `host` alone doesn't say whether a client elsewhere can reach this. These two do:
            // a reader that finds loopbackOnly true and a hostname that isn't its own can report
            // "that Designer is bound to loopback on <machine>" instead of a bare ECONNREFUSED,
            // which is indistinguishable from a dead port. The default bind is loopback, so this
            // is the common case rather than the exotic one.
            put("loopbackOnly", loopbackOnly)
            put("hostname", localHostname())
            put("secret", secret)
            put("project", project)
            put("gateway", gatewayAddress)
            put("startedAt", Instant.now().toString())
        }

        try {
            val temp = directory.resolve("designer-$pid.json.tmp")
            Files.write(temp, McpJson.toPrettyString(json).toByteArray(StandardCharsets.UTF_8))
            restrictPermissions(temp)
            Files.move(
                temp,
                dataPath,
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
            logger.info("Discovery file written: {}", dataPath)
        } catch (e: IOException) {
            logger.error("Failed to write discovery file {}", dataPath, e)
        }
    }

    fun shutdown() {
        runCatching { lock?.takeIf { it.isValid }?.release() }
        runCatching { lockChannel?.close() }
        runCatching { lockFile?.close() }
        lock = null
        lockChannel = null
        lockFile = null

        runCatching { Files.deleteIfExists(dataPath) }
        runCatching { Files.deleteIfExists(lockPath) }
    }

    /** A lock we can acquire belongs to a Designer that is no longer running — clean it up. */
    private fun sweepStale() {
        val locks = try {
            Files.list(directory).use { stream ->
                stream.filter { it.fileName.toString().let { n -> n.startsWith("designer-") && n.endsWith(".lock") } }
                    .toList()
            }
        } catch (e: IOException) {
            return
        }

        for (candidate in locks) {
            if (candidate == lockPath) continue
            try {
                RandomAccessFile(candidate.toFile(), "rw").use { raf ->
                    raf.channel.use { channel ->
                        val acquired = channel.tryLock() ?: return@use
                        acquired.release()
                        Files.deleteIfExists(candidate)
                        Files.deleteIfExists(
                            candidate.resolveSibling(candidate.fileName.toString().removeSuffix(".lock") + ".json")
                        )
                        logger.info("Removed stale discovery files for {}", candidate.fileName)
                    }
                }
            } catch (e: Exception) {
                logger.debug("Skipping {}: {}", candidate.fileName, e.message)
            }
        }
    }

    private fun restrictPermissions(target: Path) {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-------"))
        } catch (e: UnsupportedOperationException) {
            // Windows: files under the user profile are already user-scoped.
        } catch (e: IOException) {
            logger.warn("Could not restrict permissions on {}", target, e)
        }
    }

    /**
     * Null rather than an exception: `getLocalHost` throws on a host whose hostname doesn't
     * resolve, which is common enough in containers, and a missing field is not worth failing a
     * Designer startup over.
     */
    private fun localHostname(): String? =
        runCatching { InetAddress.getLocalHost().hostName }.getOrNull()

    private fun generateSecret(): String {
        val bytes = ByteArray(24)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    companion object {
        fun defaultDirectory(): Path =
            Paths.get(System.getProperty("user.home"), ".ignition", "mcp")
    }
}
