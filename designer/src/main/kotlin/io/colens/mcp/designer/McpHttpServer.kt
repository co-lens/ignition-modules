package io.colens.mcp.designer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.colens.mcp.common.McpHttpRequest
import io.colens.mcp.common.McpServer
import org.slf4j.LoggerFactory
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ThreadPoolExecutor

/**
 * A loopback-only HTTP front end for [McpServer], using the JDK's own `com.sun.net.httpserver`.
 *
 * That module (`jdk.httpserver`) is present in every JRE Ignition 8.3 ships — Linux, macOS and
 * Windows all carry it — so the Designer scope needs no HTTP dependency at all.
 *
 * Security posture: bound to the loopback interface only, and every request must carry
 * `Authorization: Bearer <secret>` where the secret is generated per Designer session and
 * written to an owner-readable file. Origin checking happens inside [McpServer].
 */
class McpHttpServer(private val mcp: McpServer, private val secret: String) {

    private val logger = LoggerFactory.getLogger("mcp.Designer.Http")
    private var server: HttpServer? = null

    val port: Int get() = server?.address?.port ?: -1

    /** Starts on an OS-assigned loopback port. Returns the port. */
    fun start(path: String = "/mcp"): Int {
        // Port 0 lets the OS pick a free port atomically — no scan loop, no bind races.
        val http = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        http.createContext(path) { exchange -> handle(exchange) }
        http.executor = Executors.newFixedThreadPool(4) { runnable ->
            Thread(runnable, "mcp-designer-http").apply { isDaemon = true }
        }
        http.start()
        server = http
        logger.info("Designer MCP endpoint listening on http://127.0.0.1:{}{}", http.address.port, path)
        return http.address.port
    }

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

    private companion object {
        const val BEARER_PREFIX = "Bearer "
    }
}
