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

## Ask questions of your data

> *"How many rejects per shift last week? The data's in the `production` database."*
>
> *"Chart the last 24 hours of `[default]Line1/Temperature` — hourly averages."*

`run_query` refuses anything that isn't a `SELECT` or `WITH`, so it is for analysis, not for
changing data. `query_tag_history` goes to the historian with a time window and an aggregation.

## Build and edit Perspective views

This is the part that needs a **Designer** connected, and it's the most substantial capability
here. Every edit is staged as an unsaved Designer change for you to review and save — nothing is
committed on your behalf.

> *"Create a view at `Page/LineStatus` with a label bound to `[default]Line1/Speed`."*
>
> *"Add a flex container to `root` with three gauges, one per line."*
>
> *"The text on `root/Readout` isn't updating — check its binding."*

See [Editing views](./perspective/editing.md) for how the tools fit together, and
[Validation](./perspective/validation.md) for the three authoring mistakes that break views
silently and which these tools make unreachable.

## What it deliberately won't do

Worth knowing, so you don't wait for something that isn't coming:

- **The gateway never mutates project resources.** All project editing goes through a Designer, so
  a human reviews and saves. The gateway endpoint has no project-write surface at all.
- **`run_query` is read-only.** `SELECT`/`WITH` only.
- **Writes need a write credential**, and that credential is powerful enough that the default
  posture is not to issue one. See [Endpoints and security](./endpoints.md).

## If it isn't working

A tool that fails returns its error message to the model rather than breaking the conversation, so
the client will often tell you what went wrong in plain language. If the connection itself is the
problem, see [Troubleshooting](./troubleshooting.md).
