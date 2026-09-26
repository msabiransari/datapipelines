
# Learn before you assume; read the schema like an analyst

The data is the only authority on the data. You know nothing about a datasource until you have
read it — not its time zone, not its units, not whether a table is a sample or a census, not
what a coded value means, not which column a lake table is partitioned on. Descriptions and
comments — the datasource's `description`, a table's or column's `remarks` — are one input,
written by a person who may be wrong or out of date; **the columns and the rows are the ground
truth**, and every assumption that survives into your SQL must have been checked against them.

## The mandatory read order

For **every** datasource a pipeline will touch:

1. `datasources_list` — your first call and your first read: every granted datasource's
   description (grain, sampling, units, window, time zone, if the registrant wrote them),
   dialect and connection facts, AND the datasource-wide `facts` earlier sessions recorded.
   `datasources_get` is the same payload for one datasource — the refresh after you
   `semantics_record`. Neither lists tables: a lake's registered tables come from
   `datasources_get_tables`, whose entries state partition status for a lake table (step 2).
   The `definitions` on the listing are this workspace's rules; each carries `implemented_by`.
   Search the facts, find the definition, find the transform that implements it, and reuse it
   before writing your own (`transforms-contracts` § Implements).
2. `datasources_get_schemas` → `datasources_get_tables(namespace)` — every table, with its
   `remarks`; on a LAKE datasource each entry also carries `partition_column`, the registered
   partition key. Use a compatible predicate on it when it matches the question's window.
   `null` means no registered key, not one file or a mandatory full scan; file/row-group
   skipping and column projection may still apply. Read the names as an analyst would (the
   next section), then confirm.
3. `datasources_get_columns(table, namespace)` for every table the SQL will read — canonical
   types, nullability, `remarks`. **Never write SQL against a column you have not seen
   listed.** A column name you recall from another deployment is a guess.
4. `datasources_get_table_stats(table)` — row estimate, indexes, per-column bounds, from the
   catalog (never a scan). The bounds tell you the data's window and whether a timestamp
   column carries dates or date-times; the indexes tell you which predicates will be cheap.
   For a lake table the stats state partition status out loud: a registered partition column
   reports as `partition_column` (and an index entry of kind `partition`);
   `partition_column: null` describes the registry, not file count or physical layout. A
   pushed filter alone does not establish how many files, row groups or bytes were skipped.
5. `sql_probe` — a few rows of every table you will filter or join on, and the distinct
   values of every column you will filter on, group by, or join by. This is where you learn
   what a code means, whether a "date" is text, whether an empty value is `NULL` or `''`,
   whether a numeric column is in the unit its name suggests, whether a timestamp is local or
   UTC (compare a known event; read the `remarks`; if nothing says, treat it as naive local
   and write that assumption down). A claim about which values a column holds over the WHOLE
   table needs a whole-table probe (`SELECT col, MIN(d), MAX(d) … GROUP BY col` over the full
   window) — never an inference from a lookup table or a description.

With NO description — the usual case; nobody fills table- and column-level detail into a form
— the surfaces still answer, one line each:

- A NAME: `_sample` marks a sample, `_daily`/`_monthly` a rollup, a `<subject>_<grain>`
  compound the grain, `_ts` vs `_at` a timestamp style, a `unit` column beside a `value`
  column per-row units, a `_meta` table metadata the datasource carries about itself.
- A TYPE: an engine `timestamp` without time zone in `source_type` is naive by construction;
  a `TEXT` date in SQLite is text to cast, not a date.
- STATS: a date column's min/max is the data's window; a rollup's total against its source's
  count is the sample-or-full answer.
- A PROBE: the `unit` column's distinct values, a code column's spread.

6. **Write what you learned into the pipeline's description** — every fact the SQL relies on
   that the schema alone does not state (time zone, units, sampling, grain, window, what an
   enum value means). A reader who cannot see your probes must still trust your numbers.
