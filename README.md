# rider-mcp-plugin

A dual-part JetBrains **Rider** plugin that exposes IDE **interface** and
**debug** data to MCP clients over a local **HTTP/SSE** endpoint.

## Architecture

```
┌─────────────────────────┐         RD protocol          ┌────────────────────────┐
│  Kotlin frontend         │  <────────────────────────>  │  .NET (ReSharper)        │
│  (IntelliJ platform)      │      :protocol models        │  backend                 │
│                           │                              │                          │
│  • MCP HTTP/SSE server     │                              │  • symbol resolution     │
│  • interface + debug tools │                              │  • solution/build data   │
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

The server registers these tools (`src/rider/main/kotlin/dev/ridermcp/tools/`):

- **Interface** — `list_open_solutions`, `find_symbols`
- **Debug** — `list_debug_sessions`, `backend_status`

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

This is a scaffold. Wiring left to complete:

- [ ] Pin exact versions: MCP Kotlin SDK, Ktor, Rider SDK, rd — see
      `gradle.properties` / `build.gradle.kts`.
- [ ] Generate the wrapper jar once (`gradle wrapper` with a system Gradle, or
      let the IDE do it) — only the wrapper scripts/properties are committed.
- [ ] Implement RD handlers on the backend (`RiderMcpHost.cs`) and the typed
      accessors in `DebugDataProvider.kt`.
- [ ] Route `find_symbols` / `backend_status` tools through the RD model.
