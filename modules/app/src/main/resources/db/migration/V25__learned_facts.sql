-- =============================================================================
-- V25 — Learned semantic layer, round 1 (118): the facts an agent learned about
-- a datasource, kept beside its live metadata
--
-- Authority: docs/superpowers/specs/2026-09-11-learned-semantic-layer-design.md
-- v0.1 (§3 the store, §4 the closed kind list, §5 trust), recorded as DDL in
-- metadata-db.md §4.18.
--
-- One table, two scopes (D-S1): a DATASOURCE fact describes the data and is
-- bound to the datasource (visible wherever it is granted); a WORKSPACE fact is
-- one organisation's meaning and is bound to that workspace. Nothing JDBC
-- metadata already provides is stored (D-S2): the kind list has no type,
-- nullable, key, comment or partition entry, and introspection stays the source
-- every fact is checked against (§6, the read-time drift check).
--
-- Facts are never edited in place (D-S6) and never hard-deleted by users
-- (D-S11): drift demotes `trust`, retirement stamps `retired_at`, and a
-- superseding fact links its predecessor through `supersedes`. A datasource
-- delete cascades its facts (the object is gone); a purged source pipeline
-- only detaches (SET NULL) — the fact outlives the pipeline that learned it.
-- =============================================================================

CREATE TABLE learned_facts (
    id                  UUID        PRIMARY KEY,
    scope               TEXT        NOT NULL,
    workspace_id        UUID        REFERENCES workspaces(id),                    -- NULL iff scope = DATASOURCE
    datasource_name     TEXT        NOT NULL REFERENCES datasources(name) ON DELETE CASCADE,
    kind                TEXT        NOT NULL,                                     -- §4, closed list
    fact                TEXT        NOT NULL,
    refs_json           JSONB       NOT NULL,                                     -- §3.1: [{schema?, table, column?}], ≥ 1
    evidence_sql        TEXT,                                                     -- the probe that showed it
    evidence_summary    TEXT,                                                     -- what the probe returned, ≤ 300 chars
    trust               TEXT        NOT NULL,                                     -- §5
    schema_fingerprint  TEXT        NOT NULL,                                     -- §3.2, per referenced table, at record time
    recorded_by         UUID        NOT NULL REFERENCES users(id),
    recorded_via        TEXT        NOT NULL,                                     -- WriteSurface: mcp | session | api_key
    recorded_in         UUID        NOT NULL REFERENCES workspaces(id),           -- the ACTIVE workspace at record time (provenance)
    source_pipeline_id  UUID        REFERENCES pipelines(id) ON DELETE SET NULL,
    source_version      INT,
    recorded_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    verified_by         UUID        REFERENCES users(id),
    verified_at         TIMESTAMPTZ,
    supersedes          UUID        REFERENCES learned_facts(id),
    retired_at          TIMESTAMPTZ,
    retired_reason      TEXT,
    CONSTRAINT chk_learned_facts_scope CHECK (scope IN ('DATASOURCE', 'WORKSPACE')),
    -- §4 — the closed kind list. enums.md §19 and the Kotlin enum are drift-tested against it.
    CONSTRAINT chk_learned_facts_kind CHECK (kind IN (
        'unit', 'time_zone', 'sampling', 'grain', 'window', 'enum_meaning', 'join', 'caveat', 'format',
        'definition', 'exclusion', 'preference'
    )),
    -- §4's scope column, stated once in the database: the three business kinds are WORKSPACE
    -- facts and the nine data kinds are DATASOURCE facts. A kind recorded under the wrong
    -- scope is refused by the service with `semantics.kind_invalid`; this is the belt.
    CONSTRAINT chk_learned_facts_kind_scope CHECK (
        (scope = 'WORKSPACE') = (kind IN ('definition', 'exclusion', 'preference'))
    ),
    CONSTRAINT chk_learned_facts_fact_length CHECK (length(fact) BETWEEN 8 AND 1000),
    CONSTRAINT chk_learned_facts_summary_length CHECK (evidence_summary IS NULL OR length(evidence_summary) <= 300),
    CONSTRAINT chk_learned_facts_refs CHECK (jsonb_typeof(refs_json) = 'array' AND jsonb_array_length(refs_json) >= 1),
    CONSTRAINT chk_learned_facts_trust CHECK (trust IN ('asserted', 'observed', 'verified', 'needs_review', 'stale', 'retired')),
    CONSTRAINT chk_learned_facts_via CHECK (recorded_via IN ('session', 'api_key', 'mcp')),
    CONSTRAINT chk_learned_facts_scope_workspace CHECK ((scope = 'WORKSPACE') = (workspace_id IS NOT NULL)),
    -- A retired fact carries its stamp and the stamp carries the state: neither half alone.
    CONSTRAINT chk_learned_facts_retired CHECK ((trust = 'retired') = (retired_at IS NOT NULL))
);

-- Every read is per datasource (the introspection enrichment, the listing, the drift
-- check), then narrowed to a table in the reader — the per-datasource fact set is small
-- by construction (hundreds, not millions), and a table filter over `refs_json` is a
-- JSON predicate no btree serves. The spec's expression index over `refs_json->0->>'table'`
-- would index only the FIRST ref of a multi-ref (join) fact and serve none of the reads
-- that ship, so the honest index is the datasource one.
CREATE INDEX idx_learned_facts_datasource ON learned_facts (datasource_name);
-- The workspace half: a WORKSPACE fact is listed by its workspace; DATASOURCE facts have none.
CREATE INDEX idx_learned_facts_workspace ON learned_facts (workspace_id) WHERE workspace_id IS NOT NULL;
