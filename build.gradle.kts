plugins {
    alias(libs.plugins.modl)
    alias(libs.plugins.kotlin) apply false
}

allprojects {
    version = "0.1.0-SNAPSHOT"
}

/**
 * Signing credentials come from `~/.gradle/gradle.properties` (or -P flags) — never from a
 * committed file. See gradle.properties.template. Unsigned builds work fine against a dev
 * gateway started with -Dignition.allowunsignedmodules=true.
 */
val signingConfigured = providers.gradleProperty("module.keystorePath").isPresent

tasks {
    deployModl {
        hostGateway.set(providers.gradleProperty("hostGateway").orElse("http://localhost:18088"))
    }

    signModule {
        if (signingConfigured) {
            keystorePath.set(providers.gradleProperty("module.keystorePath"))
            keystorePw.set(providers.gradleProperty("module.keystorePw"))
            certFilePath.set(providers.gradleProperty("module.certFilePath"))
            certPw.set(providers.gradleProperty("module.certPw"))
            alias.set(providers.gradleProperty("module.alias"))
        }
    }
}

ignitionModule {
    name.set("Ignition MCP")
    fileName.set("Ignition-MCP")
    id.set("io.colens.mcp-ign")
    moduleVersion.set("${project.version}")
    moduleDescription.set("Model Context Protocol server for Ignition gateways and Designers.")
    requiredIgnitionVersion.set(libs.versions.ignition)
    freeModule.set(true)

    projectScopes.putAll(
        mapOf(
            ":common" to "GD",
            ":gateway" to "G",
            ":designer" to "D",
        )
    )

    // Perspective is declared OPTIONAL. In Ignition a module dependency is what grants
    // classloader visibility of another module's classes — without this entry our Perspective
    // code cannot load at all, no matter that it compiles. `required = false` gets that
    // visibility when Perspective is installed while still letting this module load on a gateway
    // that doesn't have it, where the perspective_* tools are simply absent from tools/list.
    moduleDependencySpecs {
        register("com.inductiveautomation.perspective") {
            scope = "GD"
            required = false
        }
    }

    hooks.putAll(
        mapOf(
            "io.colens.mcp.gateway.GatewayHook" to "G",
            "io.colens.mcp.designer.DesignerHook" to "D",
        )
    )

    skipModlSigning.set(!signingConfigured)
}
