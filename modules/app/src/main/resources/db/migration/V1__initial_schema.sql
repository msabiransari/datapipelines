-- V1__initial_schema.sql
--
-- Generated from metadata-db.md §4 (the DDL authority) per §7.1/§7.2.
-- Creates all 10 tables, their constraints, and every EXPLICIT index from §5.
--
-- Three rules this file respects (metadata-db.md §7.2):
--   1. Only explicit indexes get a CREATE INDEX. Constraint-backed indexes
--      (§5 "via PK" / "via UNIQUE") are created by Postgres from the constraint;
--      emitting a CREATE INDEX for one of those is a duplicate index.
--   2. Table order follows the FK graph. pipeline_executions cannot be created
--      before pipeline_versions — its composite FK targets that table's PK.
--   3. No triggers are emitted. updated_at is application-maintained (§2): every
--      UPDATE statement sets `updated_at = NOW()` in its SET clause. The column
--      DEFAULT covers INSERT only. An UPDATE that forgets it is a repository bug.
--
-- Requires Postgres 16+ (gen_random_uuid(), JSONB, TEXT[], partial indexes) — §2.
-- Flyway runs this in one transaction and guards concurrent instances with a
-- Postgres advisory lock (§7.3).

-- ---------------------------------------------------------------------------
-- §4.1 users
-- ---------------------------------------------------------------------------
CREATE TABLE users (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email               TEXT        NOT NULL UNIQUE,
    display_name        TEXT        NOT NULL,
    profile_picture_url TEXT,
    provider            TEXT        NOT NULL,              -- OIDC registration name (free text: 'google', 'okta', 'company-sso', etc.)
    provider_subject    TEXT        NOT NULL,              -- OIDC 'sub' claim
    is_active           BOOLEAN     NOT NULL DEFAULT TRUE,
    is_admin            BOOLEAN     NOT NULL DEFAULT FALSE,
    theme_preference    TEXT,                              -- NULL = use the deployment default
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_login_at       TIMESTAMPTZ
);

CREATE UNIQUE INDEX uq_users_provider_subject ON users(provider, provider_subject);

-- ---------------------------------------------------------------------------
-- §4.2 api_keys
-- ---------------------------------------------------------------------------
CREATE TABLE api_keys (
    id                    TEXT        PRIMARY KEY,          -- 'dpk_ABCDEFGHIJKL'
    user_id               UUID        NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                  TEXT        NOT NULL,             -- 'Claude Desktop key'
    key_hash              TEXT        NOT NULL,             -- Argon2id hash of full key
    scopes                TEXT[]      NOT NULL DEFAULT '{read}',
    is_revoked            BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at          TIMESTAMPTZ,
    expires_at            TIMESTAMPTZ,
    last_used_ip          INET,
    last_used_user_agent  TEXT
);

CREATE INDEX idx_api_keys_user ON api_keys(user_id) WHERE is_revoked = FALSE;
CREATE INDEX idx_api_keys_expires ON api_keys(expires_at)
    WHERE expires_at IS NOT NULL AND is_revoked = FALSE;

-- ---------------------------------------------------------------------------
-- §4.3 audit_log
-- ---------------------------------------------------------------------------
CREATE TABLE audit_log (
    id           BIGSERIAL   PRIMARY KEY,
    timestamp    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    event        TEXT        NOT NULL,             -- 'auth.login.success' etc.
    user_id      UUID        REFERENCES users(id),
    key_id       TEXT,                             -- API key id, if auth via API key
    source_ip    INET,
    user_agent   TEXT,
    details_json JSONB       NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_audit_timestamp ON audit_log(timestamp DESC);
CREATE INDEX idx_audit_user ON audit_log(user_id, timestamp DESC) WHERE user_id IS NOT NULL;
CREATE INDEX idx_audit_event ON audit_log(event, timestamp DESC);

-- ---------------------------------------------------------------------------
-- §4.4 pipelines
-- ---------------------------------------------------------------------------
CREATE TABLE pipelines (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            TEXT        NOT NULL UNIQUE,     -- machine name, [a-z0-9_]+
    display_name    TEXT        NOT NULL,
    description     TEXT        NOT NULL DEFAULT '',
    owner_id        UUID        NOT NULL REFERENCES users(id),
    current_version INTEGER     NOT NULL DEFAULT 0,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pipelines_owner ON pipelines(owner_id) WHERE is_deleted = FALSE;

-- ---------------------------------------------------------------------------
-- §4.5 pipeline_versions
-- ---------------------------------------------------------------------------
CREATE TABLE pipeline_versions (
    pipeline_id     UUID        NOT NULL REFERENCES pipelines(id) ON DELETE CASCADE,
    version         INTEGER     NOT NULL,
    body_json       JSONB       NOT NULL,            -- full pipeline JSON (per Pipeline Contract §3)
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID        NOT NULL REFERENCES users(id),
    PRIMARY KEY (pipeline_id, version)
);

-- ---------------------------------------------------------------------------
-- §4.6 pipeline_executions
-- ---------------------------------------------------------------------------
CREATE TABLE pipeline_executions (
    execution_id        UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    pipeline_id         UUID        NOT NULL REFERENCES pipelines(id),
    pipeline_version    INTEGER     NOT NULL,        -- snapshot of version at execution time
    status              TEXT        NOT NULL,        -- 'RUNNING' | 'SUCCESS' | 'FAILED' | 'ABORTED'
    parameters_json     JSONB       NOT NULL DEFAULT '{}',
    triggered_by        UUID        NOT NULL REFERENCES users(id),
    triggered_via       TEXT        NOT NULL,        -- 'UI' | 'REST' | 'MCP'
    correlation_id      UUID,
    started_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    completed_at        TIMESTAMPTZ,
    duration_ms         BIGINT,
    failed_node_id      TEXT,
    error_json          JSONB,                       -- error envelope if FAILED
    node_stats_json     JSONB,                       -- array of node stats
    result_row_count    BIGINT,                      -- rows in the caller result; NULL if the pipeline has no caller node
    result_size_bytes   BIGINT,                      -- materialized result size in Redis; NULL if no caller node
    CONSTRAINT chk_status CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'ABORTED')),
    CONSTRAINT chk_triggered_via CHECK (triggered_via IN ('UI', 'REST', 'MCP')),
    CONSTRAINT fk_executions_pipeline_version
        FOREIGN KEY (pipeline_id, pipeline_version)
        REFERENCES pipeline_versions (pipeline_id, version)
);

