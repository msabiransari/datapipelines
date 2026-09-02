-- V8__typed_templates.sql
--
-- Typed templates (template-hierarchy-design.md §5.1, round 046). A template version declares
-- its kind: 'sql' (the only kind before this migration — a Freemarker body that renders SQL
-- for pipeline nodes) or 'html' (a body that renders escaped output through a second,
-- auto-escaping engine configuration; §6). Nothing serves 'html' anywhere yet — the acceptance
-- bar for the type is the schema, the second configuration, and a preview render proving
-- escaping (design §2).

ALTER TABLE template_versions
    ADD COLUMN type TEXT NOT NULL DEFAULT 'sql',
    ALTER COLUMN dialect DROP NOT NULL,
    DROP CONSTRAINT chk_dialect,
    ADD CONSTRAINT chk_dialect CHECK (
        dialect IS NULL OR dialect IN ('POSTGRES','ORACLE','MSSQL','MYSQL','H2','DUCKDB','SQLITE')
    ),
    ADD CONSTRAINT chk_template_type CHECK (type IN ('sql','html')),
    ADD CONSTRAINT chk_type_dialect CHECK (
        (type = 'sql'  AND dialect IS NOT NULL) OR
        (type = 'html' AND dialect IS NULL)
    );

-- Existing rows backfill to 'sql' via the column default — no data migration. Every stored
-- template before V8 is SQL by construction, so the default IS the truthful value.
--
-- chk_type_dialect makes the type-conditional contract (§5.1: dialect required iff sql) a
-- database invariant rather than an application-level hope — the same two-layer discipline the
-- version lifecycle and the name grammar follow.
--
-- Deliberately NOT here: any change touching body_hash. The hash inputs stay exactly
-- {engine, dialect, is_library, imports, body} (§5.2, normative): 'type' is constant across
-- every version of a template, so it cannot distinguish anything the hash is ever compared
-- for, and touching the expression would break the draft-on-change no-op guard for every
-- pre-V8 row. An html template's absent dialect hashes as JSON null — deterministically, and
-- identically on both sides of every comparison, because the write path binds the same null.
