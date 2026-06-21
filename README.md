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
The focus is **tool-window / console content** and **live debug state** — things
the official Rider MCP doesn't already cover (it has symbol/solution lookup, so
those were dropped).

**Window content (`WindowContentTools.kt`)** — pure frontend reads:

| Tool | Purpose |
|------|---------|
| `list_tool_windows` | Tool window ids + visibility/tab count |
| `read_tool_window` | Text shown in a tool window (`id='Build'` → build output, `'Problems View'`, `'Version Control'`, …) |
| `list_processes` | Run/debug processes and their consoles |
| `read_process_output` | Console output of a run/debug process (debug process log / program output) |

**Debugger (`DebuggerTools.kt`)** — live XDebugger state. `list_breakpoints`
works any time; the rest need a debug session **suspended at a breakpoint**:

| Tool | Purpose |
|------|---------|
| `debug_status` | Active debug sessions: name, running/suspended, current `file:line` |
| `list_threads` | Threads (execution stacks) of the suspended session; marks the active one |
| `get_call_stack` | Call stack of a thread: each frame's function + `file:line` |
| `get_local_variables` | Locals/params/fields visible in a frame, with values and types |
| `evaluate` | Evaluate an expression in a frame (`obj.field`, `list.Count`, …) |
| `list_breakpoints` | All breakpoints: location, enabled, condition (no session needed) |

`get_local_variables` and `evaluate` take an optional `frame` index (from
`get_call_stack`) and `thread` index; `evaluate` takes an `expression`. The
XDebugger read path is async/callback-based, so each call is adapted to a
coroutine (off the EDT) with a timeout. Raw byte-memory isn't exposed — it's not
in the public XDebugger API; `evaluate` / `get_local_variables` cover value and
object/field inspection instead.

**Diagnostics (`DiagnosticsTools.kt`)** — RD-backed:

| Tool | Purpose |
|------|---------|
| `backend_status` | Backend snapshot over RD: solution name, project count, version, readiness |

Content extraction walks the tool window's Swing component tree on the EDT,
pulling text from editor and text components. `read_tool_window` and
`read_process_output` support **line-based pagination**: `offset` (0-based;
negative counts from the end) and `count`, with a `[lines X–Y of N]` header so
clients can page. Output is still capped by `maxChars` (default 20k).

**Extraction limitation.** The walk only recognizes IntelliJ editor
(`EditorComponentImpl`) and `JTextComponent` content — which covers the Build
output and most run/debug consoles (verified). Windows whose text lives in a
data model rendered per-row (e.g. `JTree`/`JList`-based test or Problems views)
or is custom-painted aren't seen by the generic walk and return empty/partial
text until a type-specific handler is added to `extractText`.

Clients connect to `http://127.0.0.1:6363/sse` (override the port with the JVM
property `-Drider.mcp.port=<n>`).

## Build & run

Prerequisites:
- **A full JDK 25** (Gradle 9.6 + the `jvmToolchain(25)`; Rider 2026.1 runs on
  JBR 25). Note a *JRE* 25 isn't enough — the toolchain needs `javac`. The Rider
  SDK itself is fetched as a dependency (`rider(...) { useInstaller = false }`
  pulls the SDK distribution, which ships `lib/rd/rider-model.jar`).
- **.NET SDK 8** (`dotnet`) for the backend.

```bash
./gradlew :protocol:rdgen      # generate the shared RD models (Kotlin + C#)
./gradlew buildReSharperHost   # compile the .NET backend (needs `dotnet`)
./gradlew runIde               # launch a sandbox Rider with the plugin
./gradlew buildPlugin          # produce a distributable .zip (bundles the
                               # backend dll under <plugin>/dotnet/)
```

A full `buildPlugin` has been verified end-to-end locally (frontend Kotlin +
.NET backend compile; the 0.1.0 zip assembles with the backend dll bundled).

## Updating to a new Rider version

Rider bundles Kotlin, kotlinx-coroutines and **Ktor** as platform modules that
win on the plugin classpath at runtime. The cardinal rule: **match the plugin's
Kotlin and Ktor (and therefore the MCP SDK) to whatever the target Rider ships**,
or you get `NoSuchMethodError` / classloader-constraint crashes at runtime even
though it compiles.

1. **Find the target build + bundled versions.** From an installed Rider
   `<rider>/`:
   - build number: `product-info.json` → `buildNumber` (e.g. `RD-261.25134.178` → branch `261`)
   - bundled Ktor: `unzip -p lib/intellij.libraries.ktor.io.jar META-INF/MANIFEST.MF | grep Implementation-Version`
   - bundled Kotlin: the `rider-model.jar` metadata version (a build error will also tell you: "metadata version is X").
