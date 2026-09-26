
# How to build a pipeline that a data engineer would sign off on

The golden path in `core` is the *sequence* of tool calls. This is the *judgment* between
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

1. `datasources_list` — your first call and your first read: every granted datasource's
   description (grain, sampling, units, window, time zone, if the registrant wrote them),
   dialect and connection facts, AND the datasource-wide `facts` earlier sessions recorded.
   `datasources_get` is the same payload for one datasource — the refresh after you
   `semantics_record`. Neither lists tables: a lake's registered tables come from
   `datasources_get_tables`, whose entries state partition status for a lake table (step 2).
   The `definitions` on the listing are this workspace's rules (`definition`, `exclusion`,
   `preference`); each carries `implemented_by` — the transform versions that implement it.
   **Search the facts, find the definition, find the transform that implements it, and reuse
   it before writing your own** (pin it; `templates_list {"implements": "<fact id>"}` lists
   them too). A rule nobody implemented yet is one you may implement — and when you do, cite
   it in the transform's `implements` (`transforms`) so the next session finds
   yours.
2. `datasources_get_schemas` → `datasources_get_tables(namespace)` — every table, with its
   `remarks`; on a LAKE datasource each entry also carries `partition_column`, the registered
   partition key. Use a compatible predicate on it when it matches the question's window.
   `null` means no registered key, not one file or a mandatory full scan; file/row-group
   skipping and column projection may still apply. Read the names as an analyst would (§2), then confirm.
3. `datasources_get_columns(table, namespace)` for every table the SQL will read — canonical
   types, nullability, `remarks`. **Never write SQL against a column you have not seen
   listed.** A column name you recall from another deployment is a guess.
4. `datasources_get_table_stats(table)` — row estimate, indexes, per-column bounds, from the
   catalog (never a scan). The bounds tell you the data's window and whether a timestamp
   column carries dates or date-times; the indexes tell you which predicates will be cheap.
   For a lake table the stats still state partition status out loud (the listing's
   `partition_column` already told you): a registered partition column reports as
   `partition_column` (and an index entry of kind `partition`);
   `partition_column: null` describes the registry, not file count or physical layout.
   A pushed filter alone does not establish how many files, row groups or bytes were skipped.
5. `sql_probe` — a few rows of every table you will filter or join on, and the distinct
   values of every column you will filter on, group by, or join by. This is where you learn
   what a code means, whether a "date" is text, whether an empty value is `NULL` or `''`,
   whether a numeric column is in the unit its name suggests, whether a timestamp is local
   or UTC (compare a known event; read the `remarks`; if nothing says, treat it as naive
   local and write that assumption down). A claim about which values a column holds over the
   WHOLE table needs a whole-table probe (`SELECT col, MIN(d), MAX(d) … GROUP BY col` over
   the full window) — never an inference from a lookup table or a description.

   With NO description — the usual case; nobody fills table- and column-level detail into a
   form — the surfaces still answer, one line each:
   - A NAME: `_sample` marks a sample, `_daily`/`_monthly` a rollup, a `<subject>_<grain>`
     compound the grain, `_ts` vs `_at` a timestamp style, a `unit` column beside a `value`
     column per-row units, a `_meta` table metadata the datasource carries about itself.
   - A TYPE: an engine `timestamp` without time zone in `source_type` is naive by
     construction; a `TEXT` date in SQLite is text to cast, not a date.
   - STATS: a date column's min/max is the data's window; a rollup's total against its
     source's count is the sample-or-full answer.
   - A PROBE: the `unit` column's distinct values, a code column's spread.

6. **Write what you learned into the pipeline's description** — every fact the SQL relies
   on that the schema alone does not state (time zone, units, sampling, grain, window, what
   an enum value means). A reader who cannot see your probes must still trust your numbers.
7. **Then re-read that description before `pipelines_create`, and strike every sentence you
   cannot point at a call you made.** A description carries facts about the data and the
   interpretation you chose — never claims about your own process: "validated", "reproduced",
   "checked" go in your reply to the person, not in a home that outlives the session. And a
   description is an EXEMPLAR: the next agent reads existing pipelines to copy their idioms,
   so a claim you wrote without running its probe is a claim the next pipeline inherits.

Two facts you must establish before you touch a number: **is this table a sample or a
census** (a hash-sampled feed compared to a full count as a share gives nonsense; if a
sample rate is stated anywhere — description, remarks, a metadata table — scale by it; if
none is stated, compare a sample only with itself, §4 — and record the unstated rate as the
assumption it is: `semantics_record` WITHOUT evidence, so it lands as `asserted` for a human
to verify), and **what one row is** (§2 — a pre-aggregate summed at the wrong grain
double-counts silently).

Copy an existing pipeline's SHAPE — staging, join placement, casts — never its window
semantics; the question's words decide the window.

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
- **Check the grain and measure of every pre-aggregate** before combining it: daily event
  counts can sum over a month; daily balances generally cannot. A join must preserve the
  intended cardinality at that grain. Carry the keys and aggregate state needed downstream (§4).
- **A place the question names that has a sensor AT it is answered by that sensor.** When
  the question names a site — an airport, a plant, a store, a port — and the data has a
  station, a meter or a device located at that site, the site's measure is that one station's
  reading, not an average over the stations nearby. The average is a different measure: it
  smooths the very difference the question asks about (real miss: averaging five stations
  named the wrong site). Probe the station table for the site's name or coordinates first; when
  no station sits at the site, the nearest one — or the average — is a choice, and you say so.
  Either way the station choice is recorded: a `preference` fact (or a `definition` when it
  defines the measure), with the probe that listed the stations as its evidence — a rule you
  chose for the answer's grain is recorded with the same care as the threshold you chose for
  its filter, and the next pipeline over the same sites reads it before choosing its own.
  **If the answer's grain names the place** — an `airport` column, a `site` column — **the
  reading is per place, not a mean across places**: a row that names one site carries that
  site's sensor, never the average of several.
