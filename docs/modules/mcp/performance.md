---
title: Performance
sidebar_position: 5
---

# Performance

Five read-only tools for the question the rest of the tool set doesn't answer: *why is this gateway
slow?*

| Tool | Scope | Answers |
| --- | --- | --- |
| [`thread_dump`](./tools/gateway.md#thread_dump) | gateway | What every thread is doing, by subsystem. Is anything deadlocked. |
| [`thread_hotspots`](./tools/gateway.md#thread_hotspots) | gateway | Which threads are burning CPU *right now*. |
| [`jvm_health`](./tools/gateway.md#jvm_health) | gateway | Heap and pool pressure, GC time, classloader growth, JVM flags. |
| [`perspective_session_performance`](./tools/gateway.md#perspective_session_performance) | gateway | What each running session costs — above all, its queue depth. |
| [`perspective_analyze_performance`](./tools/gateway.md#perspective_analyze_performance) | both | What a project's views cost to open and to keep open. |

All five are read-only in the strict sense: they observe, and they change nothing — including
JVM-level switches. So they serve from `/data/mcp/mcp-readonly` as well as the write endpoint. One
caveat about that on the [endpoints page](./endpoints.md).

## Start with the shape of the problem

The tools divide along a line worth knowing before you pick one.

**Is the whole gateway slow, or one screen?** A gateway pegged at 100% CPU, or spending a third of
its wall clock in garbage collection, is slow for everyone, and no amount of view analysis will
explain it. Start at `jvm_health` and `thread_hotspots`. If the JVM looks calm and one Perspective
screen still feels bad, the problem is that screen, and the two `perspective_*` tools are where to
look.

**Is it slow now, or slow to open?** Different tools. Ongoing cost is bindings and polling —
`perspective_session_performance` and the polling findings. Cost at open is size and fan-out —
component counts, embedded views, nesting depth.

## Threads

`thread_dump` groups threads by name prefix. Ignition names them by subsystem and appends an index
(`perspective-worker-3`, `gateway-scheduled-1`), so dropping the numeric parts turns four hundred
threads into a readable per-subsystem table: how many, in what states, how much CPU between them.

`deadlocked` is always computed and is usually empty. When it isn't, that is the entire answer, and
it comes with the stack and lock owner of each participant.

The per-thread CPU in a dump is **cumulative since gateway startup**, which on a gateway that has
been up for a week ranks whatever has been running longest rather than whatever is busy. That is
what `thread_hotspots` is for: it samples twice over a window and ranks by the difference.

```
thread_hotspots(sampleSeconds: 5)
```

It blocks for the window before returning — the sample *is* the wait — and is capped at 30 seconds.

:::note What these deliberately do not report
Blocked and waited *times* per thread need `ThreadMXBean` contention monitoring, which is off by
default, costs on every lock operation, and would have to be switched on — a mutation, in tools
that promise not to mutate. Blocked and waited *counts* need no such switch and are reported
instead; they are enough to see contention.
:::

## The JVM

`jvm_health` is instantaneous by default. The number that most often ends an investigation only
appears when you ask for a window:

```
jvm_health(sampleSeconds: 10)
```

That adds `sample.gcTimePercent` — garbage collection as a fraction of wall clock. A gateway
spending a large share of its time collecting is slow for a reason that will never appear in a log
file.

The rest is per-memory-pool occupancy (which tells you *which* generation is under pressure, unlike
a single heap number), GC counts and times per collector, direct buffer usage, class-loading
counts, and the JVM's own launch arguments — heap sizing and GC choice, which are frequently the
answer by themselves.

A loaded-class count that climbs steadily across module restarts and never comes back down is the
classic classloader leak.

## Perspective sessions

`perspective_session_performance` reports each running session. **Read `queueDepth` first.**

Every Perspective session serializes all of its work through a single execution queue. A queue that
is not draining is the definition of a session that feels slow, and it says so before any timer
average has moved. Sessions are ranked by it unless you pass `sortBy`.

Alongside it: uptime, last communication, page/view/component/binding counts, and Dropwizard timers
for the scripts, expressions, fetches and queued tasks that session has run — count, mean, p95, p99
and max, converted to milliseconds. The gateway-wide script and expression timers come back in the
same call, so you can see whether one session is unusual or everything is.

`includeViews: true` adds the pages and views currently mounted, with each view's age. Perspective
exposes no view *load* duration anywhere, so age is the closest available signal — useful when a
session's component count has been climbing and you want to know what has been resident.

## Perspective views

`perspective_analyze_performance` sweeps a project's views and reports what each costs, heaviest
first, with findings for the specific things that make them cost that. It works from either scope,
so you can run it against a Designer's open project before saving.

Every finding is a **warning**. That is deliberate: `perspective_validate_view` errors block edits,
and a performance report that refused edits to an already-slow view would be actively unhelpful.

| Code | What it catches |
| --- | --- |
| `fast_polling_binding` | A query, HTTP or tag-history binding polling faster than the budget. Runs per open session, forever. |
| `polled_query_uncached` | A polling binding with the value cache off, so every session issues its own request every tick instead of sharing one. |
| `expensive_expression` | `now()` (installs a one-second timer), `runScript()` (enters Jython on every evaluation), `tag()` (an unsubscribed read each time). |
| `script_transform_chain` | Several script transforms in sequence, or one on a polling binding. |
| `repeated_view_cost` | A view rendered many times by a repeater or carousel, multiplied by its own binding count. |
| `heavy_view` | More components or bindings than the budget. |
| `deep_nesting` | Container nesting beyond the budget, which multiplies browser layout work. |
| `embedded_view_fanout` | Many embedded views, each a separate resource load. |

Every threshold is a tool argument. There is no defensible fixed number — a one-second poll is
unremarkable on a five-component status view and ruinous on a view a repeater renders forty times —
so the defaults are a starting point, not a verdict.

### The one that needs the whole project

`repeated_view_cost` is the reason this is a project-wide sweep rather than a per-view tool. It is
invisible from inside either view involved: the repeater's view.json shows a component and a path,
and the target's shows some bindings. Only together do they say *this view's forty bindings are
created ten times over*. Bindings inside a repeated view are the most expensive thing in
Perspective, and this is the only way the tools can see them.

A repeater whose instance list is itself bound has a count decided at runtime; the finding says so
rather than reporting a wrong number.

### One thing worth unlearning

**Tag bindings do not poll.** They subscribe, and their config has no rate in it at all. Only
`query`, `http` and `tag-history` bindings poll, through a shared `config.polling` block whose
`rate` is an expression string in *seconds*. The rules are written against Perspective's own
binding constants, so a tag binding is never flagged as polling — and when a poll rate is an
expression rather than a number, nothing is claimed about it.
