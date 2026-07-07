package dev.ridermcp.tools

import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.wm.ToolWindowManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.awt.Component
import java.awt.Container
import javax.swing.text.JTextComponent

/**
 * MCP tools that expose the *content of Rider's tool windows and process
 * consoles* — build output, run/debug process logs, Problems, VCS console, etc.
 *
 * These are pure IntelliJ-frontend reads (no RD backend involved). Tool windows
 * and consoles render into Swing components on the EDT, so text extraction runs
 * inside `withContext(Dispatchers.EDT)` and walks the component tree pulling
 * text out of editors and text components.
 */
object WindowContentTools {

    private const val DEFAULT_MAX_CHARS = 20_000

    fun register(server: Server) {
        registerListToolWindows(server)
        registerReadToolWindow(server)
        registerListProcesses(server)
        registerReadProcessOutput(server)
    }

    // -- list_tool_windows ---------------------------------------------------

    private fun registerListToolWindows(server: Server) {
        server.addTool(
            name = "list_tool_windows",
            description = "Lists Rider tool windows (Build, Debug, Run, Problems View, " +
                "Terminal, Version Control, …) with visibility and tab count. Use the " +
                "ids here with read_tool_window.",
            inputSchema = toolSchema(properties = solutionOnlyProps()),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution"))
                ?: return@addTool noSolution()

            val text = withContext(Dispatchers.EDT) {
                val twm = ToolWindowManager.getInstance(project)
                // Only read visibility/availability — do NOT touch contentManager
                // here, as that force-initializes every tool window's content,
                // which can throw (e.g. "Backup and Sync History") and pop a modal
                // dialog that blocks the EDT.
                twm.toolWindowIds.sorted().joinToString("\n") { id ->
                    val tw = twm.getToolWindow(id)
                    val state = when {
                        tw == null -> "unavailable"
                        tw.isVisible -> "visible"
                        tw.isAvailable -> "available"
                        else -> "hidden"
                    }
                    "$id  [$state]"
                }
            }
            CallToolResult(content = listOf(TextContent(text.ifEmpty { "(no tool windows)" })))
        }
    }

    // -- read_tool_window ----------------------------------------------------

