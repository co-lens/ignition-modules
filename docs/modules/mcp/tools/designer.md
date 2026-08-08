---
title: Designer tools
sidebar_position: 3
---

import ToolReference from '@site/src/components/ToolReference';

# Designer tools

Served on the Designer's loopback bridge — see [the Designer endpoint](../endpoints.md#designer)
for how to connect to it.

Every write here **stages** an unsaved Designer change rather than committing to the gateway. The
`destructive` badge means the staged edit is hard to undo, not that it reaches the gateway.

The one exception is `save_project`, which commits — it is registered only when the Designer was
started with `-Dmcp.designer.allowSave=true`, so it is absent unless somebody asked for it. See
[Saving from a Designer](../designer-save.md).

<ToolReference scope="designer" />
