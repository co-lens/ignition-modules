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
    // Ignition supplies these at runtime. ignition-common also brings the relocated Gson at
    // com.inductiveautomation.ignition.common.gson.* — the only JSON library this module uses.
    compileOnly(libs.bundles.common)
    // Perspective's component registry lives behind an interface here so both scopes share one
    // implementation. compileOnly, and the unit tests never load it, so :common:test still runs
    // with no Ignition and no Perspective present.
    compileOnly(libs.ignition.perspective.common)
    modlImplementation(libs.kotlin.stdlib)

    testImplementation(libs.bundles.common)
    testImplementation(libs.bundles.kotest)
}

tasks.test {
    useJUnitPlatform()
}
