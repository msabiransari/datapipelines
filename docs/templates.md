# Templates Specification

**Status:** v1.6 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md), [Pipeline Contract spec](pipeline-contract.md), [Configuration Reference](configuration.md), [Metadata DB spec](metadata-db.md)
**Last updated:** 2026-08-09

---

## 1. Purpose

A **Template** is a versioned, Freemarker-encoded SQL generator. Pipelines reference templates by `{id, version}` instead of inlining SQL — templates are reusable, independently versioned, editable in their own UI screen, and sharable across pipelines.

This spec defines:
- The Template entity model.
- The Freemarker integration (allowed constructs, security constraints).
- The **library macro** system for sharing SQL patterns across templates.
- Template versioning rules and immutability.
- The render lifecycle (template + context → SQL string).
- Validation rules.

---

## 2. Design Principles

1. **Templates are first-class entities, not embedded in pipelines.** Separation lets pipelines stay small, templates be reused, edits be isolated, and versioning be tracked cleanly.
2. **Templates target one dialect.** A template written for Postgres is not directly executable against Oracle — different SQL syntax, different function names, different type names. The `dialect` field declares the target.
3. **Templates are immutable per version.** Editing a template creates a new version. Pipelines reference `{id, version}` — that reference always produces the same SQL for the same context, forever.
4. **Freemarker is the template engine.** Mature, well-known, embeddable in Java/Kotlin, supports macros/import for libraries. We configure it conservatively for security (§4.3).
5. **Templates do not declare their variables — the pipeline does.** A template has no parameter schema of its own. The **pipeline's `parameters` block is the single declaration point** ([Pipeline Contract §6](pipeline-contract.md#6-parameters-input-map-declaration)), and the full parameter map (after defaults are applied) *is* the render context passed to every template the pipeline references. A template is therefore a pure function of the calling pipeline's parameters; the same template can be reused by pipelines that declare those parameters differently. Templates keep a free-text `description` so humans and agents can discover what a template expects.
6. **No arbitrary code execution.** Templates render SQL strings, not arbitrary program logic. Freemarker's dangerous constructs are disabled (§4.2) and the body is scanned at save time (§7).
7. **Nothing invalid is ever stored.** Every create/update validates fully before the row is written — the universal save-time validation principle in [Pipeline Contract §2](pipeline-contract.md#2-design-principles). For templates that means parse-level validation (§7.1); the *render*-level check belongs to pipeline save, because only a pipeline knows the parameters.

---

## 3. Template Entity

### 3.1 JSON structure

```json
{
  "schema_version": 1,
  "id": "fetch_orders.sql",
  "version": 2,
  "engine": "freemarker",
  "dialect": "POSTGRES",
  "display_name": "Fetch Orders in Date Range",
  "description": "Pulls orders between start_date and end_date (DATE), with an include_cancelled (BOOLEAN) switch. Intended for pipelines that declare those three parameters.",
  "imports": [
    {"id": "lib_date_filters.sql", "version": 1, "alias": "dates"}
  ],
  "body": "SELECT\n  order_id,\n  customer_id,\n  total_amount,\n  order_date,\n  status\nFROM orders\nWHERE <@dates.date_range column=\"order_date\" start=start_date end=end_date />\n<#if !include_cancelled>\n  AND status <> 'CANCELLED'\n</#if>",
  "created_at": "2026-08-01T10:00:00Z",
  "created_by": "user-uuid",
  "is_library": false
}
```

Note that the body contains **no `<#import>` directive**. The engine synthesizes the imports from the `imports` array at render time (§6.3); the body simply calls `<@dates.date_range .../>` using the declared alias.

### 3.2 Field reference

| Field | Type | Required | Description |
|---|---|---|---|
| `schema_version` | integer | yes | Currently `1` — the only value v1 accepts; any other is **rejected at save time** with `template.validation.schema_version_unsupported` (§7). Independent of the entity `version` below — see [Pipeline Contract §15.4](pipeline-contract.md#154-which-version-counter-governs-what). |
| `id` | string | yes | Stable identifier. `[a-z0-9_\.\-]+`. Auto-generated if omitted on create. |
| `version` | integer | yes | Monotonically increasing per template id. Server-assigned. |
| `engine` | string (enum) | optional (default `"freemarker"`) | Template engine. v1 supports only `"freemarker"`; a save carrying any other value (`"pebble"`, `"handlebars"`, `"none"`) is **rejected at save time** with `template.validation.engine_unsupported` (§7) — it is never stored and silently rendered as Freemarker. Reserved values are catalogued in [Enums §6](enums.md#6-templateengine--template-language). |
| `dialect` | string (enum) | yes | One of `POSTGRES`, `ORACLE`, `MSSQL`, `MYSQL`, `H2`, `DUCKDB`, `SQLITE`. |
| `display_name` | string | yes | Human-readable name. |
| `description` | string | yes | Free-text, for human and agent discoverability. Not machine-validated: it is the only place a template can hint at the parameters it expects, since it declares none (§2.5). |
| `imports` | array of `{id, version, alias}` | yes (may be empty) | Library templates whose macros this template calls, and the namespace alias each is bound to. See §6. Only meaningful when `engine` is `"freemarker"`. |
| `body` | string | yes | Template source. Syntax depends on `engine` (Freemarker by default). Multi-line. Must **not** contain `<#import>` or `<#include>` directives (§6.3). |
| `created_at` | ISO 8601 timestamp | yes | Server-assigned. |
| `created_by` | string (UUID) | yes | User ID of creator. |
| `is_library` | boolean | yes | `true` if this template exists to be imported by others. A library body contains **only `<#macro>` / `<#function>` definitions — no output outside macro definitions** (§6.2); `body` is still required. `false` if the template is executable directly by a pipeline node. |

**Render context.** There is no `params_schema` field. The variables a body may reference are exactly the keys of the calling pipeline's `parameters` map, defaults applied — see [Pipeline Contract §7.4](pipeline-contract.md#74-template-variable-resolution).

---

## 4. Freemarker Integration

### 4.1 Freemarker version

Pinned via Gradle version catalog (see [Module Structure spec](module-structure.md)). At time of writing, the latest stable Freemarker is `2.3.34` — actual pinning happens at implementation time against current stable. The `Configuration` incompatible-improvements version in §4.3 must be kept equal to the pinned artifact version.

### 4.2 Allowed Freemarker constructs

**Permitted:**
- `${var}` interpolation — type-aware rendering (Date → ISO 8601, BigDecimal → plain string, etc.).
- `<#if>`, `<#else>`, `<#elseif>` conditionals.
- `<#list items as item>` loops over collections.
- `<#assign var=value>` local variables.
- `<#macro name param1 param2>` macro definitions.
- `<@alias.macro_name .../>` calls into imported library namespaces (§6).
- `<#function>` definitions.
- `<#switch>`, `<#case>`.
- Built-ins: `?c` (computer-format), `?string("...")`, `?lower_case`, `?upper_case`, `?size`, `?has_content`, `?default(...)`, etc.

**The scan operates on the PARSED template, not on regex-stripped source (normative).** A scanner that strips comments and matches text with regex is bypassable — verified against the pinned Freemarker: hiding `<#--` / `-->` inside FTL string literals (`<#assign a="<#--">…<#assign b="-->">`) makes a regex comment-stripper delete a `?eval`/`<#include>` the engine still executes, and a leading `[#ftl]` switches the parser to square-bracket syntax an angle-bracket regex never sees. The body is already parsed for syntax validation (§7); the forbidden-construct scan MUST walk that same FreeMarker AST (per-element expression text via `TemplateElement.getDescription()`, plus `LibraryLoad`/`Import` nodes) so the parser and the scanner agree by construction — a payload hidden in a string literal cannot survive, because FreeMarker's canonical AST rendering escapes `<`/`>` inside literals (`"<#--"` prints as `"\l#--"`), so a literal cannot re-inject tag syntax into the scanned text. (On the pinned FreeMarker the public expression-walk API is package-private and `Environment` needs a render, which §7.1 forbids at save; the AST walk therefore takes a deliberate, `@Deprecated`-but-only-available `TemplateElement`/`_CoreAPI` dependency (the `TemplateElement` type appears in a few signatures, but the walk *mechanics* live in one file) guarded by a jar-version drift test so an upgrade fails the build rather than silently opening a hole.) The scanner additionally, **at the source level**, rejects: square-bracket tag/interpolation syntax (`[#…`, `[=…`) — the load-bearing guard against a `[#ftl]` parser switch, since pinning `tagSyntax` alone does not stop it (§4.3); and a **leading `<#ftl …>` header** — the header is consumed by the parser and produces *no AST node*, so the AST scan cannot see it, and `<#ftl attributes={…}>` evaluates expressions at **parse time on the save thread** (a measured multi-second, uninterruptible CPU burn from a tiny body, needing no forbidden built-in — the save-path DoS the length cap cannot bound). v1 disallows the header outright (§6.2); it is unnecessary for SQL bodies and is also incompatible with the synthesized import prologue. A mis-cased or malformed built-in (`?EVAL`, fullwidth `?ｅｖａｌ`) is a parse error → `template.validation.syntax_error` (still rejected, never stored). The body is **length-capped at save** (`datapipelines.templates.max-body-chars`, [Configuration §3.9](configuration.md#39-templates)) — over-cap bodies are rejected with `template.validation.syntax_error` before parsing, bounding parse cost and heap.

**Forbidden** — rejected at save time by the body scan (`template.validation.dangerous_construct`, §7):
- `?eval` — evaluates a string as a Freemarker expression. **There is no configuration switch that disables `?eval` in Freemarker 2.3.x** (verified against the `Configurable` setting list, §4.3), so the save-time scan is the *only* guard and is therefore normative, not belt-and-braces. With §4.3's class resolver and object wrapper in place, `?eval` cannot reach Java classes even if one slipped through — its blast radius is confined to expressions over the render context.
- `?interpret` and `?eval_json` — sibling constructs to `?eval` that compile a **string value** (which may be a render-context value, i.e. an API-supplied pipeline parameter) into template source and execute it. Like `?eval` they have no configuration switch, so the save-time scan is the sole guard: they are the constructs that would let a context value become source, defeating "a context value is data, never source". Rejected on the same footing as `?eval`.
- `?api` — Java API access on wrapped objects. Also disabled by configuration (§4.3).
- `?new` — instantiates arbitrary Java classes. Hard-disabled by configuration (§4.3).
- `Execute`, `ObjectConstructor`, `freemarker.template.utility.JythonRuntime` — the classic Freemarker SSTI vectors. Unreachable because class resolution is disabled entirely (§4.3).
- `<#import>` and `<#include>` **inside a body**. Imports are declarative (`imports`, §6.3) and synthesized by the engine; a literal directive in a body would bypass alias/version/`is_library` validation, so it is rejected.
- Any filesystem- or classpath-backed template loading (`freemarker.cache.FileTemplateLoader`, `ClassTemplateLoader`). Templates come only from the registry (§4.3).

### 4.3 Security configuration

Templates are authored by authenticated users but are still *untrusted input* to the render engine — configure for the hostile case. Freemarker's own FAQ (item 23, "allowing users to upload templates") is the reference; every setting below was checked against the Freemarker 2.3.x `Configurable` / `Configuration` / `TemplateClassResolver` javadoc.

```kotlin
val fmVersion = Configuration.VERSION_2_3_34   // keep equal to the pinned catalog version (§4.1)

val freemarkerConfig = Configuration(fmVersion).apply {
    // 1. Templates come only from the registry, keyed "id@version" (§6.3).
    //    No FileTemplateLoader, no ClassTemplateLoader — nothing filesystem- or classpath-backed.
    templateLoader = RegistryTemplateLoader(templateRegistry)

    // 2. No class resolution at all — kills ?new, ObjectConstructor, Execute, JythonRuntime.
    //    ALLOWS_NOTHING_RESOLVER, NOT SAFER_RESOLVER: Freemarker's own FAQ states
    //    SAFER_RESOLVER is "not restrictive enough" for untrusted templates.
    newBuiltinClassResolver = TemplateClassResolver.ALLOWS_NOTHING_RESOLVER

    // 3. No ?api. Freemarker's default is already false; set it explicitly so a
    //    future default change cannot silently open it.
    isAPIBuiltinEnabled = false

    // 4. The render context holds only canonical-typed scalars and lists (§4.4),
    //    so the wrapper never needs to expose Java members at all.
    objectWrapper = SimpleObjectWrapper(fmVersion)

    // 5. Render failures propagate as errors — never partially into the SQL string.
    templateExceptionHandler = TemplateExceptionHandler.RETHROW_HANDLER
    logTemplateExceptions = false

    // 6. Pin the tag/interpolation syntax. NOTE (verified against the pinned jar): this pin does
    //    NOT by itself stop a leading [#ftl] from switching the parser to square-bracket syntax —
    //    `[#ftl][#include "y"]` still parses to a live Include node even with ANGLE_BRACKET pinned.
    //    The pin only renders a BARE `[#include …]` inert text. So the LOAD-BEARING guard is §4.2's
    //    outright source-level refusal of `[#`/`[=`; this pin is defense in depth, not the barrier.
    tagSyntax = Configuration.ANGLE_BRACKET_TAG_SYNTAX
    interpolationSyntax = Configuration.LEGACY_INTERPOLATION_SYNTAX
}
```

**Verified (2026-08-07, against the Apache Freemarker 2.3.x javadoc and FAQ):**
- `Configurable.setNewBuiltinClassResolver(TemplateClassResolver)` exists; `TemplateClassResolver.ALLOWS_NOTHING_RESOLVER` ("doesn't allow resolving any classes"), `SAFER_RESOLVER`, and `UNRESTRICTED_RESOLVER` are the three published constants.
- `Configurable.setAPIBuiltinEnabled(boolean)` exists and is initialized to `false` by the `Configurable` constructor.
- `Configurable.setTemplateExceptionHandler(TemplateExceptionHandler)` exists; `RETHROW_HANDLER` is a published handler.
- **No setting named `builtin_classes` exists**, and **no `externalsCatchAll` property exists** on `BeansWrapperConfiguration` (its exposure knobs are `setExposureLevel(int)`, `setExposeFields(boolean)`, `setMemberAccessPolicy(MemberAccessPolicy)`). Both appeared in the v1.0 draft of this section and were invented; they are deleted.
- Freemarker "cannot enforce CPU/memory limits" (FAQ) — the render timeout below therefore cannot be a Freemarker setting.

**Implementation gate — verify before merging the engine module:**
1. Accessor forms only: confirm the Kotlin property spellings (`isAPIBuiltinEnabled`, `newBuiltinClassResolver`, `logTemplateExceptions`) resolve against the pinned artifact, and the `SimpleObjectWrapper(Version)` constructor arity. The *requirements* above are fixed; only the call spelling is subject to this check.
2. If the render context ever grows beyond canonical scalars/collections, `SimpleObjectWrapper` must be replaced by a `DefaultObjectWrapperBuilder` configured with a `WhitelistMemberAccessPolicy` — not by relaxing the resolver.
3. Re-read the Freemarker `SECURITY.md` advisories for the pinned version and add any new hardening knob it introduces.

**Render guards.** Two limits apply to every render, both configured centrally — see [Configuration §3.9](configuration.md#39-templates); this doc never restates their defaults:
- `datapipelines.templates.render-timeout-ms` — wall-clock cap on a single render. Freemarker has no timeout setting, so the render runs on a worker and a watchdog interrupts it at the cap. **The implementation-gate question is now answered against the pinned version: a plain `Thread.interrupt()` does NOT abort a Freemarker render — a `<#list 1..2000000000>` keeps running after `interrupt()`+`cancel(true)`, and merely abandoning the worker leaks one core-burning thread per runaway, without bound.** Therefore the engine MUST (a) register `freemarker.core.ThreadInterruptionSupportTemplatePostProcessor` (present in the pinned jar) so interruption actually aborts the render, and (b) run renders on a **bounded** worker pool with a rejection policy, so a leaked worker cannot accumulate. Exceeding the cap fails the node with `pipeline.node.template_render_failed`.
- Output size — a render whose accumulated output exceeds the staging batch memory budget is aborted under the same error code. Memory accounting rules are owned by [Staging §8](staging.md). Note the output **writer** cap alone does not bound heap: a template can accumulate in a `<#assign s=s+s>` variable without writing a byte, so the bounded worker + interruption above (not the writer cap) is what stops in-memory growth.

### 4.4 Type-aware interpolation

`${var}` rendering must respect the canonical Type System types:

| Canonical type in context | Rendered as |
|---|---|
| `INTEGER` | Plain integer string (`42`) |
| `BIGINTEGER` | Plain integer string (`9223372036854775807`) |
| `DECIMAL(p,s)` (exact) | Plain decimal string with declared scale (`12345.67`) |
| `DECIMAL(p)` (approximate) | Plain decimal string (`3.141592653589793`) |
| `BIGDECIMAL(p,s)` | Plain decimal string with declared scale |
| `BOOLEAN` | `true` / `false` (lowercase) |
| `STRING` | Verbatim string |
| `DATE` | ISO 8601 date (`2026-08-05`) |
| `TIME` | ISO 8601 time (`14:30:00`) |
| `TIMESTAMP` | ISO 8601 datetime, UTC, with `Z` (`2026-08-05T14:30:00Z`) |
| `BINARY` | Base64-encoded string |

For SQL contexts requiring quoted values, the template author wraps the interpolation in the dialect's quoting: `'${start_date}'` (single quotes for date literals in standard SQL).

### 4.5 Binding declared parameters (v1.6, 042)

**A parameter DECLARED in the pipeline's `parameters` block is a value, and is referenced in
SQL as a bind parameter: `:start_date`.** The executor translates every `:name` into a
positional parameter on a prepared statement (spring-jdbc's `NamedParameterUtils`, pinned) and
binds the coerced value — `DATE` as `LocalDate`, `BIGDECIMAL` as `BigDecimal`, and so on, with
no quoting syntax around it. A `STRING` value is therefore never parsed as SQL: a payload of
`x' OR '1'='1` matches no row, and a `; DROP TABLE …` payload is one inert scalar. The
execution semantics — including the loud failure for a `:name` no parameter declares — are in
§8.4.

Pipeline save enforces the form: a template that interpolates a declared parameter inside
`${}` is refused with `template.validation.parameter_interpolated` (Pipeline Contract §13.9),
and the message names both the `${name}` written and the `:name` form to write (§7.2).

**What stays the author's responsibility.** `${}` interpolation is for **structure** — table
names, dynamic `IN` lists, `ORDER BY` fragments — and remains exactly as trusting as it always
was: interpolate only what the author controls, never a declared parameter (which the save-time
rule now refuses). Freemarker's `${}` does NOT escape SQL, and there is deliberately **no
`?sql_escape` built-in**: for values binding made it unnecessary, and for structure an escape
built-in would invite the belief that interpolation is now safe.

---

## 5. Template Versioning

### 5.1 Rules

- Versions are integers, monotonically increasing per template id.
- Each version is immutable — `body`, `imports`, `dialect`, `engine` cannot be changed once stored.
- Deleting a template (soft delete) does not affect existing versions. Pipelines referencing deleted templates' versions continue to work until those pipelines are explicitly modified.
- Versions never reused, never renumbered.

### 5.2 Lifecycle

```
Create template        → version 1
Update template body   → version 2 (1 is preserved, immutable)
Update again           → version 3
Soft-delete            → version 3 marked deleted; pipelines referencing v1/v2/v3 still work
```

### 5.3 What "update" means

`PUT /templates/{id}` creates a new version. The request body contains the new `body`, `imports`, etc. The server:
1. Runs the full §7 validation set — Freemarker **parse only**, forbidden-construct scan, and import-graph resolution. No render is attempted: a template does not know its callers' parameters, so there is no context to render against (§7.1).
2. Stores as a new version (current version + 1).
3. Does NOT modify or remove previous versions.

Existing pipelines are unaffected: they pin `{id, version}`, so a new version is invisible to them until a pipeline is edited to reference it — at which point that pipeline's save re-runs the dry-render check ([Pipeline Contract §12.6](pipeline-contract.md#126-template-validations)).

---

## 6. Library Templates

### 6.1 Motivation

Companies have common SQL patterns: date-range filters, status filters, common JOIN shapes, aggregate templates. Duplicating these across pipeline templates is error-prone and unmaintainable.

Library templates solve this. A library template defines `<#macro>`s that other templates import and call.

### 6.2 Example library template

```ftl
<#-- lib_date_filters.sql version 1 — is_library: true -->
<#macro date_range column start end>
  ${column} BETWEEN '${start}' AND '${end}'
</#macro>

<#macro recent_only column days=30>
  ${column} >= CURRENT_DATE - INTERVAL '${days}' DAY
</#macro>
```

Marked `is_library: true`. The `body` field is still **required** — it is where the macros live. What a library must not have is **output outside macro definitions**: everything at the top level is `<#macro>` / `<#function>` / comments. A library that emits text of its own would inject that text into every importer. A leading `<#ftl …>` header is **not permitted** (§4.2) — it is a save-path DoS surface and is incompatible with the synthesized import prologue (which must precede it), so v1 disallows it for templates and libraries alike.

### 6.3 Importing in a regular template

A template declares its libraries in the `imports` array. Each entry binds one library version to one namespace alias:

```json
"imports": [
  {"id": "lib_date_filters.sql", "version": 1, "alias": "dates"}
]
```

The body then calls the macros through the alias — and contains **no import directive**:

```ftl
SELECT order_id, customer_id, total_amount
FROM orders
WHERE <@dates.date_range column="order_date" start=start_date end=end_date />
  AND status = 'ACTIVE'
```

The render engine:
1. Resolves each `imports` entry to a stored library version and registers it with the template loader under the key `"{id}@{version}"`.
2. **Synthesizes** the equivalent `<#import "{id}@{version}" as {alias}>` prologue from the array — the author never writes it, so an alias can never point at an unvalidated, unpinned, or non-library template. Because `alias` and `id` are interpolated into that synthesized directive, they are validated as strict identifiers *before* synthesis: `alias` matches `[a-zA-Z_][a-zA-Z0-9_]*` and `id` matches the §7 `id` rule; `version` is a positive integer. A value that fails — an alias or id carrying Freemarker metacharacters, whitespace, or a directive fragment, or a non-positive version — is a prologue-injection attempt and is rejected with `template.validation.dangerous_construct` (§4.2/§7); the refusal message never echoes the offending value back into logs. The loader independently re-checks the key on the read path, failing closed on any row that reached storage by another route.
3. Resolves transitive imports the same way (a library's own `imports` array), building the full closure.
4. Renders the main body against the pipeline's parameter map.

Because the loader only ever resolves `"{id}@{version}"` keys against the registry, there is no template name a body could reference to escape the registry — which is why §4.2 forbids literal `<#import>`/`<#include>`.

### 6.4 Import resolution rules

- Imports are resolved by `{id, version}` from the template registry — exact version, no ranges, no "latest".
- Version pinning is explicit — bumping a library to v2 does NOT silently change templates that imported v1. Authors must update the importing template to use v2 (which creates a new version of the importer).
- An imported template must exist at that exact version (`template.validation.import_not_found`) and must have `is_library: true` (`template.validation.import_not_library`).
- Aliases must be unique within a template's `imports` array (`template.validation.duplicate_alias`). Two libraries may share macro names as long as their aliases differ — that is what namespacing is for.
- Imports are transitive: library A can import library B. Transitive depth is capped at **10** (`template.validation.import_depth_exceeded`). The cap is a fixed constant, not a config key.
- Cycles forbidden: A imports B, B imports A → `template.validation.import_cycle`.

### 6.5 Library promotion across environments

Libraries are promoted like regular templates — export bundle includes them. Importing pipelines fail validation in the target env if a referenced library version is missing (`pipeline.import.missing_template`).

---

## 7. Validation Rules

All checks below run at template create/update time, before anything is written (D2 — [Pipeline Contract §2](pipeline-contract.md#2-design-principles)). The canonical HTTP status and wording for each code live in the central catalog, [Pipeline Contract §13.9](pipeline-contract.md#139-template).

| Code | Check |
|---|---|
| `template.validation.dialect_invalid` | `dialect` is in the allowed enum |
| `template.validation.engine_unsupported` | `engine` is a value v1 supports (only `"freemarker"`) |
| `template.validation.schema_version_unsupported` | `schema_version` is a value v1 supports (only `1`) |
| `template.validation.id_invalid` | `id` matches `[a-z0-9_\.\-]+`, length 1–100 |
| `template.validation.syntax_error` | Freemarker parses the body without syntax errors |
| `template.validation.dangerous_construct` | Body uses a forbidden Freemarker construct — including a literal `<#import>`/`<#include>` (see §4.2) |
| `template.validation.duplicate_alias` | No two `imports` entries share an `alias` |
| `template.validation.import_not_found` | Every `imports` entry resolves to an existing template at that exact version |
| `template.validation.import_not_library` | Every imported template has `is_library: true` |
| `template.validation.import_cycle` | Import graph is acyclic |
| `template.validation.import_depth_exceeded` | Transitive import depth ≤ 10 |
| `template.validation.is_library_without_macros` | `is_library: true` requires at least one `<#macro>` **or** `<#function>` definition and no output outside those definitions (FreeMarker represents both as one node type; a `<#function>`-only library is legal) |

### 7.1 Save-time validation is parse-only

- **At template save** — everything in the table above: Freemarker syntax validity, the forbidden-construct scan, and full import-graph resolution (existence, `is_library`, alias uniqueness, depth, cycles). **No render is performed.** A template declares no parameters (§2.5), so at save time there is no context to render against and no basis for a synthetic one. There is deliberately no "sample context" anywhere in this contract.
- **At pipeline save** — the render-level check. Pipeline validation dry-renders every template its nodes reference against the pipeline's declared `parameters` (defaults where present, type-appropriate sample values otherwise). See §7.2.
- **At execution** — nothing new is checked. Both gates above have already run; a render failure at execution time is a bug or an environment drift, and surfaces as `pipeline.node.template_render_failed` (§8.2).

### 7.2 The dry-render rule (owned by pipeline validation)

The rule that catches template/pipeline drift lives on the **pipeline** side, because only a pipeline knows the parameters:

**At pipeline save, for every template referenced by every node, the template (plus its resolved import closure) must render successfully against the pipeline's declared `parameters`.** Any Freemarker variable the body references that has no corresponding pipeline parameter is an undefined-variable render failure, and validation fails with `pipeline.validation.template_parameter_undeclared`.

Normative definition: [Pipeline Contract §7.4](pipeline-contract.md#74-template-variable-resolution) and [§12.6](pipeline-contract.md#126-template-validations). This catches typos and template-pipeline drift at write time, not at execution time.

**A declared parameter referenced inside `${}` is refused (042 B2).** Pipeline save also scans
each referenced template's parse tree: a declared parameter name found inside a `${}`
interpolation fails with `template.validation.parameter_interpolated` (HTTP 400, Pipeline
Contract §13.9) — a declared parameter is a value and must be referenced as `:name` (§4.5),
and the message names both forms. The scan is AST-based for the same evasion-proofing reason
as §4.2's construct scan, and honours macro-parameter and loop-variable shadowing; a backslash
"escape" is pinned to be a live interpolation in 2.3.34, so no spelling hides one.

A consequence worth stating plainly: a template that is perfectly valid on its own can still be un-referenceable by a given pipeline. That is intended — the template is reusable, and each pipeline proves for itself that it supplies what the template needs.

---

## 8. Render Lifecycle

### 8.1 Single-template render

```
Input: TemplateRef{id, version}, Context{the pipeline's full parameter map, defaults applied}
Process:
  1. Look up template body + imports from registry by {id, version}
  2. Resolve the import closure transitively; register each library
     with the loader under "{id}@{version}"
  3. Synthesize the <#import ... as alias> prologue from the imports array
  4. Render body + prologue via Freemarker, under the §4.3 configuration
     and the §4.4 render guards (timeout, output size)
  5. Return SQL string
Output: SQL string
```

There is no per-template type-check step: the context is the pipeline's parameter map, already validated and coerced against the pipeline's own `parameters` declarations ([Pipeline Contract §6.3](pipeline-contract.md#63-wire-encoding-of-input-parameter-values)) before any render begins.

### 8.2 Error behavior

- Template `{id, version}` not found → `pipeline.node.template_not_found` (should not happen — write-time validation should have caught this).
- Variable in body not in context → `pipeline.node.template_render_failed` (details include the variable name and template id+version). Should not happen either: the pipeline's dry-render (§7.2) covers it.
- Render exceeds `render-timeout-ms` or the output size cap → `pipeline.node.template_render_failed` (details name which guard tripped).
- Forbidden construct somehow present (shouldn't be — caught at save time) → `pipeline.node.template_render_failed`.

### 8.3 Performance and caching

- Parsed templates are cached in memory after first render. Cache capacity is `datapipelines.templates.cache-size` — see [Configuration §3.9](configuration.md#39-templates). This doc does not restate the default.
- Cache key: `{template_id, version, resolved import closure versions}`.
- Cache invalidated on template update by construction: a new version is a new cache key; the old entry remains valid for in-flight executions, which is exactly the immutability guarantee (§5.1).
- Render itself is fast (<10ms typical for templates up to ~5KB body). The `render-timeout-ms` guard (§4.3) exists for pathological bodies, not for the normal path.

### 8.4 Named-parameter binding at execution (v1.6, 042)

The rendered SQL may carry `:name` references. The executor translates them **before the
driver sees anything** — spring-jdbc 6.2.19's `NamedParameterUtils`, pinned and standalone, not
a hand-rolled SQL scanner — and binds the coerced context values on a prepared statement. Two
properties define the contract:

- **Missing names fail loudly.** A `:name` the execution context does not declare fails with
  `pipeline.node.sql_parameter_missing` (HTTP 500, Pipeline Contract §13.4) **before anything
  executes** — never a silently-null predicate, which would return wrong data instead of an
  error.
- **Values bind as values.** `DATE` binds as `LocalDate`, `BIGDECIMAL` as `BigDecimal`, and so
  on — no quoting syntax is used around a bound reference. `DATE ':start_date'` is wrong in
  the same way `'${start_date}'` now is.

**What the translator does with dialect syntax** (pinned by `NamedParameterTranslationTest`
against the pinned jar, cited rather than re-derived):

- Postgres/H2/DuckDB/SQLite `::` casts survive untouched — `col::text` and `:param` coexist
  in one statement. The house habit of writing `CAST(:x AS …)` is belt-and-braces, not a
  requirement.
- `'…'` literals, `"…"` identifiers, MySQL `` `…` `` backtick identifiers, and `--` /
  `/* … */` comments are skipped correctly.
- Four constructs are **not** understood, and a colon inside them is mis-read as a parameter:
  MySQL `#` comments, MSSQL `[a:b]` identifiers, Oracle `q'[…]'` strings, and PostgreSQL
  `$$…$$` dollar-quoting. Such a template fails loudly (`sql_parameter_missing`, naming the
  mis-read name) when that name has no value, and is visibly mangled in `templates_render`
  when it does. The fix is the author's: rephrase the construct. Values themselves never
  re-enter the parser, so the injection property §4.5 exists for is unaffected by these.

---

## 9. CRUD Operations (Brief — full HTTP in REST API spec)

| Operation | Method & Path |
|---|---|
| Create template | `POST /templates` |
| Get latest version | `GET /templates/{id}` |
| Get specific version | `GET /templates/{id}/versions/{version}` |
| Update (creates new version) | `PUT /templates/{id}` |
| List | `GET /templates?dialect={d}&q={search}` |
| Delete (soft) | `DELETE /templates/{id}` |
| List versions | `GET /templates/{id}/versions` |
| Preview render with a caller-supplied context | `POST /templates/{id}/versions/{version}/render` |
| Import library bundle | `POST /templates/import` |

The `/render` endpoint is an **editor preview affordance only** — the caller explicitly supplies the variable map to render against. It is not a validation step and it never runs implicitly: template save is parse-only (§7.1), and the authoritative render check is the pipeline dry-render (§7.2).

---

## 10. UI Editor Integration

The UI template editor (in `web` module) provides:

- **Syntax highlighting** for Freemarker + SQL.
- **Live preview** via the `/render` endpoint — the author types a variable map by hand and sees the rendered SQL. Purely an authoring aid (§9).
- **Validation feedback** — error markers for parse errors, forbidden constructs, and import-resolution failures (the §7 checks). Undefined-variable feedback is *not* available here; it appears when the template is wired into a pipeline (§7.2).
- **Library browser** — pick library templates to import from a searchable list; picking one appends an `{id, version, alias}` entry to `imports` (the editor never writes `<#import>` into the body).
- **Version history** — view diff between versions, restore old version (creates new version with old body).
- **Test render against a scratch tempdb** (optional) — available for templates whose `dialect` matches the dialect of the tempdb engine. The engine is declared per pipeline in `settings.tempdb.engine` (H2 in v1; DuckDB templates become testable this way when that engine lands) — see [Pipeline Contract §12.6](pipeline-contract.md#126-template-validations). The editor does not hardcode H2.

---

## 11. Stability Promise

The Template entity is **versioned, additive-only**.

### 11.1 Frozen in v1

- The Template JSON shape (top-level fields, `imports` entry shape `{id, version, alias}`).
- The rule that templates declare no parameters of their own — the pipeline's `parameters` is the single declaration point.
- The Freemarker integration contract (allowed/forbidden constructs).
- The `is_library` convention.
- The import resolution rules, including body-never-imports.
- The version-immutability guarantee.

### 11.2 Not frozen

- New optional fields (`tags`, `metadata`, etc.) may be added non-breakingly.
- The render cache and timeout configuration are deployment-specific ([Configuration §3.9](configuration.md#39-templates)).
- Future template languages beyond Freemarker are out of scope but possible (would be a new `engine` value).

---

## 12. Implementation Notes

### 12.1 Where this lives

`templates` Gradle module:

- `co.datapipelines.templates.Template` data class
- `co.datapipelines.templates.TemplateVersion` data class
- `co.datapipelines.templates.TemplateImport` data class (`id`, `version`, `alias`)
- `co.datapipelines.templates.TemplateRegistry` — lookup by id+version, caching
- `co.datapipelines.templates.RegistryTemplateLoader` — the Freemarker `TemplateLoader` of §4.3; resolves only `"{id}@{version}"` keys against the registry
- `co.datapipelines.templates.TemplateEngine` — wraps Freemarker, exposes `render(ref, context): String`, owns the §4.3 render guards
- `co.datapipelines.templates.TemplateValidator` — runs §7 checks
- `co.datapipelines.templates.LibraryResolver` — resolves transitive imports, enforces depth/cycle/alias rules

The module's public API carries no parameter-schema types.

### 12.2 Persistence

`templates` and `template_versions` live in the app's metadata DB. **The DDL is defined once, in [Metadata DB §4](metadata-db.md) — that spec is the sole DDL authority (D4) and this section deliberately carries no `CREATE TABLE` block.**

What metadata-db must express for this spec to hold:
- `template_versions` carries `engine` and `is_library` alongside `dialect`, `imports` (JSONB), and `body`.
- There is **no `params_schema` column** on either table.
- `imports` JSONB is an array of `{id, version, alias}` (§3.2).
- `(template_id, version)` is the primary key; versions are never updated in place (§5.1).

### 12.3 Testing

- Unit tests for `TemplateValidator` covering every check in §7, including the negative cases: duplicate alias, import of a non-library template, depth 11, and a two-node cycle.
- Round-trip tests: every sample template in `docs/examples/` must parse, validate, and resolve its import closure. Render assertions belong to the pipeline fixtures that supply the parameters.
- Forbidden-construct tests: every Freemarker SSTI vector (from public security advisories) must be rejected at save time, and a literal `<#import>` in a body must be rejected.
- Hardening tests: with the §4.3 configuration, `?new`, `?api`, `Execute`, and `ObjectConstructor` must all fail at render even when the save-time scan is bypassed — the two layers are tested independently.
- Library import tests: transitive imports, depth limits, cycle detection, missing library handling, alias namespacing of same-named macros in two libraries.
- Render-guard tests: a runaway `<#list>` trips `render-timeout-ms`; an oversized output trips the size cap.
- Performance tests: render latency for templates up to a 50KB body with a wide import fan-out at the maximum transitive depth (§6.4 caps depth at **10** — an earlier "20 imports deep" here contradicted that cap; read it as ≤10 deep, up to ~20 imports wide). Also: a save-time adversarial-input suite — a body at `max-body-chars`, deeply-nested parens/`<#if>`, and a wide import DAG — must complete within a bounded time (guards the §4.2 AST scan and §6.4 traversal against the quadratic/exponential blowups a regex scanner and an unmemoized walk exhibit).

---

## 13. Open Questions / Future Additions

Out of scope for v1:

- **Alternative engines**: support Pebble, Handlebars, or Thymeleaf for SQL alongside Freemarker. New `engine` value on templates. Not v1.
- ~~**Parameterized SQL output**: instead of rendering to a single SQL string, render to `{sql, params}` for prepared-statement execution. Closes the SQL-injection gap. v1.1 candidate.~~ **Shipped as bound `:name` parameters (2026-09-01, 042)** — see §4.5: the render stays a single SQL string, but declared parameters are written as `:name` and the executor binds them on prepared statements, so the `{sql, params}` framing was never needed.
- **Multi-dialect templates**: a single template with dialect-conditional sections (`<#if dialect == "ORACLE">...<#else>...</#if>`). v2 candidate.
- **Template testing framework**: declarative test cases per template (`given this context, render should match this expected SQL`). Useful for regression testing.
- **Template composition visualizer**: UI to show how imports resolve into the final rendered SQL.

---

## Appendix A: Worked Example

### Library: `lib_aggregate.sql` v1 (`is_library: true`)

```ftl
<#macro sum_by group_by_column value_column table>
  SELECT
    ${group_by_column} AS group_key,
    SUM(${value_column}) AS total
  FROM ${table}
  GROUP BY ${group_by_column}
</#macro>

<#macro count_by group_by_column table>
  SELECT
    ${group_by_column} AS group_key,
    COUNT(*) AS record_count
  FROM ${table}
  GROUP BY ${group_by_column}
</#macro>
```

Nothing outside the macro definitions — the library emits no output of its own (§6.2).

### Pipeline template: `monthly_revenue.sql` v3 (`is_library: false`)

Its `imports` array:

```json
"imports": [
  {"id": "lib_aggregate.sql", "version": 1, "alias": "agg"}
]
```

Its `body` — no `<#import>` directive; the engine synthesizes `<#import "lib_aggregate.sql@1" as agg>`:

```ftl
WITH revenue AS (
  <@agg.sum_by
     group_by_column="customer_id"
     value_column="total_amount"
     table="stg_orders" />
)
SELECT
  c.customer_name,
  r.total AS revenue,
  r.total / 100.0 AS revenue_display
FROM revenue r
JOIN stg_customers c ON r.group_key = c.customer_id
WHERE r.total >= ${min_total}
ORDER BY r.total DESC
```

(Plain `${min_total}` — not `${min_total?c}`. §4.4's type-aware rendering already emits a `BIGDECIMAL(12,2)` as the plain, computer-safe decimal string `1000.00` with its declared scale; `?c` is unnecessary here and actively wrong — every published Freemarker `CFormat` drops trailing zeros, so `?c` would render `1000` and lose the declared scale §4.4 promises. Reserve `?c` for cases where you explicitly want scale-less computer format.)

### Render context

The template declares nothing. The context is the calling **pipeline's** parameter map, which must therefore declare `min_total`:

```json
"parameters": {
  "min_total": {
    "type": "BIGDECIMAL", "required": false, "default": "1000.00",
    "precision": 12, "scale": 2
  }
}
```

At pipeline save, the dry-render (§7.2) renders this template against `{min_total: BigDecimal("1000.00")}` and succeeds. Had the pipeline omitted `min_total`, the save would fail with `pipeline.validation.template_parameter_undeclared`.

### Rendered SQL

```sql
WITH revenue AS (
  SELECT
    customer_id AS group_key,
    SUM(total_amount) AS total
  FROM stg_orders
  GROUP BY customer_id
)
SELECT
  c.customer_name,
  r.total AS revenue,
  r.total / 100.0 AS revenue_display
FROM revenue r
JOIN stg_customers c ON r.group_key = c.customer_id
WHERE r.total >= 1000.00
ORDER BY r.total DESC
```

---

## Appendix B: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-01 | v1.7 | 042 implementation | New §4.5: declared parameters are values and bind as `:name` — the old §4.4 injection paragraph is replaced by the split of responsibilities it states. §7.2: pipeline save refuses a declared parameter inside `${}` with `template.validation.parameter_interpolated` (AST scan, scope-aware, no escape spelling per the 2.3.34 pin). New §8.4: execution-time binding semantics — loud `pipeline.node.sql_parameter_missing` for an undeclared `:name`, and the dialect-construct translation table pinned by `NamedParameterTranslationTest` (`::` casts survive; MySQL `#` comments, MSSQL `[a:b]`, Oracle `q'…'`, PG `$$…$$` mis-parse). §13: the "parameterized SQL output" v1.1 candidate is struck as shipped. |
| 2026-08-05 | v1.0 | initial draft | Initial templates spec: entity, Freemarker config (security-hardened), library macros, versioning, validation |
| 2026-08-05 | v1.1 | propagation | Added `engine` field to Template entity (default `"freemarker"`; future-proofing for Pebble/Handlebars/raw-SQL). Updated `body` and `imports` field descriptions to be engine-aware. Renamed `__staging__` → `tempdb` in UI editor section. |
| 2026-08-07 | v1.2 | consistency campaign | Per [SPEC-REVIEW-2026-08 §2.8](SPEC-REVIEW-2026-08.md#28-templatesmd): removed `params_schema` entirely (D3 — pipeline `parameters` is the single declaration point; template save is parse-only, dry-render moved to pipeline save); `imports` entries become `{id, version, alias}` with engine-synthesized `<#import>` and body-never-imports (D12); `is_library` corrected to "no output outside macro definitions", `body` required (D12); `template.import.cycle_detected` → `template.validation.import_cycle`, `body_malformed` → `syntax_error`, added `duplicate_alias` / `import_not_library` (D5); §4.3 Freemarker hardening rewritten against the verified 2.3.x API (invented `externalsCatchAll` / `builtin_classes` deleted; `ALLOWS_NOTHING_RESOLVER` replaces `SAFER_RESOLVER`) with an implementation-gate checklist; render guards reference [Configuration §3.9](configuration.md#39-templates) (D8); §12.2 DDL replaced by a pointer to [Metadata DB](metadata-db.md) (D4); §10 tempdb dialect derived from `settings.tempdb.engine`; fixed the §3.2 link to [Pipeline Contract §6](pipeline-contract.md#6-parameters-input-map-declaration). |
| 2026-08-10 | v1.6 | P3 build (Gate C security re-review, TPL-SEC-10) | **A leading `<#ftl …>` header is now disallowed** (§4.2 source-level refusal + §6.2) — it produces no AST node so the scan is blind to it, and `<#ftl attributes={…}>` evaluates expressions at parse time on the save thread (a measured multi-second uninterruptible CPU burn from a ~65-byte body, needing no forbidden built-in — a save-path DoS the length cap can't bound); it is also incompatible with the synthesized import prologue (which must precede it). This reverses the v1.4 "optional leading `<#ftl>` header allowed" allowance, which was the mistake. §4.2 deprecated-API wording softened ("confined to one file" → the type appears in a few signatures, the walk mechanics are in one file). rest-api §8.1/§8.4 examples add the required `display_name`. |
| 2026-08-10 | v1.5 | P3 build (Gate C fix cycle findings) | Doc-sync of what the AST-scan implementation proved against the pinned jar: §4.2 records that a string literal cannot re-inject tag syntax (FreeMarker escapes `<`/`>` in literals → the comment-strip bypass is impossible by construction) and that the scan takes a deliberate `@Deprecated`-but-only-available `TemplateElement`/`_CoreAPI` dependency (public expression-walk is package-private; `Environment` needs a render §7.1 forbids), drift-guarded; §4.3 corrected — pinning `tagSyntax` does NOT stop a leading `[#ftl]` (verified), so §4.2's outright `[#`/`[=` refusal is the load-bearing guard, the pin is defense in depth; §7 `is_library_without_macros` accepts a `<#function>`-only library (§6.2 reading; one FreeMarker node type); a mis-cased/malformed built-in is a `syntax_error` not `dangerous_construct` (parse error). |
| 2026-08-09 | v1.4 | P3 build (Gate C: 1 CRITICAL + 3 HIGH) | §4.2: forbidden-construct scan made **AST-based, not regex-over-stripped-source** — a comment-strip regex is bypassable by `<#--`/`-->` hidden in FTL string literals and by a leading `[#ftl]` square-bracket switch (both verified against the pinned jar; the CRITICAL that let `?eval`/`?interpret`/`<#include>` through the sole normative save-time gate); scanner also rejects `[#`/`[=` and the body is length-capped (`datapipelines.templates.max-body-chars`, Configuration §3.9). §4.3: `tagSyntax`/`interpolationSyntax` pinned; render-guard gate answered — plain `Thread.interrupt()` does NOT abort a Freemarker render, so `ThreadInterruptionSupportTemplatePostProcessor` + a bounded worker pool are required (abandonment alone leaks a core-burning thread per runaway; the writer cap does not bound `<#assign s=s+s>` heap). §6.2: optional leading `<#ftl>` header allowed. §12.3: "20 imports deep" corrected to ≤10 (contradicted the §6.4 cap) + adversarial-input timing suite. rest-api §8.4: template `dialect` may change across versions (existing pipelines pin a version). |
| 2026-08-09 | v1.3 | P3 build (Gate B) | `?interpret`/`?eval_json` added to §4.2's forbidden list (context-value-to-source siblings of `?eval`, no config switch). §3.2 `engine` and `schema_version` now rejected at save when unsupported — new codes `template.validation.engine_unsupported` / `template.validation.schema_version_unsupported` (§7, [Pipeline Contract §13.9](pipeline-contract.md#139-template)) close the silent-mis-render gap. §6.3: `imports` `alias`/`id`/`version` validated as strict identifiers before prologue synthesis (prologue-injection attempt → `dangerous_construct`, message never echoes the value; loader re-checks fail-closed). Appendix A worked example corrected `${min_total?c}` → `${min_total}` (`?c` drops the declared scale §4.4 promises). |
