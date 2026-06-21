import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
import org.jetbrains.intellij.platform.gradle.TestFrameworkType

// ---------------------------------------------------------------------------
// Root build — IntelliJ/Rider frontend (Kotlin) + orchestration of the
// .NET backend build. The :protocol module generates the shared RD models.
// ---------------------------------------------------------------------------

plugins {
    kotlin("jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
    id("com.jetbrains.rdgen") version "2024.3.1"
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

dependencies {
    intellijPlatform {
        create(IntelliJPlatformType.Rider, platformVersion)
        testFramework(TestFrameworkType.Platform)
    }

    // Shared RD protocol models (generated Kotlin side).
    implementation(project(":protocol"))

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

intellijPlatform {
    pluginConfiguration {
        name = "rider-mcp-plugin"
        val sinceBuild: String by project
        val untilBuild: String by project
        ideaVersion {
            this.sinceBuild = sinceBuild
            this.untilBuild = untilBuild
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
