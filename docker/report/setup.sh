#!/usr/bin/env bash
# Configures the throwaway dev gateway: a queryable historian, and optionally some known data.
#
# Needed because nothing in this container is persisted, so `docker compose up -d` (as opposed to
# `restart`) starts from nothing. Scripted rather than written up as prose steps because it has now
# been done by hand three times, and the JSON has two traps in it that cost an hour each.
#
#   ./setup.sh                     configure the historian
#   ./setup.sh --seed <api-token>  also store six known points, for verifying query results
set -euo pipefail

CONTAINER=report-dev-gw
GATEWAY=http://localhost:18288
HISTORIAN=Plant

echo "==> Creating the '$HISTORIAN' historian"

# CoreHistorian, NOT InternalHistorian. They are different storage engines and only one is any use
# here: InternalHistorian is SQLite-backed and its query engine has no aggregation executor at all,
# so it reports zero native aggregates and fails every aggregated query. CoreHistorian is QuestDB.
#
# And "settings" must be PRESENT and POPULATED. An empty object fails with a single
# "Error deserializing resource" log line and no historian; omitting the key entirely lets the
# historian register by name and then fail to start, so /api/historians cheerfully lists something
# that cannot answer a query.
docker exec "$CONTAINER" sh -c "
set -e
D=/usr/local/bin/ignition/data/config/resources/core/com.inductiveautomation.historian/historian-provider/$HISTORIAN
mkdir -p \"\$D\"
cat > \"\$D/config.json\" <<'JSON'
{
  \"profile\": { \"type\": \"CoreHistorian\" },
  \"settings\": {
    \"partitionInterval\": \"DAY\",
    \"dataDeduplication\": false,
    \"maintenanceSettings\": {
      \"strategy\": \"NONE\",
      \"directory\": \"\",
      \"maintenanceAgeUnits\": \"YEAR\",
      \"maintenanceAge\": 1
    }
  }
}
JSON
cat > \"\$D/resource.json\" <<'JSON'
{
  \"scope\": \"G\",
  \"version\": 1,
  \"restricted\": false,
  \"overridable\": false,
  \"files\": [\"config.json\"],
  \"attributes\": { \"uuid\": \"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\" }
}
JSON
"

echo "==> Restarting to pick up the config"
# restart, NEVER up -d: up recreates the container and takes the data directory with it, which is
# where the historian config and every stored point live.
docker restart "$CONTAINER" >/dev/null
until curl -sf "$GATEWAY/StatusPing" 2>/dev/null | grep -q '"state":"RUNNING"'; do sleep 5; done

echo "==> Gateway is up. /data/report/health:"
curl -s "$GATEWAY/data/report/health" \
  | python3 -c 'import sys,json;d=json.load(sys.stdin);print("   ",{k:d.get(k) for k in ("status","version","poiVersion","devMode")})'

if [ "${1:-}" = "--seed" ]; then
  TOKEN="${2:?--seed needs an API token: ./setup.sh --seed keyId:secret}"
  BASE="histprov:$HISTORIAN:/sys:$CONTAINER:/prov:default:/tag"

  echo "==> Storing six known points (10,20,30,40,50,60 at one-minute spacing)"
  # Known values on purpose: a report is only verified when its numbers can be checked against an
  # answer worked out by hand. Over two five-minute windows these give
  # avg 30/60, min 10/60, max 50/60, count 5/1, sum 150/60.
  START=$(python3 -c "import time;print(int(time.time()//600*600)-1200)")
  python3 -c "
import json
base = $START
print(json.dumps({
    'historian': '$HISTORIAN',
    'points': [
        {'path': '$BASE:Line2/Test', 'timestamp': (base + i * 60) * 1000, 'value': 10 * (i + 1)}
        for i in range(6)
    ],
}))" > /tmp/report-seed.json

  curl -s -X POST "$GATEWAY/data/report/api/storeDataPoints" \
    -H 'Content-Type: application/json' \
    -H "X-Ignition-API-Token: $TOKEN" \
    -d @/tmp/report-seed.json
  echo
fi

echo "==> Done."
