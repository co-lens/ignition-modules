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

:::warning The bearer secret is the only thing protecting it
Loopback-only is the right default because the secret in the discovery file is the *sole*
credential. Once the endpoint is reachable from the network, that secret is all that stands between
your Designer and anything that can route to it. Pair a widened bind with a firewall rule or a
forwarded port rather than leaving it open, and don't carry this into production.
:::

## The advertised host

The connect dialog builds its command from the address the bridge bound to. With
`bindAddress=0.0.0.0` that is what it will show you — a valid address to *bind*, but not one to
*dial*. Substitute the address your client can actually reach, which in a VM or container setup is
often neither the guest's own address nor `0.0.0.0` but the host or container address that forwards
to it.
