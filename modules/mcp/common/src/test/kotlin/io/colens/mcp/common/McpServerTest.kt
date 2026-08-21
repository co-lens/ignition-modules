package io.colens.mcp.common

import com.inductiveautomation.ignition.common.gson.JsonObject
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain

/**
 * Protocol-level tests. These need no Ignition runtime — that separation is the whole point of
 * keeping the MCP core in `:common`.
 */
class McpServerTest : StringSpec({

    fun echoTool() = Tool(
        name = "echo",
        title = "Echo",
        description = "Returns its input.",
        inputSchema = schema { string("value", "Value to echo", required = true) },
        handler = { args -> jsonObject { put("echoed", args.requireString("value")) } },
    )

    fun boomTool() = Tool(
        name = "boom",
        title = "Boom",
        description = "Always fails.",
        inputSchema = schema(),
        handler = { throw IllegalStateException("kaboom") },
    )

    fun writeTool() = Tool(
        name = "mutate",
        title = "Mutate",
        description = "Changes something.",
        inputSchema = schema(),
        readOnly = false,
        destructive = true,
        handler = { JsonObject() },
    )

    fun server(vararg tools: Tool) =
        McpServer(ToolRegistry(tools.toList()), serverVersion = "0.0.0-test")

    fun post(server: McpServer, json: String, origin: String? = null) =
        server.handle(McpHttpRequest("POST", json, origin, contentType = "application/json"))

    fun bodyOf(result: McpHttpResult): JsonObject = McpJson.parse(result.body).asJsonObject

    // -- content type -------------------------------------------------------

    // Not pedantry: requiring this is what forces a browser through CORS preflight, which the 405
    // on non-POST already kills. Without it a page can reach the server with a text/plain simple
    // request, and under dev mode the Origin allowlist is off.
    "a POST without a content type is refused" {
        val res = server(echoTool()).handle(McpHttpRequest("POST", """{"jsonrpc":"2.0","id":1,"method":"ping"}"""))
        res.status shouldBe 415
    }

    "a POST claiming text/plain is refused" {
        val res = server(echoTool()).handle(
            McpHttpRequest("POST", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", contentType = "text/plain")
        )
        res.status shouldBe 415
    }

    "a charset parameter is tolerated" {
        val res = server(echoTool()).handle(
            McpHttpRequest(
                "POST",
                """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
                contentType = "application/json; charset=utf-8",
            )
        )
        res.status shouldBe 200
    }

    "the content type is matched case-insensitively" {
        val res = server(echoTool()).handle(
            McpHttpRequest("POST", """{"jsonrpc":"2.0","id":1,"method":"ping"}""", contentType = "APPLICATION/JSON")
        )
        res.status shouldBe 200
    }

    // -- lifecycle ----------------------------------------------------------

    "initialize echoes a supported protocol version and advertises tools" {
        val res = post(
            server(echoTool()),
            """{"jsonrpc":"2.0","id":1,"method":"initialize",
                "params":{"protocolVersion":"2025-06-18","capabilities":{},
                          "clientInfo":{"name":"test","version":"1"}}}""",
        )

        res.status shouldBe 200
        res.contentType shouldBe "application/json"

        val result = bodyOf(res).getAsJsonObject("result")
        result.optString("protocolVersion") shouldBe "2025-06-18"
        result.getAsJsonObject("capabilities").has("tools").shouldBeTrue()
        result.getAsJsonObject("serverInfo").optString("name") shouldBe "ignition-mcp"
        result.getAsJsonObject("serverInfo").optString("version") shouldBe "0.0.0-test"
    }

    "initialize falls back to our default for an unknown protocol version" {
        val res = post(
            server(),
            """{"jsonrpc":"2.0","id":1,"method":"initialize",
                "params":{"protocolVersion":"1999-01-01"}}""",
        )

        bodyOf(res).getAsJsonObject("result").optString("protocolVersion") shouldBe "2025-06-18"
    }

    "initialize tolerates a missing params object" {
        val res = post(server(), """{"jsonrpc":"2.0","id":1,"method":"initialize"}""")

        res.status shouldBe 200
        bodyOf(res).getAsJsonObject("result").optString("protocolVersion") shouldBe "2025-06-18"
    }

    "a notification is accepted with 202 and an empty body" {
        val res = post(server(), """{"jsonrpc":"2.0","method":"notifications/initialized"}""")

        res.status shouldBe 202
        res.body shouldBe ""
        res.contentType shouldBe null
    }

    "ping returns an empty result" {
        val res = post(server(), """{"jsonrpc":"2.0","id":7,"method":"ping"}""")

        res.status shouldBe 200
        bodyOf(res).getAsJsonObject("result").size() shouldBe 0
    }

    // -- tools/list ---------------------------------------------------------

    "tools/list reports schema and annotations" {
        val res = post(server(echoTool(), writeTool()), """{"jsonrpc":"2.0","id":2,"method":"tools/list"}""")

        val listed = bodyOf(res).getAsJsonObject("result").getAsJsonArray("tools")
        listed.map { it.asJsonObject.optString("name") } shouldContainExactly listOf("echo", "mutate")

        val echo = listed[0].asJsonObject
        echo.optString("title") shouldBe "Echo"
        echo.getAsJsonObject("inputSchema").optString("type") shouldBe "object"
        echo.getAsJsonObject("inputSchema")
            .getAsJsonArray("required").map { it.asString } shouldContainExactly listOf("value")
        echo.getAsJsonObject("annotations").optBoolean("readOnlyHint", false).shouldBeTrue()

        val mutate = listed[1].asJsonObject.getAsJsonObject("annotations")
        mutate.optBoolean("readOnlyHint", true) shouldBe false
        mutate.optBoolean("destructiveHint", false).shouldBeTrue()
    }

    "a read-only view hides mutating tools entirely" {
        val registry = ToolRegistry(listOf(echoTool(), writeTool()))
        val readOnly = McpServer(registry.readOnlyView(), serverVersion = "0.0.0-test")

        val listed = bodyOf(post(readOnly, """{"jsonrpc":"2.0","id":1,"method":"tools/list"}"""))
            .getAsJsonObject("result").getAsJsonArray("tools")
        listed.map { it.asJsonObject.optString("name") } shouldContainExactly listOf("echo")

        // ...and calling the hidden tool by name fails rather than silently working.
        val call = post(readOnly, """{"jsonrpc":"2.0","id":2,"method":"tools/call",
                                      "params":{"name":"mutate","arguments":{}}}""")
        bodyOf(call).getAsJsonObject("error").optInt("code", 0) shouldBe JsonRpcErrors.INVALID_PARAMS
    }

    // -- tools/call ---------------------------------------------------------

    "tools/call returns both text content and structuredContent" {
        val res = post(
            server(echoTool()),
            """{"jsonrpc":"2.0","id":3,"method":"tools/call",
                "params":{"name":"echo","arguments":{"value":"hi"}}}""",
        )

        res.status shouldBe 200
        val result = bodyOf(res).getAsJsonObject("result")
        result.optBoolean("isError", true) shouldBe false
        result.getAsJsonObject("structuredContent").optString("echoed") shouldBe "hi"

        val text = result.getAsJsonArray("content")[0].asJsonObject
        text.optString("type") shouldBe "text"
        text.optString("text").shouldNotBeNull() shouldContain "hi"
    }

    "a throwing tool becomes an isError result, not a protocol error" {
        val res = post(
            server(boomTool()),
            """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"boom"}}""",
        )

        res.status shouldBe 200
        val body = bodyOf(res)
        body.has("error") shouldBe false

        val result = body.getAsJsonObject("result")
        result.optBoolean("isError", false).shouldBeTrue()
        result.getAsJsonObject("structuredContent").optString("error") shouldBe "kaboom"
    }

    "a missing required argument is reported through the tool result" {
        val res = post(
            server(echoTool()),
            """{"jsonrpc":"2.0","id":5,"method":"tools/call",
                "params":{"name":"echo","arguments":{}}}""",
        )

        val result = bodyOf(res).getAsJsonObject("result")
        result.optBoolean("isError", false).shouldBeTrue()
        result.getAsJsonObject("structuredContent").optString("error") shouldContain "value"
    }

    "calling an unknown tool is an invalid-params error" {
        val res = post(
            server(echoTool()),
            """{"jsonrpc":"2.0","id":6,"method":"tools/call","params":{"name":"nope"}}""",
        )

        bodyOf(res).getAsJsonObject("error").optInt("code", 0) shouldBe JsonRpcErrors.INVALID_PARAMS
    }

    // -- protocol errors ----------------------------------------------------

    "an unknown method is -32601" {
        val res = post(server(), """{"jsonrpc":"2.0","id":9,"method":"resources/list"}""")

        res.status shouldBe 200
        bodyOf(res).getAsJsonObject("error").optInt("code", 0) shouldBe JsonRpcErrors.METHOD_NOT_FOUND
    }

    "malformed JSON is -32700 with a 400" {
        val res = post(server(), "{ not json")

        res.status shouldBe 400
        bodyOf(res).getAsJsonObject("error").optInt("code", 0) shouldBe JsonRpcErrors.PARSE_ERROR
    }

    "an empty body is rejected" {
        post(server(), "").status shouldBe 400
    }

    "batch requests are rejected" {
        val res = post(server(), """[{"jsonrpc":"2.0","id":1,"method":"ping"}]""")

        res.status shouldBe 400
        bodyOf(res).getAsJsonObject("error").optInt("code", 0) shouldBe JsonRpcErrors.INVALID_REQUEST
    }

    // -- transport ----------------------------------------------------------

    "GET and DELETE are 405 with an Allow header" {
        listOf("GET", "DELETE").forEach { verb ->
            val res = server().handle(McpHttpRequest(verb))
            res.status shouldBe 405
            res.headers["Allow"] shouldBe "POST"
        }
    }

    "a loopback Origin is allowed" {
        listOf("http://localhost:6274", "http://127.0.0.1:8080", "http://[::1]:1234").forEach { origin ->
            post(server(), """{"jsonrpc":"2.0","id":1,"method":"ping"}""", origin).status shouldBe 200
        }
    }

    "a remote Origin is 403" {
        val res = post(server(), """{"jsonrpc":"2.0","id":1,"method":"ping"}""", "https://evil.example.com")

        res.status shouldBe 403
        bodyOf(res).getAsJsonObject("error").optInt("code", 0) shouldBe JsonRpcErrors.INVALID_REQUEST
    }

    "an explicitly allowed Origin passes" {
        val server = McpServer(
            ToolRegistry(),
            serverVersion = "0.0.0-test",
            extraAllowedOrigins = setOf("https://ops.example.com"),
        )

        server.handle(
            McpHttpRequest(
                "POST",
                """{"jsonrpc":"2.0","id":1,"method":"ping"}""",
                "https://ops.example.com",
                contentType = "application/json",
            )
        ).status shouldBe 200
    }

    // -- registry -----------------------------------------------------------

    "duplicate tool names are rejected at construction" {
        val thrown = runCatching { ToolRegistry(listOf(echoTool(), echoTool())) }.exceptionOrNull()
        thrown.shouldNotBeNull()
        thrown.message.shouldNotBeNull() shouldContain "echo"
    }
})
