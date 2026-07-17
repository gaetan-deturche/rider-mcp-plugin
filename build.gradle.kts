import org.jetbrains.intellij.platform.gradle.Constants
import kotlin.io.path.isRegularFile

// ---------------------------------------------------------------------------
// Root build — IntelliJ/Rider frontend (Kotlin) + orchestration of the
// .NET backend build. The :protocol module generates the shared RD models.
// ---------------------------------------------------------------------------

plugins {
    // Versions are centralized in settings.gradle.kts (pluginManagement).
    kotlin("jvm")
    id("org.jetbrains.intellij.platform")
}

val pluginGroup = project.property("pluginGroup") as String
val pluginVersion = project.property("pluginVersion") as String
val platformType = project.property("platformType") as String
val platformVersion = project.property("platformVersion") as String
val buildConfiguration = project.property("buildConfiguration") as String
val mcpServerPort = project.property("mcpServerPort") as String

group = pluginGroup
version = pluginVersion

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Expose the Rider RD model jar from the downloaded SDK so the :protocol module
// can compile its model against SolutionModel. The path is resolved lazily,
// once the IntelliJ Platform has been initialized (SDK extracted).
val riderModel = configurations.create("riderModel") {
    isCanBeConsumed = true
    isCanBeResolved = false
}

artifacts {
    add(riderModel.name, provider {
        intellijPlatform.platformPath.resolve("lib/rd/rider-model.jar").also {
            check(it.isRegularFile()) { "rider-model.jar is not found at \"$it\"." }
        }
    }) {
        builtBy(Constants.Tasks.INITIALIZE_INTELLIJ_PLATFORM_PLUGIN)
    }
}

dependencies {
    intellijPlatform {
        // useInstaller = false pulls the Rider SDK distribution (from the
        // intellij-repository), which ships lib/rd/rider-model.jar — the
        // installer (end-user IDE) does not.
        rider(platformVersion) {
            useInstaller = false
        }
        jetbrainsRuntime()
        // Solution protocol access (com.jetbrains.rider.projectView.solution) and
        // rd platform coroutines (startSuspending) live in core product jars,
        // already on the classpath.
        // BuildTools' hot-reload path uses DotNetHotReloadManager (in the core
        // intellij.rider jar) whose applyChangesIfNeeded() returns
        // HotReloadApplyResultKind — that type lives in a separate product module
        // that isn't on the compile classpath by default, so pull it in explicitly.
        bundledModule("intellij.rider.debugger.shared")
    }

    // NB: we do NOT depend on project(":protocol") at runtime. rdgen emits the
    // Kotlin model directly into this module's source set, and it compiles
    // against the platform's rd framework (rd.jar). Depending on the protocol
    // jar would drag rd-gen + kotlin-compiler-embeddable into the plugin.

    // --- MCP server (Streamable HTTP transport) ----------------------------
    // Official MCP Kotlin SDK. Must use the same Ktor MAJOR.MINOR as the IDE
    // bundles (Rider 2026.1 ships Ktor 3.4.x as platform modules that win at
    // runtime). SDK 0.13.0 targets Ktor 3.4.3 ≈ the platform's 3.4.1.
    implementation("io.modelcontextprotocol:kotlin-sdk:0.13.0")

    // Embedded HTTP server for the Streamable HTTP MCP endpoint. Pinned to 3.4.1
    // to match the Ktor that Rider 2026.1 bundles as platform library modules —
    // the platform's copy is what loads at runtime, so our bytecode must target
    // the same version (3.0.x gave NoSuchMethodError on embeddedServer).
    // ktor-server-sse is still required: mcpStreamableHttp installs the SSE
    // plugin itself for the streaming half of the transport.
    implementation("io.ktor:ktor-server-cio:3.4.1")
    implementation("io.ktor:ktor-server-sse:3.4.1")
    implementation("io.ktor:ktor-server-content-negotiation:3.4.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.4.1")
}

// Force every (incl. transitive, e.g. from the MCP SDK) io.ktor module to the
// platform's 3.4.1 so there's a single, consistent Ktor on the classpath.
configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "io.ktor") useVersion("3.4.1")
    }
}

// The IDE provides Kotlin stdlib/reflect and kotlinx-coroutines. Bundling our
// own copies makes the plugin classloader hold different Class objects for
// kotlin.coroutines.* / kotlinx.coroutines.Dispatchers than the platform, which
// breaks every suspend call that crosses the plugin<->platform boundary
// (Dispatchers.EDT, and even invoking our suspend ProjectActivity). Exclude them
// so the platform's copies are used; we only bundle Ktor + MCP SDK + serialization.
// Scope these to `implementation` only (it propagates to compile/runtime
// classpath, which drive bundling) — NOT configurations.all, which would also
// strip stdlib from the Kotlin compiler's own tooling classpath and break the
// build. The platform supplies these from the IDE jars at compile and runtime.
configurations.named("implementation") {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-common")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
}

intellijPlatform {
    pluginConfiguration {
        name = "rider-mcp-plugin"
        val pluginSinceBuild = project.property("pluginSinceBuild") as String
        val pluginUntilBuild = project.property("pluginUntilBuild") as String
        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = pluginUntilBuild
        }
    }
    // Bundle the compiled .NET backend (dotFiles) into the plugin distribution.
    // See `buildReSharperHost` below.
}

// ---------------------------------------------------------------------------
// Backend (.NET) build wiring
// ---------------------------------------------------------------------------
val resharperPluginPath = layout.projectDirectory.dir("ReSharperPlugin")
val dotnetSolution = resharperPluginPath.file("RiderMcp.sln")

val buildReSharperHost = tasks.register<Exec>("buildReSharperHost") {
    group = "rider"
    description = "Builds the .NET (ReSharper) backend of the plugin."
    workingDir = resharperPluginPath.asFile
    // The backend references the C# model generated by rdgen, so generate first.
    dependsOn(":protocol:rdgen")
    // Requires the .NET SDK on PATH (`dotnet`). Not installed in every dev box —
    // CI and Rider's own build will provide it.
    commandLine("dotnet", "build", dotnetSolution.asFile.absolutePath,
        "-c", buildConfiguration)
}

// rdgen generates Kotlin (frontend) + C# (backend) stubs from :protocol.
// The generated C# is referenced by the .NET project; generated Kotlin is on
// the frontend source set. The :protocol module owns the rdgen configuration.
tasks.named("compileKotlin") {
    dependsOn(":protocol:rdgen")
}

tasks.named("buildPlugin") {
    dependsOn(buildReSharperHost)
}

// Bundle the compiled .NET backend into the plugin layout under dotnet/, where
// Rider loads backend plugin assemblies from.
val backendOutput = resharperPluginPath.dir("RiderMcp/bin/$buildConfiguration/net8.0")
tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask> {
    dependsOn(buildReSharperHost)
    from(backendOutput) {
        into("${rootProject.name}/dotnet")
        include("RiderMcp.dll", "RiderMcp.pdb")
    }
}

sourceSets {
    main {
        kotlin.srcDirs("src/rider/main/kotlin")
        resources.srcDirs("src/rider/main/resources")
    }
}

kotlin {
    // Rider 2026.1 runs on JBR 25, so we can build with JDK 25 and drop the
    // separate JDK 21 the older Gradle required.
    jvmToolchain(25)
}

