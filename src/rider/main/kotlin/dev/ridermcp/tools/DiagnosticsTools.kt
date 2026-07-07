package dev.ridermcp.tools

import com.intellij.openapi.components.service
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Diagnostic tools backed by the .NET backend over RD ([DebugDataProvider]).
 * This is the sole remaining consumer of the dual-part backend now that the
 * symbol/interface tools (covered by the official Rider MCP) are gone.
 */
object DiagnosticsTools {

    fun register(server: Server) {
        server.addTool(
            name = "backend_status",
            description = "Returns a diagnostic snapshot from the .NET backend " +
                "(solution name, project count, backend version, readiness).",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("solution", buildJsonObject {
                        put("type", "string")
                        put("description", "Target solution name or path; required when several solutions are open in one Rider instance.")
                    })
                },
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution"))
                ?: return@addTool noSolution()

            val status = project.service<DebugDataProvider>().backendStatus()
            val text = if (status == null) {
                "Backend not connected for '${project.name}'."
            } else {
                """
                solution       = ${status.solutionName}
                ready          = ${status.isReady}
                projectCount   = ${status.projectCount}
                backendVersion = ${status.backendVersion}
                """.trimIndent()
            }
            CallToolResult(content = listOf(TextContent(text)))
        }
    }
}
