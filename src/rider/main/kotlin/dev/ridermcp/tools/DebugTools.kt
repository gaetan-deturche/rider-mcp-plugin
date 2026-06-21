package dev.ridermcp.tools

import com.intellij.openapi.project.ProjectManager
import com.intellij.xdebugger.XDebuggerManager
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import kotlinx.serialization.json.buildJsonObject

/**
 * MCP tools that expose *debug* data: active debug sessions, current stack /
 * breakpoints, and backend diagnostic status.
 */
object DebugTools {

    fun register(server: Server) {
        server.addTool(
            name = "list_debug_sessions",
            description = "Lists active debug sessions across open solutions, " +
                "including the currently suspended frame if any.",
            inputSchema = Tool.Input(properties = buildJsonObject {}),
        ) { _ ->
            val report = buildString {
                for (project in ProjectManager.getInstance().openProjects) {
                    val sessions = XDebuggerManager.getInstance(project).debugSessions
                    appendLine("Solution: ${project.name} — ${sessions.size} session(s)")
                    for (s in sessions) {
                        val suspended = if (s.isSuspended) "suspended" else "running"
                        appendLine("  • ${s.sessionName} [$suspended]")
                    }
                }
            }.ifBlank { "(no active debug sessions)" }
            CallToolResult(content = listOf(TextContent(report)))
        }

        server.addTool(
            name = "backend_status",
            description = "Returns a diagnostic snapshot from the .NET backend " +
                "(solution name, project count, backend version, readiness).",
            inputSchema = Tool.Input(properties = buildJsonObject {}),
        ) { _ ->
            // TODO: call RiderMcpModel.getBackendStatus over RD via DebugDataProvider.
            CallToolResult(content = listOf(TextContent("backend_status — RD wiring pending")))
        }
    }
}
