# Templates Specification

**Status:** v1 (frozen contract — additive-only changes after this point)
**Owner:** datapipelines.co core
**Depends on:** [Type System spec](type-system.md), [Pipeline Contract spec](pipeline-contract.md)
**Last updated:** 2026-08-05

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
4. **Freemarker is the template engine.** Mature, well-known, embeddable in Java/Kotlin, supports macros/import/include for libraries. We configure it conservatively for security.
5. **Variables are declared upfront.** Templates declare their input variables in `params_schema`. The pipeline validates that every variable referenced in the body is declared (either in `params_schema` or supplied by the pipeline's `parameters`).
6. **No arbitrary code execution.** Templates render SQL strings, not arbitrary program logic. Freemarker's dangerous constructs are disabled.

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
  "description": "Pulls orders with optional filter on cancellation status.",
  "params_schema": {
    "start_date": {
      "type": "DATE",
      "description": "Inclusive start date."
    },
    "end_date": {
      "type": "DATE",
      "description": "Inclusive end date."
    },
    "include_cancelled": {
      "type": "BOOLEAN",
      "description": "Whether to include cancelled orders.",
      "default": false
    }
  },
  "imports": [
    {"id": "lib_date_filters.sql", "version": 1}
  ],
  "body": "SELECT\n  order_id,\n  customer_id,\n  total_amount,\n  order_date,\n  status\nFROM orders\nWHERE order_date BETWEEN '${start_date}' AND '${end_date}'\n<#if !include_cancelled>\n  AND status <> 'CANCELLED'\n</#if>\n<@lib.date_range_status />",
  "created_at": "2026-08-01T10:00:00Z",
  "created_by": "user-uuid",
  "is_library": false
}
```

### 3.2 Field reference

| Field | Type | Required | Description |
|---|---|---|---|
| `schema_version` | integer | yes | Currently `1`. |
| `id` | string | yes | Stable identifier. `[a-z0-9_\.\-]+`. Auto-generated if omitted on create. |
| `version` | integer | yes | Monotonically increasing per template id. Server-assigned. |
| `engine` | string (enum) | optional (default `"freemarker"`) | Template engine. v1 supports only `"freemarker"`. Reserved for future: `"pebble"`, `"handlebars"`, `"none"` (raw SQL, no template processing). See [Enums §6](enums.md#6-templateengine--template-language). |
| `dialect` | string (enum) | yes | One of `POSTGRES`, `ORACLE`, `MSSQL`, `MYSQL`, `H2`, `DUCKDB`, `SQLITE`. |
| `display_name` | string | yes | Human-readable name. |
| `description` | string | yes | Long-form description. |
| `params_schema` | object | yes (may be empty) | Parameter declarations. Same shape as [Pipeline Contract §5](pipeline-contract.md#5-parameters-input-map-declaration). |
| `imports` | array of `{id, version}` | yes (may be empty) | Library templates whose macros this template uses. See §6. Only meaningful when `engine` is `"freemarker"`. |
| `body` | string | yes | Template source. Syntax depends on `engine` (Freemarker by default). Multi-line. |
| `created_at` | ISO 8601 timestamp | yes | Server-assigned. |
| `created_by` | string (UUID) | yes | User ID of creator. |
| `is_library` | boolean | yes | `true` if this template exists to be imported by others (typically defines `<#macro>`s, no main body). `false` if it's executable directly. |

---

## 4. Freemarker Integration

### 4.1 Freemarker version

Pinned via Gradle version catalog (see [Module Structure spec](module-structure.md)). At time of writing, the latest stable Freemarker is `2.3.34` — actual pinning happens at implementation time against current stable.

### 4.2 Allowed Freemarker constructs

**Permitted:**
- `${var}` interpolation — type-aware rendering (Date → ISO 8601, BigDecimal → plain string, etc.).
- `<#if>`, `<#else>`, `<#elseif>` conditionals.
- `<#list items as item>` loops over collections.
- `<#assign var=value>` local variables.
- `<#macro name param1 param2>` macro definitions.
- `<#import "..." as alias>` and `<#include "...">` for libraries.
- `<#function>` definitions.
- `<#switch>`, `<#case>`.
- Built-ins: `?c` (computer-format), `?string("...")`, `?lower_case`, `?upper_case`, `?size`, `?has_content`, `?default(...)`, etc.

