package dev.ridermcp.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolves the project an MCP tool call targets. The MCP server is
 * application-scoped and a single Rider process can host several open solutions
 * (each in its own window), so tools accept an optional `solution` selector —
 * matched against the solution **name or path**. With no selector we auto-pick
 * only when exactly one solution is open; when several are open the caller must
 * disambiguate (see [noSolution]), rather than silently guessing.
 */
internal fun resolveProject(selector: String?): Project? {
    val open = ProjectManager.getInstance().openProjects
    if (selector.isNullOrBlank()) return open.singleOrNull()
    val norm = selector.replace('\\', '/').trimEnd('/')
    return open.firstOrNull { it.name.equals(selector, ignoreCase = true) }
        ?: open.firstOrNull {
            val base = it.basePath?.replace('\\', '/')?.trimEnd('/') ?: return@firstOrNull false
            base.equals(norm, ignoreCase = true) || base.endsWith("/$norm", ignoreCase = true)
        }
}

/**
 * Standard "couldn't pick a solution" reply. Lists the open solutions (name +
 * path) so the caller can retry with an explicit `solution` selector — mirrors
 * how the built-in Rider MCP reports ambiguous project targets.
 */
internal fun noSolution(): CallToolResult {
    val open = ProjectManager.getInstance().openProjects
    val text = if (open.isEmpty()) {
        "No solution is open."
    } else {
        "No matching open solution. Pass 'solution' (name or path) to select one of:\n" +
            open.joinToString("\n") { "  - ${it.name}  (${it.basePath ?: "?"})" }
    }
    return CallToolResult(content = listOf(TextContent(text)))
}

/** Reads a string-valued argument from an MCP tool request payload (nullable). */
internal fun JsonObject?.stringArg(key: String): String? =
    this?.get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

/** Reads an int-valued argument (accepts JSON number or numeric string). */
internal fun JsonObject?.intArg(key: String): Int? =
    this?.get(key)?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }

/** Reads a boolean-valued argument (accepts JSON boolean or "true"/"false" string). */
internal fun JsonObject?.boolArg(key: String): Boolean? =
    this?.get(key)?.let { runCatching { it.jsonPrimitive.let { p -> p.booleanOrNull ?: p.content.toBooleanStrictOrNull() } }.getOrNull() }

/**
 * Reads a string-list argument. Accepts a JSON array of strings, or a single
 * string (treated as a one-element list). Returns an empty list if absent.
 */
internal fun JsonObject?.stringListArg(key: String): List<String> {
    val element = this?.get(key) ?: return emptyList()
    return runCatching { element.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }
        .getOrElse { listOfNotNull(element.jsonPrimitive.contentOrNull) }
}

/** Builds a JSON-Schema object for a tool's input (MCP SDK 0.13 ToolSchema). */
internal fun toolSchema(properties: JsonObject, required: List<String> = emptyList()): ToolSchema =
    ToolSchema(properties = properties, required = required)
