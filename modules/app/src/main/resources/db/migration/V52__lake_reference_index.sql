-- =============================================================================
-- V52 — the lake reference index (#778, dp-lake L8b)
--
-- Authority: the reference-scanner design slice (orchestration store
-- design-records/2026-11-02-778-reference-scanner-slice.md, DRAFT 1) D-C, under the dp-lake design
-- record §7 "Pipeline-to-table check" (ruling 7, 2026-11-01). Two tables:
--
-- 1. `lake_reference_scans` — one row per scanned RELEASED version of a pipeline or a parameter
--    set: the scan's status (exact | partial | unscannable), the classifier's and the serializer's
--    findings (`reasons_json`), and the `scanner_version` that produced it, so a scanner change re-scans
--    by number (the boot backfill picks rows below the current number). RELEASED version rows are
--    never UPDATEd (metadata-db §4.5), which is why the index is its own table.
--
-- 2. `lake_table_references` — one row per table position a scanned rendering names: the node
--    (or selector) and the template version it came from, the LAKE datasource BY NAME (datasources
--    map between environments by name, versioning §10.5), the written triple, and — when the
--    position names a lake table at all — the resolved registry identity (namespace TEXT[], table).
--    Re-scanning a version replaces its rows (ON DELETE CASCADE from the scan row).
--
-- Deviation from the slice's D-C, recorded in the lane's handback: both tables carry
-- `workspace_id`, and a scan is keyed by the entity's id, not its name — pipeline and set names
-- are unique per WORKSPACE only, and a release may carry a rename; `name` is kept for display.
--
-- Down path (documented, not shipped — Flyway runs forward only):
--   DROP TABLE lake_table_references; DROP TABLE lake_reference_scans;
-- =============================================================================

CREATE TABLE lake_reference_scans (
    workspace_id    UUID        NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,
    family          TEXT        NOT NULL,
    entity_id       UUID        NOT NULL,
    version         INTEGER     NOT NULL,
    name            TEXT        NOT NULL,
    status          TEXT        NOT NULL,
    reasons_json    JSONB       NOT NULL DEFAULT '[]'::jsonb,
    scanner_version INTEGER     NOT NULL,
    scanned_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT lake_reference_scans_pkey PRIMARY KEY (workspace_id, family, entity_id, version),
    CONSTRAINT chk_lake_reference_scans_family CHECK (family IN ('pipeline', 'parameter_set')),
    CONSTRAINT chk_lake_reference_scans_status CHECK (status IN ('exact', 'partial', 'unscannable')),
    CONSTRAINT chk_lake_reference_scans_version CHECK (version >= 1),
    CONSTRAINT chk_lake_reference_scans_scanner_version CHECK (scanner_version >= 1),
    CONSTRAINT chk_lake_reference_scans_reasons CHECK (jsonb_typeof(reasons_json) = 'array')
);

CREATE TABLE lake_table_references (
    workspace_id     UUID     NOT NULL,
    family           TEXT     NOT NULL,
    entity_id        UUID     NOT NULL,
    version          INTEGER  NOT NULL,
    ordinal          INTEGER  NOT NULL,
    node_id          TEXT,
    selector         TEXT,
    template_id      TEXT     NOT NULL,
    template_version INTEGER  NOT NULL,
    datasource_name  TEXT     NOT NULL,
    written_catalog  TEXT,
    written_schema   TEXT,
    written_table    TEXT     NOT NULL,
    resolved         BOOLEAN  NOT NULL,
    namespace        TEXT[],
    table_name       TEXT,
    time_travel      BOOLEAN  NOT NULL DEFAULT FALSE,
    statement_index  INTEGER  NOT NULL,
    CONSTRAINT lake_table_references_pkey PRIMARY KEY (workspace_id, family, entity_id, version, ordinal),
    CONSTRAINT fk_lake_table_references_scan FOREIGN KEY (workspace_id, family, entity_id, version)
        REFERENCES lake_reference_scans (workspace_id, family, entity_id, version) ON DELETE CASCADE,
    -- A reference site is a pipeline node OR a parameter-set selector, never both, never neither.
    CONSTRAINT chk_lake_table_references_site CHECK ((node_id IS NULL) <> (selector IS NULL)),
    -- A resolved row names a registry identity; an unresolved row (CTE, system schema, ambiguous bare name) names none.
    CONSTRAINT chk_lake_table_references_resolved CHECK (
        (resolved AND namespace IS NOT NULL AND cardinality(namespace) >= 1 AND table_name IS NOT NULL)
        OR (NOT resolved AND namespace IS NULL AND table_name IS NULL)),
    CONSTRAINT chk_lake_table_references_counters CHECK (ordinal >= 0 AND statement_index >= 0 AND template_version >= 1)
);

-- The receiver-side lookup and the future "who reads this table" surface (P8): by datasource and identity.
CREATE INDEX idx_lake_table_references_table ON lake_table_references (datasource_name, namespace, table_name);
