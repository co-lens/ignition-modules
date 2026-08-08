---
title: Editing views
sidebar_position: 2
---

# Editing views

Thirteen tools, Designer-only. They exist so a model can change a view without ever handling raw
`view.json` — it addresses components by path, and the failure mode is a rejected call rather than
a corrupted view.

The full argument lists are in the [Designer tool reference](../tools/designer.md). This page is
the shape they share.

## The contract

Every edit tool does the same three things:

1. **Read** the view as the Designer currently sees it, including unsaved edits.
2. **Apply** exactly one change.
3. **Validate**, and refuse to stage anything that would leave the view broken.

Nothing is committed by these tools. Every successful edit appears as an **unsaved Designer change** for a human
to review and save — check what's pending with
[`list_pending_changes`](../tools/designer.md#list_pending_changes).

## Addressing

**Component paths** are slash-separated names from the root container down:

```
root
root/FlexContainer
root/FlexContainer/Label
```

The tree from [`perspective_get_view`](../tools/designer.md#perspective_get_view) carries the exact
path for every component, so read the view first rather than guessing paths.

For view-level properties, pass the literal path `view` instead of a component path.

**Property keys are scoped.** A bare name is rejected with a message telling you what to write
instead:

| Key | Means |
| --- | --- |
| `props.text` | a built-in component property |
| `custom.value` | a custom property you added |

## A worked example

Create a view, put a label in it, bind the label to a tag, and check the result:

```text
perspective_create_view      view="Page/Demo"
perspective_add_component    view="Page/Demo" parent="root"
                             type="ia.display.label" name="Readout"
perspective_set_binding      view="Page/Demo" path="root/Readout"
                             property="props.text" type="tag"
                             config={"tagPath": "[default]Line1/Speed"}
perspective_validate_view    view="Page/Demo"
```

Then tell the user to review and save it in the Designer.

## The three things that usually go wrong, and don't here

These are exactly the mistakes [validation](./validation.md) exists to catch in hand-written views.
Through the tools they're unreachable:

- **Bindings land in `propConfig`, never in `props`.** A binding written into `props` renders as
  literal text and Perspective reports nothing at all.
- **`bidirectional` goes *inside* `config`**, not beside it, where it would be silently ignored.
  `perspective_set_binding` validates the binding config against Perspective's schema for that
  binding type before staging anything.
- **Scripts are indented for you.** Perspective event scripts are function bodies, so an unindented
  line is a runtime syntax error. Write the body flat; `perspective_set_event` and
  `perspective_set_change_script` handle the indentation.

## Positions come from the parent

When you add a component, its `position` shape depends on the **parent container's** type — a flex
container wants different keys than a coordinate container. `perspective_add_component` takes the
default position from the registry entry for the parent, which is what makes a new component lay
out correctly instead of landing at 0,0 with the wrong keys.

Let the tool supply the position unless you have a specific reason not to. Names are made unique
among siblings automatically.

## Moving and merging

- `perspective_update_component` **merges** recursively, so nested objects like `style` keep the
  keys you didn't mention. It can rename in the same call.
- `perspective_move_component` reparents or reorders, and props, bindings and events travel with
  the component.
- `perspective_delete_component` removes the component and everything beneath it.

## Custom properties

`perspective_set_custom_property` adds or updates a custom property on a component, or on the view
itself with path `view`. Custom properties are where values shared between bindings and scripts
belong. Deleting one also removes any binding attached to it.
