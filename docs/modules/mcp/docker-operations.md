---
title: Docker operations
sidebar_position: 9
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Docker operations

The deeper half of running this in containers: where an 8.3 API token physically lives, how to move
one between gateways, and how to run a fleet. [Docker Compose](./docker.md) covers getting one
gateway up, which is all most people need.

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
`-Dmcp.devMode=true` as a trailing JVM argument serves **both** endpoints with no token at all —
which is what makes a disposable compose stack usable without ever opening the gateway UI, and what
makes it a gateway-root hole on anything else. `-Dmcp.gateway.allowAnonymousRead=true` is the
narrower version, opening only the read-only endpoint. See
[Endpoints and security](./endpoints.md#dev-mode).
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

### Restoring a backed-up token into a fresh gateway

Copying a token *back* — into a gateway that has never issued one — needs the parent directory
created first:

```bash
docker exec ignition mkdir -p \
  /usr/local/bin/ignition/data/config/resources/core/ignition/api-token
docker cp ./api-token/mcp \
          ignition:/usr/local/bin/ignition/data/config/resources/core/ignition/api-token/mcp
```

`api-token/` doesn't exist until the gateway issues its first token, so without the `mkdir -p` the
`docker cp` has nowhere to land.

:::danger The obvious repair afterwards is unreachable by construction
A restored token isn't live until the gateway rescans its resource files — and the tool that does
that, `scan_resource_files`, is on the **write** endpoint, which is exactly the endpoint you have no
working token for. There is no way round it from the client side.

**Restart the gateway.** That is the only way in.
:::

<small>Both of these came out of a real restore run by the lens project.</small>

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

:::note Ports here start at 18188, not 18088
`docker/docker-compose.yml` in this repository already binds 18088, and `docker/testing/` binds
18300/18301 and 18400/18401. The fleet below stays clear of all three so you can run it alongside
them.
:::

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
    ports: ["18188:8088", "18143:8043"]
    volumes:
      - *modl
      - gw1-data:/usr/local/bin/ignition/data

  gw2:
    <<: *gateway
    command: ["-n", "gw2"]
    ports: ["18189:8088", "18144:8043"]
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
for p in 18188 18189 18190 18191 18192; do
  printf '%s ' "$p"; curl -s "http://localhost:$p/data/mcp/health" | jq -r '.status // "no response"'
done
```

Each gateway needs its own API token issued in its own UI, unless you share one as described above.

:::tip Five gateways, five trials
An unlicensed gateway stops after two hours, independently on each one. For a throwaway dev fleet
the [trial watchdog](./contributing/dev-gateway.md#trial-expiry) can keep them alive; licence
anything a customer touches.
:::
