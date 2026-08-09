---
title: Tags and UDTs
sidebar_position: 4
---

# Tags and UDTs

Reading tag configuration has always been possible. Writing it now is too, without reaching for
`run_script`.

| Tool | Scope | What it does |
| --- | --- | --- |
| [`browse_tags`](./tools/gateway.md#browse_tags) | read | Walks the tag tree |
| [`read_tags`](./tools/gateway.md#read_tags) | read | Current values, quality and timestamp |
| [`get_tag_config`](./tools/gateway.md#get_tag_config) | read | How a tag is *built* |
| [`write_tags`](./tools/gateway.md#write_tags) | write | Writes live process **values** |
| [`configure_tags`](./tools/gateway.md#configure_tags) | write | Creates and edits tags, UDT definitions and instances |
| [`delete_tags`](./tools/gateway.md#delete_tags) | write | Removes tags and everything under them |
| [`rename_tag`](./tools/gateway.md#rename_tag) | write | Renames in place |
| [`import_tags`](./tools/gateway.md#import_tags) | write | Applies a whole tag export |

The four write tools are gateway-scope and **commit immediately**. Unlike the Perspective edit
tools there is no staging step and no human review — a `delete_tags` call is gone the moment it
returns. They serve only from `/data/mcp/mcp`.

## The shape

`configure_tags` takes a `parentPath` — the folder tags land in — and an array of configuration
objects, each carrying its own `name`. That is Ignition's own convention rather than one we
invented: `TagUtilities.toTagConfiguration` composes a path from a parent plus the JSON's `name`.

```json
{
  "parentPath": "[default]Area1",
  "tags": [
    { "name": "Count", "tagType": "AtomicTag", "valueSource": "memory", "dataType": "Int4", "value": 0 }
  ]
}
```

**UDT definitions are ordinary tag configs under the provider's `_types_` folder.** Write one with
`parentPath: "[default]_types_"` and `tagType: "UdtType"`, with its members in a nested `tags`
array and its parameters in `parameters`. An instance is `tagType: "UdtInstance"` with a `typeId`.
One tool covers all three because Ignition makes no distinction underneath.

The configuration format is the one [`get_tag_config`](./tools/gateway.md#get_tag_config) returns,
so read-modify-write is the intended workflow. It is not a perfect round trip, though — see
[Reading back what you wrote](#reading-back-what-you-wrote).

## Collisions

`collisionPolicy` decides what happens when a tag already exists. The default is
**`MergeOverwrite`**: the properties you send are applied, and the ones you leave out keep their
current values. Changing one setting therefore does not require sending the whole tag.

The distinction that costs people data is `Overwrite`, which **replaces** the configuration:

```
existing:  { name: M1, dataType: Int4, historyEnabled: true, tagGroup: "Fast" }
sent:      { name: M1, dataType: Int8 }

MergeOverwrite ->  { name: M1, dataType: Int8, historyEnabled: true, tagGroup: "Fast" }
Overwrite      ->  { name: M1, dataType: Int8 }        // history and tag group are gone
```

`Overwrite` is right when you have read a full config, changed it, and are sending it back.
`Abort` refuses to touch anything that already exists, which is the safe first pass against a
provider you don't know. `Rename` and `Ignore` are also accepted.

All three behaviours above are verified on a live 8.3.8 rather than inferred: under the default a
tag re-sent with only `name` and a changed `dataType` kept its `historyEnabled`, `tagGroup` and
`documentation`; under `Overwrite` the same call reduced it to exactly what was sent.

**A refusal is per tag, and the call still succeeds.** `Abort` hitting an existing tag comes back
as a bad quality on that entry, not as an error — so check `written` against `attempted`, or read
`ok` on each entry of `results`. The refused tag is left exactly as it was.

## Why the tools validate before writing {#validation}

`configure_tags` checks the configuration and **refuses the whole call** if anything is wrong,
reporting findings in the same shape as `perspective_validate_view`. That is not defensive
politeness. Ignition's own parser accepts several broken inputs silently, so a tool that passed
them through would report success while writing a tag that does not work.

All four were verified against `common-8.3.8.jar`:

| What you write | What Ignition does |
| --- | --- |
| `"parameters": { "DeviceName": { "value": "PLC7" } }` | **Succeeds.** Stores `{ "dataType": "Integer" }` — the value discarded, the type wrong. Logs to the `tags.json` logger and returns success. |
| `"datatype": "Int4"` (wrong case) | **Succeeds.** Keeps `datatype` as a custom property and leaves the real `dataType` unset. |
| no `"name"` | Throws `IndexOutOfBoundsException: Index 0 out of bounds for length 0`. |
| `"name": "bad/name"` | **Succeeds**, though `TagUtilities.isValidName` returns false for it. |

The first is the one to internalise, because the natural way to override a UDT parameter on an
instance is to give the value and let the definition supply the type — and that is exactly the
input that silently fails. **Always state `dataType` alongside `value`, even when the UDT
definition already declares it.**

Findings carry a `fix` line saying what to do instead. Warnings do not block: an unrecognised
property is only a warning, because tags legitimately carry custom ones. A property that differs
from a real one *only in case* is an error, because that is a typo every time.

## Reading back what you wrote {#reading-back-what-you-wrote}

`get_tag_config` output and `configure_tags` input are the same format, but not byte-identical
across a round trip. Two known normalisations:

- **Keys are sorted.** Ignition's tag writer sorts object keys alphabetically, and sorts arrays of
  types too, so what comes back will not preserve the order you sent. (Perspective's view writer
  behaves differently — it preserves insertion order. The two writers are not the same.)
- **Some type names are canonicalised**, e.g. `Int4` is stored as `Integer` in a `parameters` block.

Neither changes meaning, but both will show up in a naive diff. Compare semantically, not textually.

:::note These bytes are pinned outside this repository
Three UDT fixtures at `test/corpus/tags/` in [co-lens/lens](https://github.com/co-lens/lens),
branch `mcp-corpus`, were produced by `system.tag.configure` on one module version and reproduced
**byte-for-byte** by `configure_tags` on another. They are stable and won't be regenerated without
notice.

That makes them a regression target for **Ignition's tag writer**, not just for these tools: if a
future platform version changes how it sorts keys or canonicalises type names, it shows up there
immediately. It also means a change to the tag configuration path here breaks a build in a
repository nobody watching this one will think to check. Worth knowing before altering how
`configure_tags` hands configuration to the platform.
:::

## Deleting and renaming

`delete_tags` removes tags, folders and UDT definitions along with everything beneath them.
Deleting a definition breaks every instance of it, so check with `browse_tags` first.

`rename_tag` is a real rename rather than a delete and recreate, so tag history and UDT instance
membership survive. Every *reference* to the old path — in bindings, scripts and other tags — does
not. It refuses to rename onto a name that is already taken rather than overwriting it.

## `import_tags`

Takes a whole Designer-style tag export as a JSON string and applies it under a base path. Use it
to move a folder or a type library in one call. For individual tags `configure_tags` is easier to
get right: it validates each entry and reports per-tag results, where `import_tags` returns only a
list of quality codes.

**Pass what `system.tag.exportTags` produced, unchanged**, and don't reshape it into
`configure_tags`' `tags` array — the two tools take deliberately different shapes. The export's own
shape is conditional, which is the reason the rule is "pass it through" rather than anything more
specific:

```
exportTags(["[default]Pump"])                    ->  { "name": "Pump", ... }        bare object
exportTags(["[default]Pump", "[default]Valve"])  ->  { "tags": [ {...}, {...} ] }   wrapped
```

`import_tags` accepts both. Verified round trip on 8.3.8: export a UDT, `delete_tags` it,
`import_tags` the same bytes back, and the persisted file is byte-identical to the original.

**Check `imported` against `total`, not `findings`.** A malformed payload fails inside Ignition's
parser rather than in validation, so it surfaces as a quality code and leaves `findings` empty —
giving a response that looks clean apart from `imported: 0`. Feeding it XML, for instance:

```json
{"imported": 0, "total": 1,
 "qualities": ["Error_Exception(\"Error importing tags: ... Not a JSON Object: \"<Tags><Tag\"\")"]}
```

Nothing is partially written when that happens.

Note that a provider stores all of its UDT definitions in a single file, so importing one type
rewrites that file. Anything comparing exported bytes needs to author types in isolation.
