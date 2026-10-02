package dev.ridermcp.tools

import com.intellij.execution.ExecutionListener
import com.intellij.execution.ExecutionManager
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.execution.runners.ExecutionEnvironment
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebugProcess
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebugSessionListener
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.XDebuggerManagerListener
import com.intellij.xdebugger.breakpoints.XLineBreakpoint
import kotlinx.coroutines.CompletableDeferred
import java.util.Collections
import java.util.WeakHashMap

/**
 * Server-side half of the crash tripwire: it starts watching a debugged process
 * at LAUNCH time, not when a client first polls.
 *
 * The client's wake still comes from its own [DebugWatchTools.wait_for_stop]
 * long-poll (nothing here can wake an MCP client), but arming the watch early
 * fixes the two ways the old poll-only design lost a crash:
 *  - a crash during startup, before the client could obtain a session handle;
 *  - a crash in the gap between two poll slices.
 * The first crash-like stop per session is BUFFERED, so a later poll reports it
 * instead of waiting for a second crash that will never come. It is delivered
 * once: consumed when reported, cleared when the session resumes.
 *
 * A launch arms a *ticket* ([Ticket], handle `tw-N`) before the process exists —
 * the debug session only appears after the before-launch build, which for Unreal
 * can take minutes. The ticket binds to the session that starts in ITS project,
 * so a launch can never latch onto the other open solution's process (Curiosity
 * and Curiosity2 are both named "UE5").
 */
object CrashTripwire {

    const val TICKET_PREFIX = "tw-"

    /** A pending launch stops being a tripwire target after this long. */
    private const val PENDING_TTL_MS = 60L * 60 * 1000

    private val log = logger<CrashTripwire>()

    /** A crash-like stop recorded before any client asked about it. */
    class BufferedStop(val reason: String, val position: String?, val threadName: String?, val atMs: Long)

    /** Per-session buffer. Created when the session is armed or first polled. */
    class SessionWatch {
        @Volatile
        var stop: BufferedStop? = null
            private set

        /** First stop wins: a crash must not be overwritten by the process exiting after it. */
        @Synchronized
        fun record(reason: String, position: String?, threadName: String?) {
            if (stop != null) return
            stop = BufferedStop(reason, position, threadName, System.currentTimeMillis())
        }

        /** [delivered] was reported: later polls must not replay it (no-op if a newer stop replaced it). */
        @Synchronized
        fun consume(delivered: BufferedStop) {
            if (stop === delivered) stop = null
        }

        /** The session resumed, so the buffered stop is no longer current. */
        @Synchronized
        fun clear() {
            stop = null
        }
    }

    /** The resolved fate of an armed launch. */
    sealed class Outcome {
        /** The debug session started (the before-launch build succeeded). */
        class Started(val session: XDebugSession) : Outcome()

        /** No session: the before-launch build failed or the launch was cancelled. */
        class NeverStarted(val reason: String) : Outcome()
    }

    /** A launch armed before its debug session exists. */
    class Ticket(
        val id: String,
        val project: Project,
        val configName: String,
        /**
         * Sessions already running when this was armed. They can never satisfy
         * this ticket — otherwise a launch would latch onto the editor the user
         * already had running in the same solution.
         */
        private val preexisting: List<XDebugSession>,
    ) {
        val armedAtMs: Long = System.currentTimeMillis()
        private val outcome = CompletableDeferred<Outcome>()

        internal fun predates(s: XDebugSession) = preexisting.any { it === s }

        @Volatile
        var session: XDebugSession? = null
            private set

        /** Set when the launch produced no session (before-launch build failed / cancelled). */
        @Volatile
        var neverStartedReason: String? = null
            private set

        val ageSeconds: Long get() = (System.currentTimeMillis() - armedAtMs) / 1000

        /** A still-pending ticket that aged out (no session, not yet resolved never-started). */
        val isExpired: Boolean
            get() = session == null && neverStartedReason == null &&
                System.currentTimeMillis() - armedAtMs > PENDING_TTL_MS

        internal fun bind(s: XDebugSession) {
            if (outcome.isCompleted) return
            session = s
            outcome.complete(Outcome.Started(s))
        }

        internal fun markNeverStarted(reason: String) {
            if (outcome.isCompleted) return
            neverStartedReason = reason
            outcome.complete(Outcome.NeverStarted(reason))
        }

        /** Suspends until the launch resolves — session started, or never-started (caller applies the timeout). */
        suspend fun awaitOutcome(): Outcome = outcome.await()
    }

