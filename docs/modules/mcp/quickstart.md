---
title: Quickstart
sidebar_position: 2
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Quickstart

Three steps, about five minutes: get the module, install it, point your client at it.

You need an Ignition gateway you can restart, and an MCP client — the examples use
[Claude Code](https://claude.com/claude-code), but any client that speaks Streamable HTTP works.

Pick your Ignition version in the tabs below; the choice is remembered across the whole site.

## 1. Get the module

Download from the [latest release](https://github.com/co-lens/ignition-modules/releases/latest).
**Name the tag** — the two platform lines publish separately, so an unnamed download gets you
whichever went out last, and the gateway refuses the wrong one.[^tag]

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

```bash
gh release download mcp-v0.4.1 --repo co-lens/ignition-modules --pattern 'Ignition-MCP-0.4.1.modl*'
sha256sum -c Ignition-MCP-0.4.1.modl.sha256
```

</TabItem>
<TabItem value="81" label="Ignition 8.1">

```bash
gh release download mcp81-v0.3.1 --repo co-lens/ignition-modules --pattern 'Ignition-MCP-81-0.3.1.modl*'
sha256sum -c Ignition-MCP-81-0.3.1.modl.sha256
```

The 8.1 line also **requires Perspective** on the gateway — see
[version differences](./versions.md).

</TabItem>
</Tabs>

Substitute the current version from the releases page. Released builds are signed, so the gateway
remembers the certificate after the first install; each release's notes carry the certificate's
SHA-256 fingerprint to compare against what the gateway shows you.

## 2. Install it on the gateway

Upload the `.modl` from **Config → Modules** and accept the certificate when prompted. Or drop it
into the module folder and restart:

```bash
cp Ignition-MCP*.modl /usr/local/bin/ignition/user-lib/modules/
```

:::tip Running in Docker?
[Docker Compose](./docker.md) has ready-to-run files and the one environment variable that stops a
container hanging at commissioning.
:::

Confirm it came up — this endpoint needs no credential:

```bash
curl -s http://<gateway>:8088/data/mcp/health
```

```json
{"status":"ok","server":"ignition-mcp","version":"0.4.1","mcpEndpoint":"/data/mcp/mcp",
 "tools":35,"readOnlyTools":27,"anonymousRead":false,"devMode":false, ...}
```

Anything other than `"status":"ok"` — including a 404 — is covered in
[Troubleshooting](./troubleshooting.md).

## 3. Connect your client

Both endpoints normally require a credential. On a **local gateway you can afford to throw away**,
skip issuing one: start it with `-Dmcp.devMode=true` and they answer with no header at all.

```bash
claude mcp add --transport http ignition \
  http://<gateway>:8088/data/mcp/mcp-readonly
```

Verify by hand before trusting it:

```bash
curl -s -X POST http://<gateway>:8088/data/mcp/mcp-readonly \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools[].name'
```

Anywhere else, issue a credential and add one header to both commands above:
`X-Ignition-API-Token: <keyId>:<secret>` on 8.3, or `Authorization: Bearer <readSecret>` on 8.1.
See [Issue a credential](./credentials.md).

A list of tool names means you're done. Try asking your client *"what tag providers does this
gateway have?"* — it will call `list_tag_providers` on its own.

:::warning For any gateway that matters
Dev mode leaves the write endpoint — and `run_script`, which is gateway root — open to anyone who
can reach the port. Issue a real credential instead: [Issue a credential](./credentials.md), and
[Endpoints and security](./endpoints.md) for what each endpoint exposes.
:::

**Next:** [what you can ask for](./using.md).

## Optional: connect a Designer

The gateway sees saved project state; a Designer additionally exposes *unsaved* edits and can build
Perspective views. Install the same module — it carries both scopes — open a project, then use
**Tools → MCP Connection Info…** for a ready-to-paste command.

See [the Designer bridge](./endpoints.md#designer), [what it stages rather than
commits](./designer-save.md), and [Reaching a Designer on another
machine](./clients/remote-designer.md).

[^tag]: A pattern doesn't save you either: `'Ignition-MCP-[0-9]*'` matches
    `Ignition-MCP-81-<version>.modl`, because the `8` of `-81-` satisfies `[0-9]`. A tag belongs to
    exactly one line, so naming it settles both.
