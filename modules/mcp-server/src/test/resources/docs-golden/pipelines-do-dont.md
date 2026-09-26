
# Do / Don't, in one screen

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
| Stage a ranked or grouped tempdb result as its own node, then join it (`pipelines-engine-quirks` § 6.5) | Join to a CTE over a staged table inside one H2 statement |
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
