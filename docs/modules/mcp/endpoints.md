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
| `POST /data/mcp/mcp` | write | all 18, or 26 with Perspective installed |
| `POST /data/mcp/mcp-readonly` | read | the 14 read-only ones, or 22 with Perspective |
| `GET /data/mcp/health` | none | status, version, tool counts, usage totals |

The two counts differ because the `perspective_*` tools are only registered when Perspective is
present. All eight gateway-side Perspective tools are read-only, so they add to both numbers
equally.

### The health endpoint

No credential, which is what makes it the right first check when nothing else is working:

```bash
curl -s http://<gateway>:8088/data/mcp/health
```

```json
{
  "status": "ok",
  "server": "ignition-mcp",
  "version": "0.2.0",
  "mcpEndpoint": "/data/mcp/mcp",
  "mcpReadOnlyEndpoint": "/data/mcp/mcp-readonly",
  "tools": 26,
  "readOnlyTools": 22,
  "perspectiveTools": 8,
  "requests": 1284,
  "errors": 3,
  "toolErrors": 2,
  "protocolErrors": 1,
  "anonymousRead": false,
  "trialWatchdog": "off"
}
```

`requests` counts JSON-RPC requests answered across both endpoints since the module last started;
notifications aren't counted, because nothing is waiting on them. The error split is the useful
part: a **`toolError`** is a tool that ran and failed — answered `200` with `isError`, so your
client saw a reply — while a **`protocolError`** never reached a tool at all. A client that looks
healthy but is getting nothing useful shows up as `toolErrors` climbing.

`trialWatchdog` is one of `off`, `running`, `stood down` (there was no longer a trial to reset) or
`gave up` (three consecutive failed resets).

`anonymousRead` is reported rather than hidden: an unauthenticated caller can already discover it
by POSTing to the read-only endpoint and being answered.

### What "credential" means

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

An **API token**, sent as `X-Ignition-API-Token: <keyId>:<secret>`. Ignition validates it before
the module's handler runs, so there is no authentication code in this module at all.

- **Read** — any valid token. Issued and revoked per client in **Config → Security → API Tokens**.
- **Write** — a token that additionally satisfies the gateway's write permission, which by default
  means the `Administrator` role.

Both routes require the token to *validate*, on top of the permission check. That distinction
matters more than it sounds: the gateway's `accessPermissions` property ships as an empty
permission set, and an empty set is satisfied by the anonymous caller, so a permission check alone
would let an unauthenticated request through.

#### Opting out of the read credential

`-Dmcp.gateway.allowAnonymousRead=true` serves the read-only endpoint without any token:

```
wrapper.java.additional.9=-Dmcp.gateway.allowAnonymousRead=true
```

:::danger This publishes the gateway's data to anyone who can reach the port
Every read-only tool becomes available with no credential — `run_query` against your database
connections, `read_project_resource` for project source, `read_tags`, `query_logs`. There is no
per-client identity, nothing to revoke, and nothing in the logs tying a read to a caller.

It is off by default and logs a WARN under `mcp.Gateway` at every startup when on. Use it on an
isolated dev gateway; never on anything reachable from a plant network.
:::

The write endpoint is unaffected by the flag and always requires a valid token with write
permission. A gateway whose `accessPermissions` have been tightened still enforces them, since the
flag relaxes only this module's own requirement.

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