**Forbidden** (disabled via Freemarker config):
- `?eval`, `?api` — arbitrary expression evaluation, Java API access.
- `?new` — instantiates arbitrary Java classes. **Hard-disabled.**
- `Execute`, `freemarker.template.utility.JythonRuntime`, `ObjectConstructor` — classic Freemarker SSTI vectors. Removed from the configuration's allowed classes.
- `freemarker.cache.FileTemplateLoader` — we don't load templates from filesystem; only from in-memory strings / DB.

### 4.3 Security configuration

```kotlin
val freemarkerConfig = Configuration(Configuration.VERSION_2_3_34).apply {
    templateLoader = StringTemplateLoader()        // bodies come from DB
    objectWrapper = DefaultObjectWrapperBuilder(Configuration.VERSION_2_3_34)
        .build().apply {
            externalsCatchAll = false
        }

    // Disable dangerous built-ins
    setSetting("builtin_classes", "")              // no ObjectConstructor, Execute, etc.
    setSetting("new_builtin_class_resolver", TemplateClassResolver.SAFER_RESOLVER)

    // No Jython/Groovy/scripting
    setSetting("template_exception_handler", TemplateExceptionHandler.RETHROW_HANDLER)
}
```

> **Verification needed:** Confirm the exact Freemarker config keys for the version pinned. Freemarker's hardening knobs evolve; cite the docs for the pinned version.

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

