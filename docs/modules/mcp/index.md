---
title: Ignition MCP
sidebar_label: Overview
sidebar_position: 1
---

# Ignition MCP

A [Model Context Protocol](https://modelcontextprotocol.io) server that runs **inside** Ignition,
as a Kotlin module. It gives an AI client structured access to a gateway — tags, project resources,
SQL, tag history, alarms, logs — and, optionally, to a running Designer.

:::info Requires Ignition 8.3
Module id `io.colens.mcp-ign`. Released as `mcp-v<version>`.
:::

## Start here

**[Quickstart](./quickstart.md)** — four steps, about ten minutes, from download to a connected
client.

## Then

- **[Endpoints](./endpoints.md)** — the three gateway endpoints, how write access is gated, and the
  Designer bridge.
- **[Tool reference](./tools/index.md)** — all 50 tools, generated from the module's own
  declarations so nothing here can drift from what the model actually receives.
- **[Perspective](./perspective/index.md)** — reading, editing, validating and diagnosing views.
- **[Clients](./clients/remote-designer.md)** — reaching a Designer on another machine, and the MCP
  Inspector.
- **[Contributing](./contributing/building.md)** — building, the throwaway dev gateway, and adding
  a tool.
- **[Why it's small](./design.md)** — the design decisions that kept this to ~2,500 lines.

## Two things worth knowing early

**A write token is gateway root.** The write endpoint exposes `run_script`, which executes
arbitrary Jython in gateway scope. Write gating is structural rather than advisory — the read-only
endpoint is backed by a registry that doesn't contain the mutating tools — but the moment you issue
a write token, you have handed over the gateway. [Endpoints](./endpoints.md) covers this properly.

**Designer writes are staged, not committed.** Everything the Designer tools write appears as an
unsaved change for a human to review and save. Nothing in this module commits to the gateway on its
own.
