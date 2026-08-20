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
`Resources needing commissioning: modules` until something accepts the certificate of a module it
doesn't already trust. `docker/docker-compose.yml` sets `ACCEPT_MODULE_CERTS: io.colens.mcp-ign`,
which does that at first boot — so you should never see it. It works for unsigned builds too;
measured on 8.3.7, an unsigned module with that variable set reaches `RUNNING` unattended from a
cold start and across restarts.

The variable is read at boot only. A container **already** stuck in `COMMISSIONING` has to be
recreated (`down` then `up -d`) for it to apply. That is safe here: a gateway that never finished
starting has no projects, tags or API tokens to lose.

`docker/commission.sh` no longer commissions anything — on 8.3.7 `POST /post-step` rejects every
payload shape with a 400. It is now a diagnostic: it reports the gateway's state and tells you what
to set. Run it when a gateway won't come up.

Two traps, both of which quarantine every stock module — Perspective included — and are miserable
to diagnose:

- **Never set `GATEWAY_MODULES_ENABLED`** to just your module. Commissioning reads it as the
  *complete* list of modules to enable and disables everything else.
- `POST /post-step` treats `acceptedCertificates` as the **complete** accepted set, not an addition
  to it, with the same effect. That is why the best-effort attempt in `commission.sh` posts every
  module id, not just ours.

Then `curl -s http://localhost:18088/data/mcp/health` should return `{"status":"ok",...}`.

## Skipping the setup on a dev gateway

On 8.3 the write endpoint costs an API key *plus* a custom security level wired into Gateway Write
Permissions — a UI ritual you do not want to repeat on a container you rebuild. `docker-compose.yml`
in this repo already passes:

```
-Dmcp.devMode=true
```

Both endpoints then answer with no credential at all, so a client needs no header:

```bash
curl -s -X POST http://localhost:18088/data/mcp/mcp \
  -H 'Content-Type: application/json' \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
```

It also implies `-Dmcp.designer.allowSave=true` and `-Dmcp.trialWatchdog=true`. Pass it to the
Designer's JVM as well if you want the Designer bridge to skip its bearer secret — that is a
separate process, so the gateway's flag does not reach it.

:::danger Dev gateways only
This opens the **write** endpoint, which carries `run_script` — arbitrary Jython in gateway scope,
i.e. root on the gateway. It logs a WARN under `mcp.Gateway` at every startup so it can't hide in an
audit, and `/data/mcp/health` reports `"devMode": true`. See
[Endpoints and security](../endpoints.md#dev-mode).
:::

If you want only the read side opened, `-Dmcp.gateway.allowAnonymousRead=true` still does exactly
that and leaves the write endpoint requiring a token.

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