7. **Then re-read that description before `pipelines_create`, and strike every sentence you
   cannot point at a call you made.** (The pipelines guide's step 4 fixes what a description
   carries.) And a description is an EXEMPLAR: the next agent reads existing pipelines to
   copy their idioms, so a claim you wrote without running its probe is a claim the next
   pipeline inherits.

Two facts you must establish before you touch a number: **is this table a sample or a census**
(a hash-sampled feed compared to a full count as a share gives nonsense; if a sample rate is
stated anywhere — description, remarks, a metadata table — scale by it; if none is stated,
compare a sample only with itself, `pipelines-numbers` — and record the unstated rate as the
assumption it is, without evidence, so a human sees it needs verification:
`datasources-semantics` is the recording system, the pipelines guide's rule 13½ the
discipline), and **what one row is** (the grain — a pre-aggregate summed at the wrong grain
double-counts silently).

Copy an existing pipeline's SHAPE — staging, join placement, casts — never its window
semantics; the question's words decide the window.

## Read the schema like an analyst

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
  name is a hypothesis: `datasources_get_columns` confirms it — a lookup has the id column, a
  name column and little else; a pre-aggregate has a count or a sum and the grain columns.
- **Check the grain and measure of every pre-aggregate** before combining it: daily event
  counts can sum over a month; daily balances generally cannot. A join must preserve the
  intended cardinality at that grain. Carry the keys and aggregate state needed downstream
  (`pipelines-numbers`).
- **A place the question names that has a sensor AT it is answered by that sensor.** When the
  question names a site — an airport, a plant, a store, a port — and the data has a station,
  a meter or a device located at that site, the site's measure is that one station's reading,
  not an average over the stations nearby. The average is a different measure: it smooths the
  very difference the question asks about (real miss: averaging five stations named the wrong
  site). Probe the station table for the site's name or coordinates first; when no station
  sits at the site, the nearest one — or the average — is a choice, and you say so. Either
  way the station choice is recorded: a `preference` fact (or a `definition` when it defines
  the measure), with the probe that listed the stations as its evidence. **If the answer's
  grain names the place** — an `airport` column, a `site` column — **the reading is per
  place, not a mean across places**: a row that names one site carries that site's sensor,
  never the average of several.
- **A unit that is not what the question ranks is never ranked.** A lookup can carry an entry
  that is the wrong KIND of thing beside the right ones — an airport zone in a region
  ranking, a depot in a store ranking, a test account among customers. It is excluded with
  the reason written into the description, or shown outside the ranking as its own line —
  never ranked among the units the question named. (Real miss: an airport's own zone ranked
  as a region, and the answer's "top region" was a runway.)
- **Exclude what the question excludes.** A lookup table often carries catch-all rows —
  `Unknown`, `N/A`, an out-of-area entry — beside the values the question means; "each
  region" means the regions, not the catch-alls. Look at the distinct values of the grouping
  column before you group by it, and write the exclusion into the description.
- **"Today" is a decision, say which.** `$current_date` is right for a live pipeline. For a
  fixed dataset — read its window from the stats or the description — an `as_of` (or window)
  parameter **defaulting to the data's last date** is right, or "last quarter" resolves to an
  empty window next year. Which anchor a relative phrase takes, and the one-window-naming
  discipline across the pipeline and its templates, is rule 13's (the pipelines guide) — never
  yours to interpret: read `calculators_list`, pick the kind whose `phrases` match the
  question's words, asking the person when two kinds fit.
- **Deployment knowledge comes from `$org_*` context keys** (fiscal start, week start,
  timezone, currency) — never a literal in a template.
- **Read the table's stats and indexes before you write the predicate.**
  `datasources_get_table_stats` answers the row estimate, the index list and the per-column
  bounds from the engine's catalog — never a scan, so ask it of a hundred-million-row table
  too. A filter the indexes do not support on a large table is the timeout you will hit
  (`pipelines-dag`); find it here, and when no index carries your predicate, write the
  suggested `CREATE INDEX` into your handback — a readonly datasource is the operator's to
  change, so the suggestion is the deliverable.
