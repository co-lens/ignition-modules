---
title: Releasing
sidebar_position: 2
---

# Releasing

Push a tag; the workflow builds, signs and publishes. Each module has its own tag prefix and its
own semver, so releasing one never version-bumps another.

```bash
git tag -a mcp-v0.2.0 -m "Release 0.2.0"
git push origin mcp-v0.2.0
```

## The version format is narrower than SemVer

The version must match `MAJOR.MINOR.PATCH` with optionally `-rcN` or `-betaN`; anything else is
rejected before the build starts.

That is deliberate. Ignition's `common.model.Version` accepts only `-rcN`, `-betaN` and
`-SNAPSHOT`, and `ModuleInfoParser` calls it on `<version>` with no error handling — so a
SemVer-legal tag like `1.0.0-alpha.1` would parse fine in the workflow and then fail to install on
someone's gateway, long after anyone could do anything about it. An `-rc`/`-beta` tag publishes as
a GitHub pre-release, which is excluded from `/releases/latest`.

## What the workflow guarantees

It refuses to release a commit that isn't on `main`, refuses to overwrite an existing release, and
asserts the built `.modl` really is signed — that its `signatures.properties` covers every jar, and
that `module.xml` and the jar manifests all agree on the version. `workflow_dispatch` runs the whole
thing and publishes nothing, which is the way to rehearse a change.

That last check matters more than it looks: when signing credentials are absent the module plugin
skips signing *silently* and leaves any previously built `.modl` in place, so "the file exists"
proves nothing on its own.

## Signing keys

Signing uses a release key held in repository secrets, separate from any local dev key.

:::warning Rotating the key is a migration, not a chore
Ignition pins the certificate fingerprint per gateway. Signing with a different key re-prompts
**every** gateway that already has the module installed, and on 8.3 a gateway will not reach
RUNNING unattended until an operator accepts it.
:::
