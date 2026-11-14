-- =============================================================================
-- V60 — lake maintenance profiles (#956, dp-lake P6; design record §11a call 35, lane cut L5)
--
-- 1. lake_profiles — the deployment's maintenance and write profiles: one row per profile, a name, the default
--    mark (the partial unique index uq_lake_profiles_one_default allows at most one; the service never leaves
--    none), the typed value document values_json (parsed into LakeProfile, lake.md "Profiles"; nothing else reads
--    it) and seed_pending (below). Per environment and never promoted. created_by / updated_by are NULL for a
--    write by the system: this migration's two inserts (no user exists on a fresh database) and the start-up seed.
-- 2. The built-in profiles: `production` (the default) and `development` (the same values, window 00:00-00:00,
--    the whole day). `production` carries seed_pending = true: a SQL migration cannot read the server's
--    configuration, so the first start with the lake enabled copies datapipelines.lake.maintenance.window and
--    .max-bytes-rewritten-per-run into it once, in one statement guarded by `WHERE seed_pending`, and clears the
--    mark (cut L5). Every service edit of a profile clears the mark too, so an edited profile is never overwritten.
-- 3. lake_tables_managed gains profile_id (NOT NULL, foreign key, no cascade) and profile_assigned_at. Every
--    existing row gets `production` and this migration's time. A new row takes the default profile in its own
--    INSERT, by a sub-select, so no row is ever without one.
-- 4. Every existing policy_json becomes '{}': its four keys were written once at creation, never edited and never
--    read (record call 35, "Migration (P6)" (3)), so none was ever in effect. From now on policy_json holds only the
--    table's two pause keys (record call 38), `pause.compaction_until` and `pause.cleanup_until`.
--
-- Down rehearsal (documented, not shipped — Flyway runs forward only):
--   DROP INDEX idx_lake_tables_managed_profile;
--   ALTER TABLE lake_tables_managed DROP COLUMN profile_assigned_at, DROP COLUMN profile_id;
--   DROP TABLE lake_profiles;
--   DELETE FROM flyway_schema_history WHERE version = '60';
--   (the dropped policy_json keys are not restored: nothing read them.)
-- =============================================================================

CREATE TABLE lake_profiles (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name         TEXT        NOT NULL,
    is_default   BOOLEAN     NOT NULL DEFAULT FALSE,
    values_json  JSONB       NOT NULL,
    seed_pending BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_by   UUID        NULL REFERENCES users(id),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by   UUID        NULL REFERENCES users(id),
    CONSTRAINT uq_lake_profiles_name UNIQUE (name),
    CONSTRAINT chk_lake_profiles_name CHECK (name ~ '^[a-z0-9_]{1,63}$'),
    CONSTRAINT chk_lake_profiles_values CHECK (jsonb_typeof(values_json) = 'object')
);

-- At most one default; the service moves the mark in one transaction and never clears it alone.
CREATE UNIQUE INDEX uq_lake_profiles_one_default ON lake_profiles (is_default) WHERE is_default;

INSERT INTO lake_profiles (name, is_default, seed_pending, values_json) VALUES
    ('production', TRUE, TRUE,
     '{"maintenance": {"window": "02:00-05:00", "max_bytes_rewritten_per_run": 53687091200,
                       "target_file_bytes": 67108864, "compact_min_small_files": 5,
                       "compact_small_file_ratio": 0.75, "rewrite_manifests_min_count": 100,
                       "cleanup_interval_hours": 24},
       "write": {"row_group_bytes": 1048576},
       "retention": {"keep": [], "keep_time_zone": "UTC"},
       "reporting": {"lateness_sample": 20, "lateness_overdue_factor": 2, "lateness_min_deliveries": 5}}'),
    ('development', FALSE, FALSE,
     '{"maintenance": {"window": "00:00-00:00", "max_bytes_rewritten_per_run": 53687091200,
                       "target_file_bytes": 67108864, "compact_min_small_files": 5,
                       "compact_small_file_ratio": 0.75, "rewrite_manifests_min_count": 100,
                       "cleanup_interval_hours": 24},
       "write": {"row_group_bytes": 1048576},
       "retention": {"keep": [], "keep_time_zone": "UTC"},
       "reporting": {"lateness_sample": 20, "lateness_overdue_factor": 2, "lateness_min_deliveries": 5}}');

ALTER TABLE lake_tables_managed ADD COLUMN profile_id UUID NULL REFERENCES lake_profiles(id);
ALTER TABLE lake_tables_managed ADD COLUMN profile_assigned_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

UPDATE lake_tables_managed
   SET profile_id = (SELECT id FROM lake_profiles WHERE name = 'production'),
       profile_assigned_at = NOW(),
       policy_json = '{}'::jsonb;

ALTER TABLE lake_tables_managed ALTER COLUMN profile_id SET NOT NULL;

-- "Which tables does this profile govern" (the P8 screen, a future delete's in-use refusal); also serves the FK.
CREATE INDEX idx_lake_tables_managed_profile ON lake_tables_managed (profile_id);
