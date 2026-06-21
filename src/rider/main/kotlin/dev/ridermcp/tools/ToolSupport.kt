package dev.ridermcp.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Resolves the project an MCP tool call targets. The MCP server is
 * application-scoped but most data is per-solution, so tools accept an optional
 * `solution` argument; absent that, the single open solution is used.
 */
internal fun resolveProject(name: String?): Project? {
    val open = ProjectManager.getInstance().openProjects
    return if (name.isNullOrBlank()) open.firstOrNull() else open.firstOrNull { it.name == name }
}

/** Reads a string-valued argument from an MCP tool request payload (nullable). */
internal fun JsonObject?.stringArg(key: String): String? =
    this?.get(key)?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

/** Reads an int-valued argument (accepts JSON number or numeric string). */
internal fun JsonObject?.intArg(key: String): Int? =
    this?.get(key)?.let { runCatching { it.jsonPrimitive.content.toInt() }.getOrNull() }

/** Builds a JSON-Schema object for a tool's input (MCP SDK 0.13 ToolSchema). */
internal fun toolSchema(properties: JsonObject, required: List<String> = emptyList()): ToolSchema =
    ToolSchema(properties = properties, required = required)
