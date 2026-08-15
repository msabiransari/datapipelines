# Semantic Layers for Agent-Facing Data — Research Brief

**Status:** research (pre-spec) — input to a future `docs/semantic-layer.md`
**Date:** 2026-08-13 (revised same day after independent adversarial review — 12
findings, 4 MAJOR, all applied; review verified this doc against mcp-server v1.3,
ROADMAP v1.1, pipeline-contract v1.3, datasources v1.9, type-system, auth §7.5–7.6,
templates §4/§7, metadata-db §4.4–4.6. **Part five added same day: owner direction
discussion — supersedes parts of Part four where they conflict.**)
**Purpose:** What the field converged on, what dbt actually ships, which libraries exist,
and how to sequence a semantic layer for datapipelines.co. Motivating problem: MCP
metadata endpoints let an agent *infer* meaning from schemas, and that inference is
unreliable; a semantic layer replaces inference with declared meaning.
**Companion artifact:** https://claude.ai/code/artifact/da90e2fc-f054-47d0-85ed-5b540414d2a6

---

## Bottom line

- A semantic layer is not richer metadata for the model to read. It is a **closed
  vocabulary plus a deterministic compiler**: the agent names measures and dimensions,
  and the server — never the model — emits the SQL. Descriptions, synonyms, and
  verified queries are accuracy tuning on top of that one structural move.
- Every serious implementation — dbt/MetricFlow, Cube, Snowflake Cortex Analyst,
  Malloy, Boring Semantic Layer — has the same seven-part anatomy and, once it grew an
  MCP server, converged on roughly the same four tools. Copy the shape; don't invent one.
- There is **no JVM semantic-layer library**, so for datapipelines.co this is a build.
  Most of the hard machinery already exists in this repo: **a semantic query is a
  one-node pipeline**. Compile the structured request into a DQL node, execute it
  through the DAG executor, and the result envelope, TTL cursor, type mapping, and
  auth all come along for free (§ Strategy).

---

## Part one — the convergence

### Four generations of the category

| Generation | Examples | Consumer | Defining constraint |
|---|---|---|---|
| BI-bound (2000s–) | LookML, Power BI/DAX, Tableau, MicroStrategy | One BI tool | Model trapped in the tool that renders it |
| Headless (2019–) | Cube, dbt Semantic Layer (MetricFlow), AtScale | Any client, over an API | Definitions in Git, served over REST/SQL/GraphQL |
| Warehouse-native (2024–) | Snowflake Cortex Analyst + Semantic Views, Databricks Metric Views | The vendor's own NL interface | First layers designed for an LLM rather than a chart |
| Agent-facing (2025–26) | All of the above + MCP servers + the OSI interchange spec | Autonomous agents | Tool surface and governance become the product |

### The anatomy (invariant across all of them)

Read any four of these products' docs side by side and the same seven constructs
appear under different names. This is the actual spec being implemented:

1. **Dataset / entity** — a physical table or view, plus its *grain* (what one row
   means) and its keys. Without declared grain, every aggregate is a coin flip.
2. **Dimension** — an attribute to group or filter by. Typed: categorical vs. time;
   time dimensions carry a smallest valid grain.
3. **Measure / metric** — an aggregation with a declared function and a declared time
   grain. Metrics compose measures (ratios, cumulative windows, period-over-period).
4. **Relationship** — declared joins with *cardinality*. This is what stops an agent
   silently fanning out a one-to-many join and doubling a revenue number.
5. **AI context** — descriptions, synonyms, and example queries at every level.
   Snowflake names synonyms, metrics, and verified queries as its three accuracy
   levers beyond bare table/column names.
6. **Access policy** — row- and column-level rules evaluated at *compile* time, so an
   unauthorized request fails compilation and the data is never read.
7. **Query contract** — a structured request (measures, dimensions, filters, grain,
   limit), not SQL. The compiler turns it into dialect SQL. **This is the load-bearing
   piece.**

### Why the query contract is the whole point

The temptation is to treat this as a documentation problem: enrich the metadata and
the model will infer better. That helps at the margin and then stops, because the
failure mode isn't ignorance of what a column means — it's that authoring correct SQL
against a real schema is a hard generation task with a huge output space and no
validation until execution.

