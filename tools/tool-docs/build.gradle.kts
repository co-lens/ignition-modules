plugins {
    kotlin("jvm")
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(libs.versions.java.get()))
    }
}

dependencies {
    implementation(project(":modules:mcp:common"))
    implementation(project(":modules:mcp:gateway"))
    implementation(project(":modules:mcp:designer"))

    // The module projects declare these `compileOnly`, so they expose no runtimeElements and none
    // of it reaches us transitively. The generator needs them for real — it constructs the tool
    // classes, whose signatures mention GatewayContext, DesignerContext and the Perspective types.
    implementation(libs.bundles.gateway)
    implementation(libs.bundles.designer)
    implementation(libs.bundles.perspectiveGateway)
    implementation(libs.bundles.perspectiveDesigner)
    implementation(libs.kotlin.stdlib)
}

application {
    mainClass.set("io.colens.tooldocs.MainKt")
}

/**
 * Regenerates the tool reference the docs site renders.
 *
 * The output is committed rather than built on demand, so a docs build needs only Node — no JDK,
 * no Gradle, and no dependency on nexus.inductiveautomation.com. `tool-reference.yml` runs this
 * task and fails on a diff, which turns "the JSON can go stale" into "a stale JSON can't merge".
 */
val generateToolDocs by tasks.registering(JavaExec::class) {
    group = "documentation"
    description = "Regenerates docs/src/data/tools.json from the tool registries."

    mainClass.set("io.colens.tooldocs.MainKt")
    classpath = sourceSets.main.get().runtimeClasspath

    // The designer artifact drags in Swing. Nothing is instantiated at construction time, but a CI
    // runner has no display and headless removes any doubt.
    systemProperty("java.awt.headless", "true")
    systemProperty("ignition.version", libs.versions.ignition.get())

    val output = rootProject.layout.projectDirectory.file("docs/src/data/tools.json")
    args(output.asFile.absolutePath)
    outputs.file(output)
    inputs.files(sourceSets.main.get().runtimeClasspath)
}
