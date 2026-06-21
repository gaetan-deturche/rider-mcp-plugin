package dev.ridermcp.tools

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.jetbrains.rd.platform.util.idea.ProtocolSubscribedProjectComponent
import com.jetbrains.rdclient.protocol.IProtocolHost

/**
 * Project-scoped bridge to the .NET backend over the RD protocol. The MCP tools
 * call into this to fetch backend-resolved data (symbols, solution status).
 *
 * NOTE: the generated RD model (`RiderMcpModel`) is produced by `:protocol`
 * rdgen at build time. Once generated, obtain it via the solution protocol and
 * expose typed accessors here, e.g.:
 *
 *     val model = project.solution.riderMcpModel
 *     model.getBackendStatus.startSuspending(lifetime, Unit)
 */
@Service(Service.Level.PROJECT)
class DebugDataProvider(private val project: Project) {

    /** True once the backend RD protocol is connected for this solution. */
    fun isBackendConnected(): Boolean =
        runCatching { IProtocolHost.Companion != null }.getOrDefault(false)

    // TODO: implement once generated model is available:
    //   suspend fun backendStatus(): BackendStatus?
    //   suspend fun findSymbols(query: String): List<SymbolInfo>
}
