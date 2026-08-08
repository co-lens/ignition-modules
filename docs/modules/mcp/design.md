---
title: Why it's small
sidebar_position: 9
---

# Why it's small

The whole module is about 2,500 lines of Kotlin, because three things fell out of the design rather
than being built:

- **No MCP SDK, no SSE, no sessions.** The MCP spec lets a server answer every request with a
  single JSON object, and sessions are optional (removed outright in the 2026-07-28 revision). So
  the transport is a stateless POST endpoint: one route handler plus a JSON-RPC dispatcher handling
  five methods. `GET`/`DELETE` return `405`, which is exactly what a modern-revision server is told
  to answer.
- **No authentication code.** Routes are mounted with `ApiTokenManager`'s access-control
  strategies, so Ignition validates the `X-Ignition-API-Token` header and the token's permissions
  before our handler runs.
- **No JSON dependency.** Ignition ships Gson relocated to
  `com.inductiveautomation.ignition.common.gson`, available in every scope. The only jar this
  module bundles is the Kotlin stdlib.

The same instinct shows up in the [tool reference](./tools/index.md) on this site: rather than
maintain a parallel description of 56 tools, a build task constructs the real tool registries
against a stub context and dumps their metadata. The documentation is the code's own output.

## The one place it isn't small: the gateway status card

Ignition 8.3 removed every Wicket hook a module used to add gateway pages with — `getConfigPanels`,
`getStatusPanels`, `getConfigCategories`, `getHomepagePanels` are all gone, and the SDK went from
485 Wicket-referencing classes to none. Everything in the 8.3 gateway UI is now a React component
loaded over SystemJS, compiled against `@inductiveautomation/ignition-web-ui` and
`ignition-gateway-lib` — neither of which is published on public npm.

So there is no config page, and there won't be one until those packages are obtainable. What there
*is* costs no JavaScript at all: `EntityManager.register` contributes an entity that IA's own
generic overview component renders as a card.

Four things about that path are invisible from the Java API and were established by disassembling
the shipped bundles. Each one fails silently or breaks a page if ignored, so they're recorded here
as much as in `StatusEntity`:

- **Only four metrics render.** `OverviewCard` slices the list at four. Anything past the fourth is
  dropped with no warning — which is why `anonymousRead` and `trialWatchdog` live on the health
  endpoint instead.
- **Order is alphabetical by alias**, not the order they're declared. The builder collects into a
  `TreeMap`; the declared order is only an include-filter.
- **A counter renders as `N/A`.** Its serializer emits `count`, and the card reads `value`. Every
  metric on the card is therefore a gauge, whatever it wraps.
- **`includeInDiagnosticOverview()` without a `navAction` blanks the Diagnostics Overview page**,
  because that page reads `actions[0].url` without checking the array is non-empty. This module has
  no page to link to, so it doesn't set the tag.

There is also no way to *unregister* an entity, and registering a duplicate name throws. The card is
therefore registered once per gateway process behind a `find()` guard, and holds nothing but strings
— every diagnostic is referenced by registered metric name rather than by a supplier, so the
long-lived entity can't pin a module classloader, and it picks up a restarted module's fresh gauges
automatically. A stopped module leaves those names unresolved and the card simply shows no numbers,
which is honest; caching suppliers would have left it showing the last values forever.
