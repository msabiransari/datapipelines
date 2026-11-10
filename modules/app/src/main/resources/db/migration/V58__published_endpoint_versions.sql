-- =============================================================================
-- V58 — API endpoint versions with exact pipeline pins (#786 U1, dependency-graph record §5.3, §24)
--
-- Authority: the business-object dependency-graph record, Draft 9 + the Draft 10 erratum (owner rulings
-- 2026-11-08: one server, existing APIs converted IN PLACE by code, no closure predicate, no unresolved state).
--
-- 1. published_endpoint_versions — one row per API version: DRAFT | RELEASED | DISCARDED, the exact pipeline pin
--    (portable pipeline NAME + exact version NUMBER, never a pipeline UUID), the timeout and the description, the
--    house body_hash, release and discard stamps, and at most one DRAFT per entity (the V39 partial-unique pattern).
-- 2. published_endpoints gains current_version: the API's OWN runtime pointer. Serving reads this pointer and the
--    version it names, never the pipeline's CURRENT (record §24: "it never reads pipeline CURRENT"). It is tied to a
--    version row by a composite FOREIGN KEY (id, current_version) → (endpoint_id, version), DEFERRABLE INITIALLY
--    DEFERRED — a constraint, not a trigger — so a transaction that purges the current version and moves the pointer
--    is checked once, at commit. A NULL pointer is not checked (MATCH SIMPLE) and serves nothing.
--
-- The body hash (record §5.3, acceptance A44). Every write — native save, the in-place conversion, the receiver's
-- import — computes it in SQL with ONE expression, the Kotlin constant
-- PublishedEndpointVersionRepository.API_BODY_HASH_EXPR, whose text a drift test asserts is a byte-equal substring of
-- this comment:
--
--   encode(sha256(convert_to((jsonb_build_object('pipeline', CAST(:pipelineName AS TEXT), 'pipeline_version', CAST(:pipelineVersion AS INTEGER), 'timeout_seconds', CAST(:timeoutSeconds AS INTEGER), 'description', CAST(:description AS TEXT)))::text, 'UTF8')), 'hex')
--
-- jsonb canonicalises key order, so the same four fields hash equally on every deployment. The path, the entity and
-- pipeline UUIDs, is_enabled and the key bindings are NOT inputs: they are entity-level or deployment-local.
--
-- The legacy columns published_endpoints.pipeline_id, timeout_seconds and description STAY, unchanged, as the SOURCE
-- of the in-place conversion (the startup step in modules/app reads them once for an entity that has no version row
-- yet); a new entity writes them as a creation snapshot and nothing serves from them. No data is backfilled here: the
-- conversion is code, so it runs under the writer guard and the shared hash expression.
--
-- Correction by reference (record §2, "Correction to two stale source comments (F5)" and "Third stale-comment
-- correction (F-A)"): V11's "Publishing pins a PIPELINE, not a version: §5.1 resolves the latest RELEASED version at
-- request time" is no longer true — from this version an API serves its current API VERSION's exact pipeline pin.
-- V37's claim that promotion binds every same-named key describes the receiver's by-name transfer, which this lane
-- keeps unchanged (owner ruling 2026-11-08; its replacement is follow-up #879); the shipped migrations are not edited.
--
-- Down path (documented, not shipped — Flyway runs forward only):
--   ALTER TABLE published_endpoints DROP CONSTRAINT fk_published_endpoints_current_version,
--     DROP CONSTRAINT chk_published_endpoints_current_version, DROP COLUMN current_version;
--   DROP TABLE published_endpoint_versions;
--   DELETE FROM flyway_schema_history WHERE version = '58';
-- =============================================================================

CREATE TABLE published_endpoint_versions (
    endpoint_id      UUID        NOT NULL REFERENCES published_endpoints(id) ON DELETE CASCADE,
    version          INTEGER     NOT NULL,
    status           TEXT        NOT NULL DEFAULT 'DRAFT'
                         CONSTRAINT chk_published_endpoint_versions_status CHECK (status IN ('DRAFT', 'RELEASED', 'DISCARDED')),
    pipeline_name    TEXT        NOT NULL,   -- the portable pipeline identity (pipeline-contract §11.1)
    pipeline_version INTEGER     NOT NULL,   -- the EXACT pin: never 'current', never re-resolved
    timeout_seconds  INTEGER     NOT NULL,   -- clamped by config at write time, published-endpoints §5.5
    description      TEXT        NOT NULL DEFAULT '',
    body_hash        TEXT        NOT NULL,
    released_at      TIMESTAMPTZ NULL,
    released_by      UUID        REFERENCES users(id),
    discarded_at     TIMESTAMPTZ NULL,
    discarded_by     UUID        REFERENCES users(id),
    updated_at       TIMESTAMPTZ NULL,
    updated_by       UUID        REFERENCES users(id),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by       UUID        NOT NULL REFERENCES users(id),
    PRIMARY KEY (endpoint_id, version),
    CONSTRAINT chk_published_endpoint_versions_version CHECK (version >= 1),
    CONSTRAINT chk_published_endpoint_versions_pin CHECK (pipeline_version >= 1),
    -- A DRAFT has never been released; every other row has (released_at survives discard/restore).
    CONSTRAINT chk_published_endpoint_versions_release_stamps CHECK (
        (status = 'DRAFT' AND released_at IS NULL AND released_by IS NULL)
        OR (status <> 'DRAFT' AND released_at IS NOT NULL)
    ),
    CONSTRAINT chk_published_endpoint_versions_discard_stamps CHECK (
        (status = 'DISCARDED' AND discarded_at IS NOT NULL)
        OR (status <> 'DISCARDED' AND discarded_at IS NULL AND discarded_by IS NULL)
    )
);

-- At most one working DRAFT per API (record §24, the V39 pattern): a second insert violates this and surfaces as
-- endpoint.version.draft_exists.
CREATE UNIQUE INDEX uq_published_endpoint_versions_one_draft
    ON published_endpoint_versions (endpoint_id) WHERE status = 'DRAFT';

-- "Which API versions pin this pipeline version?" — the per-version protection of a pipeline discard/purge (§14).
CREATE INDEX idx_published_endpoint_versions_pin ON published_endpoint_versions (pipeline_name, pipeline_version);

ALTER TABLE published_endpoints ADD COLUMN current_version INTEGER NULL;
ALTER TABLE published_endpoints
    ADD CONSTRAINT chk_published_endpoints_current_version CHECK (current_version IS NULL OR current_version >= 1);
ALTER TABLE published_endpoints
    ADD CONSTRAINT fk_published_endpoints_current_version
        FOREIGN KEY (id, current_version) REFERENCES published_endpoint_versions (endpoint_id, version)
        DEFERRABLE INITIALLY DEFERRED;
