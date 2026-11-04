-- Permanent delivery identities and publication attempts (#799, design §5.2 / §8b).
-- The revision trigger's operation_id, joined through attempts, is the commit evidence.
-- Down rehearsal: drop attempts, then deliveries, then the version flag; remove history 55.
CREATE TABLE lake_deliveries (
    id                      UUID PRIMARY KEY,
    table_id                UUID NOT NULL REFERENCES lake_tables_managed(id),
    feed                    TEXT NOT NULL,
    delivery_id             TEXT NOT NULL,
    fingerprint             TEXT NOT NULL,
    manifest_json           JSONB NULL,
    state                   TEXT NOT NULL DEFAULT 'RECEIVED',
    failure_phase           TEXT NULL,
    failure_code            TEXT NULL,
    error_json              JSONB NULL,
    operation_id            UUID NOT NULL,
    execution_id            UUID NOT NULL,
    pipeline_id             UUID NOT NULL,
    snapshot_id             BIGINT NULL,
    metadata_location_after TEXT NULL,
    replay_mode             TEXT NULL,
    received_at             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published_at            TIMESTAMPTZ NULL,
    CONSTRAINT uq_lake_deliveries_key UNIQUE (table_id, feed, delivery_id),
    CONSTRAINT chk_lake_deliveries_feed CHECK (feed ~ '^[a-z0-9_-]{1,64}$'),
    CONSTRAINT chk_lake_deliveries_delivery_id CHECK (delivery_id ~ '^[a-z0-9_-]{1,64}$'),
    CONSTRAINT chk_lake_deliveries_fingerprint CHECK (fingerprint ~ '^[a-f0-9]{64}$'),
    CONSTRAINT chk_lake_deliveries_state CHECK (state IN ('RECEIVED', 'PUBLISHING', 'PUBLISHED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT chk_lake_deliveries_failure_phase CHECK (failure_phase IN ('listing', 'staging', 'validation', 'publish')),
    CONSTRAINT chk_lake_deliveries_replay_mode CHECK (replay_mode IN ('exact', 'directed'))
);

CREATE TABLE lake_delivery_attempts (
    id            UUID PRIMARY KEY,
    delivery_id   UUID NOT NULL REFERENCES lake_deliveries(id),
    operation_id  UUID NOT NULL UNIQUE,
    recipe_key    TEXT NULL,
    recipe_sha256 TEXT NULL,
    started_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ended_at      TIMESTAMPTZ NULL,
    outcome       TEXT NULL,
    CONSTRAINT chk_lake_delivery_attempts_outcome CHECK (outcome IN ('PUBLISHED', 'FAILED', 'UNKNOWN')),
    CONSTRAINT chk_lake_delivery_attempts_sha256 CHECK (recipe_sha256 ~ '^[a-f0-9]{64}$')
);

CREATE INDEX idx_lake_deliveries_unsettled ON lake_deliveries (table_id, state)
    WHERE state IN ('RECEIVED', 'PUBLISHING', 'UNKNOWN');
CREATE INDEX idx_lake_delivery_attempts_delivery ON lake_delivery_attempts (delivery_id);

-- Derived at save/validation/import; historical versions predate managed lake writes.
ALTER TABLE pipeline_versions ADD COLUMN writes_managed_lake BOOLEAN NOT NULL DEFAULT FALSE;
