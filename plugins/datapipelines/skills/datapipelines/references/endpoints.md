# Published endpoints

Open when a released read-only pipeline has to answer a plain HTTP GET.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

## Publishing a pipeline as a GET endpoint

A released, **read-only** pipeline can be served at a stable URL under `/api/x`, so an
application fetches rows with one `GET` and no event handling. Three steps: publish, bind a key,
call it.

```
endpoints_create {"path": "/nyc/revenue/{borough}", "pipeline": "revenue_by_borough",
                  "timeout_seconds": 60}
→ url: /api/x/nyc/revenue/{borough}
```

Then mint a key for it and bind it (REST or the UI — there is no key-minting MCP tool, on
purpose: a minted key is a live credential and a tool result travels through your context and
transcript):

```bash
curl -s http://localhost:8080/api/v1/auth/api-keys -X POST \
  -H "Content-Type: application/json" -H "DP-API-Key: dpk_..." \
  -d '{"name": "nyc-serving", "kind": "endpoint", "bindings": ["/nyc"]}'
# the plaintext key is in this response ONCE

curl -s http://localhost:8080/api/x/nyc/revenue/Manhattan -H "DP-API-Key: dpk_..."
```

The `200` body is the `data_ready` payload you already know from `pipelines_execute`:
`execution_id`, `schema`, `rows`, `row_count`, `total_rows`, `has_more`, `result_url`,
`expires_at`, `ttl_seconds`. `DP-Result-Page-Rows` sizes the inline page.

**Only side-effect-free pipelines can be published.** Every node must be `DQL` into tempdb or
the caller (or `CALCULATOR`), transitively through `PIPELINE` nodes. A `DML`/`DDL` node — or a
DQL node writing back to a datasource, which is a write wearing a read's type — is refused with
`endpoint.pipeline_not_readonly` naming the node. This is not a formality: `GET` is retried on
timeout, preloaded by browsers and followed by crawlers, so a write behind one of these URLs
would happen repeatedly and unbidden. The rule is re-checked on every serve, because an endpoint
pins a pipeline and serves its latest RELEASED version — a later release can change the body.

**An unknown query parameter is a `400`, on purpose.** `?start_dt=2024-01-01` on an endpoint
declaring `start_date` is refused with `endpoint.request.parameter_unknown` rather than ignored.
The execute body tolerates extra keys; this surface must not, because a typo that silently ran
the default would return plausible, wrong rows — and wrong rows that look right are worse than
an error. Every defect in a request comes back **together**, in one `400` whose
`details.errors[]` lists each `{parameter, code, message}`, so you fix the URL once.

**A slow pipeline answers `202`, not an error.** When the endpoint's `timeout_seconds` elapses
the execution is **not** cancelled — you get `202` with `{execution_id, result_url, status_url,
expires_at}` and come back to the cursor when it is ready (`GET /executions/{id}` reports
status; the cursor `404`s until the result exists). Do not treat a `202` as a failure and do not
retry it as a new run: the answer is already coming, and retrying starts a second execution.

**Keys are bound to tree NODES, and a deeper binding replaces a shallower one.** A key bound at
`/nyc` authorises everything beneath it — until some node deeper down carries its own binding,
which then decides for that subtree alone. An endpoint with no binding on any ancestor accepts
`user` keys of its workspace holding `execute`; an `endpoint` key with no binding authorises
nothing at all. An endpoint key reaches published endpoints and the cursor of executions it
started, and nothing else — not `/mcp`, not `/api/v1`.

`endpoints_list`, `endpoints_get` and `endpoints_delete` complete the surface. Deleting an
endpoint stops the URL answering and leaves the pipeline untouched.
