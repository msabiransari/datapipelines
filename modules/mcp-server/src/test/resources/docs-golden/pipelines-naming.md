
# Folders in practice

The grammar, the required folder, `confirm_new_root` and `test/` are the core's naming rule —
stated once there. This is what the rule looks like laid out across a whole area.

**Worked example.** An organisation's finance area keeps its pipelines and the templates they
read under one prefix:

```
acme/finance/revenue_by_region       (pipeline)
acme/finance/monthly_briefing        (pipeline — a PIPELINE node invoking revenue_by_region)
acme/finance/daily_orders.sql        (template the pipelines read)
acme/lib/metrics.sql                 (shared macros)
```

A new finance pipeline goes under `acme/finance/`; a first pipeline for a different
organisation or product is a new root, so you ask first — the confirmation flow and its codes
are the core's naming rule. A pipeline and the templates it
uses share a prefix: one prefix query shows an area's work whichever kind you browse.

**What folders are NOT:**

- **Not permissions.** The workspace is the isolation boundary, and your role in it is what
  you may do there (the core's rules) — `auth.role_required` is about your key's role, never
  about a folder.
- **Not a rename mechanism.** A name is the asset's identity — child references
  (`{name, version}`), pins, execution history and promotion all key on it, and there is no
  move for either kind.
- **Not a schema dimension.** No folder column, no folder ids. A folder exists exactly as
  long as something is named under it, and disappears with the last thing in it.
