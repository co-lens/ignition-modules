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

With it set, a fresh container goes straight to `RUNNING` — no commissioning step to click
through and nothing in quarantine. It is read at **boot only**, so a container already sitting in
`COMMISSIONING` has to be recreated (`down` then `up -d`) for it to take effect; that is safe,
because a gateway that never finished starting has nothing in it to lose.

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

:::note This section is for a gateway you intend to keep
The compose files **in this repository** deliberately have no data volume — they are throwaway dev
gateways, and `docker/docker-compose.yml` says so in a comment. If you are only running the repo's
own gateway, you can skip to [Rebuilding a local module](#rebuilding-a-local-module).
:::

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

## The gateways in this repository

Three compose files, all throwaway, all without a data volume — `restart` to pick up a rebuilt
module, never `up -d` on a running one:

| File | Gateway | Ports | Module | Notes |
| --- | --- | --- | --- | --- |
| `docker/docker-compose.yml` | rolling `8.3` | 18088, 18000 | signed | the day-to-day dev gateway |
| `docker/testing/docker-compose.8.3.7.yml` | pinned 8.3.7 | 18300, 18301 | unsigned | the declared floor, for release testing |
| `docker/testing/docker-compose.8.1.43.yml` | pinned 8.1.43 | 18400, 18401 | unsigned | the 8.1 line, built from a separate worktree |

The two 8.3 files set `ACCEPT_MODULE_CERTS` and `-Dmcp.devMode=true`, so they come up with no
commissioning click and no credential to issue. `docker/commission.sh` reports what state a gateway
is in when one will not start.

## Rebuilding a local module

If you're mounting a module you're building yourself rather than a release, the mount is live — a
rebuild only needs the gateway restarted, not recreated:

```bash
./gradlew :modules:mcp:build
docker compose restart
```

Signing a local build is worth doing, but it is not what keeps the gateway from re-prompting:
`ACCEPT_MODULE_CERTS` accepts an **unsigned** module perfectly well. Measured on 8.3.7 — the pinned
test gateway mounts `Ignition-MCP.unsigned.modl` with that variable set and reaches `RUNNING`
unattended from a cold start and across repeated restarts.

[Building](./contributing/building.md) covers signing; [Dev
gateway](./contributing/dev-gateway.md) covers the repo's own compose file, and
[Docker operations](./docker-operations.md) covers tokens and fleets.
