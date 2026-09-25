-- =============================================================================
-- V36 — lane 7e (#7), R9: a transform version cites the learned facts it implements
--
-- Authority: docs/superpowers/specs/2026-09-09-transform-nodes-design.md (RATIFIED), §2.3,
-- §8.2, §8.3; recorded as DDL in metadata-db.md §4.21.
--
-- template_implements — one row per (template version, cited fact). A join table rather than a
-- JSON column so the reverse arrow ("which transforms implement this definition?", §8.3's
-- `implemented_by` and the `templates_list implements=` filter) is an index read on fact_id.
--
-- NOT content. The citation is a claim about meaning, never a change of behaviour (R9): it is
-- outside `body_hash` (no hash expression reads this table — V33's header says so and the
-- hash-equality integration test pins it), and it is the one thing a RELEASED version accepts
-- after release (templates.md §5.1).
--
-- The keys:
--   (template_id, version) → template_versions ON DELETE CASCADE — a purged draft (the hard
--     delete of versioning §5.4) and an entity purge take their citations with them.
--   fact_id → learned_facts, NO cascade — facts are never deleted by the product (D-S11:
--     retire is an UPDATE; datasource delete is soft). A retired fact stays cited, and reading
--     that citation is what marks the version `needs_review` (§8.2, computed on read — nothing
--     here stores the mark).
--
-- Which facts may be cited (a visible WORKSPACE fact of kind definition / exclusion /
-- preference) is the application's rule, `template.implements_unresolved` at write time; a
-- CHECK cannot read another table.
--
-- idx_learned_facts_supersedes — the successor lookup: a retired citation names the row whose
-- `supersedes` is the retired id (there is no `superseded_by` column). Partial, because almost
-- no fact supersedes another; without it every read of a version with a retired citation scans
-- learned_facts.
--
-- DOWN PATH (manual): DROP INDEX idx_learned_facts_supersedes; DROP TABLE template_implements;
-- — lossless for content (no hash, no body reads the table); the citations themselves go.
-- =============================================================================

CREATE TABLE template_implements (
    template_id UUID    NOT NULL,
    version     INTEGER NOT NULL,
    fact_id     UUID    NOT NULL,
    PRIMARY KEY (template_id, version, fact_id),
    CONSTRAINT fk_template_implements_version FOREIGN KEY (template_id, version)
        REFERENCES template_versions (template_id, version) ON DELETE CASCADE,
    CONSTRAINT fk_template_implements_fact FOREIGN KEY (fact_id)
        REFERENCES learned_facts (id)
);

-- The reverse arrow: every version citing one fact (§8.3).
CREATE INDEX idx_template_implements_fact ON template_implements (fact_id);

-- The successor of a retired fact (§8.2's detail).
CREATE INDEX idx_learned_facts_supersedes ON learned_facts (supersedes) WHERE supersedes IS NOT NULL;
