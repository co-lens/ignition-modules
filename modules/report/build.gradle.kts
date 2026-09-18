plugins {
    id("io.ia.sdk.modl")
}

/**
 * Version, independent per module. The release workflow derives `0.2.0` from the tag
 * `report-v0.2.0` and passes `-PreportVersion=0.2.0`; locally it defaults to a snapshot, so a dev
 * build can never be mistaken for a release artifact.
 *
 * The property name deliberately has no dot, unlike the `module.*` signing properties, so
 * `ORG_GRADLE_PROJECT_reportVersion` stays usable as an escape hatch.
 *
 * This must be assigned before the `ignitionModule` block below, which reads `project.version`
 * eagerly. Gradle configures a parent before its children, so `allprojects` here — meaning this
 * project and its two scope subprojects — lands in time for all of them.
 */
val reportVersion: String = providers.gradleProperty("reportVersion").getOrElse("0.1.0-SNAPSHOT")
allprojects { version = reportVersion }

/**
 * `Implementation-Version` in every scope jar's manifest. GatewayHook reads
 * `javaClass.package.implementationVersion` from its own jar; without it `/data/report/health`
 * falls back to "dev".
 */
subprojects {
    plugins.withId("java") {
        tasks.named<Jar>("jar") {
            manifest.attributes(
                "Implementation-Title" to "Ignition Report",
                "Implementation-Version" to project.version.toString(),
                "Implementation-Vendor" to "co-lens",
            )
        }
    }
}

/**
 * Assembling the .modl only needs the scope subprojects' `jar`, not their `test` — so without
 * this aggregation `:modules:report:build`, which is the release command, would happily publish
 * past a red `:modules:report:common:test`.
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
 * Ignition-Report.modl untouched, which is how a stale or unsigned artifact could be published
 * with a green build. `module.requireSigning=true` turns that skip into a configuration-time
 * failure; CI sets it, so a missing or misnamed secret fails the job instead.
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
    name.set("Ignition Report")
    fileName.set("Ignition-Report")
    id.set("io.colens.report-ign")
    moduleVersion.set("${project.version}")
    moduleDescription.set("Excel reporting for Ignition: historical data API, add-in hosting, and scheduled headless report delivery.")
    requiredIgnitionVersion.set(libs.versions.ignition)
    freeModule.set(true)

    projectScopes.putAll(
        mapOf(
            ":modules:report:common" to "GD",
            ":modules:report:gateway" to "G",
        )
    )

    hooks.putAll(
        mapOf(
            "io.colens.report.gateway.GatewayHook" to "G",
        )
    )

    skipModlSigning.set(!signingConfigured)
}
