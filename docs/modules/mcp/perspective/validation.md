---
title: Validation
sidebar_position: 3
---

# Validation

[`perspective_validate_view`](../tools/gateway.md#perspective_validate_view) checks against the live
component registry and Perspective's own JSON schemas, and specifically catches the mistakes that
break hand-written views *silently*:

| Code | What it catches |
| --- | --- |
| `inline_binding` | a binding left in `props{}` instead of `propConfig{}` — Perspective renders it as literal data and reports nothing |
| `bidirectional_misplaced` | `bidirectional` on the binding rather than inside `binding.config`, where it is ignored |
| `script_indentation` | an event script without its leading tab; scripts are function bodies, so this is a runtime syntax error |
| `unknown_component_type` | a type that isn't in the registry |
| `invalid_prop` / `invalid_binding_config` | schema violations, via Ignition's own `JsonSchema.validate` |

Props are validated **merged over the component's defaults**, because a stored view omits every
property left at its default — validating the stored object alone reports each unwritten default as
"missing but required" and buries the real findings.

## One amended schema

Binding configs are checked against the schemas Perspective ships, with a single documented
exception. `schemas/binding-tag.json` declares four config keys and sets
`additionalProperties: false`, while Perspective's own `TagBindingConfig` reads seven — it also
takes `fallbackDelay`, `publishInitial` and `coalesce`. Since the Designer writes `fallbackDelay`
on indirect tag bindings, the shipped schema rejects the platform's own output.

The module restores those three declarations before validating, and nothing else: the schema stays
closed, so a misspelled key is still caught. An existing declaration is never overwritten, so a
future Perspective that fixes its own schema simply wins. The gap is identical on 8.1 and 8.3.

`perspective_set_event` and `perspective_set_change_script` indent scripts for you, and
`perspective_set_binding` writes to `propConfig` and validates the binding config before staging —
so the three silent mistakes above **cannot be made through the tools at all**. Validation exists
for views that were written by hand, or edited outside these tools.

## The write gate

The Designer edit tools run these same checks before staging anything, and refuse an edit that
would **introduce** an error. Errors the view already carried are reported on the result instead of
blocking the write — see [Pre-existing errors](./editing.md#pre-existing-errors).
