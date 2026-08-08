# UI Screens Inventory

**Status:** v1
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Editor](pipeline-editor.md), [Design System](pipeline-editor.md#34-design-system-acmedesign-tokens), [REST API](rest-api.md)
**Last updated:** 2026-08-05

---

## 1. Purpose

The pipeline editor is fully specified, but the app has many other screens. This spec inventories **every page in the application** — its URL, purpose, what it shows, what REST endpoints it calls, what design system primitives it uses, and whether it uses htmx or vanilla JS.

These are standard CRUD + list/detail screens. They don't need pipeline-editor-level detail (no Cytoscape, no SSE, no Alpine complexity). They use **Thymeleaf + htmx + design system primitives** — server renders HTML, htmx swaps partials for interactions.

---

## 2. Design Principles

1. **Server-rendered by default.** Thymeleaf renders full HTML pages. htmx handles partial updates (search, filter, pagination, form submission) without full page reload.
2. **htmx for these screens, fetch for the pipeline editor.** htmx is the right tool for "server renders partial HTML, swap it in" patterns. The pipeline editor is the exception (graph + SSE requires vanilla JS).
3. **Design system everywhere.** Every screen uses `@acme/design-tokens` tokens and `.ds-*` primitives. No exceptions, no hardcoded colors.
4. **Consistent layout shell.** Every page (except login) shares a common nav bar + sidebar layout, defined in a Thymeleaf layout fragment.

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

---

## 4. Screen Catalog

### 4.1 Login

| Attribute | Value |
|---|---|
| URL | `GET /login` |
| Auth required | No |
| Purpose | OIDC login — renders one button per configured provider |
| Design primitives | `.ds-card`, `.ds-button--secondary` |
| JS | None (static links to `/oauth2/authorization/{provider-name}`) |
| htmx | None |

Content: centered card with app logo + one button per configured OIDC provider. Buttons are **dynamic** — the controller reads the `ClientRegistrationRepository` and passes the provider list to Thymeleaf. A deployment with Google + Okta shows two buttons; a deployment with only Keycloak shows one. Button text is the `display-name` from the provider config. No hardcoded provider names.

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
| htmx | Yes — search filter (`hx-get="/pipelines?q=..." hx-target="#pipeline-table"`), pagination |

Content: search bar, datasource filter dropdown, table of pipelines (name, description, version, owner, last updated). "Create Pipeline" button (requires `author` scope) → opens create modal.

### 4.4 Pipeline Editor

Fully specified in [Pipeline Editor spec](pipeline-editor.md). Not repeated here.

### 4.5 Datasource List

| Attribute | Value |
|---|---|
| URL | `GET /datasources` |
| Auth required | Yes (`read`; create/edit/delete requires `admin`) |
| Purpose | Browse, test, manage datasource connections |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-button`, `.ds-modal` |
| JS | None |
| htmx | Yes — test connection button (`hx-post="/datasources/{name}/test" hx-target="#test-result"`), create/edit modal forms |

Content: table of datasources (name, display_name, dialect badge, status indicator). Per-row "Test" button → inline connection test result. "Register Datasource" button (admin scope) → modal form with: name, dialect dropdown, JDBC URL, username, password, pool properties.

### 4.6 Template List

| Attribute | Value |
|---|---|
| URL | `GET /templates` |
| Auth required | Yes (`read`) |
| Purpose | Browse SQL templates, filter by dialect, search |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-input` |
| JS | None |
| htmx | Yes — search, dialect filter, pagination |

Content: table of templates (id, display_name, dialect badge, version, is_library badge). Clickable → template editor.

### 4.7 Template Editor

| Attribute | Value |
|---|---|
| URL | `GET /templates/{id}/editor` (and `/versions/{version}/editor`) |
| Auth required | Yes (`read`; save requires `author`) |
| Purpose | Edit template body (Freemarker SQL), preview rendered SQL, manage versions |
| Design primitives | `.ds-card`, `.ds-button`, `.ds-code-block`, `.ds-form` |
| JS | Light — tab switching (edit / preview), context-variable form |
| htmx | Yes — "Render Preview" button (`hx-post="/templates/{id}/versions/{v}/render" hx-target="#preview-output"`) |

Content:
- **Editor pane**: textarea with the Freemarker body (syntax-highlighted if feasible without build step; otherwise monospace `.ds-code-block`).
- **Context variables form**: inputs for each declared `params_schema` variable. Values sent to the render endpoint.
- **Preview pane**: rendered SQL output. Updated via htmx on "Render Preview" click.
- **Version selector**: dropdown to view/restore previous versions.
- **Save button** (author scope): creates a new version via `PUT /templates/{id}`.
- **Imports panel**: shows imported library templates with links.

### 4.8 Execution History

| Attribute | Value |
|---|---|
| URL | `GET /executions` |
| Auth required | Yes (`read`) |
| Purpose | Browse past executions, filter by pipeline/status/date |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-input` |
| JS | None |
| htmx | Yes — filters (pipeline, status, date range), pagination |

Content: table of executions (pipeline name, version, status badge, triggered_by, triggered_via badge, started_at, duration). Clickable → execution detail.

### 4.9 Execution Detail

| Attribute | Value |
|---|---|
| URL | `GET /executions/{execution_id}` |
| Auth required | Yes (`read`) |
| Purpose | View execution metadata, node stats, result, replay events |
| Design primitives | `.ds-card`, `.ds-table`, `.ds-badge`, `.ds-code-block` |
| JS | Light — result preview pagination if large |
| htmx | Yes — result pagination (`hx-get="/executions/{id}/result?offset=..."`) |

Content:
- **Header**: pipeline name + version, status badge, timing, triggered_by + via.
- **Node stats table**: per-node status, duration, rows_out, error.
- **Error details** (if failed): code, message, user_message, details JSON, doc_url link.
- **Result preview**: first 100 rows as table (if result available and not expired).
- **Download buttons**: JSON, CSV, Arrow (if result available).
- **Event replay**: link to `GET /executions/{id}/events` (SSE stream replay).

### 4.10 API Keys

| Attribute | Value |
|---|---|
| URL | `GET /settings/api-keys` |
| Auth required | Yes (any authenticated user manages own keys) |
| Purpose | Issue, view, revoke API keys for agents |
| Design primitives | `.ds-table`, `.ds-button`, `.ds-modal`, `.ds-badge`, `.ds-code-block` |
| JS | Light — copy-to-clipboard for newly created key |
| htmx | Yes — generate key modal (`hx-post="/api/v1/auth/api-keys" hx-target="#key-list"`), revoke (`hx-delete="/api/v1/auth/api-keys/{id}"`) |

Content:
- **Key list**: name, key prefix (`dpk_ABCDEF...`), scopes badges, created_at, last_used_at, expires_at, status (active/revoked).
- **Generate button**: opens modal with name input, scope checkboxes, expiration date picker. On submit: creates key, shows plaintext key **once** with copy button + warning.
- **Revoke button**: per-row, confirms, sets `is_revoked = true`.
- **Never shows**: the full key (only the prefix after creation), the hash.

### 4.11 User Settings

| Attribute | Value |
|---|---|
| URL | `GET /settings` |
| Auth required | Yes |
| Purpose | Profile info, theme preference |
| Design primitives | `.ds-card`, `.ds-avatar`, `.ds-select` |
| JS | None |
| htmx | Yes — theme switch (`hx-post="/settings/theme" hx-vals='{"theme":"dark"}'`) |

Content:
- **Profile**: avatar (from OIDC provider), display name, email, provider badge (Google/Microsoft).
- **Theme selector**: dropdown of available design system themes. Switching sends htmx request → server updates session preference → page re-renders with new theme CSS.
- **Session info**: JWT issued at, expires at. "Logout" button.

### 4.12 Admin: User Management (admin scope only)

| Attribute | Value |
|---|---|
| URL | `GET /admin/users` |
| Auth required | Yes (`admin`) |
| Purpose | View all users, activate/deactivate, grant/revoke admin |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-button` |
| JS | None |
| htmx | Yes — activate/deactivate toggle, admin grant/revoke |

Content: table of all users (email, display_name, provider, is_active, is_admin, last_login_at). Admin can toggle `is_active` and `is_admin` per user.

---

## 5. htmx Usage Pattern

Standard htmx pattern for list/filter/pagination:

```html
<!-- Search input triggers table refresh -->
<input type="search" name="q" class="ds-input"
       hx-get="/pipelines"
       hx-trigger="keyup changed delay:300ms"
       hx-target="#pipeline-table"
       hx-select="#pipeline-table"
       placeholder="Search pipelines...">

<!-- Table is a partial that gets swapped -->
<table id="pipeline-table" class="ds-table">
    <tr th:each="p : ${pipelines}">
        <td><a th:href="@{'/pipelines/' + ${p.id} + '/editor'}" th:text="${p.displayName}">Name</a></td>
        <td th:text="${p.description}">Desc</td>
        <td><span class="ds-badge ds-badge--neutral" th:text="'v' + ${p.currentVersion}">v1</span></td>
    </tr>
</table>

<!-- Pagination -->
<div hx-get="/pipelines?offset=50&q=${q}" hx-target="#pipeline-table" hx-swap="outerHTML">
    Load more
</div>
```

Server returns a partial HTML fragment (just the `<table>` + pagination), htmx swaps it in. No full page reload. No client-side rendering. No JSON parsing.

---

## 6. Error Pages

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
