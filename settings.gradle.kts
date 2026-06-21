rootProject.name = "rider-mcp-plugin"

// RD protocol model lives in its own module so it can be consumed by both
// the Kotlin frontend (rdgen -> Kotlin) and the .NET backend (rdgen -> C#).
include(":protocol")

pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven("https://cache-redirector.jetbrains.com/intellij-dependencies")
        // MCP Kotlin SDK + Ktor are on Maven Central; nothing extra required here.
    }
}
