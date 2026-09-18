plugins {
    `java-library`
    kotlin("jvm")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }
}

dependencies {
    compileOnly(libs.bundles.gateway)
    // The 8.3 Historian module's own API. This module is a reporting front end for it, so the
    // dependency is declared required in the ignitionModule block — a missing historian is a
    // load-time failure with a clear message on the gateway status page, which beats loading
    // successfully and failing every query later.
    compileOnly(libs.bundles.historian)
    compileOnly(project(":modules:report:common"))
    // NEVER modlImplementation. Ignition ships poi 4.1.2 in lib/core/common, and parent-first
    // classloading means a bundled copy is shadowed rather than used.
    compileOnly(libs.bundles.poi)
    modlImplementation(libs.kotlin.stdlib)

    testImplementation(libs.bundles.gateway)
    testImplementation(libs.bundles.historian)
    testImplementation(project(":modules:report:common"))
    testImplementation(libs.bundles.poi)
    testImplementation(libs.bundles.kotest)
}

tasks.test {
    useJUnitPlatform()
}
