package dev.ridermcp.server

import com.intellij.openapi.diagnostic.logger
import dev.ridermcp.tools.DiagnosticsTools
import dev.ridermcp.tools.WindowContentTools
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcp

/**
 * Embeds a Ktor server that speaks MCP over SSE. MCP clients (e.g. Claude
 * Code, IDE agents) connect to:  http://127.0.0.1:<port>/sse
 *
 * Tool registration is delegated to [WindowContentTools] (tool-window /
 * console content) and [DiagnosticsTools] (backend status); both read live
 * IDE/backend state when a tool is invoked.
 */
class McpHttpServer(private val port: Int) {

    private val log = logger<McpHttpServer>()
    private var engine: EmbeddedServer<*, *>? = null

    fun start() {
        engine = embeddedServer(CIO, host = "127.0.0.1", port = port) {
            install(SSE)
            // The MCP Kotlin SDK wires the /sse + /message routes for us; each
            // connecting client gets its own Server instance via the factory.
            mcp { buildServer() }
        }.also { it.start(wait = false) }
        log.info("MCP SSE endpoint listening on http://127.0.0.1:$port/sse")
    }

    private fun buildServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "rider-mcp", version = "0.1.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
            ),
        )
        WindowContentTools.register(server)
        DiagnosticsTools.register(server)
        return server
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        engine = null
    }
}
