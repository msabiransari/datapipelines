-- ====
-- V33 — lane 7b (#7), D-T7: transform template versions carry contract / invariants / tests
--
-- Authority: docs/superpowers/specs/2026-09-09-transform-nodes-design.md (RATIFIED), §2.2;
-- recorded as DDL in metadata-db.md §4.9.
--
-- Three jsonb columns and three rule changes:
--
--   contract_json / invariants_json / tests_json — the transform model's blocks. Non-null
--   exactly when the version's type is a transform type, enforced by chk_transform_blocks.
--   They are part of the version's CONTENT: body_hash (computed in Postgres, never in
--   Kotlin — V6's rule) gains them through the CASE arm of the hash expression, so an
--   sql/html row's hash is byte-identical before and after this migration (the migration
--   test recomputes every existing row and finds zero changes — V8's "any change touching
--   body_hash" warning is the reason the arm is conditional, and 7e's `implements` is NOT
--   in it by design: a citation is a claim about meaning, not a change of behaviour, R9).
--
--   chk_template_type — the type vocabulary is final for round one: 'sql', 'html', and the
--   two transform types 'jsonata' and 'javascript' (the record's §2.1; 'javascript' saves
--   are refused by the application until round two's engine ships — the CHECK admits the
--   value so round two is not a schema change).
--
--   chk_type_dialect — dialect is required iff the type is 'sql' (was: iff not 'html').

ALTER TABLE template_versions
    ADD COLUMN contract_json jsonb,
    ADD COLUMN invariants_json jsonb,
    ADD COLUMN tests_json jsonb,
    DROP CONSTRAINT chk_template_type,
    DROP CONSTRAINT chk_type_dialect,
    ADD CONSTRAINT chk_template_type CHECK (type IN ('sql','html','jsonata','javascript')),
    ADD CONSTRAINT chk_type_dialect CHECK (
        (type = 'sql' AND dialect IS NOT NULL) OR
        (type IN ('html','jsonata','javascript') AND dialect IS NULL)
    ),
    ADD CONSTRAINT chk_transform_blocks CHECK (
        (type IN ('jsonata','javascript')
            AND contract_json IS NOT NULL AND invariants_json IS NOT NULL AND tests_json IS NOT NULL)
        OR
        (type IN ('sql','html')
            AND contract_json IS NULL AND invariants_json IS NULL AND tests_json IS NULL)
    );

-- No data migration: every pre-V33 row is sql or html, so the three columns stay NULL and
-- satisfy chk_transform_blocks' second arm by construction.
