# Connecting: transport, keys, scopes, datasources

Open before your first call against a deployment, when a call is refused for scope or credential reasons, or when the client has no MCP transport.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

## Connecting

- **Which host?** The user's own deployment — this product is self-hosted, so there is no
  default endpoint and you must ask for one rather than assume `localhost`. If the user has
  no server yet, one command on a machine with Docker gives them a working one with sample
  data in it: `./app.sh --start --demo nyc` from a checkout. That deployment is
  `DATAPIPELINES_ENV=local` under the `development` posture, and `app.sh` prints the login
  that exists after it is healthy. A deployment their organisation runs will be named
  something else and may be `hardened`, which refuses authoring writes — see the
  `*.authoring.disabled` row in `references/error-codes.md`.

- **MCP:** Streamable HTTP at `POST {host}/mcp` — stateless, protocol pinned to
  `2025-06-18`. Auth is API-key-only: `DP-API-Key: dpk_<id>.<secret>` or
  `Authorization: Bearer dpk_<id>.<secret>`. Browser session cookies are rejected on
  `/mcp`. REST lives at `/api/v1/**` with `DP-`-prefixed custom headers and a JSON
  envelope (`{"data": ...}` / `{"error": {code, user_message, details}}`).

- **30 MCP tools:** `pipelines_list`, `pipelines_get`, `pipelines_execute`,
  `pipelines_execute_node`, `pipelines_create`, `pipelines_update`, `templates_list`,
  `templates_get`, `templates_used_by`, `templates_create`, `templates_render`,
  `datasources_list`, `datasources_get`, `datasources_test`,
  `datasources_get_schemas`, `datasources_get_tables`, `datasources_get_columns`,
  `datasources_preview_rows`, `executions_list`,
  `executions_get`, `executions_get_result`, `endpoints_create`, `endpoints_list`,
  `endpoints_get`, `endpoints_delete`, `calculators_list`, `calculators_get`,
  `lake_tables_register`, `lake_tables_import`, `lake_tables_unregister`.

- **3 prompts:** `analyze_pipeline` (read-only structural review of a pipeline),
  `create_pipeline_for_question` (ground a new pipeline's SQL in the introspection
  tools, then author it), `debug_failed_execution` (walk a failed execution to a
  diagnosis).

- **Which key you need, and why not `admin`.** Your credential is a **`user` key** — the kind
  the UI calls "Agent / API key" (one kind, two surfaces: MCP and REST). Ask for the LOWEST
  scope that covers what you were asked to do: `read` to inspect, `execute` to run, `author` to
  create or change. **Do not ask for `admin`.** No MCP tool requires it, so it buys you nothing
  you can use — and it turns a key that lives in a config file, a transcript and a client's logs
  into one that can manage users and workspaces. If a tool answers `auth.scope.insufficient`,
  name the ONE scope you need and why. The other two key kinds are not yours: an `endpoint` key
  serves published endpoints, a `server` key is one deployment's credential for another, and
  `/mcp` refuses both with `endpoint.key_kind_refused`.

- **Scopes** (hierarchical: `admin ⊃ author ⊃ execute ⊃ read`): `read` = list/get;
  `execute` = run; `author` = create/update pipelines + templates (also template render,
  datasource test, schema introspection, the three `lake_tables_*` dp-lake registry writes,
  and workspace-bound datasource mutation). Creating, updating and deleting a datasource are
  REST/UI only — **no credential travels through an agent** (094): ask a person to add the
  datasource in the UI, then use it by name. Mutating a GLOBAL datasource's lake registry needs
  `admin`. A tool or endpoint rejects with
  `auth.scope.insufficient` when the key's scope is too low.

- **You cannot register a datasource, and there is no tool that lets you.** **No credential
  travels through an agent.** A secret passed through you transits your context, your transcript
  and any logging the client does — a property of handing a secret to an agent, which no server
  can undo. So datasource create, update and delete are UI/REST-only. If the datasource you need
  does not exist, **ask the person to add it in the UI**, then read it back with
  `datasources_list`. (There was a `datasources_create` tool; it carried a warning in its own
  description, and a description is not a control, so it was removed.)

- **What you CAN do with a datasource:** `datasources_list` / `datasources_get` to find one,
  `datasources_test` to confirm it connects, `datasources_get_schemas` / `_get_tables` /
  `_get_columns` to read its shape, `datasources_preview_rows` for up to 50 rows of a table.
  That is everything authoring a pipeline needs. `datasources_get` also reports the connection
  POOL's effective settings (`pool`: each value with its unit and which layer supplied it), which
  is what a pool-timeout failure is usually explained by.

