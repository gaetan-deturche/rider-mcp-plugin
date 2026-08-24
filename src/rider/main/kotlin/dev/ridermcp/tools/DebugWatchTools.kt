package dev.ridermcp.tools

import com.intellij.execution.process.BaseProcessHandler
import com.intellij.openapi.project.ProjectManager
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
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
 * - [list_active_debug_sessions] returns a watchable handle per row: `ds-…` for
 *   a live debug session (whoever started it — a plugin launch or a manual Play
 *   in Rider) and `tw-…` for a launch armed by run_configuration whose session
 *   hasn't started yet. Exactly one row per target, so a client can discover
 *   its handle when there is no ambiguity.
 * - [wait_for_stop] long-polls one target in ~25s slices (so no HTTP layer times
 *   out; the caller loops on [TIMEOUT] replies) and returns on the next
 *   crash-like stop. Pauses at enabled USER breakpoints are filtered out — they
 *   never complete the poll, so manual debugging doesn't wake the watcher.
 *
 * Watching starts server-side at launch ([CrashTripwire]), so a crash during
 * startup or between two poll slices is buffered and reported by the next poll
 * rather than lost.
 *
 * Reason labels: unhandled_exception (a pause matching no user breakpoint —
 * crash, manual pause, or step; the debugger doesn't expose the cause cleanly),
 * exception (an exception-type breakpoint fired), already_paused (session was
 * suspended when the wait was armed), process_exited (session ended — also ends
 * the watch), never_started (the armed launch never produced a debug session).
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
            description = "Lists every watchable target across open solutions as one row each: running " +
                "debug sessions (handle=ds-…, started by run_configuration or manually in Rider) and " +
                "launches armed by run_configuration whose session hasn't started yet (handle=tw-…, " +
                "state=pending — the before-launch build is still running). Rows carry the solution PATH " +
                "because several open solutions can share a name. The handle feeds wait_for_stop to arm a " +
                "crash tripwire, including on a process this client did not launch.",
            inputSchema = toolSchema(properties = buildJsonObject {}),
        ) { _ ->
            val sessions = liveSessions()
            val pending = CrashTripwire.pendingTickets()
                .onEach { CrashTripwire.resolveSession(it) }
                .filter { it.session == null }
            if (sessions.isEmpty() && pending.isEmpty()) return@addTool text("(no active debug sessions)")
            val rows = mutableListOf<String>()
            sessions.forEach { (h, s) ->
                val state = when {
                    s.isStopped -> "stopped"
                    s.isSuspended -> "suspended"
                    else -> "running"
                }
                val pid = runCatching {
                    (s.debugProcess.processHandler as? BaseProcessHandler<*>)?.process?.pid()
                }.getOrNull()
                val watch = CrashTripwire.ticketOf(s)?.let { "  watch=${it.id}" } ?: ""
                val buffered = CrashTripwire.bufferedStop(s)?.let { "  buffered=${it.reason}" } ?: ""
                rows += "  - handle=$h  config=\"${s.sessionName}\"  pid=${pid ?: "?"}  state=$state  " +
                    "solution=${s.project.basePath ?: s.project.name}$watch$buffered"
            }
            pending.forEach { t ->
                rows += "  - handle=${t.id}  config=\"${t.configName}\"  pid=-  state=pending  " +
                    "solution=${t.project.basePath ?: t.project.name}  (armed ${t.ageSeconds}s ago, " +
                    "debug session not started yet)"
            }
            text("Active debug sessions:\n" + rows.joinToString("\n"))
        }

        server.addTool(
            name = "wait_for_stop",
            description = "Long-poll crash tripwire: blocks up to timeoutSeconds (default " +
                "$DEFAULT_WAIT_SECONDS, max $MAX_WAIT_SECONDS) and returns when the target with the given " +
                "handle (from list_active_debug_sessions) next stops in a crash-like way: " +
                "reason=unhandled_exception|exception with faulting thread + top frames, or " +
                "reason=process_exited when it ends (stop re-polling). A crash-like stop recorded before " +
                "this call — including during startup — is reported immediately rather than lost. Pauses " +
                "at enabled user breakpoints are IGNORED; they never complete this wait. A tw-… handle " +
                "whose launch is still building replies [TIMEOUT] until its session starts. Replies " +
                "starting with [TIMEOUT] mean still running: call again with the same handle to keep " +
                "watching. If the session is already suspended when called, returns immediately with " +
                "reason=already_paused.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("handle", buildJsonObject {
                        put("type", "string")
                        put("description", "Target handle from list_active_debug_sessions: ds-… (live session) or tw-… (armed launch).")
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
            val timeoutSec = (request.arguments.intArg("timeoutSeconds") ?: DEFAULT_WAIT_SECONDS)
                .coerceIn(1, MAX_WAIT_SECONDS)

            // A tw-… handle names a launch: its session exists only after the
            // before-launch build, so wait for the binding inside this slice.
            val session: XDebugSession = if (handle.startsWith(CrashTripwire.TICKET_PREFIX)) {
                val ticket = CrashTripwire.ticket(handle)
                    ?: return@addTool text(
                        "[STOP · never_started] No armed launch with handle $handle — it expired or never " +
                            "existed. Call list_active_debug_sessions for current handles."
                    )
                CrashTripwire.resolveSession(ticket)
                    ?: withTimeoutOrNull(timeoutSec * 1000L) { ticket.awaitSession() }
                    ?: return@addTool text(
                        if (ticket.isExpired)
                            "[STOP · never_started] Launch \"${ticket.configName}\" (handle $handle) never " +
                                "produced a debug session — the before-launch build likely failed. Check the " +
                                "Build tool window; re-arm after a successful launch."
                        else
                            "[TIMEOUT] Launch \"${ticket.configName}\" (handle $handle) armed " +
                                "${ticket.ageSeconds}s ago — debug session not started yet (before-launch " +
                                "build). Call wait_for_stop again with the same handle."
                    )
            } else {
                liveSessions().firstOrNull { it.first == handle }?.second
                    ?: return@addTool text(
                        "[STOP · process_exited] No debug session with handle $handle — it ended or never existed. " +
                            "Call list_active_debug_sessions for current handles."
                    )
            }

            // Start buffering for a session we didn't launch (manual Play), so a
            // crash between this call's slices can't slip through either.
            CrashTripwire.watch(session)

            // A stop recorded before this poll wins over the session's current
            // state: a crash that already killed the process must still report as
            // a crash, not as a bare process_exited.
            CrashTripwire.bufferedStop(session)?.let { buffered ->
                if (buffered.reason == "process_exited") return@addTool text(exitedPayload(session, handle))
                return@addTool text(bufferedPayload(session, handle, buffered))
            }
            if (session.isStopped) return@addTool text(exitedPayload(session, handle))
            if (session.isSuspended) return@addTool text(stopPayload(session, handle, "already_paused"))

            val stopReason = CompletableDeferred<String>()
            val listener = object : XDebugSessionListener {
                override fun sessionPaused() {
                    val reason = CrashTripwire.classifyPause(session) ?: return // user breakpoint: keep waiting
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

    private fun exitedPayload(session: XDebugSession, handle: String) =
        "[STOP · process_exited] \"${session.sessionName}\" (handle $handle) ended. Stop re-polling; " +
            "use read_process_output for its final console output."

    /**
     * Payload for a stop recorded before the caller polled. The process normally
     * stays suspended on a crash, so the live stack is still available — fall
     * back to the recorded snapshot when it has since exited.
     */
    private suspend fun bufferedPayload(
        session: XDebugSession,
        handle: String,
        buffered: CrashTripwire.BufferedStop,
    ): String {
        val agoSec = (System.currentTimeMillis() - buffered.atMs) / 1000
        val note = "\n(recorded by the tripwire ${agoSec}s ago, before this poll)"
        if (session.isSuspended && !session.isStopped) {
            return stopPayload(session, handle, buffered.reason) + note
        }
        val sb = StringBuilder()
        sb.append("[STOP · ${buffered.reason}] \"${session.sessionName}\" (handle $handle)")
        buffered.position?.let { sb.append("\nposition: $it") }
        buffered.threadName?.let { sb.append("\nthread: $it") }
        sb.append("\nThe process has since exited, so no live stack is available — read_process_output for ")
        sb.append("the console/callstack tail.")
        sb.append(note)
        return sb.toString()
    }

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
