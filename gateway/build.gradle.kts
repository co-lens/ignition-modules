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
    compileOnly(project(":common"))
    modlImplementation(libs.kotlin.stdlib)
}
