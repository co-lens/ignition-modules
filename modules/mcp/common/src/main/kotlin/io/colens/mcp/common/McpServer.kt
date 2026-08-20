package io.colens.mcp.common

import com.inductiveautomation.ignition.common.gson.JsonElement
import com.inductiveautomation.ignition.common.gson.JsonObject
import java.net.URI

/** An inbound HTTP request, reduced to only what the MCP transport cares about. */
data class McpHttpRequest(
    val method: String,
    val body: String? = null,
    val origin: String? = null,
)

data class McpHttpResult(
    val status: Int,
    val body: String,
    val contentType: String? = "application/json",
    val headers: Map<String, String> = emptyMap(),
)

object JsonRpcErrors {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}

/**
 * A complete MCP server over the Streamable HTTP transport, in its simplest compliant form:
 * stateless, POST-only, always answering `application/json`.
 *
 * The spec permits this. A server may answer any request with a single JSON object instead of
 * an SSE stream, and sessions are optional (and removed outright in the 2026-07-28 revision).
 * So there is no session store, no SSE, no resumability and no background streams to manage —
 * which is why this class is short enough to hold in your head.
 *
 * GET and DELETE get `405`, which is exactly what a modern-revision server is told to answer
 * when an older client probes for the standalone SSE stream or tries to end a session.
 */
class McpServer(
    private val tools: ToolRegistry,
    private val serverVersion: String,
    private val serverName: String = Constants.SERVER_NAME,
    private val instructions: String? = null,
    /** Origins permitted in addition to loopback. Loopback and absent Origin are always fine. */
    private val extraAllowedOrigins: Set<String> = emptySet(),
    /**
     * Accept every Origin. Set from [io.colens.mcp.common.DevMode] by the hooks rather than read
     * here, so this class stays free of system properties and its tests stay hermetic.
     */
    private val allowAnyOrigin: Boolean = false,
) {

    fun handle(request: McpHttpRequest): McpHttpResult {
        request.origin?.let { origin ->
            if (!isAllowedOrigin(origin)) {
                return errorResult(403, JsonRpcErrors.INVALID_REQUEST, "Origin not allowed: $origin")
            }
        }

        return when (request.method.uppercase()) {
            "POST" -> handlePost(request.body)
            // Not an error the client needs to recover from — just tell it what we support.
            "GET", "DELETE" -> McpHttpResult(
                status = 405,
                body = errorEnvelope(JsonRpcErrors.INVALID_REQUEST, "Only POST is supported"),
                headers = mapOf("Allow" to "POST"),
            )
            else -> McpHttpResult(
                status = 405,
                body = errorEnvelope(JsonRpcErrors.INVALID_REQUEST, "Unsupported method"),
                headers = mapOf("Allow" to "POST"),
            )
        }
    }

    private fun handlePost(body: String?): McpHttpResult {
        if (body.isNullOrBlank()) {
            return errorResult(400, JsonRpcErrors.INVALID_REQUEST, "Empty request body")
        }

        val parsed = try {
            McpJson.parse(body)
        } catch (e: Exception) {
            return errorResult(400, JsonRpcErrors.PARSE_ERROR, "Invalid JSON: ${e.message}")
        }

        if (parsed.isJsonArray) {
            // JSON-RPC batching was removed from MCP in 2025-06-18.
            return errorResult(400, JsonRpcErrors.INVALID_REQUEST, "Batch requests are not supported")
        }
        if (!parsed.isJsonObject) {
            return errorResult(400, JsonRpcErrors.INVALID_REQUEST, "Request must be a JSON object")
        }

        val message = parsed.asJsonObject
        val method = message.optString("method")
            ?: return errorResult(400, JsonRpcErrors.INVALID_REQUEST, "Missing 'method'")
        val id = message.get("id")

        // No id means a notification: acknowledge and produce nothing.
        if (id == null || id.isJsonNull) {
            return McpHttpResult(status = 202, body = "", contentType = null)
        }

        val params = message.optObject("params") ?: JsonObject()

        return try {
            when (method) {
                "initialize" -> success(id, initializeResult(params))
                "ping" -> success(id, JsonObject())
                "tools/list" -> success(id, jsonObject { put("tools", tools.toJsonArray()) })
                "tools/call" -> success(id, callTool(params))
                else -> failure(id, JsonRpcErrors.METHOD_NOT_FOUND, "Unknown method: $method")
            }
        } catch (e: McpArgumentException) {
            failure(id, JsonRpcErrors.INVALID_PARAMS, e.message ?: "Invalid parameters")
        } catch (e: Exception) {
            failure(id, JsonRpcErrors.INTERNAL_ERROR, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun initializeResult(params: JsonObject): JsonObject {
        val requested = params.optString("protocolVersion")
        val negotiated = if (requested != null && requested in Constants.SUPPORTED_PROTOCOL_VERSIONS) {
            requested
        } else {
            Constants.DEFAULT_PROTOCOL_VERSION
        }

        return jsonObject {
            put("protocolVersion", negotiated)
            put("capabilities", jsonObject {
                put("tools", jsonObject { put("listChanged", false) })
            })
            put("serverInfo", jsonObject {
                put("name", serverName)
                put("title", "Ignition")
                put("version", serverVersion)
            })
            instructions?.let { put("instructions", it) }
        }
    }

    private fun callTool(params: JsonObject): JsonObject {
        val name = params.requireString("name")
        val tool = tools[name]
            ?: throw McpArgumentException("Unknown tool: $name")
        val arguments = params.optObject("arguments") ?: JsonObject()

        return try {
            toolResult(tool.handler(arguments), isError = false)
        } catch (e: Throwable) {
            // A failing tool is a *result*, not a protocol error — the model needs to see the
            // message so it can correct the call rather than the request being rejected.
            val detail = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
            toolResult(jsonObject { put("error", detail) }, isError = true)
        }
    }

    private fun toolResult(content: JsonElement, isError: Boolean): JsonObject {
        // `structuredContent` must be an object; wrap anything else so tools stay free to
        // return arrays or scalars.
        val structured = if (content.isJsonObject) {
            content.asJsonObject
        } else {
            jsonObject { put("result", content) }
        }

        return jsonObject {
            put("content", jsonArray {
                add(jsonObject {
                    put("type", "text")
                    put("text", McpJson.toPrettyString(structured))
                })
            })
            put("structuredContent", structured)
            put("isError", isError)
        }
    }

    // -----------------------------------------------------------------------
    // Envelopes
    // -----------------------------------------------------------------------

    private fun success(id: JsonElement, result: JsonElement) = McpHttpResult(
        status = 200,
        body = McpJson.toString(jsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }),
    )

    private fun failure(id: JsonElement, code: Int, message: String) = McpHttpResult(
        status = 200,
        body = McpJson.toString(jsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", jsonObject {
                put("code", code)
                put("message", message)
            })
        }),
    )

    private fun errorResult(status: Int, code: Int, message: String) =
        McpHttpResult(status = status, body = errorEnvelope(code, message))

    private fun errorEnvelope(code: Int, message: String): String =
        McpJson.toString(jsonObject {
            put("jsonrpc", "2.0")
            put("id", null as JsonElement?)
            put("error", jsonObject {
                put("code", code)
                put("message", message)
            })
        })

    private fun isAllowedOrigin(origin: String): Boolean {
        if (allowAnyOrigin) return true
        if (origin in extraAllowedOrigins) return true
        val host = try {
            URI(origin).host
        } catch (e: Exception) {
            null
        } ?: return false
        return host.equals("localhost", ignoreCase = true) ||
            host == "127.0.0.1" ||
            host == "::1" ||
            host == "[::1]"
    }
}