    private fun registerReadToolWindow(server: Server) {
        server.addTool(
            name = "read_tool_window",
            description = "Reads the text content currently shown in a tool window " +
                "(e.g. id='Build' for build output, 'Problems View', 'Version Control'). " +
                "Defaults to the trailing portion; page with offset/count (lines).",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("id", strProp("Tool window id, as reported by list_tool_windows."))
                    put("tab", numProp("Optional: content tab index (defaults to the selected tab)."))
                    put("offset", offsetProp())
                    put("count", countProp())
                    put("maxChars", numProp("Optional: payload cap in characters (default $DEFAULT_MAX_CHARS)."))
                    put("solution", solutionProp())
                },
                required = listOf("id"),
            ),
        ) { request ->
            val id = request.arguments.stringArg("id").orEmpty()
            if (id.isBlank()) {
                return@addTool CallToolResult(content = listOf(TextContent("'id' is required (see list_tool_windows).")))
            }
            val project = resolveProject(request.arguments.stringArg("solution"))
                ?: return@addTool noSolution()
            val tab = request.arguments.intArg("tab")

            val raw = withContext(Dispatchers.EDT) {
                val tw = ToolWindowManager.getInstance(project).getToolWindow(id) ?: return@withContext null
                val cm = tw.contentManager
                val content = if (tab != null) cm.getContent(tab) else (cm.selectedContent ?: cm.contents.firstOrNull())
                content?.component?.let { extractText(it) }
            }

            val text = when {
                raw == null -> "No tool window '$id' (or no readable content). Try list_tool_windows."
                raw.isBlank() -> "Tool window '$id' has no extractable text content."
                else -> window(raw, request.arguments)
            }
            CallToolResult(content = listOf(TextContent(text)))
        }
    }

    // -- list_processes ------------------------------------------------------

    private fun registerListProcesses(server: Server) {
        server.addTool(
            name = "list_processes",
            description = "Lists run/debug processes and their consoles (each is a tab in " +
                "the Run/Debug tool window). Use the index with read_process_output.",
            inputSchema = toolSchema(properties = solutionOnlyProps()),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution"))
                ?: return@addTool noSolution()

            val text = withContext(Dispatchers.EDT) {
                RunContentManager.getInstance(project).allDescriptors.mapIndexed { i, d ->
                    "[$i] ${d.displayName}  — ${processState(d)}"
                }.joinToString("\n")
            }
            CallToolResult(content = listOf(TextContent(text.ifEmpty { "(no run/debug processes)" })))
        }
    }

    // -- read_process_output -------------------------------------------------

    private fun registerReadProcessOutput(server: Server) {
        server.addTool(
            name = "read_process_output",
            description = "Reads the console output of a run/debug process (the debug " +
                "process log / program output). Selects by index, then by name match, " +
                "else the currently selected process. Page with offset/count (lines).",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("index", numProp("Optional: process index from list_processes."))
                    put("name", strProp("Optional: substring match against the process display name."))
                    put("offset", offsetProp())
                    put("count", countProp())
                    put("maxChars", numProp("Optional: payload cap in characters (default $DEFAULT_MAX_CHARS)."))
                    put("solution", solutionProp())
                },
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution"))
                ?: return@addTool noSolution()
            val index = request.arguments.intArg("index")
            val name = request.arguments.stringArg("name")

            val raw = withContext(Dispatchers.EDT) {
                val mgr = RunContentManager.getInstance(project)
                val all = mgr.allDescriptors
                val descriptor: RunContentDescriptor? = when {
                    index != null -> all.getOrNull(index)
                    name != null -> all.firstOrNull { it.displayName.contains(name, ignoreCase = true) }
                    else -> mgr.selectedContent ?: all.firstOrNull()
                }
                descriptor?.executionConsole?.component?.let { extractText(it) }
            }

            val text = when {
                raw == null -> "No matching process console. Try list_processes."
                raw.isBlank() -> "Process console is empty."
                else -> window(raw, request.arguments)
            }
            CallToolResult(content = listOf(TextContent(text)))
        }
    }

    // -- helpers -------------------------------------------------------------

    private fun processState(d: RunContentDescriptor): String {
        val ph = d.processHandler ?: return "no process"
        return when {
            ph.isProcessTerminated -> "terminated" + (ph.exitCode?.let { " (exit $it)" } ?: "")
            ph.isProcessTerminating -> "stopping"
            else -> "running"
        }
    }

    /**
     * Walks a Swing component tree and concatenates text from editors and text
     * components. Editors back most consoles and the Build output view; plain
     * text components cover simpler panels.
     */
    private fun extractText(root: Component): String {
        val sb = StringBuilder()
        fun walk(c: Component) {
            when (c) {
                is EditorComponentImpl -> { sb.append(c.editor.document.text).append('\n'); return }
                is JTextComponent -> { sb.append(c.text).append('\n'); return }
            }
            if (c is Container) c.components.forEach(::walk)
        }
        walk(root)
        return sb.toString().trimEnd()
    }

    /**
     * Renders the requested slice of [text].
     *
     * Pagination is line-based via the `offset`/`count` args (logs are
     * line-oriented). `offset` is a 0-based line index; negative counts from the
     * end (e.g. -100 = last 100 lines). `count` bounds the number of lines.
     * `maxChars` is a final payload cap regardless of line selection.
     *
     * With no `offset`/`count`, behaviour is unchanged: the char-capped tail.
     * A `[lines X–Y of N]` header is prefixed when windowing, so the client can
     * page (request offset=Y next, etc.).
     */
    private fun window(text: String, args: kotlinx.serialization.json.JsonObject?): String {
        val offset = args.intArg("offset")
        val count = args.intArg("count")
        val maxChars = args.intArg("maxChars") ?: DEFAULT_MAX_CHARS

        if (offset == null && count == null) return tail(text, maxChars)

        val lines = text.split('\n')
        val total = lines.size
        val start = when {
            offset == null -> 0
            offset < 0 -> maxOf(0, total + offset)
            else -> minOf(offset, total)
        }
        val end = if (count != null) minOf(total, start + maxOf(0, count)) else total
        val slice = lines.subList(start, end).joinToString("\n")
        val body = if (slice.length > maxChars) {
            "…[char-capped at $maxChars]\n" + slice.takeLast(maxChars)
        } else {
            slice
        }
        val from = if (total == 0) 0 else start + 1
        return "[lines $from–$end of $total]\n$body"
    }

    /** Keeps the trailing [maxChars] (recent output matters most for logs). */
    private fun tail(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text
        else "…[truncated ${text.length - maxChars} earlier chars]\n" + text.takeLast(maxChars)

    private fun solutionOnlyProps() = buildJsonObject { put("solution", solutionProp()) }
    private fun solutionProp() = strProp("Target solution name or path; required when several solutions are open in one Rider instance.")
    private fun offsetProp() = numProp("Optional: 0-based start line; negative counts from the end (e.g. -100 = last 100 lines).")
    private fun countProp() = numProp("Optional: number of lines to return from offset.")
    private fun strProp(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }
    private fun numProp(desc: String) = buildJsonObject { put("type", "number"); put("description", desc) }
}
