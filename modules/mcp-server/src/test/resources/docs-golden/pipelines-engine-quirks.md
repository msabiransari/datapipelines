
# Engine quirks you will meet

(A ledger row or handback citing "rule 14", "rule 15", "rule 16" or "rule 17" means §6.1,
§6.2, §6.3 and §6.4 respectively; "§6.5" is the materialisation rule.)

### 6.1 A bind inside GROUP BY (H2)

Never put a `:bind` parameter inside a GROUP BY expression in H2 (tempdb). H2 fails to match
the GROUP BY expression to the identical SELECT expression when it contains a parameter
marker — `Column "x.amount" must be in the GROUP BY list` (SQLState 90016), a lie that sends
you chasing the wrong fix. Compute the classified value in a derived table (`FROM (SELECT
CASE ... :threshold ... END AS bucket ...) x`) and `GROUP BY x.bucket` — a plain column
always matches. Measured on H2 2.3.232.

### 6.2 DECIMAL ÷ DECIMAL in H2

Never divide DECIMAL by DECIMAL in H2 (tempdb) — cast to DOUBLE first. H2's DECIMAL
arithmetic collapses result scale (a `DECIMAL(·,2)/DECIMAL(·,0)` division can come back
scale-0): `SUM(distance)/SUM(seconds)*3600` returned **0** where the true answer was ~12, and
`100.0 * part / whole` rounded to one decimal. Cast every ratio operand: `CAST(SUM(x) AS
DOUBLE) / NULLIF(CAST(SUM(y) AS DOUBLE), 0)`. Verified empirically against the pinned driver
2.3.232 — plain DECIMAL gave 2448.00 where DOUBLE gave the correct 2456.81.

### 6.3 H2 names `VALUES` columns `C1, C2, …` — and `rows` is reserved on MySQL/DuckDB

H2 (tempdb) names `VALUES` columns `C1, C2, …` — not `column1`. Postgres and DuckDB call a
`VALUES` row's columns `column1…`; H2 2.x calls them `C1…`, so `SELECT column1 FROM (VALUES
(0),(1))` fails at execution with `Column "column1" not found` after every other node ran
green. Dialect-safe form: alias the derived table's columns — `FROM (VALUES (0),(1)) AS
t(hr)` — or spell a small spine as `SELECT 0 AS hr UNION ALL SELECT 1 …`. H2 has no schema to
introspect, so check every tempdb statement with `sql_probe {"name": "tempdb"}` (an empty
engine: a self-contained error surfaces in milliseconds) before you pay a full DAG run to
find out — passing `parameters` for every `:name` the statement binds (else `invalid_params`;
the scratch checks the statement, not values, so any representative value of the right type
will do). Read `validation_status`: `executed` validated the statement; `incomplete` means H2
stopped at a missing staged table and checked nothing after it — finish it as
`pipelines-dag`'s ladder says (a `VALUES` restatement, or the node run with its real inputs).
And `rows` is a reserved word on MySQL and DuckDB — alias a count `AS n`, never `AS rows`.

### 6.4 A source node sees only its own datasource

Its SQL runs on that engine; staged tables are visible only to `source: "tempdb"` nodes, and
no engine reads another. To filter a source by a set computed elsewhere, bind a parameter or
write literals and keep the computing node for the labels — and say so in the description
(`pipelines-dag`). The same wall holds inside a probe: a `zones` lookup on the SQLite
datasource cannot be joined inside a Postgres probe — bind the ids or stage both.

### 6.5 In tempdb, an aggregate you join to is a NODE, not a CTE

H2 inlines a `WITH` clause as a view — it does not materialise it — so a CTE that ranks or
aggregates a staged table and is then JOINED to another table is recomputed for every probe
of the join: the whole aggregate, once per outer row. A statement that finishes in under a
second when the aggregate is its own table burned a full 60 s statement timeout in this shape
(measured: 0.8 s materialised vs `pipeline.node.query_timeout` inlined, same data). The tell
on the failed node's stack: a `queryGroup` inside a `queryGroup`, reached through a
`RegularQueryExpressionIndex` — H2's name for the inlined view being probed as an index.
Stage the aggregate as its own tempdb node (`output: tempdb`, the ranked or grouped rows
only) and join it in the NEXT node; the DAG is the materialisation H2 will not do for you.
The same rule reaches a derived table in the `FROM` clause when it aggregates and is joined.
This is H2's behavior, measured on the pinned driver — other engines materialise differently,
and a CTE that is not joined to is not this shape; `pipelines-verification`'s rewrite rule
(reconcile, then measure) is the general one.
