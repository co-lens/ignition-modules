---
slug: /
title: co-lens Ignition modules
sidebar_label: Overview
sidebar_position: 1
---

# co-lens Ignition modules

Ignition modules written in Kotlin, each carrying independent semver and released by its own tag.

:::info Requires Ignition 8.3
Every module here targets the 8.3 platform line.
:::

## Modules

| Module | Ignition module id | Release tag |
| --- | --- | --- |
| **[Ignition MCP](/modules/mcp/)** — a [Model Context Protocol](https://modelcontextprotocol.io) server that runs *inside* Ignition, giving an AI client structured access to a gateway and, optionally, to a running Designer. | `io.colens.mcp-ign` | `mcp-v*` |

New here? Start with the **[Ignition MCP quickstart](/modules/mcp/quickstart)** — four steps, about
ten minutes, from download to a connected client.

## How this repo is organised

Each module lives under `modules/<name>/` with its own scope subprojects, its own version, and its
own release tag, so shipping one module never version-bumps another. See
[Repo layout](./contributing/repo-layout.md) for the shape and the reasoning, and
[Releasing](./contributing/releasing.md) for how a release is cut.

## Licence

MIT. See [LICENSE](https://github.com/co-lens/ignition-modules/blob/main/LICENSE).
