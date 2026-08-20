# co-lens Ignition modules

Ignition modules written in Kotlin, each carrying independent semver and released by its own tag.

**Documentation: <https://co-lens.github.io/ignition-modules/>** — installation, the full tool
reference, Perspective editing, and contributor guides. This README is a pointer and a quickstart;
the site is canonical for everything else.

Requires **Ignition 8.3**. A time-limited **Ignition 8.1** port of the MCP module lives on the
[`8.1/main`](https://github.com/co-lens/ignition-modules/tree/8.1/main) branch — same tools,
different authentication, released as `Ignition-MCP-81-*.modl` under `mcp81-v*` tags. Unsupported
after February 2027.

## Modules

| Module | Module id | Docs | Release tag |
| --- | --- | --- | --- |
| **Ignition MCP** — a [Model Context Protocol](https://modelcontextprotocol.io) server that runs *inside* Ignition, giving an AI client structured access to a gateway (tags, project resources, SQL, tag history, alarms, logs) and, optionally, to a running Designer. | `io.colens.mcp-ign` | [Docs](https://co-lens.github.io/ignition-modules/modules/mcp) | `mcp-v*` |

## Quickstart — Ignition MCP

Four steps, about ten minutes. The
[full quickstart](https://co-lens.github.io/ignition-modules/modules/mcp/quickstart) has the
gotchas and the Designer setup.

**1. Download the signed module** from the
[latest release](https://github.com/co-lens/ignition-modules/releases/latest):

```bash
gh release download --repo co-lens/ignition-modules --pattern 'Ignition-MCP-[0-9]*'
sha256sum -c Ignition-MCP-*.modl.sha256
```

**2. Install it** from the gateway's **Config → Modules** page, accepting the certificate when
prompted. Then check it came up — this endpoint needs no auth:

```bash
curl -s http://<gateway>:8088/data/mcp/health
```

**3. Create an API token** under **Config → Security → API Tokens**. A default token (security
level `Authenticated`) is all the read-only endpoint needs.

> New tokens default to **Require Secure Channel**, which makes them fail with `401` over plain
> HTTP no matter what else is right. Use HTTPS, or untick that box for a local gateway.

**4. Point your client at it:**

```bash
claude mcp add --transport http ignition \
  http://<gateway>:8088/data/mcp/mcp-readonly \
  --header "X-Ignition-API-Token: <keyId>:<secret>"
```

That gives you the read-only tools. The write endpoint (`/data/mcp/mcp`) additionally requires the
gateway's **write** permission and exposes `run_script` — arbitrary Jython in gateway scope, which
is gateway root. Issue such a token deliberately or not at all. See
[Endpoints](https://co-lens.github.io/ignition-modules/modules/mcp/endpoints) and the
[tool reference](https://co-lens.github.io/ignition-modules/modules/mcp/tools).

### Developing against a throwaway gateway

Steps 3 and 4 exist to protect a gateway that matters. If yours doesn't — a local container you can
delete and rebuild — start it with `-Dmcp.devMode=true` and skip them entirely:

```bash
claude mcp add --transport http ignition http://localhost:8088/data/mcp/mcp
```

Both endpoints then answer with no credential, which means anyone who can reach the port can run
`run_script` as gateway root. Never set it on a gateway that is reachable from a plant network. See
[Dev mode](https://co-lens.github.io/ignition-modules/modules/mcp/endpoints#dev-mode).

## Build from source

```bash
./gradlew :modules:mcp:build         # protocol tests + modules/mcp/build/Ignition-MCP.unsigned.modl
./gradlew :modules:mcp:common:test   # protocol tests alone; no Ignition needed
```

See [Building](https://co-lens.github.io/ignition-modules/modules/mcp/contributing/building) for
signing, which you want even locally — an unsigned module re-prompts for commissioning on every
gateway restart.

## Layout

```
modules/mcp/       the Ignition MCP module — id io.colens.mcp-ign
  common    GD   MCP protocol + tool registry. Pure Kotlin, unit-tested without Ignition.
  gateway   G    Mounts the MCP endpoints under /data/mcp/. Gateway tools.
  designer  D    Loopback HTTP endpoint + discovery file. Designer tools.
tools/tool-docs/   generates the tool reference on the docs site
docs/              the docs site
docker/            throwaway dev gateway
```

More on the shape and the reasoning:
[Repo layout](https://co-lens.github.io/ignition-modules/contributing/repo-layout).

## Contributing

- [Building](https://co-lens.github.io/ignition-modules/modules/mcp/contributing/building)
- [Dev gateway](https://co-lens.github.io/ignition-modules/modules/mcp/contributing/dev-gateway)
- [Adding a tool](https://co-lens.github.io/ignition-modules/modules/mcp/contributing/adding-a-tool)
- [Releasing](https://co-lens.github.io/ignition-modules/contributing/releasing)

The tool reference on the docs site is generated from the module's own `Tool` declarations, so a
new tool documents itself — run `./gradlew :tools:tool-docs:generateToolDocs` and commit
`docs/src/data/tools.json`. CI fails on a stale one.

## Licence

MIT. See [LICENSE](LICENSE).
