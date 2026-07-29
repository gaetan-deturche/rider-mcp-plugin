package dev.ridermcp.tools

import com.intellij.openapi.application.EDT
import com.jetbrains.rd.ide.model.RdConfigurationAndPlatform
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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
                "chosen via the configuration name. See list_solution_configurations for valid pairs.",
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

            val all = mgr.solutionConfigurationsAndPlatforms
            val matches = all.filter {
                it.configuration.equals(wantCfg, ignoreCase = true) &&
                    (wantPlat.isEmpty() || it.platform.equals(wantPlat, ignoreCase = true))
            }
            val target: RdConfigurationAndPlatform = when {
                matches.size == 1 -> matches[0]
                matches.isEmpty() -> return@addTool text(
                    "No solution configuration matches \"$wantCfg\"" +
                        (if (wantPlat.isEmpty()) "" else " | \"$wantPlat\"") + ". Available:\n" +
                        all.joinToString("\n") { "  - ${it.configuration} | ${it.platform}" }
                )
                else -> return@addTool text(
                    "\"$wantCfg\" exists for several platforms — pass 'platform' to pick one:\n" +
                        matches.joinToString("\n") { "  - ${it.configuration} | ${it.platform}" }
                )
            }

            val previous = mgr.activeConfigurationAndPlatform
            if (target == previous)
                return@addTool text("Already active: ${target.configuration} | ${target.platform}.")
            withContext(Dispatchers.EDT) {
                mgr.activeConfigurationAndPlatform = target
            }
            val prev = previous?.let { "${it.configuration} | ${it.platform}" } ?: "<none>"
            text("[SOLUTION CONFIG set] ${target.configuration} | ${target.platform}  (was $prev) in '${project.name}'.")
        }
    }

    private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))
}
