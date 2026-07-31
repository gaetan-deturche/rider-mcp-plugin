package dev.ridermcp.tools

import com.intellij.execution.process.BaseProcessHandler
import com.intellij.openapi.project.ProjectManager
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Crash tripwire: lets an MCP client watch a debugged process for crash-like
 * stops without babysitting it.
 *
 * - [list_active_debug_sessions] returns a stable handle for every running
 *   debug session, regardless of who started it (plugin tool or a manual Play
 *   in Rider) — this is how a caller attaches to a process it didn't launch.
 * - [wait_for_stop] long-polls one session in ~25s slices (so no HTTP layer
 *   times out; the caller loops on [TIMEOUT] replies) and returns on the next
 *   crash-like stop. Pauses at enabled USER breakpoints (line breakpoints, and
 *   non-exception special breakpoints) are filtered out inside the wait — they
 *   never complete the poll, so a manual breakpoint doesn't wake the watcher.
 *
 * Reason labels: unhandled_exception (a pause matching no user breakpoint —
 * crash, manual pause, or step; the debugger doesn't expose the cause cleanly,
 * refine later), exception (an exception-type breakpoint fired),
 * already_paused (session was suspended when the wait was armed),
 * process_exited (session ended — also ends the watch).
 */
object DebugWatchTools {

    private const val MAX_WAIT_SECONDS = 60
    private const val DEFAULT_WAIT_SECONDS = 25
    private const val PAYLOAD_FRAMES = 10

    private fun handleOf(s: XDebugSession) = "ds-" + Integer.toHexString(System.identityHashCode(s))

    /** Live sessions across all open projects, keyed by their stable handle. */
    private fun liveSessions(): List<Pair<String, XDebugSession>> =
        ProjectManager.getInstance().openProjects.flatMap { p ->
            XDebuggerManager.getInstance(p).debugSessions.map { s -> handleOf(s) to s }
        }

