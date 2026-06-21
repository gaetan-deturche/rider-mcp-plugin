package dev.ridermcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Starts the application-scoped MCP server when a solution opens. The server is
 * shared across open solutions (startIfNeeded is idempotent); the plugin's
 * tools operate on an open solution, so solution-open is the natural trigger.
 */
class McpServerStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        val port = System.getProperty("rider.mcp.port")?.toIntOrNull() ?: DEFAULT_PORT
        ApplicationManager.getApplication()
            .getService(McpServerService::class.java)
            .startIfNeeded(port)
    }

    companion object {
        const val DEFAULT_PORT = 6363
    }
}
