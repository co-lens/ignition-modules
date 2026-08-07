---
title: Quickstart
sidebar_position: 2
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Quickstart

Four steps: get the module, install it on your gateway, issue an API token, point your client at
it. Budget ten minutes.

You need an Ignition 8.3 gateway you can restart, and an MCP client — the examples use
[Claude Code](https://claude.com/claude-code), but any client that speaks Streamable HTTP works.

## 1. Get the module

<Tabs>
<TabItem value="release" label="Download a release" default>

Download the signed `.modl` from the
[latest release](https://github.com/co-lens/ignition-modules/releases/latest). Releases are tagged
`mcp-v<version>` and the asset is `Ignition-MCP-<version>.modl`, with a `.sha256` beside it.

```bash
gh release download --repo co-lens/ignition-modules --pattern 'Ignition-MCP-*'
sha256sum -c Ignition-MCP-*.modl.sha256
```

Released builds are signed, so the gateway remembers the certificate and reaches RUNNING
unattended after the first install. Each release's notes carry the certificate's SHA-256
fingerprint — compare it against what the gateway shows you when it asks you to accept.

</TabItem>
<TabItem value="source" label="Build from source">

```bash
git clone https://github.com/co-lens/ignition-modules.git
cd ignition-modules
./gradlew :modules:mcp:build
```

That produces `modules/mcp/build/Ignition-MCP.unsigned.modl`. **Sign it if you can** — an unsigned
module has no certificate fingerprint for the gateway to remember, so 8.3 re-prompts for
commissioning on every restart and never reaches RUNNING unattended.
[Building](./contributing/building.md) has a three-command self-signed recipe; signing turns the
output into `modules/mcp/build/Ignition-MCP.modl`.

</TabItem>
</Tabs>

## 2. Install it on the gateway

Upload the `.modl` from the gateway's **Config → Modules** page and accept the certificate when
prompted. Or drop the file straight into the module folder and restart:

```bash
cp Ignition-MCP-<version>.modl /usr/local/bin/ignition/user-lib/modules/
```

If you built an **unsigned** module yourself, the gateway must be started with
`-Dignition.allowunsignedmodules=true` or it will refuse to load it. Released builds are signed and
need no such flag.

Confirm it came up — this endpoint needs no auth:

```bash
curl -s http://<gateway>:8088/data/mcp/health
# {"status":"ok","server":"ignition-mcp","version":"...","mcpEndpoint":"/data/mcp/mcp",...}
```

`"status":"starting"` means the module loaded but the hook hasn't finished; a 404 means it didn't
load at all — check the gateway log for `mcp.Gateway`.

## 3. Create an API token

**Config → Security → API Tokens**. A default token (security level `Authenticated`, no extra
permissions) is all the read-only endpoint needs. The token is `<keyId>:<secret>`.

:::warning The gotcha that costs everyone an hour
New tokens default to **Require Secure Channel**, which makes them fail with `401` over plain HTTP
no matter what else is correct. Use HTTPS, or untick that box for a local gateway.
:::

Start read-only. The write endpoint additionally requires the gateway's **write** permission, which
by default means the `Administrator` role — and a write token can call `run_script`, i.e. arbitrary
Jython in gateway scope. That is gateway root. Issue one deliberately or not at all.

## 4. Connect your client

```bash
claude mcp add --transport http ignition \
  http://<gateway>:8088/data/mcp/mcp-readonly \
  --header "X-Ignition-API-Token: <keyId>:<secret>"
```

Verify by hand before trusting it:

```bash
curl -s -X POST http://<gateway>:8088/data/mcp/mcp-readonly \
  -H 'X-Ignition-API-Token: <keyId>:<secret>' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools[].name'
```

A list of tool names means you're done. Ask your client something like *"what tag providers does
this gateway have?"* and it will call `list_tag_providers` on its own.

For write access, swap the URL to `/data/mcp/mcp` and use a token carrying the write permission. An
ordinary token should get **403** there while still getting **200** on `/mcp-readonly` — that pair
of checks validates the whole auth story in one go.

## Optional: connect a Designer

The gateway sees saved project state. A Designer additionally exposes *unsaved* edits, and its
write tools **stage** changes for a human to review rather than committing them. Install the same
module (it carries both scopes), open a project, then use **Tools → MCP Connection Info…** for a
ready-to-paste command.

See [the Designer bridge](./endpoints.md#designer), and
[Reaching a Designer on another machine](./clients/remote-designer.md) if the Designer isn't on the
same host as your client.

## Where to go next

[Endpoints](./endpoints.md) for the tool inventory · [Perspective](./perspective/index.md) for view
editing and diagnostics · [Dev gateway](./contributing/dev-gateway.md) for a throwaway Docker
gateway · [Adding a tool](./contributing/adding-a-tool.md) to extend it.
