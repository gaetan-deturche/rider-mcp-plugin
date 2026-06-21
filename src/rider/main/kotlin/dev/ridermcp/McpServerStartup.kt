package dev.ridermcp

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity

/**
 * Starts the application-scoped MCP server when a solution opens. Logs at entry
 * (LOG.warn) so idea.log shows whether the activity fired at all, and catches
 * everything so a failure is visible instead of silently aborting.
 */
class McpServerStartup : ProjectActivity {

    private val log = logger<McpServerStartup>()

    override suspend fun execute(project: Project) {
        log.warn("Rider MCP: startup activity fired for project '${project.name}'")
        try {
            val port = System.getProperty("rider.mcp.port")?.toIntOrNull() ?: DEFAULT_PORT
            ApplicationManager.getApplication()
                .getService(McpServerService::class.java)
                .startIfNeeded(port)
        } catch (t: Throwable) {
            log.error("Rider MCP: startup activity failed", t)
        }
    }

    companion object {
        const val DEFAULT_PORT = 6363
    }
}
