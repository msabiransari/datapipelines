---
area: templates
layer: guide
purpose: The SQL a node runs — template anatomy, dialects, the draft loop, library macros.
---

# Templates — SQL with a draft loop

A **template** is Freemarker SQL the pipeline's nodes pin by `{id, version}`: an `id` that is
always a folder path (the core's naming rule), a `dialect`, a `display_name`, a `description`,
optional `imports` for library macros, the `body`, and `is_library`. **There is no params
schema field** — the variables a body may reference are exactly the calling pipeline's
`parameters` keys (defaults applied). A declared parameter is referenced as a bind: `WHERE id
= :customer_id` (the core's parameter-safety rule). The body must never contain
`<#import>`/`<#include>` — imports come from the `imports` array and the body calls macros by
alias (`<@dates.date_range …/>`).

**Dialects** — eight: POSTGRES, ORACLE, MSSQL, MYSQL, H2, DUCKDB, SQLITE, LAKE. A node's
template dialect must match what its `source` can execute; `LAKE` is object storage read in
place (`lake`).

**Types.** `sql` is the default and the only kind a pipeline node references today; `html`
renders escaped output and takes no `dialect`; the transform types are their own area
(`transforms` — the render tools refuse them with `template.render_not_applicable`).

## The workflow

1. **Write** — `templates_create` with `dialect` and a `description` naming every parameter
   the body expects (the description is the only discoverability mechanism for parameters).
   Before creating a lookup or reference template, `templates_list {"q": "<table>"}` and pin
   what exists.
2. **Render** — `templates_render` with representative values; save-time validation is
   parse-only, so the render is your check that the SQL is what you meant.
3. **Iterate on the draft** — to change one, read its `body_hash` with `templates_get` and
   call `templates_update` with the body and the hash (**the dialect is inherited**); it
   writes the DRAFT the same way `pipelines_update` writes a pipeline's. `templates_purge_draft`
   is for a template that should not exist, not for editing one, and is refused once a
   pipeline pins the template.

## Libraries

Shared macros live in library templates (`is_library: true`) — only `<#macro>`/`<#function>`
definitions, named under `<owner>/lib/` (the core's naming rule). A consumer lists them in
`imports` (`[{"id","version","alias"}]`) and calls them by alias; template versions are pinned
like any node's. Before importing a helper, read what it computes — a macro that ROUNDS is a
presentation helper and changes answers it is reused inside (`pipelines-numbers`). A literal
list shared by several templates lives in ONE library template that they all import — never
copied between siblings (`pipelines-dag`).

## Common mistakes

Minting a second copy of a lookup another pipeline already pins; letting the next
`pipelines_execute` be a new template's first render; editing by re-creating instead of
`templates_update` with the hash; interpolating a caller value where a bind belongs.

## References — open when

- **`templates-calculators`** — a value must be computed once and bound downstream: the
  CALCULATOR node, the Context keys, overriding.
- **`templates-tools`** — the area's tools, generated from their shipped descriptions.
- **`transforms`** — the body is a function, not SQL: the transform types.
- **`pipelines-engine-quirks`** — the render failed or the dialect fought back: engine traps.
