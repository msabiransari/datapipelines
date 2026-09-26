---
area: datasources
layer: guide
purpose: A first call against a deployment, a new datasource, introspection, and the learned-facts system.
---

# Datasources — connecting, discovery, learned facts

A **datasource** is a registered connection: name, dialect, credential (encrypted), and the
pool the server queries through. Registered once and granted to N workspaces — you see exactly
your workspace's grants (the core's workspace rule), and an ungranted name is absent, the same
answer as a name that exists nowhere.

## Connecting

- **Which host?** The user's own deployment — this product is self-hosted, so there is no
  default endpoint; ask rather than assume `localhost`. If the user has no server yet, one
  command on a machine with Docker gives them a working one: `./app.sh --start` from a
  checkout. A deployment their organisation runs may be `hardened`, which refuses authoring
  writes — see the `*.authoring.disabled` row in `core-error-codes`.
- **MCP:** Streamable HTTP at `POST {host}/mcp` — stateless, protocol pinned to `2025-06-18`.
  Auth is API-key-only: `DP-API-Key: dpk_<id>.<secret>` or `Authorization: Bearer
  dpk_<id>.<secret>`. Browser session cookies are rejected on `/mcp`. **Your key connects an
  MCP client and nothing else**: the REST API refuses it on every route with
  `endpoint.key_kind_refused` (`details.reason = mcp_key_off_surface`).
- **Getting a key.** No key is minted at sign-in: someone creates it on the Keys page — you
  (if your role holds `mcp_key.create`) or a colleague. The creator picks the key's ROLE at
  creation and may offer only roles whose permission set is a subset of their own; the
  plaintext is shown once and the key acts as its own identity. The other key kinds are not
  yours: an `endpoint` key serves published endpoints, a `server` key pairs deployments, and
  `/mcp` refuses both. A key is tied to its creator's membership — if that member is removed
  from the workspace, keys they created there are revoked (`auth.api_key.invalid`); ask for a
  re-invite and create a new key.
- **No MCP transport?** The manual itself needs none: `GET /skill.md` and
  `GET /skill/<name>.md` are unauthenticated and serve the same documents. The REST API is NOT
  a second road for an agent — your key is refused there; say so and ask the person to connect
  a client that speaks MCP. Three prompts complete the surface: `analyze_pipeline`,
  `create_pipeline_for_question`, `debug_failed_execution`.

## Discovery and introspection

`datasources_list` is your first call and your first read (the pipelines guide's step 1 owns
the full read order). What you can do: `datasources_list` / `datasources_get` to find one,
`datasources_get_schemas` / `_get_tables` / `_get_columns` to read its shape,
`datasources_preview_rows` for up to 50 rows, `datasources_get_table_stats` for the catalog's
estimate, and `sql_probe` to run a read-only SELECT. `datasources_test` settles connectivity
and credentials cheaply — test the datasource before you build on it. Registering tables on a
LAKE datasource is the `lake` guide's workflow.

**Namespaces, not just schemas.** `datasources_get_schemas` returns `entries: [{namespace,
label}]`. `namespace` is the ordered path (outermost first) and `label` is its last segment.
On a two-level engine — `catalog.schema`, `project.dataset` — two entries can share a label
and differ only by their outer segment, so **pass the whole `namespace` array back** to
`_get_tables`/`_get_columns`, not the label. An unqualified `_get_columns` on a two-level
engine can merge same-named tables from different catalogs.

**`credential.kind`, on the read side.** `datasources_get` reports WHAT a datasource
authenticates with, never the secret: `password`, `token`, `private_key`,
`service_account_json` (in the contract for warehouse connectors; no shipped dialect accepts
them yet), and `none` — an IAM role, OS auth, or a file database with no authentication. It is
the fact that explains an authentication failure you are asked to diagnose. Credentials are
write-only — reads carry `password_set: true`, never the value; never echo one you were given
into a body, a template or a reply (the core's parameter-safety rule). `datasources_get` also
reports the pool's effective settings (`pool`) — what a pool-timeout failure is usually
explained by.

**One nuance worth passing along:** in-process engines — H2 `mem:`/`file:`, DuckDB, SQLite —
are registered by a super admin only, file-backed ones only under the deployment's declared
file roots; for those, the right person to ask is a super admin, not a workspace admin. Where
the encryption keys come from is a deployment seam (`datapipelines.db.key-provider`) with a
written extension procedure — `docs/key-providers.md`; do not redesign the crypto.

## Common mistakes

Guessing a datasource or table name (a guess is always not-found and tells you nothing —
list); passing a label where the `namespace` array is required; diagnosing an authentication
failure without reading `credential.kind`; treating a pool timeout as a query problem before
reading the pool settings.

## References — open when

- **`datasources-semantics`** — recording, reusing, superseding or retiring a learned fact.
- **`datasources-tools`** — the area's tools, generated from their shipped descriptions.
- **`lake`** — the datasource is object storage: registration and reading.
- **`pipelines-learning`** — the mandatory read order before SQL: stats, probes, facts.
