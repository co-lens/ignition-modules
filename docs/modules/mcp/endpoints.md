---
title: Endpoints
sidebar_position: 3
---

# Endpoints

## Gateway

| Endpoint | Auth | Tools |
| --- | --- | --- |
| `POST /data/mcp/mcp` | API token with gateway **write** permission | all 17, or 25 with Perspective installed |
| `POST /data/mcp/mcp-readonly` | any valid API token | the 14 read-only ones, or 22 with Perspective |
| `GET /data/mcp/health` | none | status/version |

The two counts differ because the `perspective_*` tools are only registered when Perspective is
installed — see [Perspective](./perspective/index.md). All eight of the gateway-side Perspective
tools are read-only, so they add to both numbers equally.

### Write gating is structural

The read-only endpoint is backed by a registry that doesn't contain the mutating tools, so a
read-scoped token can't list *or* call them. One consequence worth knowing when debugging: calling
`write_tags` through the read-only endpoint fails with `Unknown tool`, not with a permission error.

:::danger A write token is gateway root
`run_script` executes arbitrary Jython in gateway scope. Anyone holding a write token effectively
has the gateway. Scope your tokens accordingly, or don't issue write tokens at all.
:::

## Designer {#designer}

When a Designer with this module opens a project, it starts a loopback-only HTTP endpoint on an
OS-assigned port and writes `~/.ignition/mcp/designer-<pid>.json` (mode 0600) containing the port
and a per-session bearer secret. **Tools → MCP Connection Info…** shows the ready-to-paste connect
command, which carries the live host, port and secret:

```bash
claude mcp add --transport http ignition-designer \
  http://127.0.0.1:<port>/mcp \
  --header "Authorization: Bearer <secret>"
```

The Designer's value over the gateway is that writes are **staged, not committed** — they show up
as unsaved Designer changes for a human to review and save. Nothing here writes to the gateway on
its own.

If the Designer isn't on the same machine as your client, see
[Reaching a Designer on another machine](./clients/remote-designer.md).

## What's on each

The [tool reference](./tools/index.md) lists every tool on both scopes, generated from the module's
own declarations: [gateway](./tools/gateway.md) and [Designer](./tools/designer.md).
