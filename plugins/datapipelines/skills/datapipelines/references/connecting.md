# Connecting: transport, keys, scopes, datasources

Open before your first call against a deployment, when a call is refused for scope or credential reasons, or when the client has no MCP transport.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

## Connecting

- **Which host?** The user's own deployment — this product is self-hosted, so there is no
  default endpoint and you must ask for one rather than assume `localhost`. If the user has
  no server yet, one command on a machine with Docker gives them a working one:
  `./app.sh --start` from a checkout (the deployment guide covers what it can load). That deployment is
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

- **Which key you need.** Your credential is a **`user` key** — the kind the UI calls
  "Agent / API key" (one kind, two surfaces: MCP and REST). Ask for the LOWEST scope that
  covers what you were asked to do: `read` to inspect, `execute` to run, `author` to create or
  change. **`admin` is not a scope a key can hold at all** — asking for one is refused with
  `auth.key_scope_unavailable`, because release, promotion and membership are human verbs. If
  a tool answers `auth.scope.insufficient`, name the ONE scope you need and why. The other two
  key kinds are not yours: an `endpoint` key serves published endpoints, a `server` key is one
  deployment's credential for another, and `/mcp` refuses both with
  `endpoint.key_kind_refused`.

- **Scopes** (hierarchical: `author ⊃ execute ⊃ read`): `read` = list/get; `execute` = run;
  `author` = create/update pipelines + templates (also template render, schema introspection
  and the three `lake_tables_*` dp-lake registry writes). Creating, updating and deleting a
  datasource are REST/UI only — **no credential travels through an agent** (094): ask a person
  to add the datasource in the UI, then use it by name. `datasources_test` DOES exist as a
  tool, but on the ROLE axis it needs `ws_admin`, so most keys get `auth.role_required` from
  it — testing opens a live connection with the stored credential and writes the datasource's
  health down, which is an operator's act, not a read.

- **Your scope is a ceiling, not a grant.** Every request is checked on TWO axes: the key's
  own scope, and the ROLE its ISSUER holds in the pinned workspace **right now**. So a key can
  do at most what the person who minted it can do today, and if their role changes the key
  starts refusing with `auth.key_issuer_role_lost` within about a minute. That one is not
  retryable at any scope — the fix is a new key from somebody who still holds the role.

- **A key whose issuer is now only a viewer can still read and run; it cannot author**
  (viewers never mint keys — this is the demoted-issuer case). `auth.role_required` means the
  ROLE is short, not the scope, so asking for a broader key will not help — ask a workspace admin.

- **You cannot register a datasource, and there is no tool that lets you.** **No credential
  travels through an agent.** A secret passed through you transits your context, your transcript
  and any logging the client does — a property of handing a secret to an agent, which no server
  can undo. So datasource create, update and delete are UI/REST-only. If the datasource you need
  does not exist, **ask the person to add it in the UI**, then read it back with
  `datasources_list`. (There was a `datasources_create` tool; it carried a warning in its own
  description, and a description is not a control, so it was removed.)

- **Which datasources can my key see? The ones GRANTED to its workspace — nothing else.**
  There is no such thing as a global datasource any more. A datasource is registered once and
  granted to N workspaces; `datasources_list` returns exactly the grants your pinned workspace
  holds, each row carrying `granted: true` and a `workspace` naming the workspace that
  REGISTERED it (the field is OMITTED — never null — when a super admin registered it at the
  instance level; the old `null = global` reading is retired and would be the wrong answer to
  "who can see this?"). A datasource registered elsewhere and not granted to you is ABSENT,
  and asking for it by name gets not-found, the same answer a name that exists nowhere gets.
  So: **never guess a datasource name.** A guess cannot succeed, and it cannot tell you
  whether the thing exists. If the one you need is missing, ask a super admin to grant it to
  your workspace.

- **What you CAN do with a datasource:** `datasources_list` / `datasources_get` to find one,
  `datasources_get_schemas` / `_get_tables` /
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
curl -s http://localhost:8080/api/v1/datasources -X POST           # register (a person, in the UI)
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
