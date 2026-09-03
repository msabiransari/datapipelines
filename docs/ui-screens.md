# UI Screens Inventory

**Status:** v1.19
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Editor](pipeline-editor.md), [Design System](pipeline-editor.md#34-design-system-acmedesign-tokens), [REST API](rest-api.md), [Auth & Security](auth.md), [Templates](templates.md), [Configuration Reference](configuration.md)
**Last updated:** 2026-09-02

---

## 1. Purpose

The pipeline editor is fully specified, but the app has many other screens. This spec inventories **every page in the application** — its URL, purpose, what it shows, what REST endpoints it calls, what design system primitives it uses, and whether it uses htmx or vanilla JS.

These are standard CRUD + list/detail screens. They don't need pipeline-editor-level detail (no Cytoscape, no SSE, no Alpine complexity). They use **Thymeleaf + htmx + design system primitives** — server renders HTML, htmx swaps partials for interactions.

---

## 2. Design Principles

1. **Server-rendered by default.** Thymeleaf renders full HTML pages. htmx handles partial updates (search, filter, pagination, form submission) without full page reload.
2. **htmx for these screens, fetch for the pipeline editor.** htmx is the right tool for "server renders partial HTML, swap it in" patterns. The pipeline editor is the exception (graph + SSE requires vanilla JS).
3. **Design system everywhere.** Every screen uses `@acme/design-tokens` tokens and `.ds-*` primitives. No exceptions, no hardcoded colors. Where a class named below has no counterpart in the vendored `primitives.css` (`.ds-spinner`, `.ds-toast*`, `.ds-empty-state` and `.ds-avatar` are the candidates — the roster is ~80 classes and is not enumerated in these docs), the app defines it in `app.css` **derived from design tokens**, never from literal values ([Pipeline Editor §3.4](pipeline-editor.md#34-design-system-acmedesign-tokens)). Confirm against the vendored file at implementation time before adding an app-level class.
4. **Consistent layout shell.** Every page (except login) shares a common nav bar + sidebar layout, defined in a Thymeleaf layout fragment.
5. **Three URL spaces, never mixed.** Pages, HTML fragments, and JSON live under distinct prefixes — see §2.1.
6. **The server holds no UI state.** The app is stateless behind a load balancer with no sticky sessions ([Deployment](deployment.md)); every user preference that must survive a request lives on the `users` row, never in an `HttpSession`.
7. **Scopes are not asserted here.** The per-screen scope column in §4 is a convenience view of the authoritative matrix in [Auth §7.6](auth.md#76-scope--operation-matrix-authoritative).

### 2.1 Route Convention

Three disjoint URL spaces. A given URL belongs to exactly one of them, and the response media type follows from the space — not from the caller.

| Space | Prefix | Returns | Called by |
|---|---|---|---|
| **UI pages** | root paths — `/`, `/pipelines`, `/executions/{id}`, `/settings/api-keys`, `/admin/users` | full HTML document (Thymeleaf layout + content) | browser navigation |
| **htmx partials** | `/partials/**` | HTML **fragment** (no layout, no envelope) | htmx `hx-get`/`hx-post`/`hx-patch`/`hx-delete` |
| **JSON API** | `/api/v1/**` | JSON [response envelope](rest-api.md#4-response-envelopes) | agents, MCP, programmatic clients, the pipeline editor's `fetch` calls |

**htmx never calls `/api/v1`.** A JSON envelope is not a swappable fragment; pointing `hx-*` at the REST API would require client-side rendering, which principle 1 rules out. Every htmx interaction in §4 targets `/partials/**`.

**Partials are a presentation layer, not a second implementation.** A `/partials/**` controller calls the *same* application service as its REST counterpart and renders the result into a Thymeleaf fragment. `POST /partials/api-keys` and `POST /api/v1/auth/api-keys` ([REST §16.1](rest-api.md#161-api-keys-any-authenticated-principal--own-keys-only)) differ only in how the response is serialized — same service, same validation, same scope check.

**Auth on partials.** `/partials/**` is authenticated by the `dp_session` JWT cookie (never by `DP-API-Key` — API keys are for agents). State-changing partial requests (`POST`/`PATCH`/`DELETE`) are CSRF-protected: the frontend sends the `dp_csrf` cookie value in the `DP-CSRF-Token` header ([Auth §8.4](auth.md#84-api-endpoints-auth-via-api-key-or-jwt)). This is wired once in the layout (§3), not per-screen.

**The one carve-out — file downloads.** Result downloads (§4.9) are plain `<a href>` full-page navigations to the REST cursor endpoint, because the response is a file (CSV/Arrow/JSON), not a fragment. That is a browser navigation, not an htmx swap, so it does not violate the rule above; it authenticates with the same `dp_session` cookie.

---

## 3. Common Layout

All authenticated pages use a shared layout:

```
┌──────────────────────────────────────────────────────┐
│ Navbar: Logo | Pipelines | Datasources | Templates    │  --header-height (60px)
│           | Executions | Settings | [User Avatar ▾]   │
├──────────────────────────────────────────────────────┤
│                                                       │
│                   Page Content                        │
│                                                       │
└──────────────────────────────────────────────────────┘
```

Thymeleaf layout: `layouts/default.html` — includes navbar, design system CSS, htmx, theme switcher.

The layout also wires three things once, for every page:

- **Theme resolution.** The active design-system theme is `users.theme_preference` when set, otherwise the deployment default `datapipelines.ui.theme` ([Configuration §3.10](configuration.md#310-ui)). `UiWorkspaceAdvice` resolves it per request into `${activeTheme}` for EVERY screen (a controller that forgets it renders a `themes/null.css` URL that 404s, leaving every design token unresolved — no borders, no surfaces); individual controllers may still set it explicitly, which simply overrides the advice's value with the same one. Emitted as the `href` of the `#theme-link` stylesheet element ([Pipeline Editor §3.4](pipeline-editor.md#34-design-system-acmedesign-tokens)) — see §4.11. The deployment setting is the default, not a ceiling: a user preference overrides it for that user only, and an unset preference is indistinguishable from today's config-only behaviour.
- **Nav chrome (027 UI pass).** The navbar is a sticky, full-width bar whose links and Logout render only for authenticated requests (`UiWorkspaceAdvice.authenticated` — anonymous screens like Login see the brand only). The active section is highlighted from `UiWorkspaceAdvice.currentPath`. Nav and content share the `.app-container` shell (app.css), capped at `--app-content-max` (1600px) rather than the design system's `--container-xl`. App-level chrome and table polish live in `static/css/app.css` — the vendored design system files are synced from design-system-starter and are never edited in this repo.
- **CSRF for htmx.** `hx-headers` on `<body>` carries the `dp_csrf` cookie value as `DP-CSRF-Token`; because `hx-headers` is inherited, every descendant htmx request is covered ([Auth §8.4](auth.md#84-api-endpoints-auth-via-api-key-or-jwt)).
- **Workspace switcher + context (workspaces design §9).** The navbar carries a `<select>` of the principal's memberships (`UiWorkspaceAdvice` fills it for every screen); choosing one POSTs `/workspace/switch`, which re-stamps the session JWT's `active_workspace` claim and re-issues `dp_session`, so full-page navigations follow the switch. The layout's `hx-headers` ALSO carries `DP-Workspace: <active>` for every htmx partial call — both mechanisms agree because the switcher drives both. A principal with zero memberships sees no switcher and empty states, never an error page (workspaces design §7).
- **Toast region.** An empty `<div id="toast" aria-live="polite"></div>` and `static/js/toast.js`, which together implement §5.1 Notifications — including `bridgeErrors`, the small `htmx:beforeSwap` listener that admits a 4xx/5xx to the swap only when the server retargeted it at `#toast` by header. No htmx extension is loaded; this replaces the `response-targets` prescription this spec once carried.

```html
<head>
    <!-- …design system load order per Pipeline Editor §3.4… -->
    <link rel="stylesheet" id="theme-link"
          th:href="@{'/vendor/design-system/themes/' + ${activeTheme} + '.css'}">
</head>
<body th:attr="hx-headers=|{&quot;DP-CSRF-Token&quot;: &quot;${csrfToken}&quot;}|">
    <div id="toast" aria-live="polite"></div>
    <!-- … -->
</body>
```

htmx is **vendored** (webjar) like the rest of the frontend stack — no CDN references, and no htmx extensions ([Pipeline Editor §4.2](pipeline-editor.md)).

---

## 4. Screen Catalog

**On the "Auth required" column:** it restates, per screen, the minimum scope for the REST operations that screen drives. The **authoritative** definition is the scope ↔ operation matrix in [Auth §7.6](auth.md#76-scope--operation-matrix-authoritative) — if this column and that matrix ever disagree, the matrix wins and this doc is wrong. Scopes are hierarchical ([Auth §7.5](auth.md#75-scopes)): `admin` ⊃ `author` ⊃ `execute` ⊃ `read`. Actions the current principal lacks scope for are **not rendered** (not merely disabled), and the server re-checks on every partial request — the UI is a convenience, never the enforcement point.

### 4.1 Login

| Attribute | Value |
|---|---|
| URL | `GET /login` (page), `POST /login` (local password form) |
| Auth required | No |
| Purpose | Sign-in — one username/password form, then a divider, then one button per configured OIDC provider; only the enabled methods render |
| Design primitives | `.ds-card`, `.ds-input`, `.ds-button--primary`, `.ds-button--secondary` |
| JS | None (plain form POST to `/login`; static links to `/oauth2/authorization/{provider-name}`) |
| htmx | None |

Content: centered card with app logo. When local accounts are enabled ([Auth §5A](auth.md#5a-local-password-accounts-optional)), an email+password form (with the `_csrf` hidden field) comes first; a plain "or" divider separates it from the provider buttons — one form, then the divider, then the buttons, never tabs. Only the methods actually enabled render: an OIDC-only deployment shows just the buttons (no form, no divider, exactly as before); a local-only deployment shows just the form. Provider buttons are **dynamic** — the controller reads the `ClientRegistrationRepository` and passes the provider list to Thymeleaf; button text is the `display-name` from the provider config ([Auth §5.1](auth.md#51-provider-configuration-generic), [§5.3](auth.md#53-login-page-dynamic--renders-buttons-for-each-configured-provider)). No hardcoded provider names anywhere in the UI.

Failure states are inline banners in the `?error=` idiom: `expired`, `domain_not_allowed`, `oidc_error` (OIDC); `credentials` (unknown email or wrong password — deliberately identical, [Auth §5A.5](auth.md#5a5-enumeration-resistance-and-the-password-policy)), `locked` (per-account lockout), `inactive` (deactivated account, either method).

### 4.2 Dashboard

| Attribute | Value |
|---|---|
| URL | `GET /dashboard` |
| Auth required | Yes (`read`) |
| Purpose | Landing page — overview of recent activity |
| Design primitives | `.ds-card`, `.ds-badge`, `.ds-table` |
| JS | None |
| htmx | Yes — refresh sections independently (`hx-get="/partials/recent-executions"`) |

Content:
- **Recent executions** (last 10): pipeline name, status badge, duration, timestamp. Clickable → execution detail.
- **My pipelines** (top 5 by updated_at): name, description, version. Clickable → pipeline editor.
- **Quick stats**: total pipelines, total executions today, success rate.

### 4.3 Pipeline List

| Attribute | Value |
|---|---|
| URL | `GET /pipelines` |
| Auth required | Yes (`read`) |
| Purpose | Search, filter, browse all pipelines |
| Design primitives | `.ds-table`, `.ds-input`, `.ds-badge`, `.ds-button` |
| JS | None |
| htmx | Yes — search filter and pagination (`hx-get="/partials/pipelines"` into the fragment-root `#pipeline-list-wrapper`, `outerHTML` — the fragment root carries the id, so the swap target survives every refresh) via the `#pipeline-filter-q` search input (`input changed delay:300ms`, `#pipeline-filter-spinner` indicator) and the shared §5 pager — full pattern in §5 |

Content: search bar, table of pipelines (name, display name, description, version, last updated). "Create Pipeline" button (requires `author` scope) → opens create modal. There is deliberately **no datasource filter**: the one shipped before v1.12 was labelled datasources but populated from `${dialects}`, the controller never had the parameter, and `PipelineRecord` carries no datasource field — serving it needs a join through the pipeline definition, so it was deleted rather than half-wired (deferred).

### 4.4 Pipeline Editor

Fully specified in [Pipeline Editor spec](pipeline-editor.md). Only the rows that touch THIS document's shared contracts are noted here:

- **SQL section (§8.3 there):** the details panel loads `GET /partials/pipelines/{id}/nodes/{nodeId}/sql` (a `READ_RESOURCES` read partial, htmx.ajax on selection) and highlights it client-side with the zero-dependency `sql-highlight.js`; the copy confirmation is a live-region announcement plus a 1.5s button-label swap — deliberately NOT a toast (high-frequency, self-evident).
- **Result grid:** the execution result table renders on the shared `.ds-table`; the bespoke `.pe-result-table` styles are gone. Paging stays client-side cursor paging (the §10.2 contract there), restyled to the shared pager's look — ghost buttons, centred count text.
- **Template reference (§9.4 there):** a node's template is a read-only reference display — a label and a link, `acme/finance/monthly_revenue @ v3`. **There is no template picker on this screen**, and template selection happens through pipeline JSON authoring, import and MCP. Since a name is a path, the reference truncates to one line with the FULL reference on `title`, at both call sites (the Alpine inspector's `templateRefText`, and the server-rendered `partials/pipeline-node-sql`) **and** in the `template-missing` empty state. **If a picker is ever added, it reuses §4.6's prefix fragment — it does not get its own client-side tree.** That constraint is recorded HERE, on the screen the picker would be built on, precisely because it would be built later, by a different task, and the rule that produced it lives on a different screen.
- **Notifications:** the SSE terminal events are the raison d'être of §5.1's Shape D — `pipeline_completed` and `execution_aborted` report via `DpToast.show` (they carry no HTTP response to hang an OOB swap on); `pipeline_failed` keeps the error modal (a failure detail is not a 6s notification). All three also announce on the live region.

### 4.5 Datasource List

| Attribute | Value |
|---|---|
| URL | `GET /datasources` |
| Auth required | Yes (`read` to browse; `author` to test a connection; workspace-bound create behind the `member-datasources-enabled` gate, global create/manage `admin` — workspaces D8) |
| Purpose | Browse, test, register datasource connections in the active workspace |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-button`, `.ds-modal` |
| JS | `static/js/toast.js` (layout-global: arms every `.ds-toast` appended to `#toast` — auto-dismiss + close) |
| htmx | Yes — test connection button (`hx-post="/partials/datasources/{name}/test"`, `hx-target="#toast"`, `hx-swap="beforeend"` — the result is a §5.1 Notifications toast, never a row swap), search + dialect filter + pager (`hx-get="/partials/datasources"` into `#datasource-list-wrapper`, `outerHTML` — the fragment root carries the id, so the swap target survives every refresh; the pager is the shared §5 fragment), register modal (`hx-post` on `/partials/datasources`, `#register-result` target — success is §5.1 Shape A: the success node closes the modal, the refreshed list and the toast ride along out-of-band, no `HX-Redirect` and no page reload). **A table partial travels as a whole `<table>` on any out-of-band path**: a `<tbody>` (or `<tr>`, `<td>`…) carrying `hx-swap-oob` nested in a `<div>` is silently DISCARDED by the browser's HTML fragment parser — table-only tags outside table context are dropped tokens, so the swap "succeeds" with empty content and no error anywhere (030 F-1; §4.10's keys table is the reference shape) |

Content: table of the ACTIVE workspace's datasources (name + `readonly` badge, dialect badge, workspace column — `global` or the bound name, URL, username). Per-row "Test" button (`author` scope) → connection result as a §5.1 Notifications toast; the table itself is never re-rendered mid-interaction. "Register Datasource" button → modal form with: name, display name, dialect dropdown, JDBC URL, username, password, description, and two checkboxes — `readonly` (always settable, [Datasources §5.7](datasources.md#57-readonly-datasources-flag-semantics-and-enforcement-layers)) and `global` (**admin-only; visible-disabled for everyone else**, workspaces D8: unchecked binds to the active workspace). The register action applies the SAME D8 rules as REST §9.1 (`DatasourceWorkspaceRules` — one component, two surfaces) and crosses the same registry save boundary. A register REFUSAL stays inline in the modal (the screen-local `htmx:responseError` path — the modal must not close over an error, so it deliberately carries no `HX-Retarget`).

The listing is workspace-scoped exactly like REST §9.2 (`listVisible`: active-bound + global, repository-level); a datasource bound to another workspace is absent, and its by-name test behaves as not-found. Rename is not offered — a datasource name is immutable (delete + re-create, blocked while referenced). The search covers every column the table renders (the §5.1 Search rule): name and the `readonly` badge, the dialect wire value, the workspace column (the bound name or the literal `global`), the JDBC URL, and the username — plus `description`, searchable though only the modal shows it.

**§4.13's workspaces screen** owns workspace lifecycle; this screen's Register button is hidden entirely when the caller is a non-admin and `member-datasources-enabled` is off (the demo shape — open datasource creation is an SSRF primitive from the server's network position).

### 4.6 Template List (the template EXPLORER — tree left, selected template right)

| Attribute | Value |
|---|---|
| URL | `GET /templates` |
| Auth required | Yes (`read` to browse; `author` to create) |
| Purpose | Browse the template tree, select a template to see its versions, filter by dialect and type, search |
| Design primitives | `.ds-badge`, `.ds-input`, `.ds-empty`, `.ds-button`, native `<details>`/`<summary>` + `static/css/template-tree.css` (structure and truncation only — every colour, size and gap is a token; the 041 two-pane layout math, `--header-height` viewport fill) |
| JS | The create modal's lifecycle (open/close, inline refusal, dialect-conditional-on-type), **and** `static/js/template-explorer.js`: selection, roving tabindex, and keyboard — expansion is still `<details>` + htmx, no JS of our own |
| htmx | Yes — see the fragment contract below |

A template name is a **path** ([template-hierarchy-design §4.1](template-hierarchy-design.md#41-grammar)), so this screen is a tree. **Folders are virtual**: a folder is a name prefix with no table, no column, no id and no CRUD ([§3.1](template-hierarchy-design.md#3-design-principles)). There is no "New folder", no rename, no move, no delete and **no empty-folder state** anywhere on this screen — a folder is derived per request from the live rows beneath it, so one with nothing beneath it does not exist to be rendered. `TemplateTreeRenderTest` asserts those absences, because an absence is exactly what a well-meaning future round removes without noticing.

**The layout (058).** Two panes, full height below the page header, on the app's wide canvas — the owner's spec was Windows file explorer: *"tree on the left and when click on the leaf, table should show up on the right."* LEFT (~30%, resizable with the browser's native `resize`, floor 260px): the tree, folders with carets, leaves with type/dialect badges, indentation per depth, the selected leaf highlighted. RIGHT (the remainder): the selected template — header (full path, badges, Open-in-editor) above the versions table (version, status badge, in-use count, created). Nothing selected: the quiet `Select a template` state, not a blank panel. **A selection swaps `#template-detail`'s innerHTML and nothing else** — the tree pane's DOM is untouched by a selection swap, which is the whole point of the layout; `TemplateExplorerRenderTest` pins this at the fragment-contract level (the detail fragment contains no tree markup, no tree swap target and no OOB swap, so nothing a selection returns could alter the tree).

**The fragment contract.** One route, `GET /partials/templates`, answers two shapes, chosen by the presence of `prefix`:

| Request | Fragment | Swap |
|---|---|---|
| no `prefix` | `partials/templates` — a dispatcher whose one root element is `#template-list-wrapper` in **both** presentations | the filter controls' `hx-target`, `outerHTML` |
| `prefix=acme/finance` (empty string = the root) | `partials/template-tree-level` — that ONE level: its direct sub-folders and its direct template children | the folder's own child container, `outerHTML` |
| `GET /partials/templates/versions?name={path}` | `partials/template-detail` — the SELECTED template: header (full path, badges, Open-in-editor, from `findLatest`) above `partials/template-versions`' table | `#template-detail`, `innerHTML` — the tree is untouched |

Every level is a **server-side prefix query** ([§8](template-hierarchy-design.md#8-repository-registry-loader)). The flat list is never shipped to the browser and no tree is assembled in JS, at any size ([§9.1](template-hierarchy-design.md#91-constraints-the-ui-inherits-normative--none-of-these-are-ui-choices)). A folder expands with `<details>`/`<summary>` — the browser owns open/closed state and the a11y semantics — and the request rides on the **summary** with `hx-trigger="click once"`, targeting the placeholder below it with `next`. The obvious alternative, the request on the placeholder with `hx-trigger="toggle from:closest details"`, works at the root and silently does nothing for a folder that arrived in a swap (measured on the demo stack: the nested level's request never fires); `click` needs no `from:` indirection and no non-bubbling event, and `<summary>` raises it for keyboard activation too. Nested level containers carry an id derived once, server-side, from the prefix (`TemplateBrowseModel.levelId`), so the placeholder a folder renders and the root of the fragment that replaces it cannot disagree; the ROOT level's id is the screen's long-standing `#template-list-wrapper`, so the tree inherits the §4.5/§5 swap contract rather than inventing a second one — and it sits inside the stable `#template-tree-pane`, so every swap replaces level CONTENT and the panes never move. Names never travel in a URL path segment — `prefix` and `name` are query parameters ([§9.6](template-hierarchy-design.md#96-addressing-the-name-never-travels-in-a-url-path-segment-normative-measured)).

**Keyboard and ARIA.** The root level's list is `role=tree` (a nested level's container is `role=group` under its folder's `treeitem`); search results are `role=listbox`/`option`. ArrowUp/ArrowDown move selection (the detail pane follows), ArrowRight expands a collapsed folder, ArrowLeft collapses an expanded one or goes to the parent, Enter opens the editor (the row's `data-editor-url`), Home/End jump to the ends. `template-explorer.js` owns `aria-selected`, `aria-expanded` (kept truthful through a capture-phase `toggle` listener) and the roving tabindex, initialising on load and after every htmx swap that lands in the left pane — selection is client state; the server renders the roles and seeds `aria-selected="false"`. Leaves and search rows carry `hx-sync="#template-detail:replace"`: a rapid keyboard sweep cannot race a stale detail load into the pane — the last selection replaces the in-flight request (keyboard loads are additionally debounced, so a sweep fires one request, not one per row).

**Browse and search are different presentations** ([§9.2](template-hierarchy-design.md#92-templates-browser--tree-presentation), decided). Browsing shows the tree. A non-empty `q` shows a **flat result list of full paths** in the LEFT pane — the same row shape as tree leaves, because the pane is 30% wide and a seven-column table is not what it is for — and selecting a result fills the right pane exactly as a tree click does. It is NOT a tree pruned to matching leaves, because pruning means walking the ancestors of every match, which is precisely the whole-list-in-the-browser work the tree exists to avoid, and a flat list of full paths is what someone searching `finance/agg` wants to see. Clearing `q` returns to the tree, by construction: the same dispatcher answers both.

**Paging.** Each level pages its own leaves through the shared §5 pager, targeting that level's own root, so `Showing N of M` is that level's truthful count and not the workspace's. A level renders that pager only when it can act (`offset > 0` or `hasMore`): a tree shows many levels at once and most hold a handful of rows, so an always-on "Showing 1 of 1" with two dead buttons is noise repeated down the whole screen. The flat search list keeps the unconditional pager — there the count is the answer to the search. Sub-folders are a `GROUP BY` over one path segment and are not paged; past 200 at one level the fragment says so rather than cutting silently. Flat legacy names sit at the tree root and are never reorganised — §4.5 there forbids renaming them.

**Filters.** `dialect` and `type` (046's column) are exact matches on the version row and travel with every level and pager request; both narrow the folder derivation too, so a folder whose whole subtree is filtered out is absent rather than empty. The search covers every rendered column (the §5.1 Search rule): id/path, display name, description, and the dialect badge's wire value — the dialect match is repository-level (`ILIKE` on the version's dialect), so a `sqlite` query finds templates whose names never mention it.

**Create** (`author`/`admin`) is a modal posting to `POST /partials/templates`, §5.1 Shape A: the success node closes the modal and the refreshed root level rides along out-of-band with the toast. It gains a **`type` selector** (`sql` default) and makes `dialect` conditional — required for `sql`, disabled and absent for `html` (the control is *disabled*, not merely hidden, so it does not post; the controller drops it either way and `chk_type_dialect` is the database's backstop). The name field's `pattern` and `maxlength` are **rendered from the server's own grammar** (`TemplateNameGrammar`, which reads the validator's `Regex` and its cap) — never a regex retyped beside it; the server validates every write regardless and its rejection is the one that counts ([§9.5](template-hierarchy-design.md#95-client-side-name-validation-is-a-convenience-never-an-authority)). There is no rename affordance on this form or anywhere else: `name` is a create-time input, full stop.

### 4.7 Template Editor

| Attribute | Value |
|---|---|
| URL | `GET /templates/editor?name={path}` (rest-api §8 addressing: the name never travels in a URL path segment) |
| Auth required | Yes (`read` to view; `author` to save or render a preview) |
| Purpose | Edit template body (Freemarker SQL), preview rendered SQL, manage versions |
| Design primitives | `.ds-card`, `.ds-button`, `.ds-code-block`, `.ds-form` + the 041 layout in `static/css/template-editor.css` — render context in a left rail (`--app-detail-width`), source column filling the viewport below the header (`--header-height` math), preview output below the editor at ~2/3 · 1/3 |
| JS | Light — tab switching (edit / preview), add/remove context rows, key-value ⇄ JSON toggle; the preview output is highlighted with the shared dependency-free SQL tokenizer (`js/pipeline-editor/sql-highlight.js`, 032) via `js/template-editor/preview.js` — the editable textarea is deliberately plain (highlighting an editing surface needs an overlay/contenteditable round of its own) |
| htmx | Yes — the version `<select>` swaps the source column (`hx-get="/partials/templates/editor/source?name={path}"`, the select's own `version` riding along, into `#template-source`, `outerHTML`) and **Edit** posts to `/partials/templates/editor/edit` (`#tpl-edit-refusal`, `innerHTML`; success answers `HX-Redirect`). "Render Preview" posts to `/partials/templates/render?name={path}&version={v}`, rendered into `#preview-output` |

Content:
- **Editor pane**: textarea with the Freemarker body — plain monospace by decision (041 D5: highlighting an editing surface means an overlay or contenteditable; not this round), sized to fill the viewport below the header and scroll inside itself rather than growing the page.
- **Description panel** (read-only in the preview column): the template's free-text `description`. Since a template declares no variables, this is the only in-app hint about what context it expects ([Templates §2.5](templates.md#2-design-principles)).
- **Render context input** — **free-form**. Templates do not declare their variables; the calling *pipeline's* `parameters` block is the single declaration point ([Templates §3.2](templates.md#32-field-reference)), so the editor has nothing to enumerate and MUST NOT try. Two equivalent input modes over the same underlying value, toggled by a tab:
  - **Key/value rows** (default): an "Add variable" button appends a `name` + `value` row pair; rows are collected into a JSON object.
  - **JSON textarea**: the same object, edited directly. Useful for nested/array values and for pasting a pipeline's parameter map.

  Whatever the active mode produces is posted verbatim as the `context` object of the render call ([REST §8.7](rest-api.md#87-validate-template-render-against-sample-context)). It is a scratch context for preview only — it is never stored on the template and has no effect on validation.
- **Preview pane**: rendered SQL output, below the editor in the source column sharing its width (041 D3), highlighted as SQL by the shared tokenizer (041 D4) and scrolling inside its pane. A reference to a variable the supplied context does not contain fails the render; the error envelope is rendered inline into `#preview-output` per §5.1 (this is a *preview* failure, not a save failure — save-time render checking lives on the **pipeline**, [Pipeline Contract §12.6](pipeline-contract.md#126-template-validations)).
- **Type and dialect**: rendered as read-only VALUES beside the id, never as controls. `type` is chosen at create and is immutable afterwards ([template-hierarchy-design §5.3](template-hierarchy-design.md#5-the-type-field)); the server refuses the write with `template.validation.type_immutable` either way, but a disabled `<select>` is re-enabled in devtools in one click, so the UI must not present a lock it does not own. There is likewise **no name field**: §4.5 there offers no rename, so a template's name appears on this screen only as text.
- **Version selector**: the dropdown's default is the **working version** — the DRAFT when one exists, else the current release ([Versioning §6](versioning.md#6-templates-same-lifecycle-plus-the-pin-rule)). Selecting a *different* entry **loads that version read-only**: its body in the preview surface (a `<pre>`, never a disabled textarea — a disabled control is re-enabled in devtools in one click), its version number, its RELEASED/DRAFT badge, and `released_at`/`released_by` when RELEASED. The editable textarea only ever carries the working version, so no selection can make a RELEASED row the write target — the invariant *"we never modify RELEASED"* is visible on the screen, not merely enforced by the server. The one way out of the read-only view is **Edit**, which does not decide anything itself: it posts the name and the selected version, and the server applies the lifecycle rule already in force — a draft exists ⇒ **that draft opens and nothing is written** (a second draft is refused by `uq_template_versions_one_draft` anyway, and a write would overwrite the author's in-progress draft with the body they were merely reading); otherwise the selected version is copied into a new draft through the same `TemplateDraftService` the REST `PUT /api/v1/templates` uses, and the editor switches to it. Copying is how "restore" works — a version is immutable, so an old one is re-published by drafting from it and releasing that draft. The precondition the copy is based on is the **current release's** hash, not the selected version's, because that is the row the create-draft guard reads.
- **Save**: no save control is rendered while a RELEASED version is displayed — writing is reachable only through Edit, and only onto a draft. (The screen ships no save affordance at all today: the body is edited and the draft is written through `PUT /api/v1/templates`, the `id` in the body — rest-api §8; a save here would run parse-level validation only, [Templates §7.1](templates.md#7-validation-rules).)
- **Imports panel**: the `imports` array as `{id, version, alias}` rows with links to each library template. The body never contains `<#import>` — the alias shown here is what the body calls ([Templates §6](templates.md#6-library-templates)).

### 4.8 Execution History

| Attribute | Value |
|---|---|
| URL | `GET /executions` |
| Auth required | Yes (`read`) |
| Purpose | Browse past executions, filter by pipeline/status/date |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-input` |
| JS | None |
| htmx | Yes — filters (pipeline, status, date range), pagination (`hx-get="/partials/executions"` into `#execution-table`, `innerHTML`, with `hx-include="#execution-filters"` re-sending the filter form by id; the pager offsets are server-rendered into `hx-vals` via `th:attr` literal substitution) |

Content: table of executions (pipeline name, version, status badge, triggered_by, triggered_via badge, started_at, duration). Clickable → execution detail. This screen deliberately keeps its own `#execution-table` / `innerHTML` / `hx-include="#execution-filters"` contract rather than adopting the §5 outerHTML one — it satisfies every §5.1 guarantee (stable target, controls outside the fragment, spinner, toasts), and the pager's `hx-vals` offsets are server-rendered via `th:attr="hx-vals=|{...}|"` (a plain-attribute `[[...]]` inlining reaches the browser unprocessed — Thymeleaf processes inlining in text nodes, not attribute values).

### 4.9 Execution Detail

| Attribute | Value |
|---|---|
| URL | `GET /executions/{execution_id}` |
| Auth required | Yes (`read` + ownership of the execution; `admin` may view any. Cancelling a running execution requires `execute`) |
| Purpose | View execution metadata, node stats, result, replay events |
| Design primitives | `.ds-card`, `.ds-table`, `.ds-badge`, `.ds-code-block` |
| JS | Light — result preview pagination if large |
| htmx | Yes — result pagination (`hx-get="/partials/executions/{id}/result?offset=..."`), cancel (`hx-delete="/partials/executions/{id}"` — success is §5.1 Shape A: the cancelled-state badge swap plus an OOB toast; the 403/404/409 refusals are `ResponseStatusException`s answered with full error pages by `UiExceptionHandler` — a recorded gap for partial requests, not a toast) |

Content:
- **Header**: pipeline name + version, status badge, timing, triggered_by + via.
- **Node stats table**: per-node status, duration, rows_out, error.
- **Error details** (if failed): code, message, user_message, details JSON, doc_url link.
- **Cancel button** (only while the execution is `RUNNING`; `execute` scope + ownership): backed by `DELETE /api/v1/executions/{id}`, moving the execution to `ABORTED` ([REST §10.4](rest-api.md#104-cancel-execution)).
- **Result panel** — see below.
- **Event replay**: link to `GET /executions/{id}/events` (SSE stream replay).

#### Result panel (cursor-backed, TTL-bounded)

There is **one** result path and this screen uses it: every completed caller result is materialized in Redis and read back through the uniform cursor ([REST §7](rest-api.md#7-result-delivery)). There is no separate "small result" case, and no claim-check special-casing.

The panel has exactly three states, decided by the cursor response:

| Condition | Panel |
|---|---|
| Execution completed **within** its result TTL | Preview table (first page, `datapipelines.result.page-size-rows`) + pager + download links |
| Execution completed, TTL elapsed (`410 result.expired`) | Empty-state card: **"Result expired — re-run the pipeline to regenerate it."** with a "Re-run" button (`execute` scope). Node stats, timings and errors remain visible — only the rows are gone |
| Execution has no caller node, or failed | "This execution produced no caller result." (a pure write-back pipeline is legal and emits no result — [Pipeline Contract](pipeline-contract.md)) |

- **Preview / pagination**: each pager click is an htmx `GET /partials/executions/{id}/result?offset=…&limit=…` that swaps the table body; server-side it is the same cursor with the same ownership check. Row order is stable across pages because the result was fully materialized before the cursor existed.
- **Effective expiry** is shown next to the panel title (from the cursor's `expires_at`). Expiry is **fixed at result-write time** — paging through the preview does not extend it, so a long browsing session can hit the expired state mid-way; the panel then swaps itself to the expired card.
- **Download buttons** (JSON / CSV / Arrow) are the *same* cursor endpoint with a different `format` parameter — not three separate mechanisms. They are plain `<a href>` links to `/api/v1/executions/{id}/result?format={json|csv|arrow}` (the download carve-out in §2.1), and they are hidden once the TTL has elapsed.

For anything that must outlive the TTL, the answer is not a longer TTL: write it back with `output.target: "datasource"` ([REST §7.1](rest-api.md#71-model)).

### 4.10 API Keys

| Attribute | Value |
|---|---|
| URL | `GET /settings/api-keys` |
| Auth required | Yes — **any authenticated principal**, own keys only (no scope requirement; see [Auth §7.6](auth.md#76-scope--operation-matrix-authoritative)) |
| Purpose | Issue, view, revoke API keys for agents |
| Design primitives | `.ds-table`, `.ds-button`, `.ds-modal`, `.ds-badge`, `.ds-code-block` |
| JS | Light — copy-to-clipboard for newly created key |
| htmx | Yes — generate key modal (`hx-post="/partials/api-keys"`, the created-key fragment into `#keyCreated`, plus §5.1 Shape A out-of-band pieces: the refreshed `#keys-table` — swapped at TABLE level, because a `tbody` OOB element nested in the response dies in the browser's fragment parser — and an info toast POINTING at the panel), revoke (`hx-delete="/partials/api-keys/{id}"`, rebuilt rows into `#keys-table-body` plus a success toast) |

Server-side these partials delegate to the same application service as [REST §16.1](rest-api.md#161-api-keys-any-authenticated-principal--own-keys-only) (`GET`/`POST /api/v1/auth/api-keys`, `DELETE /api/v1/auth/api-keys/{key_id}`) — identical validation and identical ownership scoping, HTML instead of JSON.

Content:
- **Key list**: name, key prefix (`dpk_ABCDEF...`), scopes badges, created_at, last_used_at, expires_at, status (active/revoked). Scoped to the caller's own keys — the list endpoint never accepts a user filter, and admins do not get a cross-user view of keys here.
- **Generate button**: opens modal with name input, scope checkboxes, expiration date picker. On submit: creates key, shows plaintext key **once** with copy button + warning — the panel PERSISTS inline (§5.1's hard rule); the same response refreshes the key table out-of-band and points an info toast at the panel (the toast never carries the plaintext).
- **Scope checkboxes are filtered to the caller's own scopes.** A key's scopes MUST be a subset of its creator's scopes at issue time ([Auth §7.4](auth.md#74-issuance)) — an `author` session cannot mint an `admin` key. The UI renders only the checkboxes the caller is entitled to; the server independently rejects a superset with `403 auth.scope.insufficient` (the checkbox filter is convenience, the server check is the guard). Default selection: `read`.
- **Revoke button**: per-row, confirms, sets `is_revoked = true`. Revocation is effective within the validation-cache TTL (~60s, [Auth §7.3](auth.md#73-validation-flow)) — the confirmation copy says so rather than implying instant global effect.
- **Never shows**: the full key (only the prefix after creation), the hash.

### 4.11 User Settings

| Attribute | Value |
|---|---|
| URL | `GET /settings` |
| Auth required | Yes — any authenticated principal, own profile only (no scope requirement) |
| Purpose | Profile info, theme preference |
| Design primitives | `.ds-card`, `.ds-avatar`, `.ds-select` |
| JS | None |
| htmx | Yes — theme switch (`hx-patch="/partials/profile/theme"`, the `<select name="theme">` posts its own value on `change`) |

Content:
- **Profile**: avatar (`profile_picture_url`), display name, email — all read-only here; they are owned by the OIDC provider and refreshed at each login ([Auth §4.2](auth.md#42-user-provisioning)).
- **Provider badge**: renders the **configured provider's `display-name`** for `users.provider`, resolved from the `ClientRegistrationRepository` at render time — exactly like the login buttons (§4.1). `provider` is free text, whatever registration name the deployment configured ([Auth §4.1](auth.md#41-user-entity)); no provider name is hardcoded in a template, and an unrecognized value falls back to the raw `provider` string rather than a guess.
- **Theme selector**: dropdown of the vendored design-system themes. On change, htmx `PATCH /partials/profile/theme` → the server **UPDATEs the `users` row** (`theme_preference`) for the authenticated user and returns an out-of-band swap of the layout's `#theme-link` stylesheet element, so the new theme applies immediately without a page reload (all tokens cascade — [Pipeline Editor §3.4](pipeline-editor.md#34-design-system-acmedesign-tokens)). The confirmation is a §5.1 toast (Shape B — the select fires `hx-swap="none"` and has no content target).
  - **Persisted on the user row, never in session state.** The server is stateless behind a load balancer with no sticky sessions (§2 principle 6) — a session-held preference would be lost on the next request that lands on another instance and would not survive re-login. `users.theme_preference` is nullable; `NULL` means "use the deployment default", `datapipelines.ui.theme` ([Configuration](configuration.md)).
  - Submitted values are validated against the vendored theme list; an unknown theme is rejected (`400`) and surfaced as a danger toast (§5.1 Shape C — the refusal keeps its status and is retargeted at `#toast`, admitted by `toast.js`'s `bridgeErrors`) — never written through.
- **Session info**: JWT issued at, expires at. "Logout" button → `POST /logout` ([REST §16.4](rest-api.md#164-logout-browser-session)), a normal CSRF-protected form post, not an htmx swap.

### 4.12 Admin: User Management (admin scope only)

| Attribute | Value |
|---|---|
| URL | `GET /admin/users` |
| Auth required | Yes (`admin`) |
| Purpose | View all users, activate/deactivate, grant/revoke admin |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-button` |
| JS | None |
| htmx | Yes — search/pagination (`hx-get="/partials/admin/users"`), activate/deactivate and admin grant/revoke (`hx-patch="/partials/admin/users/{id}/{action}"`, row-level swap) |

Content: table of all users (email, display_name, `is_active` and `is_admin` as `.ds-badge` variants — success/danger for status, primary/default for role — local-access status). Admin can toggle `is_active` and `is_admin` per user, and — for local accounts ([Auth §5A.1](auth.md#5a1-accounts)) — create local users, reset passwords, disable local access, and clear lockouts.

- Partials delegate to [REST §16.3](rest-api.md#163-user-administration-admin-scope) (`activate`, `deactivate`, `grant-admin`, `revoke-admin`), which writes the `auth.user.*` audit events. Every row action keeps its `#user-row-{id}` outerHTML swap and reports the outcome as a success toast naming the action and the user's email (§5.1 Shape A).
- **Create local user** (rendered only when local accounts are enabled): email + optional display name; the server generates a random one-time password shown to the admin exactly once (out-of-band notice — PERSISTENT and inline per §5.1's hard rule; the success toast only points at it) with `must_change_password = TRUE` — there is no email flow, so the admin conveys it out-of-band ([Auth §5A.1](auth.md#5a1-accounts)). A taken email answers `409` and an invalid email `400`, both as danger toasts (§5.1 Shape C) — before the toast bridge existed these refusals were invisible: htmx never swapped the 4xx bodies and the screen had no error listener.
- **Reset PW** issues a new one-time password under the same rules (and clears any lockout); **Disable local** clears the hash (account becomes OIDC-only); **Unlock** clears the lockout only. The `Local` column shows `local`, `local · locked`, or `—` (OIDC-only).
- Deactivation copy states the effect window: existing JWTs and API keys stop working within the liveness-cache TTL (~60s), not instantly and not at JWT expiry.
- Scopes are derived, not assigned, in v1: `is_admin` → `admin`, every other active user → `author` ([Auth §7.5](auth.md#75-scopes)). So the "grant admin" toggle *is* the scope control — there is no per-user scope editor to build.

### 4.13 Workspaces (workspaces design §9)

| Attribute | Value |
|---|---|
| URL | `GET /workspaces` |
| Auth required | Yes (`author` for the create/manage actions — [Auth §7.6](auth.md#76-scope--operation-matrix-authoritative); ownership/mode gates enforced server-side per [REST §17](rest-api.md#17-workspace-endpoints)) |
| Purpose | Create/join workspaces per provisioning mode; manage members of owned workspaces; switch the active workspace |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-button`, `.ds-input`, `.ds-card` |
| JS | One `onchange` submit on the navbar switcher (`<noscript>` fallback button included) |
| htmx | No — plain CSRF-protected form posts with `redirect:` outcomes (`?ok=`/`?error=` query state, the login screen's idiom) |

Content, in order: a **create form** (name + display name; hidden under `closed` mode for non-admins — `workspace.creation_forbidden`), a **joinable list** (`open-join: true` only — each card has a Join button; hidden when `open-join` is off), **your workspaces** (name, role badge, active marker, Switch/Delete actions), and per OWNED workspace a **member table** (name/email/role) with add-by-email and remove actions. Removing a member with the `owner` role is refused server-side (`workspace.in_use`, `blocked_by: owner_membership`) — ownership transfer is not a v1 operation. Delete bounces back with the `in_use` counts while content remains.

The **switcher in the navbar** (§3) drives the active workspace; the screen's Switch buttons POST the same `/workspace/switch`. Expected refusals and successes alike render as §5.1 toasts — the redirect contract (`?ok=`/`?error=` query state, the login screen's idiom) is unchanged, but the layout renders the flash into the `#toast` stack server-side instead of the old inline banner (error copy verbatim from the reviewed 022/F8 wording); the generic error page is reserved for the unexpected (§6).

### 4.14 Change password (local accounts)

| Attribute | Value |
|---|---|
| URL | `GET /settings/password` |
| Auth required | Yes (any authenticated session with a local password) |
| Purpose | Self-service password change — and the one screen the §5A.4 forced-change gate lets a `must_change_password` user reach |
| Design primitives | `.ds-card`, `.ds-input`, `.ds-button--primary` |
| JS | None |
| htmx | Yes — `hx-post="/partials/account/password"` (success is §5.1 Shape B, toast-only; failures stay inline in `#password-change-result` via the screen's own 4xx listener) |

Content: current / new / confirm fields with the policy floor stated inline (at least 12 characters, [Auth §5A.5](auth.md#5a5-enumeration-resistance-and-the-password-policy)). A `must_change_password` user additionally sees the one-time-password warning banner — every other route redirects here until the change succeeds ([Auth §5A.4](auth.md#5a4-forced-password-change)). An account without a local password (OIDC-only) sees an explanatory note instead of the form. Success is a §5.1 toast (Shape B); failure outcomes are field-level/credential validation and stay INLINE in `#password-change-result` — wrong current password, policy violation, confirmation mismatch — delivered by the screen's own `htmx:responseError` listener, because htmx never swaps 4xx and these refusals deliberately carry no `HX-Retarget` (this is the one screen where Shape B and inline errors coexist); the forced-change gate releases on the next navigation.

### 4.15 Marketing site (public)

| Attribute | Value |
|---|---|
| URL | `GET /` |
| Auth required | No — the one public page (with `/site/**` assets); everything else defaults to authenticated |
| Purpose | The product marketing page — hero, how-it-works, screenshots, security, roadmap |
| Design primitives | The vendored design system via `/vendor/design-system/**` (the same copy the app serves; the retired `website/` directory carried a second vendored copy) |
| JS | `static/site/js/site.js` — theme toggle + copy-to-clipboard only; fully readable without it |
| htmx | No |

Content: a single static page (`templates/site/index.html` + `static/site/**`), served by the app since v1.15 — the marketing site and the product are one deployment (owner decision 2026-08-31). The only dynamic fact, the MCP tool count, is baked at render time from `McpToolCatalog` (a compile-time constant — no DB access on any public route). Defence is `Cache-Control: public` on both `/` and `/site/**`, deliberately NOT the login rate limiter (OPEN-ITEMS T46: its `remoteAddr` key is the load balancer's address behind the documented deployment, so a limiter would let one client 429 the homepage). Signed-in users hitting `/` get the marketing page too — no auto-redirect; the dashboard is one nav link away at `/dashboard`. Emergency static fallback: `./gradlew :modules:web:websiteExport` renders the same template with facts baked ([Deployment §6.7](deployment.md#67-marketing-site--in-product-docs)).

### 4.16 Documentation (in-product)

| Attribute | Value |
|---|---|
| URL | `GET /docs` (grouped index), `GET /docs/{slug}` |
| Auth required | Yes — session only (no API-key access; docs are a human surface) |
| Purpose | The operations manual and spec set for the version being run — packaged into the jar, so the docs can never describe a different server |
| Design primitives | `.ds-card` (index); `.doc-body` typography from `static/css/docs.css` (token-derived) |
| JS | None |
| htmx | No |

Content: the packaged `docs/*.md` set (the exclusion policy — `docs/superpowers/`, `semantic-layer-research.md`, `SPEC-REVIEW-2026-08.md` — lives in `modules/web/build.gradle.kts`), grouped Operations manual / Contracts / Reference, rendered to HTML once at startup. Relative links resolve in-app to `/docs/{slug}` or rewrite to their canonical GitHub URL; heading anchors use the same slug algorithm `scripts/docs-audit.sh` validates against. Rendered markdown is inserted as data (`th:utext`) — `${...}` placeholders in config examples display verbatim.

---

## 5. htmx Usage Pattern

Standard pattern for list/filter/pagination. The page (`GET /pipelines`) renders the shell **and** the initial fragment; every subsequent refresh hits the partial endpoint (`GET /partials/pipelines`) and swaps the fragment only.

Filter values are carried by **form fields plus `hx-include`** — never by string-interpolating a query parameter into an `hx-get` URL. Interpolation is evaluated once, server-side, at page render; it would freeze whatever the search box contained at that moment (usually empty) and every later request would silently drop the filter.

```html
<!-- Filter controls. Each control issues the request; hx-include re-sends the whole group,
     so the search term, the datasource filter and the offset always travel together. -->
<div id="pipeline-filters">
    <input type="search" name="q" class="ds-input"
           placeholder="Search pipelines…"
           hx-get="/partials/pipelines"
           hx-trigger="keyup changed delay:300ms, search"
           hx-include="#pipeline-filters"
           hx-target="#pipeline-results"
           hx-swap="outerHTML"
           hx-indicator="#pipeline-loading">

    <select name="datasource" class="ds-select"
            hx-get="/partials/pipelines"
            hx-trigger="change"
            hx-include="#pipeline-filters"
            hx-target="#pipeline-results"
            hx-swap="outerHTML"
            hx-indicator="#pipeline-loading">
        <option value="">All datasources</option>
        <option th:each="d : ${datasources}" th:value="${d.name}" th:text="${d.displayName}">pg-main</option>
    </select>

    <span id="pipeline-loading" class="ds-spinner htmx-indicator" aria-hidden="true"></span>
</div>

<!-- The swapped fragment: table + pager, returned whole by /partials/pipelines -->
<div id="pipeline-results">
    <table class="ds-table">
        <tr th:each="p : ${pipelines}">
            <td><a th:href="@{'/pipelines/' + ${p.id} + '/editor'}" th:text="${p.displayName}">Name</a></td>
            <td th:text="${p.description}">Desc</td>
            <td><span class="ds-badge ds-badge--neutral" th:text="'v' + ${p.currentVersion}">v1</span></td>
        </tr>
    </table>

    <!-- Pager: the next offset is a server-rendered value, not a client-side expression -->
    <button th:if="${hasMore}" class="ds-button ds-button--secondary"
            hx-get="/partials/pipelines"
            hx-include="#pipeline-filters"
            th:attr="hx-vals=|{&quot;offset&quot;: ${nextOffset}}|"
            hx-target="#pipeline-results"
            hx-swap="outerHTML"
            hx-indicator="#pipeline-loading">
        Load more
    </button>
</div>
```

Server returns a partial HTML fragment (the `#pipeline-results` div), htmx swaps it in. No full page reload. No client-side rendering. No JSON parsing.

Two rules this example encodes, applicable to every list screen:

1. **Parameters come from named form fields**, gathered with `hx-include`. Values the user never edits (the next `offset`) are server-rendered into `hx-vals` with `th:attr`.
2. **The swap target is a wrapper that contains everything that changes** — table *and* pager — so one `outerHTML` swap keeps them consistent. Swapping only the `<table>` leaves a stale pager behind. On the screens that swap `outerHTML`, the wrapper is the list **fragment's own root element** (`#pipeline-list-wrapper`, `#template-list-wrapper`, `#datasource-list-wrapper`): the id must live on the fragment root, never on the page's host div — `th:replace` removes the host, so an id written there never reaches the DOM and every swap targets nothing.

**The shared pager.** Every list screen renders the one fragment `partials/pager :: pager(targetId, prevUrl, nextUrl, offset, hasMore, shown, total)`. The fragment never builds URLs: Thymeleaf link expressions take literal parameter names, so a per-screen filter set cannot be splatted into `@{...}` — each caller builds `prevUrl`/`nextUrl` with its own `th:with` (keeping that screen's filters in the query string) and passes the finished strings. `total` is nullable: a screen that computes no count renders `Showing N` rather than `Showing N of M`. Previous is disabled at `offset == 0`, Next when `hasMore` is false. The executions screen is the deliberate exception — its `#execution-table` / `innerHTML` contract is documented in §4.8.

### 5.1 Standard States

Every list, panel and form on these screens implements the same three states. They are layout-shell concerns, specified once here rather than per screen.

**Empty state.** When a collection legitimately has zero rows, the partial returns a `.ds-empty` block — `.ds-empty-title` naming what is missing, `.ds-empty-description` with the follow-up, and `.ds-empty-actions` for the action if the user has scope for it ("No pipelines yet — Create pipeline"). These are the only empty-state classes; `.ds-empty-state` is **not a class** — no stylesheet defines it (four templates once used it and every pixel came from the inline styles beside it). Distinguish the two empties: *nothing exists* gets the create action; *nothing matched the filter* gets "No pipelines match "…" — Clear filters". An empty table with only a header row is not an acceptable empty state.

**Search.** A screen's server-side search covers every column that screen renders; where a column is derived, the search matches the rendered text (a dialect enum's wire value, an unbound workspace's `global` literal, a `readonly` restriction badge). A search that silently ignores a visible column reads as "no results" to the user.

**Loading state.** htmx's own indicator mechanism: `hx-indicator` points at an element carrying `.htmx-indicator`, which htmx toggles to `.htmx-request` for the duration of the request.
- List/panel refreshes use an inline `.ds-spinner` next to the control that triggered them (see §5 above).
- Buttons that mutate (`Save`, `Generate key`, `Test connection`) additionally set `hx-disabled-elt="this"` so the action cannot be double-submitted.
- Indicators are CSS-only (opacity/visibility transitions on `.htmx-request`), so there is no layout shift and no JS.

**Error rendering.** A partial request that fails returns the **standard REST error envelope** ([REST §4.2](rest-api.md#42-error-envelope)) rendered into an HTML fragment — the same `code` / `message` / `user_message` / `correlation_id`, not a bespoke error format. No htmx extension is loaded: htmx never swaps 4xx/5xx responses on its own (`responseHandling` maps `[45]..` to `{swap: false}`), so a refusal that should surface as a toast keeps its real 4xx status, carries the `partials/toast-oob` fragment as its body, and sets the `HX-Retarget: #toast` + `HX-Reswap: beforeend` response headers; `toast.js`'s `bridgeErrors` listener flips `shouldSwap` on `htmx:beforeSwap` — only when the server asked for `#toast` by header, so an ordinary error behaves exactly as before (Shape C under **Notifications**).

- The retarget leaves the success target untouched — a failed refresh never blanks the panel it was going to replace.
- The server renders the envelope into a `.ds-toast-danger` fragment: `user_message` as the headline, `code` and `correlation_id` in small text (the correlation id is what a user quotes in a support request), and `doc_url` as a link when the envelope carries one.
- **Field-level validation errors** (`400` with per-field `details`) are the exception: they render inline next to the offending inputs, because a toast that vanishes is the wrong place for "this field is required".
- **Modal-scoped errors** stay in the modal: a screen whose error must not dismiss its context (the §4.5 register modal) keeps its own `htmx:responseError` path and does NOT retarget to the stack.
- **`401`** is not a toast — the partial responds with `HX-Redirect: /login?expired=true`, which sends the browser to the login page (§6). Rendering a login form inside a swapped fragment would nest a page inside a panel.
- **`403`** renders a toast and, where the affordance should not have been visible at all, the swap also removes it — a scope-gated action becoming visible is a UI bug, and the toast copy says "you don't have permission", never "something went wrong".

**Notifications.** Success and failure alike are reported as **toasts**: the layout carries a single `#toast` stack (`.ds-toast-stack`, `aria-live="polite"`, top-right below the header), and a partial that has something to report returns the server-rendered `partials/toast` fragment (`.ds-toast` + one of the design-system variants `success` / `danger` / `warning` / `info`, title + body + close button) — the panel, table, or form that fired the request is NOT re-rendered, so a notification can never break layout. `static/js/toast.js` is loaded once by the layout and owns the whole lifecycle: a `MutationObserver` arms each appended toast with an auto-dismiss timer (6s) and its close button; exit is the design system's own `.exiting` animation. Markup is built client-side in exactly one place, `DpToast.show`, for events that carry no HTTP response; everything else is server-rendered (the 025 theme-swap rule: fragments are rendered by Thymeleaf).

**The hard rule.** A toast auto-dismisses after 6s, so it NEVER carries anything the user must keep. One-time secrets (`partials/api-key-created.html`, the admin one-time-password notice) stay persistent inline; a toast may POINT at them ("shown below, copy it now") and nothing more. Field-level validation stays inline at the form.

**Delivery shapes.** htmx 2.0.10 swaps the CHILDREN of an out-of-band element for any swap style other than `outerHTML` ("we use the content of the node, not the node itself" — `oobSwap` in `htmx.js`), so a toast bound for the stack is always wrapped: `partials/toast-oob :: oob(variant, title, message)` renders the `hx-swap-oob="beforeend:#toast"` wrapper with the `.ds-toast` as its child. Putting `hx-swap-oob` on the `.ds-toast` itself appends the close button, title and body BARE into the stack — no `.ds-toast` node, no arming, no auto-dismiss, and no error anywhere (`ToastOobFragmentRenderTest` pins the nesting).

- **Shape A — content + toast.** The response is the normal swap content with the `toast-oob` fragment spliced in; the triggering control keeps its `hx-target`/`hx-swap`.
- **Shape B — toast only.** The control sets `hx-swap="none"`; the response body is the `toast-oob` fragment alone. No response headers.
- **Shape C — refusal as toast.** The response keeps its real 4xx status, its body is the `toast-oob` fragment, and it sets `HX-Retarget: #toast` + `HX-Reswap: beforeend`; `bridgeErrors` (see **Error rendering**) is what lets htmx swap it.
- **Shape D — client-originated (the exception, not a convenience).** `DpToast.show(variant, title, message)` in `toast.js` builds the one toast shape and appends it to `#toast`, for events that arrive with no HTTP response to attach an OOB swap to — the pipeline editor's SSE terminal events (`pipeline_completed`, `execution_aborted`, `pipeline_failed`). It is built with `createElement` + `textContent`, never `innerHTML`: titles and bodies carry abort reasons, node ids and error text, none of which is trusted markup. Any outcome that arrives on an HTTP response uses A, B or C — nothing else in the codebase gets a second toast builder. Both markup definitions — `partials/toast.html` and `show` — assert ONE contract: root classes `ds-toast ds-toast-{variant}`, `role="status"`, exactly three children in the order close / title / body, pinned by `ToastMarkupParityTest` (server) and `toast.test.mjs` (client).

---

## 6. Error Pages

These are **full-page** errors — the result of a browser navigation to a page URL. Errors raised by an htmx partial swap never navigate here; they follow the toast/inline rule in §5.1. The single bridge between the two is `401` on a partial, which returns `HX-Redirect: /login?expired=true` and lands on the first row below.

| Page | URL | Content |
|---|---|---|
| 401 (Unauthorized) | `GET /login?expired=true` | Login page with "Your session has expired" message |
| 403 (Forbidden) | `GET /error?status=403` | "You don't have permission to access this page" + link back to dashboard |
| 404 (Not Found) | `GET /error?status=404` | "Page not found" + link to dashboard |
| 500 (Server Error) | `GET /error?status=500` | "Something went wrong" + correlation_id for support |
| Login errors | `GET /login?error={code}` | Login page with error message (domain_not_allowed, inactive, oidc_error) |

All error pages use the design system's `.ds-card` with appropriate `.ds-text--danger` or `.ds-text--warning` classes.

---

## 7. Future Screens (Not in v1)

| Screen | When |
|---|---|
| Pipeline create wizard (guided) | v2 — drag-and-drop graph editor |
| Template library browser | v2 — browse/import community library templates |
| Execution comparison | v2 — compare two executions side by side |
| Webhook management | v2 — configure webhook subscriptions for execution events |
| Audit log viewer | v1.1 — searchable audit log UI for admins |
| System metrics dashboard | v1.1 — Micrometer metrics visualization |

---

## Appendix A: Change Log

| Date | Version | Author | Change |
|---|---|---|---|
| 2026-09-02 | v1.17 | template tree UI (047) | §4.6 rewritten as the template **tree**: the one-route/two-shape fragment contract (`/partials/templates` with and without `prefix`, plus `/partials/templates/versions`), levels as server-side prefix queries with no client-side tree assembly at any size, `<details>`-driven lazy expansion with no JS of our own, per-level paging through the shared §5 pager against each level's own derived id (the ROOT level's id stays `#template-list-wrapper`, so the existing swap contract carries over unchanged), and the decided **browse-vs-search** rule — a non-empty `q` is a FLAT list of full paths, not a pruned tree. The four §9.1 absences are recorded as absences and guarded by render assertions: no folder CRUD, no empty-folder state, no `type` control on the edit form, no rename affordance anywhere. The list screen gains a `type` filter (046's column) and a real Create modal (§5.1 Shape A) whose name `pattern`/`maxlength` are RENDERED FROM the server's grammar rather than retyped beside it, and whose `dialect` is disabled-and-absent for `type=html`. §4.7 records `type`/`dialect` as read-only values on the editor. §4.4 records the pipeline editor's read-only template reference (one-line truncation with the full path on `title`, at both call sites and in the `template-missing` state) **and the rule that a future template picker reuses §4.6's prefix fragment rather than building its own client-side tree** — written on the screen the picker would be built on. |
| 2026-09-02 | v1.19 | template explorer layout (058) | §4.6 rewritten as the template **explorer**: two panes, full height below the page header (the 041 layout math) — tree LEFT (~30%, natively resizable, 260px floor) and the SELECTED template RIGHT (header with full path/badges/Open-in-editor above the versions table; quiet `Select a template` empty state). The owner's spec was Windows file explorer, not a refinement of 047's full-width accordion. **A selection swaps `#template-detail`'s innerHTML and nothing else** — pinned at the fragment-contract level by the new `TemplateExplorerRenderTest` (the detail fragment contains no tree markup, no tree swap target, no OOB). `/partials/templates/versions` now answers `partials/template-detail` (header from `findLatest` + 047's versions table unchanged; a dead name renders a quiet not-found). Search renders IN THE LEFT PANE — full-path `role=listbox` rows that select exactly like tree leaves (047's browse-vs-search rule and the §4.4 future-picker note both survive). Keyboard per the spec: up/down moves selection, right/left expands/collapses, Enter opens the editor — `static/js/template-explorer.js` owns selection, `aria-selected`/`aria-expanded` and the roving tabindex (root level is `role=tree`, nested levels `role=group`); leaves carry `hx-sync="#template-detail:replace"` so a keyboard sweep cannot race a stale detail load. Everything underneath 047 is unchanged: server-side prefix levels one request per expansion, virtual folders, the four absences, per-level paging, filters, the create modal. |
| 2026-09-02 | v1.18 | version selection in the template editor (054) | §4.7: the version dropdown was inert (it blanked the query string and reloaded the same version). Selecting a version now LOADS it read-only — body in the preview surface as a `<pre>` (never a disabled textarea), version number, RELEASED/DRAFT badge, `released_at`/`released_by` — swapping the new `#template-source` root via `GET /partials/templates/editor/source` (§5's idiom: the page paints the same fragment the swap returns). The editable textarea carries the working version and nothing else, so no selection can make a RELEASED row the write target. **Edit** (`POST /partials/templates/editor/edit`) decides nothing itself: a draft exists ⇒ that draft opens and NOTHING is written; otherwise the selected version is copied into a new draft through the same `TemplateDraftService` the REST write uses, based on the CURRENT RELEASE's hash. The §4.7 Save bullet now states the honest position: no save affordance is on the screen, and none is rendered while a RELEASED version is displayed. |
| 2026-08-31 | v1.16 | recurrence defect round (034) | §4.5: the OOB whole-table rule recorded beside the swap-root rule — a table partial travels as a whole `<table>` on any out-of-band path; a `<tbody>` carrying `hx-swap-oob` nested in a `<div>` is silently discarded by the browser's fragment parser (030 F-1, previously known only from the §4.10 changelog note). Doc-only; a mechanical guard was judged disproportionate (the shape is only visible to a real HTML parser — a regex over templates cannot tell an OOB `<tbody>` from a legitimate one inside a `<table>`). |
| 2026-08-31 | v1.15 | website + docs in-app (033) | §4.2 Dashboard moved from `/` to `GET /dashboard` — `/` is now the public marketing site (new §4.15, app-served, cache-defended per OPEN-ITEMS T46, no rate limiter); new §4.16 Documentation — the packaged spec set rendered in-product at `/docs` (session-only), with the §A link-rewrite rule (packaged slug or canonical GitHub URL, never a dead relative href) and `th:utext` doc-body insertion. Navbar gains the Docs entry; error pages and login/workspace-switch redirects point at `/dashboard`. The root `README.md` website pointer and the `website/` directory are gone (the app's vendored design system is the single copy). |
| 2026-08-05 | v1.0 | initial draft | UI screens inventory: 12 screens (login, dashboard, pipeline list/editor, datasource list, template list/editor, execution history/detail, API keys, user settings, admin users), htmx patterns, error pages |
| 2026-08-07 | v1.1 | consistency campaign | Per [SPEC-REVIEW-2026-08.md](SPEC-REVIEW-2026-08.md) §2.12: route convention §2.1 (pages / `/partials/**` / `/api/v1/**`, htmx never calls the JSON API) and all `hx-*` endpoints re-pointed at `/partials/**` incl. §4.10 API keys [1]; template-editor context form replaced with free-form key-value/JSON input — templates no longer declare variables [1b, D3]; §5 htmx example fixed (`hx-include` + `th:attr` `hx-vals` instead of `${q}` interpolation) [2]; §4 scope column declared a view of the authoritative [Auth §7.6](auth.md#76-scope--operation-matrix-authoritative) matrix, datasource test corrected to `author`, key scopes ⊆ creator's scopes [3, D15]; §4.11 theme preference persisted on the `users` row via `PATCH /partials/profile/theme`, not session state [4]; §4.11 provider badge renders the configured provider `display-name` [5]; §4.9 result panel rebuilt on the uniform cursor with the TTL-expired state and `format`-parameter downloads [6, D9]; new §5.1 standard states (empty / loading via `hx-indicator` / errors via the `response-targets` extension into `#toast`) [7]; CSRF via `dp_csrf` cookie + `DP-CSRF-Token` header wired in the layout [D10] |
| 2026-08-28 | v1.9 | workspaces surfaces slice | New **§4.13 Workspaces** screen (create per mode, open-join, owned-workspace member management, switch) + navbar **workspace switcher** (§3: POST /workspace/switch re-stamps the session claim; hx-headers carries DP-Workspace for partials). §4.5 datasource list re-grounded: workspace-scoped listing, workspace/readonly columns, register modal with the D8-gated `global` (admin-only, visible-disabled) and `readonly` checkboxes, Register hidden for gated-off members. |
| 2026-08-30 | v1.11 | datasources SPA table + toasts | §4.5: search/dialect/pager re-fetch only the list fragment into the stable `#datasource-list-wrapper` swap root (the id moved onto the fragment root — it previously died with the page's placeholder div); the connection test result is a §5.1 toast, ending the row-swap/"Back to list" contract that broke the table layout; the dead View button (REST JSON via hx-get) removed. New §5.1 **Notifications** state: `#toast` stack, server-rendered `partials/toast`, layout-global `toast.js` lifecycle. |
| 2026-08-30 | v1.10 | local password auth | §4.1 Login: local form + divider + provider buttons, only enabled methods render; `credentials`/`locked` banners join the `?error=` idiom. §4.12 admin users: create local user (one-time password shown once), reset, disable local, unlock; `Local` column. New §4.14 Change password — the §5A.4 forced-change screen. |
| 2026-08-31 | v1.12 | table component rollout + live list controls | §4.3/§4.6: the pipelines and templates swap roots moved onto the fragment roots (`#pipeline-list-wrapper` / `#template-list-wrapper`) — the ids previously died with the page's `th:replace`d host div, so both pagers had never worked — and the search inputs gained their `hx-*` wiring (they were inert). §4.3's unimplementable datasource filter was deleted (deferred: `PipelineRecord` carries no datasource; serving it needs a join through the pipeline definition). New §5 **shared pager** fragment (`partials/pager`, caller-built URLs, nullable `total`). New §5.1 **Search** rule: a screen's search covers every rendered column — datasources search now matches URL, username, dialect wire value and workspace; template search matches the dialect column (repository-level ILIKE). `.ds-table`/`.ds-badge`/`.ds-empty` adopted on the three list partials, API keys, admin users (rows are Kotlin-built), workspaces and the template editor's imports table; the undefined `.ds-empty-state` class is gone. §4.8 keeps its `#execution-table`/`innerHTML` contract; its pager's `hx-vals` offsets are now server-rendered via `th:attr` (they previously reached the browser as an unprocessed `[[...]]` literal). Two new mechanical guards: no unquoted literal inside a `th:attr` assignation, and every `hx-target` id a rendered list page references must exist in that page. |
| 2026-08-31 | v1.13 | toast application rollout | Every mutation now reports its outcome through the §5.1 toast — successes AND refusals. §5.1 rewritten: the `response-targets` prescription is gone (the extension was never loaded; the layout's `hx-target-error` was dead config and is removed) and replaced by the four delivery shapes — A (content + OOB toast), B (toast-only, `hx-swap="none"`), C (refusal: real 4xx + `HX-Retarget: #toast` + `HX-Reswap: beforeend`, admitted by `toast.js`'s new `bridgeErrors`, the twelve-line `htmx:beforeSwap` bridge that exists because htmx never swaps 4xx/5xx on its own), and D (the ONE client-side builder `DpToast.show`, for stream-borne events with no HTTP response; `createElement` + `textContent` only). Toasts bound for the stack travel WRAPPED in the new `partials/toast-oob` fragment, because htmx swaps a non-`outerHTML` OOB element's children, not the element. The hard rule is carved into §5.1: a 6s toast never carries anything the user must keep — one-time secrets stay in their persistent inline panels and toasts only point at them. Previously INVISIBLE refusals are now delivered: admin user-create 400/409 (§4.12, Shape C), the unknown-theme 400 (§4.11, Shape C), and the password-change failures (§4.14 — field-level validation, so they stay inline, delivered by the screen's own `htmx:responseError` listener, the one screen where Shape B and inline errors coexist). §4.5 register drops `HX-Redirect`: success closes the modal, refreshes the list and toasts without a page reload, while the modal refusal stays inline (the screen keeps its own error path — no `HX-Retarget`). §4.10: creating a key now refreshes the key table out-of-band (the table markup is one extracted fragment, `settings/api-keys :: keysTable` — swapped at TABLE level, because a `tbody` OOB element nested in the response is destroyed by the browser's fragment parser: table-only tags outside table context are dropped tokens) and the dead `HX-Trigger: keyRevoked` header is gone. §4.9 cancel toasts; its `ResponseStatusException` refusals stay full error pages (recorded gap: `UiExceptionHandler` has no htmx-aware branch). §4.13's redirect flash renders as a server-side toast in the layout's `#toast` stack instead of the banner, copy verbatim. New guards: `ToastOobFragmentRenderTest` (nesting), `ToastMarkupParityTest` + `toast.test.mjs` (one markup contract, server and client), and the `bridgeErrors`/`show` JS tests under `editorJsTest`. |
| 2026-08-31 | v1.14 | execute page redesign (032) | §4.4 Pipeline Editor expanded from the bare pointer into the rows that touch this document's shared contracts: the new `READ_RESOURCES` node-SQL read partial (`GET /partials/pipelines/{id}/nodes/{nodeId}/sql`, spec §8.3) with the deliberately-NOT-a-toast copy confirmation (live region + 1.5s label swap); the result grid moved onto the shared `.ds-table` (bespoke `.pe-result-table` styles deleted; paging stays the client-side cursor contract, restyled to the shared pager's look); and the SSE terminal events land on §5.1's Shape D — `pipeline_completed`/`execution_aborted` toast via `DpToast.show` (stream-borne, no HTTP response), `pipeline_failed` keeps the error modal — with all three announcing on the live region. No §5.1 amendment was needed: Shape D already existed (v1.13) and the copy button does not use it. |
