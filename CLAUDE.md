# rider-mcp-plugin (agent notes)

> Agent notes for this repo, migrated out of Claude's global memory store (2026-07-31) so they
> load only when working here. `[[double-bracket]]` names refer to notes in
> `~/.claude/shared-memory/`; `skill` names refer to `~/.claude/skills/`.


## Repo, build and release


`H:\Dev\rider-mcp-plugin` — local clone. **Canonical remote is now the user's GitHub fork `https://github.com/gaetan-deturche/rider-mcp-plugin` (branch `main`)** — repointed 2026-07-02. The old Bitbucket `https://bitbucket.org/s0m30n3/rider-mcp-plugin/` is **archived/stale** (its `main` @ 4cee077 is an ancestor of the GitHub `main`, which adds a GitHub Actions CI commit). This is the source of the **rider-mcp-plugin** MCP server (Rider/IntelliJ plugin exposing debugger/tool-window data; see [[rider-mcp-for-crash-info]]). Dual-part: Kotlin frontend (`src/rider/...`, MCP server via Ktor + MCP Kotlin SDK 0.13.0) + .NET ReSharper backend (`ReSharperPlugin/`, RD protocol over the `RiderMcpModel` Ext on `SolutionModel.Solution`).

**Tools it exposes** (registered in `McpHttpServer.buildServer()`): WindowContentTools, DebuggerTools, DiagnosticsTools (`backend_status`), and — added 2026-07-02 on branch `Users/Gaetan.Deturche/BuildProjectTool` — **BuildTools** `build_project`: builds a *specific* project (the gap the stock Rider `build_solution` leaves, since that only builds the whole solution). Impl = a new `buildProject` RD call in `:protocol`'s `RiderMcpModel.kt` → C# handler in `RiderMcpHost.cs` that drives Rider's own `ISolutionBuilder` (`JetBrains.ProjectModel.Features.SolutionBuilders`): resolve `IProject` by name, `builder.CreateBuildRequest(BuildSessionTarget.Build/Rebuild, projects, SilentMode.Silent, new SolutionBuilderRequestAdvancedSettings{IsSingleProjectBuild,WithoutDependencies,BuildPurpose.BuildOnly})`, `ExecuteBuildRequest`, await via `request.ContinueWith(lifetime, …)` bridged to a `TaskCompletionSource`, then read `Succeeded/HasErrors/HasWarnings/HasCancelled/Skipped` + resolve `GetAllBuildErrors()` offsets through `(request.SessionLoader as InFileBuildSessionLoader).LoadEvent(offset)` → `BuildEvent{Kind,Message,Code,FilePath,Line,Column}`. RD async endpoints use `SetAsync(Func<Lifetime,TReq,Task<TRes>>)` extension (not `RdTask` ctor — it's internal); sync ones use `SetSync`. **`build_project` also does Hot Reload** (added 2026-07-02): if a live .NET hot-reload session exists it applies changes instead of a cold build — frontend-only, via `project.service<com.jetbrains.rider.debugger.editAndContinue.DotNetHotReloadManager>()`: `.processes` non-empty ⇒ session running, then `.applyChangesIfNeeded()` (same call as the toolbar "Apply Changes" button) → `HotReloadApplyResultKind{Applied,BuildFailed,Failed,Canceled,NoChanges}`; gated on `!rebuild`; applies to the running process(es), not just the named project. **Classpath gotcha:** `DotNetHotReloadManager` + `.processes` are in the core `intellij.rider.jar` (on classpath) but `HotReloadApplyResultKind` is in a separate product module → needs `bundledModule("intellij.rider.debugger.shared")` in build.gradle.kts `dependencies { intellijPlatform { … } }`, else "Cannot access class 'HotReloadApplyResultKind'". Find a module's id in `<rider>\product-info.json` (layout `name` → jar). **Unreal path (added 2026-07-02):** .NET Hot Reload doesn't apply to UE C++ — a cold build is refused by UnrealBuildTool ("Unable to build while Live Coding is active", a UBT message, not a Rider one). The UE equivalent = Live Coding, and Rider's UE build button (`com.jetbrains.rider.plugins.unreal.actions.HotReloadBuildAction`, chosen via `BuildButtonModeProvider`/`HotReloadBuildModeProvider`) just does `application.saveAll()` + fires `project.solution.rdRiderModel.triggerHotReload` (an `ISignal<Unit>` on the UnrealLink `RdRiderModel`). Gate = `UnrealHost.getInstance(project).isUnrealEngineSolution() && isHotReloadAvailable()`. `build_project` does this on EDT. **Cross-plugin classloader gotcha:** `UnrealHost`/`RdRiderModel` live in the **UnrealLink plugin** (id `unreal-link`), a *different* classloader than ours — `Class.forName` from our plugin can't see them, so load via `PluginManagerCore.getPlugin(PluginId.getId("unreal-link"))?.pluginClassLoader?.loadClass(...)` + reflection (keeps no hard UnrealLink dependency; falls back to a normal build if absent). Fire-and-forget: results show in the editor's Live Coding log, not returned. (`intellij.rider.jar` classes like DotNetHotReloadManager ARE core/on-classpath; UnrealLink plugin classes are NOT — the distinction is core-platform-module vs bundled-plugin.)

**Launch tools (added ~2026-07-10): `run_configuration` + `list_run_configurations`.** `run_configuration(name, solution, debug=true)` launches a run/debug config by name (runs its before-launch build first, exactly like clicking Run/Debug) and — crucially — **without the confirmation prompt** the built-in `jetbrains__execute_run_configuration` shows (that built-in obeys the JetBrains MCP plugin's global "brave mode", so you can't scope it to launches-only). This gives the user's wanted split: frictionless launches while `execute_terminal_command`/shell stays gated. Defaults to **DEBUG** (debugger attaches); `debug=false` for plain Run. `list_run_configurations(solution)` lists names. This is now the preferred way to start the UE editor — see [[run-ue-via-rider-for-debugger]]. **`stop_process(name?, solution)`** stops running session(s) via `ExecutionManager`/`ExecutionManagerImpl.stopProcess` (Stop-button equivalent; omit name = the single running one).

**Debugger control + breakpoint suite (added ~2026-07-13..16, DebuggerTools, all frontend XDebugger — no RD backend):** read tools `debug_status`/`list_threads`/`get_call_stack`/`get_local_variables`/`evaluate`; stepping `resume`/`pause`/`step_over`/`step_into`/`step_out` (on `XDebugSession`, EDT); breakpoints `list`/`set`/`remove`/`update`_breakpoint. **Two gotchas that took a live UE session to pin (both verified against Rider debugging Unreal C++):**
- **Creating a breakpoint: use `XBreakpointManager.addLineBreakpoint(type, fileUrl, line0, type.createBreakpointProperties(file,line0))` which RETURNS the breakpoint — NOT `XDebuggerUtil.toggleLineBreakpoint(...)` + re-find in `allBreakpoints`.** The toggle+re-find races: the re-find returns null right after, so any post-create work (condition, group tag) is silently skipped and never lands. This was the real "conditions/tags don't work" bug (the condition itself was fine). Resolve the type via `XDebuggerUtil.getInstance().lineBreakpointTypes.firstOrNull { it.canPutAt(file, line0, project) }` (cast to `XLineBreakpointType<XBreakpointProperties<*>>`). Do create+tag+condition in ONE `runWriteAction` on EDT.
- **Breakpoint condition must carry the file's LANGUAGE:** build it with `XDebuggerUtil.getInstance().createExpression(text, (file.fileType as? LanguageFileType)?.language, null, EvaluationMode.EXPRESSION)`. `XExpressionImpl.fromText(text)` leaves language = null → the debugger evaluator can't compile it → the breakpoint stops unconditionally (condition ignored).
- **MCP-set breakpoints are tagged** `group = "MCP"` via `(bp as XBreakpointBase<*,*,*>).group` — persisted to workspace.xml, shown in the Breakpoints dialog, travels with edits. Native C++ (Unreal) breakpoints ARE `XBreakpointBase`, so the cast works (once the null-`bp` race above is fixed). `list_breakpoints` prefixes them `[MCP]`; `remove_breakpoint`/`update_breakpoint` take `mcpOnly=true` for bulk ops on just the MCP set.
- **Focus-steal when a breakpoint hits** = the global registry key `debugger.mayBringFrameToFrontOnBreakpoint` (read by `DebuggerFocusManager`; checkbox in Settings→Build,Execution,Deployment→Debugger). It's global, NOT per-breakpoint — can't be scoped to MCP breakpoints; set it false to stop the IDE grabbing focus for all breakpoints.
- **KTS-runner limit:** the `jetbrains__run_inspection_kts` tool can't import debugger APIs (XDebuggerManager etc. aren't on its classpath) → "No inspection created after compilation". Not usable as a live debugger REPL.

