pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
    // rdgen is published only as a library (com.jetbrains.rd:rd-gen), not as a
    // Gradle plugin marker — redirect the plugin id to that artifact so the
    // `plugins { id("com.jetbrains.rdgen") }` blocks resolve. Keep this version
    // in sync with `rdVersion` in gradle.properties.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.jetbrains.rdgen") {
                useModule("com.jetbrains.rd:rd-gen:2026.1.3")
            }
        }
    }
    // Centralized plugin versions; build scripts apply by id without versions.
    plugins {
        id("org.jetbrains.kotlin.jvm") version "2.3.0"
        id("org.jetbrains.intellij.platform") version "2.16.0"
    }
}

rootProject.name = "rider-mcp-plugin"

// RD protocol model lives in its own module so it can be consumed by both
// the Kotlin frontend (rdgen -> Kotlin) and the .NET backend (rdgen -> C#).
include(":protocol")

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
        // MCP Kotlin SDK + Ktor are on Maven Central; nothing extra required here.
    }
}
