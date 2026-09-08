# Templates, calculators and the Context

Open when you are writing SQL: what a template is, how library imports work, and how a CALCULATOR node computes a value the SQL then binds.

Part of the `datapipelines` skill — the operating core is `SKILL.md` beside this file.

**Template** — Freemarker SQL: `id` — always a folder path, e.g.
`nyc/mobility/daily_by_zone.sql` (2–10 `/`-separated segments, each starting `[a-z0-9]`,
≤ 64 chars per segment, ≤ 200 total; a bare `fetch_orders.sql` is refused), `dialect` (one of
`POSTGRES`, `ORACLE`, `MSSQL`, `MYSQL`, `H2`, `DUCKDB`, `SQLITE`, `LAKE`), `display_name`,
`description`, `imports` (`[{"id","version","alias"}]` for library macros), `body`,
`is_library`. **There is no params_schema field** — the variables a body may reference
are exactly the calling pipeline's `parameters` keys (defaults applied). A declared
parameter is referenced in the SQL as a **bind parameter**: `WHERE id = :customer_id`.
The body must **never** contain `<#import>` / `<#include>` — imports come from the `imports` array and
the body calls macros by alias (`<@dates.date_range …/>`). Library templates
(`is_library: true`) contain only `<#macro>`/`<#function>` definitions.

## Calculators — computing a value the SQL then binds

A `CALCULATOR` node evaluates one **pure function the server ships** and writes ONE typed value
into the execution Context. Downstream nodes bind it exactly like a parameter.

```json
{ "id": "fiscal_q", "type": "CALCULATOR",
  "kind": "fiscal_quarter",
  "inputs": {"date": "$current_date", "fiscal_start": "$org_fiscal_start_date"},
  "context_key": "run_fiscal_quarter",
  "depends_on": [] }
```

…and then, in a node that `depends_on: ["fiscal_q"]`:

```sql
SELECT region, SUM(amount) AS total
FROM orders
WHERE fiscal_quarter = :run_fiscal_quarter
GROUP BY region
```

**Call `calculators_list` before you author one.** The kind names and their input names are not
guessable; the tool returns every kind with typed inputs, its output type and a worked example.
`calculators_get {kind}` is the same entry for one kind.

Four rules, and the third is the one that bites:

1. **`$name` is a reference, anything else is a literal.** `"fiscal_start": "$org_fiscal_start_date"`
   reads the deployment's setting; `"fiscal_start": "09-15"` pins this pipeline's own. Literals are
   type-checked at save.
2. **`context_key` is not `output`.** It names a Context value, never a table. A CALCULATOR node
   carries no `source`, no `template` and no `output` — declaring any of them is refused.
3. **Sequencing is `depends_on`, not array order.** A node that references another node's
   `context_key` — in `inputs` OR as a `:bind` in its SQL — must depend on the producer, directly
   or transitively. Otherwise the save is refused with
   `pipeline.validation.calculator_input_unordered`, and the fix is one entry in `depends_on`.
4. **Row-level transforms are NOT calculators.** A calculator computes one value for the whole
   run. Transforming columns is SQL's job — on the source engine, in tempdb, or through a library
   template macro. There is no row calculator and there is not going to be one.

### Context keys you can reference without declaring anything

| Key | Type | What it is |
|---|---|---|
| `org_currency_name`, `org_currency_symbol` | STRING | The deployment's currency |
| `org_fiscal_start_date` | STRING | `MM-DD` — when the fiscal year starts |
| `org_week_start` | STRING | `monday` or `sunday` |
| `org_timezone` | STRING | IANA zone id |
| `current_date` | DATE | Today, in `org_timezone`, fixed at execution start |
| `current_timestamp` | TIMESTAMP | The execution's start instant |
| `execution_id` | STRING | This execution's id |

Precedence, lowest first — **org config < platform < declared `parameters` < execute-time inputs
< calculator outputs**. Declaring a parameter named `org_timezone` overrides the deployment's, and
that override is visible in the body, which is the point. A calculator may shadow an org or
platform key; it may **never** shadow a declared parameter (`calculator_output_collision`), and
two nodes may not write the same key.

After a run, `executions_get` shows each CALCULATOR node's `context_key` and `context_value` in
its `node_stats` entry, and `parameters` carries the fully resolved Context — every tier, the
calculator outputs included. That is where you look when a computed number is not what you
expected.

**Overriding a calculator.** Every calculator `context_key` is also an implicit OPTIONAL input
of `pipelines_execute` — `pipelines_get` lists these under `parameters` with `"derived": true`.
Supply the key and the node is **skipped**: the supplied value is what downstream nodes bind,
and the node's `executions_get.node_stats[]` entry shows `provided_by: "caller"`. Omit it and
the node computes from the Context exactly as before. The value is typed by the kind's output,
so a backfill passes the right JSON type: a pipeline whose `fiscal_quarter` calculator derives
the run quarter from `$current_date` can be re-run for an old quarter with
`"run_fiscal_quarter": 4` — a JSON **number** (the kind outputs INTEGER), never `"2025-Q4"`,
which fails coercion with `pipeline.execution.invalid_parameter_type`.

## Aggregates and arithmetic across engines — cast the result

When you ship an aggregate or arithmetic result across engines, **cast it** —
`SUM(x)::NUMERIC(14,2)`, `CAST(AVG(x) AS DECIMAL(14,4))` — so the wire type is what you
mean, not what the driver guesses. Engines drop the typmod on computed numerics
(Postgres reports `SUM`/`AVG`/division over a `NUMERIC(10,2)` with no precision and no
scale), and while the platform now stores an unsized exact numeric exactly (it stages as
H2 `DECFLOAT`, every fraction intact), the cast is still the readable contract: it
documents the shape you intended, and it protects the result if the query is ever run
through a client or engine that does not.
