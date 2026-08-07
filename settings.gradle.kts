pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://nexus.inductiveautomation.com/repository/public") }
    }
}

rootProject.name = "ignition-modules"

dependencyResolutionManagement {
    repositories {
        // Ignition SDK artifacts (ignition-common, gateway-api, designer-api, ia-gson, ...)
        maven { url = uri("https://nexus.inductiveautomation.com/repository/public") }
        mavenCentral()
    }
}

// One block per module. A module's scope subprojects MUST be descendants of the project that
// applies io.ia.sdk.modl: the plugin only wires projects inside its own `allprojects` set, so a
// scope project outside that subtree is silently ignored rather than reported as an error.
include(
    ":modules:mcp",
    ":modules:mcp:common",
    ":modules:mcp:gateway",
    ":modules:mcp:designer",
)

// Build tooling, deliberately OUTSIDE any module's subtree. The modl plugin only wires projects
// inside the project that applies it, so nothing here can end up in a shipped .modl, and no
// module's `check` depends on it.
include(":tools:tool-docs")
