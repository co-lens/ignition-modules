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
  been approved. Approve it in **Config → Modules**. On a *dev* build this is version-dependent:
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

## 403 on `/data/mcp/mcp` while `/mcp-readonly` works

Working as designed: your credential is valid but doesn't carry write access.

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

The token needs the gateway's **write** permission, which by default means the `Administrator`
role, set under **Config → Security → Security Levels**.

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

The Designer bridge is a separate endpoint from the gateway's, with its own credential:

1. A Designer must be **open with a project loaded**.
2. Get the connection command from **Tools → MCP Connection Info…** — the port and secret are
   per-session and change every time the Designer restarts.
3. If your client isn't on the same machine as the Designer, the bridge binds to loopback by
   default. See [Reaching a Designer on another machine](./clients/remote-designer.md).

## The gateway stopped after two hours

That's the Ignition trial expiring, not the module. `reset_trial` restarts the countdown, and
there's an opt-in watchdog that does it automatically on a dev gateway — see
[Dev gateway](./contributing/dev-gateway.md).

## Something else

The [MCP Inspector](./clients/inspector.md) catches protocol-level problems that hand-written curl
won't, and `query_logs` (once you're connected at all) is usually faster than reading the gateway
log by hand.
