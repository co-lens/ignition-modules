# Ignition MCP

A [Model Context Protocol](https://modelcontextprotocol.io) server that runs **inside** Ignition,
as a Kotlin module. It gives an AI client structured access to a gateway (tags, project
resources, SQL, tag history, alarms, logs) and, optionally, to a running Designer.

Requires **Ignition 8.3**.

## Why it's small

The whole module is about 2,500 lines of Kotlin across 17 files, because three things fell out
of the design rather than being built:

- **No MCP SDK, no SSE, no sessions.** The MCP spec lets a server answer every request with a
  single JSON object, and sessions are optional (removed outright in the 2026-07-28 revision).
  So the transport is a stateless POST endpoint: one route handler plus a JSON-RPC dispatcher
  handling five methods. `GET`/`DELETE` return `405`, which is exactly what a modern-revision
  server is told to answer.
- **No authentication code.** Routes are mounted with `ApiTokenManager`'s access-control
  strategies, so Ignition validates the `X-Ignition-API-Token` header and the token's
  permissions before our handler runs.
- **No JSON dependency.** Ignition ships Gson relocated to
  `com.inductiveautomation.ignition.common.gson`, available in every scope. The only jar this
  module bundles is the Kotlin stdlib.

## Layout

```
:common    GD   MCP protocol + tool registry. Pure Kotlin, unit-tested without Ignition.
:gateway   G    Mounts the MCP endpoints under /data/mcp/. Gateway tools.
:designer  D    Loopback HTTP endpoint + discovery file. Designer tools.
```

## Gateway endpoints

| Endpoint | Auth | Tools |
|---|---|---|
| `POST /data/mcp/mcp` | API token with gateway **write** permission | all 16 |
| `POST /data/mcp/mcp-readonly` | any valid API token | the 14 read-only ones |
| `GET /data/mcp/health` | none | status/version |

Write gating is structural: the read-only endpoint is backed by a registry that doesn't contain
the mutating tools, so a read-scoped token can't list *or* call them.

### Tools

**Tags** — `list_tag_providers`, `browse_tags`, `read_tags`, `get_tag_config`, **`write_tags`**

**Projects** — `list_projects`, `list_project_resources`, `read_project_resource`

**Data** — `list_datasources`, `run_query` (SELECT/WITH only), `query_tag_history`

**System** — `gateway_info`, `list_modules`, `query_logs`, `list_active_alarms`, **`run_script`**

Bold tools are write-scoped and annotated `destructiveHint`. `run_script` executes arbitrary
Jython in gateway scope — anyone with a write token effectively has gateway root, so scope your
tokens accordingly, or don't issue write tokens at all.

## Designer endpoint

When a Designer with this module opens a project, it starts a loopback-only HTTP endpoint on an
OS-assigned port and writes `~/.ignition/mcp/designer-<pid>.json` (mode 0600) containing the
port and a per-session bearer secret. **Tools → MCP Connection Info…** shows the ready-to-paste
connect command.

Tools: `designer_info`, `list_resources`, `read_resource`, `list_pending_changes`,
**`write_resource`**, **`delete_resource`**.

The Designer's value over the gateway is that writes are **staged, not committed** — they show
up as unsaved Designer changes for a human to review and save. Nothing here writes to the
gateway on its own.

## Build

```bash
./gradlew build          # runs :common protocol tests, produces build/Ignition-MCP.unsigned.modl
./gradlew :common:test   # protocol tests alone; no Ignition needed
```

Signing is skipped unless you set `module.keystorePath` and friends — see
`gradle.properties.template`. Put real values in `~/.gradle/gradle.properties`; the repo's
`gradle.properties` is gitignored so a keystore password can't be committed by accident.

> **Note:** `./gradlew deployModl` is broken on Gradle 9 — the plugin's `Deploy` task fails
> configuration validation (`property 'targetUrl' ... is in 'java.*'`). The dev container below
> mounts the build output directly instead, which needs no deploy step at all.

## Dev gateway

```bash
docker compose -f docker/docker-compose.yml up -d
```

This mounts `build/Ignition-MCP.unsigned.modl` straight into the gateway's module folder, so
after a rebuild you only need `docker compose -f docker/docker-compose.yml restart`.

Ignition 8.3 requires explicit operator acceptance of unsigned modules, so a fresh container
stops at `COMMISSIONING` with `Resources needing commissioning: modules`. Accept ours:

```bash
curl -s "http://localhost:18088/get-step?step=modules"        # shows what's pending

curl -s -X POST -H 'Content-Type: application/json' \
  -d '{"id":"modules","step":"modules","data":{"acceptedLicenses":[],"acceptedCertificates":["io.colens.mcp-ign"]}}' \
  http://localhost:18088/post-step

docker compose -f docker/docker-compose.yml restart
```

Then `curl -s http://localhost:18088/data/mcp/health` should return `{"status":"ok",...}`.

## Connecting a client

Create an API token in the gateway UI (**Config → Security → API Tokens**). A default token —
security level `Authenticated`, no extra permissions — is all the read-only endpoint needs.

> **Gotcha:** new tokens default to **Require Secure Channel**, which makes them fail with `401`
> over plain HTTP no matter what else is right. Either use HTTPS, or untick that box for a local
> dev gateway.

To reach `/data/mcp/mcp` (the mutating tools) the token additionally needs to satisfy the
gateway's **write** permission — by default that means the `Administrator` role, set under
**Config → Security → Security Levels** on the token.

```bash
claude mcp add --transport http ignition \
  http://localhost:18088/data/mcp/mcp-readonly \
  --header "X-Ignition-API-Token: <keyId>:<secret>"
```

Check it by hand first:

```bash
curl -s -X POST http://localhost:18088/data/mcp/mcp-readonly \
  -H 'X-Ignition-API-Token: <keyId>:<secret>' \
  -H 'Content-Type: application/json' \
  -H 'Accept: application/json, text/event-stream' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' | jq '.result.tools[].name'
```

An ordinary token must get **403** on `/data/mcp/mcp` while getting **200** on
`/data/mcp/mcp-readonly` — that pair of checks validates the whole auth story. And because the
read-only endpoint is backed by a registry without the mutating tools, calling `write_tags`
through it fails with `Unknown tool`, not a permission error.

For the Designer, use the command from **Tools → MCP Connection Info…**, which carries the live
port and secret:

```bash
claude mcp add --transport http ignition-designer \
  http://127.0.0.1:<port>/mcp \
  --header "Authorization: Bearer <secret>"
```

### MCP Inspector

Catches spec violations that hand-written curl won't:

```bash
npx @modelcontextprotocol/inspector
# Streamable HTTP → http://localhost:18088/data/mcp/mcp
# header X-Ignition-API-Token: <keyId>:<secret>
```

The Inspector runs on `localhost`, which the server's `Origin` check allows. To permit a
non-loopback browser origin, start the gateway with
`-Dmcp.allowedOrigins=https://tools.example.com`.

## Adding a tool

One constructor call, a hand-written schema, no annotations or codegen:

```kotlin
Tool(
    name = "list_tag_providers",
    title = "List tag providers",
    description = "Lists the tag providers configured on this gateway.",
    inputSchema = schema { string("filter", "Substring to match") },
    readOnly = true,          // false puts it behind the write-scoped endpoint only
    handler = { args ->
        jsonObject { put("providers", jsonArrayOfStrings(context.tagManager.tagProviderNames)) }
    },
)
```

Throwing from a handler is fine and often right: `McpServer` turns it into an `isError` tool
result rather than a protocol error, so the model sees the message and can correct its call.
