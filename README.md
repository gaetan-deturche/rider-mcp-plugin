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
      when a solution opens. The frontend MCP server starts on solution-open
      (`postStartupActivity`).
- [x] `runIde` smoke test: the plugin **loads cleanly** (`Loaded custom
      plugins: rider-mcp-plugin`), the ReSharper backend recognizes it, and
      there are no errors from `dev.ridermcp`.
- [ ] Confirm the MCP server binds + the RD round-trip works at runtime. This
      could not be verified in the headless CI/dev environment used here — the
      sandbox Rider GUI hangs during first-run startup with no real display.
      Verify on a desktop: `./gradlew runIde`, open a solution, then connect an
      MCP client to `http://127.0.0.1:6363/sse` and call `backend_status`.
- [ ] Verify the Swing text-extraction against real Build/Debug views — some
      consoles wrap editors in ways the component walk may need to special-case.
