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

val rdVersion = project.property("rdVersion") as String

repositories {
    mavenCentral()
    maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
}

dependencies {
    implementation("com.jetbrains.rd:rd-gen:$rdVersion")
    // stdlib is needed here because the root disables the default stdlib dep.
    implementation(kotlin("stdlib"))
    // Rider RD model (SolutionModel) exposed by the root project's SDK.
    implementation(project(mapOf("path" to ":", "configuration" to "riderModel")))
}

val ktOutput = layout.projectDirectory.dir("../src/rider/main/kotlin/dev/ridermcp/model")
val csOutput = layout.projectDirectory.dir("../ReSharperPlugin/RiderMcp/Model")

rdgen {
    verbose = true
    packages = "model.rider"

    generator {
        language = "kotlin"
        transform = "asis"
        root = "com.jetbrains.rider.model.nova.ide.IdeRoot"
        namespace = "dev.ridermcp.model"
        directory = ktOutput.asFile.absolutePath
    }

    generator {
        language = "csharp"
        transform = "reversed"
        root = "com.jetbrains.rider.model.nova.ide.IdeRoot"
        namespace = "RiderMcp.Model"
        directory = csOutput.asFile.absolutePath
    }
}

tasks.withType<RdGenTask> {
    // rdgen needs the model classes on its classpath.
    dependsOn(tasks.named("compileKotlin"))
    classpath(sourceSets.main.get().runtimeClasspath)
}
