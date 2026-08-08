---
title: Docker Compose
sidebar_position: 5
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Docker Compose

Running the gateway in a container changes two things about installing this module: how its
certificate gets accepted, and what survives the container being recreated. Get both right and
compose is the least fussy way to run it — the examples below need no manual commissioning step and
no re-issued credential after a restart.

## One gateway

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

```yaml title="docker-compose.yml"
services:
  ignition:
    image: inductiveautomation/ignition:8.3
    restart: unless-stopped
    ports:
      - 8088:8088
      - 8043:8043
    environment:
      ACCEPT_IGNITION_EULA: Y
      IGNITION_EDITION: standard
      GATEWAY_ADMIN_PASSWORD: ${GATEWAY_ADMIN_PASSWORD:?set it in .env}
      # Accept this module's signing certificate unattended. Without it a fresh gateway stops at
      # COMMISSIONING and waits for a human.
      ACCEPT_MODULE_CERTS: io.colens.mcp-ign
    volumes:
      - ./Ignition-MCP.modl:/usr/local/bin/ignition/user-lib/modules/Ignition-MCP.modl:ro
      # Everything worth keeping lives under data/ — see below.
      - ignition-data:/usr/local/bin/ignition/data

volumes:
  ignition-data:
```

```bash
echo "GATEWAY_ADMIN_PASSWORD=$(openssl rand -hex 16)" > .env
docker compose up -d
curl -s http://localhost:8088/data/mcp/health
```

</TabItem>
<TabItem value="81" label="Ignition 8.1">

```yaml title="docker-compose.yml"
services:
  ignition:
    image: inductiveautomation/ignition:8.1
    restart: unless-stopped
    ports:
      - 8088:8088
      - 8043:8043
    environment:
      ACCEPT_IGNITION_EULA: Y
      IGNITION_EDITION: standard
      GATEWAY_ADMIN_PASSWORD: ${GATEWAY_ADMIN_PASSWORD:?set it in .env}
    volumes:
      - ./Ignition-MCP-81.modl:/usr/local/bin/ignition/user-lib/modules/Ignition-MCP-81.modl:ro
      - ignition-data:/usr/local/bin/ignition/data
    # Trailing arguments become JVM arguments — the compose equivalent of a
    # `wrapper.java.additional.N=` line in ignition.conf. This is how 8.1 gets its credential.
    command: >
      --
      -Dmcp.gateway.readSecret=${MCP_READ_SECRET:?set it in .env}

volumes:
  ignition-data:
```

```bash
{ echo "GATEWAY_ADMIN_PASSWORD=$(openssl rand -hex 16)"
  echo "MCP_READ_SECRET=$(openssl rand -hex 24)"; } > .env
docker compose up -d
curl -s http://localhost:8088/data/mcp/health
```