The benchmark record makes the gap concrete. On the original Spider benchmark GPT-4o
scored 86.6% (top systems ~91%); on **Spider 2.0** (enterprise schemas averaging ~800
columns, some >1,000, real BigQuery/Snowflake environments) the same model drops to
**10.1%**, o1-preview to 17.1%, multi-step agentic evaluation ~21%. (Reported metrics
differ across the two benchmarks — Spider 1.0 figures are commonly exact-match,
Spider 2.0 execution accuracy — but the collapse is driven by schema scale, not the
metric change.)

That gap has since been closed, and *how* is the argument for a semantic layer. By
March 2026 the top Spider 2.0-Snow system reports 96.70% execution accuracy. A 2026
paper (arXiv 2606.31041) takes the most explicitly semantic route: the agent composes
a compact intermediate representation (a "Semantic Model Query") instead of SQL, and a
deterministic compiler emits dialect-specific SQL — 94.15% execution accuracy on
Spider2-snow with Gemini 3 Pro, third on the official leaderboard.

> **How to read those numbers.** The leaderboard systems are heavy purpose-built
> harnesses; none reproduced here, and 94.15% is the paper's own claim. What survives
> the caveat is the direction: the wins came from *structure* — schema linking,
> verified building blocks, deterministic compilation — not from a bigger model. A
> semantic layer buys that structure once, up front, instead of paying an agentic
> exploration loop on every question.

### The convergent MCP tool surface

Three independent implementations landed on nearly identical tools:

- **Boring Semantic Layer:** `list_models`, `get_model`, `get_time_range`,
  `query_model`, `compare_periods`
- **Malloy `malloyyo`:** `list_sources`, `describe_source`, `query`
- **Cube (read-only pair):** `searchDataModel`, `runQuery`

Guidance for governed MCP servers independently recommends the same small set
(`list_metrics`, `get_entities`, `validate_join`, `query_metric`) and warns against
the alternative: *faced with twelve tools that all return revenue-shaped numbers, an
agent picks one, often the wrong one.* Two design notes worth stealing: put catalogs
and definitions on the MCP **resource** surface rather than the tool surface
(discovery is read-only context, not an action), and return governance denials as tool
errors with `isError: true` and an actionable message, so the agent degrades instead
of crashing.

---

## Part two — dbt

### What dbt is, and what its semantic layer actually does

**dbt itself is not a semantic layer.** It's a transformation framework: SELECT
statements as versioned models in Git, compiled into a DAG, materialized as
tables/views inside the warehouse, with tests and lineage. It owns the *transform* in
ELT. Nothing about that answers "what does revenue mean."

