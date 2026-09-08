# The pipeline JSON document

Open when you are writing or reading a pipeline body: the parameter block's fields and types, and a minimal complete pipeline to copy.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

**Parameters** — typed with the canonical logical types: `BOOLEAN`, `INTEGER`,
`BIGINTEGER`, `DECIMAL`, `BIGDECIMAL`, `STRING`, `DATE`, `TIMESTAMP`, etc. (11 total —
see type-system.md §3). Each declares `type`, `required`, optional `default`,
`description`. **`DECIMAL` parameters must also declare `precision`** — omitting it
fails the save with `pipeline.validation.parameter_precision_missing`; `BIGDECIMAL`
precision is optional (omitted = unbounded).

Minimal single-node pipeline (Postgres source, the single DQL node IS the caller node):

```json
{
  "schema_version": 1,
  "name": "acme/reporting/active_users",
  "display_name": "Active Users",
  "description": "List all active users from local PG",
  "parameters": {},
  "nodes": [{
    "id": "fetch_active_users",
    "description": "Fetch active users",
    "type": "DQL",
    "source": "pg-local",
    "template": {"id": "acme/reporting/active_users.sql", "version": 1},
    "depends_on": []
  }]
}
```
