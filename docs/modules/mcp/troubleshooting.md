---
title: Troubleshooting
sidebar_position: 8
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Troubleshooting

Work down the list — each check assumes the one above it passed.

## Is the module even loaded?

```bash
curl -s http://<gateway>:8088/data/mcp/health
```

This endpoint needs no credential, which makes it the right first check.

| What you get | What it means |
| --- | --- |
| `{"status":"ok",...}` | The module is running. Move to the next section. |
| `{"status":"starting"}` | It loaded but the hook hasn't finished. Wait a few seconds and retry. |
| **404** | It didn't load. See below. |
| Connection refused | Wrong host or port, or the gateway itself is down. |

**On a 404**, check the gateway log for the `mcp.Gateway` logger and for the module manager:

```bash
# Config → Logs in the gateway UI, or on a container:
docker logs <container> 2>&1 | grep -iE 'mcp\.|Ignition MCP|quarantine'
```

The usual causes:

- **"Moving module … to quarantine because certificate not yet accepted"** — the certificate hasn't
  been approved. Approve it in **Config → Modules**, or — in a container — set
  [`ACCEPT_MODULE_CERTS`](./docker.md#accept_module_certs-is-what-stops-it-hanging-at-commissioning),
  which accepts it at boot without anyone clicking. On a *dev* build this is version-dependent:
  8.3 quarantines **unsigned** modules, and 8.1 quarantines modules signed with an **unknown**
  certificate while loading unsigned ones directly. See
  [version differences](./versions.md).
- **On 8.1, no Perspective installed** — the module hard-depends on it and won't install without
  it.
- **Version mismatch** — an `Ignition-MCP-81-*.modl` on an 8.3 gateway, or vice versa. The gateway
  refuses it.

## 401 Unauthorized

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

**The overwhelmingly common cause: Require Secure Channel.** New API tokens have it on by default,
and it makes the token fail with 401 over plain HTTP no matter what else is right. Either use
HTTPS, or untick it on the token for a local gateway.

Then check, in order:

1. The header is `X-Ignition-API-Token`, not `Authorization`.
2. The value is `<keyId>:<secret>` — both halves, colon-separated.
3. The token still exists and is enabled in **Config → Security → API Tokens**.
4. You're sending a token at all. The read-only endpoint requires one — if you have a client that
   used to work without a header, see below.

:::note If read-only requests started returning 401 after an upgrade
Earlier builds served `/mcp-readonly` to callers with no token at all, because the permission set
being checked was empty and an empty set admits anonymous requests. That was a security bug, not a
feature; the endpoint now requires a valid token like the docs always said. Issue a token for the
client, or — for an isolated dev gateway only — set
[`-Dmcp.gateway.allowAnonymousRead=true`](./endpoints.md#opting-out-of-the-read-credential).
:::

:::tip If this is a gateway you can afford to throw away
[`-Dmcp.devMode=true`](./endpoints.md#dev-mode) makes every 401 and 403 on this page go away by
removing the credential check from both endpoints. That includes the write endpoint and
`run_script`, so it is only ever right for a disposable gateway.
:::

</TabItem>
<TabItem value="81" label="Ignition 8.1">

1. The header is `Authorization: Bearer <secret>`, not `X-Ignition-API-Token`.
2. `-Dmcp.gateway.readSecret` is actually set — check `/data/mcp/health`, which reports
   `"authConfigured"`. If that's `false`, the JVM argument didn't take.
3. The JVM argument was added to `ignition.conf` **and the gateway was restarted**. These are read
   once at startup.
4. No stray quotes or trailing whitespace in the `ignition.conf` line.

The gateway log carries an ERROR at startup naming the exact property when no secret is set.

</TabItem>
</Tabs>

## 403 on `/data/mcp/mcp` while `/mcp-readonly` works {#write-403}

Working as designed: your credential is valid but doesn't carry write access.

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

The key needs the gateway's **write** permission, which a default 8.3 ships as
`AnyOf[Authenticated/Roles/Administrator]`.

Ticking `Administrator` on the key will not do it — 8.3 ignores `Authenticated/Roles` and
`SecurityZones` levels granted to an API key, so on a default gateway **no key can satisfy
`writePermissions` at all**. You have to create a security level of your own, add it to **Gateway
Write Permissions**, and grant it to the key: see
[Issue a credential](./credentials.md#write-permission) for the three steps.

If you granted a level and still get 403, check that you ticked it under **Gateway Write
Permissions** and not only on the key — the key holding a level the gateway never asks for changes
nothing.

</TabItem>
<TabItem value="81" label="Ignition 8.1">

You're sending the read secret. The write endpoint needs `-Dmcp.gateway.writeSecret`, and
`/data/mcp/health` reports `"writeEndpointEnabled"` so you can tell whether it's configured at all.

Note the 8.1 build returns **401** here rather than 403 — there is one credential per endpoint, so
"wrong credential" and "insufficient credential" are the same thing.

</TabItem>
</Tabs>

Before you fix it: a write credential exposes `run_script`, which is arbitrary Jython in gateway
scope. Consider whether you actually need it — see [Endpoints and security](./endpoints.md).

## "Unknown tool" instead of a permission error

You called a write tool through `/data/mcp/mcp-readonly`. Write gating is structural rather than
permission-based: the read-only endpoint is backed by a registry that doesn't contain the mutating
tools, so they can't be listed *or* called there. Point your client at `/data/mcp/mcp` with a write
credential.

## The `perspective_*` tools are missing

On **8.3**, Perspective is an optional dependency: without it installed, those tools are absent
from `tools/list` rather than present and broken. Install Perspective and restart.

On **8.1** this shouldn't happen — Perspective is a hard dependency, so the module wouldn't have
installed at all.

## The Designer tools aren't available

The Designer bridge is a separate endpoint from the gateway's, and by default it needs no
credential at all:

1. A Designer must be **open with a project loaded**.
2. Get the connection command from **Tools → MCP Connection Info…**. It is stable across restarts —
   the bridge defaults to port 8770 and requires no token — so a saved client entry keeps working.
3. If your client isn't on the same machine as the Designer, the bridge binds to loopback by
   default. See [Reaching a Designer on another machine](./clients/remote-designer.md).

**A 401 from the Designer bridge** means exactly one thing: `-Dmcp.designer.secret` is set on that
Designer's JVM and your client's `Authorization` header doesn't match it. Check the **Auth:** row in
the connect dialog — it reads `none required` or `bearer`. A bridge with no secret configured never
returns 401, and ignores a stale header rather than rejecting it.

**A 415** means your client didn't send `Content-Type: application/json`. The bridge requires it, so
that a browser cannot reach it with a form-style request.

**With a second Designer open**, both endpoints run, each with its own discovery file. The first
takes 8770; the second warns that it fell back to an OS-assigned port — take its real address from
the connect dialog, or pin each Designer with `-Dmcp.designer.port`. The connect command is named
after the project (`ignition-designer-<project>`) so adding the second doesn't overwrite the first.

**On `java.net.BindException: Address already in use`** in the Designer console, check *both*
JVM-argument fields in
`~/.ignition/clientlauncher-data/designer-launcher.json` — the global
`global.client.defaults.jvm.arguments` **and** the per-application `applications[].jvm.arguments`.
Clearing one leaves the other in force.

**On a bare "connection refused"**, don't assume a dead port. A loopback bind produces exactly the
same error from another machine. The tell is the connect dialog reading `127.0.0.1`, or
`"loopbackOnly": true` in `~/.ignition/mcp/designer-<pid>.json` on the Designer's own machine — see
[Connection refused, and how to tell why](./clients/remote-designer.md#connection-refused-and-how-to-tell-why).

## I edited a file and nothing happened

Ignition serves what it loaded, not what is on disk. **On Ignition 8.3 nothing re-reads the disk
unless you ask it to** — 8.3 has no periodic scan at all. (8.1 rescans projects roughly every five
minutes, so there it eventually self-heals.)

1. Run `scan_resource_files`. It reports which resources actually changed; `changedCount: 0` means
   the gateway already matched the disk.
2. If it reports nothing and you expected a change, check the file is somewhere the gateway reads:
   `data/projects/<project>/…` for project resources, `data/config/…` for gateway config.
3. Gateway config is file-based **only from 8.3**. On 8.1 it lives in `config.idb`, so
   `target: config` reports itself unavailable and there is nothing a scan can do.
4. If the gateway now has the change but a **Designer** still shows the old version, that's
   expected — run `merge_gateway_changes` on the Designer endpoint.

## merge_gateway_changes refuses with a conflict list

Working as designed. It means an unsaved edit in the Designer touches the same resource as a change
waiting on the gateway, and merging would discard your edit silently. Save or discard those
resources in the Designer, then call it again.

The check reflects what the gateway has already pushed to the Designer, so straight after a scan
give the notification a moment to arrive — the tool waits a few seconds itself when there are
unsaved edits, which you can extend with `settleSeconds`.

## The gateway stopped after two hours

That's the Ignition trial expiring, not the module. `reset_trial` restarts the countdown, and
there's an opt-in watchdog that does it automatically on a dev gateway — see
[Dev gateway](./contributing/dev-gateway.md).

## Something else

The [MCP Inspector](./clients/inspector.md) catches protocol-level problems that hand-written curl
won't, and `query_logs` (once you're connected at all) is usually faster than reading the gateway
log by hand.
