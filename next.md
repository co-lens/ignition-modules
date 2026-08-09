# Next steps

**0.3.0 is released.** Shipped 2026-08-09, signed, and now `/releases/latest`.

https://github.com/co-lens/ignition-modules/releases/tag/mcp-v0.3.0

Every feature in it was verified against a live Ignition 8.3.8 gateway and a running Designer
during the rc period — see the four completed sections below for what was actually run. rc
validation turned up three defects; all were fixed before the stable tag.

**Nothing is outstanding that blocks anything.** What follows is (1) the record of what was
verified and how, which is worth keeping because several results were surprising, and (2) the four
undecided items, all now built. None were defects.

**One thing is still unverified**: the release rehearsal change has never run against GitHub — see
[Not yet verified](#not-yet-verified). Everything else has been run.

---

## ~~1. Grant the gateway API token write permission~~ — DONE

You granted it. This had blocked the same test twice, from two directions.

For the record, since the diagnosis is worth keeping: a freshly-commissioned 8.3.8 ships
`writePermissions` as `AnyOf[Authenticated/Roles/Administrator]`, and a token carrying
`securityLevels: [Authenticated]` gets 200 on `/mcp-readonly` and 403 on `/mcp`. Not a regression —
the write path is byte-identical to 0.2.0, verified by diff. This is why doc fix #1 below matters.

## ~~2. Verify the tag and UDT tools against a live gateway~~ — DONE, and better than the bar

Run on rc1 against a live 8.3.8. **All three UDT fixtures came back byte-identical** to the ones
`system.tag.configure` produced — `cmp` clean, not "equivalent" or "semantically the same":

```
cycle 1  Motor                 written 1, valid, 0 errors  ->  udts-simple.json   IDENTICAL
cycle 2  Valve + Skid          written 2, valid, 0 errors  ->  udts-nested.json   IDENTICAL
cycle 3  Pump (the Int4 case)  written 1, valid, 0 errors  ->  udts-params.json   IDENTICAL
```

So `configure_tags` is a faithful **replacement** for `system.tag.configure`, not merely a working
alternative. That was the right bar and it's cleared. `delete_tags` exercised between cycles,
single and multi-path, qualities `Good`.

Three design decisions were vindicated rather than merely untested:

- **Not validating `dataType` values** turns out to be load-bearing for that byte identity. `Int4`
  went through untouched and persisted as `Integer`, exactly as via `system.tag.configure`. A type
  allowlist would have rejected the input outright and made the comparison impossible to run at
  all — not merely different.
- **The forward `typeId` reference held**, confirming resolution happens at save and ordering is
  the gateway's business: `Skid.Inlet` naming `Valve` in one call wrote 2 and came back sorted.
- The per-path `ok` plus `valid`/`errorCount`/`findings` envelope was assertable programmatically,
  where `run_script` returns a stringified `QualityCode` list to parse.

**Worth keeping:** those three fixtures were produced by `system.tag.configure` one day and
reproduced byte-for-byte by `configure_tags` the next, on different module versions. They are now a
regression target for **Ignition's tag writer itself**, not just for our tool — if a future
platform or module version changes those bytes, it shows up immediately on either side.

**`import_tags` is now also verified**, and it closed the last unverified assumption in the rc. Full
round trip on a live 8.3.8: `system.tag.exportTags` a UDT → `delete_tags` → `import_tags` the same
bytes back → persisted file byte-identical to the fixture. The hardcoded `"json"` format argument
was inferred from `exportTags`; it is now confirmed end to end.

That run also found two things wrong with the tool's *description*, both since fixed (uncommitted):

- It claimed the export arrives "with its top-level `tags` array". Wrong — a single-path export is
  a bare **object**, and reshaping it into `configure_tags`' array form would have been wrong with
  the tool's own text to blame.
- A malformed payload fails in Ignition's parser rather than in validation, so it surfaces in
  `qualities` and leaves `findings` empty — a response that looks clean apart from `imported: 0`.
  Callers must assert `imported` against `total`. Nothing is partially written when it happens.

**Collision semantics and `rename_tag` are now verified too**, so all four tag tools have run
against a live gateway and every policy that carries risk is covered.

- **`MergeOverwrite` (the default) does what the docs promise.** A tag re-sent with only `name` and
  a changed `dataType` kept its `historyEnabled`, `tagGroup` and `documentation`. No silent data
  loss, and the worked example in `tags.md` is honest.
- **`Overwrite` is correctly destructive** — the same call reduced the tag to exactly what was
  sent. The contrast is sharp, which is what you want from a policy one string away from the
  default.
- **`Abort` refuses correctly**, leaving the tag untouched.
- **`rename_tag` works both ways**, including refusing to rename onto an existing sibling.

`Rename` and `Ignore` policies remain unrun. Deliberately: they are pass-through to Ignition's own
`CollisionPolicy` with no code of ours on the path, and the two that carry risk are covered.

That run found **two more defects, both since fixed (uncommitted)** — one of them a real bug:

- **`configure_tags` reported `written` as the number of tags *attempted*.** An `Abort` refusal came
  back as `written: 1` with `ok: false` in `results`, so a caller asserting `written == len(tags)`
  read a total refusal as a complete success. A field called `written` returning the number offered
  is simply wrong, and prose can't repair a name that lies — it now counts successes, with a new
  `attempted` alongside it.
- **The export-shape correction from earlier was an overcorrection.** The shape is *conditional*:
  one path yields a bare object, several yield an object wrapping a `tags` array. The original text
  was right for multi-path, the replacement right for single-path, and both were confidently wrong
  half the time. Documented as conditional now, with "pass it through unchanged" as the rule, since
  `import_tags` accepts either.

## ~~3. Verify `save_project` on a running Designer~~ — DONE

Worked first time, on a real Designer with ten staged views:

```
save_project {} -> {"committed": 10, "resources": [ ...all ten... ], "pendingAfter": 0,
                    "note": "Committed to the gateway. Nothing is staged in this Designer now."}
```

Every file landed on disk, `list_pending_changes` went to 0. The `committed` + `pendingAfter` pair
did its job — the outcome was assertable without counting an array or trusting the note.

The rest of this section is kept because it is the procedure to re-run after any change to the
save path, and because the two warnings still apply.

Needs a Designer started with `-Dmcp.designer.allowSave=true`.

> **Set both JVM args, not one.** This cost a round trip: `-Dmcp.designer.bindAddress=0.0.0.0` got
> *replaced* rather than appended when `allowSave` was added, putting the bridge back on
> guest-loopback and unreachable from the host. Both are needed on a Designer in a VM:
>
> ```
> -Dmcp.designer.bindAddress=0.0.0.0
> -Dmcp.designer.allowSave=true
> ```
>
> The tell was the connect dialog advertising `127.0.0.1`. Otherwise the failure is
> indistinguishable from a dead port — see the discovery-file decision below.

> ⚠️ **Do not enable it on a Designer you are working in.** `save_project` pushes the project tree
> but cannot flush editors you have open and unsaved — `commitAll()` is private. A save you didn't
> perform would leave your buffer behind. Still true and still undetectable by the tool; the
> successful run above was on an unattended Designer, which is the case it is built for.

Worth checking, in order:

1. **Flag off** (default): `save_project` absent from `tools/list`; calling it returns
   `Unknown tool`, not a permission error.
2. **Flag on**: the startup WARN fires and names the property.
3. Nothing staged → `committed: 0`, no error.
4. Stage an edit → `list_pending_changes` shows 1 → `save_project` names the resource →
   `list_pending_changes` returns 0 → the gateway's `read_project_resource` shows the change.
5. Provoke a conflict (edit a resource in the Designer, change the same one on the gateway,
   `scan_resource_files`) → `save_project` refuses, names it, and the staged edit survives.

## ~~4. Verify the `add_component` props fix in a real staged view~~ — DONE

Confirmed on rc1, and it turned out to be the fix that unblocked a fixture nobody could produce:

```
DefaultOrder   props=['path']              loading absent    <- impossible on 0.2.0
WithParent     props=['loading','path']    loading={"order":"with-parent"}
```

Both the present and absent cases in one file, which is what an absence-based lint rule needs and
what eager defaults had made unbuildable. `perspective_create_view` also emits `root.props: {}`.

Incidental confirmation of the size cost: regenerating a large fixture on the fixed build took it
from 91 KB to 58 KB, purely from the defaults going away.

---

## ~~Decisions waiting on you~~ — ALL FOUR DONE (uncommitted)

Checking the list against the tree first found that it was **four items, not five**:
`troubleshooting.md`'s 403 entry already existed, and the discovery file already recorded the bound
address. What follows is the original write-up with what was actually built noted against each.

### ~~The release rehearsal has a hole~~ — DONE, the second option

`workflow_dispatch` skips the release-creation step entirely, so it **passed while the real run
failed**. It cannot catch any bug in creating, annotating or flagging a release — which is the step
most likely to be wrong, because it's the only one that never runs otherwise.

That's how the `--latest=true` on a prerelease bug survived: every `-rcN`/`-betaN` tag would have
failed, and nothing had exercised the path. Worse, it failed *after* creating the release and
uploading assets, landing in exactly the half-published state the "refuse to overwrite an existing
release" guard exists to prevent.

Two options:

- **Cheap and weak** — assert the `--latest`/prerelease combination in the rehearsal without
  calling GitHub. Catches this bug, not its neighbours.
- **Cheap and real** — have the rehearsal create a *draft* release and delete it. Exercises the
  actual API including `make_latest`.

**Built, with one correction to the idea.** A *draft* can't work: GitHub rejects `make_latest=true`
on a draft for the same reason it rejects it on a prerelease, so a draft rehearsal of a stable
version would 422 on every run. The rehearsal now creates a **real** release on a throwaway
`rehearsal-mcp-v<version>-<runid>` tag and deletes it with `--cleanup-tag`.

The one deliberate deviation, commented in the workflow: a stable-version rehearsal is forced to
`--latest=false` with a `::notice::`, because a throwaway release must not take the `Latest` badge
even for the seconds before deletion. That costs nothing — `--latest=true` runs on every real stable
release anyway, whereas the **prerelease path is the one that had never run**, and it now runs
byte-identical to the real thing apart from `--target` standing in for `--verify-tag`.

Cleanup is `if: always()`. That is the point rather than a detail: the failure this exists to catch
happens *after* the release and assets are created, so cleaning up only on success would leave
behind exactly the half-published artefact being tested for.

Verified locally by stubbing `gh` and running all four
(rehearsal × prerelease) combinations, plus the version resolver against five inputs. The real
`workflow_dispatch` run has not happened yet — see the verification note at the end.

### ~~Have the discovery file record the bound address~~ — DONE, and it was half-built already

Second data point today. When the Designer bridge is on loopback but the client is elsewhere, the
failure is `ECONNREFUSED` — indistinguishable from a dead port, a wrong port, or a Designer that
never started. Both times, the thing that actually diagnosed it was a human noticing the connect
dialog said `127.0.0.1`.

If `~/.ignition/mcp/designer-<pid>.json` recorded the address the server actually bound to, a
client could fail with *"this Designer is bound to loopback on the machine running it, not on
this one"* instead. That is an error that teaches. Small change, and it is now the cheapest fix
for a problem that has cost two sessions time.

**It already recorded it.** `DiscoveryFile.write()` has published `host` and `url` from
`server.boundHost` all along, so the premise above was wrong — the gap was that neither field says
*whether a client elsewhere can use them*. Two fields now do: `loopbackOnly` (from a new
`McpHttpServer.loopbackOnly`) and `hostname`, the latter null-tolerant because `getLocalHost()`
throws on hosts whose name doesn't resolve, which is common in containers. A reader finding
`loopbackOnly: true` and a `hostname` that isn't its own can name the machine in the error.

Documented in `clients/remote-designer.md` under "Connection refused, and how to tell why", with a
pointer from `troubleshooting.md`. The JVM-argument trap from §3 went in alongside it — the launcher
field is a single value, so adding `allowSave` *replaces* `bindAddress` rather than appending to it,
and the failure that produces is the very one the section diagnoses.

Related but larger: the gateway→Designer relay idea from earlier is still unbuilt and still the
better general answer, since the gateway is the one endpoint reachable in every VM/NAT/WSL case at
once. It also still needs its own credential rather than reusing the write token.

### ~~Add the `ViewDocument` ordering regression test~~ — DONE, and it bites

The only outstanding item where **being wrong lands silently on someone else's repo**.

lens's view-fixture corpus depends on `ViewDocument` mutating the parsed tree in place, so member
order survives a read-modify-write. That's verified — a round trip is byte-identical, and editing
one component leaves every other member's position untouched — but **nothing asserts it**.
`ViewDocumentTest`'s 21 cases cover structure, not ordering; the nearest is "the document is
deep-copied, so edits never touch the caller's json", which is about isolation.

So a refactor of `ViewDocument` toward typed fields would reorder every MCP-touched view, break a
downstream corpus, and pass our entire suite.

Small: parse a view with awkward key order (`children` before `type`, `scripts` after it), mutate
one node, assert byte-identical apart from that node. Say the word and it's minutes.

**Three cases added** against an `AWKWARD_ORDER` fixture — round trip, edit-one-node, and
`addComponent`, the last because `perspective_add_component` is the operation the pinned view
corpus actually goes through. `keyOrders()` addresses every object in the tree by position and maps
it to its member order, which says "nothing moved" more precisely than comparing serialized text
(that also changes when a value does).

**Confirmed the tests bite**, which matters more than that they pass: making `ViewDocument` rebuild
its tree with sorted keys failed all three — and left the other 140 tests in the module green. That
is the blind spot, demonstrated rather than asserted. Mutation reverted.

### ~~Three documentation fixes, identified but not made~~ — DONE, but there were only two

**#2 was already done** — `troubleshooting.md` has carried the 403/`mcp-readonly` section for some
time. This list was stale, not the docs.

**#1 was the one to do before stable.** It has now cost two sessions time independently, and
the symptom pair — `/mcp-readonly` 200, `/mcp` 403 — is diagnostic, in that nothing else in the
stack produces it.

1. **`quickstart.md`** — promote the write-permission line from a parenthetical to a step. "Reads
   fine, writes 403" reads like a module fault when it's a permissions one, and we've now each lost
   time to it.
   → Now a `:::warning` beside the Require-Secure-Channel one, carrying the diagnostic symptom pair
   inline and linking to `troubleshooting.md#write-403` (a new explicit anchor id, so the link
   can't rot on a heading reword).
2. ~~**`troubleshooting.md`**~~ — already present at "403 on `/data/mcp/mcp` while `/mcp-readonly`
   works". No change beyond giving it the stable anchor id above.
3. **`docker.md`** — two additions from the lens session's real run, worth crediting to them:
   - restoring a backed-up token to a *fresh* gateway needs `mkdir -p` first, because
     `config/resources/core/ignition/api-token/` doesn't exist until the first token is issued, so
     `docker cp` has nowhere to land;
   - the obvious repair afterwards, `scan_resource_files`, is unreachable **by construction** —
     it's on the write endpoint you have no working token for. A restart is the only way in.
   → Both, as a new "Restoring a backed-up token into a fresh gateway" subsection placed *after*
   the existing mount guidance rather than inside it, so the "boot once before adding this mount"
   warning stays attached to the block it warns about.

---

## Not yet verified

Everything above passes `./gradlew build` and a clean `docusaurus build` (both new doc anchors
resolve in the built HTML). One thing cannot be checked locally:

**The release rehearsal has not been run against GitHub.** Push the branch, then:

```bash
gh workflow run "Release module" --ref <branch> -f version=0.3.1-rc1 && gh run watch
```

Confirm the release-creation step ran, then that nothing survived it:

```bash
gh release list | grep rehearsal          # expect nothing
git ls-remote --tags origin | grep rehearsal   # expect nothing
```

Run it a second time with a stable version (`0.3.1`) to see the `::notice::` downgrade fire, and
check the `Latest` badge still points at `mcp-v0.3.0` throughout.

---

## ~~Cut 0.3.0 stable~~ — DONE

Released 2026-08-09 from `56acfd3`. `Ignition-MCP-0.3.0.modl` + `.sha256`, signed with the release
key, and it correctly took the `Latest` badge while rc1 stayed a pre-release. Same certificate
fingerprint as 0.2.0, so installed gateways won't re-prompt.

For the next one, the whole procedure is: commit, push `main`, then

```bash
git tag -a mcp-v<version> -m "Release <version>"
git push origin mcp-v<version>
```

The workflow refuses commits not on `main` and refuses to overwrite an existing release, so the
only real precondition is that verification actually happened.

Consider whether 8.1 (`mcp81-v*`) should follow. Per `versions.md` it doesn't get the performance
tools, the tag tools or `save_project` — the branch is frozen to security and wrong-data fixes —
so there may be nothing to release there.

---

## External dependencies on this behaviour

Worth knowing before changing anything in these areas — the lens project now has public fixtures
that act as canaries, on `co-lens/lens` branch `mcp-corpus`:

- **Three UDT fixtures** at `test/corpus/tags/`, produced by `system.tag.configure` on one module
  version and reproduced byte-for-byte by `configure_tags` on another. They are a regression target
  for **Ignition's tag writer**, not just for our tool. Stable; they won't be regenerated without
  notice.
- **Ten view fixtures**, pinned to 0.3.0-rc1. These *would* change if `perspective_add_component`'s
  seeding changes again — which makes them the canary for exactly the bug we just fixed.

## Environment left running

| Container | Port | State |
|---|---|---|
| `mcp-verify-gw` | 18500 | Local dev build (35 tools). Has a `perftest` project with a deliberately slow view, and `-Dmcp.trialWatchdog=true` added to its `ignition.conf` so the trial self-resets. |
| `lens-gw83` | 8188 | **0.3.0-rc1 installed** — the first real deployment. Clean install, certificate accepted unattended. |
| `docker-ignition-gateway-1` | 18088 | Still on **0.2.0-rc3**. Predates the tool-count fields in `/health`, so its health payload stops after `mcpReadOnlyEndpoint` and looks like a regression. It isn't; it's just old. |

**If a gateway 404s on `/data/mcp/health` after a `.modl` swap**, check `data/modules.json` for
`"onStartup": "disabled"` before rebuilding anything — the module silently doesn't start and
nothing is logged. Sign local builds with:

the local dev key's `-Pmodule.*` properties — see
[`contributing/building.md`](docs/content/contributing/building.md) for the five of them and how the
key was generated.

Without them the plugin skips signing **silently** and leaves the previous `.modl` in place, so
"the file exists" proves nothing.

---

## Shipped in 0.3.0 after rc1

Three defects found by rc validation, all fixed in `56acfd3` before the stable tag:

1. **`configure_tags` reported `written` as tags *attempted*.** An `Abort` refusal answered
   `written: 1` with `ok: false` buried in `results`, so a caller asserting on the count read a
   total refusal as complete success. Now counts successes, with `attempted` added.
2. **The `import_tags` export-shape description was wrong**, then wrong in the other direction
   after the first correction. The shape is conditional; both forms are now documented, with "pass
   it through unchanged" as the rule.
3. **`import_tags` accounting** — a malformed payload fails in Ignition's parser rather than in
   validation, so it surfaces in `qualities` and leaves `findings` empty. Compare `imported`
   against `total`.

Plus the `perspective_add_component` defaults fix, which shipped in rc1 itself.
