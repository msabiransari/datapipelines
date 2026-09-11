# Folders in practice

Open when you are choosing where a new pipeline or template lives, or explaining the folder rules to a human.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

**Worked example.** An organisation's finance area keeps its pipelines and the templates
they read under one prefix:

```
acme/finance/revenue_by_region       (pipeline)
acme/finance/monthly_briefing        (pipeline — a PIPELINE node invoking revenue_by_region)
acme/finance/daily_orders.sql        (template the pipelines read)
acme/lib/metrics.sql                 (shared macros)
```

A new finance pipeline goes under `acme/finance/`; a first pipeline for a different
organisation or product is a new root, so you ask.

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
