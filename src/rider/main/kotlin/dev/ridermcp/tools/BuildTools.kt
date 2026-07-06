package dev.ridermcp.tools

import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.service
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.jetbrains.rider.debugger.editAndContinue.DotNetHotReloadManager
import com.jetbrains.rider.projectView.solution
import dev.ridermcp.model.BuildProblem
import dev.ridermcp.model.BuildProjectResult
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tool that builds a *specific* project (or projects) in the open solution
 * via Rider's own build engine — the gap the stock `build_solution` tool leaves,
 * since it only builds the whole solution.
 *
 * The build itself runs on the .NET backend ([DebugDataProvider] -> RD ->
 * `ISolutionBuilder`); this layer just marshals arguments and formats the result.
 */
object BuildTools {

    private const val MAX_PROBLEMS = 200

    fun register(server: Server) {
        server.addTool(
            name = "build_project",
            description = "Builds one or more specific projects in the open solution (not the whole " +
                "solution) using Rider's build engine, and returns whether it succeeded plus any " +
                "errors/warnings with file:line. If a hot-reload session is already running, it " +
                "instead applies changes live (like the toolbar 'Apply Changes' button) unless " +
                "rebuild=true — .NET Hot Reload for .NET runs, or Unreal Live Coding for a running " +
                "UE editor. Use get_solution_projects to discover project names.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("projects", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Project name(s) to build, as shown in the Solution Explorer.")
                    })
                    put("rebuild", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Rebuild (clean + build) instead of an incremental build. Default false.")
                    })
                    put("withoutDependencies", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Build only the named project(s), skipping referenced projects. Default false.")
                    })
                    put("solution", buildJsonObject {
                        put("type", "string")
                        put("description", "Optional: target solution name when several are open.")
                    })
                },
                required = listOf("projects"),
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution"))
                ?: return@addTool text("No matching open solution.")

            val names = request.arguments.stringListArg("projects")
            if (names.isEmpty()) return@addTool text("'projects' is required (one or more project names).")

            val rebuild = request.arguments.boolArg("rebuild") ?: false
            val withoutDeps = request.arguments.boolArg("withoutDependencies") ?: false

            // If a hot-reload session is already running, apply changes live —
            // the same thing the toolbar "Apply Changes" button does — instead of a
            // cold build. Like that button, this applies all pending edits to the
            // running process(es), not just the named project. A `rebuild` forces a
            // full build, so it always takes the build route.
            if (!rebuild) {
                // .NET hot reload (run/debug session with hot reload).
                val hotReload = runCatching { project.service<DotNetHotReloadManager>() }.getOrNull()
                if (hotReload != null && hotReload.processes.isNotEmpty()) {
                    val kind = withContext(Dispatchers.EDT) { hotReload.applyChangesIfNeeded() }
                    return@addTool text("Hot reload (live session, ${hotReload.processes.size} process(es)): $kind")
                }
                // Unreal: Live Coding is the hot-reload equivalent (a cold build is
                // refused while it's active). Trigger it like Rider's UE build button.
                tryUnrealHotReload(project)?.let { return@addTool text(it) }
            }

            val result = project.service<DebugDataProvider>().buildProject(names, rebuild, withoutDeps)
                ?: return@addTool text("Backend not connected for '${project.name}'.")

            text(format(result))
        }
    }

    /**
     * Unreal projects don't use .NET hot reload — the equivalent is Live Coding,
     * driven from the running editor. Rider's UE build button (HotReloadBuildAction)
     * just does saveAll() + fires RdRiderModel.triggerHotReload; we do the same when
     * a Live Coding session is available (UnrealHost.isHotReloadAvailable). Returns a
     * status string when it fired, or null when the UE hot-reload path doesn't apply
     * (not a UE project / no live session / UnrealLink absent) so the caller builds.
     *
     * Done reflectively through the UnrealLink plugin's classloader so this plugin
     * keeps no hard dependency on UnrealLink and still loads on non-Unreal setups.
     */
    private suspend fun tryUnrealHotReload(project: Project): String? {
        return runCatching {
            val loader = PluginManagerCore.getPlugin(PluginId.getId("unreal-link"))?.pluginClassLoader
                ?: return null

            val hostClass = loader.loadClass("com.jetbrains.rider.plugins.unreal.UnrealHost")
            val companion = hostClass.getField("Companion").get(null)
            val host = companion.javaClass.methods.first { it.name == "getInstance" }.invoke(companion, project)
            val isUnreal = host.javaClass.methods.first { it.name == "isUnrealEngineSolution" }.invoke(host) as Boolean
            val available = host.javaClass.methods.first { it.name == "isHotReloadAvailable" }.invoke(host) as Boolean
            if (!isUnreal || !available) return null

            withContext(Dispatchers.EDT) {
                ApplicationManager.getApplication().saveAll()
                val solution = project.solution
                val model = loader.loadClass("com.jetbrains.rider.plugins.unreal.model.frontendBackend.RdRiderModel_PregeneratedKt")
                    .methods.first { it.name == "getRdRiderModel" }.invoke(null, solution)
                    ?: return@withContext
                val signal = model.javaClass.methods.first { it.name == "getTriggerHotReload" }.invoke(model)
                signal.javaClass.methods.first { it.name == "fire" && it.parameterCount == 1 }.invoke(signal, Unit)
            }
            "Triggered Unreal Live Coding / Hot Reload for the running session — check the editor's Live Coding tool window/log for results."
        }.getOrNull()
    }

    private fun format(r: BuildProjectResult): String {
        r.errorMessage?.let { return it }

        val status = when {
            r.cancelled -> "CANCELLED"
            r.hasErrors || !r.succeeded -> "FAILED"
            r.hasWarnings -> "SUCCEEDED (with warnings)"
            r.skipped -> "UP-TO-DATE (nothing to build)"
            else -> "SUCCEEDED"
        }

        val sb = StringBuilder()
        sb.append("Build $status")
        if (r.builtProjects.isNotEmpty()) sb.append(" — ").append(r.builtProjects.joinToString(", "))
        val errors = r.problems.count { it.kind.equals("Error", ignoreCase = true) }
        val warnings = r.problems.count { it.kind.equals("Warning", ignoreCase = true) }
        sb.append("\n$errors error(s), $warnings warning(s)")

        r.problems.take(MAX_PROBLEMS).forEach { sb.append('\n').append(problemLine(it)) }
        if (r.problems.size > MAX_PROBLEMS) sb.append("\n… ${r.problems.size - MAX_PROBLEMS} more")
        return sb.toString()
    }

    private fun problemLine(p: BuildProblem): String {
        val location = buildString {
            append(p.file ?: "<no file>")
            p.line?.let { append(":$it") }
            p.column?.let { append(":$it") }
        }
        val code = p.code?.let { "$it: " } ?: ""
        return "[${p.kind}] $location  $code${p.message}"
    }

    private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))
}