**Crash tripwire (added 2026-07-31 v0.17.0; auto-armed at launch since v0.19.0, 2026-08-24 —
`DebugWatchTools.kt` + `CrashTripwire.kt`, frontend XDebugger):** wakes a Claude session on an
UNEXPECTED crash of a debugged process without babysitting. **The plugin cannot wake an MCP client**
— the wake is the client's own background task exiting — so the split is: the plugin watches
server-side from process start, the client long-polls for the wake.
`run_configuration(debug=true)` **arms a ticket** (`CrashTripwire.arm`) BEFORE
`executeConfiguration`, returns `tripwire armed (handle=tw-N)` plus the watcher command in its
reply, and buffers the first crash-like stop per session — so a crash during startup, or between
two poll slices, is replayed by the next poll instead of being lost (the old poll-only design lost
both). A ticket binds to a session in **its own project** only, and never to one that already
existed when it was armed (`Ticket.predates`) — Curiosity and Curiosity2 are both open as solution
name `UE5`, so name matching alone is not safe. Binding happens on `XDebuggerManager.TOPIC` /
`processStarted` **and** via `resolveSession()`, a scan used by `wait_for_stop`/the listing so the
binding doesn't depend on that event reaching us.
`list_active_debug_sessions` → **one row per target**: `handle=ds-<identityHashCode>` for live
sessions (incl. a manual Play), `handle=tw-N state=pending` for an armed launch whose session
hasn't started, each with the solution **PATH** and a `watch=`/`buffered=` annotation. Exactly one
row per target is what lets the client discover its handle unambiguously.
`wait_for_stop(handle, timeoutSeconds<=60)` takes either handle kind, long-polls in 25s slices
(reply `[TIMEOUT]` → re-poll; keeps each HTTP request under transport idle timeouts) and returns on
crash-like stops only: `unhandled_exception` (pause matching no user breakpoint — also covers manual
pause/step, caveat included) / `exception` (exception-type non-line bp via
`XDebugSessionImpl.activeNonLineBreakpoint`) / `already_paused` / `process_exited` /
`never_started` (armed launch, no session — before-launch build failed); **enabled user breakpoints
are filtered INSIDE the wait**, so manual breakpoints never wake the watcher. Impl:
`XDebugSession.addSessionListener` + `CompletableDeferred` + `withTimeoutOrNull`; payload = position,
thread, top 10 frames (DebuggerTools' `awaitFrames`/`frameLabel`, internal). A buffered stop is
re-rendered from the live suspend context when the process is still suspended (the normal case),
else from the snapshot taken at stop time.
**Claude side** = the user-level `rider-run` skill (`~/.claude/skills/rider-run/`, sibling
`ue-attach` for an instance the USER started): after the launch, `run_in_background`
`arm_tripwire.sh [handle]` — a curl loop hitting the plugin **directly on :6363** (NOT via
mcp-proxy: its 120s call cap) whose exit wakes the owning session with the payload. **The handle is
optional**: with none the script discovers the plugin's single watchable row and refuses (exit 2,
printing the list) on zero or several — it never guesses between two editors. A PostToolUse hook
(`~/.claude/hooks/tripwire-reminder.py`, wired in `~/.claude/settings.json`) injects the watcher
command after every debug launch as a second reminder layer. Verified end-to-end 2026-08-24:
launch → `tripwire armed (handle=tw-1)` → bound to `ds-…` in the right solution → `debug crash` via
remote Python → session woken with `[STOP · unhandled_exception]` at `StructuredLog.cpp:1608`
(`UEngine::PerformError`), buffered replay instant afterwards.

