package dev.ridermcp.tools

import com.intellij.openapi.project.Project
import com.intellij.ui.ColoredTextContainer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.xdebugger.XDebugSession
import com.intellij.xdebugger.XDebuggerManager
import com.intellij.xdebugger.evaluation.XDebuggerEvaluator
import com.intellij.xdebugger.frame.XCompositeNode
import com.intellij.xdebugger.frame.XExecutionStack
import com.intellij.xdebugger.frame.XFullValueEvaluator
import com.intellij.xdebugger.frame.XStackFrame
import com.intellij.xdebugger.frame.XSuspendContext
import com.intellij.xdebugger.frame.XValue
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.frame.XValueNode
import com.intellij.xdebugger.frame.XValuePlace
import com.intellij.xdebugger.frame.presentation.XValuePresentation
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.swing.Icon

/**
 * MCP tools exposing Rider's debugger (XDebugger frontend API): debug status,
 * threads, call stack, local variables (with values), expression evaluation,
 * and breakpoints.
 *
 * The XDebugger read path is callback-based and async; each `await*` helper
 * adapts a callback to a [CompletableDeferred] with a timeout. These run off the
 * EDT (the MCP handler coroutine), which is what the compute* APIs expect.
 */
object DebuggerTools {

    private const val TIMEOUT_MS = 5_000L
    private const val MAX_FRAMES = 50
    private const val MAX_VARS = 200

    fun register(server: Server) {
        registerDebugStatus(server)
        registerThreads(server)
        registerCallStack(server)
        registerLocals(server)
        registerEvaluate(server)
        registerBreakpoints(server)
    }

    // -- debug_status --------------------------------------------------------

    private fun registerDebugStatus(server: Server) {
        server.addTool(
            name = "debug_status",
            description = "Lists active debug sessions: name, running/suspended, and the " +
                "current execution position (file:line) when suspended.",
            inputSchema = toolSchema(properties = solutionOnlyProps()),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            val sessions = XDebuggerManager.getInstance(project).debugSessions
            val text = sessions.joinToString("\n") { s ->
                val state = if (s.isSuspended) "suspended" else "running"
                val pos = s.currentPosition?.let { " @ ${it.file.name}:${it.line + 1}" } ?: ""
                "${s.sessionName} [$state]$pos"
            }.ifEmpty { "(no active debug sessions)" }
            text(text)
        }
    }

    // -- list_threads --------------------------------------------------------

    private fun registerThreads(server: Server) {
        server.addTool(
            name = "list_threads",
            description = "Lists the threads (execution stacks) of the suspended debug session, " +
                "marking the active one. Use the index with get_call_stack.",
            inputSchema = toolSchema(properties = solutionOnlyProps()),
        ) { request ->
            val ctx = suspendedContext(request) ?: return@addTool noSuspended()
            val active = ctx.activeExecutionStack
            val stacks = awaitExecutionStacks(ctx)
            val text = stacks.mapIndexed { i, st ->
                val mark = if (st === active) " *active*" else ""
                "[$i] ${st.displayName}$mark"
            }.joinToString("\n").ifEmpty { "(no threads)" }
            text(text)
        }
    }

    // -- get_call_stack ------------------------------------------------------

