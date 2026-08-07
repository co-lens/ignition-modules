---
title: Adding a module
sidebar_position: 3
---

# Adding a module

Six steps. The constraints worth knowing up front are in step 2 and step 5 — both are places where
getting it wrong fails *silently* rather than loudly.

## 1. Create the tree

```
modules/<name>/
  build.gradle.kts        the ignitionModule { } block
  common/                 shared across scopes, if the module needs it
  gateway/                G-scope code
  designer/               D-scope code
```

## 2. Register the projects

In `settings.gradle.kts`, add the module project **and every scope subproject**:

```kotlin
include(
    ":modules:<name>",
    ":modules:<name>:common",
    ":modules:<name>:gateway",
    ":modules:<name>:designer",
)
```

:::warning Scope projects must be descendants of the module project
`io.ia.sdk.modl` only wires projects inside the subtree of the project that applies it. A scope
project listed in `projectScopes` but living outside that subtree is **silently ignored** — no
error, just a `.modl` missing a jar.
:::

## 3. Configure the module

`modules/<name>/build.gradle.kts` applies `id("io.ia.sdk.modl")` and configures `ignitionModule {}`.
Copy `modules/mcp/build.gradle.kts` — it also carries the version property, the jar-manifest block
and the signing wiring, all of which a new module wants unchanged apart from the property name.

Give the module its own version property (`<name>Version`), with a snapshot default:

```kotlin
val moduleVersion: String = providers.gradleProperty("<name>Version").getOrElse("0.1.0-SNAPSHOT")
allprojects { version = moduleVersion }
```

Deliberately no dot in the property name, so `ORG_GRADLE_PROJECT_<name>Version` stays usable as an
escape hatch — unlike the `module.*` signing properties.

## 4. Give it a release tag prefix

Copy `.github/workflows/release.yml` to `release-<name>.yml`, changing the tag glob (`<name>-v*`),
the version property, the Gradle project path and the `fileName`.

Once there are two of these, convert the shared body into a `workflow_call` workflow with one thin
caller per module. Not a matrix — a tag releases exactly one module, so a matrix would rebuild the
others for nothing.

## 5. Add it to the tool-reference generator

If the module exposes MCP tools, add its tool classes to
`tools/tool-docs/src/main/kotlin/io/colens/tooldocs/Main.kt`.

:::warning The staleness gate cannot catch this one
CI regenerates the reference and fails on a diff, which catches a *changed* description. It cannot
catch a tool class that was never listed, because the generator's output stays self-consistent.
:::

## 6. Add it to this site

- a folder under `docs/modules/<name>/`
- a sidebar export in `docs/sidebarsModules.ts`
- an entry in the navbar `Modules` dropdown in `docs/docusaurus.config.ts`
- a row in the module table on the [overview](../index.md)
