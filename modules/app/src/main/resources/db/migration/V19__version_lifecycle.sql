-- V19__version_lifecycle.sql (101, versioning §3 as ruled by D57–D60)
--
-- Four changes, in dependency order:
--
-- 1. Discard stamps on `pipeline_versions` / `template_versions` (§3.1): `discarded_at` /
--    `discarded_by`, both NULL unless the row is DISCARDED. Pre-existing DISCARDED rows
--    (the pre-101 executed-draft tombstones §3.4 used to flip) are backfilled
--    `discarded_at = COALESCE(updated_at, NOW())` so the CHECK can require a stamp.
-- 2. The entity soft-delete (`pipelines.is_deleted` / `templates.is_deleted`, V1) is
--    RETIRED: any soft-deleted row is migrated to "every version DISCARDED" with a NULL
--    pointer (§3.2 — entity DISCARDED is derived, never stored). Expected count outside
--    tests: ZERO; counted by the RAISE NOTICE below so the number lands in the migration
--    log either way.
-- 3. The two partial indexes that filtered on `is_deleted` are rebuilt without it.
-- 4. The column itself is dropped — every reader moved to the derived entity status.

-- 1a. Pipeline version discard stamps + backfill of pre-101 tombstones.
ALTER TABLE pipeline_versions
    ADD COLUMN discarded_at TIMESTAMPTZ NULL,
    ADD COLUMN discarded_by UUID NULL REFERENCES users(id);

UPDATE pipeline_versions
   SET discarded_at = COALESCE(updated_at, NOW())
 WHERE status = 'DISCARDED' AND discarded_at IS NULL;

ALTER TABLE pipeline_versions
    ADD CONSTRAINT chk_pipeline_versions_discard_stamps CHECK (
        (status = 'DISCARDED' AND discarded_at IS NOT NULL)
        OR (status <> 'DISCARDED' AND discarded_at IS NULL AND discarded_by IS NULL)
    );

-- 1b. Template version discard stamps (no pre-existing DISCARDED template rows can exist —
--     template discard was always a hard delete — but the backfill runs anyway: a restore
--     of a migrated soft-deleted template's version needs a stamp, and the statement is a
--     no-op on an empty set).
ALTER TABLE template_versions
    ADD COLUMN discarded_at TIMESTAMPTZ NULL,
    ADD COLUMN discarded_by UUID NULL REFERENCES users(id);

UPDATE template_versions
   SET discarded_at = COALESCE(updated_at, NOW())
 WHERE status = 'DISCARDED' AND discarded_at IS NULL;

ALTER TABLE template_versions
    ADD CONSTRAINT chk_template_versions_discard_stamps CHECK (
        (status = 'DISCARDED' AND discarded_at IS NOT NULL)
        OR (status <> 'DISCARDED' AND discarded_at IS NULL AND discarded_by IS NULL)
    );

-- 2. Soft-deleted entities → every version DISCARDED, pointer NULL (§3.2). The stamps use
--    NOW(): the migration is the discard actor's stand-in and `discarded_by` stays NULL.
DO $$
DECLARE
    pipelines_migrated INTEGER;
    templates_migrated INTEGER;
BEGIN
    UPDATE pipeline_versions v
       SET status = 'DISCARDED', discarded_at = NOW()
      FROM pipelines p
     WHERE p.id = v.pipeline_id AND p.is_deleted = TRUE
       AND v.status <> 'DISCARDED';
    GET DIAGNOSTICS pipelines_migrated = ROW_COUNT;

    UPDATE template_versions v
       SET status = 'DISCARDED', discarded_at = NOW()
      FROM templates t
     WHERE t.id = v.template_id AND t.is_deleted = TRUE
       AND v.status <> 'DISCARDED';
    GET DIAGNOSTICS templates_migrated = ROW_COUNT;

    -- A soft-deleted entity's pointer must not name a DISCARDED version (§3.4 invariant);
    -- for the entities just migrated every version is DISCARDED, so the pointer goes NULL.
    UPDATE pipelines SET current_version = NULL WHERE is_deleted = TRUE;
    UPDATE templates SET current_version = NULL WHERE is_deleted = TRUE;

    RAISE NOTICE 'V19: soft-deleted entities migrated (expected 0 outside tests): pipelines=%, templates=%',
        pipelines_migrated, templates_migrated;
END $$;

-- 3. Rebuild the two partial indexes without `is_deleted` (same columns as before; the
--    derived-live predicate is answered through the (pipeline_id) / (template_id) PK probes).
DROP INDEX idx_pipelines_owner;
CREATE INDEX idx_pipelines_owner ON pipelines(owner_id);

DROP INDEX idx_templates_active;
CREATE INDEX idx_templates_active ON templates(id);

-- 4. The column is gone; entity DISCARDED is derived (NOT EXISTS a live version row).
ALTER TABLE pipelines DROP COLUMN is_deleted;
ALTER TABLE templates DROP COLUMN is_deleted;