2. **`gradle.properties`** — `platformVersion`, `rdVersion` → the Rider version;
   `pluginSinceBuild`/`pluginUntilBuild` → the new branch (e.g. `261` / `261.*`).
3. **`settings.gradle.kts`** — `org.jetbrains.kotlin.jvm` version → the IDE's
   Kotlin line; `rd-gen` `useModule` version → match `rdVersion`.
4. **`build.gradle.kts`** — `io.ktor:*` versions and the `eachDependency`
   force → the IDE's Ktor MAJOR.MINOR; `io.modelcontextprotocol:kotlin-sdk` →
   a release that targets that same Ktor MAJOR.MINOR (check its POM/module).
5. **`ReSharperPlugin/RiderMcp/RiderMcp.csproj`** — `JetBrains.Rider.SDK` → the
   Rider version wave (e.g. `2026.1.*`).
6. Rebuild: `./gradlew clean buildPlugin`. Fix any API breaks the version jump
   surfaces (the MCP SDK in particular relocates/renames types between releases).

Reference: the 243 → 261 migration commit shows the exact set of changes.

## Releasing / CI

CI runs on **Bitbucket Pipelines** (`bitbucket-pipelines.yml`) and builds the
full plugin (frontend + .NET backend), uploading the `.zip` as a pipeline
artifact.

It is **tag-only**, not per-push. The Rider SDK is multi-GB and can't be cached
on Bitbucket Cloud — the gradle cache is ~6.2 GiB compressed, over the **1 GiB**
upload limit, so it's discarded every run (and attempting to upload it wastes
~10 min compressing first). So there's no gradle cache, every build re-downloads
the SDK, and we only pay that cost when cutting a release. **Day-to-day
validation is local `./gradlew buildPlugin`.**

**Cut a release:**

```bash
# bump pluginVersion in gradle.properties first, then:
git tag v0.1.0
git push origin v0.1.0      # any tag triggers the build
```

**Build on demand without tagging:** Bitbucket → *Pipelines → Run pipeline →
custom: `build`*.

The build step installs .NET 8 and `libicu` (a hard .NET runtime dep the slim
Temurin image lacks), runs `:protocol:rdgen`, then `buildPlugin`. The publishable
zip lands in `build/distributions/` (downloadable from the run's *Artifacts*).

## Status / TODO

The full build compiles locally; CI (`bitbucket-pipelines.yml`) builds it on
tag push (see [Releasing / CI](#releasing--ci)).

- [x] Gradle build + rdgen model generation (Kotlin + C#).
- [x] Frontend Kotlin compiles against the Rider SDK.
- [x] .NET backend compiles (`RiderMcp.dll`); `buildPlugin` bundles it.
- [x] Window-content tools (`WindowContentTools.kt`): `list_tool_windows`,
      `read_tool_window`, `list_processes`, `read_process_output`.
- [x] RD diagnostics: backend `GetBackendStatus` handler + frontend
      `DebugDataProvider` (EDT/protocol-scheduler aware) → `backend_status` tool.
- [x] Backend `RiderMcpHost` registered as an eager solution component
      (`Instantiation.ContainerAsyncPrimaryThread`), so it wires the RD handlers
      when a solution opens. The frontend MCP server **auto-starts at IDE launch**
      via `McpAppStartup : AppLifecycleListener.appStarted()` — no solution or
      menu click needed (`postStartupActivity` never fired here; kept as a backup).
- [x] `runIde` smoke test: the plugin **loads cleanly** (`Loaded custom
      plugins: rider-mcp-plugin`), the ReSharper backend recognizes it, and
      there are no errors from `dev.ridermcp`.
- [x] Debugger tools (`DebuggerTools.kt`) over the XDebugger API: `debug_status`,
      `list_threads`, `get_call_stack`, `get_local_variables`, `evaluate`,
      `list_breakpoints`. Async callbacks adapted to coroutines off the EDT.
- [x] **Confirmed at runtime on desktop Rider 2026.1**: the MCP server binds on
      `http://127.0.0.1:6363/sse`, tools execute, and the RD round-trip works
      (`backend_status`). The debugger tools were verified live against a paused
      C++ session (call stack, typed locals, expression evaluation).
- [x] Verified the Swing text-extraction against a real Build view —
      `read_tool_window('Build')` returns live MSBuild output. (Other consoles
      may still wrap editors in ways the component walk needs to special-case.)
