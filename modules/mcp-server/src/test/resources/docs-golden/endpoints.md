
# Published endpoints

An API serves one exact version of a pipeline at a stable URL —
`/api/<category>/<version>/<path…>` — so an application fetches rows with one `GET` and no
event handling. The route's HTTP **method is half of its identity** (#1037), immutable like the
path: `GET` by default, or `POST`/`PUT`/`DELETE` for a mutation route — a `GET` and a `POST` at
one pattern are two routes. The category is YOUR namespace (a business domain, a team);
`v<number>` and `api` are reserved to the product and refused with `endpoint.path_reserved`, as
is a path that could match an `executions/{id}` or `executions/{id}/result` polling suffix. The
version is one free-form segment (`v1` by convention); path variables come after it. Three
steps: create, bind a key, call it.

```
endpoints_create {"path": "/finance/v1/revenue/{region}", "pipeline": "revenue_by_region",
                  "pipeline_version": 3, "timeout_seconds": 60, "method": "GET"}
→ url: /api/finance/v1/revenue/{region}
```

`endpoints_create` creates the API's first DRAFT version pinned to the exact `pipeline_version`
given. Creation is an authoring operation: a deployment with authoring disabled refuses it
(`endpoint.authoring_disabled`); there is no release to perform there. In an authoring-enabled
deployment the draft is the entity's current version and serves under the normal runtime
checks — the entity is enabled, the caller's key is bound, and the pinned pipeline version is
eligible. The pin never moves when the pipeline's current version moves.

A route of the SAME method that could match the same URL as an existing one is refused
(`endpoint.path_conflict`), never resolved by precedence; another workspace's route at a
matching pattern is refused the same way with a redacted body naming nothing but your own
pattern. A mutation route serves once the deployment's operator enables mutations; until
then a fresh call answers `503 endpoint.mutations_disabled`.

The stored form is always the part after `/api`: write the path without the prefix (one
prefix, if present, is stripped — never stored).

Then an **admin creates an API key for it and associates it** — you never mint keys (there is
no key-minting MCP tool, on purpose: a minted key is a live credential and a tool result
travels through your context and transcript; and key creation is the workspace admin's
`api_key.create`, not yours). The admin does it on the `/api-keys` page — create, then "Edit
associations". The key's role is `api_caller`: it serves the paths bound to it, reads the runs
it started, and acts as its OWN identity, so its runs are attributed to the key rather than to
the admin who created it. The plaintext is shown once. The call below is the APPLICATION's, not
yours: REST is for programs and people, an agent never holds an `endpoint` key (role `api_caller`),
and its own MCP key is refused on every REST route (`endpoint.key_kind_refused`). The program that
holds the key then calls:

```bash
curl -s http://localhost:8080/api/finance/v1/revenue/EMEA -H "DP-API-Key: dpk_..."
```

The `200` body is the `data_ready` payload you already know from `pipelines_execute`:
`execution_id`, `schema`, `rows`, `row_count`, `total_rows`, `has_more`, `result_url`,
`expires_at`, `ttl_seconds`. `DP-Result-Page-Rows` sizes the inline page.

**Only side-effect-free pipelines can serve GET.** Every node must be `DQL` into tempdb or
the caller (or `CALCULATOR`), a `DML`/`DDL` node whose `source` is `tempdb`, transitively
through `PIPELINE` nodes. `tempdb` is the execution's own in-memory H2, created for the run
and discarded with it — a `CREATE INDEX` or similar statement there cannot outlive the
request, so it does not make `GET` unsafe. A `DML`/`DDL` node against a registered datasource
— or a DQL node writing back to a datasource, which is a write wearing a read's type — is
refused with `endpoint.pipeline_not_readonly` naming the node and the datasource. This is not
a formality: `GET` is retried on timeout, preloaded by browsers and followed by crawlers, so a
write behind one of these URLs would happen repeatedly and unbidden. A mutation route's pin
admits a writing pipeline by design — the read-only rule is the GET rule. It is checked when
the API is created and re-checked on every serve of the exact pipeline version it pins.

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

**Keys are bound to tree NODES for GET, and to one route for a mutation.** A key bound
at `/finance` authorises everything beneath it — until some node deeper down carries its own
binding, which then decides for that subtree alone. A mutation grant names exactly ONE route
entity (`method` + pattern): a sibling method at the same pattern, or a recreated route at the
same one, is NOT covered — the grant follows the entity. **An endpoint with no grant for the
caller's method cannot be called**: serving is by key only, so ask an admin to bind one. An
`endpoint` key with no binding authorises nothing at all. An
endpoint key reaches published endpoints and the status and cursor of executions it started,
and nothing else: not `/mcp`, and no other `/api/v1` route.

`endpoints_list`, `endpoints_get` and `endpoints_delete` complete the surface — each takes the
route's `method` where it matters (absent means `GET`). Deleting an
endpoint stops the URL answering that method and leaves the pipeline untouched; a mutation
grant on the route goes with it, GET node grants stay. Only a draft-only API is
deleted: one that holds a RELEASED or DISCARDED version is refused
`endpoint.version.last_release` and stays whole — say so, never retry. A row listed with
`"legacy": true` is a stored path saved before the current three-segment grammar: it is retired
— it never serves and never blocks a publish — and unpublishing it (`endpoints_delete`) is the
fix.

**A mutation call carries an `Idempotency-Key`, and a retry reuses it.** Every `POST`, `PUT`
and `DELETE` to a published route — a bodyless `DELETE` too — sends `Idempotency-Key` (1–128 of
`A–Z a–z 0–9 . _ : -`); without one it is a `400` before anything runs. The same key with the
same input, from any replica, is answered from the ONE original execution — `202` while it
runs, then its stored answer — and never runs again; the same key with different input is
`409 idempotency.key_reused_for_different_request`. Input is compared as the API's original
version types it: `quantity=01` and `quantity=1` on an `INTEGER` are the same request. A
retry still needs an `Accept` that admits JSON (else `406`). A deliberate second write takes a NEW key.
A run that ended `FAILED`, `ABORTED` or `OUTCOME_UNKNOWN` answers `409` with its code and a
`status_url`: an unknown outcome means the target may hold the write — reconcile, never resend.
`503 idempotency.reservation_unavailable` ran nothing; retry with the same key. The answer's
`status_url` and `result_url` poll the run under the same business path, with the key that
started it and the grant it was started under — a write-only key needs no `GET` grant for it.
Write a `PUT` or `DELETE` pipeline so repeating the request has the same intended effect: the
platform deduplicates only within one key's replay window.

## Common mistakes and references

Treating a `202` as a failure and retrying into a second execution; expecting an API to follow
the pipeline's newer versions (it serves the exact `pipeline_version` it pins — a new pin is a
new API version, made by a person in the UI); omitting `pipeline_version` (refused); trying to
mint a key yourself — there is no tool for it; ask an admin; sending a mutation without an
`Idempotency-Key`, or retrying one under a fresh key (that is a second write).

- **`endpoints-tools`** — the area's tools, generated from their shipped descriptions.
- **`executions`** — the runs an endpoint's key started, and the result cursor.
