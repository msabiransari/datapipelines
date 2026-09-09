# How to build a pipeline that a data engineer would sign off on

The golden path in `SKILL.md` is the *sequence* of tool calls. This is the *judgment* between
them: what an expert decides at each step, and the mistakes agents actually made on this
platform (each Don't below is a real one, 2026-09-08, on the demo data).

## 1. Read the question before you read the schema

- **Name the grain and the measure in one sentence** before touching a tool: "trips per
  (borough, company) over a date window; the winner per borough". If you cannot write that
  sentence, you do not yet know what to build.
- **Parameters wear the question's vocabulary.** A question asked in quarters gets a
  `quarter` parameter (`2024-Q4`), not two raw dates as the only door. Technical inputs
  are *derived*: a CALCULATOR node turns the human parameter into the `start_date`/`end_date`
  the SQL binds (`period_start`/`period_end` with `unit: quarter`; `prior_period` for "last";
  see `templates.md` § Calculators). Two dates the caller already passes need no calculator.
- **"Today" is a decision, say which.** `$current_date` is right for a live, scheduled
  pipeline. For a fixed dataset — the demo ends 2024-12 — an `as_of` (or window) parameter
  **defaulting to the data's last date** is right, or "last quarter" resolves to an empty
  window in 2026. Write the choice into the description.
- **Deployment knowledge comes from `$org_*` context keys** (fiscal start, week start,
  timezone, currency) — never a literal in a template.

## 2. Read the schema like an analyst

- **Codes have lookups. Answer with display names.** A column holding `HV0003`, `uber`, a
  `rate_code_id`, a `location_id` is an identifier; the human-readable name lives in a
  table beside it (`hvfhs_companies`, `zones`). List every table in the namespace, then
  ask of each id column: *which table explains this?* Join it. An answer that prints codes
  where the question said "which company" is not finished. (Real miss: `top_carrier_by_borough`
  printed `uber` with `hvfhs_companies` one `get_tables` away.)
- **Learn from naming when no description exists.** `*_companies`, `*_zones`, `*_lookup`,
  `dim_*`, `ref_*` are lookups; `*_daily`, `*_zone_day`, `*_monthly`, `agg_*` are
  pre-aggregates (prefer them over raw fact tables — they are the answer to "will this be
  slow"); `*_sample` is a hash sample (see §4); `stg_*`/`tmp_*` are staging. Confirm with
  `datasources_get_columns` — a lookup has the id column and a name column and little else.
- **Check the grain of every pre-aggregate** before summing it: a `zone_day` table summed
  over a month is fine; joined to a daily weather table it must be joined on the day, not
  the month.
- **Exclude what the question excludes.** A TLC zone table carries `EWR`, `Unknown`, `N/A`
  beside the five boroughs; "each NYC borough" means five rows. Look at the distinct values
  of the grouping column before you group by it.

## 3. Shape the DAG — push work down, ship little, then index

- **Aggregate at the source.** The source engine is built for its own data; tempdb (H2) is
  not a warehouse. Ship *the grain the answer needs*, not the raw rows: a lake node that
  returns `SUM(trip_count) GROUP BY pu_location_id, company` ships ~1k rows; the same node
  without the `GROUP BY` shipped 47,000 rows into H2 to join 263 zones. Push filters down too
  — the `WHERE` on the partition column is what makes a lake read prune.
- **Then, and only then, index.** If a staged table is still large *and* a downstream node
  joins or filters it by a key, add a `DDL` node between the staging node and the join:
  `CREATE INDEX IF NOT EXISTS ix_stg_x_key ON stg_x(key)`, `depends_on` the stager, the
  join `depends_on` the index (`pipeline-contract.md §16.4`). Order is load → index → query:
  indexing a filled table is one pass; loading into an indexed one pays per row. Do not index
  small tables (< ~10k rows) or tables read once in a scan.
- **`depends_on` is data flow, nothing else.** A node depends on the nodes whose tables or
  context values it reads. Never add an edge to serialise work "to reduce load" or "avoid
  contention" — you make the critical path longer and hide the real problem. Independent
  nodes run in parallel by design. (Real miss: four Postgres slices chained behind an
  unrelated lake read.)
- **A timeout is a signal, not an obstacle.** A node that hits the query timeout is doing
  too much work in the wrong place: pre-aggregate at the source, use a rollup table, filter
  earlier. Never split one scan into N slices with N parameter pairs to dodge the limit — it
  ships the same rows, adds parameters that can drift from the window, and the next run under
  load fails anyway. If a single scan legitimately needs longer, say so in your handback for
  the operator; do not work around it in the pipeline.
- **One template, bound per node.** If two nodes run the same SQL over different values,
  that is one template with parameters, not two copies. Copies drift.
- **On a lake table, filter on the PARTITION column.** A day-partitioned table
  (`hvfhv_trips`, partitioned on `pickup_date`) prunes only on that column: `WHERE pickup_date
  IN (DATE '2024-01-01', …)` or `BETWEEN` two dates touches those partitions and nothing else.
  A filter on a sibling timestamp (`pickup_at >= TIMESTAMP …`), a `CAST(pickup_date AS …)`,
  or any function on the column reads EVERY file and relies on row-group statistics — it
  looks fast on a quiet box and dies at the timeout on a busy one. Never build a `UNION ALL`
  of date-range branches to work around it; that is N full walks in one statement. One query,
  the partition column, a list of dates. (Real miss: an 11-branch UNION over `pickup_at`,
  cancelled at 60 s.)
- **After a node runs green, analyse it — and say what you found.** Read the node's timing
  and, where the platform gives you the plan (`sql_probe` when it exists; the node's
  `node_stats` otherwise), ask one question of every SOURCE node: *is the predicate and join
  key index-supported on that table?* If not — a date range filtered by a second column
  through a single-column index, a join key with no index, a lake read that did not prune —
  write it into your handback as a concrete suggestion: the exact `CREATE INDEX …` (or the
  partition column to filter on), the table, why (rows scanned vs rows kept), and what it would
  save. A readonly datasource is the operator's to change, so the suggestion is the
  deliverable; a tempdb table is yours — add the `DDL` node. Never leave "it was slow" as the
  whole story; "it read 780k rows through `idx_trips_pickup_date` to keep 65k — an index on
  `(pu_location_id, pickup_date)` would read the 65k" is the story.

## 4. Get the numbers right

- **Cast what you ship across engines.** `SUM`, `AVG` and arithmetic over a `NUMERIC(p,s)`
  lose their declared scale on the wire (Postgres reports "unknown"); make the type
  explicit: `SUM(x)::NUMERIC(14,2)`, `CAST(AVG(x) AS DECIMAL(14,4))`. It is the readable
  contract for the next reader even where the platform stores the value exactly.
- **Never compare a sample with a census.** A `*_sample` or hash-sampled feed (the demo's
  yellow-taxi trips) cannot be compared to a full count (the demo's rideshare feed) as a
  share: you get "rideshare 99.9%". Either scale by the sample rate, or compare
  *within-mode* ratios (rainy/dry per mode) — a ratio of a sample to itself is unbiased.
- **Ties and exclusions are part of the answer.** `RANK()` can return two rows for one
  borough on a tie — say so in the description or use `ROW_NUMBER()` with a stated
  tie-break. Filters like `trip_distance > 0 AND < 100` are assumptions; write them into
  the description.
- **Validate against an independent number** before you call it done: one borough's total
  from a direct query on the source, compared to the pipeline's row. Row counts agreeing
  between two versions of *your* pipeline proves consistency, not correctness.
- H2 specifics that bite: no `:bind` inside a `GROUP BY` expression; cast DECIMAL ratio
  operands to DOUBLE (both in `SKILL.md` Best practices 13–14).

## 5. Finish like a professional

- **Stop at the draft.** Your update is not live until a human releases it — never call a
  release endpoint, never say "released" or "live" about your own work. Execute the draft
  (by version) to prove it, then hand back.
- **Describe what a reader needs:** the question, the grain, the assumptions (filters,
  exclusions, sample scaling, tie-break), the parameters and how "today" is defined, and
  which tables were joined for names. The description is the only documentation the
  pipeline will ever have.
- **Report what you did not verify.** "Row counts match the previous version" is not
  "the numbers are right". Name the independent check you ran — or that you ran none.
- **Report the index analysis** (§3): for every source node, one line — supported by
  `<index>` / *not supported — suggest `CREATE INDEX … ON table (cols)`* / *lake: filters on
  the partition column, N of M partitions*. The operator reads
  the handback; that line is how a slow pipeline becomes a fast one without a rewrite.

## Do / Don't, in one screen

| Do | Don't |
|---|---|
| After each source node runs, check its predicate/join key against the table's indexes and put the `CREATE INDEX` suggestion in the handback | Report "the node was slow" and leave the operator to guess |
| Filter a lake table on its partition column, with literals or an `IN` list | Filter on a sibling timestamp, CAST the column, or UNION date-range branches |
| Join the lookup; answer with names | Print `HV0003` / `uber` and call it an answer |
| Infer table roles from `*_companies`, `*_zone_day`, `*_sample` when no description exists | Ignore a table because nothing described it |
| Aggregate and filter at the source; ship the answer's grain | Stage raw rows into H2 and aggregate there |
| Load → `DDL` index on the join key → query, for large staged tables | Index small tables, or index before loading |
| `depends_on` = the tables and context values a node reads | Chain nodes to "reduce load" |
| Treat a timeout as "wrong place for this work" | Slice one scan into N parameterised copies |
| Parameters in the question's words; calculators derive dates | Raw date pairs as the only interface to "last quarter" |
| Cast aggregates you ship (`::NUMERIC(14,2)`) | Trust the driver's guess at the scale |
| Compare a sample within itself | Compare a sample to a census as a share |
| Exclude `EWR`/`Unknown` when the question says "NYC boroughs" | Group by whatever the lookup contains |
| Validate one number independently | Call two runs of your own pipeline "verified" |
| Stop at the draft; a human releases | Call release; say "released"/"live" |
