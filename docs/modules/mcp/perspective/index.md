---
title: Perspective
sidebar_label: Overview
sidebar_position: 1
---

# Perspective

Perspective is an **optional** module dependency (`required="false"`), which in Ignition is what
grants classloader visibility of another module's classes. With Perspective installed the
`perspective_*` tools appear; without it they're simply absent from `tools/list` rather than
present-and-broken.

## Reading and diagnosing works from either scope

[`perspective_get_view`](../tools/gateway.md#perspective_get_view) returns the component tree with
the path you pass to every other tool, which properties are bound, and how many events are
attached. [`perspective_get_component`](../tools/gateway.md#perspective_get_component) returns one
component in full.

## Editing is Designer-only

Edits stage as unsaved Designer changes for a human to review and save — the gateway gains no
project-mutation surface at all.

The edit tools are surgical (`perspective_add_component`, `perspective_set_binding`,
`perspective_set_event`, …), so a model addresses components by path and never handles raw
`view.json`. Every edit is validated before it is staged and refused outright if the result would
be invalid.

See **[Editing views](./editing.md)** for how the 13 edit tools fit together.

## The rest of this section

- **[Editing views](./editing.md)** — the editing contract, paths, bindings, events.
- **[Validation](./validation.md)** — what `perspective_validate_view` catches, and why those
  mistakes are silent.
- **[Live diagnostics](./live-diagnostics.md)** — inspecting a view in a running session.
