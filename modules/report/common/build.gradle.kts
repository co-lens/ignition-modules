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
    // ignition-common brings the relocated Gson at com.inductiveautomation.ignition.common.gson.*,
    // which is the only JSON library this module uses — matching :modules:mcp:common.
    compileOnly(libs.bundles.common)
    modlImplementation(libs.kotlin.stdlib)

    testImplementation(libs.bundles.common)
    testImplementation(libs.bundles.kotest)
    // POI is on the gateway's own classpath at runtime (see the catalog comment), so main code
    // takes it compileOnly. Tests need a real copy to load, and it must be the SAME 4.1.2 — a
    // test passing against 5.x would prove nothing about what happens on a gateway.
    testImplementation(libs.bundles.poi)
}

tasks.test {
    useJUnitPlatform()
}
