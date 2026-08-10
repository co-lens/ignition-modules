# Porting the ten missing tools to the 8.1 line

`main` has 56 tools. This is the plan for bringing the 8.1 line up to them. It is in scope for that
branch under the tool-surface-parity exception in its README, and it is the only outstanding
tool-surface work — see "Not in scope" for the one gap that stays.

**Progress: waves 1 and 2 are done — 53 tools on 8.1, three to go.**

- **Wave 1** (`b5ad0d1`, released in **0.2.2**) — `jvm_health`, `thread_dump`, `thread_hotspots`.
  Byte-identical copies of main's files; registration was one import and one `addAll`.
- **Wave 2** (`905c9b0`, released in **0.2.3**) — `configure_tags`, `delete_tags`, `rename_tag`,
  `import_tags`. `TagTools.kt` was taken from main wholesale: this branch's copy turned out to be a
  strict subset apart from four lines, and those four were main's improvements. 0.2.3 also carries
  the `write_tags` fix described below.

Two corrections to this plan came out of doing it, both worth carrying into the remaining waves:

- **`Finding.kt` is a wave 2 dependency, not wave 4.** It is filed under wave 4 below because that
  is where `ViewPerformanceAnalyzer` needs it; `TagConfigValidator` reports through it too. It is
  already on the branch.
- **`17bfc2f` was not pure feature work.** It also replaced `write_tags`'s
  `quality.toString().contains("Good")` with `QualityCode.isGood`, and added a `getOrNull` guard —
  a wrong-data fix to a tool that exists on *both* lines, which the earlier commit-by-commit parity
  review classified away as part of a feature commit. It rode over with wave 2. Before wave 3,
  diff the *files* a wave touches against main rather than trusting commit titles; anything the
  8.1 copy is missing is either a port or a fix, and only the diff distinguishes them.

Baselines when this was written: `main` at `366dd8f` (module 0.3.2, floor 8.3.7), `8.1/main` at
`7c1bcd0` (module 0.2.1, floor 8.1.43).

## Feasibility — checked, not assumed

Every one of the ten is implementable on 8.1.43. This was verified by resolving each SDK type the
tool needs against the 8.1.43 / perspective-2.1.43 jars, not by reading release notes.

| Tool group | 8.1.43 status |
| --- | --- |
| `jvm_health`, `thread_dump`, `thread_hotspots` | **No SDK exposure.** `JvmProbe` uses only `java.lang.management`; `PerfTools` touches `GatewayContext` and nothing else. |
| `configure_tags`, `delete_tags`, `rename_tag`, `import_tags` | **Identical signatures.** `saveTagConfigsAsync(List, CollisionPolicy, SecurityContext)`, `removeTagConfigsAsync(List, SecurityContext)`, `importTagsAsync(TagPath, String, String, CollisionPolicy, SecurityContext)`, `BasicTagConfiguration.createRename`, `CollisionPolicy` (incl. `Abort`, `MergeOverwrite`), `TagUtilities.isValidName` / `toTagConfiguration`. |
| `perspective_session_performance` | **All six types present** in perspective-2.1.43. `SessionStats` is byte-identical to 3.3.8. `PerspectiveSessionMonitor` differs only by `findDesignerSession`, added in 3.3.8 and never called by `LiveSessionInspector`. |
| `perspective_analyze_performance` | **Pure gson.** `ViewPerformanceAnalyzer` imports only `JsonElement`/`JsonObject`. The cost is its dependencies, not the platform — see wave 4. |
| `save_project` | **Needs a translation.** `PlatformRpcInstances` and `common.resourcecollection.*` do not exist on 8.1. Equivalents do — see wave 5. |

## Sequencing

Five waves, ordered by ascending risk. Each is independently shippable: finish a wave, bump
`modules/mcp/VERSION`, push to `8.1/main`, and the release workflow tags and publishes. Do not
batch waves 4 and 5 into one release.

### Wave 1 — the JVM performance trio (lowest risk)

`jvm_health`, `thread_dump`, `thread_hotspots`.

Port `gateway/perf/JvmProbe.kt` and `gateway/tools/PerfTools.kt` as-is, and register them in the
8.1 `GatewayHook`. No SDK surface is involved, so this should be close to a straight file copy.

Do this one first specifically because it exercises the whole loop — port, build against 8.1.43,
release — with nothing platform-specific that can confound a failure.

### Wave 2 — tag configuration

`configure_tags`, `delete_tags`, `rename_tag`, `import_tags`.

Port `common/tags/TagConfigValidator.kt`, `common/tags/TagPropertyCatalog.kt` and their test, then
graft the four tool declarations from `main`'s `gateway/tools/TagTools.kt` onto the 8.1 copy. The
8.1 `TagTools` is the pre-`17bfc2f` file, so this is an additive merge of four declarations plus
their helpers, not a whole-file replacement.

