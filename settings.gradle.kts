pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven { url = uri("https://nexus.inductiveautomation.com/repository/public") }
    }
}

rootProject.name = "mcp-ign"

dependencyResolutionManagement {
    repositories {
        // Ignition SDK artifacts (ignition-common, gateway-api, designer-api, ia-gson, ...)
        maven { url = uri("https://nexus.inductiveautomation.com/repository/public") }
        mavenCentral()
    }
}

include(":common", ":gateway", ":designer")
