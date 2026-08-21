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
    compileOnly(libs.bundles.designer)
    compileOnly(libs.bundles.perspectiveDesigner)
    compileOnly(project(":modules:mcp:common"))
    modlImplementation(libs.kotlin.stdlib)

    // McpHttpServer and DiscoveryFile import no Ignition classes at all — only com.sun.net.httpserver,
    // the JDK, :common and slf4j — so the endpoint can be started and driven over real HTTP here.
    // `main` takes :common as compileOnly, so the test source set has to ask for it explicitly.
    testImplementation(project(":modules:mcp:common"))
    testImplementation(libs.bundles.common)
    testImplementation(libs.bundles.kotest)
}

tasks.test {
    useJUnitPlatform()
}
