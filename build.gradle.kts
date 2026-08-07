// This project builds nothing. It exists only to put the Kotlin and Ignition-module plugins on the
// build classpath at a single declared version; each module then applies them by bare id.
//
// Versions are deliberately NOT set here. Every module carries independent semver and sets its own
// version from a per-module Gradle property, so that a `mcp-v0.2.0` tag can move the MCP module
// without touching anything else. See modules/mcp/build.gradle.kts.
plugins {
    alias(libs.plugins.kotlin) apply false
    alias(libs.plugins.modl) apply false
}
