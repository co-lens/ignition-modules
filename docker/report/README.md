# Report module dev gateway

```bash
./gradlew :modules:report:build
cd docker/report && docker compose up -d      # FIRST time only
```

Gateway at <http://localhost:18288>, admin / password.

After a rebuild:

```bash
./gradlew :modules:report:build && docker compose restart
```

**`restart`, never `up -d` after the first run.** `up -d` recreates the container and takes the
gateway's whole data directory with it — historian data, tag config, API tokens — because nothing
here is persisted. That is deliberate for a throwaway, and it is also why rebuilding with the
wrong command silently loses the test data you just set up.

`-Dreport.devMode=true` is set, so every `/data/report/api` route answers without an API token.
The gateway logs a WARN saying so at startup. Never set it anywhere real.

The Historian module is licensed (`freeModule=false`), so a fresh container runs it on a two-hour
demo. See the licensing note at the end — a restart does NOT reset it.

## Configuring a historian

A fresh gateway has **no historian**, so `/data/report/api/historians` returns `[]` and every
query fails. There is no way to create one from the command line, so write the config resource
directly — 8.3 keeps gateway config as a file tree under `data/config/resources`.

**Use `CoreHistorian`, not `InternalHistorian`.** They are different storage engines and only one
is useful here: `InternalHistorian` is SQLite-backed and its query engine has executors for raw
points, metadata, browse and annotations — and *none* for aggregation, so it reports zero native
aggregates and fails every aggregated query. `CoreHistorian` is the QuestDB one.

```bash
docker exec report-dev-gw sh -c '
D=/usr/local/bin/ignition/data/config/resources/core/com.inductiveautomation.historian/historian-provider/Plant
mkdir -p "$D"
cat > "$D/config.json" <<JSON
{
  "profile": { "type": "CoreHistorian" },
  "settings": {
    "partitionInterval": "DAY",
    "dataDeduplication": false,
    "maintenanceSettings": { "strategy": "NONE", "directory": "",
                             "maintenanceAgeUnits": "YEAR", "maintenanceAge": 1 }
  }
}
JSON
cat > "$D/resource.json" <<JSON
{ "scope": "G", "version": 1, "restricted": false, "overridable": false,
  "files": ["config.json"],
  "attributes": { "uuid": "11111111-2222-3333-4444-555555555555" } }
JSON
'
docker compose restart
```

Three things here cost an hour to work out, so they are worth stating plainly:

- The resource type is `com.inductiveautomation.historian` / `historian-provider`, which is only
  discoverable by decompiling `HistorianExtensionPoint.RESOURCE_TYPE`.
- **`"settings": {}` fails** with `Error deserializing resource` — the gateway logs that and moves
  on, leaving no historian and no other clue. Omitting `settings` entirely gets further: the
  historian is then *listed by name* but fails to start, logging `Error adding historian: Internal`
  while `/api/historians` cheerfully reports it. That mismatch is why `HistorianService` reports
  "configured but not queryable" separately from "no such historian".
- Historian Core's demo expiry is covered at the end of this file.
A historian with no data answers raw queries with an empty result, and aggregated queries with
`Bad_Unsupported` for tags it has never seen. Both are correct — and both are reported as
`warnings` rather than thrown, so one unknown tag cannot blank a whole report.

## Seeding data

Tag logging needs a tag provider, tag definitions and a tag group, none of which can be created
from the shell. `storeDataPoints` writes straight into a historian instead, which is what the
scripting function is for:

```bash
curl -s -X POST http://localhost:18288/data/report/api/storeDataPoints \
  -H 'Content-Type: application/json' -d '{
    "historian": "Plant",
    "points": [
      {"path": "histprov:Plant:/sys:report-dev-gw:/prov:default:/tag:Line1/Flow",
       "timestamp": 1789735800000, "value": 10}
    ]
  }'
```

Then query it back with `queryRawPoints` or `queryAggregatedPoints`. Putting *known* values in is
the point — a report is only verified when its numbers can be checked against an answer worked out
by hand.

## Two provider limits worth knowing

`GET /api/historians` reports each historian's `nativeAggregates`, and they come back in QuestDB's
own spelling (`SIMPLE_AVERAGE`, `MAXIMUM`, `STANDARD_DEVIATION`), not `system.historian`'s. The set
is per provider: asking for an aggregate a historian lacks fails the *whole* query, so the add-in
should offer only what a given historian reports.

The Core historian cannot answer an aggregated query carrying **more than one key** — two
aggregates on one path, or one aggregate across two paths, both fail while either works alone.
`HistorianService` fans out to one query per key because of it. The raw query path has no such
limit and takes many paths happily.

## Auth, and the permission asymmetry

`devMode` is off in this compose file, so every `/data/report/api` route needs an API key. Create
one at **Platform → Security → API Keys → Create API Key**, type *Basic Token*. Two things to get
right:

- **Untick "Require secure connections for API Keys."** It is ticked by default and makes every
  key 403 over plain `http://`, which is how this gateway is reached locally.
- **Give the key a security level that satisfies `writePermissions`.** Read and write are NOT
  symmetric, and the gateway's own `security-properties` shows why:

  ```json
  "accessPermissions": { "securityLevels": [], "type": "AllOf" }
  "writePermissions":  { "securityLevels": [Authenticated/Roles/Administrator], "type": "AnyOf" }
  ```

  `accessPermissions` ships as an **empty AllOf**, which is *vacuously true* — satisfied by a
  caller presenting no token at all. That is the trap `ReportRoutes` guards against by AND-ing
  `requireValidToken()` with every `TOKEN_*` strategy, and it is measured here rather than assumed:
  `TOKEN_ACCESS` on its own protects nothing on a default gateway.

  `writePermissions` is the opposite — it requires Administrator out of the box, so a freshly
  created key reads fine and 403s on `storeDataPoints` until its security level is raised.

Verified against this container:

```
no token     -> 401
wrong token  -> 401
valid token  -> 200   (read)
valid token  -> 403   (write, until the key's security level satisfies writePermissions)
```

## Testing the add-in in Excel

Office will not load an add-in over plain HTTP, and this gateway exposes only `http://localhost:18288`.
So the add-in cannot be sideloaded against this container as it stands — it needs a browser-reachable
**HTTPS** origin, by one of:

- an HTTPS tunnel (`cloudflared tunnel --url http://localhost:18288`, or
  `ssh -R 80:localhost:18288 nokey@localhost.run`), with `-Dreport.publicUrl` set to the public
  address so the generated manifest points at it rather than at localhost;
- a local reverse proxy holding a certificate the browser trusts (mkcert + caddy);
- Ignition's own HTTPS port, which is **not** enabled or exposed in this compose file.

`/data/report/manifest.xml` deliberately refuses to emit over `http://` and says so, rather than
producing a manifest that Office would reject silently.


## When the historian stops answering

Historian Core is a licensed module, so this gateway runs it on a two-hour demo. **A container
restart does NOT reset it.** The expiry is recorded in the data directory and survives one:

```
SQL Historian access level changed: Demo_Enabled -> Demo_Expired
License does not support storage.  route-path=/api/storeDataPoints
License does not support querying. route-path=/api/browse
```

Reset it from the gateway UI: a yellow **Trial Expired** banner sits at the bottom of any page
with a reset button (log in first). Recreating the container also resets it, at the cost of the
historian config, every stored point and every API key.

What it looks like from outside is worth knowing, because it does not look like licensing:
`storeDataPoints` answers `{"stored":0,"failed":9,"quality":"Bad_TrialExpired"}`, but `browse`
returns an empty node list with no error — which reads exactly like a historian that simply has
no data in it.
