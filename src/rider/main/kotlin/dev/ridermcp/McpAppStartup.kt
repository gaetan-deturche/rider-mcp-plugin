package dev.ridermcp

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.updateSettings.impl.PluginUpdateSourceService
import com.intellij.openapi.updateSettings.impl.UpdateSettings

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
        // Self-register the plugin repository so Rider offers updates without a manual setup step.
        runCatching {
            val hosts = UpdateSettings.getInstance().storedPluginHosts
            if (UPDATE_REPOSITORY !in hosts) hosts.add(UPDATE_REPOSITORY)
        }.onFailure { log.warn("Rider MCP: could not register the update repository", it) }
        // A disk-installed copy has no "Updates from" source, and 2026.3 only updates from that source. Never override one.
        runCatching {
            if (PluginUpdateSourceService.isFunctionalitySupported()) {
                val sources = PluginUpdateSourceService.getInstance()
                val descriptor = PluginManagerCore.getPlugin(PluginId.getId("dev.ridermcp"))
                if (descriptor != null && sources.isMissingPluginUpdateSource(descriptor))
                    sources.setPluginUpdateSourceId(descriptor.pluginId, sources.createCustomRepositoryPluginUpdateSourceId(UPDATE_REPOSITORY))
            }
        }.onFailure { log.warn("Rider MCP: could not set the plugin update source", it) }
    }

    companion object {
        const val UPDATE_REPOSITORY = "https://raw.githubusercontent.com/gaetan-deturche/rider-mcp-plugin/main/updatePlugins.xml"
    }
}
