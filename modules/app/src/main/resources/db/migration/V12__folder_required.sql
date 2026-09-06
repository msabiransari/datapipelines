-- V12__folder_required.sql
--
-- A folder is mandatory (template-hierarchy-design.md §4.1, round 077, owner ruling
-- 2026-09-05). The name grammar narrows from 1–10 `/`-separated segments to 2–10: the first
-- segment is a folder, the last is the asset. `active_users` is refused where it was accepted;
-- `test/active_users` is the form. Nothing else in the grammar moves.
--
-- No DDL. `templates.name` stays TEXT and `uq_templates_workspace_name` is unchanged (§4.3) —
-- the full path IS the name, and a folder is still a name prefix with no table, no column and
-- no id (§3.1). What this migration carries is the §4.6 gate, and nothing else.
--
-- WHY TEMPLATES NEED A GATE AND PIPELINES DO NOT (§4.6, §14.2). A template name is
-- re-validated on three paths and two of them run at RENDER time: `RegistryTemplateLoader
-- .parseKey` (a name outside the grammar returns null, so the template does not resolve and
-- the pipeline fails) and the import-prologue synthesis (`TemplateImport.isSafeToSynthesize`,
-- which drops the import). A stored flat template would therefore break execution of
-- already-released, already-promoted pipelines after an upgrade, and §4.5 forbids rename, so
-- there is no in-place repair afterwards. A PIPELINE name is validated at SAVE only: nothing
-- on the execute path consults the grammar, `PipelineResolver` looks a child reference up by
-- name without re-checking its shape, and pipelines are UUID-addressed over HTTP. A legacy
-- flat pipeline keeps listing, keeps opening and keeps executing; its next save is refused
-- with `pipeline.validation.name_invalid` naming the value. So this gate deliberately does
-- NOT look at `pipelines` — a check there would abort a deployment over a row that still
-- works.
--
-- This is V7's gate re-issued, not V7 edited: an applied migration's text is frozen by its
-- Flyway checksum, and V7's file is a faithful record of the 1-to-10-segment rule 043
-- enforced. The pattern below is the CURRENT §4.1 pattern, which is what
-- `TemplateNameGrammarSpecDriftTest` and `PipelineNameGrammarSpecDriftTest` hold both Kotlin
-- copies to.

-- §4.6 legacy-name gate. Runs FIRST, before any DDL, so a violating deployment
-- fails with an actionable message and an unchanged schema.
-- is_deleted is NOT filtered: lookupVersion resolves soft-deleted templates for
-- pinned refs, so their names are still subject to the loader's grammar.
DO $$
DECLARE offenders TEXT;
BEGIN
    SELECT string_agg(name, ', ' ORDER BY name) INTO offenders
      FROM templates
     WHERE length(name) > 200
        OR name !~ '^[a-z0-9][a-z0-9_.-]{0,63}(/[a-z0-9][a-z0-9_.-]{0,63}){1,9}$';
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION
            'V12 aborted: template name(s) violate the v1 naming grammar: %. '
            'Every template name needs a folder (test/<name> for scratch). '
            'Remediation: docs/template-hierarchy-design.md §4.6.', offenders;
    END IF;
END $$;
