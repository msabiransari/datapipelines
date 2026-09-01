-- V6__version_lifecycle.sql
--
-- The draft/release version lifecycle (versioning.md §3–§9, implemented 035).
-- metadata-db.md §4.5/§4.9 remain the DDL authority; this migration is the mechanical
-- projection of the columns and indexes declared there.
--
-- What lands on BOTH version tables:
--   status        DRAFT | RELEASED | DISCARDED, defaulting RELEASED so every existing
--                 row backfills with zero data-migration semantics (§3.1).
--   body_hash     SHA-256 (hex) of the canonical body, computed BY THE DATABASE (see
--                 below), NOT NULL after the backfill.
--   released_at   set by the database (NOW()) at release — never application-supplied,
--                 because §8's draft-run derivation compares it against the
--                 application-supplied pipeline_executions.started_at, and at most one
--                 of the two timestamps may span a clock.
--   released_by   the human actor of the release (agents never release — D4).
--   updated_by / updated_at  the last DRAFT writer — powers the 409 conflict details
--                 (§4.2); NULL on RELEASED/DISCARDED rows, which are never UPDATEd
--                 except the one-way status transition itself.
--
-- The one-draft partial unique indexes (§3.3) are what make the copy-on-write write
-- rule race-safe: two simultaneous first-writers both insert, one wins, the loser
-- violates the index and surfaces as pipeline.version.conflict pointing at the
-- winner's hash.
--
-- ## Canonical hash — the backfill and the runtime share ONE expression
--
-- The canonical body is the database's JSONB text projection of the stored body, and
-- every hash in the system (backfill, create, draft write, import recompute) is
-- `encode(sha256(convert_to(<canonical jsonb>::text, 'UTF8')), 'hex')` computed by Postgres.
-- A JSONB column does not preserve the writer's key order, so hashing the
-- PipelineSerializer string in the application but `body_json::text` here would give
-- two different hashes for one body — and every pre-migration row would fail its
-- first precondition check (the exact failure §4.1/A2 exists to prevent). One
-- expression, one place: the repositories' write statements repeat the expressions
-- below verbatim, and PipelineRepositoryIntegrationTest's backfill case proves a
-- pre-migration row passes its first precondition.
--
-- For templates the canonical body is the version-owned field object
-- {engine, dialect, is_library, imports, body} — display_name/description live on the
-- index row `templates` only (they are not part of the versioned artifact; §3.5's
-- metadata-rides-the-release rule has no template-side draft metadata to stage).
-- jsonb_build_object normalizes key order, so the projection is deterministic from
-- any writer.

-- ---------------------------------------------------------------------------
-- pipeline_versions
-- ---------------------------------------------------------------------------
ALTER TABLE pipeline_versions
    ADD COLUMN status TEXT NOT NULL DEFAULT 'RELEASED'
        CONSTRAINT chk_pipeline_versions_status CHECK (status IN ('DRAFT', 'RELEASED', 'DISCARDED')),
    ADD COLUMN body_hash TEXT,
    ADD COLUMN released_at TIMESTAMPTZ,
    ADD COLUMN released_by UUID REFERENCES users(id),
    ADD COLUMN updated_by UUID REFERENCES users(id),
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE pipeline_versions
   SET released_at = created_at,
       released_by = created_by,
       body_hash = encode(sha256(convert_to(body_json::text, 'UTF8')), 'hex');

ALTER TABLE pipeline_versions ALTER COLUMN body_hash SET NOT NULL;

CREATE UNIQUE INDEX uq_pipeline_versions_one_draft
    ON pipeline_versions (pipeline_id) WHERE status = 'DRAFT';

-- ---------------------------------------------------------------------------
-- template_versions
-- ---------------------------------------------------------------------------
ALTER TABLE template_versions
    ADD COLUMN status TEXT NOT NULL DEFAULT 'RELEASED'
        CONSTRAINT chk_template_versions_status CHECK (status IN ('DRAFT', 'RELEASED', 'DISCARDED')),
    ADD COLUMN body_hash TEXT,
    ADD COLUMN released_at TIMESTAMPTZ,
    ADD COLUMN released_by UUID REFERENCES users(id),
    ADD COLUMN updated_by UUID REFERENCES users(id),
    ADD COLUMN updated_at TIMESTAMPTZ;

UPDATE template_versions
   SET released_at = created_at,
       released_by = created_by,
       body_hash = encode(
           sha256(
               convert_to(
                   jsonb_build_object(
                       'engine', engine,
                       'dialect', dialect,
                       'is_library', is_library,
                       'imports', imports_json,
                       'body', body
                   )::text, 'UTF8'
               )
           ),
           'hex'
       );

ALTER TABLE template_versions ALTER COLUMN body_hash SET NOT NULL;

CREATE UNIQUE INDEX uq_template_versions_one_draft
    ON template_versions (template_id) WHERE status = 'DRAFT';
