---
title: MCP Inspector
sidebar_position: 2
---

# MCP Inspector

Catches spec violations that hand-written curl won't:

```bash
npx @modelcontextprotocol/inspector
# Streamable HTTP → http://localhost:18088/data/mcp/mcp
# header X-Ignition-API-Token: <keyId>:<secret>
```

The Inspector runs on `localhost`, which the server's `Origin` check allows. To permit a
non-loopback browser origin, start the gateway with
`-Dmcp.allowedOrigins=https://tools.example.com`.
