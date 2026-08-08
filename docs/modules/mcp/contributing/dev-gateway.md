---
title: Dev gateway
sidebar_position: 2
---

# Dev gateway

```bash
docker compose -f docker/docker-compose.yml up -d
```

This mounts `modules/mcp/build/Ignition-MCP.modl` — the *signed* output — straight into the
gateway's module folder, so after a rebuild you only need
`docker compose -f docker/docker-compose.yml restart`.

:::danger Use `restart`, never `up -d`
No volume persists `/usr/local/bin/ignition/data`, so anything that recreates the container — a
changed volume, an override file, a compose edit — silently destroys the gateway's projects and API
tokens along with it.
:::

## Commissioning

Ignition 8.3 stops a fresh container at `COMMISSIONING` with
`Resources needing commissioning: modules` until an operator accepts our module's certificate:

```bash
docker/commission.sh
```

Two traps that script exists to avoid, both of which quarantine every stock module — Perspective
included — and are miserable to diagnose:

- **Never set `GATEWAY_MODULES_ENABLED`** to just your module. Commissioning reads it as the
  *complete* list of modules to enable and disables everything else.
- `POST /post-step` treats `acceptedCertificates` as the **complete** accepted set, not an addition
  to it, with the same effect.

Then `curl -s http://localhost:18088/data/mcp/health` should return `{"status":"ok",...}`.

## Skipping the token on a dev gateway

Issuing an API token per client is friction you may not want on a throwaway gateway. Add to the
JVM args:

```
-Dmcp.gateway.allowAnonymousRead=true
```

The read-only endpoint then answers without a credential. The write endpoint is unaffected and
still needs a valid token with write permission.

:::danger Dev gateways only
This exposes every read-only tool — `run_query`, `read_project_resource`, `read_tags`,
`query_logs` — to anyone who can reach the web port. It logs a WARN under `mcp.Gateway` at every
startup so it can't hide in an audit. See
[Endpoints and security](../endpoints.md#opting-out-of-the-read-credential).
:::

## Trial expiry

An unlicensed gateway stops after two hours.
[`reset_trial`](../tools/gateway.md#reset_trial) restarts that countdown — the same action as the
**Reset Trial** button on the gateway home page, calling the same `LicenseManagerImpl.resetTrial()`
Ignition's own web route calls, under the same rule that the timer must have run out first. Pass
`force=true` to top it up mid-session instead. It is refused on an activated gateway, where there
is no trial to reset.

For an unattended dev loop, a watchdog can do it for you:

```
-Dmcp.trialWatchdog=true                 # off by default
-Dmcp.trialWatchdog.intervalSeconds=30   # default 30, floor 5
```

It polls `demoTimeRemaining` and resets once the trial has expired.

Automating a button Ignition ships is not circumventing a licence check, but running it forever
unattended turns a deliberately time-boxed trial into an unbounded one — so it is opt-in, refuses
to start on an activated gateway, and logs at WARN both at startup and on every reset, under the
logger `mcp.Gateway.Trial`. Use it on a throwaway dev gateway; licence anything a customer touches.
