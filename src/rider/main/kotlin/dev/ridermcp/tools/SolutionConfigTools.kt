package dev.ridermcp.tools

import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.jetbrains.rd.ide.model.RdConfigurationAndPlatform
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.WeakHashMap

/**
 * MCP tools for the solution configuration/platform selector (the toolbar
 * "Development Editor | Win64" pair). For Unreal solutions the build TARGET is
 * encoded in the configuration name ("Development Editor", "Shipping Client",
 * ...), so configuration + platform covers the whole VS-style triple.
 */
object SolutionConfigTools {

    fun register(server: Server) {
        server.addTool(
            name = "list_solution_configurations",
            description = "Lists the solution configuration|platform pairs (e.g. 'Development Editor | " +
                "Win64') and marks the active one. For Unreal the target is part of the configuration " +
                "name. Use set_solution_configuration to switch.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("solution", buildJsonObject {
                        put("type", "string")
                        put("description", "Target solution name or path; required when several solutions are open in one Rider instance.")
                    })
                },
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            val mgr = SolutionConfigurationManager.tryGetInstance(project)
                ?: return@addTool text("No solution-configuration support in '${project.name}' (solution still loading?).")
            val active = mgr.activeConfigurationAndPlatform
            val all = mgr.solutionConfigurationsAndPlatforms
            if (all.isEmpty()) return@addTool text("No solution configurations in '${project.name}'.")
            val lines = all.joinToString("\n") { cp ->
                val mark = if (cp == active) "  *active*" else ""
                "  - ${cp.configuration} | ${cp.platform}$mark"
            }
            text("Solution configurations in '${project.name}':\n$lines")
        }

        server.addTool(
            name = "set_solution_configuration",
            description = "Sets the active solution configuration and platform (the toolbar selector, e.g. " +
                "configuration 'Development Editor' + platform 'Win64'). Matching is case-insensitive; " +
                "omit 'platform' when the configuration name is unambiguous. For Unreal the target is " +
                "chosen via the configuration name. See list_solution_configurations for valid pairs. Rider " +
                "shows a sticky 'set by MCP' notification with a Revert action, and a later run_configuration " +
                "launch (restore=true, default) puts the user's own configuration back when its session ends. " +
                "Prefer run_configuration(configuration=..., platform=...) for a launch.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("configuration", buildJsonObject {
                        put("type", "string")
                        put("description", "Configuration name, e.g. 'Development Editor' or 'Shipping'.")
                    })
                    put("platform", buildJsonObject {
                        put("type", "string")
                        put("description", "Platform name, e.g. 'Win64'. Optional when the configuration exists for only one platform.")
                    })
                    put("solution", buildJsonObject {
                        put("type", "string")
                        put("description", "Target solution name or path; required when several solutions are open in one Rider instance.")
                    })
                },
                required = listOf("configuration"),
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            val mgr = SolutionConfigurationManager.tryGetInstance(project)
                ?: return@addTool text("No solution-configuration support in '${project.name}' (solution still loading?).")
            val wantCfg = request.arguments.stringArg("configuration")?.trim().orEmpty()
            if (wantCfg.isEmpty()) return@addTool text("'configuration' is required.")
            val wantPlat = request.arguments.stringArg("platform")?.trim().orEmpty()

            val target = when (val m = match(mgr, wantCfg, wantPlat)) {
                is Match.Found -> m.target
                is Match.Error -> return@addTool text(m.message)
            }
            val previous = mgr.activeConfigurationAndPlatform
            if (target == previous) return@addTool text("Already active: ${label(target)}.")
            applyFromMcp(project, mgr, target)
            text(
                "[SOLUTION CONFIG set] ${label(target)}  (was ${label(previous)}) in '${project.name}'. " +
                    "Rider shows a 'set by MCP' notification with a Revert action; a run_configuration " +
                    "launch restores the user's configuration when its session ends."
            )
        }
    }

    sealed class Match {
        class Found(val target: RdConfigurationAndPlatform) : Match()
        class Error(val message: String) : Match()
    }

    /** Case-insensitive configuration|platform lookup; platform may be blank when the configuration is unambiguous. */
    fun match(mgr: SolutionConfigurationManager, wantCfg: String, wantPlat: String): Match {
        val all = mgr.solutionConfigurationsAndPlatforms
        val matches = all.filter {
            it.configuration.equals(wantCfg, ignoreCase = true) &&
                (wantPlat.isEmpty() || it.platform.equals(wantPlat, ignoreCase = true))
        }
        return when {
            matches.size == 1 -> Match.Found(matches[0])
            matches.isEmpty() -> Match.Error(
                "No solution configuration matches \"$wantCfg\"" +
                    (if (wantPlat.isEmpty()) "" else " | \"$wantPlat\"") + ". Available:\n" +
                    all.joinToString("\n") { "  - ${label(it)}" }
            )
            else -> Match.Error(
                "\"$wantCfg\" exists for several platforms — pass 'platform' to pick one:\n" +
                    matches.joinToString("\n") { "  - ${label(it)}" }
            )
        }
    }

    fun label(cp: RdConfigurationAndPlatform?): String = cp?.let { "${it.configuration} | ${it.platform}" } ?: "<none>"

    // -- MCP override tracking ------------------------------------------------

    /**
     * An MCP-made change to a project's solution configuration. [baseline] is the
     * user's configuration from before MCP's FIRST change, so restoring after
     * several MCP changes (set_solution_configuration, then run_configuration)
     * still returns to what the user had.
     */
    private class Override(val baseline: RdConfigurationAndPlatform?, var current: RdConfigurationAndPlatform, var notification: Notification?)

    private val overrides: MutableMap<Project, Override> = Collections.synchronizedMap(WeakHashMap())

    /** Sets [target] as active on behalf of MCP, records the override and shows the sticky notice. */
    suspend fun applyFromMcp(project: Project, mgr: SolutionConfigurationManager, target: RdConfigurationAndPlatform) {
        val previous = mgr.activeConfigurationAndPlatform
        withContext(Dispatchers.EDT) {
            mgr.activeConfigurationAndPlatform = target
            val prior = overrides[project]
            // Only an override the user hasn't touched since keeps its baseline.
            val baseline = if (prior != null && prior.current == previous) prior.baseline else previous
            prior?.notification?.expire()
            if (baseline == target) {
                overrides.remove(project) // MCP put the user's own config back: nothing to track
                return@withContext
            }
            val ov = Override(baseline, target, null)
            ov.notification = NotificationGroupManager.getInstance().getNotificationGroup("RiderMcpConfig")
                .createNotification(
                    "Solution configuration set by MCP",
                    "${label(target)} (your configuration: ${label(baseline)})",
                    NotificationType.INFORMATION,
                )
                .addAction(NotificationAction.createSimpleExpiring("Revert to ${label(baseline)}") { restoreBaseline(project) })
            overrides[project] = ov
            ov.notification?.notify(project)
        }
    }

    /**
     * Puts back the user's configuration if the toolbar still shows what MCP set.
     * Call on the EDT. Returns what was done, or null when there was nothing to restore
     * (no override, or the user changed the configuration themselves since).
     */
    fun restoreBaseline(project: Project): String? {
        val ov = overrides.remove(project) ?: return null
        ov.notification?.expire()
        val mgr = SolutionConfigurationManager.tryGetInstance(project) ?: return null
        val baseline = ov.baseline ?: return null
        if (mgr.activeConfigurationAndPlatform != ov.current) return null
        mgr.activeConfigurationAndPlatform = baseline
        return "solution configuration restored to ${label(baseline)}"
    }

    /** The user's configuration a restore would put back, or null when MCP hasn't changed it. */
    fun baselineLabel(project: Project): String? = overrides[project]?.baseline?.let { label(it) }

    /** Short-lived notice for an automatic restore. */
    fun notifyInfo(project: Project, title: String, content: String) {
        NotificationGroupManager.getInstance().getNotificationGroup("RiderMcp")
            .createNotification(title, content, NotificationType.INFORMATION)
            .notify(project)
    }

    private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))
}