**SQL injection prevention is the template author's responsibility, not the engine's.** Freemarker's `${}` does NOT escape SQL; if a parameter is user-controlled and used in a context where SQL injection is possible, the author must use parameterized queries via the source's prepared statement mechanism — but currently, templates emit raw SQL strings. This is a known trade-off for the template-driven model; pipeline authors are trusted with template authoring, and runtime context values come from the API caller (auth'd). Future v1.1 may add `?sql_escape` built-in for paranoid mode.

---

## 5. Template Versioning

### 5.1 Rules

- Versions are integers, monotonically increasing per template id.
- Each version is immutable — body, params_schema, dialect cannot be changed once stored.
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

`PUT /templates/{id}` creates a new version. The request body contains new `body`, `params_schema`, etc. The server:
1. Validates the new body against Freemarker (parse + render with sample context).
2. Stores as a new version (current version + 1).
3. Does NOT modify or remove previous versions.

---

## 6. Library Templates

### 6.1 Motivation

Companies have common SQL patterns: date-range filters, status filters, common JOIN shapes, aggregate templates. Duplicating these across pipeline templates is error-prone and unmaintainable.

Library templates solve this. A library template defines `<#macro>`s that other templates import and call.

### 6.2 Example library template

```ftl
<#-- lib_date_filters.sql version 1 -->
<#macro date_range column start end>
  ${column} BETWEEN '${start}' AND '${end}'
</#macro>

<#macro recent_only column days=30>
  ${column} >= CURRENT_DATE - INTERVAL '${days}' DAY
</#macro>
```

Marked `is_library: true`. Has no main body — only macros.

### 6.3 Importing in a regular template

```ftl
<#import "lib_date_filters.sql" as lib>

SELECT order_id, customer_id, total_amount
FROM orders
WHERE <@lib.date_range column="order_date" start=start_date end=end_date />
  AND status = 'ACTIVE'
```

The template's `imports` field declares which library templates it uses:

```json
"imports": [
  {"id": "lib_date_filters.sql", "version": 1}
]
```

The render engine:
1. Loads each imported library template (by id+version, from registry).
2. Builds a combined Freemarker namespace.
3. Renders the main template body.

### 6.4 Import resolution rules

- Imports are resolved by `{id, version}` from the template registry.
- Version pinning is explicit — bumping a library to v2 does NOT silently change templates that imported v1. Authors must update the importing template to use v2.
- Imports are transitive: library A can import library B. The render engine resolves the full closure.
- Cycles forbidden: A imports B, B imports A → `template.import.cycle_detected`.
- Max import depth: 10 (configurable). Prevents pathological nesting.

### 6.5 Library promotion across environments

Libraries are promoted like regular templates — export bundle includes them. Importing pipelines fail validation in the target env if a referenced library version is missing (`pipeline.import.missing_template`).

---

## 7. Validation Rules

All checks run at template create/update time.

| Code | Check |
|---|---|
| `template.validation.dialect_invalid` | `dialect` is in the allowed enum |
| `template.validation.id_invalid` | `id` matches `[a-z0-9_\.\-]+`, length 1–100 |
| `template.validation.body_malformed` | Freemarker parses the body without syntax errors |
| `template.validation.undefined_variable` | Every `${var}` and `<#if var...>` references a variable in `params_schema` (or supplied by pipeline parameters at render time) — see §7.2 |
| `template.validation.import_not_found` | Every `imports` entry resolves to an existing library template version |
| `template.validation.import_cycle` | Import graph is acyclic |
| `template.validation.import_depth_exceeded` | Transitive import depth ≤ 10 |
| `template.validation.dangerous_construct` | Body uses a forbidden Freemarker construct (see §4.2) |
| `template.validation.params_schema_invalid` | `params_schema` parameter declarations are well-formed (per Type System rules) |
| `template.validation.is_library_without_macros` | `is_library: true` requires at least one `<#macro>` definition |

### 7.1 Parse-time vs render-time validation

- **Parse-time** (always run on save): Freemarker syntax validity, dangerous-construct scan, import graph validity, params_schema structural validity.
- **Render-time** (run when a pipeline using this template is created/updated): every variable referenced in the body is resolvable — either declared in `params_schema`, or supplied by the pipeline's `parameters`, or produced by a declared calculator (v2).

### 7.2 The undefined-variable rule

This is the most subtle validation. The rule:

**At pipeline-validation time, for every template referenced by every node, every Freemarker variable used in the template body must be resolvable** to one of:
1. A parameter declared in the template's own `params_schema`.
2. A parameter declared in the pipeline's `parameters` (passed via the context).
3. (v2) A calculator output declared in the pipeline's `calculators` block.

If any variable is unresolvable, validation fails with `pipeline.validation.template_parameter_undeclared`.

This catches typos and template-pipeline drift at write time, not at execution time.

---

## 8. Render Lifecycle

### 8.1 Single-template render

```
Input: TemplateRef{id, version}, Context{map of variables}
Process:
  1. Look up template body + params_schema from registry by {id, version}
  2. Resolve imports (transitively)
  3. Build combined Freemarker namespace (main template + imported libraries)
  4. Validate every variable in context satisfies the template's params_schema (type check)
  5. Render via Freemarker
  6. Return SQL string
Output: SQL string
```

### 8.2 Error behavior

- Template `{id, version}` not found → `pipeline.node.template_not_found` (should not happen — write-time validation should have caught this).
- Variable in body not in context → `pipeline.node.template_render_failed` (details include the variable name and template id+version).
- Type mismatch (e.g., context has STRING where DATE is expected) → `pipeline.node.template_render_failed`.
- Forbidden construct somehow present (shouldn't be — caught at save time) → `pipeline.node.template_render_failed`.

### 8.3 Performance

- Templates are cached in memory after first render (LRU cache, configurable size, default 1000 templates).
- Cache key: `{template_id, version, import_versions}`.
- Cache invalidated on template update (new version → new cache entry; old entry remains for in-flight executions).
- Render itself is fast (<10ms typical for templates up to ~5KB body).

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
| Render against sample context | `POST /templates/{id}/versions/{version}/render` |
| Import library bundle | `POST /templates/import` |

---

## 10. UI Editor Integration

The UI template editor (in `web` module) provides:

- **Syntax highlighting** for Freemarker + SQL.
- **Live preview** via `/render` endpoint — type sample context values, see rendered SQL.
- **Validation feedback** — error markers for parse errors, undefined variables, forbidden constructs.
- **Library browser** — pick library templates to import from a searchable list.
- **Version history** — view diff between versions, restore old version (creates new version with old body).
- **Test render against real datasources** (optional) — for templates targeting `tempdb`, can render and run against the user's own H2 instance.

---

## 11. Stability Promise

The Template entity is **versioned, additive-only**.

### 11.1 Frozen in v1

- The Template JSON shape (top-level fields, `params_schema` structure, `imports` structure).
- The Freemarker integration contract (allowed/forbidden constructs).
- The `is_library` convention.
- The import resolution rules.
- The version-immutability guarantee.

### 11.2 Not frozen

- New optional fields (`tags`, `metadata`, etc.) may be added non-breakingly.
- The render cache configuration is deployment-specific.
- Future template languages beyond Freemarker are out of scope but possible (would be a new `engine` field).

---

## 12. Implementation Notes

### 12.1 Where this lives

`templates` Gradle module:

- `co.datapipelines.templates.Template` data class
- `co.datapipelines.templates.TemplateVersion` data class
- `co.datapipelines.templates.TemplateRegistry` — lookup by id+version, caching
- `co.datapipelines.templates.TemplateEngine` — wraps Freemarker, exposes `render(ref, context): String`
- `co.datapipelines.templates.TemplateValidator` — runs §7 checks
- `co.datapipelines.templates.LibraryResolver` — resolves transitive imports

### 12.2 Persistence

`templates` and `template_versions` tables in the app's metadata DB. Schema:

```sql
CREATE TABLE templates (
  id           VARCHAR(100) PRIMARY KEY,
  display_name VARCHAR(255) NOT NULL,
  description  TEXT,
  is_library   BOOLEAN NOT NULL DEFAULT FALSE,
  is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
  created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
  created_by   UUID NOT NULL,
  current_version INTEGER NOT NULL
);

CREATE TABLE template_versions (
  template_id  VARCHAR(100) NOT NULL REFERENCES templates(id),
  version      INTEGER NOT NULL,
  dialect      VARCHAR(20) NOT NULL,
  params_schema JSONB NOT NULL,
  imports      JSONB NOT NULL,         -- array of {id, version}
  body         TEXT NOT NULL,
  created_at   TIMESTAMP NOT NULL DEFAULT NOW(),
  created_by   UUID NOT NULL,
  PRIMARY KEY (template_id, version)
);
```

### 12.3 Testing

- Unit tests for `TemplateValidator` covering every check in §7.
- Round-trip tests: every sample template in `docs/examples/` must parse, validate, and render against its sample context.
- Forbidden-construct tests: every Freemarker SSTI vector (from public security advisories) must be rejected at save time.
- Library import tests: transitive imports, depth limits, cycle detection, missing library handling.
- Performance tests: render latency for templates up to 50KB body, 20 imports deep.

---

## 13. Open Questions / Future Additions

Out of scope for v1:

- **Alternative engines**: support Pebble, Handlebars, or Thymeleaf for SQL alongside Freemarker. New `engine` field on templates. Not v1.
- **Parameterized SQL output**: instead of rendering to a single SQL string, render to `{sql, params}` for prepared-statement execution. Closes the SQL-injection gap. v1.1 candidate.
- **Multi-dialect templates**: a single template with dialect-conditional sections (`<#if dialect == "ORACLE">...<#else>...</#if>`). v2 candidate.
- **Template testing framework**: declarative test cases per template (`given this context, render should match this expected SQL`). Useful for regression testing.
- **Template composition visualizer**: UI to show how imports resolve into the final rendered SQL.

---

## Appendix A: Worked Example

### Library: `lib_aggregate.sql` v1

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

### Pipeline template: `monthly_revenue.sql` v3

```ftl
<#import "lib_aggregate.sql" as agg>

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
WHERE r.total >= ${min_total?c}
ORDER BY r.total DESC
```

### Render against context `{min_total: BigDecimal("1000.00")}`

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
| 2026-08-05 | v1.0 | initial draft | Initial templates spec: entity, Freemarker config (security-hardened), library macros, versioning, validation |
| 2026-08-05 | v1.1 | propagation | Added `engine` field to Template entity (default `"freemarker"`; future-proofing for Pebble/Handlebars/raw-SQL). Updated `body` and `imports` field descriptions to be engine-aware. Renamed `__staging__` → `tempdb` in UI editor section. |
