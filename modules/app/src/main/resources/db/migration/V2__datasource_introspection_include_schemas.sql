-- §4.10 datasources: introspection include-schemas allowlist (datasources.md §3.3 / §7A, R3 F5).
-- Optional per-datasource additive allowlist: a schema named here is exempt from the
-- system-schema exclusion in all three introspection filter sites. Empty by default —
-- absent means today's behavior. JSONB array of lowercase schema names, like the
-- properties_json document beside it.
ALTER TABLE datasources
    ADD COLUMN introspection_include_schemas JSONB NOT NULL DEFAULT '[]';
