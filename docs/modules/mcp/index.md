---
title: Ignition MCP
sidebar_label: Overview
sidebar_position: 1
---

# Ignition MCP

A [Model Context Protocol](https://modelcontextprotocol.io) server that runs **inside** Ignition,
as a Kotlin module. It gives an AI client structured access to a gateway — tags, project resources,
SQL, tag history, alarms, logs — and, optionally, to a running Designer, where it can build and
edit Perspective views.

:::info Supports Ignition 8.3 and 8.1
Module id `io.colens.mcp-ign`. The tools are identical on both lines; authentication and the
download name differ — see [version differences](./versions.md). **The 8.1 line is time-limited and
unsupported after February 2027.**
:::

**[Start with the quickstart →](./quickstart.md)** Four steps, about ten minutes.

## What it's for

Reading and diagnosing, mostly. Point a model at a gateway you've never seen and ask what's on it;
ask why a binding is returning bad quality; ask what changed in the log at 14:20. Then, with a
Designer connected, ask it to build the view.

[What you can ask for](./using.md) has worked examples of each.

## Two things worth knowing early

**A write credential is gateway root.** The write endpoint exposes `run_script`, which executes
arbitrary Jython in gateway scope. Write gating is structural rather than advisory — the read-only
endpoint is backed by a registry that doesn't contain the mutating tools — but the moment you issue
a write credential, you have handed over the gateway. Start read-only; it covers every diagnostic
use. [Endpoints and security](./endpoints.md) covers this properly.

**Designer writes are staged, not committed.** Everything the Designer tools write appears as an
unsaved change for a human to review and save. Nothing in this module commits to the gateway on its
own, and the gateway endpoint has no project-write surface at all.

## Where to go next

| | |
| --- | --- |
| [Quickstart](./quickstart.md) | Install, credential, connect. |
| [What you can ask for](./using.md) | What's actually possible, with examples. |
| [Perspective](./perspective/index.md) | Reading, editing, validating and diagnosing views. |
| [Tool reference](./tools/index.md) | All 46 tools, generated from the code. |
| [Endpoints & security](./endpoints.md) | The three endpoints and what guards them. |
| [Troubleshooting](./troubleshooting.md) | 401s, 404s, missing tools. |
| [Version differences](./versions.md) | 8.3 vs 8.1. |
