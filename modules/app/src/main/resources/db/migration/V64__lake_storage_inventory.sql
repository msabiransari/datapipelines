-- =============================================================================
-- V64 — the lake storage breakdown (#964, dp-lake P6; design record §11b call 46 "The storage breakdown";
-- lane cut L12)
--
-- lake_storage_inventory — the LATEST inventory per managed table, not a history: one row per table and row name,
-- six rows per table, written by the cleanup's observer after the cleanup's own expiry and removals (one listing,
-- one walk). Each row: the listed objects that fall in it (first fit, so the six add up to the listing), their
-- summed bytes, the inventory's ONE time (the catalog's clock at the cleanup's listing) and the run id. A cleanup
-- replaces the table's six rows in one transaction; a table with no row has not been measured ("not measured yet",
-- never zero). run_id names a lake_maintenance_runs row without referencing it (the figures outlive the run row).
--
-- Down rehearsal (documented, not shipped — Flyway runs forward only):
--   DROP TABLE lake_storage_inventory;
--   DELETE FROM flyway_schema_history WHERE version = '64';
-- =============================================================================

CREATE TABLE lake_storage_inventory (
    table_id     UUID        NOT NULL REFERENCES lake_tables_managed(id) ON DELETE CASCADE,
    row_name     TEXT        NOT NULL,
    object_count BIGINT      NOT NULL,
    bytes        BIGINT      NOT NULL,
    measured_at  TIMESTAMPTZ NOT NULL,
    run_id       UUID        NOT NULL,
    PRIMARY KEY (table_id, row_name),
    CONSTRAINT chk_lake_storage_inventory_row CHECK (
        row_name IN ('dp_records', 'table_metadata', 'current', 'history', 'keep_held', 'not_referenced')),
    CONSTRAINT chk_lake_storage_inventory_figures CHECK (object_count >= 0 AND bytes >= 0)
);
