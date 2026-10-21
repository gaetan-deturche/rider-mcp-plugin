package dev.ridermcp.tools

import com.intellij.execution.RunManager
import com.intellij.execution.RunnerAndConfigurationSettings
import com.intellij.openapi.application.EDT
import com.intellij.openapi.project.Project
import com.jetbrains.rider.run.devices.ActiveDeviceManager
import com.jetbrains.rider.run.devices.CompatibilityProblem
import com.jetbrains.rider.run.devices.Device
import com.jetbrains.rider.run.devices.DevicesConfiguration
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.types.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Collections
import java.util.WeakHashMap

/**
 * MCP tools for the run-target device selector (the toolbar combo next to the run
 * configuration). Its list comes from the DevicesProvider of the SELECTED run
 * configuration, so a configuration is selected before its devices are read.
 * Only Rider's generic device API is used: any provider works the same way.
 */
object DeviceTools {

    private const val LOAD_TIMEOUT_MS = 20_000L
    private const val READBACK_MS = 5_000L

    fun register(server: Server) {
        server.addTool(
            name = "list_devices",
            description = "Lists the devices a run configuration can launch on (the toolbar device selector " +
                "next to the run configuration) and marks the active one. Only configurations with a device " +
                "selector have any. Selects the configuration in the toolbar (its devices only load while it " +
                "is selected). Use set_device, or run_configuration(device=...), to pick one.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Run/debug configuration name. Omit to use the selected one.")
                    })
                    put("solution", buildJsonObject {
                        put("type", "string")
                        put("description", "Target solution name or path; required when several solutions are open in one Rider instance.")
                    })
                },
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            val settings = when (val s = settingsFor(project, request.arguments.stringArg("name"))) {
                is Lookup.Found -> s.settings
                is Lookup.Error -> return@addTool text(s.message)
            }
            val devices = when (val l = load(project, settings)) {
                is Load.Ok -> l.devices
                is Load.Error -> return@addTool text(l.message)
            }
            if (devices.isEmpty()) return@addTool text("No devices found for \"${settings.name}\".")
            text("Devices for \"${settings.name}\" in '${project.name}':\n" + describe(devices, activeDevice(project)))
        }

        server.addTool(
            name = "set_device",
            description = "Sets the device a run configuration launches on (the toolbar device selector), " +
                "verified by reading it back. Matching is case-insensitive: an exact name, else a unique " +
                "substring. Selects the configuration in the toolbar first. A later run_configuration launch " +
                "(restore=true, default) puts the user's own device and configuration back when its session " +
                "ends. Prefer run_configuration(device=...) for a launch.",
            inputSchema = toolSchema(
                properties = buildJsonObject {
                    put("device", buildJsonObject {
                        put("type", "string")
                        put("description", "Device name as listed by list_devices (exact, or a unique substring).")
                    })
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "Run/debug configuration name. Omit to use the selected one.")
                    })
                    put("solution", buildJsonObject {
                        put("type", "string")
                        put("description", "Target solution name or path; required when several solutions are open in one Rider instance.")
                    })
                },
                required = listOf("device"),
            ),
        ) { request ->
            val project = resolveProject(request.arguments.stringArg("solution")) ?: return@addTool noSolution()
            val wanted = request.arguments.stringArg("device")?.trim().orEmpty()
            if (wanted.isEmpty()) return@addTool text("'device' is required.")
            val settings = when (val s = settingsFor(project, request.arguments.stringArg("name"))) {
                is Lookup.Found -> s.settings
                is Lookup.Error -> return@addTool text(s.message)
            }
            when (val a = apply(project, settings, wanted)) {
                is Apply.Error -> text(a.message)
                is Apply.Done ->
                    if (a.previous == a.device) text("Already active for \"${settings.name}\": ${a.device.name}.")
                    else text("[DEVICE set] \"${settings.name}\" → ${a.device.name}  (was ${a.previous?.name ?: "<none>"}) in '${project.name}'.")
            }
        }
    }

    sealed class Apply {
        class Done(val device: Device, val previous: Device?) : Apply()
        class Error(val message: String) : Apply()
    }

    /** Selects [settings], picks [wanted] among its devices, sets it and reads it back. */
    suspend fun apply(project: Project, settings: RunnerAndConfigurationSettings, wanted: String): Apply {
        val devices = when (val l = load(project, settings)) {
            is Load.Ok -> l.devices
            is Load.Error -> return Apply.Error(l.message)
        }
        val exact = devices.filter { it.first.name.equals(wanted, ignoreCase = true) }
        val hits = exact.ifEmpty { devices.filter { it.first.name.contains(wanted, ignoreCase = true) } }
        val listing = describe(devices, activeDevice(project))
        val (device, problem) = when (hits.size) {
            1 -> hits[0]
            0 -> return Apply.Error("No device of \"${settings.name}\" matches \"$wanted\". Available:\n$listing")
            else -> return Apply.Error("\"$wanted\" matches several devices — use the full name:\n" +
                describe(hits, activeDevice(project)))
        }
        if (problem != null) return Apply.Error("Device \"${device.name}\" can't run \"${settings.name}\": ${problem.message}")

        val manager = ActiveDeviceManager.getInstance(project)
        val previous = activeDevice(project)
        if (previous != device) withContext(Dispatchers.EDT) {
            val ov = overrides.getOrPut(project) { Override() }
            // Only a device the user hasn't touched since keeps its baseline.
            if (ov.mcpDevice == null || ov.mcpDevice != previous) ov.userDevice = previous
            ov.mcpDevice = device
            manager.setActiveDevice(device)
        }
        // The selector updates asynchronously after setActiveDevice.
        withTimeoutOrNull(READBACK_MS) { while (activeDevice(project) != device) delay(100) }
            ?: return Apply.Error("Asked for device \"${device.name}\" but the selector shows " +
                "\"${activeDevice(project)?.name ?: "<none>"}\".")
        return Apply.Done(device, previous)
    }

    fun activeDevice(project: Project): Device? = ActiveDeviceManager.getInstance(project).getDevice<Device>()

    // -- MCP override tracking ------------------------------------------------

    /** MCP-made changes to the selected run configuration and its device; user* = what the user had before. */
    private class Override {
        var userConfig: RunnerAndConfigurationSettings? = null
        var mcpConfig: RunnerAndConfigurationSettings? = null
        var userDevice: Device? = null
        var mcpDevice: Device? = null
    }

    private val overrides: MutableMap<Project, Override> = Collections.synchronizedMap(WeakHashMap())

    /**
     * Puts back the user's device, then their selected run configuration, each only
     * if the toolbar still shows what MCP set. Call on the EDT. Returns what was done,
     * or null when there was nothing to restore.
     */
    fun restoreBaseline(project: Project): String? {
        val ov = overrides.remove(project) ?: return null
        val done = mutableListOf<String>()
        val userDevice = ov.userDevice
        if (ov.mcpDevice != null && userDevice != null && activeDevice(project) == ov.mcpDevice) {
            ActiveDeviceManager.getInstance(project).setActiveDevice(userDevice)
            done += "device restored to ${userDevice.name}"
        }
        val rm = RunManager.getInstance(project)
        val userConfig = ov.userConfig
        if (userConfig != null && ov.mcpConfig != null && rm.selectedConfiguration === ov.mcpConfig && userConfig !== ov.mcpConfig) {
            rm.selectedConfiguration = userConfig
            done += "run configuration reselected: ${userConfig.name}"
        }
        return done.takeIf { it.isNotEmpty() }?.joinToString("; ")
    }

    /** The user's device a restore would put back, or null when MCP hasn't changed it. */
    fun baselineLabel(project: Project): String? = overrides[project]?.takeIf { it.mcpDevice != null }?.userDevice?.name

    // -- helpers --------------------------------------------------------------

    private sealed class Lookup {
        class Found(val settings: RunnerAndConfigurationSettings) : Lookup()
        class Error(val message: String) : Lookup()
    }

    private fun settingsFor(project: Project, name: String?): Lookup {
        val rm = RunManager.getInstance(project)
        val wanted = name?.trim().orEmpty()
        if (wanted.isEmpty()) return rm.selectedConfiguration?.let { Lookup.Found(it) }
            ?: Lookup.Error("No run configuration is selected in '${project.name}' — pass 'name'.")
        return rm.findConfigurationByName(wanted)?.let { Lookup.Found(it) }
            ?: Lookup.Error("No run/debug configuration named \"$wanted\" in '${project.name}'.")
    }

    private sealed class Load {
        class Ok(val devices: List<Pair<Device, CompatibilityProblem?>>) : Load()
        class Error(val message: String) : Load()
    }

    private suspend fun load(project: Project, settings: RunnerAndConfigurationSettings): Load {
        if (settings.configuration !is DevicesConfiguration)
            return Load.Error("\"${settings.name}\" [${settings.type.displayName}] has no device selector.")
        select(project, settings)
        val byKind = withTimeoutOrNull(LOAD_TIMEOUT_MS) { ActiveDeviceManager.getInstance(project).getDevices() }
            ?: return Load.Error("The device list of \"${settings.name}\" did not load within ${LOAD_TIMEOUT_MS / 1000}s.")
        return Load.Ok(byKind.values.flatten())
    }

    /** Makes [settings] the selected run configuration, tracking the user's own for restore. */
    private suspend fun select(project: Project, settings: RunnerAndConfigurationSettings) = withContext(Dispatchers.EDT) {
        val rm = RunManager.getInstance(project)
        val current = rm.selectedConfiguration
        val prior = overrides[project]
        // Only a selection the user hasn't touched since keeps its baseline.
        val ov = if (prior != null && prior.mcpConfig === current) prior else Override().also {
            it.userConfig = current
            overrides[project] = it
        }
        ov.mcpConfig = settings
        if (current !== settings) rm.selectedConfiguration = settings
    }

    private fun describe(devices: List<Pair<Device, CompatibilityProblem?>>, active: Device?): String =
        devices.joinToString("\n") { (device, problem) ->
            val mark = if (device == active) "  *active*" else ""
            val issue = problem?.let { "  (unavailable: ${it.message})" } ?: ""
            "  - ${device.name}  [${device.kind.name}]$mark$issue"
        }

    private fun text(s: String) = CallToolResult(content = listOf(TextContent(s)))
}