**The dbt Semantic Layer is a separate thing alongside it**, powered by MetricFlow
(from dbt Labs' 2023 acquisition of Transform; open-sourced Apache 2.0 in late 2025).
YAML next to dbt models declares semantic models and metrics; at query time MetricFlow
builds a dataflow plan, generates warehouse SQL, and runs it in the warehouse.

### The YAML shape

```yaml
# a semantic model points at a dbt model and declares its grain
semantic_models:
  - name: orders
    description: One row per customer order.
    model: ref('fct_orders')
    defaults:
      agg_time_dimension: ordered_at

    entities:              # primary | unique | foreign | natural
      - name: order
        type: primary
      - name: customer
        type: foreign
        expr: customer_id

    dimensions:            # categorical | time
      - name: ordered_at
        type: time
        type_params: {time_granularity: day}
      - name: status
        type: categorical
        description: Fulfilment state of the order.

    measures:              # the aggregation is declared, not inferred
      - name: order_total
        expr: amount
        agg: sum

# metrics compose measures; types: simple, ratio, cumulative, derived, conversion
metrics:
  - name: revenue
    type: simple
    type_params: {measure: order_total}
```

The parts that earn their keep (and are easy to skip when rolling your own):
**entities are typed** (primary/unique/foreign/natural), which is how the engine
proves a join path is safe rather than guessing; **the aggregation is declared**
rather than inferred from a column name; **every measure has an aggregation time
dimension**, which is what makes "revenue last month" well-defined.

### How agents reach it

dbt ships an official MCP server — local via `uvx dbt-mcp`, plus a hosted remote
flavour — organised into eight toolsets covering project metadata, lineage, SQL
execution, job control, and the Semantic Layer. The Semantic Layer toolset is six
tools including `list_metrics`. Same discover-then-query-by-name pattern as everyone
else.

### Where it doesn't fit

- **Licensing is split.** MetricFlow is Apache 2.0 and runs standalone at no cost.
  The serving layer — query API, caching, access control, BI and MCP integrations —
  is dbt Cloud, requiring a paid Starter or Enterprise tier. "MetricFlow is open
  source" and "the dbt Semantic Layer is free" are different claims.
- **It assumes a warehouse and a dbt project.** Compiles against dbt models in
  Snowflake, BigQuery, Databricks, Redshift. Pointing it at operational Postgres is
  not the intended shape.
- **It answers metric-shaped questions only.** No exploratory row-level lookups,
  multi-step analysis, cross-source joins, or conversation memory.
- **Compile cost is real.** Cold compiles over large metric graphs take seconds and
  scan large marts; an agent loop can fire several metric lookups per user question.
  Standard mitigation is pre-materialisation + compile-result caching.

**Verdict:** the YAML schema is worth studying and partly copying. The product is a
poor fit for a JVM service over operational databases; adopting it means adopting dbt,
a warehouse, and a dbt Cloud subscription to get the API actually wanted.

---

## Part three — the shelf

Verified against the repositories and docs on 2026-08-13 (stars/licence from the
GitHub API that day).

| Project | Licence | Stack | Model format | Native MCP | Postgres | Notes |
|---|---|---|---|---|---|---|
| Cube Core | non-standard (GitHub: `NOASSERTION`), ~20.6k ★ | Node + Rust | YAML/JS | cloud-tied | yes | Most adopted headless layer. Self-hostable (Docker) with SQL/REST/GraphQL APIs. First-party MCP server is 20 tools across six groups but is a **Cube Cloud Premium/Enterprise** feature — self-hosters wrap the REST API in their own thin MCP server. Read the LICENSE before depending on it. |
| MetricFlow | Apache-2.0, ~1.7k ★ | Python | dbt YAML | via dbt Cloud | warehouses | Engine behind the dbt Semantic Layer, usable standalone. Best-specified metric grammar in the field; tightly coupled to dbt projects. |
| Boring Semantic Layer | MIT, ~478 ★ | Python + Ibis | YAML or Python | **built in** | yes | A library, not a platform — runs in-process, no server. Ships `MCPSemanticModel` over FastMCP with five tools. Ibis backends: Postgres, DuckDB, Snowflake, BigQuery. Closest thing to a drop-in reference implementation. |
| Malloy + Publisher | MIT, ~96 ★ (Publisher) | TypeScript | `.malloy` DSL | **built in** | yes | A semantic modelling *language*; nested data first-class. Publisher serves models over REST + MCP; `malloyyo` runs a local stdio MCP server (`list_sources`, `describe_source`, `query`). Team moved Google → Meta; APIs declared subject to change. |
| Apache Ossie (OSI) | Apache-2.0, spec v0.1.1 | — | YAML spec | n/a | dialect-aware | Not an engine — the interchange *format*. See below. |
| Lightdash / Superset / Dremio | Apache-2.0 | various | various | no | yes | Open source, but the semantic model is bound to their BI product. Wrong shape when the consumer is an agent. |

### The one to pay attention to: OSI / Apache Ossie

Launched 2025-09-23 by Snowflake with Salesforce, dbt Labs, BlackRock, RelationalAI;
entered the Apache Incubator in June 2026 as **Apache Ossie**. Five constructs:
semantic models, datasets, fields, relationships, metrics. Three features make it more
than a lowest common denominator:

- **Multi-dialect expressions.** A single field or metric can carry expressions for
  `ANSI_SQL`, `SNOWFLAKE`, `DATABRICKS`, `MDX`, `TABLEAU` simultaneously; a converter
  picks its dialect and falls back to ANSI.
- **First-class AI context.** Descriptions, synonyms, and example queries annotatable
  at every level — model, dataset, field, relationship, metric. Agent legibility is a
  spec requirement, not a comment field.
- **Lossless vendor extensions.** `custom_extensions` blocks tagged by vendor,
  preserved through round-trip conversion, ignored by tools that don't know them.

> **Status check before betting on it (mid-2026):** no product ships native OSI
> support. Four reference converters merged (dbt/MetricFlow, GoodData, Salesforce,
> Apache Polaris); 50+ organisations participate, but participation signals intent,
> not delivery — and neither Looker nor Power BI take part publicly. **Adopt it as
> the file format** (costs nothing, buys an exit); do not plan around importing or
> exporting to other vendors' tools yet.

### Two gaps on the shelf

1. **No JVM or Kotlin semantic layer library exists.** Every mature option is Python,
   Node, or TypeScript. For a Kotlin service the honest choices are: sidecar in
   another runtime, embedded Python, or write the compiler yourself.
2. **None of them federate.** Every product compiles to one engine and assumes the
   data already landed there. A semantic model spanning a Postgres OLTP database and a
   MySQL reporting replica has no off-the-shelf home. datapipelines.co already solved
   this (tempdb staging).

---

## Part four — strategy for datapipelines.co

### Eight principles, in the order they pay off

1. **Adopt the OSI/Ossie YAML shape as the model format on day one** — even writing
   every line of the compiler ourselves. Apache-licensed, already settled the naming
   arguments, and makes a future migration to Cube/Malloy a converter, not a rewrite.
2. **Author the model; do not infer it at query time.** Bootstrap a draft from
   introspection with an LLM (this is what Snowflake's semantic model generator does),
   then a human verifies and commits. The value is in the assertions a human made.
3. **Compile, don't generate.** In the happy path the agent never emits SQL. It sends
   a structured request naming things from a closed vocabulary; the server compiles.
4. **Validate against the closed vocabulary before compiling.** Unknown measure names
   fail loudly with a suggestion (`unknown measure 'rev'; did you mean 'revenue'?`),
   never fall through to fuzzy matching. Errors that teach are the cheapest accuracy
   mechanism.
5. **Keep the tool surface small and intent-shaped.** Four to six tools; catalog on
   the resource surface; resist one tool per metric.
6. **Invest in verified queries early.** A repository of known-good question → query
   pairs is the highest-yield lever after descriptions, and the one everyone adds
   last. It doubles as a regression suite: replay the corpus after every model change.
7. **Keep a demoted raw-SQL escape hatch.** Semantic layers only cover metric-shaped
   questions. Keep raw SQL on a read-only role with statement timeout + mandatory row
   limit — but make the semantic path the default and log every fallback, because the
   fallback log *is* the model backlog.
8. **Guard the model against schema drift with a test, not a rule.** A model
   referencing a column renamed six weeks ago fails silently and confidently. One CI
   test resolving every field, expression, and join against the live
   `information_schema` turns that into a red build.

### We already built most of the backend

The reason this is a build rather than a buy is not just the missing JVM library — a
semantic layer's expensive half is execution, and datapipelines.co already ships it:

| Semantic layer needs | datapipelines.co has | Gap |
|---|---|---|
| SQL execution against many engines | Dialect adapters per datasource, pooled, credential-encrypted, refused-property hardening | none |
| Dialect-specific expression variants | Dialect adapters supply drivers, type mapping, pooling, URL validation — execution and typing only (datasources §4.2) | per-dialect SQL *generation* (identifier quoting, date arithmetic, LIMIT vs FETCH FIRST vs ROWNUM) is new compiler work; OSI's named dialects don't cover this repo's seven |
| SQL generation from a structure | Freemarker templates, versioned + immutably pinned; parse-checked at template save, dry-rendered at pipeline save (templates §7.1/§7.2) | templates are hand-authored, not compiled from a model |
| Result delivery, typing, paging | Schema envelope, Redis cursor with TTL, `executions_get_result` pagination, BIG* string encoding | none |
| Cross-source joins | tempdb staging in per-execution H2 — the federation no other semantic layer has | none |
| Verified query repository | Named, versioned, parameterised pipelines with typed parameter maps | not yet linked to a semantic vocabulary |
| Agent transport, auth, audit | MCP over Streamable HTTP, per-agent API keys, scopes, structured errors, resource surface | none |
| **The semantic model itself** | — | **this is the whole build** |

The architectural move that follows: **compile a semantic query into a one-node
pipeline and execute it through the existing DAG executor.** A structured request
resolves against the model, emits SQL for the datasource's dialect, and becomes a
single DQL node with `output.target: caller` (omitted `output` — pipeline-contract
§16.1). Downstream — result envelope, TTL cursor, paging, type mapping, node stats,
audit — is reused. A model spanning two datasources compiles to a two-source pipeline
staging into tempdb: the federated semantic layer nobody else on the shelf can offer.

> **Fork to settle in the spec, not the code — and the coupling surface is larger
> than "namespace pollution."** If a semantic query is a pipeline, `semantics_query`
> is a front end over the execution machinery, and the question is whether compiled
> pipelines are **ephemeral or persisted**. Per the current contract, *persisted* is
> the path that matches existing machinery and *ephemeral* is the contract change:
> (a) a node's `template: {id, version}` is required and SQL lives in template
> entities, never inline in the pipeline (pipeline-contract §2, §4.6) — compiled SQL
> has no legal home without minting template entities or amending the contract;
> (b) `pipeline_executions` carries a NOT NULL FK to `pipeline_versions`
> (metadata-db §4.6) — an ephemeral pipeline cannot record an execution, which
> breaks `executions_get_result`, execution-ownership auth, and the
> `datapipelines://executions/{id}` resource, i.e. the very things reuse is supposed
> to deliver. Persisted has its own cost beyond clutter: pipeline names are globally
> unique *including soft-deleted rows* (pipeline-contract §12.1), so every compiled
> query burns a name forever unless the spec carves out a namespace. Note result
> caching is itself a deferred v2 item (ROADMAP §3.6) — persistence provides a
> natural cache key, not free caching.

### The tool surface, in the house naming convention

Four tools in a new `semantics` domain, following `{domain}_{action}` — deliberately
NOT one tool per model, which would flip `tools.listChanged` and reintroduce the
twelve-revenue-tools problem:

```
semantics_list        # models available, optionally filtered by datasource
semantics_get         # one model: dimensions, measures, joins, descriptions,
                      #   synonyms, example queries, time grains
semantics_time_range  # min/max for a time dimension — stops the agent
                      #   guessing what date range exists
semantics_query       # {model, measures[], dimensions[], filters[], grain,
                      #   order_by[], limit} → same result envelope as
                      #   pipelines_execute

# resources (discovery is context, not an action; keyed by model id per
#   mcp-server §7.1's pattern — a model may span datasources, so the
#   datasource list is model content, not a URI segment)
datapipelines://semantics/{model}
datapipelines://semantics/{model}/examples
```

**Scopes:** auth §7.6 is the only place operation-level scope requirements live, so
the spec must add matrix rows there in the same change: `semantics_list` /
`semantics_get` / `semantics_time_range` → `read`; `semantics_query` → `execute`.
One collision to resolve explicitly: under the *persisted* fork, `semantics_query`
silently creates pipelines — an `author`-scope operation — while agent keys will
typically hold only `execute`. Whether semantic-model resources participate in
`resources/list` scope-filtering (mcp-server §7.3) also needs a row.

**Filters and injection:** `filters[]` values (and `order_by` / `limit` boundaries)
are open input compiled into raw SQL, and templates §4.4 puts injection prevention on
the SQL author — which the compiler now is. The spec must choose between
compiler-side typed-literal escaping and parameterized output; ROADMAP §2 already
lists **parameterized SQL output (`{sql, params}`)** as the v1.1 item that closes
this gap, and it is the natural substrate for semantic filters — P4 should probably
depend on it.

Note what this does to the deferred `create_pipeline_for_question` prompt
([mcp-server §8.2](mcp-server.md)): it was pulled from v1 on the reasoning that
without introspection the agent would stall or hallucinate a schema. Schema
introspection is the minimum fix; a semantic model is the better one — the agent
authors against curated business vocabulary with worked examples rather than a
thousand raw column names.

### Phasing

Sized for a solo operator; each phase independently useful and independently
checkable.

| Phase | Work | Exit gate |
|---|---|---|
| **P0** | Ship the deferred introspection tools already in [ROADMAP §2](ROADMAP.md#2-v11-candidates): `datasources_get_schemas` / `_get_tables` / `_get_columns` + REST counterparts. Necessary substrate (the model is authored against and validated against it) — necessary, not sufficient, which is precisely the limit already hit. | agent can enumerate every schema, table and column without guessing |
| **P1** | **Write the spec before the code**: `docs/semantic-layer.md` settling model format (OSI-shaped), storage (metadata DB vs Git-loaded files), the ephemeral-vs-persisted fork, **model versioning & pinning** (everything else executable in this product is immutable-per-version with explicit pins — pipeline-contract §15.4, templates §5.1; a mutable-in-place model would be the sole exception, and persisted compiled pipelines must record which model version they came from), the filter/injection mechanism, scope rows, and the tool surface — then move the corresponding ROADMAP items out, per the house rule. | the spec answers every fork above without re-litigation |
| **P2** | Model registry, validator, drift test. Load + validate models at startup; resolve every field/expression/join against the live schema. Ship the drift test **in the same commit as the first model**. | renaming a source column turns the build red |
| **P3** | Resources, descriptions, synonyms on the MCP resource surface. No compiler yet — agents still author SQL, but against curated vocabulary with worked examples. First measurable accuracy gain; materially improves `create_pipeline_for_question` (P0's introspection tools already unblock it per mcp-server §8.2). Start collecting question → query pairs here — the corpus format locks at P5. | a held-out question set measurably improves |
| **P4** | The compiler + `semantics_query`. Structured request in, one-node pipeline out. Closed-vocabulary validation before compile, cardinality-aware join proving, declared grain arithmetic, mandatory limit. Log resolved entities, proven join path, emitted SQL on every call. Reuse the existing execution path. | the agent's happy path emits zero SQL |
| **P5** | Verified queries as a regression corpus: the pairs collected since P3, converted to P4's structured-query shape, served as context, replayed after every model change. (Placed after P4 only because the corpus format needs P4's query shape; collection starts at P3 per principle 6.) | model changes gated by corpus replay |
| **P6** | Compile-time access policy: row/column rules injected during compilation; unauthorized requests fail before any data is read, with a structured error. Defer until multi-tenant SaaS is real — same trigger already recorded for OAuth. | an unauthorized request cannot produce a row |

### What NOT to build

- **A SQL parser.** Already rejected on record ([ROADMAP §1](ROADMAP.md)); a semantic
  layer doesn't change the reasoning — we generate SQL from a typed model, not parse it.
- **Our own YAML dialect.** Naming arguments settled by OSI + MetricFlow; reuse is
  free and buys a migration path.
- **A tool per model or per metric.** Named failure mode in governed-MCP guidance —
  faced with twelve tools that all return revenue-shaped numbers, an agent picks the
  wrong one — and it degrades as the catalog grows. (ROADMAP §3.7 does sanction
  dynamic per-pipeline tools for v2, flipping `tools.listChanged`, so the argument
  rests on the failure mode, not the flag.)
- **A second execution path.** If `semantics_query` doesn't run through the DAG
  executor, we maintain two result envelopes, two cursors, two audit trails.
- **Model inference at query time.** Reintroduces exactly the guessing the layer
  exists to eliminate, with worse latency.
- **Cross-vendor OSI interchange, for now.** No product consumes it natively yet. Use
  the format; don't plan a workflow around exporting to someone else's tool.
- **Caching / pre-aggregation before P4 ships.** Real at warehouse scale, premature
  here; the result cursor already absorbs repeat reads.

---

## Part five — owner direction (2026-08-13 discussion, resume point)

Recorded from a design conversation with the owner after Parts one–four were
written. **Where this part conflicts with Part four, this part wins.** These are
direction decisions and open proposals, not yet a spec — P1 is where they get
settled.

### Decision 1 — no hand-authored model file. The semantic layer is learned.

The owner rejects a hand-authored YAML/OSI model as the authoring mechanism. The
semantic layer should be **populated and maintained by agents**, harvesting meaning
from the pipelines engineers already build: when an engineer ships a pipeline that
computes revenue, that pipeline *is* an executable statement of what revenue means —
the agent should be able to save that fact and reuse it when authoring the next
pipeline.

What changes and what survives:

- **Dies:** YAML as an authoring format; the "human writes the model" workflow;
  OSI as the model's native shape (demote it to an optional *export* format later —
  costs nothing to keep the door open).
- **Survives — the anatomy.** Agent-saved facts must be *structured*, not prose
  notes: measure, expression, dataset, grain, join path + cardinality, time
  dimension. Prose blobs would be re-inferred every time, reintroducing the
  guessing. The seven-part anatomy (Part one) becomes the **table schema of the
  learned store** (metadata DB, Flyway migration — rows, not files).
- **Survives — provenance + trust.** Every learned fact carries
  `source_pipeline_id + version` and a trust state (e.g. `observed` →
  `verified`). Promotion by signals: independent pipelines agreeing, source
  pipeline still live, optional one-click human confirm. Conflicting definitions
  (gross vs. net revenue) **coexist with provenance and are shown to the agent as
  a conflict** — never a silent winner. Rationale: "the pipeline executed" is not
  evidence of semantic correctness; an unverified store compounds wrong facts.
- **Survives — the drift guard (P2).** Learned facts referencing renamed/dropped
  columns are *demoted* on schema drift, not silently served. Template/pipeline
  version pinning makes this detectable.
- **Capture mechanism (open fork for P1):** extraction at pipeline-save time vs.
  an explicit agent tool (e.g. `semantics_record`) vs. both. Also open: promotion
  rules, retention of superseded facts, whether capture runs on execution success
  rather than save.

### Decision 2 — the metadata layer is the spine; semantics merge into it

The [ROADMAP §2](ROADMAP.md#2-v11-candidates) schema-introspection tools
(`datasources_get_schemas` / `_get_tables` / `_get_columns`) move from "necessary
substrate" to **the primary surface**. There is one metadata layer, progressively
enriched: introspection responses carry raw schema *plus* whatever learned
semantic facts exist for that table/column (descriptions, known measures touching
it, known join paths, trust state). Agents get one place to look; semantic
knowledge appears exactly where the agent is already looking when it authors SQL.
This replaces Part four's separate `datapipelines://semantics/{model}` catalog as
the *starting* design (a dedicated surface may still emerge later; P1 decides).

### Decision 3 — components + composition instead of a from-scratch SQL compiler

The owner's alternative to Part four's compiler: engineers build **reusable
components** — today a Freemarker template (single-source logic), and, with one
contract addition, a whole **pipeline used as a node inside another pipeline**
(new node type `pipeline`), so multi-source calculations compose. A pipeline is
already "a federated query where each engine runs its share"; composition extends
that. The agent's happy path becomes **pick a verified component + wire
parameters**, not generate SQL — which achieves the same structural move the
compiler was for (closed vocabulary, server-side determinism, no model-authored
SQL) with far less new machinery: "compilation" collapses to instantiating
pipelines from existing verified pieces.

Trade-off to keep eyes-open (recorded, not resolved): a component vocabulary is
**coarse-grained** — it answers the questions someone pre-built (softened by
Freemarker parameterization, e.g. group-by as a parameter). A classic
measure/dimension compiler is **fine-grained** — any slice of any measure without
pre-building. Novel slices under the component model fall back to SQL authoring
against the enriched metadata layer (Decision 2), and the fallback log remains
the backlog signal. A thin compiler on top of the learned store stays available
as a later escalation; it is no longer the plan of record.

Contract questions the `pipeline` node type opens (settle in P1/spec, alongside
the existing ephemeral-vs-persisted fork):

- **Version pinning:** a `pipeline` node must pin the child pipeline version
  (consistency with template pinning, pipeline-contract §15.4 / templates §5.1).
- **Cycle detection** across pipeline references, and a depth limit.
- **Parameter mapping** parent → child (child `parameters` filled from parent
  values/params).
- **Output semantics:** child's caller-output rows land as a parent staging
  table? What happens to a child with `output.target: datasource`?
- **Execution semantics:** inline the child DAG into the parent execution vs.
  spawn a child execution record (affects executions FK, stats, audit, SSE).
- **Auth/scopes:** executing a pipeline that embeds someone else's pipeline.

### Impact on the Part-four phasing

- **P1 (spec-first — unchanged as the next step)** now covers: learned-store
  schema, capture mechanism + trust states + conflict semantics, metadata-layer
  enrichment shape, the `pipeline` node type contract, and versioning. The static
  model format question is gone.
- **P2** drift test applies to learned facts instead of an authored model.
- **P3** = serve learned knowledge through the enriched metadata layer.
- **P4** compiler is demoted from plan-of-record to optional escalation;
  component composition takes its slot.
- **P0** (introspection tools) is unchanged and now even higher leverage — it is
  the surface everything else merges into.

---

## Sources (retrieved 2026-08-13)

1. [How the dbt Semantic Layer works with MetricFlow](https://www.getdbt.com/blog/how-the-dbt-semantic-layer-works) — dbt Labs
2. [Semantic models](https://docs.getdbt.com/docs/build/semantic-models), [Build your metrics](https://docs.getdbt.com/docs/build/build-metrics-intro) — dbt Developer Hub
3. [Announcing open source MetricFlow](https://www.getdbt.com/blog/open-source-metricflow-governed-metrics) — dbt Labs
4. [Introducing the dbt MCP Server](https://docs.getdbt.com/blog/introducing-dbt-mcp-server) — dbt Developer Blog
5. [dbt Semantic Layer FAQs](https://docs.getdbt.com/docs/use-dbt-semantic-layer/sl-faqs) (plan requirements) — dbt Developer Hub
6. [Semantic Layer for AI Agents (2026)](https://cube.dev/articles/semantic-layer-for-ai-agents-2026) — Cube
7. [MCP server](https://docs.cube.dev/docs/integrations/mcp-server) — Cube Documentation
8. [cube-js/cube](https://github.com/cube-js/cube) — GitHub
9. [boringdata/boring-semantic-layer](https://github.com/boringdata/boring-semantic-layer) + [docs](https://boringdata.github.io/boring-semantic-layer/)
10. [malloydata/publisher](https://github.com/malloydata/publisher), [malloydata/malloyyo](https://github.com/malloydata/malloyyo) — GitHub
11. [Open Semantic Interchange](http://open-semantic-interchange.org/) + [OSI spec repository](https://github.com/open-semantic-interchange/OSI)
12. [Snowflake OSI announcement](https://www.snowflake.com/en/blog/open-semantic-interchange-ai-standard/) — Snowflake
13. [Semantic Layer Tools in 2026: Complete List + OSI (Apache Ossie) Status](https://datus.ai/blog/semantic-layer-tools-list-osi/) — Datus
14. [Cortex Analyst](https://docs.snowflake.com/en/user-guide/snowflake-cortex/cortex-analyst) + [Verified Query Repository](https://docs.snowflake.com/en/user-guide/snowflake-cortex/cortex-analyst/verified-query-repository) — Snowflake Documentation
15. [MCP Semantic Layer: Build a Governed MCP Server (2026)](https://colrows.com/blogs/mcp-semantic-layer-integration/) — Colrows
16. [dbt Semantic Layer for AI: Architecture and Trade-offs (2026)](https://infinisynapse.com/en/blog/dbt-semantic-layer-architecture) — InfiniSynapse
17. [Spider 2.0](https://spider2-sql.github.io/) + [xlang-ai/Spider2](https://github.com/xlang-ai/Spider2); leaderboard standing via [Genloop](https://genloop.ai/blogs/genloop-is-1-on-spider-2.0)
18. [A Semantic-Layer-Mediated Agent for NL2SQL over Heterogeneous Enterprise Databases](https://arxiv.org/abs/2606.31041) — arXiv 2606.31041
19. [I Gave My AI Agent a Semantic Layer Instead of Raw SQL](https://builder.aws.com/content/2nHgBx9YiFp5Dm2kUb9mpRH3foM/i-gave-my-ai-agent-a-semantic-layer-instead-of-raw-sql) — AWS Builder Center (title only — page body did not render for retrieval)

**Unverified claims carried in this doc:**

- The 94.15% figure is the arXiv paper's own claim, not reproduced.
- The 96.70% Spider 2.0-Snow figure comes from the vendor's own "we're #1" blog post.
- **MetricFlow's Apache-2.0 relicensing** (it was BSL-1.1 for most of its life) rests
  on one dbt Labs blog post plus the GitHub API license field on 2026-08-13 — this is
  the most load-bearing licence claim in the doc (it underpins "runs standalone at no
  cost" and the shelf verdict); **re-verify against the repo's LICENSE file before
  the spec relies on it.**
- "No JVM semantic-layer library exists" is a negative claim from web search, not
  proof of absence.
- The AWS case study is cited by title only (page body did not render for retrieval).
