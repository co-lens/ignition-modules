# Gateway testing: 8.3.7 and 8.1.43

Two throwaway gateways pinned to the exact minimum version each line claims to support, and the
manual pass that runs against them. It exists because everything here is behaviour no unit test
reaches: the suites were green through all four bugs this pass caught, because all four only appear
against a running gateway.

**Run this before cutting a release on either line.** It was first run on 2026-08-11 — see
[Result](#result-2026-08-11) — and the recipes are written to be repeatable rather than as a record
of that run. The containers are disposable; bring them up with the compose files below.

Neither shares anything with the other Ignition containers on this machine, which are left alone.

## What it runs against

| | 8.3 line | 8.1 line |
| --- | --- | --- |
| Image | `inductiveautomation/ignition:8.3.7` | `inductiveautomation/ignition:8.1.43` |
| Container | `mcp-test-837-ignition-gateway-1` | `mcp-test-8143-ignition-gateway-1` |
| Compose project | `mcp-test-837` | `mcp-test-8143` |
| Gateway | http://localhost:18300 | http://localhost:18400 |
| JVM debug port | 18301 | 18401 |
| Admin | `admin` / `password` | `admin` / `password` |
| Module source | this checkout, `modules/mcp/build/` | `/home/nate/src/mcp-ignition-81`, a worktree of `8.1/main` |
| Module version | `0.1.0-SNAPSHOT`, floor 8.3.7 | `0.1.0-SNAPSHOT`, floor 8.1.43 |

Compose files are in `docker/testing/`. The 8.1 build lives in a separate worktree because
`8.1/main` never merges into `main`.

```bash
# rebuild + reload, 8.3
./gradlew :modules:mcp:build
docker compose -f docker/testing/docker-compose.8.3.7.yml restart

# rebuild + reload, 8.1
cd /home/nate/src/mcp-ignition-81 && ./gradlew :modules:mcp:build
docker compose -f docker/testing/docker-compose.8.1.43.yml restart
```

> **`restart`, never `up -d`.** Neither gateway persists anything. Recreating the container takes
> the projects, tags, API tokens and users with it.

## Already proven by these two being up

Don't re-test these; they were the point of the pinned images and they passed at boot.

- **The 8.3 module installs on 8.3.7.** It declared 8.3.8 until `cc72c56`, and Ignition refuses a
  module whose `requiredIgnitionVersion` exceeds the gateway. It loaded here.
- **The 8.1 module installs on 8.1.43.** Same bug, same fix, `mcp81-v0.2.1`.
- **Both endpoints reject anonymous callers** — `HTTP 401` with no credential on both, which is
  `decb351` on 8.3 and `BearerAccessControl` failing closed on 8.1.
- **8.1 answers `tools/list` with 27 read-only tools** under the read secret.

## Before you start on 8.3.7

The 8.1 gateway needs nothing — its secrets are JVM args, already set:

```
read : test-read-0123456789abcdef0123456789abcdef
write: test-write-0123456789abcdef0123456789abcdef
```

**8.3.7 needs nothing either, now.** Its compose file passes `-Dmcp.devMode=true`, which opens both
endpoints, so there is no API key to create and no `McpWrite` security level to wire up. That also
means a container *recreate* costs nothing here any more.

```bash
call() {  # call <tool> <json-args>
  curl -s --max-time 60 -X POST http://localhost:18300/data/mcp/mcp \
    -H 'Content-Type: application/json' \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
    | python3 -m json.tool
}
```

<details>
<summary>The token procedure, for testing the <em>authenticated</em> path deliberately</summary>

Drop `-Dmcp.devMode=true` from `docker/testing/docker-compose.8.3.7.yml` and restart. The
Administrator role **cannot** be granted to an API key — 8.3 ignores `Authenticated/Roles` levels on
keys, so a default gateway's write endpoint is unreachable by any key (read 200, write 403). The
working procedure, verified on 8.3.7:

1. **Platform → Security → Levels**: select `Authenticated` → Add Level → `McpWrite` → Save.
2. **Platform → Security → General Settings → Roles & Permissions**: tick `McpWrite` under
   *Gateway Write Permissions* → Save.
3. **Platform → Security → API Keys → Create API Key**: untick *Require secure connections*,
   tick `McpWrite`. The secret shows once.

Then add `-H "X-Ignition-API-Token: $TOK"` back to `call()`.

</details>

The 8.1 equivalent, which needs no setup:

```bash
call81() {
  curl -s --max-time 60 -X POST http://localhost:18400/data/mcp/mcp \
    -H 'Content-Type: application/json' \
    -H 'Authorization: Bearer test-write-0123456789abcdef0123456789abcdef' \
    -d "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"$1\",\"arguments\":$2}}" \
    | python3 -m json.tool
}
```

> ⚠️ **The 8.3.7 gateway may re-prompt for commissioning.** The module is unsigned, so there is no
> certificate fingerprint for the gateway to remember — `certFingerprint` is `""` in `modules.json`.
> If it does, open http://localhost:18300 and click **Trust → Trust Unsigned Module → Finish Setup →
> Start Gateway**. 8.1 does not do this; there, unsigned modules load straight through.
>
> It did **not** re-prompt at any point on 2026-08-11, across a `start` from exited and two
> `restart`s including a `.modl` swap — the gateway reached `RUNNING` unattended each time. So treat
> it as a possibility to check (`curl -s localhost:18300/StatusPing`), not a step to plan around.

---

# The tests

Ordered by risk. Stop at the end of §2 if time is short — that is where the unverified behaviour is
concentrated.

## 1. Tag configuration — the highest-risk area

Wave 2 ported these to 8.1 and they have never run anywhere. Run each on **both** gateways.

### 1a. The `Abort` collision reports `written: 0`

This is the bug `56acfd3` fixed: `written` counted tags *offered*, not tags written, so a total
refusal came back looking like complete success.

```bash
call configure_tags '{"parentPath":"[default]","tags":[{"name":"TestPump","tagType":"AtomicTag","dataType":"Float8","value":1.0}]}'
# expect: written 1, attempted 1

call configure_tags '{"parentPath":"[default]","tags":[{"name":"TestPump","tagType":"AtomicTag","dataType":"Float8","value":2.0}],"collisionPolicy":"Abort"}'
# expect: written 0, attempted 1, results[0].ok false
```

**Fail if** the second call reports `written: 1`.

### 1b. UDT export → delete → import round trip

The docs claim the persisted file is byte-identical afterwards. Verified on 8.3.8, never on either
floor version.

```bash
call get_tag_config '{"paths":["[default]TestPump"],"recursive":true}'   # keep the output
call delete_tags    '{"paths":["[default]TestPump"]}'
call browse_tags    '{"path":"[default]"}'                               # confirm it is gone
call import_tags    '{"parentPath":"[default]","json":"<the export, unchanged>"}'
call get_tag_config '{"paths":["[default]TestPump"],"recursive":true}'   # compare
```

**Fail if** the two `get_tag_config` outputs differ, or `imported` is 0 while `findings` is empty —
that combination means the payload died in Ignition's parser rather than in validation.

### 1c. `rename_tag` refuses a collision

```bash
call rename_tag '{"path":"[default]TestPump","newName":"TestPump2"}'   # expect ok true
call configure_tags '{"parentPath":"[default]","tags":[{"name":"TestPump","tagType":"AtomicTag","dataType":"Float8"}]}'
call rename_tag '{"path":"[default]TestPump","newName":"TestPump2"}'   # expect ok false, Abort
```

**Also check the config survives** — `ok` alone does not cover it, which is exactly how this got
shipped broken on both lines:

```bash
call configure_tags '{"parentPath":"[default]","tags":[{"name":"Renamer","tagType":"AtomicTag","dataType":"Float8","value":3.5,"engHigh":50.0,"tooltip":"rename probe"}]}'
call get_tag_config '{"paths":["[default]Renamer"]}'                   # keep this
call rename_tag     '{"path":"[default]Renamer","newName":"Renamed"}'  # reports ok true
call get_tag_config '{"paths":["[default]Renamed"]}'                   # compare
```

**Fail if** the second `get_tag_config` has lost `dataType`, `value`, `tooltip` or `engHigh`.

This was broken on both lines and is now **fixed** — keep it as the regression guard.
`BasicTagConfiguration.createRename` is `createEdit` plus a `Name` and nothing else, and saving
that replaced the tag rather than merging: everything but `name` and `tagType` was wiped,
`read_tags` returned `value: null`, and the call still reported `ok: true`. `rename_tag` now sends
the tag's own local configuration with the rename (`TagTools.renameConfig`). Also covered, since
the first fix attempt could plausibly have broken them: renaming a **folder** keeps its children,
and renaming a **UDT instance** keeps `typeId`, parameters and members.

### 1d. `write_tags` quality reporting

`17bfc2f` replaced a substring match on the quality *name* with `QualityCode.isGood`. Stock quality
codes agree either way, so this is a sanity check rather than a reproduction.

```bash
call write_tags '{"writes":[{"path":"[default]TestPump","value":42.5}]}'   # ok true
call write_tags '{"writes":[{"path":"[default]NoSuchTag","value":1}]}'     # ok false, not a crash
```

## 2. Perspective — the wave 4 consolidation

Wave 4 touched `ViewValidator` and `PerspectiveComponentCatalog`, both shared with the 19
Perspective tools that already worked. The unit tests cover the logic; nothing covers the Designer.

Launch a Designer against each **gateway** port — 18300 / 18400, *not* 18301 / 18401, which are the
JVM debug ports. Then through the Designer bridge (**Tools → MCP Connection Info…**):

1. `perspective_create_view` a new view.
2. `perspective_add_component`, `perspective_update_component`, `perspective_set_binding`,
   `perspective_delete_component` on it.
3. Open the view in the Designer and confirm it renders and the components behave.
4. `perspective_validate_view` — findings should have `severity` and `fix` fields populated
   (this is the consolidated `Finding` type). **A clean view returns `valid: true` with an empty
   array, so this proves nothing on its own** — force a finding first, e.g. `perspective_set_binding`
   with `"type":"notarealbindingtype"`, which yields an `unknown_binding_type` warning carrying both
   fields.
5. Confirm **no** `~/.ignition/mcp/backups/` directory is created. Pre-edit snapshots were removed
   from the 8.3 line — git is the recovery path now — so a backups directory appearing here means
   `SnapshotStore` came back from somewhere.

> **Setting a tooltip is not possible through these tools.** Tooltips live in `meta.tooltip`, and
> `perspective_update_component` writes only `props`, `position` and `name` (the last into
> `meta.name`). Writing `props.tooltip` is accepted silently and is not a tooltip — it is an unknown
> property, which 8.3's property editor displays and 8.1's does not.

> **Known divergence, not a bug to report:** 8.1 seeds default properties into new components,
> 8.3 writes only what you set. `perspective_add_component` and `perspective_create_view` will
> produce different files on the two lines. This predates the port work and is recorded in
> [version differences](docs/modules/mcp/versions.md).

## 3. Live session tools

Need a live session, which is why they were left for a real gateway. A session is cheap to get
without a Designer: map a page to the view in `page-config`, then open
`http://<gateway>/data/perspective/client/<project>`.

1. Open a Perspective session against the view from §2.
2. `call perspective_session_performance '{"includeViews":true}'`
3. **Expect** the session listed with `queueDepth`, uptime, and the view mounted — under
   `sessions[].pages[].views`, which only appears when `includeViews` is set.

### 3a. `perspective_list_sessions` agrees with it

```bash
call perspective_list_sessions '{}'          # count
call perspective_session_performance '{}'    # sessionCount
```

**Fail if** the two disagree. `list_sessions` reported `count: 0` against a gateway with a live
session on 8.1.43: it reads `getSessionInfos` through Perspective's Gson, every entry failed to
deserialize, and each was skipped with only a debug line. It now falls back to the live-session
enumeration. This matters because `perspective_diagnose_live_view` needs a session id and its own
error text sends you here to get one.

### 3b. `perspective_diagnose_live_view` reports real values

```bash
call perspective_diagnose_live_view '{"sessionId":"<id>"}'
```

**Expect** `value`, `quality`, `qualityGood` and `timestamp` populated — not null — for a bound
property. Then force a bad quality and confirm it is *detected*:

```bash
call configure_tags '{"parentPath":"[default]","tags":[{"name":"TestPump","tagType":"AtomicTag","dataType":"Float8","valueSource":"expr","expression":"forceQuality(42.5, 0)"}],"collisionPolicy":"MergeOverwrite"}'
call perspective_diagnose_live_view '{"sessionId":"<id>"}'   # expect badQualityCount 1
```

**Fail if** `badQualityCount` stays 0 while `read_tags` calls that tag `Bad`. All four runtime
fields were null on every property, on both lines: `PropertyTree.read` opens with
`ExecutionQueue.requireInQueue()`, so reading from a request thread always threw and the catch
recorded null. Since `badQualityCount` counts `qualityGood == false`, it could only ever report
zero — a view visibly showing a bad-quality overlay came back clean. The walk now runs on the
session's execution queue with a 5s bound.

## 4. `save_project` — the rewritten one

The only tool written rather than copied. It was written *for* 8.1, which has no `canSaveProject`
so failures surface as the gateway's own error — but it ships on **both** lines and both are worth
running.

`save_project`, `list_pending_changes` and `merge_gateway_changes` are **Designer-scope tools on the
bridge**, not on the gateway endpoint — `call81 list_pending_changes` cannot work. Use the bridge
address from **Tools → MCP Connection Info…**.

`-Dmcp.designer.allowSave=true` belongs on the **Designer**, not the gateway. Set every argument you
need in one value, since the launcher's field replaces rather than appends:

```
-Dmcp.designer.bindAddress=0.0.0.0;-Dmcp.designer.port=8770;-Dmcp.designer.allowSave=true
```

**Don't pin the port to run two Designers.** The default is OS-assigned and already handles two —
`-Dmcp.designer.port` exists for forwarding or firewalling, and the launcher hands the *same* value
to every Designer of that application. A second Designer asking for a taken port now warns and falls
back rather than dying, so if you do run two with the line above, expect this on the second one and
take its address from **Tools → MCP Connection Info…**:

```
WARN  mcp.Designer.Http -- Port 8770 (-Dmcp.designer.port) is already in use ... Fell back to
      OS-assigned port 41337.
```

**Fail if** the second Designer logs `java.net.BindException` and its connect dialog reports the
endpoint is not running — that is the regression this replaced.

Then, against the bridge:

```bash
des list_pending_changes '{}'
des save_project '{}'
des list_pending_changes '{}'   # expect 0
```

### The three directions

Worth holding in mind, because the refusal test depends on it:

| tool | direction |
| --- | --- |
| `scan_resource_files` | files → gateway |
| `merge_gateway_changes` | gateway → Designer |
| `save_project` | Designer → gateway |

### 4a. The refusal path

Stage an edit in the Designer, make a **conflicting** change to the same resource on the gateway,
and confirm `save_project` refuses and names the resource rather than silently winning. The staged
edit must survive.

Editing the resource on the gateway's disk is how to induce it — but on 8.3 **the gateway never
scans on its own**, so the edit is invisible until you call `scan_resource_files`. That is the whole
reason this looked untestable on 8.3 at first:

```bash
# 1. stage a Designer edit to MCP/Probe, then edit the same view.json on the gateway's disk
call scan_resource_files '{"target":"projects"}'   # gateway now sees the disk change
des save_project '{}'                              # expect refusal naming the resource
```

**`merge_gateway_changes` is not the way out of this**, despite what older builds' refusal text
said: it gates on the same conflict predicate, so it refuses too (confirmed with 30s/10s waits, so
not a timing artifact). Only a human saving or discarding in the Designer clears it.

The merge *does* work when the gateway's change lands on a **different** resource — scan, merge,
save then completes and the local edit survives the merge. Worth testing both.

## 5. 8.1 authentication

```bash
# read secret must not open the write endpoint
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:18400/data/mcp/mcp \
  -H 'Authorization: Bearer test-read-0123456789abcdef0123456789abcdef' \
  -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# expect 401

# write secret opens both
curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:18400/data/mcp/mcp-readonly \
  -H 'Authorization: Bearer test-write-0123456789abcdef0123456789abcdef' \
  -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}'
# expect 200
```

---

## Gotchas

- **Swapping the `.modl` can silently disable the module.** After a rebuild + restart, if tools go
  missing, check `onStartup` before debugging anything else:
  ```bash
  docker exec mcp-test-837-ignition-gateway-1 \
    grep -A3 colens /usr/local/bin/ignition/data/modules.json
  ```
  `"onStartup": "disabled"` means the swap disabled it, not that your code broke.

- **The 2-hour trial.** Both gateways run the trial watchdog (`-Dmcp.trialWatchdog=true`,
  10s interval), so they should reset themselves. If one stops mid-test, `reset_trial` is a tool.

- **`docker compose down` on these is fine** — they are throwaway. Doing it to any of the other
  gateways on this machine is not.

## Between passes: stop, don't down

`stop` is what you want after a pass. It keeps the containers, and with them everything that was
expensive to set up: the 8.3 API key, the `McpWrite` security level and the gateway write permission
that references it, the projects, and the tags. `start` picks up where you left off.

```bash
docker compose -f docker/testing/docker-compose.8.3.7.yml stop
docker compose -f docker/testing/docker-compose.8.1.43.yml stop
# later
docker compose -f docker/testing/docker-compose.8.3.7.yml start
```

`down` destroys the containers, and there is no data volume — so it throws all of that away and the
next pass starts by creating an API key through the gateway UI again, which needs an interactive
login. Do it only when you want a genuinely clean floor gateway.

## Tearing down for real

```bash
docker compose -f docker/testing/docker-compose.8.3.7.yml down
docker compose -f docker/testing/docker-compose.8.1.43.yml down
git worktree remove /home/nate/src/mcp-ignition-81   # only when done with the 8.1 line entirely
```

## Result, 2026-08-11 {#result-2026-08-11}

**Everything above passes on both gateways.** §1, §2, §2, §3, §4 and §5 all green on 8.1.43 and
8.3.7, including the parts that had never run anywhere: the pre-edit backups, the wave 4
Perspective consolidation, and `save_project` with its conflict refusal.

The pass found four bugs, all now fixed and re-verified on both lines:

| | fixed in |
| --- | --- |
| `rename_tag` wiped every property but `name`/`tagType`, reporting `ok: true` | `TagTools.renameConfig` |
| `perspective_diagnose_live_view` reported null value/quality on every property, so `badQualityCount` could only ever be 0 | `LiveSessionInspector.diagnoseView`, now on the session queue |
| `perspective_list_sessions` reported zero sessions against a live one | `LiveSessionInspector.listSessions`, live-session fallback |
| `save_project`'s refusal sent callers to `merge_gateway_changes`, which refuses on the same predicate | `DesignerTools` refusal text |

The first three were on **both** lines, not just 8.1. None were caught by the unit suites (302 tests
across the two repos, all passing before and after) because all three are SDK-interaction bugs that
only appear against a running gateway. That is the argument for this pass having existed.

Two loose ends, neither a test failure:

- `meta` is unreachable except through `name`, so tooltips cannot be set at all.

## What shipped

> The pre-edit backups recorded below were later **removed from the 8.3 line**: 8.3 keeps tags and
> project resources as files on disk, so version control covers the same ground and `SnapshotStore`
> could block an edit for a reason unrelated to the edit. 8.1 keeps them. The results below stand as
> what was true on 2026-08-11.

**`mcp-v0.3.3` and `mcp81-v0.2.6`, both on 2026-08-11**, carrying the pre-edit backups, wave 4/5 on
the 8.1 line, and the four fixes above. Those were the first releases gated on this pass.

Cutting them differs per line, which is easy to get backwards:

- **8.3** — push `main`, then push a `mcp-v<version>` tag. The version comes from the tag.
- **8.1** — bump `modules/mcp/VERSION`, then push `8.1/main`. The workflow triggers on the *branch
  push*, not a tag, and creates the tag itself. So any push to that branch attempts a release:
  bump the version in the same push, or it fails on "release already exists".

See [Releasing](docs/content/contributing/releasing.md).

`next.md` — the five-wave port plan this pass was verifying — was deleted when the pass finished,
as it said to. Its two facts that outlived it are in
[version differences](docs/modules/mcp/versions.md).
