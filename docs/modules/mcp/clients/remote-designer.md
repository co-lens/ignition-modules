---
title: Reaching a Designer on another machine
sidebar_label: Remote Designer
sidebar_position: 1
---

# Reaching a Designer on another machine

The bridge binds to loopback on an OS-assigned port, which assumes the MCP client runs on the same
machine as the Designer. When it doesn't — a Designer in a VM, or on a workstation you're driving
remotely — two JVM arguments on the Designer opt out of that:

```
-Dmcp.designer.bindAddress=0.0.0.0;-Dmcp.designer.port=8770
```

Both default to the safe behaviour, and the module logs a warning when you widen the bind.

:::note A pinned port is one per machine
The OS-assigned default is what lets several Designers coexist — each takes a free port and writes
its own `designer-<pid>.json`. Pin a port only when something needs to forward or firewall it.

A second Designer inherits the same JVM arguments from the launcher, so it asks for the same pinned
port and can't have it. Rather than lose its endpoint, it warns and takes an OS-assigned port:

```
WARN  mcp.Designer.Http -- Port 8770 (-Dmcp.designer.port) is already in use, most likely by
      another Designer on this machine. Fell back to OS-assigned port 41337. ...
```

So a forwarded port reaches whichever Designer started **first**. For the others, read the real
address off **Tools → MCP Connection Info…**, or pin genuinely different ports per Designer.
:::

:::warning Set every argument you need in one go — they replace, they don't accumulate
The launcher's JVM-argument field is a single value. Adding `-Dmcp.designer.allowSave=true` to a
Designer that already had `-Dmcp.designer.bindAddress=0.0.0.0` **replaces** it unless you type both,
putting the bridge silently back on loopback:

```
-Dmcp.designer.bindAddress=0.0.0.0;-Dmcp.designer.allowSave=true
```

This has cost two sessions time, and the failure it produces is the one below.
:::

## "Connection refused", and how to tell why

`ECONNREFUSED` from a client on another machine is indistinguishable from a dead port, a wrong port,
or a Designer that never started. Two things tell them apart:

- **The connect dialog** (**Tools → MCP Connection Info…**) shows the address the bridge actually
  bound to. If it reads `127.0.0.1` and your client is elsewhere, the bind is the problem — not the
  port and not the secret.
- **The discovery file** on the Designer's machine,
  `~/.ignition/mcp/designer-<pid>.json`, records the same thing in a form a client can act on:

  ```json
  {
    "host": "127.0.0.1",
    "url": "http://127.0.0.1:41337/mcp",
    "loopbackOnly": true,
    "hostname": "designer-vm"
  }
  ```

  `loopbackOnly: true` with a `hostname` that isn't the caller's own means the endpoint is up and
  healthy but reachable only from `designer-vm`. A client that reads the file can say so instead of
  passing a bare connection error along.

:::warning The bearer secret is the only thing protecting it
Loopback-only is the right default because the secret in the discovery file is the *sole*
credential — and under [`-Dmcp.devMode=true`](../endpoints.md#dev-mode) there is no credential at
all, so never combine dev mode with a widened bind. Once the endpoint is reachable from the network, that secret is all that stands between
your Designer and anything that can route to it. Pair a widened bind with a firewall rule or a
forwarded port rather than leaving it open, and don't carry this into production.
:::

## The advertised host

The connect dialog builds its command from the address the bridge bound to. With
`bindAddress=0.0.0.0` that is what it will show you — a valid address to *bind*, but not one to
*dial*. Substitute the address your client can actually reach, which in a VM or container setup is
often neither the guest's own address nor `0.0.0.0` but the host or container address that forwards
to it.
