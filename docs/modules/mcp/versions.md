---
title: Ignition 8.1 vs 8.3
sidebar_label: Version differences
sidebar_position: 3
---

# Ignition 8.1 vs 8.3

The module supports both platform lines, from two branches. **The tools are identical** — same
names, same arguments, same behaviour, all 46 of them. What differs is how you authenticate and
what the file is called.

:::warning The 8.1 line is time-limited
It exists for people who can't move to 8.3 yet, receives only security and wrong-data bug fixes,
and is **unsupported after February 2027**. Published releases keep working after that; nothing
further is built. If you have a choice, use 8.3.
:::

## What differs

| | **Ignition 8.3** | **Ignition 8.1** |
| --- | --- | --- |
| Credential | API token, issued per client, revocable in the gateway UI | two shared secrets set as JVM arguments |
| Header | `X-Ignition-API-Token: <keyId>:<secret>` | `Authorization: Bearer <secret>` |
| Read vs write | the token's gateway permissions | which of the two secrets you send |
| Revoking access | delete the token in the UI | edit `ignition.conf`, restart the gateway |
| Download | `Ignition-MCP-<version>.modl` | `Ignition-MCP-81-<version>.modl` |
| Release tag | `mcp-v*` | `mcp81-v*` |
| Perspective | optional — the module loads without it | **required** — the module will not install without it |
| Config file scan | `scan_resource_files` covers `target: config` | config lives in `config.idb`, not on disk, so that target reports itself unavailable |
| Unsigned dev builds | need commissioning approval | load directly; *signed* dev builds are the ones quarantined |

Everything else — the [tool reference](./tools/index.md), [Perspective](./perspective/index.md),
the Designer bridge, the trial tools — is the same on both. Note the *tool list* is identical even
where a platform can't do something: `scan_resource_files` exists on both lines with the same
arguments, and only the `config` target is unavailable on 8.1.

## Why 8.1 authentication is weaker

Not a stylistic difference. **Ignition 8.1 has no API tokens at all**, so the module has to supply
its own credential scheme. What it supplies is two shared bearer secrets, and you should know
exactly what that costs:

- **Not revocable** without restarting the gateway.
- **Visible** in the process table and on the gateway's own status page to anyone who can log in.
- **Shared** by every client, rather than issued per client.
- The write secret grants `run_script` — arbitrary Jython in gateway scope. That is gateway root.

**On 8.1, set only `readSecret` unless you specifically need writes.** With the write secret unset,
the write endpoint is closed and answers 401 to everything.

## Why Perspective is required on 8.1

8.1's module descriptor parser has no `required` attribute on dependencies, so optional module
dependencies don't exist on that platform line. The alternative — dropping the dependency — would
cost classloader visibility of Perspective's classes and remove all 19 Perspective tools, which is
the worse trade.

On 8.3 the dependency is genuinely optional: without Perspective installed the `perspective_*`
tools are simply absent from `tools/list` rather than present and broken.

## Which do I have?

The gateway's own health endpoint tells you, no credential needed:

```bash
curl -s http://<gateway>:8088/data/mcp/health
```

The 8.1 build reports `"platform":"8.1"` and `"authConfigured"`; the 8.3 build reports neither.
