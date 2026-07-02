package model.rider

import com.jetbrains.rd.generator.nova.*
import com.jetbrains.rd.generator.nova.PredefinedType.*
import com.jetbrains.rd.generator.nova.csharp.CSharp50Generator
import com.jetbrains.rd.generator.nova.kotlin.Kotlin11Generator
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

// The Ext attaches to the existing Rider Solution model (root IdeRoot, declared
// in rider-model.jar); rdgen generates only this extension, not the whole model.
@Suppress("unused")
object RiderMcpModel : Ext(SolutionModel.Solution) {

    // A debug/diagnostic snapshot the backend can produce on demand.
    private val BackendStatus = structdef {
        field("solutionName", string)
        field("isReady", bool)
        field("projectCount", int)
        field("backendVersion", string)
    }

    // A single compiler diagnostic emitted by a build (error/warning/…).
    private val BuildProblem = structdef {
        field("kind", string)                 // "Error" / "Warning" / "Info" / "Message"
        field("message", string)
        field("code", string.nullable)        // e.g. "CS0246"
        field("file", string.nullable)
        field("line", int.nullable)
        field("column", int.nullable)
        field("projectName", string.nullable)
    }

    // Which project(s) to build and how.
    private val BuildProjectParams = structdef {
        field("projectNames", immutableList(string))
        field("rebuild", bool)                 // clean+build instead of incremental
        field("withoutDependencies", bool)     // build only the named project(s)
    }

    // The outcome of a build request.
    private val BuildProjectResult = structdef {
        field("succeeded", bool)
        field("hasErrors", bool)
        field("hasWarnings", bool)
        field("cancelled", bool)
        field("skipped", bool)                 // up-to-date / nothing to build
        field("builtProjects", immutableList(string))
        field("problems", immutableList(BuildProblem))
        field("errorMessage", string.nullable) // set when the request couldn't start
    }

    init {
        setting(Kotlin11Generator.Namespace, "dev.ridermcp.model")
        setting(CSharp50Generator.Namespace, "RiderMcp.Model")

        // Frontend -> backend: ask for a status snapshot.
        call("getBackendStatus", void, BackendStatus.nullable)

        // Frontend -> backend: build specific project(s). Long-running; the
        // backend answers asynchronously (RdTask) once the build completes.
        call("buildProject", BuildProjectParams, BuildProjectResult.nullable)

        // Backend -> frontend: pushed whenever backend readiness changes, so the
        // MCP layer can reflect liveness without polling.
        signal("backendReadyChanged", bool)
    }
}
