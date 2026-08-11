---
title: Pre-edit backups
sidebar_label: Pre-edit backups
sidebar_position: 7
---

# Pre-edit backups

Before the module changes a tag's configuration or a Perspective view, it writes a copy of what is
about to change. **If that copy cannot be written, the edit does not happen.** That refusal is the
feature — a backup that silently fails is worse than none, because you would trust it.

Nothing needs switching on.

## What is covered

| Tool | What is copied | Restore with |
| --- | --- | --- |
| `configure_tags` | each named tag's subtree | `import_tags` |
| `delete_tags` | each path's subtree | `import_tags` |
| `rename_tag` | the tag's subtree | `import_tags` |
| `import_tags` | the whole destination folder | `import_tags` |
| the 13 `perspective_*` editing tools | the view's `view.json` | `write_resource` |

Tag copies are written in exactly the shape `import_tags` accepts, so restoring is a call this
module already offers rather than a Designer round trip. They always use the wrapped
`{"tags": [...]}` form, so a restore never depends on how many tags happened to be under the path.

## What is not covered, and why

**`write_tags` is not.** It writes tag *values*, and a configuration export restores none of them.
Offering a backup there would be a promise the module cannot keep — live process values are not
recoverable from here at all. Worth knowing before you call it rather than after.

**`perspective_create_view` is not.** It refuses to overwrite an existing view, so there is never a
prior state to lose.

## One copy per thing per session

The first time a session touches a given view or tag path, its state is copied. Later edits to the
same target reuse that copy. Editing one view twelve times leaves one file — what the view looked
like before any of it — rather than twelve near-identical ones.

This is deliberate. The state worth being able to return to is the one before this run started;
intermediate states are recoverable by not making the next edit. "Session" means the module's
lifetime: a gateway restart, or a new Designer, starts fresh.

A consequence worth knowing: if a tag did not exist when first touched, **no file is written**, and
the key is still consumed. That is correct rather than a gap — the prior state was "absent", and
restoring it means deleting the tag, not replaying a file. But it does mean the absence of a file is
itself information.

## Where they go

| Scope | Default location |
| --- | --- |
| Gateway (tags) | `<data dir>/mcp-backups/tags/` |
| Designer (views) | `~/.ignition/mcp/backups/views/` |

The gateway's is under the data directory so the copies travel with a gateway backup. The
Designer's sits beside the discovery file the bridge already writes, on whichever machine the
Designer is running.

Override either with `-Dmcp.backupDir=/some/path`.

File names lead with a UTC timestamp and carry the tag path or view path, so the right file is
findable without opening any of them. Names never collide, so a later copy cannot displace an
earlier one.

## Growth

At most **500 files per category**, oldest deleted first. Pruning is best-effort and never blocks an
edit: once the new copy is safely on disk, failing the write because an *old* file could not be
deleted would refuse a change that is already protected.

Combined with one-copy-per-session this bounds a single run tightly. It does not bound a
long-running gateway across many sessions — that is what the cap is for.

## When a backup fails

The tool returns an error, and **nothing is changed**:

```
Refusing to proceed: could not write the pre-edit backup of
'[default]Area1/Pump' to /usr/local/bin/ignition/data/mcp-backups
(Permission denied). Nothing was changed. Fix the backup directory,
or point it elsewhere with -Dmcp.backupDir.
```

The same applies when the *current* state cannot be read — if the tag provider will not answer, the
edit is refused rather than performed unprotected.

A failed backup does not consume its slot: fix the cause and call again, and the copy is taken
properly. Without that, a retry would sail past the guard rail reporting success while having
preserved nothing.