- **`credential.kind`, on the read side.** `datasources_get` reports WHAT a datasource
  authenticates with, never the secret: `password` (a login), `token` (a bearer or personal
  access token), `private_key` and `service_account_json` (in the contract for the warehouse
  connectors; no shipped dialect accepts them yet), and `none` — an IAM role, OS auth, or a FILE
  database with no authentication at all. It is the fact that explains an authentication failure
  you are asked to diagnose.

- **Namespaces, not just schemas.** `datasources_get_schemas` returns `entries: [{namespace,
  label}]`. `namespace` is the ordered path (outermost first) and `label` is its last segment. On
  a two-level engine — `catalog.schema`, `project.dataset` — two entries can share a label and
  differ only by their outer segment, so **pass the whole `namespace` array back** to
  `datasources_get_tables`/`_get_columns`, not the label. The legacy `schemas` array of bare
  labels and the `schema` argument still work; they cannot express the difference. An unqualified
  `datasources_get_columns` on a two-level engine can merge same-named tables from different
  catalogs, which is why the namespace is worth passing.

## Credential encryption and key providers

Three facts, and where to go for the rest:

- **Datasource credentials are write-only** — whatever kind they are. They are stored AES-256-GCM encrypted, bound to the
  datasource name, and no endpoint, tool or resource ever returns one — reads carry
  `password_set: true` instead. Never try to read a password back, and never echo one you were
  given into a pipeline body, a template, a commit message or a chat summary.
- **Every stored credential carries a key VERSION** (its first byte), so a deployment can rotate
  keys lazily: rows keep decrypting under the key they were written with, and move to the
  current key the next time their password is saved. The operator flow is
  `docs/datasources.md` §7.3 — there is deliberately no rotation endpoint or CLI to call.
- **Where the keys come from is a seam, not a constant.** `datapipelines.db.key-provider`
  selects a `KeyProvider`; `env` ships and is the default. Implementing an AWS/GCP/Azure/Vault
  provider is a written procedure with a shared contract suite every implementation must pass:
  **`docs/key-providers.md`**. If you are asked to "add KMS support", that document is the task
  — do not redesign the crypto.

## REST fallback (when the client has no MCP transport)

Same server, HTTP + JSON, authenticated with `-H "DP-API-Key: dpk_..."`:

```bash
curl -s http://localhost:8080/api/v1/pipelines                     # list
curl -s http://localhost:8080/api/v1/datasources -X POST           # register (admin)
  -H "Content-Type: application/json" -H "DP-API-Key: dpk_..." -d '{...}'
curl -s http://localhost:8080/api/v1/pipelines/{id}/execute -X POST # run (SSE stream)
  -H "Accept: text/event-stream" -H "DP-API-Key: dpk_..." -d '{"parameters": {}}'
curl -s http://localhost:8080/api/v1/executions/{id}/result?offset=0&limit=100
curl -s http://localhost:8080/api/v1/executions/{id} -X DELETE     # cancel
```

**Template addressing (rest-api v2.0):** a template name NEVER travels in a URL path
segment — a name may contain `/`, and an encoded `%2F` in the path is refused `400` by the
container before routing. Address templates by query parameter or body field instead:

```bash
curl -s "http://localhost:8080/api/v1/templates?name=acme/finance/report"        # one template
curl -s "http://localhost:8080/api/v1/templates/versions?name=acme/finance/report&version=1"
curl -s http://localhost:8080/api/v1/templates/render -X POST \
  -H "Content-Type: application/json" -H "DP-API-Key: dpk_..." \
  -d '{"name": "acme/finance/report", "version": 1, "context": {}}'
```

`GET /api/v1/templates` answers two shapes on one route: the single-resource envelope
(`404 template.not_found` on a miss) when `name` is present, the paged list when it is not.
`PUT /api/v1/templates` takes the `id` in the JSON body; release/draft-discard are
`POST /api/v1/templates/release` and `/draft/discard` with `{"name": ...}` in the body.

The execution endpoint answers with an SSE stream of events
(`execution_started`, `node_started`, `node_completed`, `pipeline_completed`,
`data_ready`, …) — the agent-facing MCP tool turns that into one blocking call with
`node_stats` in the result. Everything the MCP tools do is a thin adapter over these
endpoints; error codes are identical.
