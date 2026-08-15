package io.colens.mcp.designer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.colens.mcp.common.McpHttpRequest
import io.colens.mcp.common.McpServer
import org.slf4j.LoggerFactory
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

/**
 * An HTTP front end for [McpServer], using the JDK's own `com.sun.net.httpserver`.
 *
 * That module (`jdk.httpserver`) is present in every JRE Ignition 8.3 ships — Linux, macOS and
 * Windows all carry it — so the Designer scope needs no HTTP dependency at all.
 *
 * Security posture: loopback-only by default, and every request must carry
 * `Authorization: Bearer <secret>` where the secret is generated per Designer session and written
 * to an owner-readable file. Origin checking happens inside [McpServer]. [start] documents the
 * opt-in for binding wider, which trades that first line of defence for reachability.
 */
class McpHttpServer(private val mcp: McpServer, private val secret: String) {

    private val logger = LoggerFactory.getLogger("mcp.Designer.Http")
    private var server: HttpServer? = null

    val port: Int get() = server?.address?.port ?: -1

    /** The address actually bound, for the discovery file and the connect dialog. */
    var boundHost: String = "127.0.0.1"
        private set

    /**
     * True when the endpoint is reachable only from this machine. Published in the discovery file
     * because the failure it causes — a client elsewhere getting `ECONNREFUSED` — is otherwise
     * indistinguishable from a dead port, a wrong port, or a Designer that never started.
     */
    var loopbackOnly: Boolean = true
        private set

    /**
     * Starts the endpoint. Loopback on an OS-assigned port by default; both are overridable so a
     * Designer running on another machine (a VM, a jump box) can still be reached:
     *
     * ```
     * -Dmcp.designer.bindAddress=0.0.0.0
     * -Dmcp.designer.port=8770
     * ```
     *
     * A pinned port is best-effort: it is honoured when free, and degraded to an OS-assigned port
     * with a warning when it isn't. A second Designer on the same machine inherits the same JVM
     * arguments from the launcher, and losing its endpoint entirely is the worse failure.
     *
     * Binding beyond loopback exposes the endpoint to anything that can route to this machine.
     * The bearer secret is then the only thing protecting it, so this is opt-in, logged loudly,
     * and should be paired with a firewall rule or a forwarded port rather than left wide open.
     */
    fun start(path: String = "/mcp"): Int {
        val requestedHost = System.getProperty(BIND_ADDRESS_PROPERTY)?.trim()?.takeIf { it.isNotEmpty() }
        // Port 0 lets the OS pick a free port atomically — no scan loop, no bind races. A fixed
        // port only makes sense when someone needs to forward or firewall it.
        val requestedPort = System.getProperty(PORT_PROPERTY)?.trim()?.toIntOrNull() ?: 0

        val address = if (requestedHost == null) {
            InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort)
        } else {
            InetSocketAddress(InetAddress.getByName(requestedHost), requestedPort)
        }

        val http = createServer(address, requestedPort)
        http.createContext(path) { exchange -> handle(exchange) }
        http.executor = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "mcp-designer-http").apply { isDaemon = true }
        }
        http.start()
        server = http
        boundHost = requestedHost ?: "127.0.0.1"
        loopbackOnly = requestedHost == null || isLoopback(requestedHost)

        if (!loopbackOnly) {
            logger.warn(
                "Designer MCP endpoint is bound to {}:{} — reachable beyond this machine. The " +
                    "bearer secret in the discovery file is the only thing protecting it; restrict " +
                    "access with a firewall rule or a forwarded port.",
                boundHost,
                http.address.port,
            )
        } else {
            logger.info(
                "Designer MCP endpoint listening on http://{}:{}{}",
                boundHost,
                http.address.port,
                path,
            )
        }
        return http.address.port
    }

    /**
     * Binds [address], falling back to an OS-assigned port if a pinned one is taken.
     *
     * The usual cause is a second Designer on this machine: the launcher's JVM-argument field is a
     * single value per application, so both Designers carry the same `-Dmcp.designer.port` and the
     * second one used to die here with a bare `BindException` and no endpoint at all. Falling back
     * keeps it working; the warning is what tells anyone forwarding the pinned port that this
     * Designer is not the one behind it.
     */
    private fun createServer(address: InetSocketAddress, requestedPort: Int): HttpServer {
        try {
            return HttpServer.create(address, 0)
        } catch (e: BindException) {
            // Nothing to fall back to: the OS had no free port at all, which is a real failure.
            if (requestedPort == 0) throw e

            val fallback = HttpServer.create(InetSocketAddress(address.address, 0), 0)
            logger.warn(
                "Port {} (-D{}) is already in use, most likely by another Designer on this " +
                    "machine. Fell back to OS-assigned port {}. Anything forwarding or " +
                    "firewalling {} will not reach THIS Designer — see Tools -> MCP Connection " +
                    "Info... for its real address.",
                requestedPort,
                PORT_PROPERTY,
                fallback.address.port,
                requestedPort,
            )
            return fallback
        }
    }

    private fun isLoopback(host: String): Boolean =
        host == "127.0.0.1" || host.equals("localhost", ignoreCase = true) || host == "::1"

    fun stop() {
        server?.let { http ->
            http.stop(1)
            (http.executor as? ThreadPoolExecutor)?.shutdownNow()
            logger.info("Designer MCP endpoint stopped")
        }
        server = null
    }

    private fun handle(exchange: HttpExchange) {
        try {
            if (!isAuthorized(exchange)) {
                respond(exchange, 401, """{"error":"Unauthorized"}""", "application/json")
                return
            }

            val body = if (exchange.requestMethod.equals("POST", ignoreCase = true)) {
                exchange.requestBody.readBytes().toString(StandardCharsets.UTF_8)
            } else {
                null
            }

            val result = mcp.handle(
                McpHttpRequest(
                    method = exchange.requestMethod,
                    body = body,
                    origin = exchange.requestHeaders.getFirst("Origin"),
                )
            )

            result.headers.forEach { (name, value) -> exchange.responseHeaders.set(name, value) }
            respond(exchange, result.status, result.body, result.contentType)
        } catch (e: Exception) {
            logger.warn("Error handling MCP request", e)
            runCatching { respond(exchange, 500, """{"error":"Internal error"}""", "application/json") }
        } finally {
            exchange.close()
        }
    }

    private fun isAuthorized(exchange: HttpExchange): Boolean {
        val header = exchange.requestHeaders.getFirst("Authorization") ?: return false
        if (!header.startsWith(BEARER_PREFIX, ignoreCase = true)) return false
        val presented = header.substring(BEARER_PREFIX.length).trim()
        // Constant-time compare so the secret can't be recovered by timing the loopback endpoint.
        return MessageDigest.isEqual(
            presented.toByteArray(StandardCharsets.UTF_8),
            secret.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun respond(exchange: HttpExchange, status: Int, body: String, contentType: String?) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        contentType?.let { exchange.responseHeaders.set("Content-Type", it) }
        if (bytes.isEmpty()) {
            exchange.sendResponseHeaders(status, -1)
        } else {
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
    }

    companion object {
        private const val BEARER_PREFIX = "Bearer "

        /** Opt in to binding beyond loopback, e.g. `0.0.0.0` for a Designer in a VM. */
        const val BIND_ADDRESS_PROPERTY = "mcp.designer.bindAddress"

        /**
         * Pin the port so it can be forwarded or firewalled. Defaults to OS-assigned, and falls
         * back to it — with a warning — when the pinned port is already taken.
         */
        const val PORT_PROPERTY = "mcp.designer.port"
    }
}
