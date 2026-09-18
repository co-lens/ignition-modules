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
    compileOnly(project(":modules:report:common"))
    // NEVER modlImplementation. Ignition ships poi 4.1.2 in lib/core/common, and parent-first
    // classloading means a bundled copy is shadowed rather than used.
    compileOnly(libs.bundles.poi)
    modlImplementation(libs.kotlin.stdlib)

    testImplementation(libs.bundles.gateway)
    testImplementation(project(":modules:report:common"))
    testImplementation(libs.bundles.poi)
    testImplementation(libs.bundles.kotest)
}

tasks.test {
    useJUnitPlatform()
}
