---
title: Live diagnostics
sidebar_position: 4
---

# Live diagnostics

[`perspective_diagnose_live_view`](../tools/gateway.md#perspective_diagnose_live_view) walks a view
in a running session and reports every configured property with its binding **and its current value
and quality**.

Perspective surfaces binding failures as quality overlays rather than as errors, so a bad quality
sitting next to its binding config is usually the whole diagnosis — which is why this tool reports
the two together rather than making you correlate them yourself.

Only views a user currently has open are visible. Use
[`perspective_list_sessions`](../tools/gateway.md#perspective_list_sessions) to find a session id
first.

This is gateway-scope only: sessions run on the gateway, so a Designer has nothing to inspect.
