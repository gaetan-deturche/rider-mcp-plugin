package model.rider

import com.jetbrains.rd.generator.nova.*
import com.jetbrains.rd.generator.nova.PredefinedType.*
import com.jetbrains.rider.model.nova.ide.SolutionModel

// ---------------------------------------------------------------------------
// RD protocol model — the typed, async contract between the Kotlin frontend
// and the .NET backend. Anything the MCP server needs to pull *from the
// backend* (ReSharper symbol data, build/debug info, etc.) is declared here as
// a call/property/signal, then implemented on the C# side and consumed on the
// Kotlin side.
//
// Keep this minimal and explicit: each member becomes generated code on both
// sides, so add only what the MCP tools actually surface.
// ---------------------------------------------------------------------------

@Suppress("unused")
object RiderMcpRoot : Root()

@Suppress("unused")
object RiderMcpModel : Ext(SolutionModel.Solution) {

    // A debug/diagnostic snapshot the backend can produce on demand.
    private val BackendStatus = structdef {
        field("solutionName", string)
        field("isReady", bool)
        field("projectCount", int)
        field("backendVersion", string)
    }

    init {
        setting(Kotlin11Generator.Namespace, "dev.ridermcp.model")
        setting(CSharp50Generator.Namespace, "RiderMcp.Model")

        // Frontend -> backend: ask for a status snapshot.
        call("getBackendStatus", void, BackendStatus.asNullable)

        // Backend -> frontend: pushed whenever backend readiness changes, so the
        // MCP layer can reflect liveness without polling.
        signal("backendReadyChanged", bool)
    }
}
