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

```bash
docker exec report-dev-gw sh -c '
D=/usr/local/bin/ignition/data/config/resources/core/com.inductiveautomation.historian/historian-provider/Internal
mkdir -p "$D"
cat > "$D/config.json" <<JSON
{
  "profile": { "type": "InternalHistorian" },
  "settings": {
    "timeLimit":  { "enabled": false, "size": 1, "sizeUnits": "YEAR" },
    "pointLimit": { "enabled": false, "size": 1000000 },
    "remoteSync": { "enabled": false, "serverName": "", "historianName": "",
                    "enableSchedule": false, "schedule": "",
                    "syncFrequencySize": 1, "syncFrequencyUnits": "MINUTE",
                    "maxBatchSize": 1000 }
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
