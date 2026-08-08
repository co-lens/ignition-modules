---
title: What you can ask for
sidebar_position: 4
---

# What you can ask for

Once connected, you don't call tools yourself — you describe what you want and the client picks
the tools. This page is about what's actually possible, so you know what to ask.

The [tool reference](./tools/index.md) has the exact arguments if you need them.

## Understand a gateway you've never seen

Probably the highest-value thing here. A model can orient itself far faster than you can click
through the UI.

> *"What's on this gateway? What tag providers, projects and database connections does it have?"*
>
> *"Show me the tag tree under `[default]Area1` — what's actually in there?"*
>
> *"What's the configuration of `[default]Line1/Motor` — its alarms, history settings, and where
> its value comes from?"*

`gateway_info` is the usual starting point; `browse_tags` walks the tree, and `get_tag_config`
returns how a tag is *built* rather than just its current value.

## Diagnose something that's broken

> *"Tags on Line 3 are showing bad quality. What's going on?"*
>
> *"Why did the historian stop writing at 14:20? Check the gateway log."*
>
> *"What alarms are active and unacknowledged right now?"*

`query_logs` is usually the fastest route to an answer — narrow it with `minLevel` and
`searchTerms` rather than pulling everything. `read_tags` returns quality and timestamp alongside
each value, which is often the whole diagnosis.

For a Perspective binding that isn't working, [live diagnostics](./perspective/live-diagnostics.md)
is the specific tool: Perspective reports binding failures as bad *quality* rather than as errors,
so seeing the quality next to the binding config is the answer.

## Read the project

> *"What Perspective views exist in this project?"*
>
> *"Show me the named query `SalesByShift`."*
>
> *"Which project resources reference the tag `Line1/Speed`?"*

Project resources come back as their raw contents — `view.json` for Perspective views, `code.py`
for scripts.

## Work with files in git

Ignition keeps its resources as files — project resources under `data/projects/`, and on 8.3 also
gateway config under `data/config/`. Edit those files directly, or switch a git branch under them,
and the running gateway carries on serving what it loaded at startup. **On Ignition 8.3 it never
notices on its own**, so a scan is the only thing that makes a disk edit take effect.

> *"I just switched branches — make the gateway load what's on disk now."*
>
> *"I edited `Page/Main`'s view.json by hand. Pick it up and tell me what changed."*
>
> *"My Designer is showing the old version after that scan — merge the gateway's changes in."*

`scan_resource_files` re-reads from disk and reports which resources actually changed, at
resource-level granularity. It covers both collections: `projects` for views, scripts and named
queries, and `config` for tags, device connections and themes — the latter on 8.3 only, since 8.1
keeps gateway config in a database rather than on disk.

Two things to know before running it against an unfamiliar working tree:

- **It scans every project, not one.** A project directory that has appeared gets registered, and a
  project whose directory is *gone* gets deleted from the gateway.
- **The Designer is separate.** A gateway scan doesn't update a Designer someone has open; that's
  what `merge_gateway_changes` does, and it refuses rather than overwrite when an unsaved Designer
  edit collides with an incoming change.

## Ask questions of your data

> *"How many rejects per shift last week? The data's in the `production` database."*
>
> *"Chart the last 24 hours of `[default]Line1/Temperature` — hourly averages."*

`run_query` refuses anything that isn't a `SELECT` or `WITH`, so it is for analysis, not for
changing data. `query_tag_history` goes to the historian with a time window and an aggregation.

## Build and edit Perspective views

This is the part that needs a **Designer** connected, and it's the most substantial capability
here. Every edit is staged as an unsaved Designer change for you to review and save — nothing is
committed on your behalf unless the Designer was started with
[saving enabled](./designer-save.md).

> *"Create a view at `Page/LineStatus` with a label bound to `[default]Line1/Speed`."*
>
> *"Add a flex container to `root` with three gauges, one per line."*
>
> *"The text on `root/Readout` isn't updating — check its binding."*

See [Editing views](./perspective/editing.md) for how the tools fit together, and
[Validation](./perspective/validation.md) for the three authoring mistakes that break views
silently and which these tools make unreachable.

## Keep a trial gateway alive

An unlicensed gateway stops after two hours, which is long enough to be evaluating happily and
short enough to lose the thread when it happens.

> *"The gateway just stopped responding — has the trial expired, or is something else wrong?"*
>
> *"Reset the trial and tell me how long I've got."*
>
> *"I'm about to demo this. Top the trial up now rather than waiting for it to run out."*

`gateway_info` answers the first one without a reset — it reports `licenseMode`, `trialExpired` and
`demoTimeRemaining` in seconds. [`reset_trial`](./tools/gateway.md#reset_trial) restarts the
countdown: the same action as the **Reset Trial** button on the gateway home page, calling the same
`LicenseManagerImpl.resetTrial()` Ignition's own web route calls, under the same rule that the
timer must have run out first. Pass `force=true` to top it up mid-session instead, which is the
third question above. On an activated gateway it's refused — there's no trial to reset.

Two caveats worth having in mind:

- **It needs a write credential.** `reset_trial` is served only on `/data/mcp/mcp`, so a read-only
  token can't call it — or even see it in `tools/list`. That credential is powerful enough to
  warrant thought first; see [Endpoints and security](./endpoints.md).
- **Licence anything a customer touches.** For an unattended dev loop there's an opt-in watchdog
  that resets the trial for you — see [Dev gateway](./contributing/dev-gateway.md#trial-expiry) —
  but automating a two-hour timer forever is a dev-gateway habit, not a deployment strategy.

## See that it's running, without asking it anything

The module puts a card on the gateway's own **Configure → Services → Overview** page, next to the
stock ones:

```
Ignition MCP
┌──────┬────────┬────────────────┬──────────┬───────┐
│ icon │   3    │       22       │  1,284   │  26   │
│      │ Errors │ Read-only tools│ Requests │ Tools │
└──────┴────────┴────────────────┴──────────┴───────┘
```

Requests and errors are totals since the module last started, across both endpoints — enough to
answer "is anything actually connecting?" without turning on debug logging. The same numbers, plus
a few that don't fit on a card, are on
[`/data/mcp/health`](./endpoints.md#the-health-endpoint), which needs no credential. Each one is
also a `mcp.gateway.*` metric on **Diagnostics → Metrics Dashboard** if you want it graphed.

The card shows four numbers because Ignition's overview card renders at most four. Two things
worth knowing therefore live only on the health endpoint: whether
[anonymous read](./endpoints.md#opting-out-of-the-read-credential) is on, and what the trial
watchdog is doing.

## What it deliberately won't do

Worth knowing, so you don't wait for something that isn't coming:

- **The gateway never mutates project resources.** All project editing goes through a Designer, so
  a human reviews and saves. The gateway endpoint has no project-write surface at all — though
  `scan_resource_files` makes it re-read them from disk, which can add or remove whole projects.
- **`run_query` is read-only.** `SELECT`/`WITH` only.
- **Writes need a write credential**, and that credential is powerful enough that the default
  posture is not to issue one. See [Endpoints and security](./endpoints.md).

## If it isn't working

A tool that fails returns its error message to the model rather than breaking the conversation, so
the client will often tell you what went wrong in plain language. If the connection itself is the
problem, see [Troubleshooting](./troubleshooting.md).
