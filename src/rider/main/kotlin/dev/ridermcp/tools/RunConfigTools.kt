package dev.ridermcp.tools

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.Executor
import com.intellij.execution.ProgramRunnerUtil
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.executors.DefaultRunExecutor
import com.intellij.execution.impl.ExecutionManagerImpl
import com.intellij.execution.process.ProcessHandler
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.execution.runners.ProgramRunner
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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

    /**
     * How long run_configuration waits for the before-launch build to resolve
     * (session started, or never_started) before returning. Kept well under the
     * MCP/HTTP response timeout; a longer cold build defers to the tripwire.
     */
    private const val BUILD_RESULT_WAIT_MS = 12_000L

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
                "normal before-launch build first (exactly like clicking Run/Debug). Pass configuration/" +
                "platform to launch an explicit target: it is set and verified before firing, and refused " +
                "on a mismatch. The reply's first line echoes the effective 'configuration | platform · args'. " +
                "By default (restore=true) the user's own solution configuration and the MCP args node are " +
                "put back when the session ends. Defaults to a DEBUG " +
                "start so a debugger attaches; pass debug=false for a plain Run. Use " +
                "list_run_configurations to discover names. Runs without a confirmation prompt (unlike the " +
                "built-in jetbrains run tool), so shell can stay gated while launches are frictionless. " +
                "RETURNS THE BEFORE-LAUNCH BUILD RESULT: it waits briefly for the launch to resolve and " +
                "replies '[DEBUG · never_started]' when the before-launch build fails or the launch is " +
                "cancelled, else '[DEBUG · started]' once the debug session is up (a long cold build exceeds " +
                "the wait and defers to the tripwire). A debug launch also ARMS THE CRASH TRIPWIRE and returns " +
                "its handle (tripwire armed (handle=tw-N)): crash-like stops AND a before-launch build failure " +
                "are captured server-side from process start, and the reply names the watcher command to run " +
                "detached so the client is woken.",
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
                    put("configuration", buildJsonObject {
                        put("type", "string")
                        put("description", "Solution configuration to launch with, e.g. 'Development Editor' or 'Development' (for Unreal the target is in this name). Set and verified before launching; omit to use the active one (the reply echoes it either way).")
                    })
                    put("platform", buildJsonObject {
                        put("type", "string")
                        put("description", "Solution platform, e.g. 'Win64' or 'PS5'. Optional when the configuration exists for only one platform.")
                    })
                    put("restore", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Default true: when this session ends (or never starts), put back the user's own solution configuration if MCP changed it, and uncheck the MCP launch-args node if it is unchanged. Pass false to keep both, e.g. for a batch of runs.")
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

            val mode = if (debug) "DEBUG" else "RUN"

            // Explicit target: set it, then read it back right before firing, so a
            // launch never relies on whatever the toolbar happens to hold.
            val wantCfg = request.arguments.stringArg("configuration")?.trim().orEmpty()
            val wantPlat = request.arguments.stringArg("platform")?.trim().orEmpty()
            val restore = request.arguments.boolArg("restore") ?: true
            val mgr = SolutionConfigurationManager.tryGetInstance(project)
            var configNote = ""
            if (wantCfg.isNotEmpty()) {
                mgr ?: return@addTool text("Not launched — no solution-configuration support in '${project.name}' (solution still loading?).")
                val target = when (val m = SolutionConfigTools.match(mgr, wantCfg, wantPlat)) {
                    is SolutionConfigTools.Match.Found -> m.target
                    is SolutionConfigTools.Match.Error -> return@addTool text("Not launched — ${m.message}")
                }
                val previous = mgr.activeConfigurationAndPlatform
                if (target != previous) {
                    SolutionConfigTools.applyFromMcp(project, mgr, target)
                    configNote = "\nsolution configuration set by MCP: ${SolutionConfigTools.label(target)} " +
                        "(was ${SolutionConfigTools.label(previous)})"
                }
                val active = withContext(Dispatchers.EDT) { mgr.activeConfigurationAndPlatform }
                if (active != target) return@addTool text(
                    "[$mode · not launched] \"$name\": asked for ${SolutionConfigTools.label(target)} but the " +
                        "toolbar shows ${SolutionConfigTools.label(active)} — refusing to launch the wrong target."
                )
            }

            // Echo what is actually being launched, so a wrong target or stale args
            // are visible in the first line of the reply.
            val args = CmdLineArgsTools.effectiveArgs(project, settings)?.takeIf { it.isNotBlank() } ?: "(none)"
            val head = "\"$name\" — ${SolutionConfigTools.label(mgr?.activeConfigurationAndPlatform)} · args: $args"
            val context = "\n  [${settings.type.displayName}] in '${project.name}' (${project.basePath ?: project.name})"

            // Arm the restore BEFORE firing so this launch's execution id is captured.
            val mcpArgs = if (restore) CmdLineArgsTools.mcpNodeArgs(project) else null
            val baseline = SolutionConfigTools.baselineLabel(project)
            val restoreNote = when {
                !restore -> "\nrestore: off — the toolbar configuration and MCP args stay as they are after the session."
                baseline == null && mcpArgs == null -> ""
                else -> {
                    armRestore(project, settings, executor.id, mcpArgs)
                    "\nrestore when this session ends (or never starts): " +
                        listOfNotNull(baseline?.let { "solution configuration → $it" }, mcpArgs?.let { "MCP args unchecked" })
                            .joinToString(", ") + " — pass restore=false to keep (e.g. a batch of runs)."
                }
            }

            // Arm the crash tripwire BEFORE firing, so a crash during startup is
            // caught too — the debug session only appears after the before-launch
            // build, which is why the handle is a ticket rather than a session id.
            val ticket = if (debug) CrashTripwire.arm(project, name) else null

            withContext(Dispatchers.EDT) {
                ProgramRunnerUtil.executeConfiguration(settings, executor)
            }

            // Non-debug launches don't arm a ticket and attach no debugger — report
            // and return (no before-launch build result to await, no tripwire).
            if (ticket == null) {
                return@addTool text(
                    "[$mode · started] $head$context. Before-launch build (if any) runs first; watch the Run/Debug " +
                        "tool window.$configNote$restoreNote\n(no tripwire: not a debug launch — no debugger " +
                        "attaches, so a crash cannot be caught)"
                )
            }

            // Return the before-launch build result: wait briefly for the launch to
            // resolve (it usually does in seconds). A long cold build exceeds the
            // bound, so we report "still building" and let the tripwire surface
            // never_started promptly if it then fails.
            val sb = StringBuilder()
            when (val outcome = withTimeoutOrNull(BUILD_RESULT_WAIT_MS) { ticket.awaitOutcome() }) {
                is CrashTripwire.Outcome.NeverStarted -> return@addTool text(
                    "[$mode · never_started] $head$context\n${outcome.reason}: no process started and nothing is " +
                        "watching. Read the Build tool window (read_tool_window id=Build), fix it, and " +
                        "relaunch.$configNote$restoreNote"
                )
                is CrashTripwire.Outcome.Started ->
                    sb.append("[$mode · started] $head$context\nbefore-launch build succeeded, debug session is up.")
                null ->
                    sb.append("[$mode · started] $head$context\nbefore-launch build still running after " +
                        "${BUILD_RESULT_WAIT_MS / 1000}s (not yet confirmed) — watch the Run/Debug tool window.")
            }
            sb.append(configNote).append(restoreNote)
            sb.append("\ntripwire armed (handle=${ticket.id}) — crash-like stops are captured from process start, ")
            sb.append("and a before-launch build failure resolves to never_started at once.")
            sb.append("\nREQUIRED to actually be woken: start the wake watcher detached now ")
            sb.append("(run_in_background), it exits with the crash or never_started payload:")
            sb.append("\n  bash ${armScriptPath()} ${ticket.id}")
            text(sb.toString())
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

    /**
     * Once THIS launch ends or never starts (matched by its execution id), puts back
     * the user's solution configuration and unchecks the MCP args that were live at
     * launch — each only if nobody changed it since.
     */
    private fun armRestore(project: Project, settings: RunnerAndConfigurationSettings, launchExecutorId: String, mcpArgs: String?) {
        val connection = project.messageBus.connect(project)
        val executionId = AtomicLong(-1)
        val done = AtomicBoolean(false)
        fun finish() {
            if (!done.compareAndSet(false, true)) return
            connection.disconnect()
            ApplicationManager.getApplication().invokeLater {
                if (project.isDisposed) return@invokeLater
                val parts = listOfNotNull(
                    SolutionConfigTools.restoreBaseline(project),
                    if (mcpArgs != null && CmdLineArgsTools.uncheckMcpNodeIfUnchanged(project, mcpArgs)) "MCP launch args unchecked" else null,
                )
                if (parts.isNotEmpty()) SolutionConfigTools.notifyInfo(project, "MCP launch \"${settings.name}\" ended", parts.joinToString("; "))
            }
        }
        fun ours(executorId: String, env: ExecutionEnvironment) = executorId == launchExecutorId &&
            env.runnerAndConfigurationSettings?.let { it === settings || it.name == settings.name } == true
        connection.subscribe(ExecutionManager.EXECUTION_TOPIC, object : ExecutionListener {
            override fun processStartScheduled(executorId: String, env: ExecutionEnvironment) {
                if (ours(executorId, env)) executionId.compareAndSet(-1, env.executionId)
            }

            override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                if (ours(executorId, env) && (executionId.get() == -1L || env.executionId == executionId.get())) finish()
            }

            override fun processTerminated(executorId: String, env: ExecutionEnvironment, handler: ProcessHandler, exitCode: Int) {
                if (ours(executorId, env) && env.executionId == executionId.get()) finish()
            }
        })
    }

    /**
     * Where the client's wake-watcher script lives, in the bash form the caller
     * runs it in (`/c/Users/…`). Overridable with -Drider.mcp.tripwireScript for
     * a client that keeps it elsewhere; falls back to the plain script name when
     * the default location doesn't exist.
     */
    private fun armScriptPath(): String {
        System.getProperty("rider.mcp.tripwireScript")?.takeIf { it.isNotBlank() }?.let { return it }
        val home = System.getProperty("user.home")?.replace('\\', '/')?.trimEnd('/') ?: return "arm_tripwire.sh"
        val path = "$home/.claude/skills/rider-run/arm_tripwire.sh"
        if (!java.io.File(path).isFile) return "arm_tripwire.sh"
        // C:/Users/x → /c/Users/x, the form bash on Windows accepts.
        return if (path.length > 2 && path[1] == ':') "/" + path[0].lowercaseChar() + path.substring(2) else path
    }

    private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))
}
