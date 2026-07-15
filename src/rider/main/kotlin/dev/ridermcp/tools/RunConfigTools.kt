package dev.ridermcp.tools

import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.EDT
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * MCP tools to launch a Rider run/debug configuration by name.
 *
 * Why it exists: firing a launch through the built-in `jetbrains` MCP tool pops
 * the JetBrains MCP "brave mode" confirmation, which is global — enabling it also
 * un-gates `execute_terminal_command` (shell). Routing launches through THIS
 * plugin runs them with no confirmation gate, so run-config launches are
 * frictionless while shell stays gated. Launches default to a DEBUG start so a
 * debugger attaches (the house rule for running UE from Rider).
 *
 * Pure frontend: ProgramRunnerUtil is IntelliJ-platform, so there's no .NET/RD
 * backend involvement here.
 */
object RunConfigTools {

    fun register(server: Server) {
        server.addTool(
            name = "list_run_configurations",
            description = "Lists the run/debug configurations in the open solution (name + type), " +
                "for use with run_configuration. Pass the 'solution' selector when several solutions " +
                "are open in one Rider instance.",
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
            val settings = RunManager.getInstance(project).allSettings
            if (settings.isEmpty()) return@addTool text("No run/debug configurations in '${project.name}'.")
            val lines = settings.joinToString("\n") { s ->
                val temp = if (s.isTemporary) "  (temporary)" else ""
                "  - ${s.name}  [${s.type.displayName}]$temp"
            }
            text("Run/debug configurations in '${project.name}':\n$lines")
        }

        server.addTool(
            name = "run_configuration",
            description = "Launches a run/debug configuration by name in the open solution, running its " +
                "normal before-launch build first (exactly like clicking Run/Debug). Defaults to a DEBUG " +
                "start so a debugger attaches; pass debug=false for a plain Run. Use " +
                "list_run_configurations to discover names. Runs without a confirmation prompt (unlike the " +
                "built-in jetbrains run tool), so shell can stay gated while launches are frictionless.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Run/debug configuration name, as shown in the configurations dropdown.")
                    })
                    put("debug", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Start under the debugger (default true). Pass false for a plain Run.")
                    })
                    put("solution", buildJsonObject {
                        put("type", "string")
                        put("description", "Target solution name or path; required when several solutions are open in one Rider instance.")
                    })
                },
                required = listOf("name"),
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            val name = request.arguments.stringArg("name")?.trim().orEmpty()
            if (name.isEmpty()) return@addTool text("'name' is required (a run/debug configuration name).")
            val debug = request.arguments.boolArg("debug") ?: true

            val runManager = RunManager.getInstance(project)
            val settings = runManager.findConfigurationByName(name)
                ?: run {
                    val avail = runManager.allSettings.joinToString("\n") { "  - ${it.name}  [${it.type.displayName}]" }
                    return@addTool text(
                        "No run/debug configuration named \"$name\" in '${project.name}'." +
                            if (avail.isBlank()) "" else "\nAvailable:\n$avail"
                    )
                }

            val executor: Executor =
                if (debug) DefaultDebugExecutor.getDebugExecutorInstance()
                else DefaultRunExecutor.getRunExecutorInstance()

            // A configuration may not support the requested executor (e.g. no debug
            // runner). Check before firing so we return a clear message instead of a
            // silent no-op.
            if (ProgramRunner.getRunner(executor.id, settings.configuration) == null) {
                val mode = if (debug) "debug" else "run"
                return@addTool text(
                    "Configuration \"$name\" [${settings.type.displayName}] does not support $mode" +
                        if (debug) " — retry with debug=false." else "."
                )
            }

            withContext(Dispatchers.EDT) {
                ProgramRunnerUtil.executeConfiguration(settings, executor)
            }
            val mode = if (debug) "DEBUG" else "RUN"
            text(
                "[$mode · started] \"$name\" [${settings.type.displayName}] in '${project.name}'. " +
                    "Before-launch build (if any) runs first; watch the Run/Debug tool window."
            )
        }

        server.addTool(
            name = "stop_process",
            description = "Stops running run/debug session(s) — the Stop-button equivalent. Omit 'name' " +
                "to stop the single running session (errors when several are running); pass 'name' to stop " +
                "the session(s) whose Run/Debug tab name matches. Pairs with run_configuration.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Running session name (the Run/Debug tab title, usually the configuration name). Omit to stop the single running session.")
                    })
                    put("solution", buildJsonObject {
                        put("type", "string")
                        put("description", "Target solution name or path; required when several solutions are open in one Rider instance.")
                    })
                },
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            val name = request.arguments.stringArg("name")?.trim().orEmpty()

            withContext(Dispatchers.EDT) {
                val em = ExecutionManager.getInstance(project) as? ExecutionManagerImpl
                    ?: return@withContext text("Execution manager unavailable for '${project.name}'.")
                val alive = em.getRunningDescriptors { true }
                    .filter { it.processHandler?.isProcessTerminated == false }
                if (alive.isEmpty()) return@withContext text("No running session in '${project.name}'.")

                val targets = if (name.isEmpty()) {
                    if (alive.size > 1)
                        return@withContext text(
                            "Several sessions are running — pass 'name' to pick one:\n" +
                                alive.joinToString("\n") { "  - ${it.displayName}" }
                        )
                    alive
                } else {
                    val matched = alive.filter { it.displayName.equals(name, ignoreCase = true) }
                    if (matched.isEmpty())
                        return@withContext text(
                            "No running session named \"$name\". Running:\n" +
                                alive.joinToString("\n") { "  - ${it.displayName}" }
                        )
                    matched
                }

                targets.forEach { ExecutionManagerImpl.stopProcess(it) }
                text("[STOPPED] " + targets.joinToString(", ") { "\"${it.displayName}\"" } + " in '${project.name}'.")
            }
        }
    }

    private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))
}