    private var ticketSeq = 0
    private val tickets = LinkedHashMap<String, Ticket>()
    private val watches: MutableMap<XDebugSession, SessionWatch> =
        Collections.synchronizedMap(WeakHashMap())
    private val installedIn: MutableSet<Project> =
        Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

    /**
     * Arms a tripwire for a launch about to start in [project]. Call BEFORE
     * firing the configuration so a crash during startup is still caught.
     * Supersedes an unbound ticket for the same configuration (a relaunch).
     */
    fun arm(project: Project, configName: String): Ticket {
        ensureInstalled(project)
        return synchronized(tickets) {
            tickets.entries.removeIf { (_, t) ->
                // Prune anything unbound that aged out (stuck-pending or an old
                // never-started), and supersede the still-pending ticket of this
                // same configuration (a relaunch).
                (t.session == null && System.currentTimeMillis() - t.armedAtMs > PENDING_TTL_MS) ||
                    (t.session == null && t.neverStartedReason == null && t.project == project && t.configName == configName)
            }
            val ticket = Ticket(
                TICKET_PREFIX + (++ticketSeq),
                project,
                configName,
                XDebuggerManager.getInstance(project).debugSessions.toList(),
            )
            tickets[ticket.id] = ticket
            log.info("Crash tripwire armed: ${ticket.id} for \"$configName\" in ${project.name}")
            ticket
        }
    }

    fun ticket(id: String): Ticket? = synchronized(tickets) { tickets[id] }

    /** Tickets still waiting for their debug session to start (excludes never-started). */
    fun pendingTickets(): List<Ticket> = synchronized(tickets) {
        tickets.entries.removeIf { (_, t) -> t.session == null && System.currentTimeMillis() - t.armedAtMs > PENDING_TTL_MS }
        tickets.values.filter { it.session == null && it.neverStartedReason == null }
    }

    /** The ticket a live session was launched by, if any (for reporting). */
    fun ticketOf(session: XDebugSession): Ticket? =
        synchronized(tickets) { tickets.values.firstOrNull { it.session === session } }

    /**
     * Starts buffering crash-like stops for [session] if not already watched —
     * idempotent. Called when a ticket binds (launch path) and when a client
     * first polls a session it did not launch (manual Play path).
     */
    fun watch(session: XDebugSession): SessionWatch = synchronized(watches) {
        watches[session]?.let { return it }
        val watch = SessionWatch()
        watches[session] = watch
        session.addSessionListener(object : XDebugSessionListener {
            override fun sessionPaused() {
                val reason = classifyPause(session) ?: return // enabled user breakpoint: not a crash
                watch.record(reason, positionOf(session), threadOf(session))
            }

            override fun sessionStopped() {
                watch.record("process_exited", null, null)
            }

            // Resumed (plugin resume tool or Rider UI): the stop was seen and handled.
            override fun sessionResumed() {
                watch.clear()
            }
        })
        watch
    }

    fun bufferedStop(session: XDebugSession): BufferedStop? = watches[session]?.stop

    /** Marks a reported buffered stop as delivered, so the next poll waits for a NEW stop. */
    fun consume(session: XDebugSession, delivered: BufferedStop) {
        watches[session]?.consume(delivered)
    }

    /** Subscribes to debug-session starts and launch failures in [project] so tickets can resolve. */
    private fun ensureInstalled(project: Project) {
        if (project.isDisposed || !installedIn.add(project)) return
        val connection = project.messageBus.connect()
        connection.subscribe(
            XDebuggerManager.TOPIC,
            object : XDebuggerManagerListener {
                override fun processStarted(debugProcess: XDebugProcess) {
                    runCatching { onSessionStarted(debugProcess.session) }
                        .onFailure { log.warn("Crash tripwire: binding failed", it) }
                }
            },
        )
        // A launch whose before-launch build fails (or that the user cancels) never
        // produces a debug session: the execution pipeline fires processNotStarted
        // instead. Resolve the matching ticket to never_started at once, so a waiter
        // isn't left polling [TIMEOUT] until the ticket's TTL.
        connection.subscribe(
            ExecutionManager.EXECUTION_TOPIC,
            object : ExecutionListener {
                override fun processNotStarted(executorId: String, env: ExecutionEnvironment) {
                    runCatching { onProcessNotStarted(executorId, env) }
                        .onFailure { log.warn("Crash tripwire: processNotStarted handling failed", it) }
                }
            },
        )
    }

