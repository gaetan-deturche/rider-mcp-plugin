package dev.ridermcp.tools

import com.intellij.openapi.project.ProjectManager
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tools that expose IDE *interface* data: open solutions, tool windows,
 * editor selection, and (via the backend RD model) resolved symbols.
 *
 * These run on a pooled thread when a client calls them; use ReadAction /
 * EDT dispatch where the platform APIs require it.
 */
object InterfaceTools {

    fun register(server: Server) {
        server.addTool(
            name = "list_open_solutions",
            description = "Lists the solutions/projects currently open in Rider.",
            inputSchema = Tool.Input(properties = buildJsonObject {}),
        ) { _ ->
            val names = ProjectManager.getInstance().openProjects.map { it.name }
            CallToolResult(
                content = listOf(TextContent(names.joinToString("\n").ifEmpty { "(none)" })),
            )
        }

        server.addTool(
            name = "find_symbols",
            description = "Resolves symbols matching a query via the .NET backend " +
                "(class/method/property names). Returns FQN, kind, and location.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("query", buildJsonObject {
                        put("type", "string")
                        put("description", "Symbol name or pattern to resolve.")
                    })
                },
                required = listOf("query"),
            ),
        ) { request ->
            val query = (request.arguments["query"] as? JsonObject)?.toString()
                ?: request.arguments["query"]?.toString().orEmpty()
            // TODO: route to RiderMcpModel.findSymbols over RD (see DebugDataProvider).
            CallToolResult(
                content = listOf(TextContent("find_symbols('$query') — backend wiring pending")),
            )
        }
    }

    @Suppress("unused")
    private fun sampleArray() = buildJsonArray { add("placeholder") }
}
