# Ignition MCP — Ignition 8.1 line

> **This branch is a time-limited port.** It exists for people still on the Ignition 8.1 platform
> line. It **never merges into `main`**; it receives security fixes, wrong-data bug fixes, and — by
> explicit exception — changes needed to keep the tool surface identical to the 8.3 line. It is
> **scheduled for deletion around February 2027**. After that, published releases keep
> working but nothing further is built.
>
> The 8.3 line — which is the maintained one — is on
> [`main`](https://github.com/co-lens/ignition-modules), with full documentation at
> <https://co-lens.github.io/ignition-modules/>.

A [Model Context Protocol](https://modelcontextprotocol.io) server that runs inside Ignition, as a
Kotlin module. 53 tools: the 8.3 line's 56, less three not yet ported. The other difference that
matters is authentication. Both are covered under [Differences](#differences-from-the-83-line).

**Requires Ignition 8.1.43+ and Perspective** (see [Differences](#differences-from-the-83-line)).

## Authentication — read this first

**Ignition 8.1 has no API tokens.** Not a different API: none at all. So this line authenticates
with two shared bearer secrets set as JVM arguments in `ignition.conf`:

```
wrapper.java.additional.9=-Dmcp.gateway.readSecret=<32+ random characters>
wrapper.java.additional.10=-Dmcp.gateway.writeSecret=<32+ random characters>
```

| Property | Opens |
| --- | --- |
| `mcp.gateway.readSecret` | `POST /data/mcp/mcp-readonly` — the read-only tools |
| `mcp.gateway.writeSecret` | `POST /data/mcp/mcp` — everything, **and** the read-only endpoint |

Clients send `Authorization: Bearer <secret>` — not the `X-Ignition-API-Token` header the 8.3 line
uses.

> [!WARNING]
> This is materially weaker than 8.3's API tokens, and you should know exactly how:
>
> - **Not revocable** without restarting the gateway.
> - **Visible** in the process table and on the gateway's own status page to anyone who can log in.
> - **Shared** by every client, rather than issued per client.
> - The write secret grants `run_script` — arbitrary Jython in gateway scope. **That is gateway
>   root.**
>
> **Recommended posture: set `readSecret` only** and leave the write endpoint closed. If neither is
> set, both endpoints reject everything with 401 and the gateway log carries an ERROR saying so.

## Quickstart

1. **Get the module** from the
   [releases](https://github.com/co-lens/ignition-modules/releases) — assets on this line are named
   `Ignition-MCP-81-<version>.modl`, tagged `mcp81-v*`. Do not use `Ignition-MCP-<version>.modl`;
   that is the 8.3 build and an 8.1 gateway will refuse it.
2. **Install it** from the gateway's Config → Modules page.
3. **Set `-Dmcp.gateway.readSecret`** in `ignition.conf` and restart. Check it took:
   ```bash
   curl -s http://<gateway>:8088/data/mcp/health
   # {"status":"ok","platform":"8.1","authConfigured":true,"writeEndpointEnabled":false,...}
   ```
4. **Connect:**
   ```bash
   claude mcp add --transport http ignition \
     http://<gateway>:8088/data/mcp/mcp-readonly \
     --header "Authorization: Bearer <readSecret>"
   ```

The Designer bridge is unchanged from the 8.3 line — it runs its own loopback server with a
per-session secret and never touched Ignition's auth. Use **Tools → MCP Connection Info…**.

## Differences from the 8.3 line

| | 8.3 (`main`) | 8.1 (here) |
| --- | --- | --- |
| Auth | API tokens, per-token, revocable in the UI | two shared JVM-arg secrets |
| Auth header | `X-Ignition-API-Token` | `Authorization: Bearer` |
| Perspective | optional — module loads without it | **required** — module will not install without it |
| `scan_resource_files` `target: config` | supported | unavailable — 8.1 keeps gateway config in `config.idb`, not on disk |
| Asset name | `Ignition-MCP-<v>.modl` | `Ignition-MCP-81-<v>.modl` |
| Release tag | `mcp-v*` | `mcp81-v*` |
| Tool count | 56 | 53 — the three below are absent |

**Why Perspective is required here.** 8.1's `ModuleInfoParser` has no `required` attribute on
`<depends>`, so optional module dependencies don't exist on this platform line. Dropping the
dependency instead would cost classloader visibility of Perspective's classes and take all 19
Perspective tools with it.

**Three tools on the 8.3 line are absent here**, all added to `main` after this branch forked:

| Group | Tools |
| --- | --- |
| Perspective performance | `perspective_analyze_performance`, `perspective_session_performance` |
| Designer | `save_project` |

Two waves of that port have landed: the JVM performance tools — `jvm_health`, `thread_dump`,
`thread_hotspots` — as of 0.2.2, and the tag configuration tools — `configure_tags`,
`delete_tags`, `rename_tag`, `import_tags` — after it.

None of the remaining three is blocked by the 8.1 platform — every SDK API they need exists in
8.1.43 with the same signatures. They are absent because nobody has ported them yet, and porting
them is explicitly in scope for this branch under the tool-surface-parity exception at the top of
this file.

For the 53 tools that *are* here, the names, arguments and behaviour are identical to the 8.3 line,
and the [tool reference](https://co-lens.github.io/ignition-modules/modules/mcp/tools) on the 8.3
docs site is accurate for them; its endpoint/auth pages are not.

## Build

```bash
./gradlew clean :modules:mcp:build      # -> modules/mcp/build/Ignition-MCP-81.unsigned.modl
```

Signing, the trial watchdog and everything else work as documented on the
[8.3 site](https://co-lens.github.io/ignition-modules/modules/mcp/contributing/building).

> [!NOTE]
> **Signing behaves the opposite way to 8.3 here.** On 8.1 a module signed with an unknown
> (self-signed) certificate is quarantined — "certificate not yet accepted" — until an operator
> approves it in the gateway UI, while an *unsigned* module loads immediately under
> `-Dignition.allowunsignedmodules=true`. So the dev compose file mounts the **unsigned** build.
> Release builds carry the real release certificate and install normally.

Dev gateway on **18188** (18088 is the 8.3 line's):

```bash
docker compose -f docker/docker-compose.yml up -d
```

`docker/commission.sh` is kept for parity with the 8.3 line but is a no-op here — 8.1 has no
commissioning servlet at all.

The compose file sets an explicit project name; without it, `docker compose up` here would adopt
and destroy the 8.3 container.

## Releasing

Edit `modules/mcp/VERSION`, commit, push. The workflow builds, signs, tags `mcp81-v<version>` and
publishes.

It is triggered by a **push to this branch, not by a tag** — deliberately. A tag keeps its
commit's workflow tree alive, so a tag-triggered release would still fire after this branch is
deleted. A branch trigger makes deleting the branch a complete off-switch.

## Removed relative to `main`, and why

Don't restore these — each one actively breaks on this branch:

- **`docs/`** (the Docusaurus site) — deploys only from `main`, and its generated tool reference
  comes from `tools/tool-docs`, which is also gone.
- **`tools/tool-docs`** — declares the Ignition SDK as real runtime dependencies and constructs the
  tool classes, so it carries more 8.1 API surface to port than the module itself.
- **`docs-test.yml`, `tool-reference.yml`** — both would fire on PRs here and fail against a tree
  that no longer contains `docs/` or `tools/`.
- **`docs-deploy.yml`** — cannot fire, but claims the repo-global `concurrency: pages` group.
- **`.github/dependabot.yml`** — read only from the default branch, so it was inert. The
  consequence is intended: **this branch gets no automated dependency updates.**

## When this branch is deleted

1. `git push origin --delete 8.1/main` — the only load-bearing step. Every CI trigger for this
   line stops immediately, because the trigger *is* the branch.
2. On `main`, `git revert 4394149` — the commit that added the pointers to `main`'s README and
   docs site.
3. `docker compose -p ignition-mcp-81 down -v`.
4. **Keep the releases and tags.** Deleting a release breaks every download URL someone has
   scripted, and GitHub issues no redirect. Annotate them "unsupported since 2027-02" instead.

What survives: every release asset at its permanent URL, and every `mcp81-v*` tag — `git checkout
mcp81-v0.1.0` still yields this whole tree. **The tags are the archive; the branch is not.**

## Licence

MIT. See [LICENSE](LICENSE).
