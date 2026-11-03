-- =============================================================================
-- V54 — managed lake table definitions (#798, dp-lake P3)
--
-- Authority: the dp-lake design record §5.2 (the namespace, managed-table, definition-version and semantics
-- rows; the lake_tables additions), §7 (the apply state machine) and §8b (the monotone projection repair).
-- V53 (#785) is the catalog core this builds on; nothing here touches its tables or triggers.
--
-- 1. lake_namespaces — one row per managed namespace (not versioned, record B7). The DP row is written when
--    the first table definition naming the namespace is created; the Iceberg namespace is created under a
--    namespace lease by the first apply.
-- 2. lake_tables_managed — one row per managed table: lifecycle status, the definition pointer
--    (current_definition_version), what THIS environment has applied (applied_version_here), the apply state
--    machine (pending | applying | applied | failed) and the attempt that owns an `applying` row
--    (apply_operation_id: every stamp is conditional on it), and the per-environment policy. Its id is the
--    table's location segment: <root>/<namespace>/<id>.
-- 3. lake_table_definition_versions — the promoted object: DRAFT | RELEASED | DISCARDED bodies with the
--    house body_hash, write-surface stamps and the one-DRAFT-per-table partial unique index.
-- 4. lake_column_semantics — the read-only projection of the APPLIED version keyed by Iceberg field id,
--    rebuilt inside the transaction that stamps `applied`.
-- 5. lake_tables gains origin (managed | external, default external so every existing row keeps its
--    meaning) and revision_seq, the monotone guard of the projection repair; only a managed row carries one.
--
-- JSONB columns carry the _json suffix (metadata-db §2): body_json, policy_json, apply_error_json, tags_json.
--
-- Down path (documented, not shipped — Flyway runs forward only):
--   ALTER TABLE lake_tables DROP CONSTRAINT chk_lake_tables_revision_seq, DROP CONSTRAINT chk_lake_tables_origin,
--     DROP COLUMN revision_seq, DROP COLUMN origin;
--   DROP TABLE lake_column_semantics; DROP TABLE lake_table_definition_versions;
--   DROP TABLE lake_tables_managed; DROP TABLE lake_namespaces;
--   DELETE FROM flyway_schema_history WHERE version = '54';
-- =============================================================================

CREATE TABLE lake_namespaces (
    id             UUID        PRIMARY KEY,
    name           TEXT        NOT NULL,
    description    TEXT        NOT NULL DEFAULT '',
    status         TEXT        NOT NULL DEFAULT 'active',
    deprecated_at  TIMESTAMPTZ NULL,
    deprecated_by  UUID        REFERENCES users(id),
    archived_at    TIMESTAMPTZ NULL,
    archived_by    UUID        REFERENCES users(id),
    replacement_id UUID        REFERENCES lake_namespaces(id),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by     UUID        NOT NULL REFERENCES users(id),
    CONSTRAINT uq_lake_namespaces_name UNIQUE (name),
    -- LakeNames.requireName's grammar: the name is a catalog identifier and a storage path segment.
    CONSTRAINT chk_lake_namespaces_name CHECK (name ~ '^[a-z0-9_]{1,63}$'),
    CONSTRAINT chk_lake_namespaces_description CHECK (char_length(description) <= 2000),
    CONSTRAINT chk_lake_namespaces_status CHECK (status IN ('active', 'deprecated', 'archived')),
    -- An active namespace carries no lifecycle stamp; the transitions themselves are P8's.
    CONSTRAINT chk_lake_namespaces_stamps CHECK (
        status <> 'active' OR (deprecated_at IS NULL AND deprecated_by IS NULL AND archived_at IS NULL AND archived_by IS NULL)
    )
);

CREATE TABLE lake_tables_managed (
    id                         UUID        PRIMARY KEY,
    namespace_id               UUID        NOT NULL REFERENCES lake_namespaces(id),
    name                       TEXT        NOT NULL,
    status                     TEXT        NOT NULL DEFAULT 'active',
    deprecated_at              TIMESTAMPTZ NULL,
    deprecated_by              UUID        REFERENCES users(id),
    archived_at                TIMESTAMPTZ NULL,
    archived_by                UUID        REFERENCES users(id),
    purged_at                  TIMESTAMPTZ NULL,
    purged_by                  UUID        REFERENCES users(id),
    replacement_id             UUID        REFERENCES lake_tables_managed(id),
    purge_allowed_after        TIMESTAMPTZ NULL,
    current_definition_version INTEGER     NULL,
    applied_version_here       INTEGER     NULL,
    apply_state                TEXT        NULL,
    apply_error_json           JSONB       NULL,
    applied_at                 TIMESTAMPTZ NULL,
    apply_operation_id         UUID        NULL,
    policy_json                JSONB       NOT NULL,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by                 UUID        NOT NULL REFERENCES users(id),
    CONSTRAINT uq_lake_tables_managed_namespace_name UNIQUE (namespace_id, name),
    CONSTRAINT chk_lake_tables_managed_name CHECK (name ~ '^[a-z0-9_]{1,63}$'),
    CONSTRAINT chk_lake_tables_managed_status CHECK (status IN ('active', 'deprecated', 'archived', 'purged')),
    CONSTRAINT chk_lake_tables_managed_stamps CHECK (
        status <> 'active' OR (deprecated_at IS NULL AND deprecated_by IS NULL AND archived_at IS NULL
            AND archived_by IS NULL AND purged_at IS NULL AND purged_by IS NULL)
    ),
    CONSTRAINT chk_lake_tables_managed_purge_stamps CHECK ((status = 'purged') = (purged_at IS NOT NULL)),
    CONSTRAINT chk_lake_tables_managed_versions CHECK (
        (current_definition_version IS NULL OR current_definition_version >= 1)
        AND (applied_version_here IS NULL OR applied_version_here >= 1)
    ),
    CONSTRAINT chk_lake_tables_managed_apply_state CHECK (apply_state IN ('pending', 'applying', 'applied', 'failed')),
    -- No apply state before the first release; every released table has one.
    CONSTRAINT chk_lake_tables_managed_apply_pointer CHECK ((apply_state IS NULL) = (current_definition_version IS NULL)),
    -- An `applying` row names the attempt that owns it: every later stamp is conditional on that id.
    CONSTRAINT chk_lake_tables_managed_applying CHECK (apply_state <> 'applying' OR apply_operation_id IS NOT NULL),
    -- `applied` means this environment holds the current definition, nothing less.
    CONSTRAINT chk_lake_tables_managed_applied CHECK (
        apply_state <> 'applied' OR applied_version_here = current_definition_version
    ),
    CONSTRAINT chk_lake_tables_managed_apply_error CHECK (
        (apply_state IS NOT DISTINCT FROM 'failed') = (apply_error_json IS NOT NULL)
        AND (apply_error_json IS NULL OR jsonb_typeof(apply_error_json) = 'object')
    ),
    CONSTRAINT chk_lake_tables_managed_applied_at CHECK ((applied_version_here IS NULL) = (applied_at IS NULL)),
    CONSTRAINT chk_lake_tables_managed_policy CHECK (jsonb_typeof(policy_json) = 'object')
);

CREATE TABLE lake_table_definition_versions (
    table_id     UUID        NOT NULL REFERENCES lake_tables_managed(id),
    version      INTEGER     NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'DRAFT',
    body_json    JSONB       NOT NULL,
    body_hash    TEXT        NOT NULL,
    released_at  TIMESTAMPTZ NULL,
    released_by  UUID        REFERENCES users(id),
    discarded_at TIMESTAMPTZ NULL,
    discarded_by UUID        REFERENCES users(id),
    updated_by   UUID        REFERENCES users(id),
    updated_at   TIMESTAMPTZ NULL,
    created_via  TEXT        NOT NULL DEFAULT 'session',
    updated_via  TEXT        NOT NULL DEFAULT 'session',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by   UUID        NOT NULL REFERENCES users(id),
    PRIMARY KEY (table_id, version),
    CONSTRAINT chk_lake_table_definition_versions_version CHECK (version >= 1),
    CONSTRAINT chk_lake_table_definition_versions_status CHECK (status IN ('DRAFT', 'RELEASED', 'DISCARDED')),
    -- The body is an object that never names its table or namespace (the identity is the row's, not the body's).
    CONSTRAINT chk_lake_table_definition_versions_body CHECK (
        jsonb_typeof(body_json) = 'object' AND NOT (body_json ? 'name') AND NOT (body_json ? 'namespace')
    ),
    CONSTRAINT chk_lake_table_definition_versions_release_stamps CHECK (
        (status = 'DRAFT' AND released_at IS NULL AND released_by IS NULL)
        OR (status <> 'DRAFT' AND released_at IS NOT NULL)
    ),
    CONSTRAINT chk_lake_table_definition_versions_discard_stamps CHECK (
        (status = 'DISCARDED' AND discarded_at IS NOT NULL)
        OR (status <> 'DISCARDED' AND discarded_at IS NULL AND discarded_by IS NULL)
    ),
    CONSTRAINT chk_lake_table_definition_versions_via CHECK (
        created_via IN ('session', 'api_key', 'mcp') AND updated_via IN ('session', 'api_key', 'mcp')
    )
);

-- One DRAFT per table: the physical guard behind copy-on-write (the V6 / versioning §3.3 pattern).
CREATE UNIQUE INDEX uq_lake_table_definition_versions_one_draft
    ON lake_table_definition_versions (table_id) WHERE status = 'DRAFT';

CREATE TABLE lake_column_semantics (
    table_id        UUID    NOT NULL REFERENCES lake_tables_managed(id) ON DELETE CASCADE,
    field_id        INTEGER NOT NULL,
    ordinal         INTEGER NOT NULL,
    name            TEXT    NOT NULL,
    logical_type    TEXT    NOT NULL,
    iceberg_type    TEXT    NOT NULL,
    required        BOOLEAN NOT NULL,
    doc             TEXT    NULL,
    tags_json       JSONB   NOT NULL DEFAULT '[]'::jsonb,
    applied_version INTEGER NOT NULL,
    PRIMARY KEY (table_id, field_id),
    CONSTRAINT chk_lake_column_semantics_field CHECK (field_id >= 1 AND ordinal >= 0 AND applied_version >= 1),
    CONSTRAINT chk_lake_column_semantics_tags CHECK (jsonb_typeof(tags_json) = 'array')
);

ALTER TABLE lake_tables
    ADD COLUMN origin TEXT NOT NULL DEFAULT 'external',
    ADD COLUMN revision_seq BIGINT NULL,
    ADD CONSTRAINT chk_lake_tables_origin CHECK (origin IN ('managed', 'external')),
    -- Only a managed row is a projection of the revision log; an external registration has no sequence.
    ADD CONSTRAINT chk_lake_tables_revision_seq CHECK (origin = 'managed' OR revision_seq IS NULL);
