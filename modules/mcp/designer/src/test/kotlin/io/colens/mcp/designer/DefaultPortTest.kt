package io.colens.mcp.designer

import io.colens.mcp.common.DesignerAuth
import io.colens.mcp.common.McpServer
import io.colens.mcp.common.ToolRegistry
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe

/** The port half of the staleness fix: a client config is only durable if the URL is too. */
class DefaultPortTest : StringSpec({

    fun server() = McpHttpServer(
        McpServer(ToolRegistry(emptyList()), serverVersion = "0.0.0-test"),
        DesignerAuth(null),
    )

    "with nothing pinned the bridge lands on the fixed default" {
        val s = server()
        try {
            s.start() shouldBe McpHttpServer.DEFAULT_PORT
            s.port shouldBe 8770
        } finally {
            s.stop()
        }
    }

    // The collision path that makes a fixed default safe: a second Designer still starts.
    "a second bridge falls back instead of dying" {
        val first = server()
        val second = server()
        try {
            first.start() shouldBe McpHttpServer.DEFAULT_PORT
            val fallback = second.start()
            fallback shouldNotBe McpHttpServer.DEFAULT_PORT
            (fallback > 0) shouldBe true
        } finally {
            second.stop()
            first.stop()
        }
    }
})
