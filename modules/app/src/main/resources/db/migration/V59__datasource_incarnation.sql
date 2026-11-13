-- =============================================================================
-- V59 — datasource incarnation and generation (#874 G3, dependency-graph record §5.1, §5.3, §15.1, acceptance A09)
--
-- 1. incarnation_id — which INCARNATION of a datasource name a row is. A datasource is keyed by its deployment-global
--    flat name, and the name stays the primary key: three foreign keys reference datasources(name) (V15 lake_tables,
--    V23 datasource_workspaces, V25 learned_facts), every pipeline, template and parameter set names a datasource by
--    name inside its body, and promotion matches datasources across deployments by name (§5.1). The incarnation is a
--    LOCAL fact beside that key: a soft delete keeps it (and so would a restore), while a row recreated under the same
--    name gets a new one when its INSERT uses the DEFAULT. A backup restore supplying the stored incarnation keeps
--    that value: the DEFAULT never replaces an explicit value. It is never compared across deployments.
-- 2. generation — a protected local counter of the row's identity-bearing facts (§5.3: never a credential-derived
--    digest): the writers advance it by one inside their guarded section for a definition update, a credential
--    change, a soft delete and a grant or revoke that changed a row; a connection test (an observation) does not.
--
-- Both are INTERNAL: they feed the dependency graph's internal fingerprint and its diagnostics, never a wire DTO, a
-- log line or release consent (record erratum (7): release consent over a datasource is the named datasource's
-- existence). The DEFAULTs backfill every existing row in the same statement, soft-deleted rows included, so no row is
-- ever without either value. No index beyond the unique constraint: every read keys on name.
--
-- Down rehearsal: drop constraint uq_datasources_incarnation, drop columns generation and incarnation_id; remove
-- history 59.
-- =============================================================================
ALTER TABLE datasources ADD COLUMN incarnation_id UUID NOT NULL DEFAULT gen_random_uuid();
ALTER TABLE datasources ADD CONSTRAINT uq_datasources_incarnation UNIQUE (incarnation_id);
ALTER TABLE datasources ADD COLUMN generation BIGINT NOT NULL DEFAULT 1 CONSTRAINT chk_datasources_generation CHECK (generation >= 1);
