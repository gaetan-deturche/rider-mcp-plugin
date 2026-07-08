package dev.ridermcp.tools

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.jetbrains.rd.util.lifetime.LifetimeDefinition
import com.jetbrains.rider.projectView.solution
import dev.ridermcp.model.BackendStatus
import dev.ridermcp.model.BuildProjectParams
import dev.ridermcp.model.BuildProjectResult
import dev.ridermcp.model.BuildStartResult
import dev.ridermcp.model.RiderMcpModel
import dev.ridermcp.model.riderMcpModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Project-scoped bridge to the .NET backend over the RD protocol.
 *
 * The generated model accessor (`solution.riderMcpModel`) and the types
 * (`RiderMcpModel`, `BackendStatus`) are produced by `:protocol`'s rdgen at
 * build time — they only resolve after `./gradlew :protocol:rdgen` has run.
 *
 * RD calls must be issued on the protocol scheduler, which in the frontend is
 * the EDT; [callBackend] enforces that and suspends until the backend replies.
 */
@Service(Service.Level.PROJECT)
class DebugDataProvider(private val project: Project) : Disposable {

    private val log = logger<DebugDataProvider>()

    // Tied to service disposal so in-flight RD calls are cancelled on shutdown.
    private val lifetimeDef = LifetimeDefinition()

    /** Diagnostic snapshot from the backend, or null if it isn't connected. */
    suspend fun backendStatus(): BackendStatus? = callBackend { model ->
        model.getBackendStatus.startSuspending(lifetimeDef.lifetime, Unit)
    }

    /**
     * Starts building the named project(s) and returns immediately with a build
     * handle (the build runs in the background). Poll [getBuildStatus] with the
     * returned buildId. Returns null if the backend isn't connected.
     */
    suspend fun startBuildProject(projectNames: List<String>, rebuild: Boolean, withoutDependencies: Boolean): BuildStartResult? =
        callBackend { model ->
            model.startBuildProject.startSuspending(
                lifetimeDef.lifetime,
                BuildProjectParams(projectNames, rebuild, withoutDependencies),
            )
        }

    /** Snapshot of a build's status by buildId, or null if the backend isn't connected. */
    suspend fun getBuildStatus(buildId: String): BuildProjectResult? =
        callBackend { model -> model.getBuildStatus.startSuspending(lifetimeDef.lifetime, buildId) }

    /**
     * Switches to the protocol scheduler (EDT), resolves the bound model for
     * this solution, and runs [block]. Returns null when the solution has no
     * RiderMcp model bound yet (backend still starting / not a Rider solution).
     */
    private suspend fun <T> callBackend(block: suspend (RiderMcpModel) -> T): T? =
        withContext(Dispatchers.EDT) {
            val model = runCatching { project.solution.riderMcpModel }.getOrNull()
            if (model == null) {
                log.warn("RiderMcp backend model not bound for '${project.name}'")
                null
            } else {
                block(model)
            }
        }

    override fun dispose() {
        lifetimeDef.terminate()
    }
}
