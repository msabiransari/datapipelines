
# Shape the DAG — push work down, ship little, then index

- **A source node sees only its own datasource.** Its SQL runs on that engine and can read
  that engine's tables — nothing else. Staged tables (`output.target: tempdb`) are visible
  only to nodes with `source: "tempdb"`; a Postgres node cannot read a table another node
  staged, and no engine can read another engine. Cross-source work therefore happens in
  tempdb, on the *aggregated* outputs the source nodes shipped. When a source node must filter
  on a set computed elsewhere (a list of dates from a calendar table on another engine, a set
  of ids), you have two honest patterns: a **parameter** the caller supplies (bound, `:name`),
  or **literals in the template** with the computing node kept so the labels stay
  data-driven — and in both cases the description says so. There is no push-down of a staged
  table into a source engine.
  **A pushed-down literal is a copy, and a copy is checked.** When a source engine cannot
  reach the table a lookup comes from, the literal list goes into that source template, the
  node that COMPUTES the lookup stays in the DAG, and the answer node compares the two — a
  mismatch fails the run, not the reader. The list lives in ONE template — a pinned library
  template the source templates import — never copied between siblings. The door stays: the
  literal covers the window the door can select, and the template's `WHERE` binds the door, so
  `parameters: {}` is never the answer to a question that names a period. (Real miss: two
  sibling templates each carrying the same 443 literal rows, no node reading the source they
  came from, and no parameters at all — the numbers were right, and nothing would have said so
  when they stopped being right.)
- **Aggregate at the source.** The source engine is built for its own data; tempdb (H2) is not
  a warehouse. Ship the grain downstream steps need, preserving keys and aggregate state
  (`pipelines-numbers`): a node that returns `SUM(x) GROUP BY key` ships a thousand rows; the
  same node without the `GROUP BY` shipped tens of thousands into H2 to join a few hundred
  lookup rows. Push filters down too — on a lake table, a compatible partition predicate can
  skip files; other data skipping depends on layout and metadata.
- **Before you divide, look at MIN/MAX of every column you divide by.** A per-mile, per-rider
  or per-second ratio inherits every lie in its denominator: one absurd distance or one zero
  row collapses the average for everyone. One catalog-stats or probe read of the bounds tells
  you whether the denominator needs a range filter — and the filter you choose is an
  interpretation: write it into the description.
- **Then, and only then, index.** If a staged table is still large *and* a downstream node
  joins or filters it by a key, add a `DDL` node between the staging node and the join:
  `CREATE INDEX IF NOT EXISTS ix_stg_x_key ON stg_x(key)`, `depends_on` the stager, the join
  `depends_on` the index. Order is load → index → query: indexing a filled table is one pass;
  loading into an indexed one pays per row. Do not index small tables (< ~10k rows) or tables
  read once in a scan. **The staged-lookup index, as a named pattern:** a staged lookup you
  join a large staged table to gets exactly that `DDL` node between its staging node and the
  join — one template per index, pinned like any other node's. 60 s → 5 s was the measured
  difference on a 2M-row join in one acceptance run.
- **`depends_on` is data flow, nothing else.** A node depends on the nodes whose tables or
  context values it reads. Never add an edge to serialise work "to reduce load" or "avoid
  contention" — you make the critical path longer and hide the real problem. Independent nodes
  run in parallel by design. (Real miss: four source slices chained behind an unrelated lake
  read.)
- **A timeout is a signal, not an obstacle.** A node that hits a timeout is usually doing too
  much work in the wrong place: pre-aggregate at the source, use a rollup table, filter
  earlier, read the plan. Only after that, and only when the data genuinely has a natural
  partition, may one scan become several — under the Timeouts section's four conditions. A
  blind split into N parameter pairs ships the same rows N times, adds inputs that drift from
  the door, and fails under load anyway. If a single scan legitimately needs longer, set THAT
  node's own `settings.timeout_seconds` and say why in your handback.
- **One template, bound per node.** If two nodes run the same SQL over different values, that
  is one template with parameters, not two copies. Copies drift.
- **On a lake table, distinguish registered partitions from measured pruning.** Read the
  stats first, then use bound predicates on the partition key where they express the intended
  selection — bound parameters prune exactly like literals — and never build a `UNION ALL` of
  date-range branches to work around a sibling-column filter: that is N full walks in one
  statement. The `lake` guide holds the measured read-path rules; this read-only datasource
  cannot repartition or sort stored files, and tempdb `CREATE INDEX` does not index S3 data.
