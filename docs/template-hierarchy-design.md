# Template Hierarchy & Typed Templates — v1 Design

**Status:** design (not yet normative; no code changed)
**Revision:** v1.2 — 2026-09-01 (UI section §9; hash-input decision §5.2; legacy-name gate §4.6; `html` acceptance bar §2). **One blocking open item: §9.6.**
**Owner:** datapipelines.co core
**Depends on:** [Templates spec](templates.md), [Pipeline Contract spec](pipeline-contract.md), [Metadata DB spec](metadata-db.md), [Enums](enums.md)
**Date:** 2026-09-01

---

## 1. Purpose

Two capabilities land together, because they touch the same schema and contract surface:

1. **Hierarchy.** Templates get path-style names (`acme/finance/aggregates`) so the UI can present a folder tree instead of a flat table, and organizations can structure shared template libraries the way they structure code.
2. **Typed templates.** Templates gain a `type` field (`sql | html`) so the same versioned, DB-stored, runtime-managed template system can later serve HTML/rendering use cases — without any component-level taxonomy leaking into the template store.

Everything remains stored in the database, editable and releasable at runtime, promotable between environments as natural-key row copies, and backed up by `pg_dump`. No filesystem state is introduced anywhere.

## 2. Scope

**In scope (v1):**

- Path-in-name hierarchy: wider name rules, tree UI, prefix queries.
- `type` column on `template_versions` (`sql | html`), part of the content contract. It is deliberately **not** a `body_hash` input — see §5.2.
- `dialect` becomes nullable: required iff `type='sql'`, null for `type='html'`.
- A second, auto-escaping Freemarker `Configuration` for `html` renders.
- Reference-legality validation: pipeline nodes may only reference `type='sql'` templates.
- Type-aware preview/render path (draft render with mock context works for both types). **This is the entire `html` acceptance bar for v1**: schema, the second engine configuration, and a draft-preview render proving escaping (§12.4). Nothing else consumes `html` until dashboards land.
- A migration pre-check that aborts the deploy on any stored template name the new grammar would reject (§4.6).

**Explicitly out of scope (later versions, listed so nobody "helpfully" builds them):**

- Component sub-typing (kpi, aggrid, svg, form, …). That vocabulary belongs to the future **dashboard** abstraction, which consumes `html` templates by pinned ref. Categorization at template level is done with path conventions only.
- Dashboards and dashboard components as an abstraction (separate design, not discussed here).
- Packages (named subtree releases), global/cross-workspace library workspace.
- Bulk import/export (zip, git-sync) of template trees.
- Folder rename/move as an operation (names are identity; see §4.5).
- A public serving endpoint for `html` output (CSP headers, embedding, caching). The draft-preview path is the only `html` consumer in v1; a real serving surface lands with dashboards.
- New template engines (`engine` stays `"freemarker"` only).

## 3. Design principles

1. **Hierarchy is a naming convention, not a schema dimension.** The full path *is* the template name. Folders are virtual — derived from name prefixes — and exist only in the UI and in prefix queries. No `namespace`/`folder`/`path` column, no new table, no change to the `(workspace_id, name)` identity.
2. **The template store stays generic.** The kernel — `id, version, type, engine, imports, body, body_hash` — is type-agnostic. Type-specific contract fields (today: `dialect` for `sql`) are type-conditional, never repurposed. `dialect` keeps its precise meaning (SQL execution target, shared with datasources) and is never overloaded with component kinds.
3. **Content contract fields live on the version row.** Everything needed to interpret `body` is frozen with the version and rides along in promotion copies. `type` is such a field — but it is deliberately *not* a `body_hash` input, because §5.3 makes it constant across every version of a template, so it cannot distinguish two rows anywhere a hash is actually compared (§5.2). The hash inputs stay `{engine, dialect, is_library, imports, body}`, unchanged since V6.
4. **Frozen contract stays additive-only — with one audited exception.** Every wire change below is additive or a relaxation: a new optional JSON field, a nullable column where one was required. The name grammar is the exception: it is a widening in the ways that matter (`/`, 200 chars) but is *narrower* than today's rule in two respects, so it ships with a migration pre-check rather than a compatibility claim (§4.6, §11).
5. **Nothing invalid is ever stored** — unchanged. New rules (name grammar, type/dialect consistency, reference legality) are save-time checks with error codes, in the existing validation style.
6. **Runtime-only operations.** Authoring, preview, release, and promotion work entirely against the database at runtime. No redeploys for content changes.

