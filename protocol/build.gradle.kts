import com.jetbrains.rd.generator.gradle.RdGenTask

// ---------------------------------------------------------------------------
// :protocol — defines the RD model shared between the Kotlin frontend and the
// .NET backend. `rdgen` reads the model classes below and emits:
//   * Kotlin  -> consumed by the frontend (src/rider generated sources)
//   * C#      -> consumed by the ReSharper backend project
// ---------------------------------------------------------------------------

plugins {
    kotlin("jvm")
    id("com.jetbrains.rdgen")
}

val rdVersion: String by project

repositories {
    mavenCentral()
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

dependencies {
    implementation("com.jetbrains.rd:rd-gen:$rdVersion")
}

val ktOutput = layout.projectDirectory.dir("../src/rider/main/kotlin/dev/ridermcp/model")
val csOutput = layout.projectDirectory.dir("../ReSharperPlugin/RiderMcp/Model")

rdgen {
    verbose = true
    packages = "model.rider"

    generator {
        language = "kotlin"
        transform = "asis"
        root = "model.rider.RiderMcpRoot"
        namespace = "dev.ridermcp.model"
        directory = ktOutput.asFile.absolutePath
    }

    generator {
        language = "csharp"
        transform = "reversed"
        root = "model.rider.RiderMcpRoot"
        namespace = "RiderMcp.Model"
        directory = csOutput.asFile.absolutePath
    }
}

tasks.withType<RdGenTask> {
    // rdgen needs the model classes on its classpath.
    dependsOn(tasks.named("compileKotlin"))
    classpath(sourceSets.main.get().runtimeClasspath)
}
