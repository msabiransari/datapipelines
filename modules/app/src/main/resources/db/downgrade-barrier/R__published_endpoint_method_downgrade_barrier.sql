-- =============================================================================
-- DOWNGRADE BARRIER for V65 (#1037, published-mutation-apis design record §7/§9)
--
-- A binary that predates V65 reads published_endpoints and endpoint_key_bindings WITHOUT a method
-- predicate: left to start on this metadata it serves a non-GET route as a GET route and honors a
-- mutation grant as a GET prefix grant (measured against the previous jar, handback 1037 r2). The
-- design's rule is that such a binary must either refuse to start or honor no non-GET metadata, and a
-- jar already shipped cannot be changed — so this migration is the refusal.
--
-- HOW: this is a REPEATABLE migration, so a Flyway that has applied it but does not RESOLVE it (every
-- binary built before it existed) fails its validation with "Detected applied migration not resolved
-- locally" — Flyway's default `ignoreMigrationPatterns` ignores only FUTURE versioned migrations, never a
-- missing repeatable — and the application does not start. The current binary resolves it, so it starts.
--
-- WHERE: its own Flyway location (spring.flyway.locations lists db/migration and db/downgrade-barrier), because
-- the test fixtures that apply the shipped scripts by hand read db/migration for V<version>__<description>.sql only.
--
-- ROLLBACK is therefore a deliberate operator procedure (docs/metadata-db.md, "Rolling back V65"): drain
-- writes, run V65's documented down path, delete this migration's and V65's rows from
-- flyway_schema_history, then start the previous binary. The statement below is only a marker visible in
-- the schema; it is idempotent, and changing it re-runs it (Flyway's checksum rule for repeatables).
-- =============================================================================

COMMENT ON COLUMN published_endpoints.method IS
    'Route method, half of a published route''s identity (#1037, V65). A binary built before V65 must not run on this schema: see the downgrade barrier migration and docs/metadata-db.md "Rolling back V65".';