## 4. Template naming grammar (normative for v1)

### 4.1 Grammar

```
path    := segment ("/" segment){0,9}          -- 1 to 10 segments
segment := [a-z0-9][a-z0-9_.-]{0,63}           -- 1 to 64 chars, starts alphanumeric
```

Total path length: **≤ 200 chars** (raised from today's 100 — paths are longer than flat names).

### 4.2 Rules and rationale

| Rule | Rationale |
|---|---|
| Separator is `/` only. Backslash rejected. | Freemarker name normalization is `/`-based; `\` would create a second, invisible path language and Windows-style ambiguity. |
| Segments start with `[a-z0-9]` | Forbids `.` and `..` segments (path-traversal shape) and `-`/`.`-leading oddities without a special-case list. **Narrower than today's rule** — see §4.6. |
| Lowercase only (unchanged) | Matches current rule (`TemplateValidation.kt:69`); avoids case-folding disputes across databases and any future export to case-insensitive filesystems. |
| Dots and dashes inside segments (unchanged) | Existing names (`fetch_orders.sql`, `lib_aggregate.sql`) remain valid; extension-style suffixes stay a pure convention, never required or interpreted. |
| No `@` (unchanged) | `@` is the `{name}@{version}` separator in registry/loader keys (`TemplateRef.key`, `RegistryTemplateLoader.parseKey`). |
| Segment ≤ 64 chars | Keeps a single path element readable in a tree and bounded in a loader key. **Narrower than today's flat 100** — see §4.6. |
| Max 10 segments, ≤ 200 chars | Bounds the tree UI depth, the synthesized import prologue length, and loader key size. 10/200 is generous for real libraries and cheap to relax later (relaxation is additive; tightening is not). |
| No leading/trailing `/`, no empty segments | A path is a sequence of segments, not a string that happens to contain slashes. |

Single-segment names (everything that exists today) are valid paths — they sit at the tree root.

### 4.3 Uniqueness and identity

Unchanged: `uq_templates_workspace_name UNIQUE (workspace_id, name)` (`V4__workspaces_rekey.sql:107`) — the **full path** is the name. `a/b` and `a/b/c` may coexist: `a/b` is a template, `a/b/c` is a template "under" a virtual folder `a` — folders have no identity, so there is nothing to collide with.

### 4.4 Relative names are prohibited — all references are absolute (normative)

**Relative path references are prohibited everywhere in the system.** Every template reference — in the `imports` array, in pipeline `TemplateRef`s, and in every name handed to Freemarker — is an **absolute path from the tree root**. There is no relative-resolution semantics to define, document, or defend.

This prohibition is cheap because of an existing invariant: bodies may not contain `<#import>`/`<#include>` directives at all (Templates §6.3), so the only names Freemarker ever sees are the ones the engine synthesizes from the validated `imports` array. Enforcement is therefore three mechanical layers, none of which trusts input:

1. **Grammar** — §4.1 rejects `.`/`..` segments, leading/trailing `/`, and empty segments, so a stored name is always a canonical absolute path.
2. **Prologue** — the synthesized import prologue (`RegistryTemplateLoader.kt:92`) MUST emit root-based names (`/{name}@{version}`). Today names are single-segment so Freemarker's relative resolution is a no-op; with `/` in names it would otherwise resolve against the importer's directory and silently mis-resolve.
3. **Loader** — `RegistryTemplateLoader.findTemplateSource` strips exactly one leading `/` and then fail-closes: anything not matching the §4.1 grammar (including `..` segments Freemarker normalization might produce) is rejected, never resolved.

**Gate:** verify against the pinned Freemarker 2.3.34 that (a) a leading-`/` name reaches the loader verbatim, and (b) relative resolution never fires for root-based names. Add a render test proving `acme/finance/report` importing `lib/dates` resolves `lib/dates`, not `acme/finance/lib/dates`.

### 4.5 No rename, no move

A template's name is its identity — pipeline refs, imports, and promotion all key on it. Renaming a template or "moving a folder" would break every pinned reference. v1 offers neither. Restructuring = create the new path (new template, new versions) and let old paths be deprecated organically. (A future alias/redirect mechanism is conceivable but out of scope.)

### 4.6 Legacy names: the grammar is not purely a widening (normative)

Today's rule is `TEMPLATE_ID = ^[a-z0-9_.\-]{1,100}$` (`TemplateValidation.kt:69`). §4.1 is wider where it counts (`/` as a separator, 200 chars total) but **narrower in two respects**:

| Legal today | Rejected by §4.1 |
|---|---|
| Leading `_`, `.` or `-` — `_helper`, `.tmp`, `-legacy` | Segments must start `[a-z0-9]` |
| A flat name of 65–100 chars | Segments cap at 64 chars |

This is not a save-time inconvenience, because `TEMPLATE_ID` is re-validated on **three** paths and two of them run at render time:

| Call site | When | Effect on a now-illegal stored name |
|---|---|---|
| `TemplateValidator.kt:54` | save | the next save is refused |
| `RegistryTemplateLoader.parseKey:65` | render | returns `null` → the template **does not resolve**; the pipeline fails |
| `TemplateValidation.kt:97` (`TemplateImport.isSafeToSynthesize`) | prologue synthesis | the import is silently dropped from the prologue |

So a stored name that becomes illegal breaks **execution of already-released, already-promoted pipelines** that pin it. Two aggravating facts make silence unacceptable here:

- §4.5 forbids rename, so there is no in-place repair after the fact.
- `TemplateRepository.lookupVersion` is deliberately *not* filtered by `is_deleted` (`TemplateRepository.kt:102-107`, templates.md §5.1) — pinned refs to a **soft-deleted** template still resolve. Soft-deleted rows are therefore in scope for the check, not exempt from it.

**Decision (2026-09-01):** keep the strict grammar and make the incompatibility loud at deploy time rather than silent at render time. `V7__typed_hierarchical_templates.sql` opens with a pre-check that aborts the migration and names every offender:

```sql
-- §4.6 legacy-name gate. Runs FIRST, before any DDL, so a violating deployment
-- fails with an actionable message and an unchanged schema.
-- is_deleted is NOT filtered: lookupVersion resolves soft-deleted templates for
-- pinned refs, so their names are still subject to the loader's grammar.
DO $$
DECLARE offenders TEXT;
BEGIN
    SELECT string_agg(name, ', ' ORDER BY name) INTO offenders
      FROM templates
     WHERE length(name) > 200
        OR name !~ '^[a-z0-9][a-z0-9_.-]{0,63}(/[a-z0-9][a-z0-9_.-]{0,63}){0,9}$';
    IF offenders IS NOT NULL THEN
        RAISE EXCEPTION
            'V7 aborted: template name(s) violate the v1 naming grammar: %. '
            'Remediation: docs/template-hierarchy-design.md §4.6.', offenders;
    END IF;
END $$;
```

**Operator remediation, per offending name** (pre-deploy; §4.5 forbids rename, so this is a re-create, not a fix):

1. Create a template at a §4.1-legal path with the same body, imports and dialect; release it.
2. Repoint every pipeline that references the old name — draft, edit the `TemplateRef`, release.
3. Delete the old template's rows outright. A *soft* delete is not enough: soft-deleted names still resolve and still trip the pre-check, by design.

**Known exposure at design time:** every template name in the shipped example seed (`fetch_orders.sql`, `active_users.sql`, `record_execution.sql`, `revenue_by_customer`, …) already satisfies §4.1. The gate is a safety net for customer-authored content, not a known blocker for the reference deployment.

## 5. The `type` field

### 5.1 Schema (migration `V7__typed_hierarchical_templates.sql`)

```sql
ALTER TABLE template_versions
    ADD COLUMN type TEXT NOT NULL DEFAULT 'sql',
    ALTER COLUMN dialect DROP NOT NULL,
    DROP CONSTRAINT chk_dialect,
    ADD CONSTRAINT chk_dialect CHECK (
        dialect IS NULL OR dialect IN ('POSTGRES','ORACLE','MSSQL','MYSQL','H2','DUCKDB','SQLITE')
    ),
    ADD CONSTRAINT chk_template_type CHECK (type IN ('sql','html')),
    ADD CONSTRAINT chk_type_dialect CHECK (
        (type = 'sql'  AND dialect IS NOT NULL) OR
        (type = 'html' AND dialect IS NULL)
    );
```

- Existing rows backfill to `type='sql'` via the default — zero data migration.
- `dialect` keeps its enum check **when present**; it is never reinterpreted.
- `chk_type_dialect` makes the type-conditional contract a database invariant, not an application-level hope.

### 5.2 Content contract — and why `type` is **not** a hash input (normative)

`type` is a version-owned contract field: it lives on the version row, is frozen with the version, and rides along in promotion copies. It is **not** added to the `body_hash` inputs. Those stay exactly `{engine, dialect, is_library, imports, body}`, and `TemplateRepository.TEMPLATE_HASH_EXPR` (`TemplateRepository.kt:719-722` — the same expression V6's backfill used) is **unchanged by V7**.

Two reasons, either sufficient on its own:

**1. It would carry no information.** The hash is compared in exactly two places: the draft-write concurrency precondition, always within one template's lineage; and promotion idempotency, always for one `(workspace, name, version)`. §5.3 makes `type` constant across every version of a template, so it is identical on both sides of both comparisons. A hash input that cannot vary where the hash is read is decoration.

**2. Changing the expression would break the draft-on-change guard.** Round 039 shipped copy-on-write drafts whose no-op detection compares the *incoming* content's hash against the *stored* one inside a single statement, using that same constant:

```
AND $TEMPLATE_HASH_EXPR <> v.body_hash    -- create a draft   (TemplateRepository.kt:790)
AND $TEMPLATE_HASH_EXPR  = v.body_hash    -- no-op, return the release  (:801)
```

Every pre-V7 row's stored hash was computed **without** `type`. Add `type` to the expression and leave history alone, and both branches misfire permanently for all existing content: the `<>` branch is always true, so a byte-identical PUT creates a draft on every save; the `=` branch never matches, so the no-op path is dead. Recompute history instead, and promotion breaks between two environments sitting at different migration levels — which §11's own gate exists to forbid.

Pinned references are unaffected either way: they resolve to a frozen version row whose hash is already recorded.

### 5.3 Type is fixed at creation (normative)

A template's `type` is chosen **when the template is created and never changes across its versions**. There is no legitimate migration of a body between SQL and HTML worlds, and allowing it would let a single identity flip meaning under every pinned reference's feet — the reference `{name, version}` would still resolve, but the *template's* purpose would have drifted.

Enforcement: `type` is accepted on template create only; `createDraft`/`writeDraft` do not accept it — a draft inherits the template's established type, and any authoring payload attempting to set a different one is rejected at save with **`template.validation.type_immutable`** (new error code). The column stays on `template_versions` (not the index row) so each version row remains self-contained: `chk_type_dialect` is a per-row invariant, and a promoted row carries its full contract with it without a join. The value is simply identical across all versions of a template by rule.

### 5.4 Wire format (additive)

Template JSON gains an optional `type` field. On **template create** it selects the type, defaulting to `"sql"` when absent; afterwards it is read-only and echoed on every version. `dialect` may be absent iff `type="html"`. Templates spec §3.1/§3.2 get the new rows.

Two distinct stability claims, because they land under different clauses of the Templates spec's stability promise:

- **Adding `type`** is squarely **Templates §11.2** ("new optional fields may be added non-breakingly"). Old clients reading new templates ignore it; old payloads without it are valid `sql` payloads.
- **Making `dialect` conditional** is *not* a new optional field — it is a relaxation of a field **Templates §11.1** froze as part of the Template JSON shape. §11.2 does not cover it verbatim. It is compatible in practice (no existing payload becomes invalid, and every existing template still carries a dialect), but the claim needs to be written down rather than assumed: **the implementing round amends Templates §11.2 to name conditional-requirement relaxations explicitly**, in the same commit as the code. Check for a spec-drift test over that section before editing it.

`enums.md` gains a `TemplateType` section (`sql`, `html`) alongside the existing `TemplateEngine`.

## 6. Rendering: two engine configurations

`FreemarkerConfigFactory.kt:46` builds one hardened `Configuration` today. v1 builds **two**, selected by the template version's `type` at render time:

- **`sql`** — exactly today's configuration. No output escaping; type-aware interpolation per Templates §4.4.
- **`html`** — identical hardening (ALLOWS_NOTHING_RESOLVER, no `?api`/`?new`, SimpleObjectWrapper, RETHROW handler, interruption support, same `ForbiddenConstructScanner` at save), **plus** `output_format = HTMLOutputFormat` and auto-escaping enabled, so `${user_value}` is HTML-escaped by default and markup requires explicit `?no_esc`.

Both configs share `RegistryTemplateLoader` (it is type-blind — it resolves `{name}@{version}`), the watchdog pool, and the render budget machinery. `WorkspaceTemplateEngines.kt:28` vends a per-workspace **pair** of engines instead of a single engine.

Everything else in the render lifecycle — imports synthesized as a prologue, no `<#import>`/`<#include>` in bodies, save-time AST scan — applies to `html` templates unchanged. A library template's macros are engine-level constructs; `imports` remains type-agnostic (an `html` template may import any library it is authorized to see).

Browser-side defenses beyond auto-escaping (CSP headers, vendored JS) are consumers' concerns — they land with whatever serves `html` output (preview endpoint now, dashboards later), not with the template store.

## 7. Reference legality

Pipeline contract §12.6 gains a sibling check in `ReferenceRules.checkTemplate` (next to the dialect check at `ReferenceRules.kt:169-181`):

- A DQL/DML/DDL node referencing a template whose version has `type='html'` is rejected at pipeline save with **`pipeline.validation.template_type_mismatch`** (new error code in `PipelineErrorCodes`, details map carries `template_type`).
- The existing `template_dialect_mismatch` check is unchanged and applies only to `sql` templates (every template a node can legally reference is `sql`, hence has a non-null dialect — the schema guarantees it).

Authoring-side symmetry: creating an `html` template does not require a dialect, and the save path rejects `dialect` present with `type='html'` with **`template.validation.dialect_not_allowed`** (new error code — distinct from `dialect_invalid`, which means "unknown dialect value"; a different failure gets a distinct, greppable code).

## 8. Repository, registry, loader

- `TemplateRepository` — `TemplateDraft`/version writes carry `type`; reads project it. New query: prefix listing for the tree (`WHERE workspace_id = ? AND name LIKE :prefix || '/%' AND is_deleted = false`, plus root-level listing for the tree's top level). Dialect-filtered listing gains a `type` filter companion.
- `RepositoryTemplateRegistry` / `RegistryTemplateLoader` — cache and key shapes unchanged (`{name}@{version}`); `parseKey`'s name-shape check widens to the §4.1 grammar plus the leading-`/` strip (§4.4).
- `TemplateValidation.kt:69` — `TEMPLATE_ID` regex replaced by the §4.1 grammar validator (a regex composition or a small segment-loop; keep it boring and total). It has **three** call sites, not one — `TemplateValidator.kt:54` (save), `RegistryTemplateLoader.parseKey:65` (render), `TemplateValidation.kt:97` `isSafeToSynthesize` (prologue). All three must move together, and §4.6 is the reason it matters.

## 9. UI

Two screens are affected, and the second is the easy one to miss: the templates browser gains the tree, and the **pipeline editor** displays template references that are about to become paths.

### 9.1 Constraints the UI inherits (normative — none of these are UI choices)

| Constraint | Source | What it forbids in the UI |
|---|---|---|
| The tree is backed by **server-side prefix queries** | §9.2, §8 | Shipping the flat list to the browser and building the tree in JS. No client-side tree assembly at any size. |
| **Folders are virtual** — derived from name prefixes, no identity | §3.1 | Folder CRUD of any kind: no "New folder", no rename, no move, no delete, no empty-folder state (an empty folder is unrepresentable, so it must never be rendered). |
| **`type` is chosen at template creation and immutable after** | §5.3 | A type control on any edit/draft form. Type is a create-time input and a read-only display everywhere else. |
| **The §4.1 grammar is server-authoritative** | §4.1, §4.6 | Client-side validation being treated as the gate. See §9.5. |
| **`/partials/**` is default-deny** | existing security config | A new tree partial that forgets its scope wiring. Any new endpoint joins the same authorization posture as `/partials/templates`. |
| Design tokens only | CLAUDE.md rule 3 | New hard-coded colors, spacing or fonts for tree chrome. Reuse the existing `ds-*` / `pe-*` classes. |

### 9.2 Templates browser — tree presentation

The screen today is a paged htmx table: `TemplateUiController.list` serves `GET /templates` (`TemplateUiController.kt:20`), `TemplatePartialController` serves the `GET /partials/templates` fragment (`TemplatePartialController.kt:20`), and `templates/list.html:16-35` wires a `q` search box and a `dialect` select at swap root `#template-list-wrapper` with `hx-swap="outerHTML"` and an `htmx-indicator` spinner. (The REST list at `TemplatesController.kt:130` is a different surface — it backs the API, not this screen.)

The tree keeps that contract rather than replacing it:

- **One level per request.** Expanding a folder issues `hx-get="/partials/templates?prefix=acme/finance"` and swaps that folder's child container. The stable swap root and the OOB/indicator conventions of the existing SPA table (`ui-screens.md` §4.5) carry over unchanged — this is a new fragment shape on an existing surface, not a new surface.
- **Leaves expand to versions**, with RELEASED/DRAFT lifecycle badges (`V6__version_lifecycle.sql`).
- **Filters:** `dialect` and `q` keep working; a `type` filter joins them.
- **Flat legacy names sit at the tree root.** Nothing is renamed or reorganized — §4.5 forbids it and §4.6 explains what happens to names that cannot survive the new grammar.

**Browse vs. search are different presentations (decided, §13.8).** Browsing shows the tree, one level per request. A non-empty `q` shows a **flat result list of full paths**, not a tree pruned to matching leaves: pruning requires walking ancestors of every match, which is precisely the whole-list-in-the-browser work §9.1 forbids, and a flat list of full paths is what a user searching `finance/agg` actually wants to see. Clearing `q` returns to the tree.

### 9.3 Create and edit forms

- **Create** gains a `type` selector (`sql` default) and makes `dialect` conditional on it — required for `sql`, absent and hidden for `html` (§5.1's `chk_type_dialect` is the backstop).
- **Edit / draft** renders `type` as a read-only value, never as a disabled control. A disabled `<select>` is re-enabled in devtools in one click; the server rejects the write with `template.validation.type_immutable` either way (§5.3), but the UI should not present a lock it does not own.
- **Name** is a create-time input only — §4.5 means there is no rename affordance anywhere.

### 9.4 Pipeline editor — the cross-screen gap

There is **no template picker in the pipeline editor today.** What exists is a read-only reference display: `pipelines/editor.html:184-188` renders a `Template` label and an anchor whose text is `id @ vN` and whose href is `'/templates/' + encodeURIComponent(selectedNode.template?.id) + '/editor'`; the server-rendered partial does the same at `partials/pipeline-node-sql.html:11-12`. Template selection happens through pipeline JSON authoring, import, and MCP — not through this screen.

That makes the gap narrower than "add a tree to the picker", and sharper:

1. **The existing display must survive paths.** `acme/finance/monthly_revenue @ v3` in a narrow inspector panel needs single-line truncation with the full path in `title`, not a wrapped or clipped href. Both call sites above, plus the `template-missing` empty state (`partials/pipeline-node-sql.html:34-38`).
2. **The link itself is the risk** — see §9.6. `encodeURIComponent` is already there and is correct, but correct encoding is not sufficient.
3. **If a picker is added later** it reuses the §9.2 prefix fragment. It does not get its own client-side tree. Recording this here is the point: the constraint is easy to lose because the picker would be built on a different screen, by a different task, from the one that establishes the rule.

### 9.5 Client-side name validation is a convenience, never an authority

The create form may check §4.1 as the user types, to give an immediate message instead of a round-trip. Two rules keep that from becoming a second, drifting authority:

- The server validates every write regardless, and its rejection is the one that counts (`TemplateValidator.kt:54`).
- The client pattern is **derived from the server's**, not retyped beside it — rendered into the form from the same source that builds the validator, so the two cannot drift. A hand-copied regex in a `<script>` block is exactly the drift this project has been bitten by; if deriving it is not practical in this round, ship **no** client-side pattern rather than a copy.

### 9.6 Blocking gate: template names are addressed as URL **path segments** (unresolved)

A template name containing `/` collides with how the name is addressed over HTTP today. This is not a styling concern — it decides whether path-shaped names are reachable at all, and it is unresolved.

Ten routes put the name in a path segment. Two are UI, eight are REST and are **frozen contract** (`rest-api.md:752-832`):

```
GET    /templates/{id}/editor                              TemplateEditorController.kt:30
POST   /partials/templates/{id}/versions/{version}/render  TemplateEditorController.kt:51
GET    /api/v1/templates/{id}                              TemplatesController.kt:86
GET    /api/v1/templates/{id}/versions/{version}                              :101
PUT    /api/v1/templates/{id}                                                 :145
POST   /api/v1/templates/{id}/release                                         :166
POST   /api/v1/templates/{id}/draft/discard                                   :182
DELETE /api/v1/templates/{id}                                                 :194
POST   /api/v1/templates/{id}/versions/{version}/render                       :210
```

`acme/finance/report` percent-encodes to `acme%2Ffinance%2Freport`. Servlet containers reject encoded slashes in the path by default, and no override exists anywhere in this repo (`grep` for `ALLOW_ENCODED_SLASH`, `relaxedPathChars`, `UrlPathHelper`: **no matches**). The likely outcome is a 400 before any handler runs — for the UI link, for every REST client, and for the promotion/import path.

**MCP is unaffected**: its tools carry the template id as a JSON argument, never as a URL path segment. This is an HTTP-addressing problem only.

**This must be settled by a spike before the round is dispatched**, because each option costs a different round:

| Option | Cost |
|---|---|
| Enable encoded slashes in the container | One config line, but it re-opens path normalization differences between container and framework — the class of thing §4.4 fail-closes against. Needs a security argument, not just a green test. |
| `{*id}` trailing-capture for the routes where the name is last | Works for `GET /templates/{id}`; does **not** work for `/{id}/versions/{version}`, `/{id}/release`, `/{id}/draft/discard`, which is most of the surface. |
| Move the name to a query parameter | Clean and normalization-free, but changes eight frozen REST routes — a contract break, not an additive change. |
| Address by the surrogate `id UUID` in paths, name only in bodies and queries | Structurally correct and already available since V4. Also a contract change, and it makes hand-written API calls less pleasant. |

**Gate:** a spike against the pinned container that actually issues `GET /api/v1/templates/a%2Fb` and reports the status code and where it was rejected. Until that number exists, §4.1's `/` separator is a design intention, not a working feature.

`ui-screens.md` gets the new screen description, the tree fragment contract, and the browse-vs-search rule when implementation lands.

## 10. API & MCP surface

- REST template endpoints (`rest-api.md` §8): request/response gain optional `type`; list endpoint gains `type` filter and `prefix` filter (tree backing). **The path-segment addressing question (§9.6) lands here too** — eight of these routes carry the name as a path segment and are frozen contract.
- MCP template tools (`mcp-server.md`): authoring tools accept `type`; read tools expose it; list gains the same filters. Tool descriptions updated so agents learn the path conventions and the type/dialect rule. MCP carries the id as a JSON argument, so §9.6 does not apply to it.
- Preview/render: the existing dry-render path (`TemplateDryRendererImpl`) is already type-agnostic mechanically; the render entry point selects the engine config by type (§6). No new endpoint is strictly required for v1 beyond making sure an `html` draft renders through the `html` config.

## 11. Compatibility & promotion

- **Stored pipelines, `type` dimension** — unaffected; every referenced template backfills to `sql`, and reference legality (§7) can only reject something that does not exist yet.
- **Stored pipelines, name dimension** — **not** unconditionally unaffected. The grammar is narrower than today's in two respects and the loader re-validates at render time, so a stored illegal name breaks execution rather than the next save. This is handled by the §4.6 pre-check aborting the deploy with the offenders named — a mechanical gate, not a compatibility claim.
- **Existing clients** — additive wire change per §5.4.
- **Promotion** — the natural-key copy story is unchanged: `(workspace, name, version)` rows copy verbatim, `type` rides along as an ordinary column, `body_hash` idempotency still detects already-promoted content. Because §5.2 leaves the hash inputs alone there is nothing to recompute and no cross-environment skew: a V6 receiver and a V7 sender compute identical hashes for identical content. **Gate:** assert `TEMPLATE_HASH_EXPR` is byte-identical before and after V7, and that a template released pre-V7 still promotes idempotently post-V7 (no spurious version conflict).
- **Backups** — still just `pg_dump`; this design adds zero out-of-band state.

## 12. Testing & implementation gates

1. **Naming grammar** — unit tests over the validator, exercised through **all three** call sites of §8: every rule in §4.2 positive and negative (`..`, `.`, `//`, trailing `/`, backslash, `@`, uppercase, 11 segments, 201 chars), plus the two §4.6 narrowing cases as explicit negatives (`_helper`, a 65-char segment).
2. **Legacy-name pre-check (§4.6)** — migration test: seed one active `_helper` template and one soft-deleted `-legacy` template at V6, run V7, assert it aborts and the message names **both**; with neither present, V7 applies cleanly. *Falsification:* delete the pre-check block and this test must go red.
3. **Relative-name gate (§4.4)** — the two pinned-Freemarker assertions plus the end-to-end import-resolution test.
4. **Dual config** — render test proving `html` escapes (`${"<script>"}` → `&lt;script&gt;`) and `sql` does not; both configs reject the same forbidden constructs at save. Together with gate 6 this is the **whole** `html` deliverable for v1 (§2) — no serving endpoint, no dashboard surface, nothing that would need CSP headers.
5. **Reference legality** — pipeline-save tests: node → `html` template rejected with `template_type_mismatch`; node → `sql` template with dialect mismatch still rejected with `template_dialect_mismatch`.
6. **Schema invariants** — migration test: backfill yields `type='sql'` everywhere; `chk_type_dialect` rejects all four violation shapes.
7. **Tree queries** — prefix listing returns exactly the direct children of a prefix; root listing excludes nested names.
8. **Error-code catalog lands in the SAME commit.** This design introduces three new codes — `template.validation.type_immutable` (§5.3), `pipeline.validation.template_type_mismatch` (§7), `template.validation.dialect_not_allowed` (§7). Each must land as a row in `pipeline-contract.md` §13.9 **and** as a constant in `PipelineErrorCodes`, in the same commit as this doc. Two mechanical guards will otherwise turn `main` red: `scripts/docs-audit.sh` (which already reports these four citations as uncatalogued today, while the doc is untracked) and `ApiErrorCatalogSpecDriftTest`, whose hand-maintained `SECTION_13_ROW_COUNT` is **99** and must become **102**. Verify with `bash scripts/docs-audit.sh` before committing.
9. **UI — tree fragment.** Expanding a folder issues exactly one request and returns exactly that folder's direct children; a fixture with 3 folders × 200 leaves proves no request returns the whole list. *Falsification:* replace the prefix query with a full listing and this test must go red.
10. **UI — the forbidden affordances are absent.** Render assertions: no folder-create/rename/delete control anywhere in the tree; no `type` control on the edit/draft form (create only); no rename affordance on either form. These are cheap render tests and they are the only thing standing between §9.1's constraints and a well-meaning future task.
11. **UI — paths do not break the pipeline editor.** A node referencing `acme/finance/monthly_revenue` renders truncated with the full path in `title`, in both `pipelines/editor.html` and `partials/pipeline-node-sql.html`, including the `template-missing` state.
12. **Hash stability (§5.2, §11)** — assert `TEMPLATE_HASH_EXPR` is unchanged by this round, and an integration test that a no-op PUT against a template released **before** V7 still returns the no-op and creates **no** draft after V7. *Falsification:* add `'type', type` to the expression and this test must go red. A gate that cannot go red on the change it forbids is not a gate.

## 13. Decisions log

Decisions 1–8 are settled. **This doc is no longer decision-complete: §9.6 is an open blocking item** and must be closed by a spike before the round is dispatched — see "Open" below.

1. ~~`dialect` on `html`: reuse `dialect_invalid` vs. new code~~ → **new code `template.validation.dialect_not_allowed`** (§7, decided 2026-09-01).
2. ~~Tree UI: server-side prefix queries vs. client-side tree~~ → **server-side prefix queries** (§9, decided 2026-09-01) — the target is companies with large shared libraries, where client-side tree-building breaks.
3. ~~Type per-draft vs. fixed at creation~~ → **fixed at creation, immutable across versions** (§5.3, decided 2026-09-01).
4. ~~Relative path support~~ → **prohibited; all references are absolute from the tree root** (§4.4, decided 2026-09-01).
5. ~~`type` as a `body_hash` input~~ → **not a hash input; `TEMPLATE_HASH_EXPR` unchanged** (§5.2, decided 2026-09-01) — immutable per §5.3 so it carries no information, and changing the expression would break 039's draft-on-change guard for every pre-V7 row.
6. ~~Legacy names the new grammar rejects: relax the grammar / grandfather in the loader / fail the deploy~~ → **fail the deploy** (§4.6, decided 2026-09-01) — one strict rule, loud at deploy time with the offenders named, rather than silent at render time where §4.5 leaves no repair.
7. ~~`html` acceptance bar for v1~~ → **preview-only** (§2, §12.4, decided 2026-09-01) — schema, second engine configuration, and a draft-preview render proving escaping. One migration, one hash decision, taken once; the serving surface lands with dashboards.
8. ~~Tree browse vs. text search: prune the tree to matches vs. flat result list~~ → **flat list of full paths on search, tree when browsing** (§9.2, decided 2026-09-01) — pruning requires ancestor-walking every match, which is the client-side whole-list work §9.1 forbids.

**Open (blocking):**

- **§9.6 — template names are addressed as URL path segments.** Ten routes (two UI, eight frozen REST) carry the name in the path; encoded slashes are rejected by default and this repo sets no override. Until a spike reports the actual status code for `GET /api/v1/templates/a%2Fb` against the pinned container, `/` in a name is a design intention rather than a working feature. Four options with their costs are enumerated in §9.6; three of the four are contract changes, so this decides the shape of the round and cannot be deferred into it.

---

*Design settled in conversation, 2026-09-01: path-in-name hierarchy; generic store with `type: sql|html`; no component taxonomy at template level; `dialect` nullable and SQL-only; runtime-only operations; dashboards deferred to a separate design.*
