# Folders in practice

Open when you are choosing where a new pipeline or template lives, or explaining the folder rules to a human.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

**Worked example (the shipped demo).** The NYC family keeps its pipelines and the templates
they read under one prefix:

```
nyc/mobility/revenue_by_borough      (pipeline)
nyc/mobility/mobility_briefing       (pipeline — a PIPELINE node invoking borough_od_matrix)
nyc/mobility/daily_by_zone.sql       (template the pipelines read)
nyc/lib/metrics.sql                  (shared macros)
```

A new NYC mobility pipeline goes under `nyc/mobility/`; a first finance pipeline is a new
root, so you ask.

**What folders are NOT:**

- **Not permissions.** The workspace is the isolation boundary, and your ROLE in it is what
  you may do there — see `error-codes.md` for `auth.role_required`.
- **Not a rename mechanism.** A name is the asset's identity — child references
  (`{name, version}`), pins, execution history and promotion all key on it. **Choose the
  folder at creation**; there is no move, for either kind.
- **Not optional.** Every name has one. There is no root-level pipeline or template and no
  way to create one.
- **Not a schema dimension.** No folder column, no folder ids. A folder exists exactly as
  long as something is named under it, and disappears with the last thing in it.
