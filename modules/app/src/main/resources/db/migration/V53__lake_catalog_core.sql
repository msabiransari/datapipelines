-- =============================================================================
-- V53 — the managed lake's catalog core (#785, dp-lake P2)
--
-- Authority: the dp-lake design record (draft 16) §5.1, §5.2, §8a; the SQL is the E1 (#774) harness's
-- (design-records/dp-lake-evidence/774-e1-fault-injection, Schema.java), where every statement below
-- was falsified red/green. V52 belongs to #778 (the reference scanner); this file takes V53.
--
-- 1. Iceberg's JdbcCatalog tables, created HERE so the catalog runs with jdbc.init-catalog-tables=false
--    and issues no DDL at start-up. The shape is Iceberg 1.12.0's V1 (JdbcUtil V0_CREATE_CATALOG_SQL +
--    V1_UPDATE_CATALOG_SQL, CREATE_NAMESPACE_PROPERTIES_TABLE_SQL): with the `iceberg_type` column
--    present, JdbcCatalog.updateSchemaIfRequired selects SchemaVersion.V1 from the column itself and
--    alters nothing (P2's A0 check, CatalogShapeIntegrationTest, pins it against the library).
--
-- 2. lake_control (one row: the catalog's mode), lake_leases (the §8a lease rows), and
--    lake_table_revisions (the revision log, written ONLY by the trigger, never pruned). The other §5.2
--    tables (namespaces, managed tables, definition versions, semantics, deliveries, attempts,
--    maintenance runs, storage usage, the lake_tables columns) belong to P3/P4/P6: no trigger reads them.
--
-- 3. The fence: dp_fence() BEFORE INSERT OR UPDATE OR DELETE on BOTH catalog tables — a write needs the
--    session's dp.lease_token + dp.operation_id (else SQLSTATE DP001), a catalog not in `restoring` mode
--    (else DP002), and a live, undeadlined lease of THAT operation on the row's name, locked FOR SHARE
--    in the same statement (else DP001). A rename (an UPDATE that changes the table's name) needs the
--    lease on the OLD name too (E1 #774 F5). The lock is what serialises a takeover with an in-flight
--    catalog write (§8a cases C2, E): the takeover UPDATE of the lease row waits for it.
--
-- 4. The revision log: dp_revlog() AFTER INSERT OR UPDATE OF metadata_location, dp_revlog_drop()
--    AFTER DELETE, on iceberg_tables — inside the commit's own transaction, so the log is complete by
--    construction; protected_until is left NULL (= protected) for a later statement to finalise (§8a).
--
-- Down path (documented, not shipped — Flyway runs forward only):
--   DROP TABLE lake_table_revisions; DROP TABLE lake_leases; DROP TABLE lake_control;
--   DROP TABLE iceberg_namespace_properties; DROP TABLE iceberg_tables;
--   DROP FUNCTION dp_fence(); DROP FUNCTION dp_revlog(); DROP FUNCTION dp_revlog_drop();
--   DELETE FROM flyway_schema_history WHERE version = '53';
-- =============================================================================

-- Iceberg 1.12.0's V1 catalog shape, byte for byte the library's own DDL (column names, types, PK).
CREATE TABLE iceberg_tables (
    catalog_name               VARCHAR(255) NOT NULL,
    table_namespace            VARCHAR(255) NOT NULL,
    table_name                 VARCHAR(255) NOT NULL,
    metadata_location          VARCHAR(1000),
    previous_metadata_location VARCHAR(1000),
    iceberg_type               VARCHAR(5),
    PRIMARY KEY (catalog_name, table_namespace, table_name)
);
COMMENT ON TABLE iceberg_tables IS 'Iceberg JdbcCatalog pointer rows (V1 shape); written only by the library under a lake lease (dp_fence)';

CREATE TABLE iceberg_namespace_properties (
    catalog_name   VARCHAR(255) NOT NULL,
    namespace      VARCHAR(255) NOT NULL,
    property_key   VARCHAR(255),
    property_value VARCHAR(1000),
    PRIMARY KEY (catalog_name, namespace, property_key)
);
COMMENT ON TABLE iceberg_namespace_properties IS 'Iceberg JdbcCatalog namespace properties; written only by the library under a namespace lease (dp_fence)';

-- The catalog's mode (§9): normal | restoring | replaying. ONE row, inserted here.
CREATE TABLE lake_control (
    id                  INT         PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    mode                TEXT        NOT NULL DEFAULT 'normal' CHECK (mode IN ('normal', 'restoring', 'replaying')),
    protection_interval INTERVAL    NOT NULL DEFAULT INTERVAL '7 days',
    last_sweep_at       TIMESTAMPTZ,
    restored_marker_at  TIMESTAMPTZ,
    restore_epoch       UUID        NOT NULL DEFAULT gen_random_uuid()
);
INSERT INTO lake_control (id, mode) VALUES (1, 'normal');

-- §8a: one lease per (kind, name); tokens random (a restore cannot rewind them); deadline_at never extended.
CREATE TABLE lake_leases (
    kind            TEXT        NOT NULL CHECK (kind IN ('table', 'namespace')),
    name            TEXT        NOT NULL,
    operation_id    UUID        NOT NULL,
    token           UUID        NOT NULL,
    acquired_at     TIMESTAMPTZ NOT NULL,
    expires_at      TIMESTAMPTZ NOT NULL,
    deadline_at     TIMESTAMPTZ NOT NULL,
    operation       TEXT        NOT NULL,
    holder_instance TEXT        NOT NULL,
    PRIMARY KEY (kind, name)
);
-- Release is by operation id (§8a: DELETE … WHERE operation_id = ?).
CREATE INDEX idx_lake_leases_operation ON lake_leases (operation_id);

-- §5.2: written by the revision trigger inside the commit transaction; never by application code; never pruned.
CREATE TABLE lake_table_revisions (
    seq                        BIGSERIAL   PRIMARY KEY,
    namespace                  TEXT        NOT NULL,
    table_name                 TEXT        NOT NULL,
    metadata_location          TEXT        NOT NULL,
    previous_metadata_location TEXT,
    became_current_at          TIMESTAMPTZ NOT NULL,
    superseded_at              TIMESTAMPTZ,
    protected_until            TIMESTAMPTZ,
    operation_id               UUID,
    dropped_at                 TIMESTAMPTZ
);
CREATE INDEX idx_lake_table_revisions_table ON lake_table_revisions (namespace, table_name, seq);
CREATE INDEX idx_lake_table_revisions_operation ON lake_table_revisions (operation_id);
-- At most one CURRENT revision per table: the trigger supersedes the previous row before inserting the next.
CREATE UNIQUE INDEX uq_lake_table_revisions_current ON lake_table_revisions (namespace, table_name)
    WHERE superseded_at IS NULL;

-- §8a fence. Step 3's OLD-name check on a rename is E1's case (x) fix (F5).
CREATE FUNCTION dp_fence() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE tok text; op text; m text; got uuid; k text; nm text;
BEGIN
  tok := current_setting('dp.lease_token', true);
  op := current_setting('dp.operation_id', true);
  IF coalesce(tok, '') = '' OR coalesce(op, '') = '' THEN
    RAISE EXCEPTION 'dp fence: % on % without a lease token', TG_OP, TG_TABLE_NAME USING ERRCODE = 'DP001';
  END IF;
  SELECT mode INTO m FROM lake_control;
  IF m = 'restoring' THEN
    RAISE EXCEPTION 'dp fence: catalog is restoring' USING ERRCODE = 'DP002';
  END IF;
  IF TG_TABLE_NAME = 'iceberg_tables' THEN
    k := 'table';
    nm := CASE WHEN TG_OP = 'DELETE' THEN OLD.table_namespace || '.' || OLD.table_name
               ELSE NEW.table_namespace || '.' || NEW.table_name END;
  ELSE
    k := 'namespace';
    nm := CASE WHEN TG_OP = 'DELETE' THEN OLD.namespace ELSE NEW.namespace END;
  END IF;
  SELECT token INTO got FROM lake_leases
   WHERE kind = k AND name = nm AND expires_at > now() AND deadline_at > now() AND operation_id::text = op
   FOR SHARE;
  IF got IS NULL OR got::text <> tok THEN
    RAISE EXCEPTION 'dp fence: no live lease % % for this operation', k, nm USING ERRCODE = 'DP001';
  END IF;
  -- Nested, not one AND-chain: PL/pgSQL plans an IF condition as ONE expression, so OLD.table_namespace in it
  -- fails on iceberg_namespace_properties (no such field) whatever TG_TABLE_NAME says (#785, found by
  -- LakeFenceIntegrationTest; E1 ran this check only with the namespace table unwritten).
  IF TG_OP = 'UPDATE' AND TG_TABLE_NAME = 'iceberg_tables' THEN
    IF (OLD.table_namespace, OLD.table_name) IS DISTINCT FROM (NEW.table_namespace, NEW.table_name)
       AND NOT EXISTS (SELECT 1 FROM lake_leases WHERE kind = 'table'
                         AND name = OLD.table_namespace || '.' || OLD.table_name
                         AND expires_at > now() AND deadline_at > now() AND operation_id::text = op) THEN
      RAISE EXCEPTION 'dp fence: rename of % without its lease', OLD.table_namespace || '.' || OLD.table_name
        USING ERRCODE = 'DP001';
    END IF;
  END IF;
  IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER dp_fence BEFORE INSERT OR UPDATE OR DELETE ON iceberg_tables
    FOR EACH ROW EXECUTE FUNCTION dp_fence();
CREATE TRIGGER dp_fence BEFORE INSERT OR UPDATE OR DELETE ON iceberg_namespace_properties
    FOR EACH ROW EXECUTE FUNCTION dp_fence();

-- §8a revision log: a pointer move supersedes the current revision and inserts the next, protection NULL.
CREATE FUNCTION dp_revlog() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  UPDATE lake_table_revisions SET superseded_at = clock_timestamp(), protected_until = NULL
   WHERE namespace = NEW.table_namespace AND table_name = NEW.table_name AND superseded_at IS NULL;
  INSERT INTO lake_table_revisions(namespace, table_name, metadata_location, previous_metadata_location,
                                   became_current_at, operation_id)
  VALUES (NEW.table_namespace, NEW.table_name, NEW.metadata_location,
          CASE WHEN TG_OP = 'UPDATE' THEN OLD.metadata_location END, clock_timestamp(),
          nullif(current_setting('dp.operation_id', true), '')::uuid);
  RETURN NEW;
END $$;
CREATE TRIGGER dp_revlog AFTER INSERT OR UPDATE OF metadata_location ON iceberg_tables
    FOR EACH ROW EXECUTE FUNCTION dp_revlog();

-- §8a AFTER DELETE: the final revision is stamped superseded and dropped, protection NULL.
CREATE FUNCTION dp_revlog_drop() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  UPDATE lake_table_revisions SET superseded_at = clock_timestamp(), dropped_at = clock_timestamp()
   WHERE namespace = OLD.table_namespace AND table_name = OLD.table_name AND superseded_at IS NULL;
  RETURN OLD;
END $$;
CREATE TRIGGER dp_revlog_drop AFTER DELETE ON iceberg_tables FOR EACH ROW EXECUTE FUNCTION dp_revlog_drop();
