---
area: pipelines
layer: guide
purpose: Authoring or changing a pipeline — the workflow, the numbered rules, and which reference holds the judgment for each step.
---

# Pipelines — the authoring workflow

A **pipeline** is a JSON document: `schema_version`, `name` (a folder path — the core's naming
rule), `display_name`, `description`, `parameters` (typed input map) and `nodes` (the DAG).
`id`, `version`, `owner` and timestamps are server-assigned. Nodes pin immutable template
versions; updating a template never changes a pipeline until you update the node reference.
Create lands v1 as a DRAFT — executable at once (the core's draft-and-release rule). One node
may ship rows to the caller (at most one caller node; zero is legal); the others stage into
tempdb, the execution's own in-memory H2.

**The references carry the judgment between these steps** — open the one the step names, and
read `pipelines-do-dont` before your first non-trivial pipeline: every row there is a mistake
an agent made here.

0. **Pick the folder.** List the roots and reuse one — the core's naming rule. Everything you
   create below goes under this prefix; it cannot be moved later.
1. **Learn before you assume.** You know nothing about a datasource until you have read it. `datasources_list` is your first call and your first read — **`definitions` on the listing are rules earlier pipelines chose**: reuse or supersede, **never re-choose**. Then introspect EVERY table the SQL will read before the first `templates_create` — a LOOKUP is a table the SQL reads — probe the values you will filter, group or join by, and record what you learned. A `pipelines_create` naming a table you never `_get_columns`'d is refused (`pipeline.validation.table_not_learned` — the refusal lists the calls that clear it). Before your first template, state which of these calls you made for each datasource and each table: a table you did not `_get_columns` and `_get_table_stats` is **a table you may not read**. `pipelines-learning` is the full procedure.
1½. **Probe before you write.** `datasources_get_table_stats` on every table, then `sql_probe`
    the exact SELECT with representative parameters and read `plan.scan` and `wall_ms` — a
    sequential scan on a large table is the timeout you would meet in step 5, found while it
    is still cheap. Then write the calculation down before the first template: each quantity's
    population, window, unit, grain and sampling weight (`pipelines-numbers`).
2. **Write the template.** `templates_create` with a dialect matching the source and a
   description naming every parameter. Before creating a lookup or reference template,
   `templates_list {"q": "<table>"}` and **PIN what exists** — a narrowed variant is a `WHERE`
   in the consumer, not a new template. To change a draft, read its `body_hash` with
   `templates_get` and call `templates_update` — **the dialect is inherited**. The `templates`
   guide holds the anatomy; the calculators reference holds the CALCULATOR node.
3. **Render before you create.** `templates_render` with representative values — save-time
   validation is parse-only, so this is your check that the SQL is what you meant. Mandatory
   for every template the pipeline pins, **including one you create after the pipeline exists:
   the run is not its render**. For tempdb sources read `validation_status` — `incomplete`
   means H2 stopped at a missing staged table and checked nothing after it
   (`pipelines-engine-quirks`).
4. **Create the pipeline.** `pipelines_create` with declared parameters, node references,
   `depends_on` wiring and `output` blocks; save-time validation dry-renders every template.
   Every node carries a one-sentence description: what it ships and at what grain. **The
   pipeline's `description` is a document, not a paragraph** — sections separated by blank
   lines, each label on its own line, in this order: **Question** (the question in the
   person's words and the answer's grain), **Window and door** (the period and the parameters
   that set it), **Sources and grain** (each table: grain, sample vs census, time zone,
   units), **Interpretation** (every rule chosen and which are recorded definitions),
   **Verification** (the numbered recipe a human re-runs before releasing), **Caveats**. A
   description carries data facts and the interpretation you chose — **never claims about your own process**: "validated" and "checked" go in your reply. `pipelines-schema` holds the
   body's fields; `pipelines-verification` holds the recipe's requirements.
5. **Iterate on the DRAFT, run it, then write checks.** `pipelines_update` writes the draft
   (first update opens it, later ones overwrite it). `pipelines_execute` with no version runs
   the working version — your draft when one exists — so testing needs no version argument. A
   draft whose pinned template was **updated after its last render** is refused
   (`pipeline.execution.template_unrendered`) — render, then run. Then verify the QUESTION:
   two check shapes are `checks[]` entries, and independent output reconciliation lives in the
   description's Verification recipe — `pipelines-verification` is the whole discipline. Then
   stop: leave the draft for a human to release.
6. **Read the result.** Inline first page, `total_rows`, `has_more`, `ttl_seconds`; page the
   remainder with `executions_get_result` within the TTL. A client can truncate a large tool
   result — page the rows and reason over what the server returned, **never over a partial view**. The `executions` guide owns runs and failures.

## The window rules (13)

13. **Parameters wear the question's vocabulary; technical inputs are derived.** Two raw dates
    are an INPUT to a template, **never the door of a pipeline**: a question that names a
    period — "2024", "Q3", "last month", "2023 to 2024" — gets that period's parameter
    (`year`, `quarter`, `base_year`/`comp_year`, an anchor date), and the calculator or
    in-dialect date math derives the bounds. The server enforces that: a raw-date door is
    refused until you pass `door_acknowledged: true` — which you do only when the question
    truly fixes two dates; passing it to silence the refusal is the miss it exists to catch. A
    relative phrase's door is an anchor date — `$current_date` for a live pipeline; for a
    fixed dataset, the data's last date for a "this period" phrase but **the day AFTER the data's last date for a "last N periods" phrase** — those resolve the complete periods before the one CONTAINING the anchor (data ends 2026-06-30: "last quarter" anchors
    `2026-07-01` → 2026-04-01..2026-06-30; `2026-06-30` → Q1). **Any relative time phrase in
    the question — "last", "this", "to date", "trailing", "N ago" — is resolved by reading
    `calculators_list`:** each kind lists the everyday phrases it answers; pick the kind whose
    phrases match the question's words, and when two kinds both fit, ask the person which one.
    A CALCULATOR node writes the technical inputs into the execution Context for downstream SQL
    to bind; the catalog's `outputs` says which keys. Write the interpretation you chose into
    the description in the question's own words, and **name the window the same way** in every
    template's description. The calculator's `context_key` is already an optional execute input
    — never also declare it as a parameter
    (`pipeline.validation.calculator_output_collision`). When the question leaves a rule to
    you — what counts as rainy, active, churned, late — write the rule you chose into the
    description AND record it as a `definition` fact, with the probe that showed the
    distribution you chose over; before choosing, read the listing's facts — **a `definition`
    an earlier pipeline recorded is the one to reuse**, so two pipelines in one workspace
    never answer "rainy" two ways (`datasources-semantics`).

13½. **A number you did not measure is not a number.** Row counts, sample rates and windows
    come from `datasources_get_table_stats`, a probe, or a metadata table — never estimated.
    **A claim about the DATA is a probe you ran or a registry line you read:** stats describe
    registered partitions, not all physical layout; confirm pruning from execution evidence. A
    column's values over the WHOLE table come from a whole-table probe, never a lookup or a
    description. **And a cause is a claim too:** the REASON for a number is named only after
    the probe that shows it, and never names a mechanism you have not seen — that includes the
    PLATFORM: a tool behaving unexpectedly is reported as what you observed and what you did —
    **never name the server's mechanism**, which you cannot see. **Report from the output you
    read:** a direction, a unit, the period, a sample size in your reply is reconciled against
    the actual result rows before you write it — the rows outrank the story you remember. **A
    description carries data facts and the interpretation you chose — never claims about your own process.** When the data cannot reveal a fact you depend on: write the assumption into
    the description and record it without evidence so it **lands as `asserted`** for a human
    to verify — **that record, not your reply**, is what the next session finds — and say in
    the reply which facts you derived and which you assumed. An assumption that moves the
    answer by an order of magnitude: stop and ask first.

## Prerequisites and common mistakes

Prerequisites: a key whose role may author (the core's role rule), the datasources granted and
introspected (step 1), the templates pinned and rendered (steps 2–3).

The mistakes that fill `pipelines-do-dont`: writing SQL against a column or unit you assumed
instead of probed; printing a code where a lookup table could answer with names; staging raw
rows into tempdb to aggregate there instead of at the source; two caller nodes; letting the
next execute be a new template's first render; splitting a scan blindly to dodge a timeout;
ranking rounded values; declaring the window three different ways.

## References — open when

- **`pipelines-learning`** — step 1 or 1½: the read order, reading a schema like an analyst.
- **`pipelines-dag`** — wiring multi-node work: shaping the DAG, tempdb, indexes, timeouts.
- **`pipelines-numbers`** — the measurement contract before the arithmetic; aggregation.
- **`pipelines-verification`** — step 5: the check shapes, the recipe, exercising the door.
- **`pipelines-engine-quirks`** — a tempdb statement failed oddly, or before the first tempdb
  node: the H2 and dialect traps, cited elsewhere as rules 14–17.
- **`pipelines-do-dont`** — the mistakes, one screen, before you start and when you review.
- **`pipelines-schema`** — writing or reading a pipeline body: parameter fields, a minimal copy.
- **`pipelines-node-types`** — wiring the DAG: what a node declares, where a DQL node's rows go.
- **`pipelines-naming`** — a worked example of the core's naming rule for a whole area.
- **`pipelines-calculators`** — the calculator kinds, generated from the server's registry.
- **`pipelines-tools`** — the area's tools, generated from their shipped descriptions.
