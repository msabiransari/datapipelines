# How to build a pipeline that a data engineer would sign off on

The golden path in `SKILL.md` is the *sequence* of tool calls. This is the *judgment* between
them: what an expert decides at each step, and the mistakes agents actually made on this
platform (each Don't below is a real one). Nothing here is about any particular dataset —
every fact about the data you are working on comes from the data, read through the tools,
in the order §1 gives.

## 1. Learn before you assume — the data is the only authority on the data

You know nothing about a datasource until you have read it. Not its time zone, not its
units, not whether a table is a sample or a census, not what a coded value means, not which
column a lake table is partitioned on. Descriptions and comments — the datasource's
`description`, a table's or column's `remarks` — are one input, written by a person who may
be wrong or out of date; **the columns and the rows are the ground truth**, and every
assumption that survives into your SQL must have been checked against them.

The mandatory read order, for **every** datasource a pipeline will touch:

1. `datasources_get` — the description (grain, sampling, units, window, time zone, if the
   registrant wrote them), the dialect, and for a lake datasource the registered tables with
   their partition columns and any `last_error`.
2. `datasources_get_schemas` → `datasources_get_tables(namespace)` — every table, with its
   `remarks`. Read the names as an analyst would (§2), then confirm.
3. `datasources_get_columns(table, namespace)` for every table the SQL will read — canonical
   types, nullability, `remarks`. **Never write SQL against a column you have not seen
   listed.** A column name you recall from another deployment is a guess.
4. `datasources_get_table_stats(table)` — row estimate, indexes, per-column bounds, from the
   catalog (never a scan). The bounds tell you the data's window and whether a timestamp
   column carries dates or date-times; the indexes tell you which predicates will be cheap.
5. `sql_probe` — a few rows of every table you will filter or join on, and the distinct
   values of every column you will filter on, group by, or join by. This is where you learn
   what a code means, whether a "date" is text, whether an empty value is `NULL` or `''`,
   whether a numeric column is in the unit its name suggests, whether a timestamp is local
   or UTC (compare a known event; read the `remarks`; if nothing says, treat it as naive
   local and write that assumption down).
6. **Write what you learned into the pipeline's description** — every fact the SQL relies on
   that the schema alone does not state (time zone, units, sampling, grain, partition
   column, window, what an enum value means). A reader who cannot see your probes must
   still be able to trust your numbers.

Two facts you must establish before you touch a number: **is this table a sample or a
census** (a hash-sampled feed compared to a full count as a share gives nonsense; if a
sample rate is stated anywhere — description, remarks, a metadata table — scale by it; if
none is stated, compare a sample only with itself, §4), and **what one row is** (§2 — a
pre-aggregate summed at the wrong grain double-counts silently).

## 2. Read the schema like an analyst

- **Name the grain and the measure in one sentence** before touching a tool: "orders per
  (region, channel) over a date window; the winner per region". If you cannot write that
  sentence, you do not yet know what to build.
- **Codes have lookups. Answer with display names.** A column holding a short code, an
  `*_id`, a licence number is an identifier; the human-readable name lives in a table beside
  it. List every table in the namespace, then ask of each id column: *which table explains
  this?* Join it. An answer that prints codes where the question said "which company" is not
  finished. (Real miss: a pipeline printed a carrier's code with the carrier lookup one
  `get_tables` away.)
- **Learn from naming when no description exists — then confirm.** `*_companies`, `*_zones`,
  `*_lookup`, `dim_*`, `ref_*` are usually lookups; `*_daily`, `*_monthly`, `*_by_day`,
  `agg_*` are usually pre-aggregates (prefer them over raw fact tables — they are the answer
  to "will this be slow"); `*_sample` is usually a sample; `stg_*`/`tmp_*` are staging. A
  name is a hypothesis: `datasources_get_columns` confirms it — a lookup has the id column,
  a name column and little else; a pre-aggregate has a count or a sum and the grain columns.
- **Check the grain of every pre-aggregate** before summing it: a per-zone-per-day table
  summed over a month is fine; joined to a daily table it must be joined on the day, not
  the month.
- **Exclude what the question excludes.** A lookup table often carries catch-all rows —
  `Unknown`, `N/A`, an out-of-area entry — beside the values the question means; "each
  region" means the regions, not the catch-alls. Look at the distinct values of the grouping
  column before you group by it, and write the exclusion into the description.
- **Parameters wear the question's vocabulary.** A question asked in quarters gets a
  `quarter` parameter (`2024-Q4`), not two raw dates as the only door. Technical inputs
  are *derived*: a CALCULATOR node turns the human parameter into the `start_date`/`end_date`
  the SQL binds (`period_start`/`period_end` with `unit: quarter`; `prior_period` for "last";
  see `templates.md` § Calculators). Two dates the caller already passes need no calculator.
- **"Today" is a decision, say which.** `$current_date` is right for a live, scheduled
  pipeline. For a fixed dataset — read its window from the stats or the description — an
  `as_of` (or window) parameter **defaulting to the data's last date** is right, or "last
  quarter" resolves to an empty window next year. Write the choice into the description.
- **Deployment knowledge comes from `$org_*` context keys** (fiscal start, week start,
  timezone, currency) — never a literal in a template.
- **Read the table's stats and indexes before you write the predicate.**
  `datasources_get_table_stats` answers the row estimate, the index list and the per-column
  bounds from the engine's catalog — never a scan, so ask it of a hundred-million-row
  table too. A filter the indexes do not support on a large table is the timeout you will
  hit in §3; find it here, and when no index carries your predicate, write the suggested
  `CREATE INDEX` into your handback — a readonly datasource is the operator's to change,
  so the suggestion is the deliverable.

## 3. Shape the DAG — push work down, ship little, then index

- **A source node sees only its own datasource.** Its SQL runs on that engine and can read
  that engine's tables — nothing else. Staged tables (`output.target: tempdb`) are visible
  only to nodes with `source: "tempdb"`; a Postgres node cannot read a table another node
  staged, and no engine can read another engine. Cross-source work therefore happens in
  tempdb, on the *aggregated* outputs the source nodes shipped. When a source node must
  filter on a set computed elsewhere (a list of dates from a calendar table on another
  engine, a set of ids), you have two honest patterns: a **parameter** the caller supplies
  (bound, `:name`), or **literals in the template** with the computing node kept so the
  labels stay data-driven — and in both cases the description says so. There is no
  push-down of a staged table into a source engine.
- **Aggregate at the source.** The source engine is built for its own data; tempdb (H2) is
  not a warehouse. Ship *the grain the answer needs*, not the raw rows: a node that returns
  `SUM(x) GROUP BY key` ships a thousand rows; the same node without the `GROUP BY` shipped
  tens of thousands into H2 to join a few hundred lookup rows. Push filters down too — on a
  lake table, the `WHERE` on the partition column is what makes the read prune.
- **Then, and only then, index.** If a staged table is still large *and* a downstream node
  joins or filters it by a key, add a `DDL` node between the staging node and the join:
  `CREATE INDEX IF NOT EXISTS ix_stg_x_key ON stg_x(key)`, `depends_on` the stager, the
  join `depends_on` the index (`pipeline-contract.md §16.4`). Order is load → index → query:
  indexing a filled table is one pass; loading into an indexed one pays per row. Do not index
  small tables (< ~10k rows) or tables read once in a scan.
- **`depends_on` is data flow, nothing else.** A node depends on the nodes whose tables or
  context values it reads. Never add an edge to serialise work "to reduce load" or "avoid
  contention" — you make the critical path longer and hide the real problem. Independent
  nodes run in parallel by design. (Real miss: four source slices chained behind an
  unrelated lake read.)
- **A timeout is a signal, not an obstacle.** A node that hits a timeout is doing too much
  work in the wrong place: pre-aggregate at the source, use a rollup table, filter earlier.
  Never split one scan into N slices with N parameter pairs to dodge the limit — it ships the
  same rows, adds parameters that can drift from the window, and the next run under load fails
  anyway. If a single scan legitimately needs longer, set THAT node's own
  `settings.timeout_seconds` and say why in your handback — see Timeouts below.
- **One template, bound per node.** If two nodes run the same SQL over different values,
  that is one template with parameters, not two copies. Copies drift.
- **On a lake table, filter on the PARTITION column.** `datasources_get` names each
  registered table's partition column; a predicate over that column prunes:
  `WHERE <partition_col> IN (DATE '…', …)`, `BETWEEN` two dates, and — measured on
  DuckDB 1.5.5 — a deterministic expression of the column such as `CAST(<partition_col> AS …)`
  still prune. **Bound parameters prune too**: write `:d` for a date filter and the engine
  folds the bound value into the scan (measured: 33 bound dates read 33 of 731 files, same as
  literals). What never prunes is a predicate on a sibling column INSIDE the files (a
  timestamp beside the partition date) — that reads EVERY file and relies on row-group
  statistics; it looks fast on a quiet box and dies at the timeout on a busy one. Never build
  a `UNION ALL` of date-range branches to work around it; that is N full walks in one
  statement. One query, the partition column, a list of dates. (Real miss: an 11-branch
  UNION over the sibling timestamp, cancelled at 60 s.)
- **Climb the ladder: probe → render → execute_node → full DAG.** `sql_probe` the exact
  SELECT against the source first — rows, `wall_ms`, and the EXPLAIN plan (captured before
  the run, so it survives the timeout it explains); then `templates_render`; then
  `pipelines_execute_node` on the one node; only then `pipelines_execute` the whole DAG.
  Each rung is cheaper than the next and isolates a different fault class. **The tempdb
  (H2) rung is different:** H2 has no schema to learn and `sql_probe` accepts `tempdb` only
  for a *syntax and self-contained* check against an empty engine — a statement that parses
  and fails with "table not found" has passed that check; one that fails on a name it
  defines itself (a `VALUES` column, an alias) has not. Use it before every full run.
  **Stop-loss: three identical failures → stop and report.** A schema refusal is not
  your typo — re-introspect or hand back; a fourth identical call changes nothing.
- **A table marked unavailable is broken at the lake, not by your query.** If
  `datasources_get` shows a lake table with `last_error` set, or a node fails
  `datasource.lake.table_unavailable`, the table's view failed to build at connect
  (bad prefix, wrong format, unreadable files) and was skipped. Re-running the query will
  never fix it — report the recorded `last_error` to the user and let them re-register or
  fix the table; do not retry.
- **After a node runs green, analyse it — and say what you found.** Read the node's timing
  and the plan (`sql_probe` the node's SELECT against the source — `plan.scan`, and on a
  lake `partitions_scanned`/`partitions_total`; the node's `node_stats` carries the
  timing), ask one question of every SOURCE node: *is the predicate and join
  key index-supported on that table?* If not — a date range filtered by a second column
  through a single-column index, a join key with no index, a lake read that did not prune —
  write it into your handback as a concrete suggestion: the exact `CREATE INDEX …` (or the
  partition column to filter on), the table, why (rows scanned vs rows kept), and what it would
  save. A readonly datasource is the operator's to change, so the suggestion is the
  deliverable; a tempdb table is yours — add the `DDL` node. Never leave "it was slow" as the
  whole story; "it read 780k rows through the date index to keep 65k — an index on
  `(location_id, event_date)` would read the 65k" is the story.

## 3a. Timeouts — three budgets, and which one to reach for

- **Three bounds, outermost first:** the whole execution (`execution-timeout-seconds`, 600) ≥ one
  NODE, wall clock, all phases (`node.settings.timeout_seconds`, else `node-timeout-seconds`, 300)
  ≥ one STATEMENT (the datasource's `query_timeout_seconds`, else `node-query-timeout-seconds`, 60).
- **Two different failures.** `pipeline.node.query_timeout` is the driver stopping one statement;
  `pipeline.node.timeout` is the executor stopping the whole node, whatever the driver does. Read
  its `phase`: `execute` → cheaper QUERY; `stage` → FEWER ROWS; `connect` → the pool or network.
- **Reach for the node's own budget LAST**, after pre-aggregating, pushing the filter down and
  pruning on the partition column. Capped at `node-timeout-max-seconds` (900), refused at SAVE
  above it. A raised timeout you cannot justify in one sentence is a slow pipeline you agreed to.
- **Never slice a scan to fit a budget, and never add a `depends_on` edge to avoid contention.**
  Source cursors drain in parallel and the staging lock is taken per batch, so independent source
  nodes really do overlap (measured). `depends_on` is data flow, nothing else.

## 4. Get the numbers right

- **Cast what you ship across engines.** `SUM`, `AVG` and arithmetic over a `NUMERIC(p,s)`
  lose their declared scale on the wire (Postgres reports "unknown"); make the type
  explicit: `SUM(x)::NUMERIC(14,2)`, `CAST(AVG(x) AS DECIMAL(14,4))`. It is the readable
  contract for the next reader even where the platform stores the value exactly.
- **Never compare a sample with a census.** A sampled feed cannot be compared to a full
  count as a share: you get "99.9%" for the census side. Either scale by the sample rate —
  when the description, a remark or a metadata table states it — or compare *within-mode*
  ratios (a rate computed inside the sample against the same rate inside the census) — a
  ratio of a sample to itself is unbiased. Say which you did in the description.
- **Ties and exclusions are part of the answer.** `RANK()` can return two rows for one
  group on a tie — say so in the description or use `ROW_NUMBER()` with a stated
  tie-break. Filters like `distance > 0 AND distance < 100` are assumptions; write them into
  the description.
- **Validate against an independent number** before you call it done: one group's total
  from a direct query on the source, compared to the pipeline's row. Row counts agreeing
  between two versions of *your* pipeline proves consistency, not correctness.
- H2 specifics that bite: no `:bind` inside a `GROUP BY` expression; cast DECIMAL ratio
  operands to DOUBLE; `VALUES` rows are named `C1, C2…` — or alias them (`SKILL.md` Best
  practices 14–16).

## 5. Finish like a professional

- **Stop at the draft.** Your update is not live until a human releases it — never call a
  release endpoint, never say "released" or "live" about your own work. Execute the draft
  (by version) to prove it, then hand back.
- **Describe what a reader needs:** the question, the grain, the assumptions (filters,
  exclusions, sample scaling, tie-break), what you learned about the data that the SQL
  relies on (§1.6), the parameters and how "today" is defined, and which tables were joined
  for names. The description is the only documentation the pipeline will ever have.
- **Report what you did not verify.** "Row counts match the previous version" is not
  "the numbers are right". Name the independent check you ran — or that you ran none.
- **Report the index analysis** (§3): for every source node, one line — supported by
  `<index>` / *not supported — suggest `CREATE INDEX … ON table (cols)`* / *lake: filters on
  the partition column, `partitions_scanned`/`partitions_total` from `sql_probe`'s plan*.
  The operator reads the handback; that line is how a slow pipeline becomes a fast one
  without a rewrite.

## Do / Don't, in one screen

| Do | Don't |
|---|---|
| Read description → schemas → tables → columns → stats → rows before the first line of SQL | Write SQL against a column, a unit, a time zone or a sample rate you assumed |
| Write what you learned about the data into the description | Leave the next reader to re-probe what you already established |
| After each source node runs, check its predicate/join key against the table's indexes (`datasources_get_table_stats`, `sql_probe`'s `plan.scan`) and put the `CREATE INDEX` suggestion in the handback | Report "the node was slow" and leave the operator to guess |
| Filter a lake table on its partition column, with literals, bound parameters, or an `IN` list | Filter on a sibling timestamp inside the files, or UNION date-range branches |
| Join the lookup; answer with names | Print a code and call it an answer |
| Infer table roles from `*_companies`, `*_by_day`, `*_sample` when no description exists — then confirm with the columns | Ignore a table because nothing described it, or trust the name alone |
| Aggregate and filter at the source; ship the answer's grain | Stage raw rows into H2 and aggregate there |
| Load → `DDL` index on the join key → query, for large staged tables | Index small tables, or index before loading |
| `depends_on` = the tables and context values a node reads | Chain nodes to "reduce load" |
| Treat a timeout as "wrong place for this work" | Slice one scan into N parameterised copies |
| Parameters in the question's words; calculators derive dates | Raw date pairs as the only interface to "last quarter" |
| Cast aggregates you ship (`::NUMERIC(14,2)`) | Trust the driver's guess at the scale |
| Compare a sample within itself, or scale by a stated rate | Compare a sample to a census as a share |
| Exclude the lookup's catch-all rows when the question names the real groups | Group by whatever the lookup contains |
| `sql_probe` a tempdb statement against the empty engine before a full run | Pay a full DAG run to find an H2 syntax error |
| Validate one number independently | Call two runs of your own pipeline "verified" |
| Stop at the draft; a human releases | Call release; say "released"/"live" |
