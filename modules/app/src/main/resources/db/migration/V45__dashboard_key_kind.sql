-- V45 — the `dashboard` key kind (L5, the dashboards implementation spec §2.2): the kind/role
-- CHECKs gain the fourth kind, `executed_by_key_kind` gains its attribution value, and the
-- bindings table — `endpoint_key_bindings` (V11) verbatim with the NAME grammar's folder
-- prefixes — lands beside it.
--
-- The migration is additive only: no data changes, every existing row satisfies the widened
-- CHECKs (a new arm admits MORE, never less). The V37 lesson stands on every new arm: under
-- SQL's three-valued logic `role = 'dashboard_viewer'` is UNKNOWN for a NULL role and a CHECK
-- passes on UNKNOWN, so `role IS NOT NULL` is spelled out — without it a live `dashboard` row
-- with a NULL role would be ADMITTED (V37 measured exactly this shape on the mcp arm).

-- 1. chk_api_keys_kind gains 'dashboard' (V37:110 is the constraint's home).
ALTER TABLE api_keys DROP CONSTRAINT chk_api_keys_kind;
ALTER TABLE api_keys
    ADD CONSTRAINT chk_api_keys_kind CHECK (kind IN ('mcp', 'endpoint', 'server', 'dashboard'));

-- 2. chk_api_keys_role gains the dashboard arm (V37:132-137) — the transport kind's fixed role,
-- the `IS NOT NULL` spelled (see the header).
ALTER TABLE api_keys DROP CONSTRAINT chk_api_keys_role;
ALTER TABLE api_keys ADD CONSTRAINT chk_api_keys_role CHECK (
    (kind = 'mcp' AND role IS NOT NULL AND role IN ('author', 'promoter', 'workspace_admin'))
    OR (kind = 'mcp' AND role IS NULL AND is_revoked)
    OR (kind = 'endpoint' AND role IS NOT NULL AND role = 'api_caller')
    OR (kind = 'server' AND role IS NOT NULL AND role = 'promotion_receiver')
    OR (kind = 'dashboard' AND role IS NOT NULL AND role = 'dashboard_viewer')
);

-- 3. `pipeline_executions.executed_by_key_kind` gains 'dashboard' (V37:116-122) — a delegated
-- dashboard refresh attributes its executions to the key (D50). History is not rewritten.
ALTER TABLE pipeline_executions DROP CONSTRAINT chk_executions_executed_by_key_kind;
ALTER TABLE pipeline_executions
    ADD CONSTRAINT chk_executions_executed_by_key_kind
        CHECK (executed_by_key_kind IS NULL OR executed_by_key_kind IN ('user', 'mcp', 'endpoint', 'server', 'dashboard'));

-- 4. dashboard_key_bindings — the endpoint bindings' twin: a key bound at a FOLDER of the
-- dashboard name space serves every dashboard beneath it. Resolution walks the dashboard name's
-- ancestors from the most specific and the FIRST node carrying any binding decides — so a
-- deeper binding REPLACES an inherited one for its subtree rather than adding to it (R-EP2
-- verbatim; auth §7.7). Bind both keys at the deeper node when both should keep working.
--
-- `name_prefix` is a folder: 1-9 segments of the name grammar (PipelineNameGrammar), or the
-- root '/' binding the whole workspace tree. `api_key_id` is TEXT because `api_keys.id` is
-- TEXT (the dpk_ id itself). ON DELETE CASCADE: a key that no longer exists cannot serve
-- anything, and leaving its bindings behind would make the Keys page show a binding to
-- nothing. The retention purge (KeyRetentionPurge) refuses to delete a key either table names.
CREATE TABLE dashboard_key_bindings (
    name_prefix      TEXT        NOT NULL,
    api_key_id       TEXT        NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    workspace_id     UUID        NOT NULL REFERENCES workspaces(id),
    created_by       UUID        NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (name_prefix, api_key_id)
);

-- "Which folders does this key bind?" — the Keys page's binding editor, and the blast radius
-- a revoke reports.
CREATE INDEX idx_dashboard_key_bindings_key ON dashboard_key_bindings(api_key_id);
