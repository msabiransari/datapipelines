# UI Screens Inventory

**Status:** v1.1
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Editor](pipeline-editor.md), [Design System](pipeline-editor.md#34-design-system-acmedesign-tokens), [REST API](rest-api.md), [Auth & Security](auth.md), [Templates](templates.md), [Configuration Reference](configuration.md)
**Last updated:** 2026-08-07

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
- **Toast region.** An empty `<div id="toast" aria-live="polite"></div>` and the htmx `response-targets` extension, which together implement the error rule in §5.1.

```html
<head>
    <!-- …design system load order per Pipeline Editor §3.4… -->
    <link rel="stylesheet" id="theme-link"
          th:href="@{'/vendor/design-system/themes/' + ${activeTheme} + '.css'}">
</head>
<body th:attr="hx-headers=|{&quot;DP-CSRF-Token&quot;: &quot;${csrfToken}&quot;}|"
      hx-ext="response-targets">
    <div id="toast" aria-live="polite"></div>
    <!-- … -->
</body>
```

htmx and its `response-targets` extension are **vendored** into `static/vendor/` like the rest of the frontend stack — no CDN references ([Pipeline Editor §4.2](pipeline-editor.md)).

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
| URL | `GET /` |
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
| htmx | Yes — search filter and pagination (`hx-get="/partials/pipelines" hx-target="#pipeline-table"`) — full pattern in §5 |

Content: search bar, datasource filter dropdown, table of pipelines (name, description, version, owner, last updated). "Create Pipeline" button (requires `author` scope) → opens create modal.

### 4.4 Pipeline Editor

Fully specified in [Pipeline Editor spec](pipeline-editor.md). Not repeated here.

### 4.5 Datasource List

| Attribute | Value |
|---|---|
| URL | `GET /datasources` |
| Auth required | Yes (`read` to browse; `author` to test a connection; workspace-bound create behind the `member-datasources-enabled` gate, global create/manage `admin` — workspaces D8) |
| Purpose | Browse, test, register datasource connections in the active workspace |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-button`, `.ds-modal` |
| JS | `static/js/toast.js` (layout-global: arms every `.ds-toast` appended to `#toast` — auto-dismiss + close) |
| htmx | Yes — test connection button (`hx-post="/partials/datasources/{name}/test"`, `hx-target="#toast"`, `hx-swap="beforeend"` — the result is a §5.1 Notifications toast, never a row swap), search + dialect filter + pager (`hx-get="/partials/datasources"` into `#datasource-list-wrapper`, `outerHTML` — the fragment root carries the id, so the swap target survives every refresh), register modal (`hx-post` on `/partials/datasources`, `HX-Redirect` on success) |

Content: table of the ACTIVE workspace's datasources (name + `readonly` badge, dialect badge, workspace column — `global` or the bound name, URL, username). Per-row "Test" button (`author` scope) → connection result as a §5.1 Notifications toast; the table itself is never re-rendered mid-interaction. "Register Datasource" button → modal form with: name, display name, dialect dropdown, JDBC URL, username, password, description, and two checkboxes — `readonly` (always settable, [Datasources §5.7](datasources.md#57-readonly-datasources-flag-semantics-and-enforcement-layers)) and `global` (**admin-only; visible-disabled for everyone else**, workspaces D8: unchecked binds to the active workspace). The register action applies the SAME D8 rules as REST §9.1 (`DatasourceWorkspaceRules` — one component, two surfaces) and crosses the same registry save boundary.

The listing is workspace-scoped exactly like REST §9.2 (`listVisible`: active-bound + global, repository-level); a datasource bound to another workspace is absent, and its by-name test behaves as not-found. Rename is not offered — a datasource name is immutable (delete + re-create, blocked while referenced).

**§4.13's workspaces screen** owns workspace lifecycle; this screen's Register button is hidden entirely when the caller is a non-admin and `member-datasources-enabled` is off (the demo shape — open datasource creation is an SSRF primitive from the server's network position).

### 4.6 Template List

| Attribute | Value |
|---|---|
| URL | `GET /templates` |
| Auth required | Yes (`read`) |
| Purpose | Browse SQL templates, filter by dialect, search |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-input` |
| JS | None |
| htmx | Yes — search, dialect filter, pagination (`hx-get="/partials/templates"`) |

Content: table of templates (id, display_name, dialect badge, version, is_library badge, truncated `description`). Clickable → template editor.

### 4.7 Template Editor

| Attribute | Value |
|---|---|
| URL | `GET /templates/{id}/editor` (and `/versions/{version}/editor`) |
| Auth required | Yes (`read` to view; `author` to save or render a preview) |
| Purpose | Edit template body (Freemarker SQL), preview rendered SQL, manage versions |
| Design primitives | `.ds-card`, `.ds-button`, `.ds-code-block`, `.ds-form` |
| JS | Light — tab switching (edit / preview), add/remove context rows, key-value ⇄ JSON toggle |
| htmx | Yes — "Render Preview" button (`hx-post="/partials/templates/{id}/versions/{v}/render" hx-target="#preview-output"`) |

Content:
- **Editor pane**: textarea with the Freemarker body (syntax-highlighted if feasible without build step; otherwise monospace `.ds-code-block`).
- **Description panel** (read-only in the preview column): the template's free-text `description`. Since a template declares no variables, this is the only in-app hint about what context it expects ([Templates §2.5](templates.md#2-design-principles)).
- **Render context input** — **free-form**. Templates do not declare their variables; the calling *pipeline's* `parameters` block is the single declaration point ([Templates §3.2](templates.md#32-field-reference)), so the editor has nothing to enumerate and MUST NOT try. Two equivalent input modes over the same underlying value, toggled by a tab:
  - **Key/value rows** (default): an "Add variable" button appends a `name` + `value` row pair; rows are collected into a JSON object.
  - **JSON textarea**: the same object, edited directly. Useful for nested/array values and for pasting a pipeline's parameter map.

  Whatever the active mode produces is posted verbatim as the `context` object of the render call ([REST §8.7](rest-api.md#87-validate-template-render-against-sample-context)). It is a scratch context for preview only — it is never stored on the template and has no effect on validation.
- **Preview pane**: rendered SQL output, swapped in by htmx on "Render Preview". A reference to a variable the supplied context does not contain fails the render; the error envelope is rendered inline into `#preview-output` per §5.1 (this is a *preview* failure, not a save failure — save-time render checking lives on the **pipeline**, [Pipeline Contract §12.6](pipeline-contract.md#126-template-validations)).
- **Version selector**: dropdown to view previous versions. Versions are immutable; "restore" means opening an old version and saving it as a new one.
- **Save button** (`author` scope): creates a new version via the partials endpoint backed by `PUT /api/v1/templates/{id}`. Save runs parse-level validation only ([Templates §7.1](templates.md#7-validation-rules)).
- **Imports panel**: the `imports` array as `{id, version, alias}` rows with links to each library template. The body never contains `<#import>` — the alias shown here is what the body calls ([Templates §6](templates.md#6-library-templates)).

### 4.8 Execution History

| Attribute | Value |
|---|---|
| URL | `GET /executions` |
| Auth required | Yes (`read`) |
| Purpose | Browse past executions, filter by pipeline/status/date |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-input` |
| JS | None |
| htmx | Yes — filters (pipeline, status, date range), pagination (`hx-get="/partials/executions"`) |

Content: table of executions (pipeline name, version, status badge, triggered_by, triggered_via badge, started_at, duration). Clickable → execution detail.

### 4.9 Execution Detail

| Attribute | Value |
|---|---|
| URL | `GET /executions/{execution_id}` |
| Auth required | Yes (`read` + ownership of the execution; `admin` may view any. Cancelling a running execution requires `execute`) |
| Purpose | View execution metadata, node stats, result, replay events |
| Design primitives | `.ds-card`, `.ds-table`, `.ds-badge`, `.ds-code-block` |
| JS | Light — result preview pagination if large |
| htmx | Yes — result pagination (`hx-get="/partials/executions/{id}/result?offset=..."`), cancel (`hx-delete="/partials/executions/{id}"`) |

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
| htmx | Yes — generate key modal (`hx-post="/partials/api-keys" hx-target="#key-list"`), revoke (`hx-delete="/partials/api-keys/{id}"`) |

Server-side these partials delegate to the same application service as [REST §16.1](rest-api.md#161-api-keys-any-authenticated-principal--own-keys-only) (`GET`/`POST /api/v1/auth/api-keys`, `DELETE /api/v1/auth/api-keys/{key_id}`) — identical validation and identical ownership scoping, HTML instead of JSON.

Content:
- **Key list**: name, key prefix (`dpk_ABCDEF...`), scopes badges, created_at, last_used_at, expires_at, status (active/revoked). Scoped to the caller's own keys — the list endpoint never accepts a user filter, and admins do not get a cross-user view of keys here.
- **Generate button**: opens modal with name input, scope checkboxes, expiration date picker. On submit: creates key, shows plaintext key **once** with copy button + warning.
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
- **Theme selector**: dropdown of the vendored design-system themes. On change, htmx `PATCH /partials/profile/theme` → the server **UPDATEs the `users` row** (`theme_preference`) for the authenticated user and returns an out-of-band swap of the layout's `#theme-link` stylesheet element, so the new theme applies immediately without a page reload (all tokens cascade — [Pipeline Editor §3.4](pipeline-editor.md#34-design-system-acmedesign-tokens)).
  - **Persisted on the user row, never in session state.** The server is stateless behind a load balancer with no sticky sessions (§2 principle 6) — a session-held preference would be lost on the next request that lands on another instance and would not survive re-login. `users.theme_preference` is nullable; `NULL` means "use the deployment default", `datapipelines.ui.theme` ([Configuration](configuration.md)).
  - Submitted values are validated against the vendored theme list; an unknown theme is rejected (`400`) and surfaced via the §5.1 toast rule — never written through.
- **Session info**: JWT issued at, expires at. "Logout" button → `POST /logout` ([REST §16.4](rest-api.md#164-logout-browser-session)), a normal CSRF-protected form post, not an htmx swap.

### 4.12 Admin: User Management (admin scope only)

| Attribute | Value |
|---|---|
| URL | `GET /admin/users` |
| Auth required | Yes (`admin`) |
| Purpose | View all users, activate/deactivate, grant/revoke admin |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-button` |
| JS | None |
| htmx | Yes — search/pagination (`hx-get="/partials/admin/users"`), activate/deactivate and admin grant/revoke (`hx-post="/partials/admin/users/{id}/{action}"`, row-level swap) |

Content: table of all users (email, display_name, is_active, is_admin, local-access status). Admin can toggle `is_active` and `is_admin` per user, and — for local accounts ([Auth §5A.1](auth.md#5a1-accounts)) — create local users, reset passwords, disable local access, and clear lockouts.

- Partials delegate to [REST §16.3](rest-api.md#163-user-administration-admin-scope) (`activate`, `deactivate`, `grant-admin`, `revoke-admin`), which writes the `auth.user.*` audit events.
- **Create local user** (rendered only when local accounts are enabled): email + optional display name; the server generates a random one-time password shown to the admin exactly once (out-of-band notice) with `must_change_password = TRUE` — there is no email flow, so the admin conveys it out-of-band ([Auth §5A.1](auth.md#5a1-accounts)). A taken email answers `409`.
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

The **switcher in the navbar** (§3) drives the active workspace; the screen's Switch buttons POST the same `/workspace/switch`. Expected refusals render as the inline error banner — the generic error page is reserved for the unexpected (§6).

### 4.14 Change password (local accounts)

| Attribute | Value |
|---|---|
| URL | `GET /settings/password` |
| Auth required | Yes (any authenticated session with a local password) |
| Purpose | Self-service password change — and the one screen the §5A.4 forced-change gate lets a `must_change_password` user reach |
| Design primitives | `.ds-card`, `.ds-input`, `.ds-button--primary` |
| JS | None |
| htmx | Yes — `hx-post="/partials/account/password"`, result fragment into `#password-change-result` |

Content: current / new / confirm fields with the policy floor stated inline (at least 12 characters, [Auth §5A.5](auth.md#5a5-enumeration-resistance-and-the-password-policy)). A `must_change_password` user additionally sees the one-time-password warning banner — every other route redirects here until the change succeeds ([Auth §5A.4](auth.md#5a4-forced-password-change)). An account without a local password (OIDC-only) sees an explanatory note instead of the form. Failure outcomes render as inline fragments: wrong current password, policy violation, confirmation mismatch; success confirms and the gate releases on the next navigation.

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
2. **The swap target is a wrapper that contains everything that changes** — table *and* pager — so one `outerHTML` swap keeps them consistent. Swapping only the `<table>` leaves a stale pager behind.

### 5.1 Standard States

Every list, panel and form on these screens implements the same three states. They are layout-shell concerns, specified once here rather than per screen.

**Empty state.** When a collection legitimately has zero rows, the partial returns an `.ds-empty-state` card — an icon, one sentence naming what is missing, and the primary action if the user has scope for it ("No pipelines yet — Create pipeline"). Distinguish the two empties: *nothing exists* gets the create action; *nothing matched the filter* gets "No pipelines match "…" — Clear filters". An empty table with only a header row is not an acceptable empty state.

**Loading state.** htmx's own indicator mechanism: `hx-indicator` points at an element carrying `.htmx-indicator`, which htmx toggles to `.htmx-request` for the duration of the request.
- List/panel refreshes use an inline `.ds-spinner` next to the control that triggered them (see §5 above).
- Buttons that mutate (`Save`, `Generate key`, `Test connection`) additionally set `hx-disabled-elt="this"` so the action cannot be double-submitted.
- Indicators are CSS-only (opacity/visibility transitions on `.htmx-request`), so there is no layout shift and no JS.

**Error rendering.** A partial request that fails returns the **standard REST error envelope** ([REST §4.2](rest-api.md#42-error-envelope)) rendered into an HTML fragment — the same `code` / `message` / `user_message` / `correlation_id`, not a bespoke error format. The chosen idiom is the htmx **`response-targets` extension**, enabled once on `<body>` (§3):

```html
<!-- 4xx/5xx go to the toast region; only the success response touches the panel -->
<button class="ds-button ds-button--primary"
        hx-post="/partials/datasources/pg-main/test"
        hx-target="#test-result"
        hx-target-error="#toast"
        hx-disabled-elt="this"
        hx-indicator="#test-spinner">
    Test connection
</button>
```

- `hx-target-error="#toast"` routes any non-2xx response to the shared `#toast` region (`aria-live="polite"`), leaving the success target untouched — a failed refresh never blanks the panel it was going to replace.
- The server renders the envelope into a `.ds-toast--danger` fragment: `user_message` as the headline, `code` and `correlation_id` in small text (the correlation id is what a user quotes in a support request), and `doc_url` as a link when the envelope carries one.
- **Field-level validation errors** (`400` with per-field `details`) are the exception: they target the form (`hx-target-error="#form-errors"`) and render inline next to the offending inputs, because a toast that vanishes is the wrong place for "this field is required".
- **`401`** is not a toast — the partial responds with `HX-Redirect: /login?expired=true`, which sends the browser to the login page (§6). Rendering a login form inside a swapped fragment would nest a page inside a panel.
- **`403`** renders a toast and, where the affordance should not have been visible at all, the swap also removes it — a scope-gated action becoming visible is a UI bug, and the toast copy says "you don't have permission", never "something went wrong".

**Notifications.** Success and failure alike are reported as **toasts**: the layout carries a single `#toast` stack (`.ds-toast-stack`, `aria-live="polite"`, top-right below the header), and a partial that has something to report returns the server-rendered `partials/toast` fragment (`.ds-toast` + one of the design-system variants `success` / `danger` / `warning` / `info`, title + body + close button), which the triggering control appends with `hx-target="#toast" hx-swap="beforeend"` — the panel, table, or form that fired the request is NOT re-rendered, so a notification can never break layout. `static/js/toast.js` is loaded once by the layout and owns the whole lifecycle: a `MutationObserver` arms each appended toast with an auto-dismiss timer (6s) and its close button; exit is the design system's own `.exiting` animation. Markup is never built client-side — the JS schedules removal only (the 025 theme-swap rule: fragments are rendered by Thymeleaf). First adopter: the §4.5 connection test.

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
| 2026-08-05 | v1.0 | initial draft | UI screens inventory: 12 screens (login, dashboard, pipeline list/editor, datasource list, template list/editor, execution history/detail, API keys, user settings, admin users), htmx patterns, error pages |
| 2026-08-07 | v1.1 | consistency campaign | Per [SPEC-REVIEW-2026-08.md](SPEC-REVIEW-2026-08.md) §2.12: route convention §2.1 (pages / `/partials/**` / `/api/v1/**`, htmx never calls the JSON API) and all `hx-*` endpoints re-pointed at `/partials/**` incl. §4.10 API keys [1]; template-editor context form replaced with free-form key-value/JSON input — templates no longer declare variables [1b, D3]; §5 htmx example fixed (`hx-include` + `th:attr` `hx-vals` instead of `${q}` interpolation) [2]; §4 scope column declared a view of the authoritative [Auth §7.6](auth.md#76-scope--operation-matrix-authoritative) matrix, datasource test corrected to `author`, key scopes ⊆ creator's scopes [3, D15]; §4.11 theme preference persisted on the `users` row via `PATCH /partials/profile/theme`, not session state [4]; §4.11 provider badge renders the configured provider `display-name` [5]; §4.9 result panel rebuilt on the uniform cursor with the TTL-expired state and `format`-parameter downloads [6, D9]; new §5.1 standard states (empty / loading via `hx-indicator` / errors via the `response-targets` extension into `#toast`) [7]; CSRF via `dp_csrf` cookie + `DP-CSRF-Token` header wired in the layout [D10] |
| 2026-08-28 | v1.9 | workspaces surfaces slice | New **§4.13 Workspaces** screen (create per mode, open-join, owned-workspace member management, switch) + navbar **workspace switcher** (§3: POST /workspace/switch re-stamps the session claim; hx-headers carries DP-Workspace for partials). §4.5 datasource list re-grounded: workspace-scoped listing, workspace/readonly columns, register modal with the D8-gated `global` (admin-only, visible-disabled) and `readonly` checkboxes, Register hidden for gated-off members. |
| 2026-08-30 | v1.11 | datasources SPA table + toasts | §4.5: search/dialect/pager re-fetch only the list fragment into the stable `#datasource-list-wrapper` swap root (the id moved onto the fragment root — it previously died with the page's placeholder div); the connection test result is a §5.1 toast, ending the row-swap/"Back to list" contract that broke the table layout; the dead View button (REST JSON via hx-get) removed. New §5.1 **Notifications** state: `#toast` stack, server-rendered `partials/toast`, layout-global `toast.js` lifecycle. |
| 2026-08-30 | v1.10 | local password auth | §4.1 Login: local form + divider + provider buttons, only enabled methods render; `credentials`/`locked` banners join the `?error=` idiom. §4.12 admin users: create local user (one-time password shown once), reset, disable local, unlock; `Local` column. New §4.14 Change password — the §5A.4 forced-change screen. |
