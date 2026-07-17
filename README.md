# rider-mcp-plugin

A dual-part JetBrains **Rider** plugin that exposes IDE **interface** and
**debug** data to MCP clients over a local **Streamable HTTP** endpoint.

## Architecture

```
┌─────────────────────────┐         RD protocol          ┌────────────────────────┐
│  Kotlin frontend         │  <────────────────────────>  │  .NET (ReSharper)        │
│  (IntelliJ platform)      │      :protocol models        │  backend                 │
│                           │                              │                          │
│  • MCP Streamable HTTP srv │                              │  • backend status        │
│  • tool-window/console tools│                             │    snapshot (diagnostics)│
└───────────┬───────────────┘                              └────────────────────────┘
            │  Streamable HTTP  http://127.0.0.1:6363/stream
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
The focus is **IDE control the official Rider MCP doesn't cover**: tool-window /
console content, live debug state, and full run / debug / build control (launch
configs, stepping, breakpoints, per-project builds). Routing launches/builds
through this plugin also avoids the JetBrains MCP plugin's global "brave mode"
confirmation (which would also un-gate shell), so these fire without a prompt.

Every tool takes an optional **`solution`** selector (name or path), needed only
when several solutions are open in one Rider instance.

**Window content (`WindowContentTools.kt`)** — pure frontend reads:

| Tool | Purpose |
|------|---------|
| `list_tool_windows` | Tool window ids + visibility/tab count |
| `read_tool_window` | Text shown in a tool window (`id='Build'` → build output, `'Problems View'`, `'Version Control'`, …) |
| `list_processes` | Run/debug processes and their consoles |
| `read_process_output` | Console output of a run/debug process (debug process log / program output) |

**Run / launch (`RunConfigTools.kt`)** — IntelliJ-platform; no confirmation prompt:

| Tool | Purpose |
|------|---------|
| `list_run_configurations` | Run/debug configurations in the solution (name + type) |
| `run_configuration` | Launch a config by `name`, running its before-launch build first (exactly like clicking Run/Debug). `debug` defaults to `true` (debugger attaches); `debug=false` for a plain Run |
| `stop_process` | Stop running session(s) — the Stop button. Omit `name` to stop the single running one; `name` matches the Run/Debug tab title |

**Debugger — reads (`DebuggerTools.kt`)** — live XDebugger state; need a session **suspended at a breakpoint** (except `debug_status`):

| Tool | Purpose |
|------|---------|
| `debug_status` | Active debug sessions: name, running/suspended, current `file:line` |
| `list_threads` | Threads (execution stacks) of the suspended session; marks the active one |
| `get_call_stack` | Call stack of a thread: each frame's function + `file:line` |
| `get_local_variables` | Locals/params/fields in a frame, with values and types |
| `evaluate` | Evaluate an `expression` in a frame (`obj.field`, `list.Count`, …) |

`get_local_variables` and `evaluate` take an optional `frame` index (from
`get_call_stack`) and `thread` index. The XDebugger read path is async/callback-
based, so each call is adapted to a coroutine (off the EDT) with a timeout.

**Debugger — execution control** — on a suspended session (`pause` needs a running one):

| Tool | Purpose |
|------|---------|
| `resume` | Continue until the next breakpoint or exit |
| `pause` | Suspend a running session |
| `step_over` / `step_into` / `step_out` | Step (F8 / F7 / Shift+F8) |

**Debugger — breakpoints** — work without a running session; they bind on the next debug run:

| Tool | Purpose |
|------|---------|
| `list_breakpoints` | All breakpoints, indexed, with `file:line`, enabled state and condition; MCP-set ones are marked `[MCP]` |
| `set_breakpoint` | Set a line breakpoint at `file` + `line` (1-based), optional `condition` |
| `update_breakpoint` | Change `enabled` / `condition` (`""` clears) / `suspend` on the breakpoint at `file:line`; `mcpOnly=true` applies to every MCP-set breakpoint |
| `remove_breakpoint` | Remove the breakpoint at `file:line`, or `all=true`, or `mcpOnly=true` |

MCP-created breakpoints are tagged with the breakpoint group **`MCP`** (persisted
to `workspace.xml`, shown in the Breakpoints dialog), so `list`/`update`/`remove`
can target the MCP set (`mcpOnly`) without touching the user's own breakpoints.
Conditions are built in the breakpoint file's own language via
`XDebuggerUtil.createExpression` so the debugger actually evaluates them, and the
breakpoint is created with `XBreakpointManager.addLineBreakpoint` (returns the
object directly) so the tag/condition reliably apply.

**Diagnostics (`DiagnosticsTools.kt`)** — RD-backed:

| Tool | Purpose |
|------|---------|
| `backend_status` | Backend snapshot over RD: solution name, project count, version, readiness |

**Build (`BuildTools.kt`)** — RD-backed, drives the .NET backend's `ISolutionBuilder`:

| Tool | Purpose |
|------|---------|
| `build_project` | Build **specific** project(s) — the gap the official `build_solution` leaves (it only builds the whole solution). Args: `projects` (name list), `rebuild`, `withoutDependencies`, `solution`. A cold build runs in the background and returns a **`buildId`** immediately — poll `build_status`. If a hot-reload session is live it applies changes instead (.NET Hot Reload, or **Unreal Live Coding** for a running UE editor) |
| `build_status` | Report a build's status by `buildId`: still running, or the final result (success + errors/warnings with `file:line`) |
| `cancel_build` | Cancel a running build by `buildId` (or the single in-flight one) via `ISolutionBuilder.Abort()` |

`build_project` runs the same path as "Build Selected Project": the backend
resolves each `IProject` by name, issues `ISolutionBuilder.CreateBuildRequest`
(`BuildSessionTarget.Build`/`Rebuild`, `IsSingleProjectBuild`), executes it in
the background, and resolves each build-error offset to a `BuildEvent`
(kind/message/code/file/line/column) read back via `build_status`.

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

Clients connect to `http://127.0.0.1:6363/stream` (override the port with the JVM
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
.NET backend compile; the zip assembles with the backend dll bundled).

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

