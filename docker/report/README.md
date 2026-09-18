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
trial. When queries start failing with a licensing error, `docker compose restart` resets the
clock — the trial, not the data, is what expired.

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
- Historian Core is a licensed module, so this gateway runs it on a two-hour trial. `docker compose
  restart` resets the clock.

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
