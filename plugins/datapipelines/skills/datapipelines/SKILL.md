---
name: datapipelines
description: Author, run and debug declarative SQL pipelines on a datapipelines.co deployment. Use when the user works with pipelines, templates, datasources or executions on their deployment's MCP server.
---

# datapipelines

This plugin ships no manual. The manual is served by your deployment, so what you read is
always what your server runs.

1. **Connect the MCP server** — the plugin's user config (deployment URL + MCP key) does it.
2. **Read the manual from the server:** call `docs_get {"name": "skill"}` first; `docs_list`
   lists the rest, one guide per functional area. The same manual is at
   `GET /skill.md` on your deployment and at `datapipelines://docs/skill`.

Guidance that matters at connect time is in the server's own instructions — they arrive with
the connection, under every client's description cap.