CI is **GitHub Actions** (`.github/workflows/ci.yml`). It builds the full plugin
(frontend + .NET backend) on **every push, PR, and tag** — installs .NET 8 +
`libicu` (a hard .NET runtime dep the slim image lacks), runs `:protocol:rdgen`
then `buildPlugin`, caches the Gradle deps (incl. the multi-GB Rider SDK) via
`actions/cache`, and uploads the `.zip` as a build **artifact** (from the run
page; artifacts expire ~90 days). Day-to-day validation is still local
`./gradlew buildPlugin`.

On a **tag push** it *also* publishes a **GitHub Release** with the built `.zip`
attached (`softprops/action-gh-release`; the job grants `contents: write`).

**Cut a release:**

```bash
# bump pluginVersion in gradle.properties AND serverInfo in McpHttpServer.kt
# (update README refs), commit, then:
git tag v0.14.1
git push origin v0.14.1      # CI builds and publishes the GitHub Release with the zip
```

**Build on demand:** GitHub → *Actions → Build plugin → Run workflow*
(`workflow_dispatch`).

**Download a release.** Prefer the **GitHub Release** assets (stable per-tag
permalink). A copy may also be committed under `dist/` for a version-pinned raw
URL, e.g.:

```
https://raw.githubusercontent.com/gaetan-deturche/rider-mcp-plugin/main/dist/rider-mcp-plugin-0.14.1.zip
```

## Status / TODO

The full build compiles locally; CI (GitHub Actions, `.github/workflows/ci.yml`) builds it on every
push/PR/tag (see [Releasing / CI](#releasing--ci)).

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
      `http://127.0.0.1:6363/stream`, tools execute, and the RD round-trip works
      (`backend_status`). The debugger tools were verified live against a paused
      C++ session (call stack, typed locals, expression evaluation).
- [x] Verified the Swing text-extraction against a real Build view —
      `read_tool_window('Build')` returns live MSBuild output. (Other consoles
      may still wrap editors in ways the component walk needs to special-case.)
