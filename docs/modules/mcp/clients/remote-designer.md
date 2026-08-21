---
title: Reaching a Designer on another machine
sidebar_label: Remote Designer
sidebar_position: 1
---

# Reaching a Designer on another machine

The bridge binds to loopback on port 8770, which assumes the MCP client runs on the same machine as
the Designer. When it doesn't — a Designer in a VM, or on a workstation you're driving remotely —
opt out on the Designer's JVM:

```
-Dmcp.designer.bindAddress=0.0.0.0;-Dmcp.designer.secret=<32+ random characters>
```

Widening the bind is the case the credential exists for; see the warning below. Add
`-Dmcp.designer.port=<port>` as well if 8770 is taken or you want a specific one. The module logs a
warning when you widen the bind, and a louder one when you do it without a secret.

:::note A pinned port is one per machine
Only the first Designer gets any given port — the rest take a free one and write their own
`designer-<pid>.json`. That is true of the 8770 default as much as of a pinned port, so a second
Designer always warns:

```
WARN  mcp.Designer.Http -- Port 8770 (the default) is already in use, most likely by
      another Designer on this machine. Fell back to OS-assigned port 41337. ...
```

The message says where the port came from — `the default`, or `-Dmcp.designer.port` if you pinned
it — so you know whether there is a flag to look for. Pin a genuinely different port per Designer
when you run several and need each to be addressable.

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

**It now has a sharper edge.** Dropping `-Dmcp.designer.secret` the same way does not put the bridge
back somewhere safe — it leaves it bound wide with **no credential at all**, and nothing in the
launcher tells you. No code can catch this: a `bindAddress` set with no secret is indistinguishable
from an operator who never wanted one. The Designer's startup WARN is the only signal, so read the
console after changing that field.
:::

## When the Designer is in a VM or a container

Widening the bind only helps if something can route to the Designer's machine. A Designer in a VM
with **NAT** networking can reach your gateway while nothing can reach *it* — outbound working tells
you nothing about inbound, and that asymmetry is the single most common reason this looks broken
after the JVM arguments are correct.

Two ways out, in order of effort:

- Switch the VM's adapter to **bridged**, so it gets an address on your LAN, or add a port-forward
  for the pinned port in the hypervisor.
- Or don't cross the boundary at all: run your MCP client **inside** the VM, where the bridge is on
  loopback and none of this applies.

If the VM itself runs inside a container, the guest is usually on a bridge private to that
container, and its address may well collide with one on your host — so probing it from the host can
answer from an entirely different machine. Enter the container's network namespace first, and use
`curl` rather than a shell's `/dev/tcp`, which is a bash builtin many container shells lack.

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
    "hostname": "designer-vm",
    "auth": "none"
  }
  ```

  `loopbackOnly: true` with a `hostname` that isn't the caller's own means the endpoint is up and
  healthy but reachable only from `designer-vm`. A client that reads the file can say so instead of
  passing a bare connection error along. `auth` is `none` or `bearer`; when it is `bearer` the
  `secret` field carries the value to send.

:::danger Widening the bind without a secret leaves the endpoint open
The bridge requires **no credential by default**. That is defensible on loopback; it is not once you
widen the bind. With `bindAddress` set and no `-Dmcp.designer.secret`, anything that can route to
this machine can read and edit the open project — and commit it, if
[`allowSave`](../designer-save.md) is on.

So widening the bind and setting a secret are one step, not two:

```
-Dmcp.designer.bindAddress=0.0.0.0;-Dmcp.designer.secret=<32+ random characters>
```

Generate one with `openssl rand -hex 24`. The Designer logs a WARN naming the property when it binds
wide without one, and an ERROR if `allowSave` is on as well. Pair either with a firewall rule or a
forwarded port rather than leaving it open, and don't carry this into production.

Note [`-Dmcp.devMode=true`](../endpoints.md#dev-mode) no longer removes this credential — a secret
you pin is enforced regardless — but it *does* turn off Origin checking, which is what keeps a web
page out. Still don't combine it with a widened bind.
:::

## The advertised host

The connect dialog builds its command from the address the bridge bound to. With
`bindAddress=0.0.0.0` that is what it will show you — a valid address to *bind*, but not one to
*dial*. Substitute the address your client can actually reach, which in a VM or container setup is
often neither the guest's own address nor `0.0.0.0` but the host or container address that forwards
to it.
