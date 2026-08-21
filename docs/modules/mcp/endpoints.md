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
| `POST /data/mcp/mcp` | write | all 25, or 35 with Perspective installed |
| `POST /data/mcp/mcp-readonly` | read | the 17 read-only ones, or 27 with Perspective |
| `GET /data/mcp/health` | none | status, version, tool counts, usage totals |

The two counts differ because the `perspective_*` tools are only registered when Perspective is
present. All ten gateway-side Perspective tools are read-only, so they add to both numbers
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
  "version": "0.5.0",
  "mcpEndpoint": "/data/mcp/mcp",
  "mcpReadOnlyEndpoint": "/data/mcp/mcp-readonly",
  "tools": 35,
  "readOnlyTools": 27,
  "perspectiveTools": 10,
  "requests": 1284,
  "errors": 3,
  "toolErrors": 2,
  "protocolErrors": 1,
  "anonymousRead": false,
  "devMode": false,
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

- **Read** — any valid token. Issued and revoked per client in **Platform → Security → API Keys**.
- **Write** — a token that additionally satisfies the gateway's write permission, which by default
  means the `Administrator` role. A token cannot hold that role — 8.3 ignores `Authenticated/Roles`
  levels granted to an API key — so reaching the write endpoint requires a custom security level
  added to the gateway's write permission. See
  [Issue a credential](./credentials.md#write-permission).

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

`thread_dump` and `thread_hotspots` are worth calling out separately: they return live stack traces
from inside the gateway JVM, which is a step beyond what the other read tools disclose. Behind a
token that is fine — it is the same audience that can already read your tags and project source.
Anonymously, it is not.

It is off by default and logs a WARN under `mcp.Gateway` at every startup when on. Use it on an
isolated dev gateway; never on anything reachable from a plant network.
:::

The write endpoint is unaffected by the flag and always requires a valid token with write
permission. A gateway whose `accessPermissions` have been tightened still enforces them, since the
flag relaxes only this module's own requirement.

#### Dev mode — opting out of every credential {#dev-mode}

`-Dmcp.devMode=true` is the flag for a throwaway gateway you are developing against. It drops the
credential from **both** endpoints, so neither checks anything at all:

```
wrapper.java.additional.9=-Dmcp.devMode=true
```

That removes the whole setup ritual — no API key to create, no custom security level to wire into
Gateway Write Permissions, no *Require secure connections* to untick. It also implies
[`mcp.designer.allowSave`](./designer-save.md) and `mcp.trialWatchdog`, and turns off
[Origin checking](#origin-checking). Set it on the Designer's JVM too — the Designer runs in its own
process, so one flag in `ignition.conf` cannot reach it. Note it no longer touches the Designer
bridge's credential either way: the bridge requires none by default, and a
`-Dmcp.designer.secret` you pinned deliberately is still enforced under dev mode. Turning off Origin
checking on a Designer is the part worth thinking about — that allowlist is what keeps a web page
out of an endpoint that otherwise needs no credential.

:::danger Dev mode hands the gateway to anyone who can reach the port
This is a larger step than `allowAnonymousRead`, which only opens the read side. Dev mode opens the
**write** endpoint, and that endpoint carries `run_script` — arbitrary Jython in gateway scope,
which is root on the gateway. Anyone who can open a TCP connection to the web port can run code as
the gateway user, read every database connection, and rewrite any project.

Two consequences worth naming, because neither is obvious:

- `jvm_health` returns this JVM's `-D` arguments verbatim, so any secret passed as a system
  property is readable by an unauthenticated caller.
- With Origin checking off, a page open in a browser **on your own machine** can drive a dev-mode
  gateway on `localhost`. The loopback bind is no longer a boundary.

It is off by default and logs a WARN under `mcp.Gateway` at every startup when on, and
`/data/mcp/health` reports `"devMode": true`. The metric `mcp.gateway.devMode` also appears on
**Diagnostics → Metrics Dashboard** — but *not* on the gateway's status card, which renders only
four metrics. Use it on an isolated dev gateway and nowhere else.
:::

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

#### Dev mode {#dev-mode-81}

`-Dmcp.devMode=true` works on this line too, and does the same thing: both endpoints answer with no
`Authorization` header at all, whatever the two secrets say, and the Origin allowlist is off. It
also implies [`mcp.designer.allowSave`](./designer-save.md) and `mcp.trialWatchdog`. It does **not**
drop the Designer bridge's credential: the bridge requires none by default, and a pinned
`-Dmcp.designer.secret` is enforced even under dev mode.

It saves less here than on 8.3 — the credential is already a `-D` property, so it only spares you
inventing one — and it is correspondingly easier to leave on by accident. `/data/mcp/health`
reports `"devMode": true`, and the gateway logs a WARN naming both secrets at every startup.

:::danger This overrides a configured secret
Dev mode does not merely fill in for a missing secret; it ignores one that is set. It opens the
**write** endpoint and `run_script` with it. Note too that `jvm_health` returns this JVM's `-D`
arguments verbatim — so with dev mode on, `readSecret` and `writeSecret` are both readable by an
unauthenticated caller. Isolated dev gateways only.
:::

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

When a Designer with this module opens a project, it starts a **loopback-only** HTTP endpoint on
port **8770** and writes `~/.ignition/mcp/designer-<pid>.json` recording where it landed.
**Tools → MCP Connection Info…** shows the ready-to-paste command:

```bash
claude mcp add --transport http ignition-designer-<project> http://127.0.0.1:8770/mcp
```

**No credential by default.** The bridge is loopback-only, and a per-session secret went stale on
every Designer restart — it cost a re-paste each time and bought nothing a same-UID attacker did not
already have, since the discovery file holding it was readable by that user anyway. Both halves of
the old command rotated, in fact: the port was OS-assigned too, so a saved client entry died on the
next restart no matter what. A fixed port and no credential is what makes the command above worth
saving.

To require a bearer token, set `-Dmcp.designer.secret=<value>` on the Designer's JVM. It is pinned
by you, so it survives restarts, and the dialog then shows a command carrying it. **Set one whenever
you widen the bind, and on a shared machine** — loopback is not per-user, so another signed-in user
cannot read your discovery file but can still reach your port. See
[Credentials](./credentials.md#designer).

The server name carries the project so that a second Designer's command **adds** a server rather
than overwriting the first one's entry. Several Designers can run at once: the first takes 8770 and
the rest fall back to an OS-assigned port with a warning, so take the later ones' addresses from the
dialog — or pin each with `-Dmcp.designer.port`. `-Dmcp.designer.port=0` restores OS assignment.
A secret, if you set one, is per-*machine* rather than per-Designer.

This behaves the same on both platform lines — the bridge runs its own server and never touches
Ignition's authentication.

The Designer's value over the gateway is that writes are **staged, not committed** by default: they appear as
unsaved Designer changes for a human to review and save. Nothing here writes to the gateway on its
own.

If the Designer isn't on the same machine as your client, see
[Reaching a Designer on another machine](./clients/remote-designer.md) — and read the warning
there, because widening the bind without setting `-Dmcp.designer.secret` leaves the endpoint open to
anything that can route to it.

## Origin checking

Requests carrying a browser `Origin` header are checked against loopback by default. To allow a
non-loopback browser origin — the MCP Inspector served from elsewhere, say — start the gateway with
`-Dmcp.allowedOrigins=https://tools.example.com`.

[Dev mode](#dev-mode) accepts every Origin, which is convenient and also removes the defence that
stops a web page from reaching a gateway on your loopback interface.

## What's on each endpoint

The [tool reference](./tools/index.md) lists every tool on both scopes, generated from the module's
own declarations: [gateway](./tools/gateway.md) and [Designer](./tools/designer.md).