    private fun registerCallStack(server: Server) {
        server.addTool(
            name = "get_call_stack",
            description = "Returns the call stack of the suspended session: each frame's " +
                "function and file:line. Defaults to the active thread.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("thread", numProp("Optional: thread index from list_threads (default: active)."))
                    put("maxFrames", numProp("Optional: max frames (default $MAX_FRAMES)."))
                    put("solution", solutionProp())
                },
            ),
        ) { request ->
            val ctx = suspendedContext(request) ?: return@addTool noSuspended()
            val stack = stackForThread(ctx, request.arguments.intArg("thread")) ?: return@addTool text("No such thread.")
            val max = request.arguments.intArg("maxFrames") ?: MAX_FRAMES
            val frames = awaitFrames(stack, max)
            val text = frames.mapIndexed { i, f ->
                val pos = f.sourcePosition?.let { "${it.file.name}:${it.line + 1}" } ?: "<no source>"
                "#$i  ${frameLabel(f)}  ($pos)"
            }.joinToString("\n").ifEmpty { "(empty stack)" }
            text(text)
        }
    }

    // -- get_local_variables -------------------------------------------------

    private fun registerLocals(server: Server) {
        server.addTool(
            name = "get_local_variables",
            description = "Lists the local variables (and fields/params) visible in a stack " +
                "frame, with their values and types. Defaults to the current frame. For nested " +
                "fields, use evaluate (e.g. evaluate 'obj.field').",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("frame", numProp("Optional: frame index from get_call_stack (default: current)."))
                    put("thread", numProp("Optional: thread index (default: active)."))
                    put("solution", solutionProp())
                },
            ),
        ) { request ->
            val session = currentSession(request) ?: return@addTool noSuspended()
            val frame = frameFor(session, request) ?: return@addTool text("No stack frame available.")
            val children = awaitChildren(frame)
            if (children.isEmpty()) return@addTool text("(no variables in this frame)")
            val lines = children.take(MAX_VARS).map { (name, value) -> "$name = ${presentationOf(value)}" }
            text(lines.joinToString("\n"))
        }
    }

    // -- evaluate ------------------------------------------------------------

    private fun registerEvaluate(server: Server) {
        server.addTool(
            name = "evaluate",
            description = "Evaluates an expression in a stack frame of the suspended session " +
                "(e.g. a variable, 'obj.field', 'list.Count'). Defaults to the current frame.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("expression", strProp("The expression to evaluate."))
                    put("frame", numProp("Optional: frame index (default: current)."))
                    put("thread", numProp("Optional: thread index (default: active)."))
                    put("solution", solutionProp())
                },
                required = listOf("expression"),
            ),
        ) { request ->
            val expr = request.arguments.stringArg("expression").orEmpty()
            if (expr.isBlank()) return@addTool text("'expression' is required.")
            val session = currentSession(request) ?: return@addTool noSuspended()
            val frame = frameFor(session, request) ?: return@addTool text("No stack frame available.")
            val evaluator = frame.evaluator ?: return@addTool text("This frame has no evaluator.")
            text(awaitEvaluate(evaluator, expr, frame))
        }
    }

    // -- list_breakpoints ----------------------------------------------------

    private fun registerBreakpoints(server: Server) {
        server.addTool(
            name = "list_breakpoints",
            description = "Lists all breakpoints (type, location, enabled, condition). Works " +
                "without a running session.",
            inputSchema = toolSchema(properties = solutionOnlyProps()),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            val bps = XDebuggerManager.getInstance(project).breakpointManager.allBreakpoints
            val text = bps.joinToString("\n") { bp ->
                val enabled = if (bp.isEnabled) "[x]" else "[ ]"
                val cond = bp.conditionExpression?.expression?.let { "  if ($it)" } ?: ""
                @Suppress("UNCHECKED_CAST")
                val loc = runCatching { (bp.type as com.intellij.xdebugger.breakpoints.XBreakpointType<com.intellij.xdebugger.breakpoints.XBreakpoint<*>, *>).getDisplayText(bp) }
                    .getOrNull() ?: bp.type.title
                "$enabled $loc$cond"
            }.ifEmpty { "(no breakpoints)" }
            text(text)
        }
    }

    // -- session / frame resolution ------------------------------------------

    private fun currentSession(request: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest): XDebugSession? {
        val project: Project = resolveProject(request.arguments.stringArg("solution")) ?: return null
        val mgr = XDebuggerManager.getInstance(project)
        return mgr.currentSession?.takeIf { it.isSuspended }
            ?: mgr.debugSessions.firstOrNull { it.isSuspended }
    }

    private fun suspendedContext(request: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest): XSuspendContext? =
        currentSession(request)?.suspendContext

    private fun stackForThread(ctx: XSuspendContext, threadIndex: Int?): XExecutionStack? {
        if (threadIndex == null) return ctx.activeExecutionStack
        return ctx.executionStacks.getOrNull(threadIndex)
    }

    private suspend fun frameFor(session: XDebugSession, request: io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest): XStackFrame? {
        val frameIndex = request.arguments.intArg("frame")
        if (frameIndex == null) return session.currentStackFrame
        val ctx = session.suspendContext ?: return null
        val stack = stackForThread(ctx, request.arguments.intArg("thread")) ?: return null
        return awaitFrames(stack, frameIndex + 1).getOrNull(frameIndex)
    }

    // -- async adapters ------------------------------------------------------

    private suspend fun awaitExecutionStacks(ctx: XSuspendContext): List<XExecutionStack> {
        val d = CompletableDeferred<List<XExecutionStack>>()
        val acc = mutableListOf<XExecutionStack>()
        ctx.computeExecutionStacks(object : XSuspendContext.XExecutionStackContainer {
            override fun addExecutionStack(stacks: MutableList<out XExecutionStack>, last: Boolean) {
                acc.addAll(stacks); if (last) d.complete(acc.toList())
            }
            override fun errorOccurred(errorMessage: String) { d.complete(acc.toList()) }
        })
        return withTimeoutOrNull(TIMEOUT_MS) { d.await() } ?: ctx.executionStacks.toList()
    }

    private suspend fun awaitFrames(stack: XExecutionStack, max: Int): List<XStackFrame> {
        val d = CompletableDeferred<List<XStackFrame>>()
        val acc = mutableListOf<XStackFrame>()
        stack.topFrame?.let { acc.add(it) }
        stack.computeStackFrames(0, object : XExecutionStack.XStackFrameContainer {
            override fun addStackFrames(frames: MutableList<out XStackFrame>, last: Boolean) {
                for (f in frames) if (acc.none { it === f }) acc.add(f)
                if (last || acc.size >= max) if (!d.isCompleted) d.complete(acc.take(max))
            }
            override fun errorOccurred(errorMessage: String) { if (!d.isCompleted) d.complete(acc.toList()) }
        })
        return withTimeoutOrNull(TIMEOUT_MS) { d.await() } ?: acc.take(max)
    }

    private suspend fun awaitChildren(container: XStackFrame): List<Pair<String, XValue>> {
        val d = CompletableDeferred<List<Pair<String, XValue>>>()
        val acc = mutableListOf<Pair<String, XValue>>()
        container.computeChildren(object : XCompositeNode {
            override fun addChildren(children: XValueChildrenList, last: Boolean) {
                for (i in 0 until children.size()) acc.add(children.getName(i) to children.getValue(i))
                if (last) d.complete(acc.toList())
            }
            @Suppress("OVERRIDE_DEPRECATION") // single-arg tooManyChildren is abstract, so must be overridden
            override fun tooManyChildren(remaining: Int) { d.complete(acc.toList()) }
            override fun setAlreadySorted(alreadySorted: Boolean) {}
            override fun setErrorMessage(errorMessage: String) { d.complete(acc.toList()) }
            override fun setErrorMessage(errorMessage: String, link: com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink?) { d.complete(acc.toList()) }
            override fun setMessage(message: String, icon: Icon?, attributes: SimpleTextAttributes, link: com.intellij.xdebugger.frame.XDebuggerTreeNodeHyperlink?) {}
        })
        return withTimeoutOrNull(TIMEOUT_MS) { d.await() } ?: acc.toList()
    }

    private suspend fun presentationOf(value: XValue): String {
        val d = CompletableDeferred<String>()
        value.computePresentation(object : XValueNode {
            override fun setPresentation(icon: Icon?, type: String?, value: String, hasChildren: Boolean) {
                d.complete(if (type.isNullOrBlank()) value else "$value : $type")
            }
            override fun setPresentation(icon: Icon?, presentation: XValuePresentation, hasChildren: Boolean) {
                val sb = StringBuilder()
                presentation.renderValue(textRenderer(sb))
                d.complete(if (presentation.type.isNullOrBlank()) sb.toString() else "$sb : ${presentation.type}")
            }
            override fun setFullValueEvaluator(fullValueEvaluator: XFullValueEvaluator) {}
        }, XValuePlace.TREE)
        return withTimeoutOrNull(TIMEOUT_MS) { d.await() } ?: "<timeout>"
    }

    private suspend fun awaitEvaluate(evaluator: XDebuggerEvaluator, expr: String, frame: XStackFrame): String {
        val d = CompletableDeferred<XValue?>()
        val err = StringBuilder()
        evaluator.evaluate(expr, object : XDebuggerEvaluator.XEvaluationCallback {
            override fun evaluated(result: XValue) { d.complete(result) }
            override fun errorOccurred(errorMessage: String) { err.append(errorMessage); d.complete(null) }
        }, frame.sourcePosition)
        val value = withTimeoutOrNull(TIMEOUT_MS) { d.await() } ?: return if (err.isNotEmpty()) "error: $err" else "<timeout>"
        return "$expr = ${presentationOf(value)}"
    }

    private fun frameLabel(frame: XStackFrame): String {
        val sb = StringBuilder()
        runCatching {
            frame.customizePresentation(object : ColoredTextContainer {
                override fun append(fragment: String, attributes: SimpleTextAttributes) { sb.append(fragment) }
                override fun append(fragment: String, attributes: SimpleTextAttributes, tag: Any) { sb.append(fragment) }
                override fun setIcon(icon: Icon?) {}
                override fun setToolTipText(text: String?) {}
            })
        }
        return sb.toString().ifBlank { frame.sourcePosition?.let { "${it.file.name}:${it.line + 1}" } ?: "<frame>" }
    }

    private fun textRenderer(sb: StringBuilder) = object : XValuePresentation.XValueTextRenderer {
        override fun renderValue(value: String) { sb.append(value) }
        override fun renderValue(value: String, key: com.intellij.openapi.editor.colors.TextAttributesKey) { sb.append(value) }
        override fun renderStringValue(value: String) { sb.append(value) }
        override fun renderStringValue(value: String, additionalChars: String?, maxLength: Int) { sb.append(value) }
        override fun renderNumericValue(value: String) { sb.append(value) }
        override fun renderKeywordValue(value: String) { sb.append(value) }
        override fun renderComment(comment: String) { sb.append(comment) }
        override fun renderSpecialSymbol(symbol: String) { sb.append(symbol) }
        override fun renderError(error: String) { sb.append(error) }
    }

    private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))
    private fun noSuspended() = text("No suspended debug session. Start debugging and hit a breakpoint first.")

    private fun solutionOnlyProps() = buildJsonObject { put("solution", solutionProp()) }
    private fun solutionProp() = strProp("Target solution name or path; required when several solutions are open in one Rider instance.")
    private fun strProp(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }
    private fun numProp(desc: String) = buildJsonObject { put("type", "number"); put("description", desc) }
}
