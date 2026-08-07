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
    compileOnly(libs.bundles.perspectiveGateway)
    compileOnly(project(":modules:mcp:common"))
    modlImplementation(libs.kotlin.stdlib)
}
