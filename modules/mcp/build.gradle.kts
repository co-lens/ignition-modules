plugins {
    id("io.ia.sdk.modl")
}

/**
 * Version, independent per module. The release workflow derives `0.2.0` from the tag `mcp-v0.2.0`
 * and passes `-PmcpVersion=0.2.0`; locally it defaults to a snapshot, so a dev build can never be
 * mistaken for a release artifact.
 *
 * The property name deliberately has no dot, unlike the `module.*` signing properties, so
 * `ORG_GRADLE_PROJECT_mcpVersion` stays usable as an escape hatch.
 *
 * This must be assigned before the `ignitionModule` block below, which reads `project.version`
 * eagerly. Gradle configures a parent before its children, so `allprojects` here — meaning this
 * project and its three scope subprojects — lands in time for all of them.
 */
val mcpVersion: String = providers.gradleProperty("mcpVersion").getOrElse("0.1.0-SNAPSHOT")
allprojects { version = mcpVersion }

/**
 * `Implementation-Version` in every scope jar's manifest. GatewayHook and DesignerHook each read
 * `javaClass.package.implementationVersion` from *their own* jar, so both need it; :common gets it
 * for the same five lines. This is what `/data/mcp/health` and MCP `initialize` report — without
 * it they fall back to "dev".
 */
subprojects {
    plugins.withId("java") {
        tasks.named<Jar>("jar") {
            manifest.attributes(
                "Implementation-Title" to "Ignition MCP",
                "Implementation-Version" to project.version.toString(),
                "Implementation-Vendor" to "co-lens",
            )
        }
    }
}

/**
 * Assembling the .modl only needs the scope subprojects' `jar`, not their `test` — so without this
 * aggregation `:modules:mcp:build`, which is the release command, would happily publish past a red
 * `:modules:mcp:common:test`.
 */
tasks.named("check") {
    dependsOn(subprojects.map { "${it.path}:check" })
}

/**
 * Signing credentials come from `~/.gradle/gradle.properties` (or the file the release workflow
 * writes into the runner's Gradle home) — never from a committed file. See
 * gradle.properties.template. Unsigned builds work fine against a dev gateway started with
 * -Dignition.allowunsignedmodules=true.
 *
 * When credentials are absent the plugin *skips* signing silently and leaves any pre-existing
 * Ignition-MCP.modl untouched, which is how a stale or unsigned artifact could be published with a
 * green build. `module.requireSigning=true` turns that skip into a configuration-time failure; CI
 * sets it, so a missing or misnamed secret fails the job instead.
 */
val signingConfigured = providers.gradleProperty("module.keystorePath").isPresent
val signingRequired = providers.gradleProperty("module.requireSigning")
    .map(String::toBoolean)
    .getOrElse(false)

check(!signingRequired || signingConfigured) {
    "module.requireSigning=true but module.keystorePath is not set — refusing to produce an unsigned .modl."
}

tasks.signModule {
    if (signingConfigured) {
        keystorePath.set(providers.gradleProperty("module.keystorePath"))
        keystorePw.set(providers.gradleProperty("module.keystorePw"))
        certFilePath.set(providers.gradleProperty("module.certFilePath"))
        certPw.set(providers.gradleProperty("module.certPw"))
        alias.set(providers.gradleProperty("module.alias"))
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
            ":modules:mcp:common" to "GD",
            ":modules:mcp:gateway" to "G",
            ":modules:mcp:designer" to "D",
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
