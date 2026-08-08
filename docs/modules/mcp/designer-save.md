---
title: Saving from a Designer
sidebar_position: 6
---

# Saving from a Designer

Every Designer write in this module stages an unsaved change and stops there, so a human reviews
it and presses Save. That is the default and it is the right one.

It is also unworkable when nobody is at the machine, which is an ordinary state: remote work, a
Designer in a VM, CI, a scheduled run. `save_project` is the supported way through, off unless you
turn it on.

```
wrapper.java.additional.9=-Dmcp.designer.allowSave=true
```

or, launching a Designer directly, add `-Dmcp.designer.allowSave=true` to its JVM arguments.

With the flag off — the default — `save_project` **is not registered at all**. It won't appear in
`tools/list`, and calling it returns `Unknown tool` rather than a permission error, the same
structural gating the gateway's write endpoint uses. The tool is documented either way, in the
[Designer tool reference](./tools/designer.md), so you can find out the flag exists without having
to already know.

## Why a flag rather than always on

:::warning This removes the review step, which is the Designer scope's whole point
With it on, a connected client can commit whatever it has staged, to a real project, with nobody
looking. The gateway's write tools are guarded by a credential; this is guarded by nothing except
having been switched on, because a client that can reach the Designer bridge already holds its
per-session secret.

It logs a WARN under `mcp.Designer` at startup naming the property. Turn it on for a Designer that
is genuinely unattended; not for one somebody is sitting at.
:::

The honest argument for having it at all: the staging boundary never actually *prevented* an
unattended agent from getting what it wanted. It is possible today to stage an edit, read the bytes
straight back out with `read_resource`, and write them wherever you like — which is exactly what
one integration ended up doing. What staging prevented was the *project* being updated, so the
workaround produced files without a saved project, which is worse than either outcome anyone
intended. A supported door with a warning on it beats people inventing their own.

## What it does

```
save_project()
```

1. **Nothing staged → nothing happens.** Reports `committed: 0` rather than erroring, so it is safe
   in a script that runs unconditionally.
2. **Checks the gateway will accept a save** from this Designer, and refuses with that answer if
   not — usually the logged-in user lacking project save rights.
3. **Refuses on conflict.** If a staged edit collides with a change already waiting on the gateway,
   it names the resources and stops. Saving would resolve that in this Designer's favour with
   nobody adjudicating, which is precisely the case where "a human reviews it" was doing real work.
   Run [`merge_gateway_changes`](./tools/designer.md#merge_gateway_changes) first.
4. **Pushes**, then reports the resource paths it committed and how many remain staged.

The push is synchronous, so a rejection comes back as a tool error you can act on rather than a
dialog nobody is there to read. `list_pending_changes` returning `0` afterwards is the invariant to
assert on.

## The limitation

**It does not flush editors a human has open.**

Ignition's real Save runs `commitAll()` first, which pushes the contents of open editor windows
into the project tree before pushing to the gateway. That method is private, and there is no public
save API at all — `DesignerContext` and `DesignableProject` both lack one. `save_project` goes
through `ProjectsRpc` directly, which is public and verified but starts one step later.

The consequence: if somebody is working in this Designer with an unsaved editor open, that
editor's content is not part of the save. Everything already in the project tree is. On an
unattended Designer — the case this exists for — there are no open editors and the distinction
never arises.

The tool cannot detect the situation, so it states it in its own description rather than pretending
otherwise.

## Checking whether it's on

`designer_info` reports the project and pending change count. The simplest check is whether
`save_project` appears in `tools/list` — if it doesn't, the flag isn't set on that Designer.
