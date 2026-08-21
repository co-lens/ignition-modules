---
title: Issue a credential
sidebar_position: 3
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Issue a credential

[Quickstart](./quickstart.md) skips this by running a local gateway with `-Dmcp.devMode=true`, which
serves both endpoints with no credential at all — on **either** platform line. That is right for a
container you can delete and wrong for anything else. This page is the real thing.

Start read-only. A write credential exposes `run_script` — arbitrary Jython in gateway scope, which
is gateway root. See [Endpoints and security](./endpoints.md).

<Tabs groupId="ignition-version" queryString>
<TabItem value="83" label="Ignition 8.3" default>

## Create an API key {#api-key}

**Platform → Security → API Keys** (older 8.3 builds label it Config → Security → API Tokens). A
default key — security level `Authenticated`, no extra permissions — is all the read-only endpoint
needs. The credential is `<keyId>:<secret>`, and it is **shown once, at creation**; there is no way
to read it back afterwards, because only a hash is stored.

Send it as a header:

```bash
claude mcp add --transport http ignition \
  http://<gateway>:8088/data/mcp/mcp-readonly \
  --header "X-Ignition-API-Token: <keyId>:<secret>"
```

:::warning The gotcha that costs everyone an hour
New keys default to **Require secure connections for API Keys**, which makes them fail with 401
over plain HTTP no matter what else is correct. Use HTTPS, or untick that box for a local gateway.
:::

## Writing needs a custom security level {#write-permission}

You cannot get write access by ticking `Administrator`.

A default key reaches the read-only endpoint and **only** that. The write endpoint checks the
gateway's **write** permission, which a freshly-commissioned 8.3 ships as
`AnyOf[Authenticated/Roles/Administrator]` — and a key carrying only `Authenticated` does not
satisfy it.

The `Administrator` checkbox is visible in the key's security-level tree and does nothing: 8.3
ignores `Authenticated/Roles` and `SecurityZones` levels granted to an API key, because those are
system-generated levels a user is not allowed to assign. Editing `securityLevels` in the key's
`config.json` on disk does not work either, for the same reason. So on a default gateway **no API
key can ever reach the write endpoint** until you add a level of your own. Three steps, all in the
gateway UI:

1. **Platform → Security → Levels** — select `Authenticated`, **Add Level**, name it (e.g.
   `McpWrite`), **Save Changes**. Its path becomes `Authenticated/McpWrite`.
2. **Platform → Security → General Settings → Roles & Permissions** — under **Gateway Write
   Permissions**, tick your new level and **Save Changes**. Leave the mode on *at least one of*, so
   `Administrator` keeps working for human users.
3. **Platform → Security → API Keys** — create the key (or **⋮ → Edit** an existing one) and tick
   the new level under `Authenticated`.

Verified on 8.3.7. This widens gateway-wide write permission, not just MCP's: anything else
checking `writePermissions` now also accepts that level, so grant it only to keys that should have
it.

Skipping this produces a symptom pair specific enough to be diagnostic — **200 on
`/data/mcp/mcp-readonly`, 403 on `/data/mcp/mcp`** — which reads like a module fault when it is a
permissions one. See [403 on `/data/mcp/mcp` while `/mcp-readonly`
works](./troubleshooting.md#write-403).

</TabItem>
<TabItem value="81" label="Ignition 8.1">

## Set a shared secret {#shared-secret}

8.1 has no API tokens, so the credential is a shared secret set as a JVM argument in
`ignition.conf`, followed by a gateway restart:

```
wrapper.java.additional.9=-Dmcp.gateway.readSecret=<32+ random characters>
```

Generate one with `openssl rand -hex 24`. Send it as `Authorization: Bearer <readSecret>`.

Add `-Dmcp.gateway.writeSecret=...` as well only if you need write access.

:::danger Set only readSecret unless you need writes
The write secret grants `run_script` — arbitrary Jython in gateway scope, which is gateway root.
These secrets are visible in the process table, are readable through `jvm_health`, and are not
revocable without a restart. See [version differences](./versions.md).
:::

If neither is set, both endpoints reject everything with 401 and the gateway log says so.

</TabItem>
</Tabs>

## The Designer bridge — optional {#designer}

Everything above is the **gateway**. The Designer bridge is a separate endpoint in a separate
process, it behaves the same on both platform lines, and **it requires no credential by default**.

That is deliberate. It binds to loopback, and the per-session secret it used to mint was regenerated
on every Designer start — so a saved client entry broke on the next restart, and it protected
nothing a same-UID attacker did not already have, since the discovery file holding it was readable
by that user anyway.

Set one when the default assumption stops holding:

```
-Dmcp.designer.secret=<32+ random characters>
```

Generate one with `openssl rand -hex 24`, and set it on the **Designer's** JVM — the gateway's
configuration cannot reach it. Two cases call for it:

- **You widened the bind.** `-Dmcp.designer.bindAddress` without a secret leaves the project
  readable and editable by anything that can route to the machine. The Designer logs a WARN naming
  the property, and an ERROR if [`allowSave`](./designer-save.md) is on as well.
- **You are on a shared machine.** Loopback is not per-user. On an RDS host or a jump box another
  signed-in user cannot read your discovery file, but can still reach your port. Without a secret,
  "same user" becomes "same machine".

Like the 8.1 gateway secrets, it is visible in the process table to any local user, and changing it
needs a Designer restart. Unlike them, no tool reads it back out through the endpoint. A shorter
value than 32 characters is warned about but still honoured — it is your call.

`-Dmcp.devMode=true` does **not** override it: a secret you pin is enforced regardless. Dev mode does
turn off Origin checking on the Designer, which is what otherwise keeps a web page away from a
credential-less bridge.
