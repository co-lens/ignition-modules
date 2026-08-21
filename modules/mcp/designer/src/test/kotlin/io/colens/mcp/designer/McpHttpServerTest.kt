package io.colens.mcp.designer

import io.colens.mcp.common.DesignerAuth
import io.colens.mcp.common.McpServer
import io.colens.mcp.common.Tool
import io.colens.mcp.common.ToolRegistry
import io.colens.mcp.common.jsonObject
import io.colens.mcp.common.put
import io.colens.mcp.common.schema
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

/**
 * Drives the real endpoint over real HTTP.
 *
 * [DesignerAuth] is unit-tested on its own in `:common`; what this covers is the plumbing that
 * cannot be — that the `Authorization` header is actually read off the exchange and reaches
 * [DesignerAuth.authorize], and that a refusal carries the status and headers a client needs.
 * Possible because `McpHttpServer` imports no Ignition classes.
 */
private const val SECRET = "0123456789abcdef0123456789abcdef"

private fun ping() = Tool(
    name = "ping",
    title = "Ping",
    description = "Returns nothing.",
    inputSchema = schema(),
    handler = { jsonObject { put("ok", true) } },
)

private fun startServer(auth: DesignerAuth): McpHttpServer {
    val mcp = McpServer(ToolRegistry(listOf(ping())), serverVersion = "0.0.0-test")
    return McpHttpServer(mcp, auth).also { it.start() }
}

private fun post(port: Int, authorization: String?): HttpResponse<String> {
    val builder = HttpRequest.newBuilder(URI("http://127.0.0.1:$port/mcp"))
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
    authorization?.let { builder.header("Authorization", it) }
    return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofString())
}

class McpHttpServerTest : StringSpec({

    "with no secret configured a request with no Authorization header succeeds" {
        val server = startServer(DesignerAuth(null))
        try {
            post(server.port, null).statusCode() shouldBe 200
        } finally {
            server.stop()
        }
    }

    // The compatibility path, end to end: a client configured before this change still sends a
    // secret that no longer exists anywhere.
    "with no secret configured a stale Bearer header is ignored rather than rejected" {
        val server = startServer(DesignerAuth(null))
        try {
            post(server.port, "Bearer 3f2a9c1d4e5b6a7f8091a2b3c4d5e6f7").statusCode() shouldBe 200
        } finally {
            server.stop()
        }
    }

    "with a secret configured the matching header succeeds" {
        val server = startServer(DesignerAuth(SECRET))
        try {
            post(server.port, "Bearer $SECRET").statusCode() shouldBe 200
        } finally {
            server.stop()
        }
    }

    "with a secret configured a missing header is refused with a challenge" {
        val server = startServer(DesignerAuth(SECRET))
        try {
            val res = post(server.port, null)
            res.statusCode() shouldBe 401
            res.headers().firstValue("WWW-Authenticate").orElse("") shouldContain "Bearer"
            res.body() shouldContain "jsonrpc"
        } finally {
            server.stop()
        }
    }

    "with a secret configured a wrong header is refused" {
        val server = startServer(DesignerAuth(SECRET))
        try {
            post(server.port, "Bearer wrong").statusCode() shouldBe 401
        } finally {
            server.stop()
        }
    }

    "a POST without the JSON content type is refused" {
        val server = startServer(DesignerAuth(null))
        try {
            val res = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI("http://127.0.0.1:${server.port}/mcp"))
                    .header("Content-Type", "text/plain")
                    .POST(HttpRequest.BodyPublishers.ofString("""{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            res.statusCode() shouldBe 415
        } finally {
            server.stop()
        }
    }
})
