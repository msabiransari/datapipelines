
# Published endpoints

A released, **read-only** pipeline can be served at a stable URL —
`/api/<category>/<version>/<path…>` — so an application fetches rows with one `GET` and no
event handling. The category is YOUR namespace (a business domain, a team); `v<number>` and
`api` are reserved to the product and refused with `endpoint.path_reserved`. The version is
one free-form segment (`v1` by convention); path variables come after it. Three steps:
publish, bind a key, call it.

```
endpoints_create {"path": "/finance/v1/revenue/{region}", "pipeline": "revenue_by_region",
                  "timeout_seconds": 60}
→ url: /api/finance/v1/revenue/{region}
```

The stored form is always the part after `/api`: write the path without the prefix (one
prefix, if present, is stripped — never stored).

Then an **admin creates an API key for it and associates it** — you never mint keys (there is
no key-minting MCP tool, on purpose: a minted key is a live credential and a tool result
travels through your context and transcript; and key creation is the workspace admin's
`api_key.create`, not yours). The admin does it on the `/api-keys` page — create, then "Edit
associations". The key's role is `api_caller`: it serves the paths bound to it, reads the runs
it started, and acts as its OWN identity, so its runs are attributed to the key rather than to
the admin who created it. The plaintext is shown once. The program that holds it then calls:

```bash
curl -s http://localhost:8080/api/finance/v1/revenue/EMEA -H "DP-API-Key: dpk_..."
```

The `200` body is the `data_ready` payload you already know from `pipelines_execute`:
`execution_id`, `schema`, `rows`, `row_count`, `total_rows`, `has_more`, `result_url`,
`expires_at`, `ttl_seconds`. `DP-Result-Page-Rows` sizes the inline page.

**Only side-effect-free pipelines can be published.** Every node must be `DQL` into tempdb or
the caller (or `CALCULATOR`), a `DML`/`DDL` node whose `source` is `tempdb`, transitively
through `PIPELINE` nodes. `tempdb` is the execution's own in-memory H2, created for the run
and discarded with it — a `CREATE INDEX` or similar statement there cannot outlive the
request, so it does not make `GET` unsafe. A `DML`/`DDL` node against a registered datasource
— or a DQL node writing back to a datasource, which is a write wearing a read's type — is
refused with `endpoint.pipeline_not_readonly` naming the node and the datasource. This is not
a formality: `GET` is retried on timeout, preloaded by browsers and followed by crawlers, so a
write behind one of these URLs would happen repeatedly and unbidden. The rule is re-checked on
every serve, because an endpoint pins a pipeline and serves its latest RELEASED version — a
later release can change the body.

**An unknown query parameter is a `400`, on purpose.** `?start_dt=2024-01-01` on an endpoint
declaring `start_date` is refused with `endpoint.request.parameter_unknown` rather than
ignored. The execute body tolerates extra keys; this surface must not, because a typo that
silently ran the default would return plausible, wrong rows — and wrong rows that look right
are worse than an error. Every defect in a request comes back **together**, in one `400` whose
`details.errors[]` lists each `{parameter, code, message}`, so you fix the URL once.

**A slow pipeline answers `202`, not an error.** When the endpoint's `timeout_seconds` elapses
the execution is **not** cancelled — you get `202` with `{execution_id, result_url,
status_url, expires_at}` and come back to the cursor when it is ready (`GET /executions/{id}`
reports status; the cursor `404`s until the result exists). Do not treat a `202` as a failure
and do not retry it as a new run: the answer is already coming, and retrying starts a second
execution.

**Keys are bound to tree NODES, and a deeper binding replaces a shallower one.** A key bound
at `/finance` authorises everything beneath it — until some node deeper down carries its own
binding, which then decides for that subtree alone. **An endpoint with no binding on any
ancestor cannot be called**: serving is by key only, and no key reaches an unbound path, so
ask an admin to bind one. An `endpoint` key with no binding authorises nothing at all. An
endpoint key reaches published endpoints and the status and cursor of executions it started,
and nothing else: not `/mcp`, and no other `/api/v1` route.

`endpoints_list`, `endpoints_get` and `endpoints_delete` complete the surface. Deleting an
endpoint stops the URL answering and leaves the pipeline untouched.

## Common mistakes and references

Treating a `202` as a failure and retrying into a second execution; publishing before the
pipeline is released (an endpoint serves the released version only); asking for a key you
could mint yourself — ask an admin instead.

- **`endpoints-tools`** — the area's tools, generated from their shipped descriptions.
- **`executions`** — the runs an endpoint's key started, and the result cursor.
