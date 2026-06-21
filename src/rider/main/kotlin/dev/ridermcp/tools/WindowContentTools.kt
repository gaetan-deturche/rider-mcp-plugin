package dev.ridermcp.tools

import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.editor.impl.EditorComponentImpl
import com.intellij.openapi.wm.ToolWindowManager
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
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
            inputSchema = Tool.Input(properties = solutionOnlyProps()),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution"))
                ?: return@addTool noSolution()

            val text = withContext(Dispatchers.EDT) {
                val twm = ToolWindowManager.getInstance(project)
                twm.toolWindowIds.sorted().joinToString("\n") { id ->
                    val tw = twm.getToolWindow(id)
                    val state = if (tw?.isVisible == true) "visible" else "hidden"
                    val tabs = tw?.contentManager?.contentCount ?: 0
                    "$id  [$state, $tabs tab(s)]"
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
                "Returns the trailing portion when large.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("id", strProp("Tool window id, as reported by list_tool_windows."))
                    put("tab", numProp("Optional: content tab index (defaults to the selected tab)."))
                    put("maxChars", numProp("Optional: max characters to return (default $DEFAULT_MAX_CHARS)."))
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
            val maxChars = request.arguments.intArg("maxChars") ?: DEFAULT_MAX_CHARS

            val raw = withContext(Dispatchers.EDT) {
                val tw = ToolWindowManager.getInstance(project).getToolWindow(id) ?: return@withContext null
                val cm = tw.contentManager
                val content = if (tab != null) cm.getContent(tab) else (cm.selectedContent ?: cm.contents.firstOrNull())
                content?.component?.let { extractText(it) }
            }

            val text = when {
                raw == null -> "No tool window '$id' (or no readable content). Try list_tool_windows."
                raw.isBlank() -> "Tool window '$id' has no extractable text content."
                else -> tail(raw, maxChars)
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
            inputSchema = Tool.Input(properties = solutionOnlyProps()),
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
                "else the currently selected process.",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("index", numProp("Optional: process index from list_processes."))
                    put("name", strProp("Optional: substring match against the process display name."))
                    put("maxChars", numProp("Optional: max characters to return (default $DEFAULT_MAX_CHARS)."))
                    put("solution", solutionProp())
                },
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution"))
                ?: return@addTool noSolution()
            val index = request.arguments.intArg("index")
            val name = request.arguments.stringArg("name")
            val maxChars = request.arguments.intArg("maxChars") ?: DEFAULT_MAX_CHARS

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
                else -> tail(raw, maxChars)
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

    /** Keeps the trailing [maxChars] (recent output matters most for logs). */
    private fun tail(text: String, maxChars: Int): String =
        if (text.length <= maxChars) text
        else "…[truncated ${text.length - maxChars} earlier chars]\n" + text.takeLast(maxChars)

    private fun noSolution() =
        CallToolResult(content = listOf(TextContent("No matching open solution.")))

    private fun solutionOnlyProps() = buildJsonObject { put("solution", solutionProp()) }
    private fun solutionProp() = strProp("Optional: target solution name when several are open.")
    private fun strProp(desc: String) = buildJsonObject { put("type", "string"); put("description", desc) }
    private fun numProp(desc: String) = buildJsonObject { put("type", "number"); put("description", desc) }
}
