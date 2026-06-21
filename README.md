# rider-mcp-plugin

A dual-part JetBrains **Rider** plugin that exposes IDE **interface** and
**debug** data to MCP clients over a local **HTTP/SSE** endpoint.

## Architecture

```
┌─────────────────────────┐         RD protocol          ┌────────────────────────┐
│  Kotlin frontend         │  <────────────────────────>  │  .NET (ReSharper)        │
│  (IntelliJ platform)      │      :protocol models        │  backend                 │
│                           │                              │                          │
│  • MCP HTTP/SSE server     │                              │  • backend status        │
│  • tool-window/console tools│                             │    snapshot (diagnostics)│
└───────────┬───────────────┘                              └────────────────────────┘
            │  SSE  http://127.0.0.1:6363/sse
            ▼
       MCP clients (Claude Code, IDE agents, …)
```

| Path | Purpose |
|------|---------|
| `build.gradle.kts` | Frontend build (IntelliJ Platform Gradle Plugin 2.x) + backend orchestration |
| `protocol/` | RD model shared by both sides; `rdgen` emits Kotlin **and** C# |
| `src/rider/main/` | Kotlin frontend: MCP server + tools |
| `ReSharperPlugin/` | .NET backend implementing the RD model |

## MCP surface

The server registers these tools (`src/rider/main/kotlin/dev/ridermcp/tools/`).
The focus is **tool-window / console content** — things the official Rider MCP
doesn't already cover (it has symbol/solution lookup, so those were dropped).

**Window content (`WindowContentTools.kt`)** — pure frontend reads:

| Tool | Purpose |
|------|---------|
| `list_tool_windows` | Tool window ids + visibility/tab count |
| `read_tool_window` | Text shown in a tool window (`id='Build'` → build output, `'Problems View'`, `'Version Control'`, …) |
| `list_processes` | Run/debug processes and their consoles |
| `read_process_output` | Console output of a run/debug process (debug process log / program output) |

**Diagnostics (`DiagnosticsTools.kt`)** — RD-backed:

| Tool | Purpose |
|------|---------|
| `backend_status` | Backend snapshot over RD: solution name, project count, version, readiness |

Content extraction walks the tool window's Swing component tree on the EDT,
pulling text from editor and text components. `read_tool_window` and
`read_process_output` support **line-based pagination**: `offset` (0-based;
negative counts from the end) and `count`, with a `[lines X–Y of N]` header so
clients can page. Output is still capped by `maxChars` (default 20k).

Clients connect to `http://127.0.0.1:6363/sse` (override the port with the JVM
property `-Drider.mcp.port=<n>`).

## Build & run

Prerequisites: **JDK 21** (the IntelliJ Platform Gradle Plugin provisions the
JBR for the sandbox), and the **.NET SDK** (`dotnet`) for the backend.

```bash
./gradlew :protocol:rdgen      # generate the shared RD models (Kotlin + C#)
./gradlew buildReSharperHost   # compile the .NET backend (needs `dotnet`)
./gradlew runIde               # launch a sandbox Rider with the plugin
./gradlew buildPlugin          # produce a distributable .zip
```

## Status / TODO

Window-content tools and the RD diagnostics path are implemented; remaining
work is version pinning and verifying a few platform API call sites:

- [x] Window-content tools (`WindowContentTools.kt`): `list_tool_windows`,
      `read_tool_window`, `list_processes`, `read_process_output`.
- [x] RD diagnostics: backend `GetBackendStatus` handler + frontend
      `DebugDataProvider` (EDT/protocol-scheduler aware) → `backend_status` tool.
- [ ] Verify the Swing text-extraction against real Build/Debug views — some
      consoles wrap editors in ways the component walk may need to special-case.
- [ ] Pin exact versions: MCP Kotlin SDK, Ktor, Rider SDK, rd — see
      `gradle.properties` / `build.gradle.kts`. Verify the version-sensitive
      frontend calls (`startSuspending`, `solution.riderMcpModel`,
      `RunContentManager`/`EditorComponentImpl`) and the backend
      `GetProtocolSolution()` namespace compile against them.

> Nothing here can be compiled until `./gradlew :protocol:rdgen` generates the
> shared model types — the handler code references those generated symbols by
> design.
