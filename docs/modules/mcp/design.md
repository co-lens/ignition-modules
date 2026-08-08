---
title: Why it's small
sidebar_position: 9
---

# Why it's small

The whole module is about 2,500 lines of Kotlin, because three things fell out of the design rather
than being built:

- **No MCP SDK, no SSE, no sessions.** The MCP spec lets a server answer every request with a
  single JSON object, and sessions are optional (removed outright in the 2026-07-28 revision). So
  the transport is a stateless POST endpoint: one route handler plus a JSON-RPC dispatcher handling
  five methods. `GET`/`DELETE` return `405`, which is exactly what a modern-revision server is told
  to answer.
- **No authentication code.** Routes are mounted with `ApiTokenManager`'s access-control
  strategies, so Ignition validates the `X-Ignition-API-Token` header and the token's permissions
  before our handler runs.
- **No JSON dependency.** Ignition ships Gson relocated to
  `com.inductiveautomation.ignition.common.gson`, available in every scope. The only jar this
  module bundles is the Kotlin stdlib.

The same instinct shows up in the [tool reference](./tools/index.md) on this site: rather than
maintain a parallel description of 52 tools, a build task constructs the real tool registries
against a stub context and dumps their metadata. The documentation is the code's own output.
