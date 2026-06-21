package dev.ridermcp.tools

import com.intellij.openapi.components.service
import com.intellij.openapi.project.ProjectManager
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tools that expose IDE *interface* data: open solutions and symbols
 * resolved by the .NET backend over RD ([DebugDataProvider]).
 */
object InterfaceTools {

    fun register(server: Server) {
        server.addTool(
            name = "list_open_solutions",
            description = "Lists the solutions/projects currently open in Rider.",
            inputSchema = Tool.Input(properties = buildJsonObject {}),
        ) { _ ->
            val names = ProjectManager.getInstance().openProjects.map { it.name }
            CallToolResult(content = listOf(TextContent(names.joinToString("\n").ifEmpty { "(none)" })))
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
                    put("solution", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional: target solution name when several are open.")
                    })
                },
                required = listOf("query"),
            ),
        ) { request ->
            val query = request.arguments.stringArg("query").orEmpty()
            if (query.isBlank()) {
                return@addTool CallToolResult(content = listOf(TextContent("'query' is required.")))
            }
            val project = resolveProject(request.arguments.stringArg("solution"))
                ?: return@addTool CallToolResult(content = listOf(TextContent("No matching open solution.")))

            val symbols = project.service<DebugDataProvider>().findSymbols(query)
            val text = symbols.joinToString("\n") { s ->
                "${s.kind} ${s.fqn}  (${s.file}:${s.line})  ${s.signature}"
            }.ifEmpty { "No symbols match '$query' in '${project.name}'." }
            CallToolResult(content = listOf(TextContent(text)))
        }
    }
}
