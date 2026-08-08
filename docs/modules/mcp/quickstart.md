---
title: Quickstart
sidebar_position: 2
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Quickstart

Four steps, about ten minutes: get the module, install it, issue a credential, point your client
at it.

You need an Ignition gateway you can restart, and an MCP client — the examples use
[Claude Code](https://claude.com/claude-code), but any client that speaks Streamable HTTP works.

:::tip Pick your Ignition version once
The tabs below are synced across the whole site. Choose 8.3 or 8.1 here and every other page
follows. Not sure which you're on? `curl -s http://<gateway>:8088/data/mcp/health` after step 2
tells you, or see [version differences](./versions.md).
:::

## 1. Get the module

Download from the [latest release](https://github.com/co-lens/ignition-modules/releases/latest).
The asset name differs per platform line — **downloading the wrong one gets you a module the
gateway refuses to install.**

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

Releases are tagged `mcp-v<version>`; the asset is `Ignition-MCP-<version>.modl`. **Name the tag**
— substitute the version from the releases page:

```bash
gh release download mcp-v0.2.0 --repo co-lens/ignition-modules --pattern 'Ignition-MCP-0.2.0.modl*'
sha256sum -c Ignition-MCP-0.2.0.modl.sha256
```

:::note Why the tag rather than a pattern
`gh release download` with no tag takes the *latest* release, and the two platform lines publish
separately — so whichever went out last wins, regardless of which one you want. A pattern doesn't
save you either: `'Ignition-MCP-[0-9]*'` matches `Ignition-MCP-81-<version>.modl`, because the `8`
of `-81-` satisfies `[0-9]`. Naming the tag settles both, since a tag belongs to exactly one line.
:::

</TabItem>
<TabItem value="81" label="Ignition 8.1">

Releases are tagged `mcp81-v<version>`; the asset is `Ignition-MCP-81-<version>.modl`. Name the tag
here too — without one you get whichever line published most recently:

```bash
gh release download mcp81-v0.2.0 --repo co-lens/ignition-modules --pattern 'Ignition-MCP-81-0.2.0.modl*'
sha256sum -c Ignition-MCP-81-0.2.0.modl.sha256
```

The 8.1 line also **requires Perspective** to be installed on the gateway — see
[version differences](./versions.md).

</TabItem>
</Tabs>

Released builds are signed, so the gateway remembers the certificate and reaches RUNNING
unattended after the first install. Each release's notes carry the certificate's SHA-256
fingerprint — compare it against what the gateway shows when it asks you to accept.

<details>
<summary>Or build from source</summary>

```bash
git clone https://github.com/co-lens/ignition-modules.git
cd ignition-modules                 # 8.3
# git switch 8.1/main               # 8.1
./gradlew :modules:mcp:build
```

See [Building](./contributing/building.md) for signing, which you want even locally.

</details>

## 2. Install it on the gateway

Upload the `.modl` from the gateway's **Config → Modules** page and accept the certificate when
prompted. Or drop the file into the module folder and restart:

```bash
cp Ignition-MCP*.modl /usr/local/bin/ignition/user-lib/modules/
```

:::tip Running in Docker?
[Docker Compose](./docker.md) has ready-to-run files, and covers the two things containers change:
accepting the module certificate unattended, and keeping your API token across a `docker compose
up -d`.
:::

Confirm it came up — this endpoint needs no credential:

```bash
curl -s http://<gateway>:8088/data/mcp/health
```

```json
{"status":"ok","server":"ignition-mcp","version":"0.1.0","mcpEndpoint":"/data/mcp/mcp", ...}
```

`"status":"starting"` means the module loaded but hasn't finished; a 404 means it didn't load at
all. Either way, [Troubleshooting](./troubleshooting.md) has the next step.

## 3. Issue a credential

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

**Config → Security → API Tokens**. A default token (security level `Authenticated`, no extra
permissions) is all the read-only endpoint needs. The token is `<keyId>:<secret>`.

:::warning The gotcha that costs everyone an hour
New tokens default to **Require Secure Channel**, which makes them fail with `401` over plain HTTP
no matter what else is correct. Use HTTPS, or untick that box for a local gateway.
:::

For write access the token additionally needs the gateway's **write** permission, which by default
means the `Administrator` role.

</TabItem>
<TabItem value="81" label="Ignition 8.1">

8.1 has no API tokens, so the credential is a shared secret set as a JVM argument in
`ignition.conf`, then a gateway restart:

```
wrapper.java.additional.9=-Dmcp.gateway.readSecret=<32+ random characters>
```

Generate one with `openssl rand -hex 24`. Add `-Dmcp.gateway.writeSecret=...` as well only if you
need write access.

:::danger Set only readSecret unless you need writes
The write secret grants `run_script` — arbitrary Jython in gateway scope, which is gateway root.
These secrets are also visible in the process table and not revocable without a restart. See
[version differences](./versions.md).
:::

If neither is set, both endpoints reject everything with 401 and the gateway log says so.

</TabItem>
</Tabs>

Start read-only either way. A write credential effectively hands over the gateway — see
[Endpoints and security](./endpoints.md).

## 4. Connect your client

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

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

</TabItem>
<TabItem value="81" label="Ignition 8.1">

```bash
claude mcp add --transport http ignition \
  http://<gateway>:8088/data/mcp/mcp-readonly \
  --header "Authorization: Bearer <readSecret>"
```

Verify by hand before trusting it:

```bash
curl -s -X POST http://<gateway>:8088/data/mcp/mcp-readonly \
  -H 'Authorization: Bearer <readSecret>' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools[].name'
```

</TabItem>
</Tabs>

A list of tool names means you're done. Try asking your client *"what tag providers does this
gateway have?"* — it will call `list_tag_providers` on its own.

**Next:** [what you can ask for](./using.md).

## Optional: connect a Designer

The gateway sees saved project state. A Designer additionally exposes *unsaved* edits, and its
write tools **stage** changes for a human to review rather than committing them — which is what
makes Perspective view editing safe.

Worth being precise about, since the two scopes differ: staging is a property of the *Designer's*
write tools only. The gateway's write tools — `write_tags`, `configure_tags`, `delete_tags`,
`run_script` and the rest — commit immediately, with nothing to review and no undo.

Install the same module (it carries both scopes), open a project, then use
**Tools → MCP Connection Info…** for a ready-to-paste command. The Designer bridge works
identically on both platform lines: it runs its own loopback server with a per-session secret and
never touches Ignition's authentication.

See [the Designer bridge](./endpoints.md#designer), and
[Reaching a Designer on another machine](./clients/remote-designer.md) if it isn't on the same host
as your client.
