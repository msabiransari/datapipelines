
# Engine quirks you will meet

(A ledger row or handback citing "rule 14", "rule 15", "rule 16" or "rule 17" means §6.1,
§6.2, §6.3 and §6.4 respectively; "§6.5" is the materialisation rule; §6.6–§6.9 are the authoring
shapes: a guard node, a literal list in a library, an hour spine, reserved aliases.)

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
`pipelines-dag`'s ladder says (a `VALUES` restatement, or `pipelines_execute`, whose run
stages the real inputs; standalone `pipelines_execute_node` refuses a tempdb source).
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

### 6.6 A guard node: make the run fail with a message you wrote

tempdb has no `ASSERT`. To stop a run whose precondition is false (a staged table empty, a
total that does not reconcile, a key that is not unique), stage a node whose SELECT fails on a
CAST of text only the bad case produces: `SELECT CAST(CASE WHEN n = 0 THEN '0' ELSE 'guard:
found ' || n END AS INTEGER) AS g FROM (...) t`, where `n` counts the offending rows. The
CASE must read a COLUMN: `CAST('guard: x' AS INTEGER)` is folded at parse time and fails even
in a branch that never runs. When it trips, the execution returns `pipeline.node.staging_failed`
("Pipeline aborted: node <id> failed") and your text is the message of the LAST `caused_by`
entry (`For input string: "guard: found 1"`). The whole run aborts even if nothing depends on
the guard, so give it `depends_on` only to order it; a passing guard stages one row you ignore.
Measured on H2 2.3.232.

### 6.7 A library cannot hold a literal list in a top-level `<#assign>`

A library template (`is_library: true`) is refused with `template.validation.is_library_without_macros`
("A library must have no output outside its macro/function definitions") when it carries a top-level
`<#assign hours = [0, 6, 12]>`. Hold the list in a `<#function>` that returns it —
`<#function hours_of_day><#return [0, 6, 12]></#function>` — and have the library's macros iterate
`hours_of_day()`. Consumers import the library and call its macros by alias (`templates`).
Measured on the save: the assign form is refused, the function form is created.

### 6.8 An hour spine: `SYSTEM_RANGE`, with the column named and cast

For a spine of integers (hours 0–23, days 1–31), `FROM SYSTEM_RANGE(0, 23) AS h(hr)` is the
shortest form in tempdb. Name the column in the alias list: the unnamed column is `X`, in
upper case, and `SELECT x` fails with `Column "x" not found` (`MODE=PostgreSQL` folds unquoted
names to lower case). The column is a BIGINTEGER, so cast it where a join or a bind expects an
INTEGER: `SELECT CAST(h.hr AS INTEGER) AS hr FROM SYSTEM_RANGE(0, 23) AS h(hr)`. It is H2-only —
the other engines spell a spine differently (the aliased `VALUES` form of §6.3 is the
portable one).

### 6.9 Reserved words as aliases: `year`, `month`, `day`, `value`, `key`

H2 refuses these as an unquoted `AS` alias — `SELECT 1 AS year` is `Syntax error in SQL
statement ... expected "identifier"` (SQLState 42001) — and so do `hour`, `user`, `order` and
`current_date`; `date` and a non-word such as `yr` are accepted. Quoting works but is a trap:
`AS "year"` keeps the lower-case name, and a later bare `SELECT year` from that staged table
is a syntax error again. Choose a non-reserved alias (`yr`, `mo`, `dy`, `val`, `k`) in every
tempdb node and in every table a downstream node reads. Measured on H2 2.3.232 with
`sql_probe {"name": "tempdb"}`; the nine refused and two accepted words above are the whole
list measured, not a catalogue of the engine's reserved words.
