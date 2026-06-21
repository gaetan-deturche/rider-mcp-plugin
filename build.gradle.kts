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

val pluginGroup: String by project
val pluginVersion: String by project
val platformType: String by project
val platformVersion: String by project
val buildConfiguration: String by project
val mcpServerPort: String by project

group = pluginGroup
version = pluginVersion

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

// Expose the Rider RD model jar from the downloaded SDK so the :protocol module
// can compile its model against SolutionModel. The path is resolved lazily,
// once the IntelliJ Platform has been initialized (SDK extracted).
val riderModel: Configuration by configurations.creating {
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
        rider(platformVersion, useInstaller = false)
        jetbrainsRuntime()
        // Solution protocol access (com.jetbrains.rider.projectView.solution) and
        // rd platform coroutines (startSuspending) live in core product jars,
        // already on the classpath — no extra bundled module needed.
    }

    // NB: we do NOT depend on project(":protocol") at runtime. rdgen emits the
    // Kotlin model directly into this module's source set, and it compiles
    // against the platform's rd framework (rd.jar). Depending on the protocol
    // jar would drag rd-gen + kotlin-compiler-embeddable into the plugin.

    // --- MCP server (HTTP / SSE transport) ---------------------------------
    // Official MCP Kotlin SDK. Pin to a version that matches your Ktor line;
    // verify the latest at https://github.com/modelcontextprotocol/kotlin-sdk
    implementation("io.modelcontextprotocol:kotlin-sdk:0.4.0")

    // Embedded HTTP server for the SSE / streamable-HTTP MCP endpoint.
    implementation("io.ktor:ktor-server-cio:3.0.3")
    implementation("io.ktor:ktor-server-sse:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.0.3")
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
        val pluginSinceBuild: String by project
        val pluginUntilBuild: String by project
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

val buildReSharperHost by tasks.registering(Exec::class) {
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
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.add("-Xcontext-receivers")
    }
}
