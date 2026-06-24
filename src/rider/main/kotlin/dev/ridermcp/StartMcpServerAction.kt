package dev.ridermcp

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Tools-menu action to start the MCP server on demand — a reliable fallback
 * that doesn't depend on the startup activity firing, and a quick way to verify
 * the server comes up in-IDE. Reports the outcome as a balloon notification.
 */
class StartMcpServerAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val port = System.getProperty("rider.mcp.port")?.toIntOrNull() ?: McpServerStartup.DEFAULT_PORT
        val service = com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(McpServerService::class.java)
        val ok = service.startIfNeeded(port)
        val msg = if (ok) "MCP server running at http://127.0.0.1:$port/stream"
                  else "MCP server FAILED to start (see idea.log)"
        notify(e, msg, if (ok) NotificationType.INFORMATION else NotificationType.ERROR)
    }

    private fun notify(e: AnActionEvent, content: String, type: NotificationType) {
        // Never let a notification problem crash the action — fall back to the log.
        runCatching {
            val group = NotificationGroupManager.getInstance().getNotificationGroup("RiderMcp")
            group?.createNotification(content, type)?.notify(e.project)
        }.onFailure {
            com.intellij.openapi.diagnostic.logger<StartMcpServerAction>().warn("Rider MCP: $content")
        }
    }
}