:::danger Don't add `writeSecret` unless you mean it
The write secret grants `run_script` — arbitrary Jython in gateway scope. See
[version differences](./versions.md#why-81-authentication-is-weaker).
:::

</TabItem>
</Tabs>

## `ACCEPT_MODULE_CERTS` is what stops it hanging at commissioning

**Ignition 8.3 only.** A fresh gateway halts at `COMMISSIONING` with
`Resources needing commissioning: modules` until an operator accepts the signing certificate of any
module it doesn't already trust. In a container there is no operator, so it waits forever.

`ACCEPT_MODULE_CERTS` is a **comma-separated list of module ids**, matched exactly — no wildcards,
no "accept everything" value:

```yaml
ACCEPT_MODULE_CERTS: io.colens.mcp-ign,com.example.other-module
```

The gateway consults it first when deciding whether a certificate is accepted, and logs
`module io.colens.mcp-ign auto-accepted via ACCEPT_MODULE_CERTS env variable` at debug level under
the `ModuleUtil` logger. A sibling variable, `ACCEPT_MODULE_LICENSES`, takes the same format for
modules that ship a license file; this module doesn't, so you won't need it.

With it set, a fresh container goes straight to `RUNNING` — no commissioning step, no
[`commission.sh`](./contributing/dev-gateway.md#commissioning), and nothing in quarantine.

This accepts a certificate you are choosing to trust — it does not disable signature verification.
Each release's notes carry the certificate's SHA-256 fingerprint; compare it once before pinning
the module id into a compose file.

:::warning Never set `GATEWAY_MODULES_ENABLED`
Commissioning reads it as the **complete** list of modules to enable, so naming only this module
disables every stock module — Perspective included — and quarantines them all on the next boot.
:::

:::note Not present on 8.1
8.1 has no `ACCEPT_MODULE_CERTS` — the variable simply doesn't exist in that platform line, so
setting it does nothing. It also isn't needed for a dev build there: on 8.1 it's *signed* modules
that get quarantined and unsigned ones that load directly. See
[version differences](./versions.md).
:::

## The data volume is what survives a restart

Everything you'd hate to lose lives under `/usr/local/bin/ignition/data`: API tokens, projects, the
admin user, and the record that the gateway was commissioned. The official image seeds that
directory into an empty volume on first boot, so a named volume is all it takes.

Without one, the lifetime of your gateway's configuration is the lifetime of the container:

| Command | Configuration survives? |
| --- | --- |
| `docker compose restart` | Yes — same container, nothing is recreated |
| `docker compose up -d` after a compose edit | **Only with the data volume** |
| `docker compose down`, `rm`, or an image bump | **Only with the data volume** |
| `docker compose down -v` | No — that deletes the volumes too |

The trap is that `up -d` looks harmless. It recreates the container whenever anything in the
service definition changes, and takes the whole data directory with it.

## Where the API token actually lives

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

An 8.3 API token is an on-disk config resource, named for its key id:

```
data/config/resources/core/ignition/api-token/<keyId>/config.json
data/config/resources/core/ignition/api-token/<keyId>/resource.json
```

`config.json` holds the token's profile — `secureChannelRequired`, its security levels — and a
`settings.tokenHash`. That is a **hash, not the secret**. The plaintext `<keyId>:<secret>` exists
exactly once, on screen at creation time, and nothing on disk can hand it back.

So a token cannot be seeded from an environment variable or a hand-written config file. Issue it in
the gateway UI once; the data volume keeps it thereafter. The plaintext belongs in a `.env` on the
**client** side, where it becomes the `X-Ignition-API-Token` header.

:::tip A dev-only way to skip this entirely
`-Dmcp.gateway.allowAnonymousRead=true` as a trailing JVM argument serves the read-only endpoint
with no token at all — convenient for a disposable compose stack, and a data leak on anything else.
See [Endpoints and security](./endpoints.md#opting-out-of-the-read-credential).
:::

### Sharing one token across gateways

A token resource copied verbatim **is** honoured by a gateway that never issued it — the hash is all
the gateway checks. Copy the directory out:

```bash
docker cp ignition:/usr/local/bin/ignition/data/config/resources/core/ignition/api-token/mcp \
          ./api-token/mcp
```

```yaml
volumes:
  - ignition-data:/usr/local/bin/ignition/data
  - ./api-token/mcp:/usr/local/bin/ignition/data/config/resources/core/ignition/api-token/mcp
```

:::danger Boot the gateway once *before* adding this mount
On first boot the gateway builds its `core` resource collection and requires
`data/config/resources/core` to be empty. A pre-populated token directory makes setup fail outright
— the gateway never reaches `RUNNING` and `StatusPing` reports:

```
FAULTED — Unable to create 'core' resource collection
Caused by: java.nio.file.FileAlreadyExistsException:
  Resource collection path '.../data/config/resources/core' exists but is not empty
```

So bring each gateway up **without** the token mount, wait for `RUNNING`, then add the mount and
`docker compose up -d`. On an already-initialised gateway it works cleanly.
:::

Two more rules:

- Mount the **directory**, never `config.json` on its own — the gateway rewrites config resources by
  atomic replace, which swaps the inode and leaves a single-file bind mount pointing at the old one.
- Don't hand-edit the files: `resource.json` carries a `lastModificationSignature` over them.

Separate tokens per gateway are still the better default — you get per-gateway revocation, and one
leaked credential doesn't open all of them.

</TabItem>
<TabItem value="81" label="Ignition 8.1">

8.1 has no API tokens. The credential is a shared secret passed as a JVM argument, which means it
*can* come from a `.env` file through compose interpolation, as in the example above.

That convenience is also the weakness: the secret is visible in the process table, shared by every
client, and not revocable without a gateway restart. See
[version differences](./versions.md#why-81-authentication-is-weaker).

</TabItem>
</Tabs>

## Several gateways

The single-gateway service scales out by giving each gateway its own port pair, its own name, and —
critically — **its own data volume**. YAML anchors keep it readable:

```yaml title="docker-compose.yml"
x-modl: &modl
  ./Ignition-MCP.modl:/usr/local/bin/ignition/user-lib/modules/Ignition-MCP.modl:ro

x-gateway: &gateway
  image: inductiveautomation/ignition:8.3
  restart: unless-stopped
  environment:
    ACCEPT_IGNITION_EULA: Y
    IGNITION_EDITION: standard
    GATEWAY_ADMIN_PASSWORD: ${GATEWAY_ADMIN_PASSWORD:?set it in .env}
    ACCEPT_MODULE_CERTS: io.colens.mcp-ign

services:
  gw1:
    <<: *gateway
    command: ["-n", "gw1"]
    ports: ["18088:8088", "18043:8043"]
    volumes:
      - *modl
      - gw1-data:/usr/local/bin/ignition/data

  gw2:
    <<: *gateway
    command: ["-n", "gw2"]
    ports: ["18089:8088", "18044:8043"]
    volumes:
      - *modl
      - gw2-data:/usr/local/bin/ignition/data

  # gw3, gw4, gw5 follow the same shape

volumes:
  gw1-data:
  gw2-data:
```

:::warning A merge key does not merge lists
`<<: *gateway` merges mappings only. A service-level `volumes:` **replaces** the anchor's rather
than adding to it, which is why the module mount is its own `*modl` anchor repeated per service.
:::

Check them all at once:

```bash
for p in 18088 18089 18090 18091 18092; do
  printf '%s ' "$p"; curl -s "http://localhost:$p/data/mcp/health" | jq -r '.status // "no response"'
done
```

Each gateway needs its own API token issued in its own UI, unless you share one as described above.

:::tip Five gateways, five trials
An unlicensed gateway stops after two hours, independently on each one. For a throwaway dev fleet
the [trial watchdog](./contributing/dev-gateway.md#trial-expiry) can keep them alive; licence
anything a customer touches.
:::

## Rebuilding a local module

If you're mounting a module you're building yourself rather than a release, the mount is live — a
rebuild only needs the gateway restarted, not recreated:

```bash
./gradlew :modules:mcp:build
docker compose restart
```

Sign even your local builds. An unsigned module has no certificate fingerprint for the gateway to
remember, so `ACCEPT_MODULE_CERTS` has nothing to match and the gateway re-prompts for commissioning
on every restart. [Building](./contributing/building.md) covers signing;
[Dev gateway](./contributing/dev-gateway.md) covers the repo's own compose file.
