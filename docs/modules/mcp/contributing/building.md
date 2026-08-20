---
title: Building
sidebar_position: 1
---

# Building

```bash
./gradlew :modules:mcp:build         # protocol tests + modules/mcp/build/Ignition-MCP.unsigned.modl
./gradlew :modules:mcp:common:test   # protocol tests alone; no Ignition needed
```

Pass `-PmcpVersion=0.2.0` to stamp a version; it defaults to `0.1.0-SNAPSHOT`. Run `clean` when you
change it — `moduleContent/` accumulates jars, and the plugin refuses to package two versions of
the same library.

## Signing

Signing is skipped unless you set `module.keystorePath` and friends — see
`gradle.properties.template`. Put real values in `~/.gradle/gradle.properties`; the repo's
`gradle.properties` is gitignored so a keystore password can't be committed by accident.

:::warning Skipping is silent
When credentials are absent the plugin skips signing without failing, and leaves any previously
signed `.modl` in place — so "the file exists" proves nothing. CI additionally sets
`-Pmodule.requireSigning=true`, which turns a missing credential into a build failure.
:::

**Signing locally is worth it, but it is not what avoids the commissioning prompt.** An unsigned
module has no certificate fingerprint for the gateway to remember, so on 8.3 a *bare* container
stops at `COMMISSIONING` and waits for a human. Setting
[`ACCEPT_MODULE_CERTS`](../docker.md#accept_module_certs-is-what-stops-it-hanging-at-commissioning)
fixes that for unsigned builds too — measured on 8.3.7, an unsigned module with that variable set
reaches RUNNING unattended from a cold start and across restarts. Sign anyway when you want the
build to resemble a release. A self-signed cert is enough:

```bash
mkdir -p ~/.mcp-ign-signing && cd ~/.mcp-ign-signing
keytool -genkeypair -alias mcp-ign -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=mcp-ign dev, OU=Dev, O=colens, C=US" \
  -keystore keystore.pfx -storetype PKCS12 -storepass changeit -keypass changeit
keytool -exportcert -alias mcp-ign -keystore keystore.pfx -storetype PKCS12 \
  -storepass changeit -rfc -file cert.pem
```

then put the five `module.*` properties in `~/.gradle/gradle.properties`.

Keep this dev key separate from the release key held in repository secrets — see
[Releasing](/contributing/releasing).

:::note `deployModl` is broken on Gradle 9
The plugin's `Deploy` task fails configuration validation (`property 'targetUrl' ... is in
'java.*'`). The [dev gateway](./dev-gateway.md) mounts the build output directly instead, which
needs no deploy step at all.
:::

## Regenerating the tool reference

The [tool reference](../tools/index.md) on this site is generated from the module's own
declarations. After changing a tool's name, title, description or schema:

```bash
./gradlew :tools:tool-docs:generateToolDocs
```

and commit `docs/src/data/tools.json`. CI regenerates and fails on a diff, so a stale reference
can't merge.
