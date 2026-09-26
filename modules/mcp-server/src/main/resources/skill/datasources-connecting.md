---
area: datasources
layer: reference
purpose: Before your first call against a deployment, when a call is refused for role or credential reasons, or when the client has no MCP transport.
---

# Connecting: transport, your key, your role, datasources

Open before your first call against a deployment, when a call is refused for role or credential reasons, or when the client has no MCP transport.

Part of the served manual — the operating core is the document `core`, and every document
answers to `docs_get` by its name here.

## Connecting

- **Which host?** The user's own deployment — this product is self-hosted, so there is no
  default endpoint and you must ask for one rather than assume `localhost`. If the user has
  no server yet, one command on a machine with Docker gives them a working one:
  `./app.sh --start` from a checkout (the deployment guide covers what it can load). That deployment is
  `DATAPIPELINES_ENV=local` under the `development` posture, and `app.sh` prints the login
  that exists after it is healthy. A deployment their organisation runs will be named
  something else and may be `hardened`, which refuses authoring writes — see the
  `*.authoring.disabled` row in `core-error-codes`.

- **MCP:** Streamable HTTP at `POST {host}/mcp` — stateless, protocol pinned to
  `2025-06-18`. Auth is API-key-only: `DP-API-Key: dpk_<id>.<secret>` or
  `Authorization: Bearer dpk_<id>.<secret>`. Browser session cookies are rejected on
  `/mcp`. **Your key connects an MCP client and nothing else**: the REST API (`/api/v1/**`)
  refuses it on every route with `endpoint.key_kind_refused` (`details.reason =
  mcp_key_off_surface`), before anything else is checked.

- **MCP tools:** each area's tools reference (`pipelines-tools`, `datasources-tools`, …) is
  generated from the shipped tools' own descriptions, so it cannot drift; `docs_list` says
  what is documented.

- **3 prompts:** `analyze_pipeline` (read-only structural review of a pipeline),
  `create_pipeline_for_question` (ground a new pipeline's SQL in the introspection
  tools, then author it), `debug_failed_execution` (walk a failed execution to a
  diagnosis).

- **Getting a key (keys v2).** Your credential is an **MCP key** (wire kind `mcp`). No key is
  minted at sign-in (A15) — someone CREATES it on the Keys page: you (if your role holds
  `mcp_key.create` — author, promoter and workspace admin do) or a colleague who does. The
  creator picks the key's ROLE at creation — `author`, `promoter` or `workspace_admin` — and
  may offer only roles whose permission set is a subset of their own (A14): an author can
  offer author only, a promoter promoter only, a workspace admin or super admin any of the
  three. `super_admin` is never a key role (B1), and viewer is not either (A15). The plaintext
  is shown once at creation; the key acts as its own identity holding that role (A13). The
  other two key kinds are not yours. An `endpoint` key ("API key") is a program's credential
  for published endpoints. A `server` key is one deployment's credential for another. `/mcp`
  refuses both with `endpoint.key_kind_refused`.

- **What your role lets you do over MCP:**
  - **viewer:** read everything (pipelines, templates, datasources, endpoints, facts), run
    pipelines, read and cancel your own executions, introspect schemas and test a datasource
    connection.
  - **author:** everything a viewer can do, plus create and change pipelines and templates,
    render templates, preview rows, `sql_probe`, execute a single node, publish endpoints,
    record and retire facts, and the three `lake_tables_*` dp-lake registry writes.
  - **promoter:** reads and introspects, but runs nothing and reads no executions.

  Each tool's permission is in its area's tools reference. Creating, updating and deleting a
  datasource are UI-only: **no credential travels through an agent** (094). Ask a person to add
  the datasource in the UI, then use it by name.

- **The key's role is its OWN — it does not follow yours (A13).** A role change of the PERSON
  who created the key changes nothing about it; a refusals-fresh check does not exist. If your
  key's role is short for a task, a new key with a bigger role can only be created by someone
  whose own permissions are a superset of that role (A14). `auth.role_required` (with
  `details.required` and `details.held`) means the key's ROLE is short. It is not retryable
  with this key or any other, so ask whoever can create a key of the needed role. The key is
  also TIED to its creator's membership: if that member is removed from the workspace, the
  keys they created there are revoked (A17/B6) and it stops working with
  `auth.api_key.invalid`. That is not retryable either: ask for a re-invite, and create a new
  key on the Keys page.

- **You cannot register a datasource, and there is no tool that lets you.** **No credential
  travels through an agent.** A secret passed through you transits your context, your transcript
  and any logging the client does — a property of handing a secret to an agent, which no server
  can undo. So datasource create, update and delete are UI/REST-only. If the datasource you need
  does not exist, **ask the person to add it in the UI**, then read it back with
  `datasources_list`. (There was a `datasources_create` tool; it carried a warning in its own
  description, and a description is not a control, so it was removed.) One nuance worth passing
  along: in-process engines — H2 `mem:`/`file:`, DuckDB, SQLite — are registered by a **super
  admin** only, and file-backed ones only under the deployment's declared file roots, so for
  those the right person to ask is a super admin, not a workspace admin. The H2 prefixes are
  matched case-sensitively, exactly as the driver reads them — `jdbc:h2:MEM:x` or
  `jdbc:h2:TCP://h/x` are refused as unknown forms, not read as the lower-case forms.

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

## When the client has no MCP transport

**The manual needs no MCP at all.** `GET /skill.md` is the operating core; its reference map
names each `GET /skill/<name>.md` (e.g. `/skill/pipelines-authoring.md`) — unauthenticated,
`text/markdown`, the same bytes `docs_get` and the `datapipelines://docs/skill` resources serve.
An unknown reference is a `404` whose message points back at the map. Read the core first, then
follow its map — the same learning path as the MCP one.

**The API itself is not open to you.** Your key is refused on every `/api/v1/**` route. The
REST API serves signed-in people, programs holding an `endpoint` key (published endpoints and
the runs they started) and peer deployments holding a `server` key. It is not a second road for
an agent. If the client has no MCP transport, say so and ask the person to connect one that
does. Do not ask for a different kind of key, and never ask for a session cookie.

For orientation: the REST execution endpoint answers with an SSE stream of events
(`execution_started`, `node_started`, `node_progress` — measured per-node operation
samples: state, destination, cumulative counts — `node_completed`, `pipeline_completed`,
`data_ready`, …) — the agent-facing MCP tool turns that into one blocking call with
`node_stats` in the result. Everything the MCP tools do is a thin adapter over these
endpoints; error codes are identical.
