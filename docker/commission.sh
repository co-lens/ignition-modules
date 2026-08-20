#!/usr/bin/env bash
# Check whether the dev gateway is commissioned, and say exactly what to do when it isn't.
#
# Ignition 8.3 stops at COMMISSIONING with "Resources needing commissioning: modules" until
# something accepts the certificate of any module it doesn't already trust — which includes an
# unsigned dev build.
#
# The reliable way to do that is the ACCEPT_MODULE_CERTS environment variable, which both compose
# files in this repo now set. It is applied at first boot, so a container that is ALREADY stuck in
# COMMISSIONING cannot be fixed by editing the env — it needs recreating. That is safe precisely
# because a gateway stuck here never finished starting, so it has no projects, tags or API tokens
# to lose.
#
# This script used to drive POST /post-step itself. That does not work on 8.3.7: every payload
# shape returns 400 with an empty body, the gateway stays in COMMISSIONING, and the script then
# waited ~7.5 minutes for a state that never arrived — which read as a hang. The servlet
# (CommissioningServlet) is a SystemJS single-page app's private API and is not worth reproducing.
# The attempt is still made below in case a different 8.3 build accepts it, but a failure is now
# reported in one second instead of hidden behind a long wait.
#
# Usage: docker/commission.sh [container-name] [gateway-url]
set -euo pipefail

CONTAINER="${1:-docker-ignition-gateway-1}"
GATEWAY="${2:-http://localhost:18088}"
MODULES_JSON="/usr/local/bin/ignition/data/modules.json"

state() { curl -s --max-time 5 "$GATEWAY/StatusPing" 2>/dev/null; }

echo "==> Waiting for the gateway to answer (up to 3 minutes)"
# `if`, not `[ ... ] && break`: a false `&&` list as the last statement in a loop body returns
# non-zero, which `set -e` treats as a fatal error — so a gateway that never answers would kill
# the script here instead of reaching the diagnosis below.
for _ in $(seq 1 36); do
  if [ -n "$(state)" ]; then break; fi
  sleep 5
done

# `|| true`: state() ends in curl, which exits non-zero when nothing is listening, and `set -e`
# aborts on a failing command substitution in an assignment — which would kill the script here
# instead of letting it report what is wrong.
st="$(state)" || true
if [ -z "$st" ]; then
  echo "!!! $GATEWAY never answered. Is '$CONTAINER' running?" >&2
  exit 1
fi

case "$st" in
  *COMMISSIONING*) ;;
  *)
    echo "==> Nothing pending commissioning ($st)"
    echo "==> Health:"
    curl -s --max-time 10 "$GATEWAY/data/mcp/health"; echo
    exit 0
    ;;
esac

echo "==> Stuck in COMMISSIONING; trying POST /post-step"
# Every module id, not just ours: /post-step treats acceptedCertificates as the COMPLETE accepted
# set, so naming only our module would mark every OTHER module disabled-on-startup and quarantine
# all ~30 stock modules, Perspective included.
ids=$(docker exec "$CONTAINER" cat "$MODULES_JSON" \
      | grep -oE '^  "[^"]+"' | tr -d ' "' | paste -sd, - \
      | sed 's/[^,]*/"&"/g')
code=$(curl -s --max-time 20 -o /dev/null -w '%{http_code}' \
  -X POST -H 'Content-Type: application/json' \
  -d "{\"id\":\"modules\",\"step\":\"modules\",\"data\":{\"acceptedLicenses\":[],\"acceptedCertificates\":[$ids]}}" \
  "$GATEWAY/post-step" || echo 000)
echo "    HTTP $code"

if [ "$code" = "200" ] || [ "$code" = "204" ]; then
  echo "==> Accepted; restarting"
  docker restart "$CONTAINER" >/dev/null
  for _ in $(seq 1 36); do
    if [ "$(state)" = '{"state":"RUNNING"}' ]; then break; fi
    sleep 5
  done
fi

if [ "$(state)" = '{"state":"RUNNING"}' ]; then
  quarantined=$(docker logs --since 3m "$CONTAINER" 2>&1 | grep -c "to quarantine" || true)
  echo "==> Modules quarantined: $quarantined (expect 0)"
  echo "==> Health:"
  curl -s --max-time 10 "$GATEWAY/data/mcp/health"; echo
  exit 0
fi

cat >&2 <<MSG

!!! Still in COMMISSIONING, and /post-step did not take (HTTP $code).
!!! This is expected on 8.3.7 — that endpoint rejects every payload shape with a 400.

    Fix it with the environment variable instead. In the compose file for this gateway:

        environment:
          ACCEPT_MODULE_CERTS: io.colens.mcp-ign

    then recreate the container so the variable is applied at boot:

        docker compose -f <compose-file> down
        docker compose -f <compose-file> up -d

    Recreating is safe here: a gateway stuck in COMMISSIONING never finished starting, so there
    are no projects, tags or API tokens to lose. Do NOT do this to a gateway that reached RUNNING
    unless you mean to discard its data — none of these compose files use a data volume.

    Both compose files in this repo already set it, so you should only hit this on a container
    created before that change.
MSG
exit 1
