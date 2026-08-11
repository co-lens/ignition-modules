package io.colens.mcp.common

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/**
 * Writes a copy of what a destructive tool is about to change, before it changes it.
 *
 * Three properties make this a guard rail rather than a convenience, and each is a decision:
 *
 * **It fails closed.** If the snapshot cannot be written, the caller is expected to abandon the
 * edit — [snapshotOnce] throws rather than returning quietly. A backup that silently does not
 * happen is worse than no backup at all, because the operator believes they have one.
 *
 * **It snapshots once per key per session.** Editing the same view twelve times leaves one copy of
 * its pre-session state, not twelve near-identical ones. The thing worth being able to get back to
 * is what existed before this run started touching it; intermediate states are recoverable by
 * simply not making the next edit.
 *
 * **It never overwrites.** File names carry a UTC timestamp, and a collision within the same second
 * gets a counter, so an earlier snapshot cannot be replaced by a later one.
 *
 * [rootProvider] is a lambda, not a [Path], deliberately: the tool classes are constructed by the
 * documentation generator against a stub context that fails the build if construction touches it,
 * so resolving the gateway's data directory has to be deferred to first use.
 */
class SnapshotStore(
    private val rootProvider: () -> Path,
    private val maxPerCategory: Int = DEFAULT_MAX_PER_CATEGORY,
) {

    private val taken = ConcurrentHashMap.newKeySet<String>()
    private val root: Path by lazy { rootProvider() }

    /**
     * Writes [content] under [category] if [key] has not been snapshotted yet in this session.
     *
     * @param key what is being protected — a tag path, a project-and-view pair. Identity, not
     *   content: the same key is snapshotted once however many times it is edited.
     * @param label goes into the file name, so a human scanning the directory can find the right
     *   file without opening any of them.
     * @param content produces the current state, or null when there is nothing to preserve — a tag
     *   that does not exist yet, a view being created. Returning null is *not* a failure and does
     *   not block the edit.
     *
     * @return where it was written, null if there was nothing to write or [key] was already taken.
     * @throws McpArgumentException if the current state could not be read or the file not written.
     *   Callers must let this propagate: that refusal is the entire point.
     */
    fun snapshotOnce(key: String, category: String, label: String, content: () -> String?): Path? {
        if (!taken.add(key)) return null

        val text = try {
            content()
        } catch (e: Exception) {
            // Un-take it: this key was never actually protected, and a retry should try again
            // rather than sail past a snapshot that never happened.
            taken.remove(key)
            throw McpArgumentException(
                "Refusing to proceed: could not read the current state of '$key' to back it up " +
                    "first (${e.message ?: e.javaClass.simpleName}). Nothing was changed."
            )
        }

        if (text == null) return null

        return try {
            val dir = root.resolve(category)
            Files.createDirectories(dir)
            val file = uniquePath(dir, label)
            // Write-then-move so a crash mid-write cannot leave a truncated file looking like a
            // usable backup.
            val temp = Files.createTempFile(dir, "snapshot", ".tmp")
            Files.write(temp, text.toByteArray(StandardCharsets.UTF_8))
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE)
            prune(dir)
            file
        } catch (e: IOException) {
            taken.remove(key)
            throw McpArgumentException(
                "Refusing to proceed: could not write the pre-edit backup of '$key' to " +
                    "${runCatching { root.toString() }.getOrElse { "the backup directory" }} " +
                    "(${e.message ?: e.javaClass.simpleName}). Nothing was changed. Fix the " +
                    "backup directory, or point it elsewhere with -D$ROOT_PROPERTY."
            )
        }
    }

    /** Timestamped and collision-proof, so no snapshot can ever displace an earlier one. */
    private fun uniquePath(dir: Path, label: String): Path {
        val stamp = TIMESTAMP.format(ZonedDateTime.now(ZoneOffset.UTC))
        val safe = label.replace(UNSAFE, "-").trim('-').take(MAX_LABEL).ifEmpty { "snapshot" }
        var candidate = dir.resolve("$stamp-$safe.json")
        var n = 1
        while (Files.exists(candidate)) candidate = dir.resolve("$stamp-$safe.$n.json").also { n++ }
        return candidate
    }

    /**
     * Best-effort, and deliberately silent. Once the snapshot is safely on disk the edit is allowed
     * to proceed; failing it here because an *old* file could not be deleted would refuse a write
     * that is already protected.
     */
    private fun prune(dir: Path) {
        runCatching {
            Files.list(dir).use { stream ->
                val files = stream.filter { Files.isRegularFile(it) }.sorted().toList()
                if (files.size > maxPerCategory) {
                    files.take(files.size - maxPerCategory).forEach { runCatching { Files.delete(it) } }
                }
            }
        }
    }

    companion object {
        /** Overrides where snapshots are written, on both the gateway and the Designer. */
        const val ROOT_PROPERTY = "mcp.backupDir"

        /**
         * Per category, not overall, so a busy run of tag edits cannot age out the view snapshot
         * somebody actually needs. Sorted by name, and names lead with a UTC timestamp, so
         * "oldest" is lexicographic order.
         */
        const val DEFAULT_MAX_PER_CATEGORY = 500

        const val TAGS = "tags"
        const val VIEWS = "views"
        const val RESOURCES = "resources"

        private const val MAX_LABEL = 80
        private val UNSAFE = Regex("[^A-Za-z0-9._-]+")
        private val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")

        /** `-Dmcp.backupDir` wins; otherwise [fallback], which each scope supplies for itself. */
        fun resolveRoot(fallback: () -> Path): Path =
            System.getProperty(ROOT_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
                ?.let { Path.of(it) }
                ?: fallback()
    }
}
