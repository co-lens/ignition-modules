#!/usr/bin/env bash
# Commission the dev gateway so our unsigned module loads alongside the stock IA modules.
#
# On the 8.1 line this is a no-op and the script exits early: 8.1 has no commissioning servlet at
# all (/get-step 404s), so the `grep -q moduleId` guard below falls through to "nothing pending".
#
# 8.1 still quarantines a module signed with an unknown certificate — it just wants an operator to
# approve it in the gateway UI rather than through this API. That is why the dev compose file
# mounts the UNSIGNED build: unsigned loads immediately under -Dignition.allowunsignedmodules=true,
# which is the opposite of the 8.3 line.
#
# Ignition 8.3 stopped at COMMISSIONING with "Resources needing commissioning: modules" until an
# operator accepts the certificate of any module it doesn't already trust — which includes an
# unsigned dev build.
#
# The trap: POST /post-step treats `acceptedCertificates` as the COMPLETE set of accepted
# modules, not an addition to it. Posting only our module id makes the gateway mark every OTHER
# module disabled-on-startup and quarantine it on the next boot — all ~30 stock modules,
# Perspective included. So we post every module id the gateway knows about, ours included.
#
# Usage: docker/commission.sh [container-name] [gateway-url]
set -euo pipefail

CONTAINER="${1:-ignition-mcp-81-ignition-gateway-1}"
GATEWAY="${2:-http://localhost:18188}"
MODULES_JSON="/usr/local/bin/ignition/data/modules.json"

echo "==> Waiting for the gateway to answer"
for _ in $(seq 1 90); do
  curl -s "$GATEWAY/StatusPing" 2>/dev/null | grep -q '"state"' && break
  sleep 5
done

if ! curl -s "$GATEWAY/get-step?step=modules" 2>/dev/null | grep -q moduleId; then
  echo "==> Nothing pending commissioning"
else
  echo "==> Accepting every module the gateway knows about"
  ids=$(docker exec "$CONTAINER" cat "$MODULES_JSON" \
        | grep -oE '^  "[^"]+"' | tr -d ' "' | paste -sd, - \
        | sed 's/[^,]*/"&"/g')
  curl -s -o /dev/null -X POST -H 'Content-Type: application/json' \
    -d "{\"id\":\"modules\",\"step\":\"modules\",\"data\":{\"acceptedLicenses\":[],\"acceptedCertificates\":[$ids]}}" \
    "$GATEWAY/post-step"
  echo "    accepted: $(echo "$ids" | tr ',' '\n' | wc -l) modules"
fi

echo "==> Restarting"
docker restart "$CONTAINER" >/dev/null
for _ in $(seq 1 90); do
  [ "$(curl -s "$GATEWAY/StatusPing" 2>/dev/null)" = '{"state":"RUNNING"}' ] && break
  sleep 5
done

quarantined=$(docker logs --since 3m "$CONTAINER" 2>&1 | grep -c "to quarantine" || true)
echo "==> Modules quarantined: $quarantined (expect 0)"
echo "==> Health:"
curl -s "$GATEWAY/data/mcp/health"; echo