- **A unit that is not what the question ranks is never ranked.** A lookup can carry an
  entry that is the wrong KIND of thing beside the right ones — an airport zone in a region
  ranking, a depot in a store ranking, a test account among customers. It is excluded with the
  reason written into the description, or shown outside the ranking as its own line — never
  ranked among the units the question named. (Real miss: an airport's own zone ranked as a
  region, and the answer's "top region" was a runway.)
- **Exclude what the question excludes.** A lookup table often carries catch-all rows —
  `Unknown`, `N/A`, an out-of-area entry — beside the values the question means; "each
  region" means the regions, not the catch-alls. Look at the distinct values of the grouping
  column before you group by it, and write the exclusion into the description.
- **Parameters wear the question's vocabulary.** A question asked in quarters gets a
  `quarter` parameter (`2024-Q4`), not two raw dates as the only door. A question that names
  a period — "2024", "Q3", "last month", "2023 to 2024" — gets that period's parameter
  (`year`, `quarter`, `base_year`/`comp_year`, an anchor date): the calculator or in-dialect
  date math derives the bounds; two raw dates are an INPUT to a template, never the door of a
  pipeline. Technical inputs
  are *derived*: a CALCULATOR node turns the human parameter into the `start_date`/`end_date`
  the SQL binds — the kind comes from `calculators_list`, whose entries each list the phrases
  they answer (see `templates` § Calculators). Two dates the caller already passes need no
  calculator.
- **"Today" is a decision, say which.** `$current_date` is right for a live, scheduled
  pipeline. For a fixed dataset — read its window from the stats or the description — an
  `as_of` (or window) parameter **defaulting to the data's last date** is right, or "last
  quarter" resolves to an empty window next year. **One correction for the trailing kinds:**
  `trailing_periods` and `prior_period` resolve the complete periods immediately before the
  one CONTAINING the anchor, so for them the anchor defaults to **the day AFTER the data's
  last date** — data ends 2026-06-30, "last quarter" → anchor `2026-07-01` →
  2026-04-01..2026-06-30; anchored `2026-06-30` (still inside Q2) it resolves Q1. A relative
  phrase is never yours to
  interpret: read `calculators_list`, pick the kind whose `phrases` match the question's
  words — asking the person when two kinds fit — then write the interpretation you chose
  into the pipeline's description in the question's own words, and name the window the same
  way in every template's description.
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
  **A pushed-down literal is a copy, and a copy is checked.** When a source engine cannot
  reach the table a lookup comes from (a calendar on another engine, a zone map in a file),
  the literal list goes into that source template, the node that COMPUTES the lookup stays in
  the DAG, and the answer node compares the two — a mismatch fails the run, not the reader.
  The list lives in ONE template — a pinned library template the source templates import —
  never copied between siblings. The door stays: the literal covers the window the door can
  select, and the template's `WHERE` binds the door, so `parameters: {}` is never the answer
  to a question that names a period. (Real miss: two sibling templates each carrying the same
  443 literal rows, no node reading the source they came from, and no parameters at all —
  the numbers were right, and nothing would have said so when they stopped being right.)
- **Aggregate at the source.** The source engine is built for its own data; tempdb (H2) is
  not a warehouse. Ship the grain downstream steps need, preserving keys and aggregate state (§4): a node that returns
  `SUM(x) GROUP BY key` ships a thousand rows; the same node without the `GROUP BY` shipped
  tens of thousands into H2 to join a few hundred lookup rows. Push filters down too — on a
  lake table, a compatible partition predicate can skip files; other data skipping depends on layout and metadata.
- **Before you divide, look at MIN/MAX of every column you divide by.** A per-mile, per-rider
  or per-second ratio inherits every lie in its denominator: one absurd distance or one
  zero row collapses the average for everyone. One catalog-stats or probe read of the bounds
  tells you whether the denominator needs a range filter — and the filter you choose is an
  interpretation: write it into the description (§4).
- **Then, and only then, index.** If a staged table is still large *and* a downstream node
  joins or filters it by a key, add a `DDL` node between the staging node and the join:
  `CREATE INDEX IF NOT EXISTS ix_stg_x_key ON stg_x(key)`, `depends_on` the stager, the
  join `depends_on` the index (`pipeline-contract.md §16.4`). Order is load → index → query:
  indexing a filled table is one pass; loading into an indexed one pays per row. Do not index
  small tables (< ~10k rows) or tables read once in a scan.
- **The staged-lookup index, as a named pattern.** A staged lookup you join a large staged
  table to gets a `DDL` node `CREATE INDEX … ON <staged>(<join key>)` between its staging node
  and the join — one template per index, pinned like any other node's; the order is the
  load → index → query rule of the bullet above. 60 s → 5 s was the measured difference on a
  2M-row join in one acceptance run: the join was the whole budget until the key it probed
  had an index.
- **`depends_on` is data flow, nothing else.** A node depends on the nodes whose tables or
  context values it reads. Never add an edge to serialise work "to reduce load" or "avoid
  contention" — you make the critical path longer and hide the real problem. Independent
  nodes run in parallel by design. (Real miss: four source slices chained behind an
  unrelated lake read.)
- **A timeout is a signal, not an obstacle.** A node that hits a timeout is usually doing too
  much work in the wrong place: pre-aggregate at the source, use a rollup table, filter earlier,
  read the plan. Only after that, and only when the data or workload genuinely has a natural
  partition (a period, a key range), may one scan become several — under the rules in §3a:
  exact disjoint coverage of the window, a recombination that is additive at the answer's grain,
  bounded concurrency, and measured evidence. A blind split into N parameter pairs ships the same
  rows N times, adds inputs that drift from the door, and fails under load anyway. If a single
  scan legitimately needs longer, set THAT node's own `settings.timeout_seconds` and say why in
  your handback — see §3a.
- **One template, bound per node.** If two nodes run the same SQL over different values,
  that is one template with parameters, not two copies. Copies drift.
- **On a lake table, distinguish registered partitions from measured pruning.** Read the
  stats first (§1), then use bound predicates on the partition key where they express the
  intended selection. Bound parameters can prune; literals are not required. A filter on a
  sibling timestamp does not by itself prove that date partitions are skipped, but Parquet
  statistics may still skip row groups. The actual layout, metadata and engine determine
  what is skipped; a pushed `READ_PARQUET` filter alone does not prove fewer bytes read.
  Record available file/partition counts and cold/warm timings, and label missing byte-level
  evidence. Do not split a scan into repeated `UNION ALL` walks as a substitute for evidence
  (§3a). `lake` explains the read path; this read-only datasource cannot
  repartition or sort stored files, and tempdb `CREATE INDEX` does not index S3 data.
  **Measured first-hand on DuckDB 1.5.5 — the working rules until a new measurement replaces
  them:** a predicate on the registered partition column prunes — `WHERE <partition_col> IN
  (DATE '…', …)`, `BETWEEN` two dates, and a deterministic expression of the column such as
  `CAST(<partition_col> AS …)` still prune. **Bound parameters prune exactly like literals**:
  write `:d` for a date filter and the engine folds the bound value into the scan (33 bound
  dates read 33 of 731 files, the same as literals). A predicate on a sibling column INSIDE
  the files (a timestamp beside the partition date) read EVERY file and relied on row-group
  statistics — it looked fast on a quiet box and died at the timeout on a busy one. Never
  build a `UNION ALL` of date-range branches to work around that; it is N full walks in one
  statement. One query, the partition column, a list of dates. (Real miss: an 11-branch UNION
  over the sibling timestamp, cancelled at 60 s.)
- **Climb the ladder: probe → render → execute_node → full DAG.** `sql_probe` the exact
  SELECT against the source first — rows, `wall_ms`, and the EXPLAIN plan (captured before
  the run, so it survives the timeout it explains); then `templates_render`; then
  `pipelines_execute_node` on the one node; only then `pipelines_execute` the whole DAG.
  Each rung is cheaper than the next and isolates a different fault class. **The tempdb
  (H2) rung is different:** H2 has no schema to learn, and `sql_probe {"name": "tempdb"}`
  prepares the statement against an EMPTY engine — read its `validation_status`.
  `executed` means a self-contained statement ran: that statement, as written, is valid.
  `incomplete` with `missing_table` means H2 stopped at the first staged table it could not
  find, and nothing after that point — a join form, a clause, a column — was checked; it is not
  a pass and not a failure. Finish it before the run: restate the suspect construct over typed,
  aliased `VALUES` inputs of the staged tables' shape so it executes on the scratch (that proves
  the construct, not the real column types), or use `pipelines_execute` to run the DAG with its
  real staged inputs. Standalone `pipelines_execute_node` refuses tempdb sources; running source
  nodes separately does not retain staged tables. An error is a real H2 error. Rendering, an incomplete preparation and an execution are
  three different pieces of evidence; only the last one says the node runs.
  What the probe rung settles about the DATA — a unit, a time zone, what a coded value
  means — is a fact: `semantics_record` it with that SELECT as `evidence_sql` (core
  step 1). A LAKE table's ref takes `schema` as the DOTTED string — `{"schema": "datalake.mart",
  "table": "events"}` — never a namespace array (that is `semantics.ref_unresolved`).
  **Stop-loss: three identical failures → stop and report.** A schema refusal is not
  your typo — re-introspect or hand back; a fourth identical call changes nothing.
- **A table marked unavailable is broken at the lake, not by your query.** If a lake
  table's registration carries a `last_error` (the register/import response shows it), or a
  node fails
  `datasource.lake.table_unavailable`, the table's view failed to build at connect
  (bad prefix, wrong format, unreadable files) and was skipped. Re-running the query will
  never fix it — report the recorded `last_error` to the user and let them re-register or
  fix the table; do not retry.
- **After a node runs green, analyse it — and say what you found.** Read the node's timing
  and the plan (`sql_probe` the node's SELECT against the source — `plan.scan`, and on a
  lake `partitions_scanned`/`partitions_total`; the node's `node_stats` carries the
  timing), ask one question of every SOURCE node: *is the predicate and join
  key index-supported on that table?* Name the access path the plan shows, never a join
  algorithm you did not see in a plan. If not — a date range filtered by a second column
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
  its `phase`: `execute` → cheaper QUERY; `stage` → FEWER ROWS, usually — but read the error and
  the elapsed time too: a `stage`-phase timeout can still be the CTAS query itself (the staging
  statement runs the SELECT), not the row count. Read the EFFECTIVE limits before you reason
  about them: a datasource with no `query_timeout_seconds` runs under the application default,
  and a node's wall-clock override never changes the statement timeout. Never report an override
  that does not exist.
- **Reach for the node's own budget LAST**, after pre-aggregating, pushing the filter down and
  pruning on the partition column. Capped at `node-timeout-max-seconds` (900), refused at SAVE
  above it. A raised timeout you cannot justify in one sentence is a slow pipeline you agreed to.
- **Partition a scan only when it is the data's own shape, and prove it.** Optimize first (the
  rules above). If the workload still does not fit and the data has a natural partition — a
  period, a key range — several nodes may each read one partition, under four conditions: the
  partitions cover the window exactly and do not overlap; the answer recombines additively at
  its grain (additive counts/sums combine; averages need sums/counts, distinct counts need
  disjoint entity sets or deduplication, and medians need more than partial medians — §4); concurrency is bounded and understood (the
  partitions run at once against the same source, each its own statement under the same
  statement timeout — N partitions are N concurrent loads, and each still has to fit); and the
  evidence is measured — per-node timings, and a reconciliation against the unsplit population
  where one exists. A blind copy-and-parameterise split, or a `UNION ALL` of the same walk, is
  neither. Never add a `depends_on` edge to avoid contention.
  Source cursors drain in parallel, and independent tempdb work may overlap up to the
  deployment's configured limits, so independent nodes really do run at once. `depends_on` is
  data flow, nothing else: a node that reads a table another node staged depends on that node.
- **One warm success near the limit is not reliability.** A node that finished at 90 % of its
  budget once, after a slower run, may have met a warm cache, a quiet box, or a real fix — the
  timings alone do not say which. Report the headroom you measured and the risk that remains.
  When no in-scope plan is robust — the query is as cheap as the source allows and still near
  the budget — say so: report the effective limit, the shape that needs it and the decision the
  operator has to make (a datasource timeout, a rollup at the source), rather than claiming the
  timeout was fixed.
- **Cross-node state is a table, never a session.** Each node's tempdb SQL runs in its own
  session, reset when the node finishes: a `SET SCHEMA`, a `SET @variable`, a local temporary
  table or an open transaction from one node is not visible to the next, and must not be relied
  on. Hand data forward with `output: tempdb` tables and read them with `source: "tempdb"`.

## 4. Get the numbers right — the contract before the arithmetic

Every number the pipeline emits is the end of a chain: what is counted, over which rows, in
which unit, weighted how, computed at what precision, ranked by what rule. Decide the chain in
this order, write each decision into the description (§5), and verify the DECISIONS before you
verify the arithmetic — a formula that reproduces its own output proves consistency, not that
the right quantity was computed.

1. **State the measurement contract before you write a formula.** For every quantity: the
   population (which rows are eligible, and which are excluded — a distance or price filter is an
   eligibility rule, not a cleanup), the time window, the unit, the sampling basis (sample or
   census, at what stated rate), and the output grain. Where a material ambiguity remains — two
   readings of the question, two candidate denominators, a filter that moves the answer — resolve
   it with the person, not by choosing quietly. Reuse a recorded `definition` only when it answers
   THIS question; a rule chosen for another measure is not automatically yours. Counting and
   measuring may need different eligibility: a row can count as an event and still be unfit for a
   per-mile or per-minute metric — separate the two populations when they differ, and say so.
2. **Normalize before you combine.** Two quantities enter one total, one share, one comparison
   or one ranking only when they are the same kind of thing. A sampled count and a census count
   are not: apply the stated sampling weight to every sampled count BEFORE the sum, the share and
   the rank. A constant multiplier preserves order only when it multiplies the whole score; applied
   to one summand it reorders — so "scaling this component cannot change the ranking" is a claim
   to disprove, never to assume. Keep three quantities distinct in the output and its labels:
   observed sample support (rows you saw), the estimated population count (support × weight) and a
   census count. When no rate is stated, a sample can be compared only with itself, and a
   using a within-sample ratio to estimate a population ratio requires a justified sampling
   assumption. Independent sampling does not make a finite sample's ratio exactly equal to the
   population's or make every ratio estimator unbiased; state the assumption and uncertainty.
3. **Reuse a helper by its formula, not its name.** Before importing a shared macro or metric,
   read what it computes: its numerator and denominator, its precision, and what it does with a
   missing value. A helper that ROUNDS is a presentation helper — reused inside a difference, a
   rank or a threshold it changes the answer (rule 4). Match the numerator's population to the
   denominator's: a component recorded only for a subset is a ratio over that subset (a fee some
   rows carry, over the rows that carry it), and a sum of per-row ratios is a different measure
   from a ratio of sums — pick one, name it, and do not label the other "avg". When two sources
   carry different components of a cost or a price (one all-in, one a base amount), they are not
   comparable until a decision makes them so: agree the basis, or keep them as two labelled
   columns; a caveat in the description does not make them one measure. Do not clip, cap or
   impute a monetary or physical value by default — a long duration or a large amount is a source
   fact until a rule you wrote and justified says otherwise.
4. **Carry precision to the output boundary.** Differences, growth, shares of shares, rank
   scores, thresholds and top-N cutoffs are computed at full precision and rounded once, for
   display, at the end — and a displayed difference is the ROUNDED TRUE DIFFERENCE, never the
   difference of two rounded values. Ranking a rounded score manufactures ties and moves the
   cutoff. Ties are a decision: `ROW_NUMBER()` with a stated, deterministic tie-break (a second
   key, then a stable id), or `RANK()` with the expansion written into the description — never a
   window with no secondary key. Verify the membership of the top N and the rows on either side
   of the cutoff, not only the winner.
5. **Support and missingness are part of the answer.** A floor on a combined or estimated
   volume does not give every contributing group its own support: inspect the observed rows per
   source, per mode, per requested subgroup, and show that support beside the estimate. No sampled
   row for a group is not evidence of no population events — it is unknown support: distinguish
   an absent group from a true zero, in the missing-data policy and in the output (a cell that is
   absent, null or flagged, never a silent zero or a point estimate of 100 % / 0 % from one side).
   A numerical gap at the cutoff, or the floor you chose, is not statistical stability; a sampled
   estimate carries uncertainty, and the reply says so instead of calling the ranking stable.
   A threshold the person approved is theirs: keep it, show the leaders' support under it, and
   recommend a different one with the evidence — never raise it silently to make a ranking look
   defensible. Then, per measure, choose the policy for what is absent — report as unknown,
   exclude with the exclusion written down, or impute with the justification written down — and
   apply the SAME policy on both sides of every ratio: a share whose denominator counts periods the
   numerator treats as missing is two answers glued together. Prove coverage — groups and periods
   present against those expected — beside the measure, and exercise it (§5): a run over a period
   the population does not cover, and one with an alternate parameter set.
6. **Cast what you ship across engines.** `SUM`, `AVG` and arithmetic over a `NUMERIC(p,s)` lose
   their declared scale on the wire (Postgres reports "unknown"); make the type explicit:
   `SUM(x)::NUMERIC(14,2)`, `CAST(AVG(x) AS DECIMAL(14,4))`. Before you divide, look at MIN/MAX of
   every column you divide by (§3). H2 specifics that bite: no `:bind` inside a `GROUP BY`
   expression; cast DECIMAL ratio operands to DOUBLE; `VALUES` rows are named `C1, C2…` — or
   alias them (§6.1–6.3).

Then verify the QUESTION (§5): derive the check from the question and the source facts —
population, weights, denominators and all — never from the SQL you just wrote.

### Preserve meaning across aggregation steps

Before reducing a source or combining summaries, identify what the later joins, filters and
groupings need. A small intermediate is useful only if it can still answer the question.

- **Keep the required keys and grain.** Retain downstream join/group/filter keys, including
  dates needed for effective-dated lookups. Aggregating away a required key loses information;
  joining a coarser summary later cannot recover its allocation without an explicit rule.
- **Carry the components of an average.** For a later average of `x`, ship `SUM(x)` and
  `COUNT(x)` over the same eligible rows; `COUNT(*)` matches only when every eligible `x` is
  non-null. Combine as `SUM(x_sum) / NULLIF(SUM(x_count), 0)` with the destination engine's
  appropriate numeric casts (§6), preserving precision until display. Averaging subgroup
  averages gives each subgroup equal weight, which is a different measure unless the counts
  are equal or that weighting is intended. For weighted averages and ratios, retain the
  corresponding weighted numerator and denominator. State the empty-input policy.
- **Distinct counts need distinct entities.** Add partial distinct counts only when the
  counted entity sets are provably disjoint. Disjoint date partitions do not prove this: an
  entity can occur on several dates. Otherwise deduplicate retained keys at the final grain,
  or use a supported mergeable approximation only when its error is acceptable and stated.
- **Check join multiplicity and coverage.** A lookup used to enrich a summary must match
  at most one row per summary row under the complete join predicate (including effective
  dates). Multiple matches multiply measures; an inner join can also lose unmatched rows.
  Verify match counts, unmatched keys and relevant totals before/after the join. An intended
  one-to-many allocation needs explicit weights or a different grain; `DISTINCT` or an
  arbitrary lookup row is not a repair.
- **Snapshots are not flows.** An `as_of_date` balance can often sum across distinct entities
  at one instant, but summing it across dates counts the same holdings repeatedly. Choose
  as-of/closing balance or a defined time average, with a policy for missing dates and
  irregular observations. Do not infer additivity from a numeric column's type or name.

Use these invariants in the Verification recipe (§5) where the pipeline relies on them,
including unequal group sizes, overlapping entities, duplicate lookup keys or multiple
snapshot dates as applicable. Source checks and successful execution alone do not reconcile
the final output; compare it independently at the requested grain.

## 5. Finish like a professional

- **Stop at the draft.** Your update is not live until a human releases it — never call a
  release endpoint, never say "released" or "live" about your own work. Execute the draft
  (by version) to prove it, then hand back.
- **Describe what a reader needs, in sections.** The description is the only documentation
  the pipeline will ever have, and it is read as a document: sections separated by blank
  lines, each opening with its label on its own line, in this order and with these labels.
  **Question** — the question in the person's words and the answer's grain (one row per …).
  **Window and door** — the period and the parameters that set it, and how "today" is
  defined. **Sources and grain** — each datasource and table, its grain, sample vs census,
  time zone and units (§1.6). **Interpretation** — every rule chosen: thresholds, exclusions,
  tie-breaks, sample scaling, which tables were joined for names, and which rules are recorded
  definitions. **Verification** — the numbered recipe a human re-runs before releasing: steps
  separated by blank lines, each carrying its purpose, the datasource and dialect it runs on,
  the parameter values used, the SQL itself on its own lines (rerunnable as-is), the observed
  result, the comparison it supports, and its scope or limitation — plus the check-id when the
  query also lives in `checks[]`. One recipe may span several queries and engines; `checks[]`
  holds the checks, the recipe holds the COMPLETE reproduction path for every verification
  claim you report — reconciliation queries beyond `checks[]` included, exploratory dead ends
  excluded. It carries queries and observed numbers — evidence, never the word
  "validated": a reader re-runs the recipe and reaches their own verdict. The SQL and the
  observed values live with the pipeline, not in a transcript. **Caveats.** Short paragraphs,
  no wall of text; a reader who stops after Question and Window knows what the pipeline is.
- **Read the whole result, not the part your client showed you.** A client can truncate a
  large tool result: if the execute reply reports more rows than you can see, or your client
  shows a truncation notice, page the rows with `executions_get_result` (`limit`, `offset`)
  and reason over what the server returned, never over a partial view.
- **Verify the question, not the SQL you wrote.** Independent means derived again from the
  question and the source facts — the population, the weights, the denominators, the window —
  never by re-running the pipeline's own formula or reading its staged tables. Re-running your
  formula checks arithmetic; only a second derivation can challenge the interpretation, and the
  interpretation is where the answer goes wrong (§4). Then cover what the question asked for:
  every requested dimension (each period, each mode, each subgroup — a combined total that
  agrees says nothing about how it splits), and the vulnerable rows — the rank cutoff and the
  rows on both sides of it, the sparsest groups, the groups that are absent, the window's
  boundaries. Where the result is small, reconcile all of it; where it is not, one winner's total
  is the weakest possible sample — a winner rarely moves, the cutoff does. Compare like with like
  at the declared precision, and report exactly what was compared: which rows, which quantity,
  what matched, what did not.
- **Report what you did not verify.** "Row counts match the previous version" is not
  "the numbers are right". Name the independent check you ran — or that you ran none. A proxy
  is reported as what it proves: a non-empty count for one period and one source shows that
  period and that source have rows — not that the combined, multi-period eligibility pool is
  right; a source-only check never validates an output ranking.
- **Verify three ways — two are checks, and the server is their judge.** A `checks[]` entry is
  ONE datasource, ONE read-only statement, ONE expectation (`value`, `range` or `rows` — a
  `rows` expectation counts the statement's rows, so an assertion need not return a single
  numeric cell), read from the RAW source — never from the pipeline's own output tables — with
  `expected` supplied by you and `observed` produced only by the server's own run
  (`pipelines_run_checks`; there is no tool that records an observed value from a caller). Two
  to five per pipeline is the right band. A human releasing from the UI sees every check's
  expected and observed and cannot release past a failing one without giving a reason. The
  first two verification strategies below are `checks[]` entries; the third is not and cannot
  be — pretending otherwise is the miss this section exists to prevent.

  - **Fixed-baseline drift check.** Literals for one baseline window; `expected` is the value
    your independent query measured there. It says "the baseline still holds" and nothing
    else — name the baseline in the check's `name` ("… (2024 baseline)"), because the static
    expectation means nothing beside any other window. When the baseline stops being the
    interesting window, the check is re-measured and re-based, not silently failed.
  - **Parameterized invariant check.** The SQL binds the pipeline's parameters AND the
    expectation holds for EVERY input the pipeline accepts: a violation count is zero, a
    reconciliation difference is empty. The tell that you have this shape: the expectation
    needs no window in its name. **Never bind a changing parameter while keeping an unrelated
    fixed expected total** — that is a baseline check wearing a bind: green on the baseline, a
    lie everywhere else.
  - **Independent output reconciliation — the recipe, not a check.** A source check cannot
    prove the OUTPUT right — it never sees it, and no `checks[]` entry reads the pipeline's
    result. Recompute the output groups the bullet above names independently from the source —
    joins, filters, weights and denominator included — and compare them against the pipeline's
    actual result rows for the same groups. That comparison is usually more than one query,
    sometimes more than one engine, plus an explicit side-by-side: it lives in the numbered
    Verification recipe, never in `checks[]`, with the SQL, the parameter values, the observed
    numbers, the comparison and its limits (§5 Describe). A source assertion that DOES fit the
    one-datasource, one-statement shape (a raw total the group's number must equal) is a check
    of one of the two kinds above — link it in the recipe by check-id. A shared literal list
    copied into several templates is verified the same way: the run compares the copies (§3),
    and the recipe shows the comparison — a check that counts one copy proves nothing about
    the others.

  Two honest limits, by design. Expectations are STATIC — the server compares the observed
  value against the value, range or row count you declared; there is no dynamic expectation.
  And the release gate binds the declared DEFAULTS (it supplies no parameters): a
  parameterized check proves the default window, not every combination a caller can pass; a
  fixed-literal baseline check proves its own named baseline, whatever the defaults are. What
  the defaults cannot prove stays in the Verification recipe as measured observations; the
  server remains the only source of a check run's observed values.
- **Exercise the door, then say what it accepts.** Before you hand back, run the draft with a
  valid input other than the defaults, and with an input the data does not cover or the
  pipeline should refuse — an empty answer with the policy stated is the pipeline working; a
  quietly plausible number is not. A period the question names is exactly that period in
  every node: a two-period comparison reads each period as asked, never one period as
  "everything up to" the other (which folds intervening periods into one side) and never the
  same period twice; when two sources read the window differently, make the formulas agree or
  document and restrict the combinations the door supports. Write the supported inputs into the
  description.
- **Leave what you learned where the next session looks.** A rule the person approved — a
  threshold, an eligibility filter, a price basis — is a `definition` recorded through
  `semantics_record` (core rule 13), with the evidence probe where one shows it; a note in
  your own client's memory or in the reply is invisible to every other session and every other
  client. Keep a fact about the platform (a tool's behavior, a timeout you met) out of the
  datasource's facts — it describes the server, not the data — and keep a diagnosis to what you
  observed: one failure is one observation, not a universal explanation.
- **The person's constraints are part of the task.** If the person said no delegation, no
  sub-agents, read-only, or MCP-only, that holds for the whole task, including anything you would
  have handed to a helper. The server sees only what you call, never how you work, so nothing
  in this document or on the server enforces it — you do.
- **Report the index analysis** (§3): for every source node, one line — supported by
  `<index>` / *not supported — suggest `CREATE INDEX … ON table (cols)`* / *lake: filters on
  the partition column, `partitions_scanned`/`partitions_total` from `sql_probe`'s plan*.
  The operator reads the handback; that line is how a slow pipeline becomes a fast one
  without a rewrite.
- **A performance rewrite proves two things, both measured.** When a node is the bottleneck
  and you reshape it (§6.5's materialisation, a pushed-down filter, a rollup table), the
  rewrite is done when (1) a meaningful reconciliation says the new shape answers the same
  question — the same rows, or the same aggregates over the same population — and (2) the
  timings say it is faster: `node_stats` durations before and after, or `sql_probe`'s
  `wall_ms` on both shapes. "Should be faster" is not a measurement, and a faster query over
  a different population is a different answer. §6.5 is about the shape it names — an
  aggregate or ranking CTE JOINED to another table in H2 — not a licence to stage every CTE,
  and H2's inlining is not every engine's behavior.
- **Report from the output you read, not the story you remember.** Every quantitative claim
  in your reply — a direction ("share rose"), a unit, the period, a sample size — is
  reconciled against the result rows before you write it: read the sign off the numbers, the
  period off the window you bound, the base off the denominator. An association is not a
  mechanism: "the two move together" is what the rows say; "one caused the other" names a
  cause the rows do not show (core rule 13½). When the reply and the output disagree,
  the output is right — fix the reply.

## 6. Engine quirks you will meet

(core's rules 14–17 live here since 131 — a ledger row or handback citing "rule 14",
"rule 15", "rule 16" or "rule 17" means §6.1, §6.2, §6.3 and §6.4 respectively.)

### 6.1 A bind inside GROUP BY (H2)

Never put a `:bind` parameter inside a GROUP BY expression in H2 (tempdb).
H2 fails to match the GROUP BY expression to the identical SELECT expression when it
contains a parameter marker — `Column "x.amount" must be in the GROUP BY list`
(SQLState 90016), a lie that sends you chasing the wrong fix. Compute the classified
value in a derived table (`FROM (SELECT CASE ... :threshold ... END AS bucket ...) x`)
and `GROUP BY x.bucket` — a plain column always matches. Measured on H2 2.3.232.

### 6.2 DECIMAL ÷ DECIMAL in H2

Never divide DECIMAL by DECIMAL in H2 (tempdb) — cast to DOUBLE first.
H2's DECIMAL arithmetic collapses result scale (a `DECIMAL(·,2)/DECIMAL(·,0)` division
can come back scale-0): `SUM(distance)/SUM(seconds)*3600` returned **0** where the
true answer was ~12, and `100.0 * part / whole` rounded to one decimal. Cast every
ratio operand: `CAST(SUM(x) AS DOUBLE) / NULLIF(CAST(SUM(y) AS DOUBLE), 0)`.
Verified empirically against the pinned driver 2.3.232 — plain DECIMAL gave 2448.00
where DOUBLE gave the correct 2456.81.

### 6.3 H2 names `VALUES` columns `C1, C2, …` — and `rows` is reserved on MySQL/DuckDB

H2 (tempdb) names `VALUES` columns `C1, C2, …` — not `column1`. Postgres and DuckDB
call a `VALUES` row's columns `column1…`; H2 2.x calls them `C1…`, so `SELECT column1
FROM (VALUES (0),(1))` fails at execution with `Column "column1" not found` after every
other node ran green. Dialect-safe form: alias the derived table's columns —
`FROM (VALUES (0),(1)) AS t(hr)` — or spell a small spine as `SELECT 0 AS hr UNION ALL
SELECT 1 …`. H2 has no schema to introspect, so check every tempdb statement with
`sql_probe {"name": "tempdb"}` (an empty engine: a self-contained error surfaces in
milliseconds) before you pay a full DAG run to find out — passing `parameters` for every
`:name` the statement binds (else `invalid_params`; the scratch checks the statement, not
values, so any representative value of the right type will do). Read `validation_status`:
`executed` validated the statement; `incomplete` means H2 stopped at a missing staged table
and checked nothing after it — finish it as §3's ladder says (a `VALUES` restatement, or the
node run with its real inputs). And `rows` is a reserved word on MySQL and DuckDB —
alias a count `AS n`, never `AS rows`.

### 6.4 A source node sees only its own datasource

Its SQL runs on that engine; staged tables are visible only to `source: "tempdb"` nodes,
and no engine reads another. To filter a source by a set computed elsewhere, bind a
parameter or write literals and keep the computing node for the labels — and say so in
the description (§3). The same wall holds inside a probe: a `zones` lookup on the SQLite
datasource cannot be joined inside a Postgres probe — bind the ids or stage both.

### 6.5 In tempdb, an aggregate you join to is a NODE, not a CTE

H2 inlines a `WITH` clause as a view — it does not materialise it — so a CTE that ranks or
aggregates a staged table and is then JOINED to another table is recomputed for every probe
of the join: the whole aggregate, once per outer row. A statement that finishes in under a
second when the aggregate is its own table burned a full 60 s statement timeout in this shape
(measured: 0.8 s materialised vs `pipeline.node.query_timeout` inlined, same data). The tell
on the failed node's stack: a `queryGroup` inside a `queryGroup`, reached through a
`RegularQueryExpressionIndex` — H2's name for the inlined view being probed as an index.
Stage the aggregate as its own tempdb node (`output: tempdb`, the ranked or grouped rows only)
and join it in the NEXT node; the DAG is the materialisation H2 will not do for you. The same
rule reaches a derived table in the `FROM` clause when it aggregates and is joined. This is
H2's behavior, measured on the pinned driver — other engines materialise differently, and a
CTE that is not joined to is not this shape; §5's rewrite rule (reconcile, then measure) is
the general one.

## Do / Don't, in one screen

| Do | Don't |
|---|---|
| State which learn-first calls you made for each datasource and each table before your first `templates_create` | Read a table you did not `_get_columns` and `_get_table_stats` |
| Read description → schemas → tables → columns → stats → rows before the first line of SQL | Write SQL against a column, a unit, a time zone or a sample rate you assumed |
| Read the listing — a not-found answer names the nearest table | Probe a table name you did not read from `datasources_get_tables` |
| Write what you learned about the data into the description | Leave the next reader to re-probe what you already established |
| After each source node runs, check its predicate/join key against the table's indexes (`datasources_get_table_stats`, `sql_probe`'s `plan.scan`) and put the `CREATE INDEX` suggestion in the handback | Report "the node was slow" and leave the operator to guess |
| Filter a lake table on its partition column, with literals, bound parameters, or an `IN` list | Filter on a sibling timestamp inside the files, or UNION date-range branches |
| Join the lookup; answer with names | Print a code and call it an answer |
| `templates_list {"q": "<table>"}` before creating a lookup template; pin the one that exists | Mint a second copy of a lookup another pipeline already pins |
| `templates_render` every template you create, including one created after the pipeline exists | Let the next `pipelines_execute` be a new template's first render |
| Answer a named site from the station AT it; record the station choice as a fact | Average the stations near a site and report it as the site's reading |
| Stage a ranked or grouped tempdb result as its own node, then join it (§6.5) | Join to a CTE over a staged table inside one H2 statement |
| Infer table roles from `*_companies`, `*_by_day`, `*_sample` when no description exists — then confirm with the columns | Ignore a table because nothing described it, or trust the name alone |
| Aggregate and filter at the source; ship the answer's grain | Stage raw rows into H2 and aggregate there |
| Load → `DDL` index on the join key → query, for large staged tables | Index small tables, or index before loading |
| `depends_on` = the tables and context values a node reads | Chain nodes to "reduce load" |
| Treat a timeout as "wrong place for this work"; partition a scan only with exact disjoint coverage, additive recombination and measured evidence | Split a scan blindly to dodge a budget, or call one warm run near the limit "fixed" |
| Look the phrase up in the catalog and say which reading you chose | Decide what "last quarter" means yourself |
| Name the window the same way in the pipeline and every template | Three phrasings for one window |
| Cast aggregates you ship (`::NUMERIC(14,2)`) | Trust the driver's guess at the scale |
| State each quantity's population, unit, window and weight before combining; weight every sampled count by its stated rate before totals, shares and rankings | Add a sample count to a census count, or scale one summand and call the order unchanged |
| Rank and select at full precision with a stated tie-break; round once, at the output | Rank the difference of two rounded display values, or leave `ROW_NUMBER()` without a secondary key |
| Read a shared helper's formula, precision and missing-value behavior before reusing it | Rank with a rounded presentation macro because it is reusable |
| Show observed support per source and subgroup beside the estimate; keep the person's threshold and recommend changes with evidence | Report an absent sample group as zero, call a cutoff gap "stable", or raise a user-approved floor silently |
| Exclude the lookup's catch-all rows when the question names the real groups | Group by whatever the lookup contains |
| `sql_probe` a tempdb statement and read `validation_status`; finish an `incomplete` one before the run | Read a missing-table result as proof the statement is sound |
| Derive the check from the question; reconcile every requested dimension and the rows at the cutoff | Recompute your own formula, check one winner, and call the ranking verified |
| Run an alternate valid input and an uncovered one; read each named period exactly | Fold intervening periods into one side of a comparison, or count a period twice |
| Record an approved rule with `semantics_record` | Keep the rule in client memory or the reply, where no other session finds it |
| Name the baseline in a fixed-baseline check's `name` | Bind a changing parameter beside an unrelated fixed expected total |
| Keep every reconciliation query in the numbered Verification recipe, check-id beside the query that is also a check | Force a two-engine reconciliation into one `checks[]` entry, or write "validated" where the rerunnable SQL should be |
| Choose and state the missing-data policy — one policy for numerators and denominators, coverage proven | Treat a missing period as a zero, or glue two population definitions into one share |
| Prove a performance rewrite with a reconciliation and before/after timings | Claim a speedup you did not measure, or stage every CTE |
| Report directions, units, periods and sample sizes from the result rows you read | Report the story you remember — or a cause the rows do not show |
| Stop at the draft; a human releases | Call release; say "released"/"live" |
