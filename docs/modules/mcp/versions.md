---
title: Ignition 8.1 vs 8.3
sidebar_label: Version differences
sidebar_position: 3
---

# Ignition 8.1 vs 8.3

The module supports both platform lines, from two branches. The **tool set is identical** — 56
tools on each. What differs is how you authenticate, what the file is called, and a short list of
behaviours the platform forces.

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
| Tool count | 56 | 56 — parity |
| [`save_project`](./designer-save.md) permission pre-check | `canSaveProject` before the push | none — 8.1 exposes no equivalent, so the push is attempted and its failure reported |
| New Perspective components | only the properties you set are written | Perspective seeds its own defaults as well |
| Project files edited on disk | never picked up until `scan_resource_files` runs | the gateway also scans on its own |
| Unsigned dev builds | need commissioning approval | load directly; *signed* dev builds are the ones quarantined |
| Pre-edit backups | none — 8.3 keeps tags and project resources as files on disk, so version control is the recovery path | written before every tag-config and Perspective edit, under `~/.ignition/mcp/backups` or the gateway data directory |
| Dev mode | `-Dmcp.devMode=true` opens both endpoints and drops the Designer bearer secret | not available; the two shared secrets are already JVM arguments, so there is little left to skip |

**The tool sets match.** The ten tools that were 8.1-only-absent — the four tag configuration
tools, the five performance tools and `save_project` — were ported in five waves and are on the 8.1
line as of module 0.2.5. Names and arguments are identical on both lines, and
[Perspective](./perspective/index.md), the Designer bridge and the trial tools work the same way.
Where a platform genuinely can't do something the tool still exists with the same arguments rather
than disappearing: `scan_resource_files` is on both lines, and only its `config` target is
unavailable on 8.1.

The [tool reference](./tools/index.md) documents the 8.3 line and is now accurate for 8.1 too, with
one behavioural caveat: `save_project` does no permission pre-check there. 8.3 asks
`canSaveProject` before pushing; 8.1's `GatewayInterface` offers `pushProject` and `pullProject`
and no permission probe, so a save the gateway refuses surfaces as the gateway's own error rather
than as a rights refusal. Nothing is committed either way.

Nothing in the ported set needed an 8.3 API — every one resolved against the 8.1.43 jars, checked
rather than inferred. Porting them was in scope because the 8.1 branch's charter freezes it to
security and wrong-data fixes *but explicitly excepts changes that keep the tool surface identical
to the 8.3 line*.

Three differences in this document are forced by the platform rather than by anyone's choice:
`scan_resource_files`'s `config` target, `save_project`'s missing permission pre-check, and the
whole of the authentication split below. Everything else is a decision.

Two more are Perspective's and Ignition's own behaviour, not this module's, and both were confirmed
against 8.1.43 and 8.3.7 in August 2026:

- **Component property seeding.** The same `perspective_add_component` call produces a different
  file on each line: 8.1 writes Perspective's seeded defaults alongside your values, 8.3 writes only
  what you set. Nothing here normalises that, so a view built by these tools is not byte-comparable
  across the lines.
- **Disk edits need a scan on 8.3.** 8.3 never re-reads `data/projects` by itself, so a file edited
  underneath it — a git checkout, a hand edit — stays invisible until `scan_resource_files` runs.
  8.1 picks such changes up on its own. On 8.3 this is not a nicety: without the scan the gateway
  and the files have simply diverged.

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
cost classloader visibility of Perspective's classes and remove all 23 Perspective tools, which is
the worse trade.

On 8.3 the dependency is genuinely optional: without Perspective installed the `perspective_*`
tools are simply absent from `tools/list` rather than present and broken.

## Which do I have?

The gateway's own health endpoint tells you, no credential needed:

```bash
curl -s http://<gateway>:8088/data/mcp/health
```

The 8.1 build reports `"platform":"8.1"` and `"authConfigured"`; the 8.3 build reports neither.

The 8.3 payload also carries `"devMode"`. If it reads `true`, that gateway serves **both** endpoints
with no credential at all — see [Dev mode](./endpoints.md#dev-mode).