Take `56acfd3` with it — that is the fix making `configure_tags` report `written` as tags actually
written rather than tags offered, plus the corrected `import_tags` description. Porting the tool
without it would reintroduce a known wrong-data bug on a fresh line.

**Verify against a real gateway, not just the compiler.** Tag write paths are where 8.1/8.3
behaviour is most likely to differ silently. At minimum: an `Abort` collision returns
`written: 0` with `ok: false`, and a UDT export → `delete_tags` → `import_tags` round trip
restores it.

### Wave 3 — live session performance

`perspective_session_performance`.

Port `gateway/perspective/LiveSessionInspector.kt` and its tool declaration in `PerspectiveTools`.
All types resolve on 2.1.43 and `SessionStats` is identical, so the risk is low; it is third only
because it needs a Perspective session actually open to test meaningfully.

### Wave 4 — view performance analysis (largest diff)

`perspective_analyze_performance`.

The analyzer itself is trivial. The cost is that `17bfc2f` also *modified*
`PerspectiveComponentCatalog.kt`, `PerspectiveReadTools.kt` and `ViewValidator.kt`, and added
`ComponentNode.kt` and `Finding.kt`, which the 8.1 branch does not have. The 8.1 copies of those
three files are the pre-`17bfc2f` versions, and they are shared with the 19 Perspective tools
already on that branch.

So this wave is a refactor backport, not a tool port. Sequence it as: bring over `Finding.kt` and
`ComponentNode.kt`, reconcile the three modified files against the 8.1 versions, confirm the 19
existing Perspective tools still behave, and only then add the analyzer and its tool. Its test
(`ViewPerformanceAnalyzerTest`, `ComponentNodeTest`) comes along and is the main safety net.

Consider stopping after wave 3 if appetite is limited — waves 1–3 deliver eight of the ten tools
for a fraction of the risk.

### Wave 5 — save_project (needs a decision)

`save_project` is the only one requiring genuinely different code, because 8.1 has no
`PlatformRpcInstances`. The mapping already exists on the branch — `merge_gateway_changes` uses
`GatewayConnectionManager.getInstance().gatewayInterface` — and the resource-model translation is
established across the six ported designer tools:

| `main` (8.3) | 8.1 equivalent |
| --- | --- |
| `PlatformRpcInstances.PROJECTS_RPC.push(changes)` | `GatewayConnectionManager.getInstance().gatewayInterface.pushProject(List<ChangeOperation>)` |
| `common.resourcecollection.ResourcePath` / `Resource` / `ResourceType` | `common.project.resource.ResourcePath` / `ProjectResource` / `ResourceType` |
| `common.resourcecollection.ChangeOperation` | `common.project.ChangeOperation` |

**Open decision.** `main` calls `PROJECTS_RPC.canSaveProject(projectName)` as a pre-flight check
before pushing. 8.1.43's `GatewayInterface` exposes `pushProject` and `pullProject` and no
equivalent permission probe. Three options, in my order of preference:

1. **Drop the pre-flight and let `pushProject` fail**, reporting its exception. Honest, simplest,
   and the failure is surfaced either way — but the error will be less specific than on 8.3.
2. **Keep `-Dmcp.designer.allowSave` as the only gate.** The flag is already opt-in and off by
   default, so the practical exposure is unchanged; this just accepts a coarser check.
3. **Probe some other 8.1 permission API.** Only worth it if one exists that actually reflects
   project-save rights; I did not find one.

Whichever is chosen, the 8.1 `save_project` description must state that it does not pre-check
permissions, since the 8.3 tool reference is what users will read.

## Verification for every wave

1. `./gradlew clean :modules:mcp:build` on the 8.1 branch with the pin at **8.1.43** — never 8.1.54,
   or a missing API will not be caught.
2. Confirm the built descriptor still reads `<requiredIgnitionVersion>8.1.43</requiredIgnitionVersion>`.
3. Exercise the new tools against a real 8.1 gateway. The compiler proves symbols exist; it proves
   nothing about behaviour, and the tag and project-save paths are exactly where 8.1 and 8.3 can
   diverge quietly.
4. Update the tool count in the 8.1 `README.md` (currently 46) and the divergence table in
   `docs/modules/mcp/versions.md` on `main` in the same commit. Both were wrong for two releases
   because this step was skipped.

## Not in scope

`scan_resource_files` with `target: config` stays unavailable on 8.1. That one is a real platform
boundary — 8.1 keeps gateway config in `config.idb` rather than on disk — and no port changes it.
With the ten above done, it is the only remaining difference in tool surface between the lines.
