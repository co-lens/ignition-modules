---
title: Ignition 8.1 vs 8.3
sidebar_label: Version differences
sidebar_position: 3
---

# Ignition 8.1 vs 8.3

The module supports both platform lines, from two branches. What differs is how you authenticate,
what the file is called, and — for the first time — a handful of tools the 8.1 line will not be
getting.

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
| Minimum platform version | 8.3.7 (module 0.3.2+) | 8.1.43 (module 0.2.1+) |
| Tool count | 56 | 46 — the ten below are absent |
| Tag configuration | `configure_tags`, `delete_tags`, `rename_tag`, `import_tags` | absent — unported |
| [Performance tools](./performance.md) | all five | absent — unported |
| [`save_project`](./designer-save.md) | opt-in via `-Dmcp.designer.allowSave` | absent — unported |
| Unsigned dev builds | need commissioning approval | load directly; *signed* dev builds are the ones quarantined |

For the 46 tools present on both lines, the names, arguments and behaviour are identical, and
[Perspective](./perspective/index.md), the Designer bridge and the trial tools work the same way.
Where a platform genuinely can't do something the tool still exists with the same arguments rather
than disappearing: `scan_resource_files` is on both lines, and only its `config` target is
unavailable on 8.1. The [tool reference](./tools/index.md) documents the 8.3 line, so treat the ten
tools listed above as the delta when reading it against an 8.1 gateway.

**Those ten are absent because nobody has ported them, not because 8.1 can't run them.** Every SDK
API they need exists in 8.1.43 with the same signatures — verified against the 8.1.43 jars, not
inferred. The 8.1 branch's charter freezes it to security and wrong-data fixes *but explicitly
excepts changes that keep the tool surface identical to the 8.3 line*, so porting them is in scope
whenever someone picks it up.

`scan_resource_files`'s `config` target is the only difference in this document forced by the
platform itself.

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
