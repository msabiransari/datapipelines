-- V11__published_endpoints.sql
--
-- Published endpoints: a released pipeline served as `GET /api/x/**`
-- (published-endpoints design §4, round 074). Three objects, no backfill.
--
-- WHY A REGISTRY AND NOT ROUTES. The catch-all handler owns the whole `/api/x` subtree
-- (ruling R-EP1); a published endpoint is a ROW that the handler matches a request path
-- against, never a Spring request mapping registered at runtime. That is what makes
-- publishing a data write with the ordinary transactional and audit story instead of a
-- mutation of the servlet container's state.
--
-- WHY `UNIQUE (path_pattern)` IS DEPLOYMENT-WIDE AND NOT PER-WORKSPACE. A URL is global:
-- `GET /api/x/lending/home` has exactly one meaning on a deployment, so two workspaces
-- cannot both own it. The workspace_id on the row says who may manage it and whose
-- datasources the pipeline runs against — it does not namespace the path. Scoping the
-- constraint per workspace would let two rows claim one URL and make resolution
-- ambiguous at request time, which is precisely what §4.1 refuses.
--
-- The unique index the constraint creates is also the resolution index: matching loads
-- the enabled rows of the deployment and matches in memory (the set is small and cached
-- per instance, invalidated over the 050 pub/sub channel), so no query plan depends on
-- the shape of `path_pattern`.
--
-- `timeout_seconds` is stored per endpoint and clamped at write time to
-- `datapipelines.endpoints.timeout-min-seconds`..`timeout-max-seconds` (§5.5). The clamp
-- is deliberately NOT a CHECK constraint: the bounds are configuration, they may be
-- retuned by an operator, and a stored row written under the old bounds must keep
-- serving rather than make the table unreadable.

CREATE TABLE published_endpoints (
    id               UUID        PRIMARY KEY,
    workspace_id     UUID        NOT NULL REFERENCES workspaces(id),
    path_pattern     TEXT        NOT NULL,   -- '/lending/{borough}/home' — §4.1 grammar, no root
    pipeline_id      UUID        NOT NULL REFERENCES pipelines(id),
    timeout_seconds  INTEGER     NOT NULL,   -- clamped by config at write time, §5.5
    description      TEXT        NOT NULL DEFAULT '',
    is_enabled       BOOLEAN     NOT NULL DEFAULT TRUE,
    created_by       UUID        NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE (path_pattern)                    -- a URL is global, see the note above
);

-- Management listings are per workspace ("show me my endpoints"); the tree screen reads
-- the whole deployment. Both are small, but the workspace listing is the hot one.
CREATE INDEX idx_published_endpoints_workspace ON published_endpoints(workspace_id);

-- Publishing pins a PIPELINE, not a version: §5.1 resolves the latest RELEASED version at
-- request time. This index answers "does any endpoint publish this pipeline?", which is
-- what a pipeline delete and the read-only re-check need.
CREATE INDEX idx_published_endpoints_pipeline ON published_endpoints(pipeline_id);

-- ---------------------------------------------------------------------------
-- §4 endpoint_key_bindings — a key bound at a TREE NODE (ruling R-EP2)
-- ---------------------------------------------------------------------------
--
-- `path_prefix` is a node of the endpoint tree, not a pattern: '/lending' authorises
-- every endpoint beneath it. Resolution walks the request path's ancestors from the most
-- specific and the FIRST node carrying any binding decides — so a deeper binding REPLACES
-- an inherited one for its subtree rather than adding to it. Bind both keys at the deeper
-- node when both should keep working.
--
-- `api_key_id` is TEXT because `api_keys.id` is TEXT (the `dpk_…` id itself, V1 §4.2).
-- ON DELETE CASCADE: a key that no longer exists cannot authorise anything, and leaving
-- its bindings behind would make the tree screen show a binding to nothing.
--
-- The primary key is (path_prefix, api_key_id): one key binds a node once, and several
-- keys may bind the same node.
CREATE TABLE endpoint_key_bindings (
    path_prefix      TEXT        NOT NULL,   -- a tree NODE: '/lending' binds '/lending/**'
    api_key_id       TEXT        NOT NULL REFERENCES api_keys(id) ON DELETE CASCADE,
    workspace_id     UUID        NOT NULL REFERENCES workspaces(id),
    created_by       UUID        NOT NULL REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (path_prefix, api_key_id)
);

-- "Which nodes does this key bind?" — the key detail view and the cascade of a revoke.
CREATE INDEX idx_endpoint_key_bindings_key ON endpoint_key_bindings(api_key_id);

-- ---------------------------------------------------------------------------
-- §5.2 api_keys.kind — 'user' | 'endpoint'
-- ---------------------------------------------------------------------------
--
-- Every key that exists today is a user key: scopes, a workspace, and the whole API
-- surface its scopes allow. DEFAULT 'user' is therefore the correct backfill for the
-- entire table and no UPDATE is needed.
--
-- An 'endpoint' key is the new kind: no scopes are consulted, it is workspace-pinned, and
-- it authorises exactly the endpoints its bindings cover plus the cursor of executions it
-- started. An endpoint key with no binding on any ancestor authorises NOTHING (§5.2) —
-- the absence of a binding is not a fallback to the user-key rule.
--
-- The CHECK is the house convention for an enum column (chk_status, chk_triggered_via,
-- chk_dialect, chk_template_type all do this): the database refuses a value the
-- application's enum cannot name, so a bad write fails where it happens instead of at the
-- next read. It is named so a later kind widens it the way V3 widened chk_triggered_via.
ALTER TABLE api_keys ADD COLUMN kind TEXT NOT NULL DEFAULT 'user';
ALTER TABLE api_keys ADD CONSTRAINT chk_api_keys_kind CHECK (kind IN ('user', 'endpoint'));

-- Endpoint resolution and the key-listing screens both ask "the endpoint keys of this
-- workspace"; user keys are the overwhelming majority, so the partial index stays small.
CREATE INDEX idx_api_keys_endpoint_kind ON api_keys(workspace_id)
    WHERE kind = 'endpoint' AND is_revoked = FALSE;

-- ---------------------------------------------------------------------------
-- §5.4 pipeline_executions.triggered_via gains 'ENDPOINT'
-- ---------------------------------------------------------------------------
--
-- A serve runs the pipeline in-process and records an execution like every other trigger.
-- `chk_triggered_via` is a closed set (V1 §4.6, widened by V3 for PIPELINE composition),
-- so WITHOUT this the very first serve would fail on the constraint rather than on
-- anything the design describes. Widened exactly the way V3 did it.
--
-- The design text writes the value as "endpoint"; the column's alphabet is UPPERCASE for
-- every existing trigger and `ExecutionTrigger` is an uppercase enum, so the stored value
-- is 'ENDPOINT' (enums.md §18).
ALTER TABLE pipeline_executions DROP CONSTRAINT chk_triggered_via;
ALTER TABLE pipeline_executions ADD CONSTRAINT chk_triggered_via
    CHECK (triggered_via IN ('UI', 'REST', 'MCP', 'PIPELINE', 'ENDPOINT'));
