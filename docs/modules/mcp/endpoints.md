---
title: Endpoints and security
sidebar_label: Endpoints & security
sidebar_position: 6
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Endpoints and security

## Gateway

| Endpoint | Credential | Tools |
| --- | --- | --- |
| `POST /data/mcp/mcp` | write | all 17, or 25 with Perspective installed |
| `POST /data/mcp/mcp-readonly` | read | the 14 read-only ones, or 22 with Perspective |
| `GET /data/mcp/health` | none | status, version, platform |

The two counts differ because the `perspective_*` tools are only registered when Perspective is
present. All eight gateway-side Perspective tools are read-only, so they add to both numbers
equally.

### What "credential" means

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

An **API token**, sent as `X-Ignition-API-Token: <keyId>:<secret>`. Ignition validates it before
the module's handler runs, so there is no authentication code in this module at all.

- **Read** — any valid token. Issued and revoked per client in **Config → Security → API Tokens**.
- **Write** — a token that additionally satisfies the gateway's write permission, which by default
  means the `Administrator` role.

</TabItem>
<TabItem value="81" label="Ignition 8.1">

A **shared bearer secret**, sent as `Authorization: Bearer <secret>`, because 8.1 has no API
tokens. Two JVM arguments in `ignition.conf` decide who gets what:

- `-Dmcp.gateway.readSecret` → the read-only endpoint.
- `-Dmcp.gateway.writeSecret` → the write endpoint, **and** the read-only one. (Not an escalation:
  the read-only registry is a strict subset, so a write-secret holder can already call every read
  tool through `/mcp`.)

Unset means nobody, never everybody — an unset secret closes its endpoint, and the routes answer
401 rather than disappearing. `/data/mcp/health` reports `authConfigured` and
`writeEndpointEnabled` so you can check without a credential.

These secrets are materially weaker than tokens — not revocable without a restart, visible in the
process table, shared by every client. See [version differences](./versions.md).

</TabItem>
</Tabs>

### Write gating is structural

The read-only endpoint is backed by a registry that doesn't contain the mutating tools, so a
read-scoped caller can't list *or* call them. One practical consequence when debugging: calling
`write_tags` through the read-only endpoint fails with `Unknown tool`, not with a permission error.

:::danger A write credential is gateway root
`run_script` executes arbitrary Jython in gateway scope with the whole `system.*` API. Anyone
holding a write credential effectively has the gateway. Issue one deliberately, scope it tightly,
or don't issue one at all — the read-only endpoint covers every diagnostic and reporting use.
:::

## Designer {#designer}

When a Designer with this module opens a project, it starts a **loopback-only** HTTP endpoint on an
OS-assigned port and writes `~/.ignition/mcp/designer-<pid>.json` (mode 0600) containing the port
and a per-session bearer secret. **Tools → MCP Connection Info…** shows the ready-to-paste command:

```bash
claude mcp add --transport http ignition-designer \
  http://127.0.0.1:<port>/mcp \
  --header "Authorization: Bearer <secret>"
```

This is identical on both platform lines — the bridge runs its own server and never touches
Ignition's authentication, which is why the 8.1 port needed no Designer changes.

The Designer's value over the gateway is that writes are **staged, not committed**: they appear as
unsaved Designer changes for a human to review and save. Nothing here writes to the gateway on its
own.

If the Designer isn't on the same machine as your client, see
[Reaching a Designer on another machine](./clients/remote-designer.md) — and read the warning
there, because widening the bind makes that per-session secret the only thing protecting it.

## Origin checking

Requests carrying a browser `Origin` header are checked against loopback by default. To allow a
non-loopback browser origin — the MCP Inspector served from elsewhere, say — start the gateway with
`-Dmcp.allowedOrigins=https://tools.example.com`.

## What's on each endpoint

The [tool reference](./tools/index.md) lists every tool on both scopes, generated from the module's
own declarations: [gateway](./tools/gateway.md) and [Designer](./tools/designer.md).
