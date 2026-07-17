package dev.ridermcp.server

import com.intellij.openapi.diagnostic.logger
import dev.ridermcp.tools.BuildTools
import dev.ridermcp.tools.DebuggerTools
import dev.ridermcp.tools.DiagnosticsTools
import dev.ridermcp.tools.RunConfigTools
import dev.ridermcp.tools.WindowContentTools
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.EmbeddedServer
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.mcpStreamableHttp
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import io.modelcontextprotocol.kotlin.sdk.types.ServerCapabilities

/**
 * Embeds a Ktor server that speaks MCP over Streamable HTTP. MCP clients (e.g.
 * Claude Code, IDE agents) connect to:  http://127.0.0.1:<port>/stream
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
            // Mount the MCP Streamable HTTP transport at /stream: a single endpoint
            // serving GET/POST/DELETE with Mcp-Session-Id session tracking. The
            // helper installs ContentNegotiation (McpJson) and SSE itself, so we
            // must NOT install them here. DNS-rebinding protection is on by default
            // and allows localhost/127.0.0.1/[::1] (the port is stripped before the
            // check), which suits this loopback-only server.
            mcpStreamableHttp(path = "/stream") { buildServer() }
        }.also { it.start(wait = false) }
        log.info("MCP Streamable HTTP endpoint listening on http://127.0.0.1:$port/stream")
    }

    private fun buildServer(): Server {
        val server = Server(
            serverInfo = Implementation(name = "rider-mcp", version = "0.14.1"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true),
                ),
            ),
        )
        WindowContentTools.register(server)
        DebuggerTools.register(server)
        DiagnosticsTools.register(server)
        BuildTools.register(server)
        RunConfigTools.register(server)
        return server
    }

    fun stop() {
        engine?.stop(gracePeriodMillis = 500, timeoutMillis = 1500)
        engine = null
    }
}
