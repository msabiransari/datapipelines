-- V7__hierarchical_template_names.sql
--
-- Hierarchical template names (template-hierarchy-design.md §4, round 043). The name grammar
-- widens to paths (`acme/finance/monthly_revenue` — 1–10 segments, each
-- `[a-z0-9][a-z0-9_.-]{0,63}`, ≤ 200 chars total). The schema needs no change for that: the
-- full path IS the name, `templates.name` stays TEXT, and `uq_templates_workspace_name` is
-- unchanged (§4.3).
--
-- What this migration carries instead is the §4.6 legacy-name gate, and nothing else. The new
-- grammar is NARROWER than the old flat rule in two respects (segments must start
-- alphanumeric; a segment caps at 64 where the flat rule allowed 100), and the loader
-- re-validates names at RENDER time — so a stored name that becomes illegal would break
-- execution of already-released, already-promoted pipelines, with no in-place repair (§4.5
-- forbids rename). The gate makes that loud at deploy time rather than silent at render time.

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
        OR name !~ '^[a-z0-9][a-z0-9_.-]{0,63}(/[a-z0-9][a-z0-9_.-]{0,63}){0,9}$';
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION
            'V7 aborted: template name(s) violate the v1 naming grammar: %. '
            'Remediation: docs/template-hierarchy-design.md §4.6.', offenders;
    END IF;
END $$;
