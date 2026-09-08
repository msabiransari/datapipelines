# Error codes you will meet

Open when a tool answered `isError: true` and you need the code's meaning and the response it calls for.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

| Code | Meaning | Agent response |
|---|---|---|
| `auth.api_key.missing` / `auth.api_key.invalid` | Credential problem | Ask the user for a fresh key |
| `auth.scope.insufficient` | Key lacks the required scope | Ask for a broader key, or change what you asked |
| `pipeline.validation.*` (`cycle_detected`, `dangling_dependency`, `duplicate_node_id`, `parameter_precision_missing`, …) | Pipeline JSON is invalid | Fix the JSON — never retry as-is |
| `template.validation.*` (`syntax_error`, `dangerous_construct`, …) | Template body rejected | Fix the Freemarker and re-render |
| `template.not_found` / `datasource.not_found` | Reference points at nothing | Create the referenced entity or fix the id |
| `pipeline.version.conflict` | The pipeline changed after you loaded it (stale `expected_hash`) | Re-read with `pipelines_get`, rebase your edit onto the current body/hash, retry — NEVER retry blindly |
| `pipeline.version.not_draft` | Release/discard hit a pipeline with no draft | Nothing to act on for an agent — the draft was already released or discarded |
| `pipeline.authoring.disabled` / `template.authoring.disabled` | This server has authoring turned off — it is a promotion receiver | Do not retry; tell the user this server only receives promoted content. Reads, execution and import still work |
| `pipeline.validation.duplicate_name` (on update) | Your draft renames onto a taken name | Pick a different `name`; this fails at write time now, not at release |
| `pipeline.execution.datasource_unreachable` | Source DB down/bad credentials | `datasources_test` to confirm |
| `pipeline.node.query_execution_failed` | A node's SQL failed | Read `node_stats` + `executions_get` for the node error, re-render its template with the failed parameters |
| `pipeline.node.sql_parameter_missing` | The rendered SQL references a `:name` no pipeline parameter declares | Name a declared parameter — or interpolate structure instead |
| `template.validation.parameter_interpolated` | A declared parameter appears inside `${}` | Write `:name` for it — bound values are never parsed as SQL |
| `result.expired` | TTL elapsed on the cursor | Re-execute and page sooner |
