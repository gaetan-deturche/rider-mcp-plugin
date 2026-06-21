package dev.ridermcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Boots the MCP server once a project (solution) is opened. The server itself
 * is application-scoped and shared across open solutions; per-project data is
 * resolved on demand through project services.
 */
class McpServerStartup : ProjectActivity {

    override suspend fun execute(project: Project) {
        // Default port; surface this in Settings later if it needs to be configurable.
        val port = System.getProperty("rider.mcp.port")?.toIntOrNull() ?: DEFAULT_PORT
        ApplicationManager.getApplication()
            .getService(McpServerService::class.java)
            .startIfNeeded(port)
    }

    companion object {
        const val DEFAULT_PORT = 6363
    }
}