**UPSTREAM RIDERLINK BUG (not this plugin) — editor crash in RD dispatch (2026-07-31):** the UE
editor died with a real `0xc0000005` access violation (reading `0x000000b0`) inside
`rd::MessageBroker::dispatch` with `subscription = NULL`. The code lives in **JetBrains' RiderLink**,
not here: `Engine/Plugins/Marketplace/Developer/RiderLink/Source/RD/src/rd_framework_cpp/src/main/protocol/MessageBroker.cpp:97`
= `RD_ASSERT_MSG(subscription->get_wire_scheduler() != default_scheduler, ...)` — the function
null-checks `s` at line 56 but then dereferences `subscription` at 97 WITHOUT one, so a message for
an unsubscribed/torn-down RdId crashes the editor. Frames: `MessageBroker.cpp:97` ->
`SingleThreadSchedulerBase::PoolTask::operator()` -> `ctpl::thread_pool`. rider-mcp-plugin's ue_*
tools only ride that channel; a late/malformed message can trigger it but the missing null-check is
upstream. It kills the whole ue_* channel, so `ue_execute_python` then just times out.
**Prime suspect = `ue_export_blueprint_nodes`**: the crash landed exactly on a call for
`/Game/Gameplay/Weapons/BP_BaseGun.BP_BaseGun` graph `EventGraph` (the tool returned "cancelled" at
that instant). Correlation, not proof. Until it's fixed, read K2 graphs via `ue_execute_python` +
`unreal.SCBlueprintGraphLibrary` (the `claude-ue-plugins` skill) instead of the export tool.
**Diagnosis recipe:** a frozen viewport + frozen log + `ue_execute_python` timeouts is NOT "busy
compiling" — check `debug_status`, and if suspended read `get_local_variables` on frame 0: the
`Exception = …` line gives the real code (0xc0000005 = genuine crash, unresumable; resuming just
re-faults). Do NOT reframe a repeated AV as an ignorable first-chance exception.
Also: exception FILTERS (`Any exception`) and `CidrWatchpoint` rows can't be toggled through this
plugin at all — `update_breakpoint` resolves only by `file:line` or the MCP group, so those need
Rider's Breakpoints dialog (Ctrl+Shift+F8).

