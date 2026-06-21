package dev.ridermcp.tools

import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
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

/** Reads a string-valued argument from an MCP tool request payload. */
internal fun JsonObject.stringArg(key: String): String? =
    this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }
