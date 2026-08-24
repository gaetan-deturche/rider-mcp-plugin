package dev.ridermcp.tools

import com.intellij.execution.CommonProgramRunConfigurationParameters
import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.application.EDT
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.project.Project
import com.jetbrains.rider.projectView.SolutionConfigurationManager
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File

/**
 * MCP control over the CommandLineArguments plugin (com.github.rebel000.cmdlineargs).
 *
 * That plugin OVERRIDES the run configuration's program parameters at launch
 * with the checked nodes of its argument tree, so editing the run config is
 * pointless while it's enabled — the tree is the source of truth. Its state is
 * a plain JSON file (<basePath>/<solution>.cmdlineargs.json); writes here edit
 * that file and then invoke the plugin's own reload() (reflectively, via its
 * classloader — same pattern as the UnrealLink hot-reload), which is exactly
 * the plugin's Reload toolbar button. Reads use its public ArgumentsService API.
 *
 * Caveat shared by all writes: reload() re-reads the file, so plugin-UI edits
 * not yet saved (it saves deferred, ~1s) are replaced — same as the user
 * pressing Reload.
 */
object CmdLineArgsTools {

    private const val PLUGIN_ID = "com.github.rebel000.cmdlineargs"
    private const val SERVICE_FQN = "com.github.rebel000.cmdlineargs.ArgumentsService"
    private const val CUSTOM_NODE = "MCP"

    private val json = Json { prettyPrint = true }

    fun register(server: Server) {
        server.addTool(
            name = "list_command_line_args",
            description = "Shows the CommandLineArguments plugin's argument tree (the source of truth " +
                "for launch args while that plugin is enabled — it overrides the run configuration's " +
                "parameters at start): every node with its path, checked state and filters, plus the " +
                "effective argument string for the selected run configuration. Use the paths with " +
                "toggle_command_line_arg.",
            inputSchema = toolSchema(properties = buildJsonObject { put("solution", solutionProp()) }),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            val svc = argumentsService(project)
                ?: return@addTool withContext(Dispatchers.EDT) {
                    val sel = RunManager.getInstanceIfCreated(project)?.selectedConfiguration
                    val direct = sel?.let { getDirectArgs(project, it) }
                    text(
                        "CommandLineArguments plugin ($PLUGIN_ID) is NOT active in '${project.name}' — the run " +
                            "configuration's own program parameters apply." +
                            (sel?.let { "\nselected config \"${it.name}\" parameters: ${direct ?: "<unreadable>"}" } ?: "") +
                            "\nUse set_custom_command_line_args to set them directly on the configuration."
                    )
                }

            val enabled = runCatching {
                svc.javaClass.methods.first { it.name == "isEnabled" }.invoke(svc) as Boolean
            }.getOrNull()
            val effective = withContext(Dispatchers.EDT) {
                RunManager.getInstanceIfCreated(project)?.selectedConfiguration?.let { sel ->
                    runCatching {
                        svc.javaClass.methods.first { it.name == "getArguments" }.invoke(svc, sel) as String
                    }.getOrNull()?.let { "\"${sel.name}\" -> $it" }
                }
            }

            val sb = StringBuilder("CommandLineArguments in '${project.name}' (plugin ${if (enabled == true) "ENABLED" else "disabled"})")
            effective?.let { sb.append("\neffective for selected config: $it") }
            // Per-configuration enablement: args only apply to configurations whose own
            // checkbox is on — a checked argument tree with all configs unchecked yields
            // an empty effective string, which is invisible without this section.
            val configs = withContext(Dispatchers.EDT) { registeredConfigs(svc) }
            if (configs.isNotEmpty()) {
                sb.append("\nconfigurations (args apply only to CHECKED ones — enable_command_line_config):")
                configs.forEach { (id, checked, trusted, args) ->
                    val flags = buildList {
                        if (!trusted) add("untrusted")
                    }.joinToString(" ")
                    sb.append("\n  ${if (checked) "[x]" else "[ ]"} $id${if (flags.isEmpty()) "" else "  ($flags)"}${if (checked && args.isNotEmpty()) "  -> $args" else ""}")
                }
            } else {
                sb.append("\nconfigurations: (none registered yet — solution still loading?)")
            }
            val file = stateFile(project)
            if (!file.exists()) {
                sb.append("\n(no state file yet: ${file.path})")
            } else {
                sb.append("\ntree (${file.name}):")
                val root = json.parseToJsonElement(file.readText()).jsonObject["root"]?.jsonObject
                renderItems(root?.get("items")?.jsonArray, "", sb)
            }
            text(sb.toString())
        }

        server.addTool(
            name = "toggle_command_line_arg",
            description = "Checks/unchecks one node of the CommandLineArguments plugin's argument tree " +
                "by its path from list_command_line_args (segments joined with '/', e.g. " +
                "\"render/-AttachPix\"). Edits the .cmdlineargs.json state file and triggers the " +
                "plugin's reload, so the change applies to the next launch.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("path", buildJsonObject {
                        put("type", "string")
                        put("description", "Node path, '/'-joined names from list_command_line_args.")
                    })
                    put("checked", buildJsonObject {
                        put("type", "boolean")
                        put("description", "true = enable the argument node, false = disable.")
                    })
                    put("solution", solutionProp())
                },
                required = listOf("path", "checked"),
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            argumentsService(project)
                ?: return@addTool text("CommandLineArguments plugin ($PLUGIN_ID) is not installed/enabled in '${project.name}'.")
            val path = request.arguments.stringArg("path")?.trim().orEmpty()
            if (path.isEmpty()) return@addTool text("'path' is required.")
            val checked = request.arguments.boolArg("checked")
                ?: return@addTool text("'checked' is required.")

            val file = stateFile(project)
            if (!file.exists()) return@addTool text("No state file (${file.path}) — the plugin has no arguments yet.")
            val doc = json.parseToJsonElement(file.readText()).jsonObject
            val rootObj = doc["root"]?.jsonObject ?: return@addTool text("Malformed state file: no 'root'.")
            val newItems = toggleAtPath(rootObj["items"]?.jsonArray ?: JsonArray(emptyList()), path.split('/'), checked)
                ?: return@addTool text("No node at path \"$path\". Check list_command_line_args for exact names.")

            writeAndReload(project, file, doc, rootObj, newItems)
            text("[ARG ${if (checked) "enabled" else "disabled"}] \"$path\" in ${file.name}; plugin reloaded — applies to the next launch.")
        }

        server.addTool(
            name = "set_custom_command_line_args",
            description = "Sets ad-hoc launch arguments. With the CommandLineArguments plugin active it " +
                "manages a dedicated top-level \"$CUSTOM_NODE\" node (created/replaced, checked by default; " +
                "args=\"\" removes it) and leaves the rest of the user's tree untouched — the plugin " +
                "overrides run-config parameters at launch, so this is the only path that works then. With " +
                "the plugin inactive it FALLS BACK to writing the run configuration's own program " +
                "parameters directly (the 'config' name, default: selected configuration). Applies to the " +
                "next launch.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("args", buildJsonObject {
                        put("type", "string")
                        put("description", "Argument string, e.g. \"-dpcvars=r.slcp.X=1 -log\". Empty removes the $CUSTOM_NODE node.")
                    })
                    put("checked", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Whether the $CUSTOM_NODE node is active (default true). Plugin path only.")
                    })
                    put("config", buildJsonObject {
                        put("type", "string")
                        put("description", "Run configuration name for the direct fallback when the plugin is inactive (default: the selected configuration). Ignored while the plugin is active.")
                    })
                    put("solution", solutionProp())
                },
                required = listOf("args"),
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            val args = request.arguments.stringArg("args")?.trim().orEmpty()
            val checked = request.arguments.boolArg("checked") ?: true
            if (argumentsService(project) == null) {
                // Plugin inactive: set the run configuration's own program parameters —
                // nothing overrides them at launch in this case.
                val runManager = RunManager.getInstanceIfCreated(project)
                    ?: return@addTool text("No run manager for '${project.name}'.")
                val cfgName = request.arguments.stringArg("config")?.trim().orEmpty()
                val settings = if (cfgName.isEmpty()) runManager.selectedConfiguration
                else runManager.findConfigurationByName(cfgName)
                settings ?: return@addTool text(
                    if (cfgName.isEmpty()) "No selected run configuration — pass 'config'."
                    else "No run configuration named \"$cfgName\"."
                )
                val where = withContext(Dispatchers.EDT) { setDirectArgs(project, settings, args) }
                    ?: return@addTool text(
                        "Configuration \"${settings.name}\" [${settings.type.displayName}] exposes no writable " +
                            "program-parameters field this tool knows — set it in the run config UI."
                    )
                return@addTool text(
                    "[ARGS set · direct] \"${settings.name}\" $where = \"$args\" (CommandLineArguments plugin " +
                        "inactive, wrote the configuration directly) — applies to the next launch."
                )
            }

            val file = stateFile(project)
            val doc = if (file.exists()) json.parseToJsonElement(file.readText()).jsonObject
            else buildJsonObject { put("revision", 3); put("enabled", true); put("preview", false); put("root", buildJsonObject { put("items", JsonArray(emptyList())) }) }
            val rootObj = doc["root"]?.jsonObject ?: return@addTool text("Malformed state file: no 'root'.")
            val items = (rootObj["items"]?.jsonArray ?: JsonArray(emptyList()))
                .filterNot { it.jsonObject["name"]?.jsonPrimitive?.contentOrNull == CUSTOM_NODE }
                .toMutableList()
            if (args.isNotEmpty()) {
                items.add(buildJsonObject {
                    put("name", CUSTOM_NODE)
                    put("checked", checked)
                    put("expanded", false)
                    put("items", JsonArray(listOf(buildJsonObject { put("name", args); put("checked", true) })))
                })
            }

            writeAndReload(project, file, doc, rootObj, JsonArray(items))
            text(
                if (args.isEmpty()) "[$CUSTOM_NODE removed] from ${file.name}; plugin reloaded."
                else "[$CUSTOM_NODE ${if (checked) "set" else "set (unchecked)"}] \"$args\" in ${file.name}; plugin reloaded — applies to the next launch."
            )
        }

        server.addTool(
            name = "enable_command_line_config",
            description = "Checks/unchecks run CONFIGURATIONS in the CommandLineArguments plugin — the " +
                "second gate: the argument tree only applies to configurations whose own checkbox is on. " +
                "Pass config=\"*\" to act on all registered configurations (recovery for a wiped " +
                "enablement list), or a configuration id/name from list_command_line_args. Persists and " +
                "rebuilds the effective arguments.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("config", buildJsonObject {
                        put("type", "string")
                        put("description", "Configuration id/name from list_command_line_args, or \"*\" for all.")
                    })
                    put("enabled", buildJsonObject {
                        put("type", "boolean")
                        put("description", "true = check (args apply), false = uncheck.")
                    })
                    put("solution", solutionProp())
                },
                required = listOf("config", "enabled"),
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            val svc = argumentsService(project)
                ?: return@addTool text("CommandLineArguments plugin ($PLUGIN_ID) is not installed/enabled in '${project.name}'.")
            val target = request.arguments.stringArg("config")?.trim().orEmpty()
            if (target.isEmpty()) return@addTool text("'config' is required (id/name, or \"*\").")
            val enabled = request.arguments.boolArg("enabled") ?: return@addTool text("'enabled' is required.")

            val changed = withContext(Dispatchers.EDT) {
                val hits = mutableListOf<String>()
                forEachRegistered(svc) { id, adapter, node ->
                    if (target == "*" || id.equals(target, true) || id.substringAfterLast('.').equals(target, true)) {
                        runCatching {
                            node.javaClass.methods.first { it.name == "setChecked" && it.parameterCount == 1 }.invoke(node, enabled)
                            adapter?.javaClass?.methods?.first { it.name == "setEnabled" && it.parameterCount == 1 }?.invoke(adapter, enabled)
                            hits.add(id)
                        }
                    }
                }
                if (hits.isNotEmpty()) {
                    // reload() rebuilds cachedArgs from the checkbox state and refreshes the UI;
                    // save() persists enabledConfigs so the change survives a restart.
                    svc.javaClass.methods.firstOrNull { it.name == "reload" || it.name.startsWith("reload$") }?.invoke(svc)
                    svc.javaClass.declaredMethods.firstOrNull { it.name == "save" || it.name.startsWith("save$") }
                        ?.also { it.isAccessible = true }?.let { runCatching { it.invoke(svc) } }
                }
                hits
            }
            if (changed.isEmpty()) return@addTool text(
                "No registered configuration matches \"$target\". See list_command_line_args (configurations section)."
            )
            text("[CONFIG ${if (enabled) "enabled" else "disabled"}] ${changed.size} configuration(s): ${changed.joinToString(", ")} — persisted; applies to the next launch.")
        }
    }

    // -- plugin access ---------------------------------------------------------

    private fun argumentsService(project: Project): Any? {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId(PLUGIN_ID)) ?: return null
        if (!descriptor.isEnabled) return null
        val clazz = runCatching { descriptor.pluginClassLoader?.loadClass(SERVICE_FQN) }.getOrNull() ?: return null
        return runCatching { project.getService(clazz) }.getOrNull()
    }

    /**
     * Iterates the service's private perSettingsData registry (uniqueID ->
     * SettingsData(adapter, node)) reflectively — the per-configuration checkbox
     * state lives on those nodes, not in the JSON state file.
     */
    private fun forEachRegistered(svc: Any, action: (id: String, adapter: Any?, node: Any) -> Unit) {
        val field = runCatching { svc.javaClass.getDeclaredField("perSettingsData").also { it.isAccessible = true } }.getOrNull() ?: return
        val map = field.get(svc) as? Map<*, *> ?: return
        for ((id, data) in map) {
            if (id !is String || data == null) continue
            val adapter = runCatching {
                data.javaClass.getDeclaredMethod("getAdapter").also { it.isAccessible = true }.invoke(data)
            }.getOrNull()
            val node = runCatching {
                data.javaClass.getDeclaredMethod("getNode").also { it.isAccessible = true }.invoke(data)
            }.getOrNull() ?: continue
            action(id, adapter, node)
        }
    }

    /** (id, checked, trusted, effectiveArgs) per registered configuration, sorted by id. */
    private fun registeredConfigs(svc: Any): List<ConfigRow> {
        val out = mutableListOf<ConfigRow>()
        forEachRegistered(svc) { id, adapter, node ->
            val checked = runCatching {
                node.javaClass.methods.first { it.name == "isChecked" && it.parameterCount == 0 }.invoke(node) as Boolean
            }.getOrDefault(false)
            val trusted = runCatching {
                adapter?.javaClass?.methods?.first { it.name == "isTrusted" && it.parameterCount == 0 }?.invoke(adapter) as? Boolean
            }.getOrNull() ?: false
            val args = runCatching {
                adapter?.javaClass?.methods?.first { it.name == "getArguments" && it.parameterCount == 0 }?.invoke(adapter) as? String
            }.getOrNull().orEmpty()
            if (adapter != null) out.add(ConfigRow(id, checked, trusted, args))
        }
        return out.sortedBy { it.id }
    }

    /** One row of the configurations section. */
    data class ConfigRow(val id: String, val checked: Boolean, val trusted: Boolean, val args: String)

    /** Mirrors the plugin's locateStateFile() for the Rider branch. */
    private fun stateFile(project: Project) = File(project.basePath, project.name + ".cmdlineargs.json")

    private suspend fun writeAndReload(project: Project, file: File, doc: JsonObject, rootObj: JsonObject, newItems: JsonArray) {
        val newRoot = JsonObject(rootObj.toMutableMap().also { it["items"] = newItems })
        val newDoc = JsonObject(doc.toMutableMap().also { it["root"] = newRoot })
        file.writeText(json.encodeToString(JsonObject.serializer(), newDoc))
        // reload() is `internal` — the Kotlin compiler may mangle it to reload$<module>.
        val svc = argumentsService(project) ?: return
        val reload = svc.javaClass.methods.firstOrNull { it.name == "reload" || it.name.startsWith("reload$") } ?: return
        withContext(Dispatchers.EDT) { runCatching { reload.invoke(svc) } }
    }

    // -- direct run-configuration fallback (plugin inactive) --------------------

    /**
     * Writes [args] straight into the run configuration's program parameters.
     * Generic configs implement CommonProgramRunConfigurationParameters; Rider
     * C++/UE configs store them per configuration|platform in launch parameters,
     * reached reflectively (same internal API the CommandLineArguments plugin
     * uses) so this plugin keeps no rider-cpp compile dependency.
     * Returns a short description of what was written, or null when unsupported.
     */
    private fun setDirectArgs(project: Project, settings: RunnerAndConfigurationSettings, args: String): String? {
        val config = settings.configuration
        if (config is CommonProgramRunConfigurationParameters) {
            config.programParameters = args
            return "program parameters"
        }
        return runCatching {
            val launch = cppLaunchParameters(project, config) ?: return null
            val setter = launch.javaClass.methods.firstOrNull { it.name == "setProgramParameters" && it.parameterCount == 1 }
                ?: return null
            setter.invoke(launch, args)
            val active = SolutionConfigurationManager.tryGetInstance(project)?.activeConfigurationAndPlatform
            "launch parameters (${active?.configuration} | ${active?.platform})"
        }.getOrNull()
    }

    private fun getDirectArgs(project: Project, settings: RunnerAndConfigurationSettings): String? {
        val config = settings.configuration
        if (config is CommonProgramRunConfigurationParameters) return config.programParameters ?: ""
        return runCatching {
            val launch = cppLaunchParameters(project, config) ?: return null
            launch.javaClass.methods.firstOrNull { it.name == "getProgramParameters" && it.parameterCount == 0 }
                ?.invoke(launch) as? String
        }.getOrNull()
    }

    /** Rider C++ config -> the ACTIVE configuration|platform's launch parameters, reflectively. */
    private fun cppLaunchParameters(project: Project, config: Any): Any? {
        val active = SolutionConfigurationManager.tryGetInstance(project)?.activeConfigurationAndPlatform ?: return null
        // PlayStation is presented as "PS5" but stored internally as "Prospero".
        val platform = if (active.platform == "PS5") "Prospero" else active.platform
        val parameters = config.javaClass.methods.firstOrNull { it.name == "getParameters" && it.parameterCount == 0 }
            ?.invoke(config) ?: return null
        val map = parameters.javaClass.methods.firstOrNull { it.name == "getParametersMap" && it.parameterCount == 0 }
            ?.invoke(parameters) ?: return null
        val getParams = map.javaClass.methods.firstOrNull {
            it.name == "getPreSetupParametersForConfigurationAndPlatform" && it.parameterCount == 2
        } ?: map.javaClass.getDeclaredMethod(
            "getPreSetupParametersForConfigurationAndPlatform", String::class.java, String::class.java
        ).also { it.isAccessible = true }
        val mutable = getParams.invoke(map, active.configuration, platform) ?: return null
        return mutable.javaClass.methods.firstOrNull { it.name == "getCurrentLaunchParameters" && it.parameterCount == 0 }
            ?.invoke(mutable)
    }

    // -- json tree helpers -----------------------------------------------------

    /** Returns a copy of [items] with the node at [segments] re-checked, or null when not found. */
    private fun toggleAtPath(items: JsonArray, segments: List<String>, checked: Boolean): JsonArray? {
        if (segments.isEmpty()) return null
        val head = segments.first()
        var found = false
        val out = items.map { el ->
            val obj = el.jsonObject
            if (!found && obj["name"]?.jsonPrimitive?.contentOrNull == head) {
                found = true
                if (segments.size == 1) {
                    JsonObject(obj.toMutableMap().also { it["checked"] = JsonPrimitive(checked) })
                } else {
                    val sub = toggleAtPath(obj["items"]?.jsonArray ?: return null, segments.drop(1), checked) ?: return null
                    JsonObject(obj.toMutableMap().also { it["items"] = sub })
                }
            } else el
        }
        return if (found) JsonArray(out) else null
    }

    private fun renderItems(items: JsonArray?, indent: String, sb: StringBuilder) {
        items?.forEach { el ->
            val obj = el.jsonObject
            val name = obj["name"]?.jsonPrimitive?.contentOrNull ?: return@forEach
            val checked = obj["checked"]?.jsonPrimitive?.let { runCatching { it.boolean }.getOrNull() } == true
            val filters = obj["filters"]?.jsonObject?.entries?.joinToString("; ") { (k, v) ->
                "$k=${v.jsonArray.joinToString(",") { it.jsonPrimitive.contentOrNull ?: "?" }}"
            }
            sb.append("\n$indent${if (checked) "[x]" else "[ ]"} $name")
            filters?.takeIf { it.isNotEmpty() }?.let { sb.append("   {$it}") }
            renderItems(obj["items"]?.jsonArray, "$indent  ", sb)
        }
    }

    private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))
    private fun solutionProp() = buildJsonObject {
        put("type", "string")
        put("description", "Target solution name or path; required when several solutions are open in one Rider instance.")
    }
}
