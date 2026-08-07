# Ignition MCP

A [Model Context Protocol](https://modelcontextprotocol.io) server that runs **inside** Ignition,
as a Kotlin module. It gives an AI client structured access to a gateway (tags, project
resources, SQL, tag history, alarms, logs) and, optionally, to a running Designer.

Requires **Ignition 8.3**.

## Getting started

Four steps: build the module, install it on your gateway, issue an API token, point your client at
it. Budget ten minutes.

You need an Ignition 8.3 gateway you can restart, and an MCP client — the examples use
[Claude Code](https://claude.com/claude-code), but any client that speaks Streamable HTTP works.

### 1. Build the module

There is no published release yet, so build it:

```bash
git clone https://github.com/co-lens/mcp-ignition.git
cd mcp-ignition
./gradlew build
```

That produces `build/Ignition-MCP.unsigned.modl`. **Sign it if you can** — an unsigned module has
no certificate fingerprint for the gateway to remember, so 8.3 re-prompts for commissioning on
every restart and never reaches RUNNING unattended. [Build](#build) has a three-command
self-signed recipe; signing turns the output into `build/Ignition-MCP.modl`.

### 2. Install it on the gateway

Upload the `.modl` from the gateway's **Config → Modules** page and accept the certificate when
prompted. Or drop the file straight into the module folder and restart:

```bash
cp build/Ignition-MCP.modl /usr/local/bin/ignition/user-lib/modules/
```

If you're installing the **unsigned** build, the gateway must be started with
`-Dignition.allowunsignedmodules=true` or it will refuse to load it.

Confirm it came up — this endpoint needs no auth:

```bash
curl -s http://<gateway>:8088/data/mcp/health
# {"status":"ok","server":"ignition-mcp","version":"...","mcpEndpoint":"/data/mcp/mcp",...}
```

`"status":"starting"` means the module loaded but the hook hasn't finished; a 404 means it didn't
load at all — check the gateway log for `mcp.Gateway`.

### 3. Create an API token

**Config → Security → API Tokens**. A default token (security level `Authenticated`, no extra
permissions) is all the read-only endpoint needs. The token is `<keyId>:<secret>`.

> **Gotcha that costs everyone an hour:** new tokens default to **Require Secure Channel**, which
> makes them fail with `401` over plain HTTP no matter what else is correct. Use HTTPS, or untick
> that box for a local gateway.

Start read-only. The write endpoint additionally requires the gateway's **write** permission,
which by default means the `Administrator` role — and a write token can call `run_script`, i.e.
arbitrary Jython in gateway scope. That is gateway root. Issue one deliberately or not at all.

### 4. Connect your client

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

For write access, swap the URL to `/data/mcp/mcp` and use a token carrying the write permission.
An ordinary token should get **403** there while still getting **200** on `/mcp-readonly` — that
pair of checks validates the whole auth story in one go.

### Optional: connect a Designer

The gateway sees saved project state. A Designer additionally exposes *unsaved* edits, and its
write tools **stage** changes for a human to review rather than committing them. Install the same
module (it carries both scopes), open a project, then use **Tools → MCP Connection Info…** for a
ready-to-paste command. See [Designer endpoint](#designer-endpoint), and
[Reaching a Designer on another machine](#reaching-a-designer-on-another-machine) if the Designer
isn't on the same host as your client.

### Where to go next

[Gateway endpoints](#gateway-endpoints) for the tool inventory · [Perspective](#perspective) for
view editing and diagnostics · [Dev gateway](#dev-gateway) for a throwaway Docker gateway ·
[Adding a tool](#adding-a-tool) to extend it.

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
| `POST /data/mcp/mcp` | API token with gateway **write** permission | all 17 |
| `POST /data/mcp/mcp-readonly` | any valid API token | the 14 read-only ones |
| `GET /data/mcp/health` | none | status/version |

Write gating is structural: the read-only endpoint is backed by a registry that doesn't contain
the mutating tools, so a read-scoped token can't list *or* call them.

### Tools

**Tags** — `list_tag_providers`, `browse_tags`, `read_tags`, `get_tag_config`, **`write_tags`**

**Projects** — `list_projects`, `list_project_resources`, `read_project_resource`

**Data** — `list_datasources`, `run_query` (SELECT/WITH only), `query_tag_history`

**System** — `gateway_info`, `list_modules`, `query_logs`, `list_active_alarms`, **`run_script`**,
**`reset_trial`**

**Perspective** (present only when Perspective is installed) — `perspective_list_views`,
`perspective_get_view`, `perspective_get_component`, `perspective_list_component_types`,
`perspective_get_component_type`, `perspective_validate_view`, plus `perspective_list_sessions`
and `perspective_diagnose_live_view` on the gateway only. See [Perspective](#perspective).

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

## Perspective

Perspective is an **optional** module dependency (`required="false"`), which in Ignition is what
grants classloader visibility of another module's classes. With Perspective installed the
`perspective_*` tools appear; without it they're simply absent from `tools/list` rather than
present-and-broken.

**Reading and diagnosing** works from either scope. `perspective_get_view` returns the component
tree with the path you pass to every other tool, which properties are bound and how many events
are attached; `perspective_get_component` returns one component in full.

**Editing is Designer-only**, and edits stage as unsaved Designer changes for a human to review
and save — the gateway gains no project-mutation surface. The edit tools are surgical
(`perspective_add_component`, `perspective_set_binding`, `perspective_set_event`, …), so a model
addresses components by path and never handles raw `view.json`. Every edit is validated before it
is staged and refused outright if the result would be invalid.

### Validation

`perspective_validate_view` checks against the live component registry and Perspective's own JSON
schemas, and specifically catches the mistakes that break hand-written views *silently*:

| Code | What it catches |
|---|---|
| `inline_binding` | a binding left in `props{}` instead of `propConfig{}` — Perspective renders it as literal data and reports nothing |
| `bidirectional_misplaced` | `bidirectional` on the binding rather than inside `binding.config`, where it is ignored |
| `script_indentation` | an event script without its leading tab; scripts are function bodies, so this is a runtime syntax error |
| `unknown_component_type` | a type that isn't in the registry |
| `invalid_prop` / `invalid_binding_config` | schema violations, via Ignition's own `JsonSchema.validate` |

Props are validated **merged over the component's defaults**, because a stored view omits every
property left at its default — validating the stored object alone reports each unwritten default
as "missing but required" and buries the real findings.

`perspective_set_event` and `perspective_set_change_script` indent scripts for you, so the most
common of those mistakes cannot be made through the tools at all.

### Live diagnostics

`perspective_diagnose_live_view` walks a view in a running session and reports every configured
property with its binding **and its current value and quality**. Perspective surfaces binding
failures as quality overlays rather than errors, so a bad quality next to its binding config is
usually the whole diagnosis. Only views a user currently has open are visible.

## Build

```bash
./gradlew build          # runs :common protocol tests, produces build/Ignition-MCP.unsigned.modl
./gradlew :common:test   # protocol tests alone; no Ignition needed
```

Signing is skipped unless you set `module.keystorePath` and friends — see
`gradle.properties.template`. Put real values in `~/.gradle/gradle.properties`; the repo's
`gradle.properties` is gitignored so a keystore password can't be committed by accident.

**Sign even for local development.** An unsigned module has no certificate fingerprint for the
gateway to remember, so 8.3 re-prompts for commissioning on *every* restart and never reaches
RUNNING unattended. A self-signed cert is enough:

```bash
mkdir -p ~/.mcp-ign-signing && cd ~/.mcp-ign-signing
keytool -genkeypair -alias mcp-ign -keyalg RSA -keysize 2048 -validity 3650 \
  -dname "CN=mcp-ign dev, OU=Dev, O=colens, C=US" \
  -keystore keystore.pfx -storetype PKCS12 -storepass changeit -keypass changeit
keytool -exportcert -alias mcp-ign -keystore keystore.pfx -storetype PKCS12 \
  -storepass changeit -rfc -file cert.pem
```

then put the five `module.*` properties in `~/.gradle/gradle.properties`.

> **Note:** `./gradlew deployModl` is broken on Gradle 9 — the plugin's `Deploy` task fails
> configuration validation (`property 'targetUrl' ... is in 'java.*'`). The dev container below
> mounts the build output directly instead, which needs no deploy step at all.

## Dev gateway

```bash
docker compose -f docker/docker-compose.yml up -d
```

This mounts `build/Ignition-MCP.unsigned.modl` straight into the gateway's module folder, so
after a rebuild you only need `docker compose -f docker/docker-compose.yml restart`.

Ignition 8.3 stops a fresh container at `COMMISSIONING` with
`Resources needing commissioning: modules` until an operator accepts our module's certificate:

```bash
docker/commission.sh
```

Two traps that script exists to avoid, both of which quarantine every stock module — Perspective
included — and are miserable to diagnose:

- **Never set `GATEWAY_MODULES_ENABLED`** to just your module. Commissioning reads it as the
  *complete* list of modules to enable and disables everything else.
- `POST /post-step` treats `acceptedCertificates` as the **complete** accepted set, not an
  addition to it, with the same effect.

Then `curl -s http://localhost:18088/data/mcp/health` should return `{"status":"ok",...}`.

### Trial expiry

An unlicensed gateway stops after two hours. `reset_trial` restarts that countdown — the same
action as the **Reset Trial** button on the gateway home page, calling the same
`LicenseManagerImpl.resetTrial()` Ignition's own web route calls, under the same rule that the
timer must have run out first. Pass `force=true` to top it up mid-session instead. It is refused
on an activated gateway, where there is no trial to reset.

For an unattended dev loop, a watchdog can do it for you:

```
-Dmcp.trialWatchdog=true                 # off by default
-Dmcp.trialWatchdog.intervalSeconds=30   # default 30, floor 5
```

It polls `demoTimeRemaining` and resets once the trial has expired. Automating a button Ignition
ships is not circumventing a licence check, but running it forever unattended turns a deliberately
time-boxed trial into an unbounded one — so it is opt-in, refuses to start on an activated gateway,
and logs at WARN both at startup and on every reset, under the logger `mcp.Gateway.Trial`. Use it
on a throwaway dev gateway; licence anything a customer touches.

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
host, port and secret:

```bash
claude mcp add --transport http ignition-designer \
  http://127.0.0.1:<port>/mcp \
  --header "Authorization: Bearer <secret>"
```

### Reaching a Designer on another machine

The bridge binds to loopback on an OS-assigned port, which assumes the MCP client runs on the
same machine as the Designer. When it doesn't — a Designer in a VM, or on a workstation you're
driving remotely — two JVM arguments on the Designer opt out of that:

```
-Dmcp.designer.bindAddress=0.0.0.0;-Dmcp.designer.port=8770
```

Both default to the safe behaviour and the module logs a warning when you widen the bind.
Loopback-only is the right default because the bearer secret in the discovery file is the *only*
credential; once the endpoint is reachable from the network, that secret is all that protects it.
Pair it with a firewall rule or a forwarded port rather than leaving it open, and don't carry
this into production.

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