- **Climb the ladder: probe → render → execute_node → full DAG.** `sql_probe` the exact
  SELECT against the source first — rows, `wall_ms`, and the EXPLAIN plan (captured before the
  run, so it survives the timeout it explains); then `templates_render`; then
  `pipelines_execute_node` on the one node; only then `pipelines_execute` the whole DAG. Each
  rung is cheaper than the next and isolates a different fault class. **The tempdb (H2) rung is
  different:** H2 has no schema to learn, and `sql_probe {"name": "tempdb"}` prepares the
  statement against an EMPTY engine — read its `validation_status`. `executed` means a
  self-contained statement ran: that statement, as written, is valid. `incomplete` with
  `missing_table` means H2 stopped at the first staged table it could not find, and nothing
  after that point — a join form, a clause, a column — was checked; it is not a pass and not a
  failure. Finish it before the run: restate the suspect construct over typed, aliased `VALUES`
  inputs of the staged tables' shape so it executes on the scratch (that proves the construct,
  not the real column types), or use `pipelines_execute` to run the DAG with its real staged
  inputs. Standalone `pipelines_execute_node` refuses tempdb sources; running source nodes
  separately does not retain staged tables. An error is a real H2 error. Rendering, an
  incomplete preparation and an execution are three different pieces of evidence; only the
  last one says the node runs. What the probe rung settles about the DATA — a unit, a time
  zone, what a coded value means — is a fact: `semantics_record` it with that SELECT as
  `evidence_sql` (`datasources-semantics`). A LAKE table's ref takes `schema` as the DOTTED
  string — `{"schema": "datalake.mart", "table": "events"}` — never a namespace array (that is
  `semantics.ref_unresolved`). The stop-loss discipline is the core's error-recovery rule: a
  schema refusal is not your typo — re-introspect or hand back, because a fourth identical
  call changes nothing.
- **A table marked unavailable is broken at the lake, not by your query.** If a lake table's
  registration carries a `last_error`, or a node fails `datasource.lake.table_unavailable`,
  the table's view failed to build at connect (bad prefix, wrong format, unreadable files) and
  was skipped. Re-running the query will never fix it — report the recorded `last_error` to
  the user and let them re-register or fix the table; do not retry.
- **After a node runs green, analyse it — and say what you found.** Read the node's timing and
  the plan (`sql_probe` the node's SELECT against the source — `plan.scan`, and on a lake
  `partitions_scanned`/`partitions_total`; the node's `node_stats` carries the timing), ask one
  question of every SOURCE node: *is the predicate and join key index-supported on that
  table?* Name the access path the plan shows, never a join algorithm you did not see in a
  plan. If not — a date range filtered by a second column through a single-column index, a
  join key with no index, a lake read that did not prune — write it into your handback as a
  concrete suggestion: the exact `CREATE INDEX …` (or the partition column to filter on), the
  table, why (rows scanned vs rows kept), and what it would save. A readonly datasource is the
  operator's to change, so the suggestion is the deliverable; a tempdb table is yours — add
  the `DDL` node. Never leave "it was slow" as the whole story; "it read 780k rows through the
  date index to keep 65k — an index on `(location_id, event_date)` would read the 65k" is the
  story.

## Timeouts — three budgets, and which one to reach for

- **Three bounds, outermost first:** the whole execution (`execution-timeout-seconds`,
  600) ≥ one NODE, wall clock, all phases
  (`node.settings.timeout_seconds`, else `node-timeout-seconds`, 300) ≥
  one STATEMENT (the datasource's `query_timeout_seconds`, else
  `node-query-timeout-seconds`, 60).
- **Two different failures.** `pipeline.node.query_timeout` is the driver stopping one
  statement; `pipeline.node.timeout` is the executor stopping the whole node, whatever the
  driver does. Read its `phase`: `execute` → cheaper QUERY; `stage` → FEWER ROWS, usually —
  but read the error and the elapsed time too: a `stage`-phase timeout can still be the CTAS
  query itself (the staging statement runs the SELECT), not the row count. Read the EFFECTIVE
  limits before you reason about them: a datasource with no `query_timeout_seconds` runs under
  the application default, and a node's wall-clock override never changes the statement
  timeout. Never report an override that does not exist.
- **Reach for the node's own budget LAST**, after pre-aggregating, pushing the filter down and
  pruning on the partition column. Capped at `node-timeout-max-seconds`
  (900), refused at SAVE above it. A raised timeout you cannot justify
  in one sentence is a slow pipeline you agreed to.
- **Partition a scan only when it is the data's own shape, and prove it.** Optimize first (the
  rules above). If the workload still does not fit and the data has a natural partition — a
  period, a key range — several nodes may each read one partition, under four conditions: the
  partitions cover the window exactly and do not overlap; the answer recombines additively at
  its grain (additive counts/sums combine; averages need sums/counts, distinct counts need
  disjoint entity sets or deduplication, and medians need more than partial medians —
  `pipelines-numbers`); concurrency is bounded and understood (the partitions run at once
  against the same source, each its own statement under the same statement timeout — N
  partitions are N concurrent loads, and each still has to fit); and the evidence is measured
  — per-node timings, and a reconciliation against the unsplit population where one exists. A
  blind copy-and-parameterise split, or a `UNION ALL` of the same walk, is neither. Never add
  a `depends_on` edge to avoid contention. Source cursors drain in parallel, and independent
  tempdb work may overlap up to the deployment's configured limits, so independent nodes
  really do run at once.
- **One warm success near the limit is not reliability.** A node that finished at 90 % of its
  budget once, after a slower run, may have met a warm cache, a quiet box, or a real fix — the
  timings alone do not say which. Report the headroom you measured and the risk that remains.
  When no in-scope plan is robust — the query is as cheap as the source allows and still near
  the budget — say so: report the effective limit, the shape that needs it and the decision
  the operator has to make (a datasource timeout, a rollup at the source), rather than
  claiming the timeout was fixed.
- **Cross-node state is a table, never a session.** Each node's tempdb SQL runs in its own
  session, reset when the node finishes: a `SET SCHEMA`, a `SET @variable`, a local temporary
  table or an open transaction from one node is not visible to the next, and must not be
  relied on. Hand data forward with `output: tempdb` tables and read them with
  `source: "tempdb"`.
