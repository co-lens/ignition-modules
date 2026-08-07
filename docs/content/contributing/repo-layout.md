---
title: Repo layout
sidebar_position: 1
---

# Repo layout

This is a monorepo of Ignition modules. Each lives under `modules/`, carries independent semver,
and is released by its own tag — `mcp-v0.2.0` releases only the MCP module.

```
modules/mcp/       the Ignition MCP module — id io.colens.mcp-ign
  common    GD   MCP protocol + tool registry. Pure Kotlin, unit-tested without Ignition.
  gateway   G    Mounts the MCP endpoints under /data/mcp/. Gateway tools.
  designer  D    Loopback HTTP endpoint + discovery file. Designer tools.
tools/tool-docs/   generates the tool reference on this site
docs/              this site
docker/            throwaway dev gateway
```

`io.ia.sdk.modl` is applied at `:modules:mcp`, not at the root: one plugin application per module,
and the plugin only wires projects inside the applying project's own subtree. That subtree rule is
also why `common` sits inside the module rather than in a shared `libraries/` tree — sharing it
across modules later means depending on it via `modlApi`, not listing it in `projectScopes`, where
it would be silently ignored.

The same rule is why `tools/tool-docs` sits outside `modules/`: nothing there can end up in a
shipped `.modl`, and no module's `check` depends on it.
