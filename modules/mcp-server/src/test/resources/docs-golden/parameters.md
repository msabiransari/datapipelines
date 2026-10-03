
# Parameters — sets, selectors and the evaluate loop

A **parameter set** is a versioned definition of form controls: a named folder path
(`acme/sales/region_filters`) holding an ordered list of parameters. A client renders the
controls, collects values and submits them back; the SERVER does all dependency work — it
re-renders the whole set on every submission and returns every parameter's fresh options and
resolved value. The client runs no SQL and no dependency logic. Versioning is exactly
templates': draft → release → (discard / restore / purge / switch), with the same hash
precondition (`body_hash` → `expected_hash`) and the same promoter lens. Addressing is
pipelines': every tool after `parameter_sets_list` takes the **id** `parameter_sets_list`
returns, never the name.

## The two kinds of control

- **`INPUT`** — a value the person types. Always `SINGLE`. Its initial value is either
  hard-coded (`default_value`) or read from the database (a pinned template returning at most
  one row with one `value` column). It may declare `constraints` (`min`, `max`, `min_length`,
  `max_length`, `pattern`); a violating value is REFUSED, never rounded or trimmed.
- **`SELECT`** — a value the person picks, `SINGLE` or `MULTI`. Options come from
  `source.constants` (hard-coded rows) or `source.template` (a pinned template). SQL is for
  data that changes; it is never required.

Every option is `{value, display_value, is_default}`; the server never infers one from another.
No option marked default ⇒ the first option in order is the default; more than one ⇒ refused.

**Ask before a MULTI without a hint.** Before creating a `SELECT` with `cardinality: MULTI`
and no `presentation.control`, ask the person how it should render — dropdown, checkboxes or
list — and set `presentation.control` accordingly. It is the same discipline as
`confirm_new_root`: one question, before the definition exists, not a surprise after.

## The selector contract (SQL-backed options)

Selector SQL is a **pinned template** (`source.template = {id, version}`), never inline SQL.
The query returns exactly three columns, by alias:

```sql
SELECT state_code   AS value,
       state_name   AS display_value,
       state_code = 'NY' AS is_default
FROM   dim_state
WHERE  country_code = :country
ORDER  BY state_name
```

- Parents bind as `:name` — NEVER interpolated (the `` `${name}` `` shape; interpolation of a
  `depends_on` parameter is refused at save). A `MULTI` parent binds as a list:
  `WHERE region IN (:regions)`.
- `ORDER BY` is required on a selector. The check proves the clause is present; you supply the
  tie-breaker, because "the first option" is the first row the database returned.
- A database-fed `INPUT` returns exactly one column, `value`, at most one row — no `ORDER BY`
  needed.
- A deliberate "nothing" is an option ROW the author adds — a sentinel such as
  `UNION ALL SELECT 'ALL', '(All)', TRUE` — and the consuming SQL handles it
  (`WHERE (:country = 'ALL' OR country = :country)`). Option values are never `NULL`.

## The in_list macro (large MULTI selections)

The list itself never reaches the template; its SIZE does, as `:regions_count` (one per MULTI
parent, named `<name>_count`). For long lists, import a library macro and render slices. A
complete library template (the parameter names are upper-case by house convention for this
macro — FreeMarker is case-sensitive, and the manual renders no lower-case interpolation
literals):

```ftl
<#-- acme/lib/in_list.sql version 1 — is_library: true -->
<#macro in_list COLUMN BIND COUNT CHUNK=500><#local SLICES = (COUNT / CHUNK)?ceiling>(
  <#list 1..SLICES as K>${COLUMN} IN (:${BIND}__${K})<#if K < SLICES> OR </#if></#list>
)</#macro>
```

Import it and call it in the selector — `COUNT` takes the parent's `_count` bind the runner
supplies:

```sql
SELECT region AS value, region AS display_value, FALSE AS is_default
FROM   dim_region
WHERE  <@in_list COLUMN="region" BIND="regions" COUNT=regions_count CHUNK=500/>
  AND  country_code = :country
ORDER  BY region
```

For a submitted list of 1,000 regions that renders
`(region IN (:regions__1) OR region IN (:regions__2))` — `ceil(count / chunk)` slices, numbered
from 1, at most `chunk` members each; the runner deals the submitted list across the slices the
SQL names in order and binds each slice. Keep the slices numbered from 1: the highest `__<k>`
the SQL names decides how the list is dealt. Caps: 1,000 values per `MULTI` parameter and 2,000
binds per statement, each refused with its own code.

## The evaluate loop (agent-driven check)

`parameter_sets_evaluate` takes the set id, optional `version` (absent = the SERVED version; a
DRAFT by its own number), and `selections` — EVERY parameter's current value, wire-encoded for
its type, `MULTI` as an array.

1. **First render:** send `{"selections": {}}` — the server initialises the whole form and
   returns every parameter with options, resolved value, `origin`, `computed_default`.
2. **On any change:** submit EVERY parameter again — the value the person chose, the value the
   last response gave, or `null` when the control is empty (`[]` for a MULTI means the same).
   Absent, `null` and `[]` are one signal — *nothing chosen* — and walk the selection
   priority: the client's valid value, else the configured default, else the first option.
3. **Read the response:** `values` is the consumer payload (one canonical value per
   parameter); `parameters[].state` carries `origin` (`client`/`default`/`first`/`source`/`none`),
   `computed_default` (what the priority would give now) and `reset` — a submitted value no
   longer among the options is NOT an error; it walked the priority and `reset: true` says so.
   `valid: false` means at least one parameter carries an `errors[]` entry — show it; invent
   none.
4. **A draft evaluate:** a draft whose pinned DRAFT template changed after this key's last
   `templates_render` of it is refused `parameter.evaluate.template_unrendered` — render each
   stale pin, then evaluate again.

An unknown `selections` key refuses the whole request. Sending only the changed control is the
one mistake this loop cannot recover from: the server cannot tell "unchanged" from "cleared".

Every evaluate is recorded, yours included: a failed or timed-out evaluation is visible on the
Parameter Sets page's History tab, whoever ran it — point the person there rather than describing
a failure you cannot see. The record holds codes and stamps only, never a value you submitted.
