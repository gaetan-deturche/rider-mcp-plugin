package dev.ridermcp

import com.intellij.ide.AppLifecycleListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger

/**
 * Starts the application-scoped MCP server when the IDE has started — a plain
 * (non-suspend) message-bus hook, independent of any open solution. This is the
 * primary auto-start; [McpServerStartup] (postStartupActivity) is kept as a
 * solution-open backup.
 */
class McpAppStartup : AppLifecycleListener {

    private val log = logger<McpAppStartup>()

    override fun appStarted() {
        log.warn("Rider MCP: appStarted (AppLifecycleListener) — starting server")
        try {
            val port = System.getProperty("rider.mcp.port")?.toIntOrNull() ?: McpServerStartup.DEFAULT_PORT
            ApplicationManager.getApplication()
                .getService(McpServerService::class.java)
                .startIfNeeded(port)
        } catch (t: Throwable) {
            log.error("Rider MCP: appStarted startup failed", t)
        }
    }
}