**Transport (as of v0.2.0, 2026-06-24):** serves MCP over **Streamable HTTP at `/stream`** (was SSE at `/sse`). `McpHttpServer.kt` uses `mcpStreamableHttp(path="/stream")` — an `Application.mcpStreamableHttp(...)` extension that installs ContentNegotiation + SSE itself (so do NOT `install()` them, but KEEP the `ktor-server-sse` dependency). DNS-rebinding protection defaults allow `127.0.0.1` (port stripped). Listens on port **6363** (`mcpServerPort` in gradle.properties).

**Multi-solution / instance selection (fixed 2026-07-02):** the MCP server is **application-scoped** (`McpServerService`, `@Service(Level.APP)`, started once per Rider *process* by `McpAppStartup.appStarted`). A single Rider 2026.1 process hosts **multiple open solutions** (each its own window — verified: `Curiosity` + `Curiosity2` open, only ONE `rider64` process), all visible to the one server via `ProjectManager.getInstance().openProjects`. So tools take an optional `solution` selector (`ToolSupport.resolveProject`) — matched against the solution **name OR path** (basePath, `\`→`/` normalized), auto-picking only when exactly one is open (`singleOrNull`); on miss/ambiguity `noSolution()` replies listing the open solutions (name + path), mirroring the built-in Rider MCP's `projectPath`. Earlier bug: it did `openProjects.firstOrNull()` → silently hit the wrong solution when >1 open. **Known limit:** a *second Rider process* can't bind 6363 (server start fails there), so only the first process's solutions are served; the built-in Rider MCP avoids this with a cross-process endpoint we don't replicate.

**build_project is ASYNC (0.5.0, 2026-07-08) — long builds can't block the MCP call.** Why: the [[mcp-aggregator-proxy]]'s downstream HTTP client has `ResponseHeaderTimeout: 20s` (+ a `callTimeout`, default 120s), so a blocking `build_project` that only sends its response when the build finishes trips at ~20s ("net/http: timeout awaiting response headers"). We tried to make it a keep-alive'd streaming (SSE) call instead, but **that's a dead end**: the MCP Kotlin SDK's public `mcpStreamableHttp` helper hard-codes `enableJsonResponse=true` (buffered JSON, headers only at the end), and its SSE mode (`enableJsonResponse=false`) *requires a Ktor `ServerSSESession` for the POST* — but **Ktor's `sse{}` is GET-only** (`route(path, HttpMethod.Get)`), so a POST response can't stream without hand-rolling raw SSE outside the SDK. So the design is **async + short-poll** instead: `build_project` → `startBuildProject` RD call kicks the build and returns a `buildId` immediately (never blocks); new `build_status(buildId)` tool → `getBuildStatus` RD call reads the live `SolutionBuilderRequest.State`/`Succeeded`/problems (backend keeps a `ConcurrentDictionary<buildId,SolutionBuilderRequest>`, frees on terminal read). Client polls ~every 3s (each call is instant → no timeout, negligible inflation, doesn't mask a hung Rider). `BuildRunState.Completed` (in `JetBrains.ProjectModel.Features.SolutionBuilders`) marks done. The .NET Hot Reload / UE Live Coding fast paths stay synchronous (they return immediately). **Aside:** Claude→proxy is stdio, so the 5-min *idle* MCP timeout (reset by progress notifications, doc'd for HTTP/SSE/WS) never applied here — the binding limit was always the proxy's own HTTP timeouts, not Claude's.

**Build — works from the Bash tool since 2026-07-02 (loopback issue SOLVED, see [[java-loopback-appdata-msix]]):**
- The old failure (`java.io.IOException: Unable to establish loopback connection`) was AF_UNIX-under-AppData breaking `Selector.open()` in Claude Desktop's MSIX process tree — fix by setting `TMP`/`TEMP` to a dir outside AppData (e.g. `H:\Dev\tmp-gradle`) on the gradlew invocation. The IntelliJ-terminal `execute_terminal_command` route still works but is no longer required.
- Needs **JBR 25** toolchain (`jvmToolchain(25)`): pass `-Dorg.gradle.java.installations.paths="C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\jbr"`. **Since the 2026.2 platform (2026-07-27) also set `JAVA_HOME` to that JBR 25** — the 2026.2 `rider-model.jar` is Java-25 bytecode and `:protocol:rdgen` forks `java` from JAVA_HOME (the machine's Adoptium 21 → `UnsupportedClassVersionError: class file version 69.0`).
- **Broken .NET install gotcha:** `:buildReSharperHost` fails because SDK `9.0.312` wants runtime `Microsoft.NETCore.App 9.0.14` but only `9.0.11` is installed. Work around with `$env:DOTNET_ROLL_FORWARD='Major'` (rolls the SDK host onto the installed `10.0.1` runtime). The user should ideally install the 9.0.14+ runtime to fix this permanently.
- Full command: `$env:DOTNET_ROLL_FORWARD='Major'; .\gradlew.bat buildPlugin --console=plain "-Dorg.gradle.java.installations.paths=C:\Program Files\JetBrains\IntelliJ IDEA 2026.1\jbr"`. Output zip → `build/distributions/rider-mcp-plugin-<ver>.zip`.
- Deprecation warning "incompatible with Gradle 10" comes from the `rdgen` plugin (`Task.project` at execution time) — not actionable, harmless on Gradle 9.6.

**Platform migration 261 → 262 (Rider 2026.2, done 2026-07-27, v0.15.0):** bump `platformVersion=2026.2` (exact string per the intellij-repository metadata — no `.0`), `rdVersion`/rd-gen `useModule` `2026.2.0`, `pluginSince/UntilBuild 262`, csproj `JetBrains.Rider.SDK 2026.2.*`. Ktor stayed 3.4.1 → MCP SDK/transport untouched; Kotlin 2.3.0 still fine. TWO breaks: (1) rdgen needs JAVA_HOME = JDK/JBR **25** (see build section); (2) **`Project.solution` (`SolutionHostExtensionsKt`, pkg `com.jetbrains.rider.projectView`) moved out of core `intellij.rider.jar` into product module `intellij.rider.rdclient.dotnet`** → add `bundledModule("intellij.rider.rdclient.dotnet")` in build.gradle.kts, else `Unresolved reference 'solution'`. Diagnose moved classes by scanning the installed Rider's jars for the declaring `*Kt` facade + `product-info.json` `layout[]` for the module name. NB: an in-place update can leave a stale install dir name (`JetBrains Rider 261.22158.211` actually contains 2026.2 build `262.8665.328` — trust `product-info.json`, not the folder).

**Release workflow:**
1. Bump `pluginVersion` in `gradle.properties` AND the `serverInfo` version in `McpHttpServer.kt` (`Implementation(name="rider-mcp", version="…")`); update README version refs.
2. `buildPlugin` (above) → copy the new zip into `dist/` (historically served via a Bitbucket `raw/main/dist/…` URL because Bitbucket Downloads needs a paid plan; now that the canonical remote is GitHub, prefer `https://raw.githubusercontent.com/gaetan-deturche/rider-mcp-plugin/main/dist/rider-mcp-plugin-<ver>.zip` or a GitHub Release asset). The zip MUST be committed on `main` for the raw URL to resolve.
3. `git add -A && commit && push origin main` (now GitHub). **The user wants commits pushed DIRECTLY to `main` for this repo — no feature branch, no PR** (stated 2026-07-02). Work on `main` locally and push it.

**Install a built plugin:** Rider/IntelliJ → Settings → Plugins → ⚙ → *Install Plugin from Disk* → the zip → restart IDE. After install, the `rider-mcp-plugin` downstream in [[mcp-aggregator-proxy]] flips UP on `reload`.

**Install gotcha (backend dll lock) — seen 2026-07-02:** if the plugin **project is open in another JetBrains IDE** (e.g. IntelliJ, where you build it), that IDE **locks `…\bin\Debug\net8.0\RiderMcp.dll`** (its build output). Installing/updating the plugin in Rider then can't overwrite the deployed backend dll → Rider logs `AccessDeniedException: …\plugins\rider-mcp-plugin\dotnet\RiderMcp.dll` at startup, silently keeps the **stale old dll** (Rider still starts, but the backend half — RD model incl. new calls like `buildProject` — is the old version → frontend tools fail / RD hash mismatch). Symptom check: compare the deployed `dotnet\RiderMcp.dll` size to a fresh `bin\Debug\net8.0\RiderMcp.dll` (they differ). Fix: **fully close the other IDE (IntelliJ) first**, then restart Rider so it can write+load the new dll. (A manual `cp` of the fresh dll over the deployed one also fails with "Device or resource busy" while the lock is held.)

## GitHub Actions CI - the disk-space trap


Building a **JetBrains-platform Gradle plugin** (IntelliJ / Rider; `./gradlew buildPlugin`) on GitHub Actions `ubuntu-latest` fails while resolving/extracting the platform SDK with:

```
Could not resolve dependencies for ...:compileClasspath
  > Could not copy zip entry .../riderRD-2026.1.zip!plugins/...
    > java.io.IOException: No space left on device
```

**Why:** the IntelliJ/Rider platform SDK the IntelliJ Platform Gradle Plugin downloads is **multi-GB** (the Rider SDK unpacked is >10 GB), and `ubuntu-latest` only has ~14 GB free by default — not enough to download + unzip it.

**Fix — reclaim disk before the build:**
```yaml
      - name: Maximize Build Space
        uses: jlumbroso/free-disk-space@v1.3.1
        with:
          tool-cache: true        # removes ~/hostedtoolcache (several GB) — the big win
          large-packages: true    # removes preinstalled large apt packages
```
Frees ~10–15 GB. **Watch out:** a `free-disk-space` step configured with `tool-cache: false` + `large-packages: false` (as the upstream `rebel-000/CommandLineArguments` `build.yml` shipped) frees almost nothing and still OOMs — flip both to `true`.

**Also:** cache `~/.gradle/caches` + `~/.gradle/wrapper` (the SDK ≈6 GB fits under GitHub's 10 GB/repo cache limit) so later runs skip the re-download — this is why GitHub can do **per-push** builds whereas **Bitbucket Pipelines free can't cache it (1 GB limit) and is therefore tag-only**. Use JDK 21 (temurin/zulu) for 2026.1-era plugins; build the specific module, e.g. `:cmdlineargs-plugin:buildPlugin`.

Hit during the Bitbucket→GitHub migration of `rider-mcp-plugin` and `CommandLineArguments` (private fork of `rebel-000/CommandLineArguments`). The error looked like a dependency/code failure but `buildSrc` had compiled fine — it was purely disk. (See the broader "the obvious cause isn't the real one" theme in [[validate-escaping-across-boundaries]].)