CREATE INDEX idx_executions_pipeline ON pipeline_executions(pipeline_id, started_at DESC);
CREATE INDEX idx_executions_status_running ON pipeline_executions(started_at)
    WHERE status = 'RUNNING';
CREATE INDEX idx_executions_user ON pipeline_executions(triggered_by, started_at DESC);
CREATE INDEX idx_executions_correlation ON pipeline_executions(correlation_id)
    WHERE correlation_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- §4.7 execution_events
--
-- No idx_events_execution: the UNIQUE (execution_id, event_id) constraint already
-- creates a btree on exactly the replay query's access path. A second index on the
-- same column list would be pure write amplification on the highest-volume table.
-- ---------------------------------------------------------------------------
CREATE TABLE execution_events (
    id              BIGSERIAL   PRIMARY KEY,
    execution_id    UUID        NOT NULL REFERENCES pipeline_executions(execution_id) ON DELETE CASCADE,
    event_id        INTEGER     NOT NULL,            -- monotonic per execution (1, 2, 3...)
    event_type      TEXT        NOT NULL,            -- 'execution_started', 'node_started', etc.
    timestamp       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    payload_json    JSONB       NOT NULL,
    CONSTRAINT uq_events_execution_event UNIQUE (execution_id, event_id)
);

-- ---------------------------------------------------------------------------
-- §4.8 templates
-- ---------------------------------------------------------------------------
CREATE TABLE templates (
    id              TEXT        PRIMARY KEY,          -- 'fetch_orders.sql'
    display_name    TEXT        NOT NULL,
    description     TEXT        NOT NULL DEFAULT '',
    current_version INTEGER     NOT NULL DEFAULT 0,
    is_deleted      BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID        NOT NULL REFERENCES users(id)
);

CREATE INDEX idx_templates_active ON templates(id) WHERE is_deleted = FALSE;

-- ---------------------------------------------------------------------------
-- §4.9 template_versions
-- ---------------------------------------------------------------------------
CREATE TABLE template_versions (
    template_id     TEXT        NOT NULL REFERENCES templates(id) ON DELETE CASCADE,
    version         INTEGER     NOT NULL,
    engine          TEXT        NOT NULL DEFAULT 'freemarker',
    dialect         TEXT        NOT NULL,            -- 'POSTGRES', 'ORACLE', etc.
    is_library      BOOLEAN     NOT NULL DEFAULT FALSE,
    imports_json    JSONB       NOT NULL DEFAULT '[]',   -- array of {id, version, alias}
    body            TEXT        NOT NULL,            -- template source; syntax per `engine`
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by      UUID        NOT NULL REFERENCES users(id),
    PRIMARY KEY (template_id, version),
    CONSTRAINT chk_dialect CHECK (dialect IN ('POSTGRES', 'ORACLE', 'MSSQL', 'MYSQL', 'H2', 'DUCKDB', 'SQLITE'))
);

CREATE INDEX idx_template_versions_dialect ON template_versions(dialect);

-- ---------------------------------------------------------------------------
-- §4.10 datasources
-- ---------------------------------------------------------------------------
CREATE TABLE datasources (
    name                    TEXT        PRIMARY KEY,        -- 'pg-prod'
    display_name            TEXT        NOT NULL,
    description             TEXT,                           -- OPTIONAL (nullable) — datasources.md §3.3
    dialect                 TEXT        NOT NULL,           -- 'POSTGRES', 'ORACLE', etc.
    jdbc_url                TEXT        NOT NULL,
    username                TEXT        NOT NULL,
    password_encrypted      BYTEA       NOT NULL,           -- AES-256-GCM: nonce || ciphertext || tag
    properties_json         JSONB       NOT NULL DEFAULT '{}',  -- {"hikari": {...}, "jdbc": {...}}
    query_timeout_seconds   INTEGER,                        -- NULL = fall back to the global executor default
    is_deleted              BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by              UUID        NOT NULL REFERENCES users(id),
    CONSTRAINT chk_datasource_name CHECK (
        char_length(name) BETWEEN 1 AND 63 AND name ~ '^[a-z0-9_-]+$'
    ),
    CONSTRAINT chk_datasource_dialect CHECK (
        dialect IN ('POSTGRES', 'ORACLE', 'MSSQL', 'MYSQL', 'H2', 'DUCKDB', 'SQLITE')
    ),
    CONSTRAINT chk_datasource_query_timeout CHECK (
        query_timeout_seconds IS NULL OR query_timeout_seconds >= 1
    )
);

CREATE INDEX idx_datasources_active ON datasources(name) WHERE is_deleted = FALSE;