    /**
     * Resolves the pending ticket for a launch that produced no process (before-launch
     * build failed, or cancelled) to never_started. Only debug launches arm a ticket.
     */
    private fun onProcessNotStarted(executorId: String, env: ExecutionEnvironment) {
        if (executorId != DefaultDebugExecutor.EXECUTOR_ID) return
        val configName = env.runnerAndConfigurationSettings?.name ?: env.runProfile.name
        val ticket = synchronized(tickets) {
            tickets.values
                .filter { it.session == null && it.neverStartedReason == null && it.project === env.project && !it.isExpired }
                .filter { it.configName.equals(configName, ignoreCase = true) }
                .maxByOrNull { it.armedAtMs }
        } ?: return
        ticket.markNeverStarted("before-launch build failed or the launch was cancelled")
        log.info("Crash tripwire ${ticket.id}: processNotStarted → never_started (\"$configName\", ${env.project.name})")
    }

    /**
     * Binds a freshly started session to the oldest pending ticket of the SAME
     * project — by configuration name, else the project's single pending ticket
     * (Rider may decorate the session name). Sessions in a project with no
     * pending ticket are left alone; a client can still watch them by handle.
     */
    private fun onSessionStarted(session: XDebugSession) {
        val ticket = synchronized(tickets) {
            val pending = tickets.values
                .filter { it.session == null && it.neverStartedReason == null && it.project === session.project && !it.isExpired }
                .sortedBy { it.armedAtMs }
            pending.filter { !it.predates(session) }
                .let { c -> c.firstOrNull { it.configName.equals(session.sessionName, ignoreCase = true) } ?: c.singleOrNull() }
        } ?: return
        ticket.bind(session)
        watch(session)
        log.info("Crash tripwire ${ticket.id} bound to session \"${session.sessionName}\" (${session.project.name})")
    }

    /**
     * Binds [ticket] by scanning live sessions, for the case where the
     * processStarted event never reaches us (a debug session created through a
     * path that doesn't publish it). Only sessions that appeared AFTER the
     * ticket was armed, in its own project, and not claimed by another ticket
     * are eligible — so this can never adopt the editor that was already
     * running. Returns the bound session, or null while none qualifies.
     */
    fun resolveSession(ticket: Ticket): XDebugSession? {
        ticket.session?.let { return it }
        if (ticket.neverStartedReason != null || ticket.project.isDisposed) return null
        val claimed = synchronized(tickets) { tickets.values.mapNotNull { it.session } }
        val candidates = XDebuggerManager.getInstance(ticket.project).debugSessions
            .filter { s -> !s.isStopped && !ticket.predates(s) && claimed.none { it === s } }
        val session = candidates.firstOrNull { it.sessionName.equals(ticket.configName, ignoreCase = true) }
            ?: candidates.singleOrNull()
            ?: return null
        ticket.bind(session)
        watch(session)
        log.info("Crash tripwire ${ticket.id} bound to session \"${session.sessionName}\" by scan (${session.project.name})")
        return session
    }

    fun positionOf(session: XDebugSession): String? =
        session.currentPosition?.let { "${it.file.name}:${it.line + 1}" }

    fun threadOf(session: XDebugSession): String? =
        runCatching { session.suspendContext?.activeExecutionStack?.displayName }.getOrNull()

    /**
     * Classifies a pause. Returns null when it's an enabled USER breakpoint
     * (the tripwire must ignore those), else a crash-like reason label.
     */
    fun classifyPause(session: XDebugSession): String? {
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
        // doesn't expose the cause cleanly, so label it unhandled_exception and
        // let the caller inspect.
        return "unhandled_exception"
    }
}
