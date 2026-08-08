---
title: Tool reference
sidebar_label: Overview
sidebar_position: 1
---

# Tool reference

Every tool on both scopes, with its arguments.

**These pages are generated from the module's own `Tool` declarations**, by a Gradle task that
constructs the tool registries and dumps their metadata. The per-tool JSON comes from the same
`Tool.toJson()` method the MCP server uses on the wire — so a description here is, byte for byte,
the description your model receives from `tools/list`. It cannot drift.

- **[Gateway](./gateway.md)** — 26 tools, of which 4 are write-scoped.
- **[Designer](./designer.md)** — 26 tools, of which 16 are write-scoped.

Fifty-two entries, 46 distinct names: the six Perspective *read* tools are served on both scopes. They
aren't quite identical between the two — the `project` argument means something different on a
Designer, which is always operating on the open project — so both are listed rather than
cross-referenced.

## Badges

| Badge | Meaning |
| --- | --- |
| `read-only` | Served on both `/data/mcp/mcp` and `/data/mcp/mcp-readonly`. |
| `write` | Served only on `/data/mcp/mcp`, which needs a token with gateway write permission. |
| `destructive` | Annotated `destructiveHint`: the effect is hard to undo. |

The badges are read from each tool's own annotations, which are also what decides which endpoint
serves it — so the labels here and the actual gating cannot disagree.

:::danger `run_script` is arbitrary code execution
It runs Jython in gateway scope with the whole `system.*` API. Anyone with a write token has the
gateway. See [Endpoints](../endpoints.md).
:::

## Perspective

The `perspective_*` tools only appear when Perspective is installed on the gateway; without it they
are simply absent from `tools/list` rather than present and broken. Reading and diagnosing work
from either scope; **editing is Designer-only** and every edit stages as an unsaved Designer change.
See [Perspective](../perspective/index.md).