    fun register(server: Server) {
        server.addTool(
            name = "list_active_debug_sessions",
            description = "Lists every currently-running debug session across open solutions — " +
                "regardless of whether it was started by run_configuration or manually in Rider — " +
                "as handle + config name + pid + state. The handle feeds wait_for_stop to arm a " +
                "crash tripwire on a process this client did not launch.",
            inputSchema = toolSchema(properties = buildJsonObject {}),
        ) { _ ->
            val sessions = liveSessions()
            if (sessions.isEmpty()) return@addTool text("(no active debug sessions)")
            val lines = sessions.joinToString("\n") { (h, s) ->
                val state = when {
                    s.isStopped -> "stopped"
                    s.isSuspended -> "suspended"
                    else -> "running"
                }
                val pid = runCatching {
                    (s.debugProcess.processHandler as? BaseProcessHandler<*>)?.process?.pid()
                }.getOrNull()
                "  - handle=$h  config=\"${s.sessionName}\"  pid=${pid ?: "?"}  state=$state  project=${s.project.name}"
            }
            text("Active debug sessions:\n$lines")
        }

        server.addTool(
            name = "wait_for_stop",
            description = "Long-poll crash tripwire: blocks up to timeoutSeconds (default " +
                "$DEFAULT_WAIT_SECONDS, max $MAX_WAIT_SECONDS) and returns when the debug session " +
                "with the given handle (from list_active_debug_sessions) next stops in a crash-like " +
                "way: reason=unhandled_exception|exception with faulting thread + top frames, or " +
                "reason=process_exited when it ends (stop re-polling). Pauses at enabled user " +
                "breakpoints are IGNORED — they never complete this wait. Replies starting with " +
                "[TIMEOUT] mean still running: call again with the same handle to keep watching. " +
                "If the session is already suspended when called, returns immediately with " +
                "reason=already_paused.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("handle", buildJsonObject {
                        put("type", "string")
                        put("description", "Session handle from list_active_debug_sessions (ds-…).")
                    })
                    put("timeoutSeconds", buildJsonObject {
                        put("type", "number")
                        put("description", "Max seconds to block before replying [TIMEOUT] (default $DEFAULT_WAIT_SECONDS, max $MAX_WAIT_SECONDS). Keep short and re-poll — long values can hit HTTP idle timeouts.")
                    })
                },
                required = listOf("handle"),
            ),
        ) { request ->
            val handle = request.arguments.stringArg("handle")?.trim().orEmpty()
            if (handle.isEmpty()) return@addTool text("'handle' is required (from list_active_debug_sessions).")
            val session = liveSessions().firstOrNull { it.first == handle }?.second
                ?: return@addTool text(
                    "[STOP · process_exited] No debug session with handle $handle — it ended or never existed. " +
                        "Call list_active_debug_sessions for current handles."
                )
            val timeoutSec = (request.arguments.intArg("timeoutSeconds") ?: DEFAULT_WAIT_SECONDS)
                .coerceIn(1, MAX_WAIT_SECONDS)

            if (session.isStopped) return@addTool text(exitedPayload(session, handle))
            if (session.isSuspended) return@addTool text(stopPayload(session, handle, "already_paused"))

            val stopReason = CompletableDeferred<String>()
            val listener = object : XDebugSessionListener {
                override fun sessionPaused() {
                    val reason = classifyPause(session) ?: return // user breakpoint: keep waiting
                    stopReason.complete(reason)
                }
                override fun sessionStopped() {
                    stopReason.complete("process_exited")
                }
            }
            session.addSessionListener(listener)
            try {
                val reason = withTimeoutOrNull(timeoutSec * 1000L) { stopReason.await() }
                    ?: return@addTool text(
                        "[TIMEOUT] \"${session.sessionName}\" (handle $handle) still running after ${timeoutSec}s — " +
                            "call wait_for_stop again with the same handle to keep watching."
                    )
                if (reason == "process_exited") return@addTool text(exitedPayload(session, handle))
                text(stopPayload(session, handle, reason))
            } finally {
                session.removeSessionListener(listener)
            }
        }
    }

    /**
     * Classifies a pause. Returns null when it's an enabled USER breakpoint
     * (the tripwire must ignore those), else a crash-like reason label.
     */
    private fun classifyPause(session: XDebugSession): String? {
        val pos = session.currentPosition
        if (pos != null) {
            val lineBpHit = XDebuggerManager.getInstance(session.project).breakpointManager.allBreakpoints
                .filterIsInstance<XLineBreakpoint<*>>()
                .any { it.isEnabled && it.fileUrl == pos.file.url && it.line == pos.line }
            if (lineBpHit) return null
        }
        // A non-line breakpoint (exception / method / signal breakpoint) that is
        // currently active: exception-type ones are crash-relevant, the rest are
        // user-set and ignored like line breakpoints.
        val nonLine = runCatching {
            (session as? com.intellij.xdebugger.impl.XDebugSessionImpl)?.activeNonLineBreakpoint
        }.getOrNull()
        if (nonLine != null) {
            val label = runCatching { nonLine.type.title }.getOrNull().orEmpty()
            return if (label.contains("exception", ignoreCase = true)) "exception" else null
        }
        // No user breakpoint matches: crash, manual pause, or step — the debugger
        // doesn't expose the cause cleanly, so label it unhandled_exception (see
        // class doc) and let the caller inspect.
        return "unhandled_exception"
    }

    private fun exitedPayload(session: XDebugSession, handle: String) =
        "[STOP · process_exited] \"${session.sessionName}\" (handle $handle) ended. Stop re-polling; " +
            "use read_process_output for its final console output."

    private suspend fun stopPayload(session: XDebugSession, handle: String, reason: String): String {
        val sb = StringBuilder()
        sb.append("[STOP · $reason] \"${session.sessionName}\" (handle $handle)")
        session.currentPosition?.let { sb.append("\nposition: ${it.file.name}:${it.line + 1}") }
        val stack = session.suspendContext?.activeExecutionStack
        if (stack != null) {
            sb.append("\nthread: ${stack.displayName}")
            val frames = DebuggerTools.awaitFrames(stack, PAYLOAD_FRAMES)
            if (frames.isNotEmpty()) {
                sb.append("\nframes:")
                frames.forEachIndexed { i, f ->
                    val p = f.sourcePosition?.let { "${it.file.name}:${it.line + 1}" } ?: "<no source>"
                    sb.append("\n  #$i  ${DebuggerTools.frameLabel(f)}  ($p)")
                }
            }
        }
        if (reason == "unhandled_exception") {
            sb.append("\n(caveat: pause matched no user breakpoint — crash, manual pause, or step)")
        }
        sb.append("\nnext: get_call_stack / get_local_variables / evaluate for deeper inspection; read_process_output for the console.")
        return sb.toString()
    }

    private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))
}
