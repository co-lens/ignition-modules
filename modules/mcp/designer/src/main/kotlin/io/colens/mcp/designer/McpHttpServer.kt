package io.colens.mcp.designer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import io.colens.mcp.common.DesignerAuth
import io.colens.mcp.common.DevMode
import io.colens.mcp.common.McpHttpRequest
import io.colens.mcp.common.McpServer
import org.slf4j.LoggerFactory
import java.net.BindException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
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
 *
 * `-Dmcp.devMode=true` drops the bearer requirement entirely. The secret is still generated and
 * still written to the discovery file, so the command [ConnectDialog] shows keeps working — it
 * just stops being checked.
 */
class McpHttpServer(private val mcp: McpServer, private val auth: DesignerAuth) {

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
        val requestedPort = System.getProperty(PORT_PROPERTY)?.trim()?.toIntOrNull() ?: DEFAULT_PORT

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

        if (DevMode.enabled()) {
            // No longer mentions the Authorization header: no credential is the default now, so
            // saying so here would imply dev mode is what removed it. These are what it still does.
            logger.warn(
                "{} is set: the Designer MCP endpoint on {}:{} has its Origin allowlist off and " +
                    "registers save_project. A pinned {} is still enforced.",
                DevMode.PROPERTY,
                boundHost,
                http.address.port,
                DesignerAuth.SECRET_PROPERTY,
            )
        }

        if (auth.secretIsShort) {
            logger.warn(
                "-D{} is {} characters; {}+ random characters is the recommendation. Not enforced.",
                DesignerAuth.SECRET_PROPERTY,
                auth.secret?.length,
                DesignerAuth.MIN_SECRET_LENGTH,
            )
        }

        if (!loopbackOnly && !auth.required) {
            // The loud one. Widening the bind used to hand you a credential by accident; now it
            // hands you an open endpoint by accident, so this names the fix before the mitigation.
            logger.warn(
                "Designer MCP endpoint is bound to {}:{} and requires NO credential. Anything " +
                    "that can route to this machine can read and edit this Designer's project. " +
                    "Set -D{}=<32+ random characters>, or drop -D{} to return to loopback. Pair " +
                    "either with a firewall rule or a forwarded port.",
                boundHost,
                http.address.port,
                DesignerAuth.SECRET_PROPERTY,
                BIND_ADDRESS_PROPERTY,
            )
        } else if (!loopbackOnly) {
            logger.warn(
                "Designer MCP endpoint is bound to {}:{} — reachable beyond this machine. The " +
                    "bearer secret from -D{} is the only thing protecting it; restrict access " +
                    "with a firewall rule or a forwarded port.",
                boundHost,
                http.address.port,
                DesignerAuth.SECRET_PROPERTY,
            )
        } else if (!auth.required) {
            // INFO, not WARN: this is the default for every Designer on earth, and a warning
            // nobody can act on is how the one above gets ignored.
            logger.info(
                "Designer MCP endpoint listening on http://{}:{}{} — no credential required, " +
                    "reachable only from this machine. Note loopback is not per-user: on a shared " +
                    "machine, set -D{} to require a bearer token.",
                boundHost,
                http.address.port,
                path,
                DesignerAuth.SECRET_PROPERTY,
            )
        } else {
            logger.info(
                "Designer MCP endpoint listening on http://{}:{}{} — bearer auth on (-D{}).",
                boundHost,
                http.address.port,
                path,
                DesignerAuth.SECRET_PROPERTY,
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
            // Say where the port came from. Since DEFAULT_PORT exists, naming -Dmcp.designer.port
            // unconditionally would send people hunting for a flag they never passed — and with a
            // fixed default this message fires for the second of any two Designers, so it is no
            // longer the rare case it was written for.
            val source =
                if (System.getProperty(PORT_PROPERTY)?.trim()?.toIntOrNull() != null) "-D$PORT_PROPERTY"
                else "the default"
            logger.warn(
                "Port {} ({}) is already in use, most likely by another Designer on this " +
                    "machine. Fell back to OS-assigned port {}. Anything forwarding or " +
                    "firewalling {} will not reach THIS Designer — see Tools -> MCP Connection " +
                    "Info... for its real address.",
                requestedPort,
                source,
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
                respondUnauthorized(exchange)
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
                    contentType = exchange.requestHeaders.getFirst("Content-Type"),
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

    /**
     * Deliberately not short-circuited by [DevMode]. With no credential required by default, the
     * only thing a dev-mode bypass could still do here is ignore a secret an operator pinned on
     * purpose — which is the wrong way round. Dev mode keeps its other effects.
     */
    private fun isAuthorized(exchange: HttpExchange): Boolean =
        auth.authorize(exchange.requestHeaders.getFirst("Authorization"))

    /**
     * JSON-RPC shaped rather than a bare `{"error":...}` so a client parsing the failure gets a
     * structured error, and carrying the same realm the 8.1 gateway uses.
     */
    private fun respondUnauthorized(exchange: HttpExchange) {
        exchange.responseHeaders.set("WWW-Authenticate", "Bearer realm=\"ignition-mcp\"")
        respond(
            exchange,
            401,
            """{"jsonrpc":"2.0","id":null,"error":{"code":-32001,"message":"Unauthorized"}}""",
            "application/json",
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
        /** Opt in to binding beyond loopback, e.g. `0.0.0.0` for a Designer in a VM. */
        const val BIND_ADDRESS_PROPERTY = "mcp.designer.bindAddress"

        /**
         * Pin the port so it can be forwarded or firewalled. Defaults to OS-assigned, and falls
         * back to it — with a warning — when the pinned port is already taken.
         */
        const val PORT_PROPERTY = "mcp.designer.port"

        /**
         * Where the bridge listens when nothing pins it. Fixed rather than OS-assigned so a client
         * configuration survives a Designer restart — an OS-assigned port went stale exactly as
         * surely as the old per-session secret did, and fixing only the credential would have left
         * every saved client entry pointing at a dead port. Already the number every example in
         * the docs uses. `-Dmcp.designer.port=0` restores OS assignment.
         */
        const val DEFAULT_PORT = 8770
    }
}
