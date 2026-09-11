# UI Screens Inventory

**Status:** v1.36
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Editor](pipeline-editor.md), [Design System](pipeline-editor.md#34-design-system-acmedesign-tokens), [REST API](rest-api.md), [Auth & Security](auth.md), [Templates](templates.md), [Configuration Reference](configuration.md)
**Last updated:** 2026-09-11 (118)

---

## 1. Purpose

The pipeline editor is fully specified, but the app has many other screens. This spec inventories **every page in the application** — its URL, purpose, what it shows, what REST endpoints it calls, what design system primitives it uses, and whether it uses htmx or vanilla JS.

These are standard CRUD + list/detail screens. They don't need pipeline-editor-level detail (no Cytoscape, no SSE, no Alpine complexity). They use **Thymeleaf + htmx + design system primitives** — server renders HTML, htmx swaps partials for interactions.

---

## 2. Design Principles

1. **Server-rendered by default.** Thymeleaf renders full HTML pages. htmx handles partial updates (search, filter, pagination, form submission) without full page reload. Navigation is boosted (§3.2): a section click fetches the SAME full page and swaps only the main region — the MPA is the design, the document-per-click feel is not required by it.
2. **htmx for these screens, fetch for the pipeline editor.** htmx is the right tool for "server renders partial HTML, swap it in" patterns. The pipeline editor is the exception (graph + SSE requires vanilla JS).
3. **Design system everywhere.** Every screen uses `@acme/design-tokens` tokens and `.ds-*` primitives. No exceptions, no hardcoded colors. Where a class named below has no counterpart in the vendored `primitives.css` (`.ds-spinner`, `.ds-toast*`, `.ds-empty-state` and `.ds-avatar` are the candidates — the roster is ~80 classes and is not enumerated in these docs), the app defines it in `app.css` **derived from design tokens**, never from literal values ([Pipeline Editor §3.4](pipeline-editor.md#34-design-system-acmedesign-tokens)). Confirm against the vendored file at implementation time before adding an app-level class.
4. **Two layouts, and only two (090 §C, normative).** App screens share the shell — rail, top bar, boosted `<main id="app-main">` — from `layouts/default.html`. **Ceremony screens** — the login page and the forced-password-change gate — use `layouts/auth.html`: brand line, centred card, the resolved theme, htmx and the toast stack, and *nothing else*. No rail, no top bar, no workspace switcher, no `#app-main`, no `hx-boost` anywhere in the layout. A ceremony screen exists to move a visitor across the session boundary; until that move completes there is nothing to navigate to, so a shell around it is a menu of dead ends — and, since `#app-main` is the boosted swap target, a shell around it is also a screen that can be swapped into an app page. `AuthLayoutRenderTest` pins both layouts, including that the login page renders no shell even when handed an authenticated model.
   *Before 090 the login page decorated with `layouts/default`, which renders the shell whenever `authenticated` is true, so a signed-in visitor who opened `/login` in a second tab got the sign-in form inside a working app (owner's walk, 2026-09-07). Both halves are closed: the layout above, and the redirect in §4.1.*
5. **Three URL spaces, never mixed.** Pages, HTML fragments, and JSON live under distinct prefixes — see §2.1.
6. **The server holds no UI state.** The app is stateless behind a load balancer with no sticky sessions ([Deployment](deployment.md)); every user preference that must survive a request lives on the `users` row, never in an `HttpSession`.
7. **Scopes are not asserted here.** The per-screen scope column in §4 is a convenience view of the authoritative matrix in [Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative).
8. **Structure must survive a bad monitor — non-text contrast floors (WCAG 1.4.11).** Boundaries a user needs to identify a component are NOT text and have their own floors, enforced by the design-system audit (its `npm run build` runs it first; the vendored copy is guarded here by `VendoredNonTextContrastTest` in `modules/web`): `border-default`/`border-hover`/`border-focus` at **3:1** on the surface they are drawn on (inputs, panes, cards), `border-subtle` at **2:1** (separators — 3:1 makes every table heavy), `surface-selected` at **1.5:1** with a left accent bar of `border-focus` width `3px` (a tint alone cannot reach 3:1 without turning grey). A pane or card boundary is a `border-default` line, never a tint alone. Text keeps its 4.5:1 floors, unchanged.

### 2.1 Route Convention

Three disjoint URL spaces, plus one shape that lives on the first of them. A given URL belongs to exactly one space, and the response media type follows from the space — not from the caller.

| Space | Prefix | Returns | Called by |
|---|---|---|---|
| **UI pages** | root paths — `/`, `/pipelines`, `/executions/{id}`, `/settings/api-keys`, `/admin/users` | full HTML document (Thymeleaf layout + content) | browser navigation |
| **Page-route mutation (PRG)** | a POST on a root path — `/workspaces/create`, `/workspaces/{name}/join\|members\|members/{id}/remove\|delete`, `/workspace/switch`, `/promotion/promote`, `/login` | a **redirect** back to the page it came from (Spring's `redirect:` view — 302), carrying `?ok=`/`?error=` | a plain `<form method="post">` |
| **htmx partials** | `/partials/**` | HTML **fragment** (no layout, no envelope) | htmx `hx-get`/`hx-post`/`hx-patch`/`hx-delete` |
| **JSON API** | `/api/v1/**` | JSON [response envelope](rest-api.md#4-response-envelopes) | agents, MCP, programmatic clients, the pipeline editor's `fetch` calls |

**Page-route mutations are the exception, and the list above is closed.** A mutation may live on a page route for exactly two reasons: its success changes the **SHELL** — the workspace switch re-mints the session cookie, a new or deleted workspace changes the switcher, a promotion changes the deployment the whole screen describes — or it must work **without JS**, which is what makes workspace administration and sign-in usable when the app is misbehaving. Everything else mutates at `/partials` and reports through a §5.1 toast.

Its response contract, so the idiom cannot drift into a third one:

- **A redirect back to the page**, never a rendered body. Post/redirect/get is what makes a reload harmless, and it is what lets the whole shell re-render around a changed identity.
- **`?ok=` / `?error=` KEYS, never free text**: a short token (`?ok=created`, `?error=user_not_found`) that the layout maps to copy. A message passed through the query string is untranslatable, unstylable, and forgeable by anyone who can hand a user a link.
- **The outcome is still a §5.1 toast** — this space has no second error idiom. The layout renders the key into the hidden `#toast-flash` bin inside `#app-main` (076 §B) and `toast.js` adopts it into the persistent `#toast` stack, so a redirect flash and an htmx refusal reach the user as the same object. The key→copy table lives in `layouts/default.html` beside the bin: an unmapped key renders nothing, which is the safe direction.
- Every one of these handlers is **session-only** where it changes privilege or identity, and each is listed with its reason in `MutatingHandlerScopeFloorTest.PAGE_ROUTE_MUTATIONS`. A new one fails that test until the reason is written down.

**htmx never calls `/api/v1`.** A JSON envelope is not a swappable fragment; pointing `hx-*` at the REST API would require client-side rendering, which principle 1 rules out. Every htmx interaction in §4 targets `/partials/**`.

**Partials are a presentation layer, not a second implementation.** A `/partials/**` controller calls the *same* application service as its REST counterpart and renders the result into a Thymeleaf fragment. `POST /partials/api-keys` and `POST /api/v1/auth/api-keys` ([REST §16.1](rest-api.md#161-api-keys-any-authenticated-principal--own-keys-only)) differ only in how the response is serialized — same service, same validation, same scope check.

**Auth on partials.** `/partials/**` is authenticated by the `dp_session` JWT cookie (never by `DP-API-Key` — API keys are for agents). State-changing partial requests (`POST`/`PATCH`/`DELETE`) are CSRF-protected: the frontend sends the `dp_csrf` cookie value in the `DP-CSRF-Token` header ([Auth §8.4](auth.md#84-api-endpoints-auth-via-api-key-or-jwt)). This is wired once in the layout (§3), not per-screen.

**The one carve-out — file downloads.** Result downloads (§4.9) are plain `<a href>` full-page navigations to the REST cursor endpoint, because the response is a file (CSV/Arrow/JSON), not a fragment. That is a browser navigation, not an htmx swap, so it does not violate the rule above; it authenticates with the same `dp_session` cookie.

---

## 3. Common Layout

All authenticated pages use a shared layout:

```
┌──────────────┬───────────────────────────────────────────────────────┐
│ ▣ datapipe…  │ Build / Pipelines     [⌘K search]  ☀  (MS)            │ --header-height
│ ┌──────────┐ ├───────────────────────────────────────────────────────┤
│ │TS workspc│ │                                                       │
│ └──────────┘ │                                                       │
│  Dashboard   │                   Page content                        │
│  BUILD       │                                                       │
│  Pipelines 9 │                                                       │
│  Templates 27│                                                       │
│  Datasources │                                                       │
│  OPERATE     │                                                       │
│  Executions  │                                                       │
│  API         │                                                       │
│  Promotion   │                                                       │
│  ORGANISATION│                                                       │
│  Workspaces  │                                                       │
│  Admin       │                                                       │
│  Docs        │                                                       │
│ ◀ Collapse   │                                                       │
└──────────────┴───────────────────────────────────────────────────────┘
 --app-rail-width (232px, or 60px collapsed)
```

Thymeleaf layout: `layouts/default.html` — includes navbar, design system CSS, htmx, theme switcher.

The layout also wires three things once, for every page:

- **Theme resolution.** The active design-system theme is `users.theme_preference` when set, otherwise the deployment default `datapipelines.ui.theme` ([Configuration §3.10](configuration.md#310-ui)). `UiWorkspaceAdvice` resolves it per request into `${activeTheme}` for EVERY screen (a controller that forgets it renders a `themes/null.css` URL that 404s, leaving every design token unresolved — no borders, no surfaces); individual controllers may still set it explicitly, which simply overrides the advice's value with the same one. Emitted as the `href` of the `#theme-link` stylesheet element ([Pipeline Editor §3.4](pipeline-editor.md#34-design-system-acmedesign-tokens)) — see §4.11. The deployment setting is the default, not a ceiling: a user preference overrides it for that user only, and an unset preference is indistinguishable from today's config-only behaviour.
- **Nav chrome (027 UI pass).** The navbar is a sticky, full-width bar whose links and Logout render only for authenticated requests (`UiWorkspaceAdvice.authenticated` — anonymous screens like Login see the brand only). The active section is highlighted from `UiWorkspaceAdvice.currentPath` on first paint, and mirrored client-side by `shell.js` after boosted swaps (§3.2). Nav and content share the `.app-container` shell (app.css) — full-bleed with one gutter since 076 (§3.1). App-level chrome and table polish live in `static/css/app.css` — the vendored design system files are synced from design-system-starter and are never edited in this repo.
- **CSRF for htmx.** `hx-headers` on `<body>` carries the `dp_csrf` cookie value as `DP-CSRF-Token`; because `hx-headers` is inherited, every descendant htmx request is covered ([Auth §8.4](auth.md#84-api-endpoints-auth-via-api-key-or-jwt)).
- **Workspace switcher + context (workspaces design §9).** The navbar carries a `<select>` of the principal's memberships (`UiWorkspaceAdvice` fills it for every screen); choosing one POSTs `/workspace/switch`, which re-stamps the session JWT's `active_workspace` claim and re-issues `dp_session`, so full-page navigations follow the switch. The layout's `hx-headers` ALSO carries `DP-Workspace: <active>` for every htmx partial call — both mechanisms agree because the switcher drives both. A principal with zero memberships sees no switcher and empty states, never an error page (workspaces design §7).
- **Toast region.** A persistent `<div id="toast" aria-live="polite"></div>` OUTSIDE the swapped region, plus `static/js/toast.js`, which together implement §5.1 Notifications — including `bridgeErrors`, the small `htmx:beforeSwap` listener that admits a 4xx/5xx to the swap only when the server retargeted it at `#toast` by header. No htmx extension is loaded; this replaces the `response-targets` prescription this spec once carried. Server-rendered redirect flashes (`?ok=`/`?error=`) render into a hidden `#toast-flash` bin INSIDE `#app-main` so a boosted response carries them; `toast.js` adopts them into the persistent stack on init and after every settle.

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

### 3.0 Every app stylesheet loads from the layout head (090 §A/§B, normative)

**No page template may carry its own `<link rel="stylesheet">`.** The `content` fragment renders *inside*
`#app-main`, which is the boosted-swap target (§3.2): htmx replaces that region's markup and the browser only
then discovers the link. An inserted stylesheet does not block the already-painted document, so the screen
paints at least one frame with none of its own rules — cached sheet or not.

Measured on the explorers (090, chromium, seeded tree, geometry read at the first animation frame after
`htmx:afterSwap`, `template-tree.css` still page-scoped):

| viewport | rail | first frame after the swap | settled |
|---|---|---|---|
| 1920 | expanded | tree 1640px, detail's left edge EQUAL to the tree's — the panes stacked | tree 480px, detail at tree.right + 24 |
| 2560 | expanded | tree 2280px, stacked | tree 480px, gapped |
| 2560 | collapsed | tree 2452px, stacked | tree 480px, gapped |

That unstyled frame is both of the round's reports at once — "css is applied after data load", and a detail
pane sitting hard against the rail. `template-tree.css` moved to `layouts/default.html`'s `<head>` and the two
list templates' own links are gone; `ExplorerPaneGeometryBrowserTest` asserts the pane contract at the FIRST
FRAME, not only on the settled page, at 1440/1920/2560 × rail expanded/collapsed × both explorers.

**Cumulative layout shift is not the instrument for this.** `PerformanceObserver('layout-shift')` scored the
broken navigation at 0.0000: the panes were *inserted* in the wrong geometry rather than moved out of a right
one, and an insertion is not a shift. The CLS budget (< 0.05 across a boosted navigation) is still asserted —
it is the right instrument for the font rule in §3.3 — but first-frame geometry is what pins this.

`pipeline-editor.css`, `template-editor.css` and `docs.css` are still page-scoped and carry the same defect.
The fix is identical (hoist the link) and belongs to whichever round owns those surfaces.

### 3.1 Shell and width policy (076, normative)

Measured on the owner's ~3,000px window (2026-09-05): four content widths across eleven screens — a 1600px cap on most, bespoke narrower columns on Settings, full-bleed on Templates and the editor. The policy that replaces them:

1. **Every app screen is full-bleed.** `<main>` spans the viewport minus one gutter (`var(--gap-lg)`), exactly as the editor always has. `--app-content-max`, `.app-main-bleed` and the editor's `fullBleed` opt-in are deleted — the opt-in became the rule. The nav spans the viewport with the same gutter. `EditorLayoutRenderTest` pins the editor and a list page rendering the SAME `<main>`.
2. **Reading content gets a reading column, not a different container.** Docs prose, empty states and forms sit in `.app-reading` (`max-width: 90ch`, LEFT-aligned inside the full-bleed main — never centred). Tables and trees never use it. **Amended 079 §D:** Settings, Admin and Workspaces do NOT — a 90ch measure is right for prose and wrong for a grid of independent cards, which is what left three quarters of a wide window empty; Settings is a two-column card grid at ≥1100px and one column below (owner ruling 2026-09-05, option (a)). `.app-reading` stays on Docs, where the content really is prose.
3. **Width lives in classes, never inline.** No app template carries an inline `max-width` or `grid-template-columns` — modals, search inputs, auth cards and the stats grid use the `app.css` classes (`app-modal*`, `app-search-input`, `app-card-auth`, `app-stats-grid`, …). `InlineWidthAuditTest` scans the templates and fails the build on a regression.

### 3.2 Boosted navigation (the app shell, 076)

`hx-boost="true"` on the `<nav>` and on `<main id="app-main">`. A section click — or any in-content navigation — fetches the SAME full page (there is no second template variant) and swaps only the main region; the nav, the workspace switcher and the toast stack persist, the URL pushes, back/forward ride htmx history.

- **The swap policy lives in `static/js/shell.js`, not in attributes.** `hx-target`/`hx-select`/`hx-swap` are deliberately NOT set on `<main>`: htmx inherits them into every child request, which would retarget the screens' partial swaps (search results, tree levels, dashboard stats) at `#app-main`. Instead `shell.js` listens for `htmx:beforeSwap` and, only for requests htmx flags as boosted, retargets at `#app-main` with `select="#app-main"` and `outerHTML show:window:top`.
- **Full navigations remain** — marked `hx-boost="false"` and pinned by `ShellRenderTest`: `/logout`, the OIDC redirects and file downloads (external links too — htmx 2 rejects cross-origin requests). **Amended 090 §C:** `/login` and the forced-change gate no longer opt OUT of boosting; they render `layouts/auth`, which has no `hx-boost` and no `#app-main` at all, so there is no boosting to opt out of. Removing the machinery beats marking each link.
- **The progress signal.** A document load used to say "loading" with a white flash; a swap must not flash, so one 2px bar under the nav (`#app-progress`, tokens only, reduced-motion respected) does the talking. Since 085 §D it shows for EVERY htmx request, boosted or partial, counted in flight between `htmx:beforeRequest` and `htmx:afterRequest` — the full loading-state contract (busy control, delayed skeleton) is §5.1's.
- **Active-section state.** Server-computed from `currentPath` for the first paint; after swaps `shell.js` mirrors the same rule (Dashboard exact, others prefix) off `data-nav-section` + `window.location.pathname`.
- **Scripts re-arm per swap.** Page scripts whose tags ride inside `#app-main` re-execute on arrival; anything document-level installs ONCE per session. The pipeline editor tears down on host-replacing swaps — the execution stream's reader is ABORTED (never `cancel()`: the run continues server-side, visible on `/executions`), the Cytoscape instance is destroyed, timers and document listeners come off — and re-binds through Alpine on `htmx:afterSettle` when a history restore brings its DOM back without re-executing scripts. `toast.js` and `template-explorer.js` follow the same idempotent-init contract; `editorJsTest` pins all three.

### 3.4 The shell chrome (079, normative)

The 076 horizontal navbar became a left rail plus a top bar
(`design-records/mocks/2026-09-05-app-shell-v2.html`, owner approved 2026-09-05). The boosted
-navigation contract of §3.2 is unchanged — same `nav.app-nav`, same `hx-boost`, same swap
target, same `data-nav-section` mirror. Only where the links sit changed, and what the shell
must therefore keep true after a swap grew.

**The shell is the viewport (2026-09-11, owner).** `.app-shell` is exactly `100dvh` tall and
`<main>` is the one scroll container under the bar; the document never scrolls. A screen built
from panes (the explorers, the editors) never drags the rail and the bar when one pane runs a
few pixels long, and a long single-column screen (`/workspaces`, `/docs`, `/settings`) scrolls
inside `<main>` with the chrome fixed. The pane arithmetic (`calc(100dvh - var(--header-height)
- …)`) is unchanged. `AppShellBrowserTest` pins it: no app screen makes the document taller
than the viewport at 1440×900 or 1920×1080, and `/docs` proves the model by overflowing
`<main>`. **The deployment default theme is `dark`** (`datapipelines.ui.theme`, matching the
marketing site); each user can choose light/system/a palette in Settings.

**The rail** (`--app-rail-width`, 232px; 60px collapsed). Brand, then the workspace switcher as
a card (keeping its POST form and its `<select>`, which is present and operable and simply
styled transparent over the card), then the sections grouped **Build** / **Operate** /
**Organisation** with Dashboard alone above them, then the collapse control at the foot. The
nav packs to the top; the free space below it is deliberate.

- **Counts.** Pipelines and Templates carry a badge from `NavCounts`, one cheap `COUNT(*)`
  each behind a 60-second TTL keyed by workspace. There is no general metadata cache in this
  tree to reuse — `DatasourceMetadataCache` is keyed by datasource name and `AuthCache` lives
  in `modules/auth`, which by design cannot see the `pipelines` or `templates` tables — so
  `NavCounts` copies the former's discipline (ConcurrentHashMap, injected ticker, lazy expiry,
  misses never cached) rather than its instance. A count that cannot be read renders **no
  badge**, never a zero.
- **The collapsed state** is a class on `<html>`, written by the layout's ONE inline script
  before the first paint and toggled by `shell.js` afterwards. This is the single deliberate
  exception to "no inline scripts": a deferred external script runs after the document paints,
  so the rail would render at 232px and snap to 60px on every navigation.
- **The active item** is `--brand-soft` background, `--brand` text and icon, **plus a 3px
  `--border-focus` left bar**. The bar is not decoration: §2.8 is normative that
  `surface-selected` reaches only 1.5:1 and a tint alone cannot carry selection. The mock
  paints the tint only; the bar is the accessible half of the same signal.
- **Icons** (085 §B) all come from ONE source: the vendored sprite
  `static/vendor/icons/lucide-sprite.svg` (lucide-static 1.39.0, ISC — the license text is
  vendored beside it as `LICENSE.lucide`), referenced everywhere as
  `<svg class="ds-icon ds-icon-{size}" aria-hidden="true"><use href="/vendor/icons/lucide-sprite.svg#NAME"/></svg>`.
  The stroke is `currentColor`, so a glyph always inherits its context's text colour and no
  use site carries a colour override; `.ds-icon` (icons.css) owns the size and
  `pointer-events: none`. The subset is EXACTLY the referenced set — 40 glyphs, each
  SHA-256-recorded per upstream file in `vendor-manifest.json`. `db`/`table`/`boxes`/
  `workflow` keep their 059 ids because the editor's `TYPE_ICONS` map was born with them
  (`db` is lucide's `database`; the other three coincide with lucide's names), and
  CALCULATOR finally draws its own `calculator` glyph instead of the `file` stand-in.
  Size maps by context, never by what looked right on the day: nav, top bar, menu rows and
  action/close buttons are `ds-icon-sm` (16); the editor's canvas tiles and view controls
  are `ds-icon-md` (20); tight inline glyphs — tree chevrons, the arrows inside text links —
  are `ds-icon-xs` (12). The ad-hoc inline copies this replaces (sixteen shell glyphs at
  two stroke weights), the `&times;` close glyphs and the `&larr;`/`&rarr;` arrows are
  retired; `IconSpriteAuditTest` enforces referenced = vendored = manifest-subset in both
  directions, so the app cannot drift back into two icon sources. The explorers' tree rows
  (085 §A) draw from the same sprite: a rotating chevron-right, folder/folder-open on
  folders, file-code on leaves.

- **The role badge (114).** A `.ds-badge.app-ws-role` under the workspace name inside the
  switcher card, carrying the role the signed-in person holds IN THAT WORKSPACE — `viewer`,
  `author`, `promoter`, `author · promoter`, `admin`, or `super admin` for an instance super
  admin in any workspace, membership or not (D-R8). It is derived from the membership's three
  flags and stored nowhere: no single word names an additive row (D-R2). `data-role` is the
  stable hook the tests read. It sits INSIDE the `app-rail-label` span, so the collapsed rail
  hides it with the workspace name rather than leaving a word with nothing to qualify; at all
  three widths (§3.6) it is the same element in the same place. The role decides what the rest
  of the app renders — the inventory is [§4.3e](#43e-role-visibility--every-verb-and-the-flag-that-renders-it-114-normative).

**The top bar** (`--header-height`). Breadcrumb (`<group> / <page>`, group muted, page bold),
a search field **placeholder**, the light/dark toggle, and the avatar menu.

- **The breadcrumb** is server-rendered from `AppNav.crumbFor(currentPath)` and re-derived
  client-side after a boosted swap from the active rail link's own `data-nav-group` /
  `data-nav-label`. `ShellRenderTest` asserts the Kotlin table and the rendered markup agree
  for every link, so the highlighted section and the crumb cannot drift apart.
- **The search field is wired to nothing this round.** It is a `<div>`, not an `<input>`, and
  `aria-hidden` — deliberately, so nobody types into a box that cannot answer. The `⌘K` chip
  is a promise about a later round, not a live shortcut.
- **The avatar** renders the OIDC `picture` claim when there is one and initials otherwise.
  The claim IS stored: `users.profile_picture_url`, written by `OidcSuccessHandler` through
  `UserRepository` on every login, and rendered on Settings since 025.
- **The menu** carries the signed-in identity, Appearance, the theme swatches, Settings, API
  keys and Log out (which keeps its POST form and `hx-boost="false"`). Escape and an outside
  click close it; arrow keys move focus within it without activating anything; the trigger
  carries `aria-expanded` and the popover `role="menu"`.

**One theme preference, three views.** The design system ships ONE STYLESHEET PER LOOK, so
`light`, `dark` and `auto` are three of the nine values `users.theme_preference` can take and
the six palettes (`saas`, `ocean`, `forest`, `healthcare`, `minimal`, `professional`) are the
rest. There is no separate "mode" column and the app does not invent one. The top bar's
sun/moon toggle, the menu's Appearance segment, the menu's swatches and Settings' Mode row and
Theme select are five controls over that single field, all PATCHing
`/partials/profile/theme`, which answers with an out-of-band swap of `#theme-link` plus a
toast. `shell.js` reads the SWAPPED href back and brings every control and `<html data-theme>`
in line — reading the href rather than the value we asked for is what makes a refused write
leave the controls where they were.

---

### 3.3 Type and density scale (076, normative)

One scale, decided once — measured against the drift the owner saw: page titles at three sizes, dates monospace in one table and proportional in the next.

**The faces (079 §G, normative).** The app sets in **Inter** (sans) and **JetBrains Mono** (mono), both
vendored — `static/vendor/fonts/inter/` and `static/vendor/fonts/jetbrains-mono/`, from the projects' own
GitHub release assets, hashes and versions recorded in `vendor/design-system/vendor-manifest.json` alongside
every other vendored asset. Both are **SIL Open Font License 1.1**, which the owner confirmed compatible with
this repository's AGPL-3.0 (2026-09-05); each directory carries its `OFL.txt` verbatim, as the licence requires
of a redistribution. **No Google Fonts and no CDN** — a webfont from a third-party host is a runtime dependency
on someone else's uptime and a per-visitor request to someone else's log.

This closes a gap the design system could not: it *names* both faces in `--_font-sans`/`--_font-mono` but ships
neither, and the two themes the mode toggle switches between — `light` and `dark` — name **neither** face at all
(`minimal` likewise). So every machine without Inter installed rendered the app in its system UI font, which is
why the running app never looked like the approved mocks. `app.css` therefore restates `--font-sans`/`--font-mono`
at the app level with the vendored faces first and the design system's own stacks behind them, so a blocked or
failed download degrades to exactly the previous rendering. Four `@font-face` blocks;
the upright sans and the regular mono are `<link rel="preload" as="font" crossorigin>`ed in **both** layouts, the italic
sans and the mono 500 are not (the app renders almost no italic, and an unused preload is a wasted request).

**`font-display: optional`, amended 090 §B (was `swap`).** `swap` paints in the fallback and REFLOWS when the face
lands. Measured (090, chromium, every font response held 1.2s, geometry sampled at the first animation frame after
`DOMContentLoaded` and again after `document.fonts.ready`): the `/dashboard` page heading went 243.5px → 251.7px
(+8.2px, +3.4%) and the `/templates` heading 611.7px → 618.2px (+6.5px). CLS stayed at 0 and 0.0075 — the reflow is
HORIZONTAL inside a block whose box does not move, which is why the budget alone could not see it. The owner did not
report a jumping page; they reported text that changes after it has been read, and 6–8px of re-flowed measure on
every heading is that. `optional` gives a ~100ms block period and NO swap window: the face is either ready in time
and used from the first paint, or dropped for that page load and used from the next one, by which point the file is
in the HTTP cache. Post-paint reflow becomes structurally impossible rather than small. Verified by re-running the
same held-font measurement: first-frame and settled widths now agree exactly (238.4/238.4 and 611.7/611.7), CLS 0.
The cost is that a first visit over a slow link renders in the fallback stack. `fallback` (100ms block + a 3s swap
window) is the alternative if that is judged too high; it would want size-adjusted fallback faces first, and the
ratios are measured and ready — Inter / `system-ui` = **102.59%**, JetBrains Mono / `ui-monospace` = **109.48%** —
but the `--_font-sans` stack they must be inserted into lives in the vendored design-system themes.
`VendoredFontsAuditTest` fails the build if a file goes missing, a hash drifts, an `OFL.txt` disappears, or any
stylesheet or template starts naming a font host over the network. The marketing site (`site/**`) does not load
`app.css` and keeps the system fallback this round.

1. **Page title**: `.ds-headline` on every screen's `h1` — one size, no inline `font-size` (`TypeScaleAuditTest` fails the build on a regression). Section headings are `.ds-title`; small uppercase section labels (eyebrows) are `.ds-caption`. **Amended 079 §E:** inside `.app-main` the headline is `--text-xl` and `.ds-title` is `--text-base`, to match the denser shell. The value moved; the rule that there is exactly ONE of it, expressed as a property of the class rather than of a screen, did not. Every page header is `.app-page-h` — title, a muted `.app-page-sub` subtitle of at most 70ch, actions right.
2. **Every table is `.ds-table` with `font-variant-numeric: tabular-nums`** (app.css). Dates and timestamps are ALWAYS proportional — never inside a `.num`/mono cell.
3. **Mono is for identifiers only**: machine names, ids, template refs (`path @ vN`), SQL, keys/prefixes, `context_key → value`. Display names, usernames, badges and dates are prose. The per-table decision:

| Screen | Mono columns | Prose columns |
|---|---|---|
| Dashboard recent executions / §4.8 history | (pipeline machine path on `title` only) | display name, status, triggered_by/via, started_at, duration (`.num`) |
| §4.9 node stats | node id, Context `key → value` | rows in/out, duration (`.num`) |
| §4.10 API keys | key prefix | name, scopes, created/last_used/expires, status |
| §4.12 admin users | — | email, display name, provider, created, scopes, actions (`.num`) |
| §4.13 workspaces | workspace name | role, members, created, actions |
| §4.17 promotion | pipeline/template path, target URL | versions (`.num`), status |
| §4.5 datasources | JDBC URL, username | name, dialect, workspace, last test |
| §4.3/§4.6 detail versions | template ref / path | released_by, created, versions (`.num`) |


### 3.5 Feedback and atmosphere (103, normative)

Owner, 2026-09-08: *"algoschool.app looks so native SPA than datapipelines although I am using the same design library."* Measured (notes T195), Algo School has **zero `hx-boost`** — every navigation there is a full document load — and reads as the more native app anyway. We are the more SPA of the two architecturally (boosted `#app-main` swaps, a progress bar) and gave the reader none of what that app gives: a click-time pending state, a ground with depth, entrance motion, heading weight, consistent elevation. This section is that layer. It changes **no architecture**: no client router, no view-transitions dependency, no new library — `static/js/shell.js` and `static/css/app.css`.

1. **The click is acknowledged, in the frame it happens.** A boosted link click marks the link `is-pending` (dimmed, `aria-disabled="true"`, `cursor: progress`, `pointer-events: none` so a second click cannot fire) and its group — the rail's `<nav>`, the breadcrumb, or the boosting element for an in-content link — `is-pending-scope`. `shell.js` decides what qualifies with the SAME lookup htmx performs (`closest("[hx-boost]")` must read `true`), so nothing htmx would refuse to boost is ever dimmed. A form submitted through htmx pends its **submit button**: htmx names the `<form>` as the requesting element, so §5.1's per-element busy marking never reaches the control the reader actually pressed.
2. **The status pill waits 150ms.** `#app-status-pill` (`role="status"`, `aria-live="polite"`, one element, class-toggled, no inline style) shows a spinner and "Loading…" only if the swap has still not arrived after 150ms — the same no-flash arm, for the same reason, as §5.1's delayed skeleton. It is `hidden` when off rather than transparent: a live region announces when its content becomes rendered, and a permanently-rendered region with unchanging text announces nothing.
3. **The pending state is cleared by the union of three endings**, because no one of them is total: `htmx:afterSettle` (the normal path — `htmx:afterRequest` would clear it one frame early, while the outgoing screen is still painted), every htmx error/abort event (`afterSettle` never fires for a response error, a network error, a timeout or an abort — 085 §D's finding), and `pageshow` (a bfcache restore re-paints the DOM with `is-pending` still on it and fires no htmx event at all).
4. **The swap arrives with motion.** After a boosted swap the new `#app-main` carries `app-enter`: a 150ms fade plus a 4px slide-up, **transform and opacity only**, so it cannot move a box and cannot spend the 0.05 cumulative-layout-shift budget `ExplorerPaneGeometryBrowserTest` holds over boosted navigation. The class comes off on `animationend` **and** on a fallback timer, because under `prefers-reduced-motion: reduce` app.css sets `animation: none` and an animation that never runs never ends. Which swaps qualify is decided by the `htmx:beforeSwap` handler that retargets the response and carried on a one-shot — htmx's own swap events carry the swap's `eventInfo`, not the response's, and have no `boosted` flag. **The class is applied on `htmx:afterSettle`, not `htmx:afterSwap`:** at afterSwap the element is still mid-ceremony (`htmx-swapping htmx-added htmx-settling`) and htmx replaces it a frame later, which starts the entrance and then CANCELS it — measured at `animationstart@771 … animationcancel@788`, with every class-level assertion still passing. `ShellFeelBrowserTest` therefore asserts the animation EVENTS (start, then end, never cancel), not the class.
5. **The ground has depth.** A fixed, `pointer-events: none` `.app-backdrop` element sits at the base layer behind `.app-shell` (which is `position: relative; z-index: 1`): the editor's own `--grid-dot` mixed further toward `--surface-page` plus one soft brand radial at the top left — the login ceremony's idiom (§4.1) carried into the app. It is an element, not a `body` background, because it must not scroll with the content and must sit under the shell's opaque surfaces. Every value is a token expression, so all nine themes get it by construction (`AppCssTokenAuditTest`).
6. **Cards read as raised.** `--shadow-sm` on `.app-main .ds-card`, and a `translateY(-1px)` + `--shadow-md` lift on hover for cards that are themselves links or carry a primary action — never on a static card, where a lift promises an affordance that is not there. The border stays: §2 principle 8 is normative, and a card boundary is a line, never a shadow alone.
7. **Headings carry weight.** Page titles are `--text-2xl` at `--weight-bold` with `--tracking-tight` and `--leading-tight`, still a property of `.ds-headline` inside `.app-main` so no screen picks its own size (`TypeScaleAuditTest`). Card titles keep 076's size and gain the weight and tracking. `.app-eyebrow` generalises the login page's `.app-auth-eyebrow` — uppercase `--text-xs`, `0.08em` tracking, brand colour. **Inter only; no second face** (owner ruling, 2026-09-08).
8. **Controls transition.** The design system already transitions `.ds-button` and `.ds-input` colour, border and shadow (`primitives.css`) — what this round adds is the missing half: a 1px `:active` press on buttons, focus transitions on the bare `<select>`/`<textarea>` controls that are not `.ds-input`, and a 100ms background transition on rail rows. All token durations and easings; all silenced by the `prefers-reduced-motion` block.

**Text contrast over the backdrop** is measured, not asserted: `ShellFeelBrowserTest` computes body text against the worst composite the backdrop can paint (the brand glow over a grid dot over the page surface) on **every** vendored theme — the list derived from `VendoredThemes.names()`, so a design-system sync that adds a theme joins the sweep by itself — and holds §2 principle 8's 4.5:1 text floor.

**Not in scope:** the three list-screen first-paint strategies (rows / skeleton+load / empty+load) are 097's `BrowseModel` work and are untouched here.

### 3.6 The shell at three widths (110, normative)

One breakpoint table for the whole shell — the rail, the top bar and the main gutter at every width the app is used at. It nests with the explorers' own drawer (`.tplx-tree` becomes a drawer below 1100px) and the login split (below 900px); it does not fight either.

| Width | Rail | Topbar | Main gutter |
|---|---|---|---|
| ≥ 1100 px | as today (expanded; `rail-collapsed` on user choice, remembered) | as today | `--gap-lg` |
| 768–1099 px | **starts collapsed** (icons only) unless the user expanded it — same class, same `localStorage` key, one more rule: the default flips at this width | search text hidden (the existing 900 rule moves to this breakpoint), crumbs truncate to the LEAF with the full path in `title` (106's pattern on `h2.tplx-detail-title`) | `--gap-lg` |
| < 768 px | **off-canvas drawer**: not in the grid (`grid-template-columns: 1fr`), `position: fixed`, `z-index: var(--z-drawer)`, translated off-screen; opened by a hamburger button that appears FIRST in the topbar; closed by Escape, backdrop tap, or any boosted navigation | brand tile → hamburger, crumbs (leaf only), workspace switcher as its avatar only, user menu; **nothing wraps**; the search moves INTO the drawer's head (it is hidden today — `app.css` — it must not disappear, it must move) | `--gap-md` |

Rules the table rides on, all testable:

1. **One element, one class.** The drawer is the SAME `<aside class="app-rail">` — no second nav. Its state is `rail-open` on `<html>`, set/cleared by `shell.js` beside `rail-collapsed`, exported for `node --test`, and NEVER persisted: a drawer is closed on every load.
2. **The default flip is pure CSS**, so it survives a live resize. The user's explicit choice travels the SAME key and the SAME pre-paint script: a stored `"1"` carries `rail-collapsed` (as always); a stored `"0"` carries `rail-expanded`, which opts out of the 768–1099 default.
3. **Closing is a union**: `#rail-close`, Escape (a document-level listener installed once — never per-swap), the `.app-rail-backdrop` scrim, and `htmx:afterSettle` for BOOSTED navigations only (the same one-shot `applyBoostSwap` arms for the entrance decides the close, so a background partial settling while the drawer is open cannot slam it shut).
4. **Focus is choreographed on change only**: opening moves focus to the first nav link, closing returns it to `#rail-open`, and `aria-expanded` mirrors the state — but a swap that arrives while the drawer is closed moves nothing. Body scroll is locked while open (`overflow: hidden` on `<html>` via the same class, no inline style).
5. **Motion is `transform` on the drawer and `opacity` on the backdrop**, both silenced under `prefers-reduced-motion: reduce`.
6. **Touch targets**: every control in the drawer and the topbar is at least `var(--field-height-lg)` tall below 768px; the nav links get the same minimum at that width only.

---

## 4. Screen Catalog

**On the "Auth required" column:** it restates, per screen, the minimum scope for the REST operations that screen drives. The **authoritative** definition is the scope ↔ operation matrix in [Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative) — if this column and that matrix ever disagree, the matrix wins and this doc is wrong. Scopes are hierarchical ([Auth §7.5](auth.md#75-scopes)): `admin` ⊃ `author` ⊃ `execute` ⊃ `read`. Actions the current principal lacks scope for are **not rendered** (not merely disabled), and the server re-checks on every partial request — the UI is a convenience, never the enforcement point.

### 4.1 Login

| Attribute | Value |
|---|---|
| URL | `GET /login` (page), `POST /login` (local password form) |
| Auth required | No — and a signed-in visitor is **redirected to `/dashboard`** (090 §C) |
| Layout | `layouts/auth` (§2 principle 4) — brand line and card, no shell |
| Purpose | Sign-in — one username/password form, then a divider, then one button per configured OIDC provider; only the enabled methods render |
| Design primitives | `.ds-card`, `.ds-input`, `.ds-button--primary`, `.ds-button--secondary` |
| JS | None (plain form POST to `/login`; static links to `/oauth2/authorization/{provider-name}`) |
| htmx | None |

Content: centered card with app logo. When local accounts are enabled ([Auth §5A](auth.md#5a-local-password-accounts-optional)), an email+password form (with the `_csrf` hidden field) comes first; a plain "or" divider separates it from the provider buttons — one form, then the divider, then the buttons, never tabs. Only the methods actually enabled render: an OIDC-only deployment shows just the buttons (no form, no divider, exactly as before); a local-only deployment shows just the form. Provider buttons are **dynamic** — the controller reads the `ClientRegistrationRepository` and passes the provider list to Thymeleaf; button text is the `display-name` from the provider config ([Auth §5.1](auth.md#51-provider-configuration-generic), [§5.3](auth.md#53-login-page-dynamic--renders-buttons-for-each-configured-provider)). No hardcoded provider names anywhere in the UI.

**A live session never sees this screen (090 §C).** `/login` is `permitAll`, so the JWT filter authenticates a
valid `dp_session` before the request reaches `UiController` — which is why the form used to render inside the app
shell for a signed-in visitor who opened it in a second tab. The GET now answers `302 /dashboard` whenever the
SecurityContext holds an `AuthenticatedPrincipal`. The check is on the PRINCIPAL, not on the cookie's presence: an
expired, tampered or deactivated session leaves the context empty and still gets the form, which is exactly what
`?error=expired` exists for. `/oauth2/authorization/{provider}` — the other door, reachable by bookmark — is closed
by `OidcSignedInBounceFilter`, installed ahead of Spring Security's `OAuth2AuthorizationRequestRedirectFilter`
(which is ordered BEFORE this chain's own credential filters, so the filter validates the cookie itself through the
same `JwtService.validate` + `UserService.isActive` pair the JWT filter uses). Everything that is not a live
session — no cookie, expired, invalid, deactivated owner — proceeds to the provider untouched.

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
- **Recent executions** (last 10): pipeline **display name** (the machine folder-path name on hover; T114 — the truncated pipeline UUID is gone everywhere), status badge, duration, timestamp. Clickable → execution detail.
- **My pipelines** (top 5 by updated_at): name, description, version. Clickable → pipeline editor.
- **Quick stats**: total pipelines, total executions today, success rate.

### 4.3 Pipelines Explorer

| Attribute | Value |
|---|---|
| URL | `GET /pipelines` |
| Auth required | Yes (`read`) |
| Purpose | Browse the pipeline folder tree, search across full paths, open a pipeline in the editor |
| Design primitives | `.ds-table`, `.ds-input`, `.ds-badge`, `.ds-button`, `.ds-empty`; the tree/two-pane chrome is `template-tree.css` (`tplx-*`, `tpl-*`), shared with §4.6 |
| JS | `static/js/template-explorer.js` — selection, focus and keyboard, shared with §4.6; it finds its pane by the `data-explorer-pane` marker, so both explorers use one file. Expansion itself is `<details>` + htmx and needs no JS |
| htmx | Yes — **one level per request** (`hx-get="/partials/pipelines?prefix=…"` on a folder's `summary`, `hx-trigger="click once"`, targeting that folder's own `.tpl-level` placeholder with `outerHTML`); leaf selection (`hx-get="/partials/pipelines/detail?id=…"` into `#pipeline-detail`, `innerHTML`, `hx-sync=replace`); search and pagination (`hx-get="/partials/pipelines"` into the fragment-root `#pipeline-list-wrapper`, `outerHTML`) via the `#pipeline-filter-q` search input (`input changed delay:300ms`, `#pipeline-filter-spinner` indicator) and the shared §5 pager — full pattern in §5 |

Since 067 pipeline names are **folder paths** ([Template Hierarchy §14](template-hierarchy-design.md)), so this screen is the pipelines **explorer**: the folder tree on the left, the selected pipeline on the right. It is the same shape §4.6 gave templates, deliberately — same server-side prefix levels, same virtual folders, same browse-vs-search rule, same stylesheet and keyboard layer.

- **Browse (no `q`)** renders one tree level per request. A folder shows its own segment as the label with the FULL prefix on `title`, and a badge counting the live pipelines beneath it. Folders are virtual — derived from name prefixes — so there is **no New folder / rename / move / delete control anywhere, and no empty-folder state**: a folder with nothing beneath it does not exist to be rendered.
- **Search (non-empty `q`)** replaces the tree with a **flat list of full paths**, not a tree pruned to matches (§9.2's decided rule in the hierarchy design). Clearing the box returns to the tree — the same dispatcher fragment answers both, so that is true by construction.
- **A new pipeline appears as `v1 draft`** (D55, 099). Creation lands version 1 as a DRAFT, so the leaf's version badge names the **working** version — the draft's number when one exists, the released one otherwise — beside the existing `draft` badge whose tooltip says it is pending release. (`p.currentVersion` alone rendered `vnull` the moment creation stopped releasing.) The Release action lives in the editor and renders for a v1 draft exactly as for any later one; nothing on this screen releases.
- **The detail pane** is three regions since 106 (§4.3b): a header, a READING column and an ACTING column. A selection swaps `#pipeline-detail`'s innerHTML and touches nothing in the tree.
- **The screen is READ-ONLY.** The pre-067 "Create Pipeline" button was permanently `disabled` and its tooltip read "Pipeline editor — Phase 2 other worktree" — an affordance the server does not have, advertised with an internal note. Both are gone (T108), replaced by a sentence saying pipelines are authored through agents over the MCP server, with a link to that spec and the fact that browser authoring is on the roadmap.
- **Rendered for** — every verb on this screen is role-gated since 114; the flag each one needs is [§4.3e](#43e-role-visibility--every-verb-and-the-flag-that-renders-it-114-normative)'s table. Release is `canPromote` and NOT `canAuthor`: an author who is not a promoter sees no Release anywhere (D-R2).
- There is deliberately **no datasource filter**: the one shipped before v1.12 was labelled datasources but populated from `${dialects}`, the controller never had the parameter, and `PipelineRecord` carries no datasource field — serving it needs a join through the pipeline definition, so it was deleted rather than half-wired (deferred).

#### 4.3a The divider handle — both explorers (104)

The tree pane's width was a stylesheet's guess, and it was re-guessed three times: `clamp(260px, 30vw, 480px)` left the tree 14% of the owner's 3491px window, `26vw` uncapped gave it 908px for a column of short names, and the divider itself then had to be drawn. The pane did carry the browser's native `resize: horizontal` — at the pane's **bottom-right corner**, which is where nobody looks for a divider, so every round was spent editing CSS instead.

The native resizer is gone. In its place, the same `static/js/splitter.js` the editor's dock uses (§4.4), bound to a `role="separator"` handle overlaying `.tplx-detail`'s left border — the pane boundary the eye already reads as the divider:

| | |
|---|---|
| Pointer | Drag the handle. `setPointerCapture`, so the drag survives leaving the 12px strip; `touch-action: none`, so a touch drag is a resize and not a scroll |
| Keyboard | Focus it (it is in the tab order) and use ←/→: ±16px, Shift ±64px; Home = floor, End = ceiling. `aria-valuemin`/`max`/`now` move with it |
| Reset | Double-click — the remembered width is forgotten and the pane falls back to `clamp(260px, 22vw, 40rem)`, which is the stylesheet's own default and not a number JS repeats |
| Bounds | Floor 260px, ceiling 40vw. A window that shrinks past a remembered width pulls it back in |
| Memory | `localStorage` key `dp.pane.explorer-tree`, **shared by both explorers** — the width follows the user between Pipelines and Templates |

The size lands as ONE CSS custom property, `--tplx-tree-w`, written on `<html>` (not on the pane: `#app-main` is the boosted-swap target, so a property on the pane would die with every navigation). `template-tree.css` derives both the pane's `width` and the handle's `x` from it through a single `--tplx-tree-size`, with the default as the `var()` fallback — which is what makes "reset" mean "remove the property". The module is a parser-blocking script above the markup, so a remembered width is the width of the first painted frame; `ExplorerPaneGeometryBrowserTest` measures the default at four viewport widths, the drag, the reload and the reset, and holds the CLS budget.

`InlineWidthAuditTest` is unaffected and stays green: it bans a static `style="…"` attribute in a template, and this is a script setting one custom property on the root — the shape its KDoc now names as the allowed one.

The datasource lake-table tree (§4.5) uses the same pane class and so inherits the remembered width, but has no detail pane beside it and therefore no handle.

#### 4.3b The detail pane — reading and acting (106, owner-approved mock 2026-09-08)

The pane used to be a stack of four tables (badges, Settings, Parameters, Versions) in one
column. The owner's mock, layout B, splits it into what a reader does and what an operator
does, and both explorers render the same shape.

**1 — Header.** The folder path is an EYEBROW (`.tplx-detail-path`, mono, muted) and the leaf
name is the `h2`; the full path still rides on `title`. The actions sit on the right: **Open in
editor** (primary) plus the lifecycle verbs 101 shipped, opened as §4.3d dialogs since 102 —
`Release v<n>…` when a draft exists, **Discard v<cur>…** when the current version is released,
**Purge pipeline…** only in the `{D}` shape, **Switch…** at ≥ 2 live eligible versions, at most
one destructive among them. Each `hx-get`s its dialog partial into `#px-dialog`; the POST goes
to the dialog's own partial route, which calls the service 101 wired and answers Shape A / Shape
C (§5.1) — the plain `window.confirm` strings 106 shipped on `data-confirm`, and the `fetch`
path that drove them, are gone.

**2 — Reading column** (`.tplx-read`, the wider one). *Overview*: the chips (`v<n>`, the draft /
released state, node count, `tempdb · <engine>`, datasource count), the description at a **78ch
measure**, and a key/value strip — Datasources (name + dialect, each linked to its detail),
Templates (`id@version`, each linked into §4.6), Created (when · by whom), Last run (status chip
· duration · rows · relative time, linking to the execution). *Parameters*: name / type /
default with a `required` chip, a table **as wide as its content** with its own 22rem scroll.
The one-row **"Settings" table is gone** — the staging engine is a chip.

There is deliberately **no "via UI / MCP / API" chip on Created**, though the mock shows one:
nothing records the surface a pipeline CREATE arrived on. There is no `pipeline.created` audit
event and `pipelines` carries no `triggered_via`, so the chip would have to be inferred. It
returns when a create is audited with its surface.

**3 — Acting column** (`.tplx-act`, narrower, its own scroll). ONE tabbed card:

| Tab | First paint? | Fragment |
|---|---|---|
| Versions | yes | rendered inline with the detail — `partials/pipeline-versions` |
| Runs | on the tab's first click | `GET /partials/pipelines/{id}/runs` → `partials/pipeline-runs` |
| Usage | on the tab's first click | `GET /partials/pipelines/{id}/usage` → `partials/pipeline-usage` |

Versions are **compact rows, never a table**: the acting column measures ~260px of content at
1440, and three ghost buttons in an `auto` track leave a six-column table's meta cell about ten
pixels (measured — `ExplorerDetailBrowserTest` asserts the meta column's readable width for
exactly that reason). Each row states version · status · created · who · runs, marks the sticky
pointer as `current` (D60 — which is not "the latest released"), and carries a per-row overflow
menu (⋯) with exactly the verbs §3.5 allows that row's status — DRAFT → Release, Purge;
RELEASED → Discard, Switch-to (when not current); DISCARDED → Restore; a verb the table refuses
is absent, not disabled (§4.3d).

**Runs** is this pipeline's last 20 executions, so reading a pipeline no longer means leaving for
§4.8 and filtering it back down. Its visibility is §4.8's, unchanged: an admin sees the
workspace's runs, everyone else their own — a second surface over the same rows is not a wider
one. **Usage** is the published endpoints serving the pipeline and the live pipeline versions
pinning it, and it runs the **same query 101's discard refusal runs**
(`PipelineRepository.findLiveParentsPinningVersion`, the evidence behind
`pipeline.version.pinned`), so what the user reads before pressing Discard is what the server
will decide on. Schedules join the list when 092 lands; there is no schedule table to query
today, and an empty third heading would claim they had been checked.

`PipelineBrowseModel.fillDetail` supplies every region in ONE call, with the lifecycle flags
computed beside the query that produced the rows — a button is rendered because the server
would accept it, never because a template read a status string. Two web-side joins sit beside
`PipelineNames` for its reason (`dag` and `pipeline-contract` own the stamped rows and neither
may reach into `auth`): `ActorNames` (who made a version — every stamp is a bare `users.id`) and
`PipelineRunStats` (one `GROUP BY`, not one `COUNT` per row).

#### 4.3c Responsive — both explorers (106 §B)

The breakpoints are defined ONCE, in `template-tree.css`, and honoured by §4.3 and §4.6:

| Width | Panes | Detail | Notes |
|---|---|---|---|
| ≥ 1440px | tree \| detail | reading `1.35fr` \| acting `1fr`, gap `--gap-lg` | the acting card sticks and scrolls inside itself |
| 1100–1439px | tree \| detail | the columns STACK (reading, then acting) | the tab panel's ceiling becomes 28rem |
| < 1100px | the tree is a **DRAWER** | full width | a "Browse" button in the page header opens it as an overlay over the app's backdrop idiom; selecting a leaf closes it and loads the detail; the header's actions wrap under the title; tables scroll inside their cards, never the page; 104's divider handle is hidden — there is no boundary for it to sit on |
| < 640px | — | — | chips wrap, the key/value strip is one column, the tab labels drop their counts |

The tree's WIDTH inside its pane stays 104's (`--tplx-tree-w`, its handle and its clamp); 106
decides only whether the pane is a column or an overlay. `ExplorerDetailBrowserTest` measures
all seven of the owner's widths — 390, 768, 1100, 1440, 1920, 2560, 3491 — for document
overflow, the two-column vs stacked geometry, the drawer's open/close, and a layout-shift
budget of 0.05 on a selection.

**Known, and NOT 106's**: below 768px the app SHELL itself overflows the document on every
screen, `/dashboard` included (measured on this branch at 390px: dashboard 154px, executions
139, pipelines 237, templates 257, datasources 275, api-console 75). The rail keeps its full
`--rail-w` and `HEADER.app-topbar`'s crumbs and controls do not fit beside it. That is
`app.css` and the shell layout, not the explorers; 106 asserts instead that the explorer REGION
fits its own box at 390 and that nothing inside the detail sticks out of the detail.

#### 4.3d Lifecycle verbs — both explorers and both editors (102)

Every version-lifecycle verb 101 shipped ([Versioning §3.5](versioning.md#35-the-lifecycle-table))
is reachable from a **confirm dialog**, one partial per verb, opened into a single per-screen
container — `#px-dialog` (pipelines explorer), `#tx-dialog` (templates explorer), `#pe-dialog`
(pipeline editor), `#te-dialog` (template editor) — emptied on close, `Escape` closed, focus
landing on the first control, the destructive button `ds-button-danger` and last in tab order.
The exemplar is §4.5's delete dialog (094 §B): the question is asked BEFORE the button exists,
the refused branch renders NO button, and the POST re-runs the guard because the screen is
never the authority. A dialog route is session-only (`LifecycleVerbs.requireSession`) — an API
key is refused `auth.session.required` exactly as the REST verbs refuse it; the dialogs call the
same services 101 wired, never the REST controllers over HTTP and never a second copy of a guard.

| Verb | Dialog (`GET`, into the container) | What it shows before its one button | Shapes that render it |
|---|---|---|---|
| Release | `…/lifecycle/release` | the draft's number, who last wrote it and when, every template pin with its status — a DRAFT or MISSING pin is refused colour, says "release the template first", and the button is NOT rendered; else "Releasing makes v`<n>` the current version and locks it." | any with a DRAFT |
| Purge draft | `…/lifecycle/purge?version=v` | the version and its execution count; the button reads "Purge v`<n>` and `<k>` runs" and needs the typed confirm `v<n>` | any with a DRAFT |
| Discard | `…/lifecycle/discard?version=v` | whether the version is current (then the §3.4 fallback: "v`<m>` becomes current" or "nothing eligible remains — the pipeline will have no current version and its endpoints will answer 503"); parents that pin it (listed, no button); "Discard is reversible — Restore brings it back." | RELEASED rows |
| Restore | `…/lifecycle/restore?version=v` | whether restoring moves the pointer (v > current, or current is NULL); "Restoring makes v`<n>` a live release again." | DISCARDED rows |
| Purge entity | `…/lifecycle/purge-entity` | only in the `{D}` shape (else the `last_release` branch, no button); the exclusive draft templates the service offers, with a checkbox "also purge these `<k>` templates (they are pinned by nothing else)"; typed confirm is the entity's NAME | `{D}` |
| Switch | `…/lifecycle/switch` | the live versions as radio rows (RELEASED always; DRAFT under development posture), the current one marked, discarded ones disabled with "restore first"; "endpoints published on this pipeline serve v`<n>` after this." Not authoring-gated (§3.1: the receiver's rollback lever) | ≥ 2 live eligible versions |

The templates twin is addressed by NAME in query/body ([§9.6](template-hierarchy-design.md#96-addressing-the-name-never-travels-in-a-url-path-segment-normative-measured)):
`GET /partials/templates/lifecycle/{verb}?name=&version=`. It has every dialog above except
Switch — templates are pinned by version; there is no served pointer to switch. The pipeline
purge-draft POST rides the **versioned** purge (`DELETE /api/v1/pipelines/{id}/versions/{v}`
beneath): the dialog names an explicit version, so there is no two-writer hash protocol to
honour, and the typed confirm carries the same fact the button states.

**Success is Shape A; refusal is Shape C.** A POST that lands re-renders the explorer's detail
region (the same fragment a selection loads — 106's model, not a copy) with the `toast-oob`
fragment spliced in and `HX-Trigger: lifecycle-changed` whose payload carries the new
working-version facts, so the tree leaf's badge follows server truth (`v3 draft` → `v3`) rather
than a client guess. A verb that removes the ROW (the entity purge) answers `HX-Redirect` back
to the explorer with a flash toast instead — the tree must lose the leaf, and this fragment may
never touch the tree. Every refusal — `pinned` with its pinners, `last_release`, `not_released`,
`not_discarded`, `not_eligible`, `not_draft`, `version.conflict`, `authoring.disabled`,
`template.in_use` — arrives as §5.1 Shape C carrying the real 4xx, never a native dialog. In
the editors the POST answers `HX-Redirect` back to the editor URL with the flash toast: the
page's draft state (`PEDraft`, the version chips) is embedded across the document, so the
honest refresh is the document itself, exactly as the pre-102 editor already reloaded.

**One destructive verb per entity per view** (owner rule). The detail header shows at most one
of Purge pipeline / Discard / Purge draft — `{D}` gets Purge pipeline beside Release; a released
current gets Discard; a draft with no header-destructive gets Purge draft; everything else
lives on the row it names. The version rows (§4.3b's compact list) carry their verbs in a
per-row overflow menu (⋯): DRAFT → Release, Purge; RELEASED → Discard, Switch-to (when not
current); DISCARDED → Restore. A verb the §3.5 table refuses for that row's shape is NOT
rendered — absent, not disabled — so the table and the screen cannot disagree; the POST still
re-runs the guard.

**No native `window.confirm` / `window.alert` anywhere in the lifecycle UI** — a static test
pins it (the four editor sites and the explorers' verb wiring are grep'd; the `hx-confirm` on
`api/console.html` is htmx's own attribute, not a native call, and is excluded by path).

#### 4.3e Role visibility — every verb, and the flag that renders it (114, normative)

**The role decides what is rendered; the server decides what is allowed** (RBAC design §7).
Round 1 (112) made the server right: every verb is refused by role through the two-axis matrix.
This is the other half — a verb the ACTIVE membership cannot perform is **not rendered**.

Rendering a verb the server will refuse is not a safe default: it teaches a person the product
is broken, and the refusal arrives after they have already decided to act. Round 112 shipped
exactly that state — a viewer saw Release, Purge, Register and New key, clicked, and got a 403
with no explanation the screen could give (`ScopeInterceptor` answers before the handler, and
htmx does not swap a 4xx, so the click simply did nothing).

**Hide, don't disable.** A verb the role cannot perform is absent — no greyed button with a
tooltip; a viewer is not being teased. The exceptions are two, both stated at their sites: the
editor's Release stays *disabled with its reason* when the DRAFT is invalid for someone who CAN
release (a state refusal, and the reason is actionable), and the promotion screen keeps its plan
readable for everyone while naming who can send it (§4.17).

One helper answers the question for every screen — `web/ui/RoleModel`, stamping `canRead`,
`canExecute`, `canAuthor`, `canPromote`, `canAdminWorkspace`, `isSuperAdmin` and `roleLabel`
into the model. No controller derives a role boolean of its own. Every verb control carries a
`data-verb` attribute, which is both the tests' hook and the inventory: `grep -rn 'data-verb='
modules/web/src/main/resources/templates` is the list, and a control outside a role guard fails
`RoleVisibilityRenderTest`.

Each boolean narrows for an API-key principal by the key's SCOPE as well as its issuer's role
([Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative) — the credential axis), so a
`read` key is never shown an author's affordances because the person who minted it is an author.

| Screen | Verb(s) | Rendered when | §7.6 operation |
|---|---|---|---|
| Pipelines explorer (§4.3) | Edit, Discard draft, Restore, Purge draft, Purge pipeline | `canAuthor` | `MUTATE_PIPELINES_TEMPLATES` |
| Pipelines explorer | Release | `canPromote` | `RELEASE_VERSION` |
| Pipelines explorer | Switch served version | `canAuthor or canPromote` | `SWITCH_SERVED_VERSION` (`Capability.SWITCH`, O-1) |
| Pipeline editor (§4.4) | Purge draft | `canAuthor` | `MUTATE_PIPELINES_TEMPLATES` |
| Pipeline editor | Release | `canPromote` | `RELEASE_VERSION` |
| Pipeline editor | Execute, Cancel | `canExecute` — **viewer-level** (D-R3) | `EXECUTE_PIPELINE` / `CANCEL_EXECUTION` |
| Templates explorer/editor (§4.6/§4.7) | Create, Edit, Discard, Restore, Purge | `canAuthor` | `MUTATE_PIPELINES_TEMPLATES` |
| Templates explorer/editor | Release | `canPromote` | `RELEASE_VERSION` |
| Datasources (§4.5) | Register, Edit, Delete, **Test** | `canAdminWorkspace` | `MUTATE_WORKSPACE_DATASOURCES` / `TEST_DATASOURCE` |
| Datasource grants (§4.5a) | Grants, Grant, Revoke | `isSuperAdmin` | `MANAGE_DATASOURCE_GRANTS` |
| API keys (§4.18) | New key | `canAuthor` (O-2 — viewers never mint) | `MANAGE_OWN_API_KEYS` + the §7.4 issuance gate |
| API keys | Revoke | `canRead` — any member, own keys | `MANAGE_OWN_API_KEYS` |
| Promotion (§4.17) | Promote | `canPromote` in the SOURCE workspace | `PROMOTE_VERSION` |
| Workspaces (§4.13) | Members: add, change flags, remove | `canAdminWorkspace`, **in the ACTIVE workspace** | `MANAGE_WORKSPACE_MEMBERS` |
| Workspaces | Display name | `canAdminWorkspace` | `MANAGE_WORKSPACE` |
| Workspaces | Create, Deactivate, Reactivate, Delete | `isSuperAdmin` | `WORKSPACE_CREATE` / `MANAGE_INSTANCE_WORKSPACES` |
| Execution detail (§4.9) | Cancel | `canExecute` **and** the execution is RUNNING | `CANCEL_EXECUTION` |
| Shell (§3.4) | the role badge | always | — |

Three rows are worth reading twice, because each is a place a reasonable guess is wrong:

- **Datasource Test is `ws_admin`, not `author`.** It is not a read: it opens a live connection
  with the instance's stored credential and writes the datasource's health down (V9). The design's
  §1 table keeps "register a datasource bound to THIS workspace; test it" on the workspace-admin
  row, and the MCP twin `datasources_test` sits on the same capability.
- **Key REVOKE is `view`, not `author`.** Only ISSUANCE carries the author gate (§7.4). Hiding
  Revoke from a viewer would strand a DEMOTED author with a live key they are not allowed to
  withdraw — the opposite of what O-2 is for.
- **Member verbs follow the ACTIVE workspace.** `ScopeInterceptor` judges
  `MANAGE_WORKSPACE_MEMBERS` against `principal.workspace`, never the workspace named in the
  path, so an admin of X working in Y cannot manage X's members until they switch. §4.13's
  member table therefore renders for the active workspace only, and names the others.

### 4.4 Pipeline Editor

Fully specified in [Pipeline Editor spec](pipeline-editor.md). Only the rows that touch THIS document's shared contracts are noted here:

- **Rendered for (114):** Release is `canPromote`, Purge draft is `canAuthor`, Execute and Cancel are `canExecute` (viewer-level, D-R3) — [§4.3e](#43e-role-visibility--every-verb-and-the-flag-that-renders-it-114-normative). A viewer's editor LOADS and is read-only: the verbs are absent and one quiet `.app-note` line reads `Read-only — you are a viewer in <workspace>`. No toast: nothing failed.
- **Draft lifecycle actions (102):** the topbar's Release / Discard draft buttons open the §4.3d dialogs in `#pe-dialog` — the release dialog shows the draft's pins and refuses on a DRAFT template pin; the discard dialog is the PURGE (versioning §5.4 — the pre-102 confirm text claiming "an executed draft is kept as history" was false and is gone). A success answers `HX-Redirect` back to the editor with a flash toast (the page's draft state is document-wide); a refusal is §5.1 Shape C. No native `confirm`/`alert` remains on this screen (the pre-102 `draft.js` carried three — a static test pins the count at zero).
- **The v2 canvas (080, owner-approved mock):** dotted-grid stage, the mock's node card (type-accent icon tile, id + eyebrow, mono facts, footer state + run numbers), bezier edges with three states, minimap, legend, controls and the fit-that-never-zooms-in ceiling. Its tokens are ONE block at the top of `app.css` (`/* app tokens (080): node-type accents */`) — the five node-type accent pairs plus `--brand(-soft)`, `--border-faint`, `--grid-dot` and the `--edge*` strokes — bridged to design-system tokens so every theme re-skins it. 079's shell v2 is told the block exists and must not redeclare it.
- **The dock (080):** Details | Results | Errors | Events — the 065 inspector overlay is a tab now (owner ruling 2026-09-05), the dock is always present, the chevron collapse is the only contraction, and there is still no close. **Notifications:** unchanged in shape — `pipeline_completed` and `execution_aborted` toast via `DpToast.show` (§5.1 Shape D), `pipeline_failed` keeps the modal — but exactly-once is now structural: the 076 afterSettle rescue stacked Alpine components on a history-restored root (one click → N executions → N toasts), and it destroys the stale tree before re-binding (`editor-toast-once.test.mjs`). Every SSE event of every kind also lands in the **Events** tab in arrival order. **The dock's height is the user's (104).** It was a fixed 232px that could only be collapsed and restored — the owner's report was *"it has a fixed height. You can min/max it but cannot change the height. I want it to be flexible and the user should be able to drag the height."* A `role="separator"` handle straddles the dock's top border (drag, or focus it and use the arrows: ±16px, Shift ±64px, Home/End for the floor and ceiling, double-click to reset). The floor is 120px and the ceiling leaves the canvas a 160px readable strip. The size is one CSS custom property (`--pe-dock-pane-h`) written on `<html>` and remembered in `localStorage` under `dp.pane.editor-dock`; `static/js/splitter.js` is a parser-blocking script ABOVE the markup, so a remembered height is the height of the FIRST frame rather than a shift onto it. Collapse still wins while it is on — expanding restores the remembered height. **The canvas follows:** `.pe-body` is `flex: 1`, so a taller dock is a shorter stage, and graph.js's stage `ResizeObserver` now routes through `handleStageResize` — `cy.resize()` always, re-fit ONLY if the view was still the fit (082/098: fit never zooms IN, and a user who has panned to a corner keeps their view). One path also covers the rail collapsing and the window resizing. The row transition is switched off for the duration of a drag, which is also the only motion `prefers-reduced-motion` users would have met here.
- **SQL section (§8.3 there):** the Details tab loads `GET /partials/pipelines/{id}/nodes/{nodeId}/sql` (a `READ_RESOURCES` read partial, htmx.ajax on selection) and highlights it client-side with the zero-dependency `sql-highlight.js`; the copy confirmation is a live-region announcement plus a 1.5s button-label swap — deliberately NOT a toast (high-frequency, self-evident). CALCULATOR and PIPELINE nodes skip the fetch (client-built evaluation / child mapping).
- **Result grid:** the execution result table renders on the shared `.ds-table`; the bespoke `.pe-result-table` styles are gone. Paging stays client-side cursor paging (the §10.5 contract there).
- **Template reference (§9.4 there):** a node's template is a read-only reference display — `acme/finance/monthly_revenue @ v3`, one line with the FULL reference on `title`, in the Details tab's key/value grid **and** in the server-rendered `partials/pipeline-node-sql` **and** in the `template-missing` empty state. **There is no template picker on this screen**; template selection happens through pipeline JSON authoring, import and MCP. **If a picker is ever added, it reuses §4.6's prefix fragment — it does not get its own client-side tree.**
- **Failure display (057/T85, re-homed by 065, consolidated by 080):** `node_failed`'s `error` object — the failure record — renders in the dock's **Errors tab**: one entry per failed node, newest last, `node id · code` as its summary line, then the message, correlation id, details, the rendered SQL and the exception chain **root-cause-first**, one Copy button. The 065 per-node inspector section is gone with the overlay — the tab is the record's one home beside the modal's one-line summary. `pipeline_failed`'s execution-level record joins the same list (deduped on node + code + message). `PEErrorDetails.build` remains the one view-model (`sse-node-failure.test.mjs` pins it). Under `error-detail=structured` the SQL and Exception sections are simply absent — no apology. The recovery poll's banner names the error code.
- **Full-bleed pages (065):** `layouts/default.html` caps every page at `--app-content-max` (1600px); a page opts out by setting a `fullBleed` model attribute (`app-main-bleed`). **The pipeline editor is the only page that sets it.** `EditorLayoutRenderTest` pins both directions.
- **Editor entry is a full document load (87c20d4):** every link into the editor carries `hx-boost="false"` — a boosted swap initialised the Alpine root before the editor's scripts existed (49 console errors, no Execute/dock). Leaving the editor may stay boosted (the 065/076 teardown handles the swap; the 080 rescue destroys the stale Alpine tree before re-binding). `PipelineExplorerRenderTest` pins the rule; the executions history has no editor links.
- **Phone widths (110): desktop-first by decision, not omission.** Below 768px the page renders a `.app-wide-screen-note` band above the editor — "Open on a wider screen to edit", the pipeline's name, its lifecycle badge (the same `hasDraft`/`draftVersion`/`releasedVersion` attributes the vchip reads) and a link back to the Pipelines explorer — while the editor itself stays rendered and scrollable underneath; no editor script is touched.

### 4.5 Datasource List

| Attribute | Value |
|---|---|
| URL | `GET /datasources` |
| Auth required | Yes (`read` to browse; `author` to test a connection; workspace-bound create behind the `member-datasources-enabled` gate, global create/manage `admin` — workspaces D8) |
| Purpose | Browse, test, register, EDIT and DELETE datasource connections in the active workspace |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-button`, `.ds-modal` |
| JS | `static/js/toast.js` (layout-global: arms every `.ds-toast` appended to `#toast` — auto-dismiss + close) |
| htmx | Yes — test connection button (`hx-post="/partials/datasources/{name}/test"`, `hx-target="#toast"`, `hx-swap="beforeend"` — the result is a §5.1 Notifications toast, never a row swap), search + dialect filter + pager (`hx-get="/partials/datasources"` into `#datasource-list-wrapper`, `outerHTML` — the fragment root carries the id, so the swap target survives every refresh; the pager is the shared §5 fragment), register modal (`hx-post` on `/partials/datasources`, `#register-result` target — success is §5.1 Shape A: the success node closes the modal, the refreshed list and the toast ride along out-of-band, no `HX-Redirect` and no page reload). **A table partial travels as a whole `<table>` on any out-of-band path**: a `<tbody>` (or `<tr>`, `<td>`…) carrying `hx-swap-oob` nested in a `<div>` is silently DISCARDED by the browser's HTML fragment parser — table-only tags outside table context are dropped tokens, so the swap "succeeds" with empty content and no error anywhere (030 F-1; §4.10's keys table is the reference shape) |

Content: table of the ACTIVE workspace's datasources (name + `readonly` badge, dialect badge, workspace column — `global` or the bound name, URL, username, **last test**). Per-row "Test" button (`author` scope) → connection result as a §5.1 Notifications toast; the table itself is never re-rendered mid-interaction. "Register Datasource" button → modal form with: name, display name, dialect dropdown, JDBC URL, username, password, description, and two checkboxes — `readonly` (always settable, [Datasources §5.7](datasources.md#57-readonly-datasources-flag-semantics-and-enforcement-layers)) and `global` (**admin-only; visible-disabled for everyone else**, workspaces D8: unchecked binds to the active workspace). The register action applies the SAME D8 rules as REST §9.1 (`DatasourceWorkspaceRules` — one component, two surfaces) and crosses the same registry save boundary. A register REFUSAL stays inline in the modal (the screen-local `htmx:responseError` path — the modal must not close over an error, so it deliberately carries no `HX-Retarget`).

**The Last test column (061/T84).** Each row shows the outcome of the LAST connection test against that datasource ([Datasources §8.1B](datasources.md#81b-the-last-tests-outcome-is-stored-and-listed)): a `ds-badge-success` **ok** or `ds-badge-danger` **failed** badge, the timestamp, and the driver's message in the cell's `title`. A datasource nobody has probed says **never tested** rather than rendering blank, because an empty cell on a health column reads as health. This column exists because LISTING a datasource does not connect to it: on 2026-09-02 this screen showed `sample-trips` as fine while every demo pipeline failed at CONNECT with `password authentication failed` — the screen could be silent but not wrong. Nothing on this screen polls; the column shows what the last test (the Test button, the REST probe, or the startup bootstrap check) found.

The listing is workspace-scoped exactly like REST §9.2 (`listVisible`: active-bound + global, repository-level); a datasource bound to another workspace is absent, and its by-name test behaves as not-found. Rename is not offered — a datasource name is immutable (delete + re-create, blocked while referenced — including by a HISTORICAL pipeline version, [Datasources §6.2](datasources.md#62-in-use-check-on-delete)). The search covers every column the table renders (the §5.1 Search rule): name and the `readonly` badge, the dialect wire value, the workspace column (the bound name or the literal `global`), the JDBC URL, the username, and the last-test state (`ok` / `failed` / `never tested`, so "show me the broken ones" is a search) — plus `description`, searchable though only the modal shows it.

**The Connection pool section (094).** The register modal and the edit dialog each carry a `<details>` labelled **"Connection pool — defaults"**, collapsed. Expanded it renders the eight tunable HikariCP keys ([Datasources §5](datasources.md#5-connection-pool-configuration)) — each with its unit, a one-line meaning, and a help line naming the layer the prefilled default came from ("Default 10 — HikariCP's own", "Default 2 — this server's"). `readOnly` is rendered DISABLED, mirroring the datasource's own `readonly` flag: it is §5.6-refused as a pool property, so a free field would be an input whose every value the validator rejects. Refused keys are never rendered at all. **A field left at its default is not persisted**, so a later change to a product default still reaches datasources created through this form. The dialect select re-fetches the section (`hx-get="/partials/datasources/pool-fields"`, `hx-target="#ds-pool-fields"`, `outerHTML`) because the effective default is the DIALECT's to decide. Out-of-range values are refused server-side and render inline like any other save refusal.

**Edit (094).** A per-row **Edit** action (`author`) fetches the dialog into the screen's single empty `#ds-dialog` container. The `name` and `dialect` are shown disabled — the name is immutable and re-pointing a live datasource at a different driver would silently take every pipeline that references it by name along. A blank secret KEEPS the stored credential (the stored one is never rendered back, [Datasources §7.1](datasources.md#71-encryption-at-rest)). The `global` checkbox is admin-only and carries a hidden companion field, because an unchecked HTML checkbox posts nothing and an admin must be able to un-global a datasource. Same D8 rules and same `registry.save` boundary as REST §9.4.

**Delete (094).** A per-row **Delete** action opens a dialog that asks the USAGE question FIRST, from the same any-version scan the REST `409 datasource.in_use` uses ([Datasources §6.2](datasources.md#62-in-use-check-on-delete)):

1. A member on a GLOBAL datasource is told admin is required, and nothing else is offered.
2. In use → the referencing rows, `pipeline › node (v3 released)`, with links. **There is no confirm button on this branch**, so the refusal cannot be clicked past.
3. Unused → a confirm that NAMES the datasource, and says that queries already running finish first (the §5.2 retirement lifecycle is what makes that true).

The POST re-runs the guard regardless: a pipeline can start referencing the datasource between the dialog opening and the button being pressed, and the screen is never the authority. Success is §5.1 Shape A — the success node closes the dialog, the refreshed list and the toast ride along out-of-band. Both dialogs are delivered as a WHOLE backdrop rather than a body swapped into a pre-rendered shell: `.u-backdrop` is `display: flex`, so the dialog is on screen the moment htmx swaps it, with no open() script to fall out of step with the markup, and closing is emptying the container. A 4xx refusal renders inline and does NOT close the dialog — the success node carries a `data-ds-saved` marker the refusal never has, which is the same distinction the register modal draws with `data-error` (022/F9).

**Rendered for (114).** Register, Edit, Delete and **Test** are all `canAdminWorkspace` — [Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative) puts `MUTATE_WORKSPACE_DATASOURCES` and `TEST_DATASOURCE` on the `ws_admin` role, not on `author`: a datasource is a live database credential, and testing one is not a read (it opens a connection with the stored secret and writes the health row). An author sees the list, the badges and the Last test column, and no action column at all. Register additionally needs the DEPLOYMENT's `member-datasources-enabled` gate — two gates, both required — so a non-admin on a locked-down server sees no Register even if their role would allow it (the demo shape: open datasource creation is an SSRF primitive from the server's network position). The `global` checkbox's admin-only rule is unchanged.

**§4.13's workspaces screen** owns workspace lifecycle; **[§4.5a](#45a-datasource-grants-114)** owns which workspaces can SEE a datasource.

### 4.5a Datasource grants (114)

| Attribute | Value |
|---|---|
| URL | `GET /partials/datasources/{name}/grants` (a dialog into `#ds-dialog`) |
| Auth required | Yes — **super admin** (`MANAGE_DATASOURCE_GRANTS`, [Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative)) |
| Purpose | Decide which workspaces can see a datasource at all (RBAC design §4, D-R7) |
| Design primitives | `.u-backdrop`, `.ds-card`, `.ds-table`, `.ds-badge`, `.ds-empty`, `.ds-select` |
| htmx | Yes — the same whole-backdrop-into-`#ds-dialog` contract §4.5's edit and delete dialogs use (094 §A/§B); each mutation re-renders THIS fragment, so the table and the select stay in step with the rows just written |

**Visibility IS the grant.** Round 1 replaced `datasources.workspace_id` (NULL = "global") with
`datasource_workspaces`: a datasource is registered once — credentials are instance secrets — and
granted to N workspaces. There is no global datasource. A workspace with no grant does not see the
row at all: `datasource.not_found`, never a 403, so the name cannot be probed.

Opened from the datasources list's per-row **Grants** button. It shows every workspace the
datasource is granted to (name, granted by, when), a Revoke per row, and an add form whose
`<select>` lists the ACTIVE workspaces that do not already hold a grant — re-granting is a legal
no-op, and an option whose only outcome is "no change" is noise. The registering workspace's own
grant (created automatically when a workspace admin registers a datasource) is **marked** with a
badge and is still revocable: the REST surface allows it, and a screen that refuses what the
server allows is the mirror of the defect §4.3e exists to remove.

**Why super admin and not workspace admin.** The grant LIST names every workspace on the instance
that holds one, which is exactly the cross-workspace disclosure D-R5 withholds from everyone below
super admin — the same reason `DatasourceGrantsController`'s READ carries the operation too. A
workspace admin still registers datasources bound to their own workspace (§4.5) and gets that
workspace's grant automatically; handing one to somebody ELSE's workspace is this screen.

Every grant and revoke is audited (`datasource.granted` / `datasource.revoked`), which is D-R7's
"every grant is audited" — the row records who and when, and the event records the decision.

### 4.5b Datasource learned facts (118)

| Attribute | Value |
|---|---|
| URL | `GET /partials/datasources/{name}/facts` (a dialog into `#ds-dialog`); the same table inline on the LAKE datasource detail page (`GET /datasources/{name}`, the 089 §A catalog tree — not separately catalogued here) |
| Auth required | Yes — any member (`READ_RESOURCES`): the facts are a READ, and which rows a workspace sees is the store's own predicate, not the screen's |
| Purpose | Show what agents LEARNED about a datasource that its schema could not say — units, time zones, sampling, grain, what coded values mean, joins, caveats — with trust badges ([learned-semantic-layer design](superpowers/specs/2026-09-11-learned-semantic-layer-design.md) §7.3) |
| Design primitives | `.u-backdrop`, `.ds-card`, `.ds-table`, `.ds-badge`, `.ds-empty` |
| htmx | Yes — the whole-backdrop-into-`#ds-dialog` contract of §4.5's dialogs (094 §A/§B, 114 §C.2) |

**Read-only in round 1.** One row per fact, oldest first: the OBJECT it is about (`schema.table.column`), the kind badge (plus a `workspace` badge on a WORKSPACE-scope fact — one organisation's meaning, D-S1), the fact text (plus a `conflict` badge when another live fact of the same kind sits on the same refs — D-S5: both shown, neither wins), the TRUST badge — `observed`/`verified` success, `needs_review` warning, `stale` danger, `asserted` default — with the drift message under it when the read-time check demoted it (§6: "column X no longer exists"), the evidence summary (or "none — asserted"), and the provenance: when, through what (`mcp`/`session`/`api_key`), a `via another workspace` badge when the active workspace did not record it, and the source pipeline as a link ONLY when this workspace can read it (D-S9). Recording, verifying and retiring from the UI are round 2; the empty state says so and names the verbs that exist today (the `semantics_*` MCP tools).

**Rendered for.** The row's **Facts** button is every member's — it is `data-read`, not `data-verb`, because a viewer's datasources screen renders no verbs (§4.3e) and this button changes nothing, exactly like the LAKE row's **Tables** link. The dialog and the detail section render the same fragment (`partials/datasource-facts`) from the same model (`DatasourceFactsModel`), so the two cannot drift. An invisible datasource answers the inline refusal an unknown name gets (the §5.3 gate, before the service runs).

### 4.6 Template List (the template EXPLORER — tree left, selected template right)

| Attribute | Value |
|---|---|
| URL | `GET /templates` |
| Auth required | Yes (`read` to browse; `author` to create — the verbs are role-gated per [§4.3e](#43e-role-visibility--every-verb-and-the-flag-that-renders-it-114-normative), and Release is `canPromote`) |
| Purpose | Browse the template tree, select a template to see its versions, filter by dialect and type, search |
| Design primitives | `.ds-badge`, `.ds-input`, `.ds-empty`, `.ds-button`, native `<details>`/`<summary>` + `static/css/template-tree.css` (structure and truncation only — every colour, size and gap is a token; the 041 two-pane layout math, `--header-height` viewport fill) |
| JS | The create modal's lifecycle (open/close, inline refusal, dialect-conditional-on-type), **and** `static/js/template-explorer.js`: selection, roving tabindex, and keyboard — expansion is still `<details>` + htmx, no JS of our own |
| htmx | Yes — see the fragment contract below |

A template name is a **path** ([template-hierarchy-design §4.1](template-hierarchy-design.md#41-grammar)), so this screen is a tree. **Folders are virtual**: a folder is a name prefix with no table, no column, no id and no CRUD ([§3.1](template-hierarchy-design.md#3-design-principles)). There is no "New folder", no rename, no move, no delete and **no empty-folder state** anywhere on this screen — a folder is derived per request from the live rows beneath it, so one with nothing beneath it does not exist to be rendered. `TemplateTreeRenderTest` asserts those absences, because an absence is exactly what a well-meaning future round removes without noticing.

**The layout (058; geometry corrected 059, 2026-09-02).** Two panes, full height below the page header, on the **viewport's width** — the owner's spec was Windows file explorer: *"tree on the left and when click on the leaf, table should show up on the right."* LEFT (a share of the viewport — `clamp(260px, 22vw, 40rem)` by default — **resizable by the divider handle, §4.3a**, floor 260px): the tree, folders with carets, leaves with type/dialect badges, indentation per depth, the selected leaf highlighted. RIGHT (the `1fr` remainder, to the right edge — the content TOP-ALIGNED with the tree's first row; the app canvas's centered cap does not apply to this screen, and the quiet empty/not-found states still centre): the selected template — header (full path, badges, Open-in-editor) above the versions table (version, status badge, in-use count, created). Nothing selected: the quiet `Select a template` state, not a blank panel. **A selection swaps `#template-detail`'s innerHTML and nothing else** — the tree pane's DOM is untouched by a selection swap, which is the whole point of the layout; `TemplateExplorerRenderTest` pins this at the fragment-contract level (the detail fragment contains no tree markup, no tree swap target and no OOB swap, so nothing a selection returns could alter the tree).

**The fragment contract.** One route, `GET /partials/templates`, answers two shapes, chosen by the presence of `prefix`:

| Request | Fragment | Swap |
|---|---|---|
| no `prefix` | `partials/templates` — a dispatcher whose one root element is `#template-list-wrapper` in **both** presentations | the filter controls' `hx-target`, `outerHTML` |
| `prefix=acme/finance` (empty string = the root) | `partials/template-tree-level` — that ONE level: its direct sub-folders and its direct template children | the folder's own child container, `outerHTML` |
| `GET /partials/templates/versions?name={path}` | `partials/template-detail` — the SELECTED template, in 106's three regions (below) | `#template-detail`, `innerHTML` — the tree is untouched |
| `GET /partials/templates/runs?name={path}` | `partials/template-runs` — the acting column's Runs tab, on its first click | `#template-tab-runs`, `innerHTML` |

Every level is a **server-side prefix query** ([§8](template-hierarchy-design.md#8-repository-registry-loader)). The flat list is never shipped to the browser and no tree is assembled in JS, at any size ([§9.1](template-hierarchy-design.md#91-constraints-the-ui-inherits-normative--none-of-these-are-ui-choices)). A folder expands with `<details>`/`<summary>` — the browser owns open/closed state and the a11y semantics — and the request rides on the **summary** with `hx-trigger="click once"`, targeting the placeholder below it with `next`. The obvious alternative, the request on the placeholder with `hx-trigger="toggle from:closest details"`, works at the root and silently does nothing for a folder that arrived in a swap (measured on the demo stack: the nested level's request never fires); `click` needs no `from:` indirection and no non-bubbling event, and `<summary>` raises it for keyboard activation too. Nested level containers carry an id derived once, server-side, from the prefix (`TemplateBrowseModel.levelId`), so the placeholder a folder renders and the root of the fragment that replaces it cannot disagree; the ROOT level's id is the screen's long-standing `#template-list-wrapper`, so the tree inherits the §4.5/§5 swap contract rather than inventing a second one — and it sits inside the stable `#template-tree-pane`, so every swap replaces level CONTENT and the panes never move. Names never travel in a URL path segment — `prefix` and `name` are query parameters ([§9.6](template-hierarchy-design.md#96-addressing-the-name-never-travels-in-a-url-path-segment-normative-measured)).

**Keyboard and ARIA.** The root level's list is `role=tree` (a nested level's container is `role=group` under its folder's `treeitem`); search results are `role=listbox`/`option`. ArrowUp/ArrowDown move selection (the detail pane follows), ArrowRight expands a collapsed folder, ArrowLeft collapses an expanded one or goes to the parent, Enter opens the editor (the row's `data-editor-url`), Home/End jump to the ends. `template-explorer.js` owns `aria-selected`, `aria-expanded` (kept truthful through a capture-phase `toggle` listener) and the roving tabindex, initialising on load and after every htmx swap that lands in the left pane — selection is client state; the server renders the roles and seeds `aria-selected="false"`. Leaves and search rows carry `hx-sync="#template-detail:replace"`: a rapid keyboard sweep cannot race a stale detail load into the pane — the last selection replaces the in-flight request (keyboard loads are additionally debounced, so a sweep fires one request, not one per row).

**Browse and search are different presentations** ([§9.2](template-hierarchy-design.md#92-templates-browser--tree-presentation), decided). Browsing shows the tree. A non-empty `q` shows a **flat result list of full paths** in the LEFT pane — the same row shape as tree leaves, because the pane is 30% wide and a seven-column table is not what it is for — and selecting a result fills the right pane exactly as a tree click does. It is NOT a tree pruned to matching leaves, because pruning means walking the ancestors of every match, which is precisely the whole-list-in-the-browser work the tree exists to avoid, and a flat list of full paths is what someone searching `finance/agg` wants to see. Clearing `q` returns to the tree, by construction: the same dispatcher answers both.

**Paging.** Each level pages its own leaves through the shared §5 pager, targeting that level's own root, so `Showing N of M` is that level's truthful count and not the workspace's. A level renders that pager only when it can act (`offset > 0` or `hasMore`): a tree shows many levels at once and most hold a handful of rows, so an always-on "Showing 1 of 1" with two dead buttons is noise repeated down the whole screen. The flat search list keeps the unconditional pager — there the count is the answer to the search. Sub-folders are a `GROUP BY` over one path segment and are not paged; past 200 at one level the fragment says so rather than cutting silently. The root level holds **folders only** since 077 ([§4.1](template-hierarchy-design.md#41-grammar)): a name carries a folder, so nothing sits directly at the root and neither browse model queries for it. A pre-077 flat PIPELINE can still exist — that name is validated at save only, so it has no migration gate — and it stays reachable by search, by `pipelines_list` and by its own URL; it is simply not a row the root level draws. Nothing is renamed or reorganised — §4.5 forbids it.

**The detail pane is the pipelines explorer's twin (106; the shape and the breakpoints are
§4.3b and §4.3c, and they are not restated here).** Header: the folder path as an eyebrow, the
leaf as the title, Open in editor plus 101's verbs as §4.3d's template twins in `#tx-dialog`
(`Release v<n>…`, Discard, Purge template — no Switch: templates are pinned by version, and
there is no served pointer to switch). Reading
column: *Overview* — the chips (`v<n>`, draft/released, `type`, `dialect`, `engine`), the
description, the **References** row and the first 12 lines of the current body with a jump to
the Source tab; and *Used by* — every pipeline pinning any version, with the version, from
`TemplateUsageService.referencedAnywhere`, which is the same evidence the `template.in_use`
delete guard answers with. Acting column: **Versions · Source · Runs**. The purge dialogs'
typed confirm names the version, and the entity purge's names the template's NAME — §5.1's
typed-confirm convention.

Versions and Source are both in the FIRST PAINT — the working body and the version list are
already read to render the header, so a second request would buy nothing; only Runs is lazy.
The Source tab renders the full body read-only in its own `<pre>` and deliberately does NOT
reuse the editor's `template-source` fragment: that fragment is the editor's source COLUMN, a
textarea with an Edit button and the preview pane, and putting an edit surface inside a read
pane is the affordance 067 spent a round removing.

**References is not "parameters".** A template declares no parameter schema anywhere in this
system — only a pipeline does — so the card lists the identifiers the body INTERPOLATES,
labelled as that. Directive variables (`<#if …>`, loop variables) are deliberately not scanned:
a loop variable is not an input, and listing one would invent a contract.

**Runs is derived, and the derivation is stated in the fragment.** An execution row names a
pipeline and a version, never the templates its nodes rendered, so there is no execution →
template edge. The pins give the pipelines and the pipelines give their recent runs (the
fan-out over pinning pipelines is capped — `TemplateBrowseModel.USED_BY_FANOUT`). It answers
"has anything that uses this template run lately", which is the question someone about to
change a template is asking; it does not claim each run rendered this body, because a pipeline
version pinning v1 does not re-render when v2 lands.

The per-version **in-use count stays** (040 D6 — distinct pipelines pinning that version in
their working version) but it is now a phrase in the row rather than an "In use" table column,
worded by the model so the pipelines' "7 runs" and the templates' "2 pipelines" cannot be
confused for one another.

**Filters.** `dialect` and `type` (046's column) are exact matches on the version row and travel with every level and pager request; both narrow the folder derivation too, so a folder whose whole subtree is filtered out is absent rather than empty. The search covers every rendered column (the §5.1 Search rule): id/path, display name, description, and the dialect badge's wire value — the dialect match is repository-level (`ILIKE` on the version's dialect), so a `sqlite` query finds templates whose names never mention it.

**Create** (`author`/`admin`) is a modal posting to `POST /partials/templates`, §5.1 Shape A: the success node closes the modal and the refreshed root level rides along out-of-band with the toast. It gains a **`type` selector** (`sql` default) and makes `dialect` conditional — required for `sql`, disabled and absent for `html` (the control is *disabled*, not merely hidden, so it does not post; the controller drops it either way and `chk_type_dialect` is the database's backstop). The name field's `pattern` and `maxlength` are **rendered from the server's own grammar** (`TemplateNameGrammar`, which reads the validator's `Regex` and its cap) — never a regex retyped beside it; the server validates every write regardless and its rejection is the one that counts ([§9.5](template-hierarchy-design.md#95-client-side-name-validation-is-a-convenience-never-an-authority)). There is no rename affordance on this form or anywhere else: `name` is a create-time input, full stop.

### 4.7 Template Editor

| Attribute | Value |
|---|---|
| URL | `GET /templates/editor?name={path}` (rest-api §8 addressing: the name never travels in a URL path segment) |
| Auth required | Yes (`read` to view; `author` to save or render a preview; Release is `canPromote` — [§4.3e](#43e-role-visibility--every-verb-and-the-flag-that-renders-it-114-normative)) |
| Purpose | Edit template body (Freemarker SQL), preview rendered SQL, manage versions |
| Design primitives | `.ds-card`, `.ds-button`, `.ds-code-block`, `.ds-form` + the 041 layout in `static/css/template-editor.css` — render context in a left rail (`--app-detail-width`), source column filling the viewport below the header (`--header-height` math), preview output below the editor at ~2/3 · 1/3 |
| JS | Light — tab switching (edit / preview), add/remove context rows, key-value ⇄ JSON toggle; the preview output is highlighted with the shared dependency-free SQL tokenizer (`js/pipeline-editor/sql-highlight.js`, 032) via `js/template-editor/preview.js` — the editable textarea is deliberately plain (highlighting an editing surface needs an overlay/contenteditable round of its own) |
| htmx | Yes — the version `<select>` swaps the source column (`hx-get="/partials/templates/editor/source?name={path}"`, the select's own `version` riding along, into `#template-source`, `outerHTML`) and **Edit** posts to `/partials/templates/editor/edit` (`#tpl-edit-refusal`, `innerHTML`; success answers `HX-Redirect`). "Render Preview" posts to `/partials/templates/render?name={path}&version={v}`, rendered into `#preview-output` |

Content:
- **Rendered for (114):** Release is `canPromote`, Purge draft and the read-only version's **Edit** (start a draft from a release) are `canAuthor`. A viewer's editor loads read-only with the same `.app-note` line the pipeline editor carries.
- **Draft lifecycle actions (102):** Release / Discard draft open the §4.3d template twins in `#te-dialog`, addressed by NAME in the query (§9.6). Success answers `HX-Redirect` back to the editor with a flash toast; refusal is §5.1 Shape C. The pre-102 inline `tplLifecycle` script carried the native `confirm`/`alert` pair and is gone (the static zero-native-dialog test covers this file).
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
- **Phone widths (110): desktop-first by decision, not omission.** Below 768px the page renders a `.app-wide-screen-note` band above the editor — "Open on a wider screen to edit", the template's name, its draft badge (the same `hasDraft`/`draftVersion` pair the header renders) and a link back to the Templates explorer — with the editor itself left rendered underneath.

### 4.8 Execution History

| Attribute | Value |
|---|---|
| URL | `GET /executions` |
| Auth required | Yes (`read`) |
| Purpose | Browse past executions, filter by pipeline/status/date |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-input` |
| JS | None |
| htmx | Yes — filters (pipeline, status, date range), pagination (`hx-get="/partials/executions"` into `#execution-table`, `innerHTML`, with `hx-include="#execution-filters"` re-sending the filter form by id; the pager offsets are server-rendered into `hx-vals` via `th:attr` literal substitution) |

Content: table of executions (pipeline **display name** — machine path on hover, T114 —, version **with a `DRAFT` label when that version was a draft when it ran** (versioning §8's derivation, `data-draft-run` for a test to read), status badge, triggered_by, triggered_via badge, started_at, duration). The label matters more since D55: a v1 run is routinely a draft run, so the number alone would not say whether a result came from reviewed content. The execution detail screen carries the same badge beside its `v1`. Clickable → execution detail. This screen deliberately keeps its own `#execution-table` / `innerHTML` / `hx-include="#execution-filters"` contract rather than adopting the §5 outerHTML one — it satisfies every §5.1 guarantee (stable target, controls outside the fragment, spinner, toasts), and the pager's `hx-vals` offsets are server-rendered via `th:attr="hx-vals=|{...}|"` (a plain-attribute `[[...]]` inlining reaches the browser unprocessed — Thymeleaf processes inlining in text nodes, not attribute values).

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
- **Node stats table**: per-node status, duration, rows_out, error — and, for a CALCULATOR node, a **Context** column showing `context_key → computed value` (mono; `node_stats_json` has carried the pair since 072 — the run detail page now actually reads it, which `pipeline-contract.md` and `dag-executor.md` always claimed).
- **Error details** (if failed): the structured failure record (057) — code badge, message, user_message, correlation id, node line, details JSON, doc_url link, the rendered SQL (`:name` form), and the exception chain collapsed root-cause-first with frames in monospace (`partials/execution-error`, shared with the result partial's failure branch). Not a raw JSON dump.
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
| Execution has no caller node | "This execution produced no caller result." (a pure write-back pipeline is legal and emits no result — [Pipeline Contract](pipeline-contract.md)) |
| Execution **failed** (`410 result.execution_failed`) | The structured failure record — the same `partials/execution-error` fragment the Error card renders (057): code, message, correlation id, node line, SQL, root-first exception chain. One bare string never substitutes for the record again |

- **Preview / pagination**: each pager click is an htmx `GET /partials/executions/{id}/result?offset=…&limit=…` that swaps the table body; server-side it is the same cursor with the same ownership check. Row order is stable across pages because the result was fully materialized before the cursor existed.
- **Effective expiry** is shown next to the panel title (from the cursor's `expires_at`). Expiry is **fixed at result-write time** — paging through the preview does not extend it, so a long browsing session can hit the expired state mid-way; the panel then swaps itself to the expired card.
- **Download buttons** (JSON / CSV / Arrow) are the *same* cursor endpoint with a different `format` parameter — not three separate mechanisms. They are plain `<a href>` links to `/api/v1/executions/{id}/result?format={json|csv|arrow}` (the download carve-out in §2.1), and they are hidden once the TTL has elapsed.

For anything that must outlive the TTL, the answer is not a longer TTL: write it back with `output.target: "datasource"` ([REST §7.1](rest-api.md#71-model)).

### 4.10 API Keys — the link (091)

**This screen is a LINK now.** Issuing, listing and revoking keys moved to §4.18's API section,
where the endpoints those keys call and the MCP connection they authenticate already live: "the
keys that may call this deployment" and "the endpoints they may call" are one question, and
keeping them a page apart made the API screen a read-only shadow of a settings page nobody
found. This screen renders one sentence and a link to `/api-console`.

| Attribute | Value |
|---|---|
| URL | `GET /settings/api-keys` |
| Auth required | Yes — any authenticated principal (the key VERBS live on §4.18: New key is `canAuthor`, Revoke is `canRead` — [§4.3e](#43e-role-visibility--every-verb-and-the-flag-that-renders-it-114-normative)) |
| Purpose | Point at §4.18. It reads no keys at all |
| Design primitives | `.ds-card`, `.app-empty`, `.ds-button` |
| JS | None |
| htmx | No |

**Why the route survives.** The avatar menu, `AppNav`'s off-rail breadcrumb table and every
bookmark point here; answering them with a 404 to save one template would be a worse deal than
rendering a sentence and a link. (The avatar menu's "API keys" entry and Settings' API card both
already target `/api-console`, so the only way here is a bookmark.)

### 4.11 User Settings

**Amended 079 §D** — a two-column card grid at ≥1100px (Profile, Appearance, Session,
Password, API), one column below; `.app-reading` is gone from this screen (§3.1). Scopes render
as chips, not four spans strung across the card. Appearance gains a Mode row (Light / Dark /
System) alongside the Theme select — **two views of ONE field**, see §3.4 — and a Density row
whose Compact option is rendered DISABLED: the preference has nowhere to live until `users`
grows a column, and a control that silently changes nothing is worse than an absent one. The
`#themeSelect` id is load-bearing beyond this screen: `siteShots`' `app` set drives its dark
pass through that exact select, because it is the control a user has. The API-keys card became
a link to §4.18, and the page-foot Logout button is gone — logging out is a shell action and
lives in the avatar menu, where every screen has it.


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
- **Create local user** (rendered only when local accounts are enabled): email + optional display name, plus the OPTIONAL workspace + flags (113 §B.3, [Auth §4.6](auth.md#46-invitations)) — a workspace name field and three checkboxes (`author`, `promoter`, `admin`). When the workspace is named, the new account's membership is written in the same act: no invitation is needed because the user row exists by the time the membership is written ([Auth §4.6](auth.md#46-invitations) rule 1), and a workspace the super admin cannot add to is refused as a toast note while the USER ROW still stands (the fix is the workspaces screen). The server generates a random one-time password shown to the admin exactly once (out-of-band notice — PERSISTENT and inline per §5.1's hard rule; the success toast only points at it) with `must_change_password = TRUE` — there is no email flow, so the admin conveys it out-of-band ([Auth §5A.1](auth.md#5a1-accounts)). A taken email answers `409` and an invalid email `400`, both as danger toasts (§5.1 Shape C) — before the toast bridge existed these refusals were invisible: htmx never swapped the 4xx bodies and the screen had no error listener.
- **Reset PW** issues a new one-time password under the same rules (and clears any lockout); **Disable local** clears the hash (account becomes OIDC-only); **Unlock** clears the lockout only. The `Local` column shows `local`, `local · locked`, or `—` (OIDC-only).
- Deactivation copy states the effect window: existing JWTs and API keys stop working within the liveness-cache TTL (~60s), not instantly and not at JWT expiry.
- Scopes are derived, not assigned, in v1: `is_admin` → `admin`, every other active user → `author` ([Auth §7.5](auth.md#75-scopes)). So the "grant admin" toggle *is* the scope control — there is no per-user scope editor to build.

### 4.13 Workspaces (workspaces design §9; members and deactivation rewritten by 114)

| Attribute | Value |
|---|---|
| URL | `GET /workspaces` |
| Auth required | Yes. Per-verb: members and the display name need `canAdminWorkspace` **in the ACTIVE workspace**; create, deactivate, reactivate and delete need `isSuperAdmin` ([§4.3e](#43e-role-visibility--every-verb-and-the-flag-that-renders-it-114-normative), [Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative)) |
| Purpose | See the workspaces you belong to, switch between them, manage the members and roles of the one you are in, and — for a super admin — create, deactivate, reactivate and delete |
| Design primitives | `.ds-table`, `.ds-badge`, `.ds-button`, `.ds-input`, `.ds-card`, `.ds-empty`, `.app-note` |
| JS | One `onchange` submit on the rail switcher (`<noscript>` fallback button included), and one delegated listener that ticks `author` when `admin` is ticked |
| htmx | No — plain CSRF-protected form posts with `redirect:` outcomes (`?ok=`/`?error=` query state, the login screen's idiom), rendered into the layout's `#toast` stack |

Content, in order:

**A create form**, super admins only (D-R11 removed the provisioning modes, and with them
`workspace.creation_forbidden` and the joinable list; 114 removed the stale "provisioning mode on
this server" footnote and the dead join section that survived them).

**Your workspaces** — name, the role you hold there, the active marker, and the verbs. Every
workspace on the instance for a super admin, with a `inactive` badge and a **Reactivate** verb on
the deactivated ones, because this is the screen that brings one back; a member never sees a
deactivated workspace at all ([Auth §11A.3](auth.md#11a3-deactivation) — deactivation must not be
a signal anybody can read). **Deactivate**, **Reactivate** and **Delete** are super-admin verbs
(`MANAGE_INSTANCE_WORKSPACES`); deactivation purges nothing, ever, and Reactivate is one row away,
which is why neither carries a typed confirm. The role label is DERIVED from the three flags
(`viewer` / `author` / `promoter` / `author · promoter` / `admin`) by the same `RoleModel.labelOf`
the shell badge and the members table use, and is stored nowhere: no single word names an additive
row (D-R2).

**Members of the ACTIVE workspace** (114 §C.1) — one section, name/email, the derived role label,
**three checkboxes** (`author` / `promoter` / `admin`) with a Save, and Remove. The add form at the
foot carries the same three, so a member arrives with the role the operator meant: before 114 it
posted `email` only and every member added from the UI was a viewer. Both verbs go through the SAME
`WorkspaceService` methods the REST surface calls (`addMember`, `setMemberFlags`) — there is no
second code path, so the last-admin rule and the `admin → author` normalisation are enforced once.

- The flags form is a **REPLACE**: an unticked box is a role being taken away, and a form that
  posted only what was ticked could never express a demotion.
- `admin` implies `author`. The checkbox script ticks `author` when `admin` is ticked so the form
  SHOWS the row it is about to write; the server normalises regardless and the database constrains
  it (`chk_workspace_member_admin_authors`), so the script is a courtesy and never the rule.
- **The last admin cannot be demoted or removed** — `409 workspace.last_admin`, rendered as a §5.1
  error toast that names the remedy ("A workspace needs at least one admin. Give another member
  admin first."). A workspace nobody administers cannot be repaired from inside it.
- **Only the ACTIVE workspace gets a member table.** `ScopeInterceptor` judges
  `MANAGE_WORKSPACE_MEMBERS` against `principal.workspace`, never the workspace named in the path,
  so an admin of X working in Y had every member form for X rendered and every one of them refused
  — invisibly, because the interceptor answers before the handler and htmx does not swap a 4xx.
  The other administered workspaces are named in an `.app-note` with the Switch that reaches them.
- **Pending invitations** (113, wired at the 113/114 merge): ghost rows under the members
  table — email, an `invited` badge, the role the invitation carries, "Becomes a member at
  first sign-in", and Revoke (`POST /workspaces/{name}/invitations/revoke`, email as a form
  field, `MANAGE_WORKSPACE_MEMBERS` — the same guard as the member rows). Never mixed into the
  member rows. The add form's outcome toast distinguishes `member_added` from `member_invited`
  (no account with that email yet), so "added" is never said of someone who cannot sign in.

The **switcher in the rail** (§3.4) drives the active workspace and carries the role badge; the
screen's Switch buttons POST the same `/workspace/switch`. Expected refusals and successes alike
render as §5.1 toasts; the generic error page is reserved for the unexpected (§6).

**The no-workspace state** — a principal with zero ACTIVE memberships (never added, removed from
the last one, or every workspace they belong to deactivated) gets `workspaces/none` instead of an
empty list: what happened, who to ask, and — super admins only — the create form. It is
deliberately not an error page: nothing failed, and `error/403` would name the wrong problem.
Reachable at the 113/114 merge: `ScopeMatrix.allowed` lets a SESSION through `WORKSPACES_READ`
with no workspace context ([Auth §11A.1](auth.md#11a1-the-404-rule)) — "list the workspaces you
belong to" is the one operation that is meaningful with none. Every other governed route still
answers such a principal `404 workspace.not_found` before any handler runs, and a key never
gets the exception.

### 4.14 Change password (local accounts)

| Attribute | Value |
|---|---|
| URL | `GET /settings/password` |
| Auth required | Yes (any authenticated session with a local password) |
| Layout | `layouts/default` for a voluntary change; **`layouts/auth`** when `must_change_password` is set (090 §C) — the controller returns `settings/password-forced`, and both views render one `partials/password-card` fragment |
| Purpose | Self-service password change — and the one screen the §5A.4 forced-change gate lets a `must_change_password` user reach |
| Design primitives | `.ds-card`, `.ds-input`, `.ds-button--primary` |
| JS | None |
| htmx | Yes — `hx-post="/partials/account/password"` (success is §5.1 Shape B, toast-only; failures stay inline in `#password-change-result` via the screen's own 4xx listener) |

Content: current / new / confirm fields with the policy floor stated inline (at least 12 characters, [Auth §5A.5](auth.md#5a5-enumeration-resistance-and-the-password-policy)). A `must_change_password` user additionally sees the one-time-password warning banner — every other route redirects here until the change succeeds ([Auth §5A.4](auth.md#5a4-forced-password-change)). An account without a local password (OIDC-only) sees an explanatory note instead of the form. Success is a §5.1 toast (Shape B); failure outcomes are field-level/credential validation and stay INLINE in `#password-change-result` — wrong current password, policy violation, confirmation mismatch — delivered by the screen's own `htmx:responseError` listener, because htmx never swaps 4xx and these refusals deliberately carry no `HX-Retarget` (this is the one screen where Shape B and inline errors coexist); the forced-change gate releases on the next navigation.

**Two views, one card (090 §C).** A user held here by `must_change_password` is inside the authentication ceremony, not on an app page: the §5A.4 interceptor refuses every other route, so the shell's rail would be ten links that all bounce straight back. That user gets `settings/password-forced` and the auth layout; a voluntary change from Settings keeps the shell. The choice is made in `UserSettingsController`, **not** by a conditional decorator in the template — Thymeleaf resolves `__${...}__` preprocessing while PARSING and caches the parsed template, so the first request through would freeze the layout for every request after it (measured, 090).

### 4.15 Marketing site (public)

| Attribute | Value |
|---|---|
| URL | `GET /` |
| Auth required | No — the one public page (with `/site/**` assets); everything else defaults to authenticated |
| Purpose | The product marketing page, written for the BUYER (115): the sentence, the result, the API — hero, before/after, artifact, trust, outcomes, who, demo, roadmap |
| Design primitives | The vendored design system via `/vendor/design-system/**` (the same copy the app serves; the retired `website/` directory carried a second vendored copy) |
| JS | `static/site/js/site.js` — theme toggle + copy-to-clipboard only; fully readable without it |
| htmx | No |

**The screenshots are a command, not a folder** (070 §C, extended 093). `./gradlew siteShots -PshotsUrl=… -PshotsEmail=… -PshotsPassword=…` drives a REAL demo deployment with Playwright and overwrites `static/site/img/**`; `-PshotsHeroOut=<dir>` additionally writes the hero's poster (2400 px wide, device scale 2) and OG (1200x630) copies somewhere outside the app. It refuses rather than photographs: a promotion screen with no usable target, a full-length key still on screen, and — 093 — a pipeline-editor canvas whose node surface resolved to the brand colour are all SKIPPED with the reason printed, because the site's own rule is that a screen which renders broken is reported, not photographed.

Content: a single static page (`templates/site/index.html` + `static/site/**`), served by the app since v1.15 — the marketing site and the product are one deployment (owner decision 2026-08-31). The only dynamic fact, the MCP tool count, is baked at render time from `McpToolCatalog` (a compile-time constant — no DB access on any public route). Defence is `Cache-Control: public` on both `/` and `/site/**`, deliberately NOT the login rate limiter (OPEN-ITEMS T46: its `remoteAddr` key is the load balancer's address behind the documented deployment, so a limiter would let one client 429 the homepage). Signed-in users hitting `/` get the marketing page too — no auto-redirect; the dashboard is one nav link away at `/dashboard`. Emergency static fallback: `./gradlew :modules:web:websiteExport` renders the same template with facts baked ([Deployment §6.7](deployment.md#67-marketing-site--in-product-docs)).

**Since 073 the public site is fifteen pages, not one** (089 added `/dp-lake`). The homepage keeps its `<h1>`; its `<title>`, meta description, canonical and `og:`/`twitter:` tags now come from the `SitePages` registry, which is also what `/sitemap.xml` lists and what the SEO guards measure — one set of strings, three consumers. Each additional page is a Thymeleaf template under `templates/site/`, served by `SitePagesController` in the same shape as `/`: GET-only, anonymous, constant content, short public cache window, no datastore.

**Since 115 the homepage speaks to the buyer, and the engineering has its own page.** The home page's reader is a founder or product owner whose customers are asking to see their data, so it sells the outcome — the sentence, the result table, the URL the app calls — in a fixed section order: hero → before/after → the artifact → verified-by-you → what-you-get (today/next-month badges) → who-it's-for → demo → open source → roadmap → FAQ (eight buyer questions). Two house rules hold above the fold: the buyer's vocabulary only — never pipeline, DAG, federated, Iceberg, DuckDB, Parquet, MCP, JDBC or staging (`SiteBuyerLanguageTest` holds the fold from `<main` to the end of `#artifact` to that, and asserts the words landed on `/how-it-works` instead — the mechanism moved, nothing was hidden) — and show the artifact, not the process. Every sentence that touches the dashboard says "next month", in the roadmap page's dated `status` markup so the freshness guard covers the home page's promise too; there is no mocked dashboard screenshot. The engineering sections the homepage used to carry (engine strip, the agent loop, "What's in the box", the security teaser) moved verbatim to the `/how-it-works` engineering page, whose FAQ is `SiteFaqs.APIS_AND_OPERATIONS`; the home FAQ is now `SiteFaqs.HOME`'s eight buyer questions, which also open `/faq`.

| URL | Template | What it is |
|---|---|---|
| `/mcp-server-for-sql-databases` | `site/pillar.html` | The cluster pillar: the credential problem in the searcher's words, the six engines, the governance guarantees, the full MCP tool list |
| `/mcp-server/{postgres,sql-server,mysql,oracle,sqlite,duckdb}` | `site/engine.html` | Six routes, ONE template over the `SitePages.ENGINES` rows: dialect, driver, license, whether the driver is in the published image, client config, the seeded pipeline that reads that engine |
| `/add-mcp-server-to-claude-code` | `site/add-mcp-server.html` | The four-step client setup — run a server, mint a scoped key, paste one config block, check the tool list — with the 401/403 troubleshooting table |
| `/ai-data-pipeline` | `site/ai-data-pipeline.html` | What an agent-authored pipeline is here: real schemas in, a versioned JSON artefact out, run governed |
| `/text-to-sql-agent` | `site/text-to-sql-agent.html` | The after-state a text-to-SQL tool leaves out. States plainly that we do not generate SQL |
| `/compare/airflow`, `/compare/dbt` | `site/compare-airflow.html`, `site/compare-dbt.html` | Honest comparisons, each leading with "use them instead when…" |
| `/federated-query` | `site/federated-query.html` | The staging join, with `nyc/mobility/revenue_by_borough`'s real SQL and the limits of an in-memory staging database |
| `/dp-lake` | `site/dp-lake.html` | dp-lake (089): Parquet and Iceberg on S3 read in place, dp-catalog, partition pruning, the four-engine showcase pipeline, and the honest limits — egress, the Iceberg metadata-file rule, no scheduler |
| `/how-it-works` | `site/how-it-works.html` | The engineering page (115): where every term the buyer's home page keeps below the fold now lives — the opening mechanism paragraph, the engine strip, the agent loop, "What's in the box" and the security teaser, all moved verbatim from the former homepage, plus a "where to next" row and `SiteFaqs.APIS_AND_OPERATIONS` |
| `/demo-data` | `site/demo-data.html` | The demo data, table by table (116): what the three published sample-data families hold, where each came from, what to ask it, and the licence each ships under |
| `/pricing` | `site/pricing.html` | The pricing page (119 §C.4): there is no price — AGPL-3.0, self-hosted, every feature; what free costs you honestly; the no-paid-tier promise under the roadmap's dated `status` markup (the freshness guard covers it, so the page carries a `data-roadmap-updated` dateline); AGPL in two sentences; the owner's contact offer from `CONTACT_EMAIL`; `SiteFaqs.PRICING` |

Chrome is shared: `templates/site/_layout.html` owns the `<head>`, the header and the footer site map, and the homepage uses its header/footer fragments — two copies of a nav is how one of them stops linking a page that exists. The footer's engine column is built from the registry, so a seventh engine page is linked from every page the moment its row lands.

**One vertical rhythm, tokened (119 §A).** Measured on 2026-09-11 at 1440×1000, the gap between a section's `h2` and its first block was a different number in every section (0, 16, 20, 24 and 32px). The rule now: ONE token, `--site-gap-head` (20px phone, 24px from 48rem), applied by ONE selector on the section layout — `.section .container > h2 + *`, with `.faq-group > h2 + *` and `.faq-block > h2 + *` for the two structures that wrap a section h2 one level deeper — and never by a block's own margin. A `.section-lede` deliberately keeps 16px under its h2 (the `h2 + .section-lede` override) and passes the head gap on to ITS first sibling (`.section-lede + *`). Cards: 24px padding and 24px grid gutters from 64rem (20/16 below); internal rhythm set once on `.card` (title → body 8px flex gap, body → a trailing spec link 12px); `align-items: start` inside `.feature-group` so a card is as tall as its text — and a card body is capped at **90 words** (`SiteCardBudgetTest`; the surplus moves into a "read the spec →" link to the cited docs section). The hero shot clamps to `--site-hero-max` (`min(44vh, 620px)` desktop) so the proof strip starts inside the fold; on a phone the hero stacks copy → numbers → picture. Group labels (`.group-title`) take the kicker treatment with `--space-8` above and `--space-4` below, no rule under them. All of it is held in a real browser by `SiteRhythmBrowserTest` (computed styles on `/`, `/how-it-works`, `/demo-data`, `/faq` at 1440×1000 and 390×844: head gaps, card padding, row-stretch, fold, and `scrollWidth == clientWidth`).

**The demo-data page's tables are generated from vendored manifests (116), pinned to the deploy versions.** The three published `manifest.json` files — one per sample-data family, at exactly the version `deploy/env/defaults.env` pins (`SAMPLE_VERSION`, `SAMPLE_TRADE_VERSION`, `SAMPLE_LAKE_VERSION`) — are vendored at `modules/web/src/main/resources/site/demo/`, parsed once at startup by `SiteDemoData` (missing or unparsable fails fast, the `DocsCatalog` posture), and rendered as the family tables: engine, table, row count, sizes, object paths and the licence stamps are model fields, never typed rows. The prose adds only what no manifest carries — grain and key columns from the families' DDL files, windows from the locks' param lines, and the attribution sentences from the manifests' provenance rows. `SiteDemoDataGuardsTest` holds each vendored manifest to its deploy pin, holds the rendered rows to exact completeness against the manifests, and proves the row counts are bound (not literal); a repack that bumps a pin and forgets the page fails the build. A family whose manifest carries no licence stamp renders a visible "not yet verified" status and makes no licence claim.

**Guards** (all in `modules/web/src/test/.../ui/site/`): `SiteSeoMetaTest` renders every registry page through its real controller and asserts title/description/canonical/`og:`, the 70- and 155-character display limits, one `<h1>` with no skipped levels, and a descriptive `alt` on every image; `SiteClaimCitationTest` extends the 024b claim rule to every site template; `SiteBuyerLanguageTest` (115) holds the home page's fold to buyer vocabulary and pins the moved vocabulary on `/how-it-works`; `SiteEngineFactsGuardTest` parses [Datasources §4.1](datasources.md#41-dialect-catalog) and [Deployment §3.5](deployment.md#35-jdbc-driver-matrix-what-ships-in-the-image) and asserts the six engine rows against them; `SiteJsonLdTest` parses the homepage's two `application/ld+json` blocks; `SitemapControllerTest` asserts the sitemap against the registry and the packaged docs; the E2E sweep fetches every `<loc>` anonymously.

**The nav is host-aware (119 §C.1).** The header's last item is `/login` on every deployment, but its label is computed from the request: on the public origin (`SITE_ORIGIN`, the same constant the canonical tag uses) it reads **Try the live demo**; on a customer's own deployment it reads **Sign in** (`SiteOriginAdvice` live; `SitePageRenderer`'s serverName offline — the static export passes the public host, so the exported site reads "Try the live demo"). The GitHub link wears a static star count baked from the build-time `GITHUB_STARS` constant (refreshed by hand at release; no visitor's browser fetches api.github.com), an `AGPL-3.0` chip beside it links `/pricing`, and `SiteOpenSourceSignalsTest` pins both host branches, the badge, and the one-contact-address rule (`CONTACT_EMAIL`; a mailto sweep fails any page carrying a second address).

**The Lighthouse budget (111 §C)** is a script, not a build gate: `scripts/site-lighthouse.sh` serves `modules/web/build/website-export` (run `./gradlew :modules:web:websiteExport` first; the export strips the claim comments — the citation guards read the source templates, not the export) on a free local port and drives **pinned** Lighthouse 12.8.2 in headless Chrome over `/`, `/how-it-works`, `/faq`, `/tableau`, `/published-api` and `/for/saas-teams`, measuring the performance, accessibility, best-practices and SEO categories. **95 is the floor in every category on every page** — a lower score names the page, the category and the number, and exits 1; the fix is the cause, never the floor. The first floor failure was structural, not per-page: five render-blocking stylesheets and a parser-blocking script put ~370 KB (two fonts included) on the critical chain, and the same pages scored 85–92. The fixes live in the head, not in the harness: `site-chrome.css` bundles the whole chain into one generated request (`scripts/build-site-chrome.sh` regenerates it; `SiteCssBundleParityTest` fails the build when any source drifts), the theme swap sheet loads disabled, the two JetBrains Mono faces load in a late `media="print"` sheet that `site.js` flips before paint (slow connections keep the fallback — the same trade `font-display: optional` already makes), and the body/LCP face is preloaded. The server is python3's own `http.server` building blocks with HTTP/1.1 keep-alive and gzip — the bare one-liner speaks HTTP/1.0 with raw bytes and scored the identical pages 1–3 points lower, a harness property, not a page one. The version is pinned so scores are comparable across runs, and the script is deliberately NOT wired into `./gradlew build`: the run needs the network (npx) and a Chrome binary, and the build gate stays hermetic. Run it from the handback and re-run it at merge.

### 4.15a Crawler surfaces (073)

| URL | Served by | Notes |
|---|---|---|
| `/robots.txt` | `static/robots.txt` | Allow all, plus the `Sitemap:` line. The private surface is deliberately not listed — `robots.txt` is public, and the filter chain is the control |
| `/sitemap.xml` | `SitemapController` | Generated from `SitePages.ALL` + the docs index + every packaged doc slug. `lastmod` is the build timestamp from `BuildProperties`, omitted entirely where no `build-info.properties` is on the classpath |



### 4.16 Documentation (in-product)

| Attribute | Value |
|---|---|
| URL | `GET /docs` (grouped index), `GET /docs/{slug}` |
| Auth required | **No, since 073** — both routes are `permitAll`. The viewer renders Markdown packaged in the jar (`DocsCatalog`'s only collaborator is a `ClassLoader`); no principal, no workspace and no datastore is reached on either route, and the same content is already public in the AGPL repository |
| Purpose | The operations manual and spec set for the version being run — packaged into the jar, so the docs can never describe a different server |
| Design primitives | `.ds-card` (index); `.doc-body` typography from `static/css/docs.css` (token-derived) |
| JS | None |
| htmx | No |

Content: the packaged `docs/*.md` set (the exclusion policy — `docs/superpowers/`, `semantic-layer-research.md`, `SPEC-REVIEW-2026-08.md` — lives in `modules/web/build.gradle.kts`), grouped Operations manual / Contracts / Reference, rendered to HTML once at startup. Relative links resolve in-app to `/docs/{slug}` or rewrite to their canonical GitHub URL; heading anchors use the same slug algorithm `scripts/docs-audit.sh` validates against. Rendered markdown is inserted as data (`th:utext`) — `${...}` placeholders in config examples display verbatim.

**Two chromes, one body (073).** An anonymous request renders `docs/index-public` / `docs/doc-public` — the marketing header and footer, an SEO head (`<title>` = the doc's H1 + " — datapipelines.co docs", a meta description taken from the doc's first real paragraph, a canonical at `https://datapipelines.co/docs/{slug}`, `og:` tags and breadcrumbs) and no link into the signed-in app. A signed-in request keeps `docs/index` / `docs/doc` in the application chrome, unchanged: making the docs public must not evict a logged-in reader to the marketing site. `DocsController` chooses on the `authenticated` model attribute `UiWorkspaceAdvice` already fills, so there is one definition of "is there a principal". The meta description skips each spec's `**Status:** …` metadata block by design — it describes the file's bookkeeping, not its subject.

---

### 4.17 Promotion (055)

| Attribute | Value |
|---|---|
| URL | `GET /promotion` |
| Auth required | Yes — **session only** for the Promote action (`POST /promotion/promote`); the listing needs `read` |
| Rendered for (114) | The PLAN renders for every member — it is a read, and hiding it would leave an author unable to see what is waiting. **Promote** renders for `canPromote` **in the SOURCE workspace** (the ACTIVE one: `PromotionUiController` reads `principal.requireWorkspace()` and the interceptor judges `PROMOTE_VERSION` against that same context; no target-side role is consulted). A member without it reads a line naming who to ask — the one place this round explains an absence rather than leaving one, because a promotion screen with no button and no words reads as broken |
| Purpose | Push released content from this deployment to its one configured higher environment ([Versioning §10](versioning.md#10-promotion-ui-driven-separate-use-case)) |
| Endpoints called | The target's [REST API §18](rest-api.md#18-promotion-endpoints-receiver) pair, server-side. The browser never talks to the target |
| Design primitives | `.ds-table` (the listing), `.ds-empty` (the three empty states), `.ds-badge` (the target label and `absent`) |
| JS | None |
| htmx | No — a plain form POST with a redirect flash. A promotion is a whole-environment action, not a fragment swap |

**079 §F: the redirect flash is a TOAST, not a banner.** `?ok=`/`?error=` used to render two
full-width `.ds-card` blocks at the top of the screen that pushed the page down and stayed
until the next navigation. They are now §5.1 Shape A — the workspaces pattern. What is NOT
shared is the vocabulary: promotion's outcomes are its own ("the target already serves that
version"), and folding them into the layout's shared code→text map would put one screen's
language in every screen's shell. So this screen renders its OWN hidden bin, marked
`[data-toast-flash]`, and `toast.js` drains every marked bin rather than only the layout's
`#toast-flash`. Any screen with its own refusal vocabulary can now do the same.

**This screen is the only way to promote.** [Versioning §10.1](versioning.md#101-policy) D8 makes promotion a human, UI-triggered action: there is no MCP tool and no schedule, and 055's fence excluded `modules/mcp-server` so that is mechanical rather than remembered.

The listing is exactly [§10.2](versioning.md#102-the-listing-rule-what-the-ui-shows)'s set — RELEASED, and a version strictly greater than the target's, with same-hash entries dropped. Drafts and same-version entries never appear. The rule lives in the sender service, not in the template, so the screen cannot drift from it.

Three states, told apart because the operator's next step differs in each:

| State | What the screen says |
|---|---|
| No target configured | Names the two config keys to set. This is a deployment change, not a promotion |
| Target reachable, nothing to promote | "Nothing to promote", with the count of pipelines examined — so an empty list reads as *in sync*, not *broken* |
| Target unreachable or refusing | The error CODE, in place, on the listing page — not a generic error page. `pipeline.promotion.target_unreachable` and `pipeline.promotion.target_is_authoring` need different fixes |

A target with `authoring-enabled: true` renders its own banner and disables the button ([D7](versioning.md#101-policy): dev is where drafts live). That is a courtesy, not the control — the sender re-checks it and the receiver refuses independently.

Each row shows the pipeline's version here and on the target (`absent` when the target does not have it). Selection defaults to all. The Promote action re-runs every §10.3 guard against a FRESH inventory server-side and recomputes the dependency closure from scratch: what the screen showed may be minutes old, and a release that landed in between must not sail through on a stale decision. Outcomes return as `?ok=` / `?error=` flashes rendered by the layout's toast stack (§5.1), so a refusal lands on the listing the operator can act on.

---

### 4.18 API section (079 §C, closes T139)

| Attribute | Value |
|---|---|
| URL | `GET /api-console` |
| Auth required | Yes — `read` (`ScopeMatrix.RestOperation.READ_RESOURCES`), the same floor `EndpointsController.list` uses |
| Purpose | Everything a program uses to talk to this workspace, in one place: published endpoints, the keys that may call them, the MCP connection |
| Design primitives | `.ds-card`, `.ds-table`, `.app-chip`, `.app-code`, `.app-empty` |
| JS | Light — the modal, the kind-conditional fields, and the select-the-secret reveal |
| htmx | Yes for KEYS (`hx-post="/partials/api-keys"` into `#keyCreated`; `hx-delete="/partials/api-keys/{id}"` into `#keys-table-body`, each with a §5.1 Shape A out-of-band piece). The endpoints and MCP cards stay read-only |

**Why the route is `/api-console` and not something under `/api`.** That prefix is the
programmatic surface, split in two — the `/api/v1` REST envelope and the `/api/x` published
endpoints this page lists — and §2.1's three-URL-space rule says a page never lives in the
JSON space. `api-console` is a different first path segment from `api`, so Spring's
segment-wise matching cannot let either shadow the other. It needs no `SecurityConfig` entry:
that chain's `permitAll` list is explicit and everything else is `.anyRequest().authenticated()`.
Because `@RequiredScope` is enforced on any path once declared (the `/api` and `/partials`
prefixes govern only where an UNannotated handler is default-denied), the annotation is a real
gate here — and it is also what refuses an `endpoint`-kind key, which carries no scopes by
design ([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)) and reaches only the published-endpoint surface.

**Read-only about ENDPOINTS; the management screen for KEYS (091).** Publishing an endpoint and
binding an existing key stay on REST/MCP (074 step 6's intent) — the endpoints card is a
truthful inventory plus links. Keys are different: their whole lifecycle is here. The key
surface is `MANAGE_OWN_API_KEYS` (any authenticated principal, own keys only), enforced by the
partial controllers' own annotation rather than by this page's `read` floor. Actions a principal
lacks scope for are not rendered (§4 preamble), and the server re-checks regardless.

Three cards. Since 091 the **API keys** card leads the page and spans it: it is the management
surface now, and seven columns do not fit the narrow track the read-only version sat in
(measured on a live stack at 1440 — `Last used` and the revoke action were off the card's right
edge). The endpoints inventory and the MCP connection keep the two-column row below it. The
numbering below stays in the order the cards were introduced.

1. **Published endpoints** — from `EndpointPublishService.list(principal)`, one batch lookup
   for pipeline names and one for released versions (never a query per row, the rule
   `PipelineNames` was written for). Columns: path, pipeline, timeout, bound keys.
   - The method is always **GET**: `PublishedEndpointController` refuses everything else with
     `Allow: GET`, so it is a fact about the surface rather than a column that could vary.
   - The version shown is the **released** one, not `pipelines.current_version`. An endpoint
     pins a PIPELINE and serves its latest release, so a draft number would describe something
     no call will ever run. A pipeline with no release renders "no released version" — an
     endpoint can outlive the release it was published against, and that is worth seeing.
   - "N bound" is the **exact-node** binding count, which is what the REST surface reports.
     It is deliberately not the EFFECTIVE authorization, which walks ancestors and takes the
     nearest node carrying any binding (`EndpointAuthorizer`); and zero bindings does not mean
     nobody can call it — a `user` key pinned to this workspace with `execute` still may.
   - **There is no "calls in the last 24 h" column, and the mock has one.** Nothing in this
     system records endpoint serves: no counter column on `published_endpoints`, no Micrometer
     counter, no query. The only trace is an append-only `endpoint.served` audit row whose one
     production reader is a per-execution boolean and whose KDoc argues explicitly against
     broadening it. A column filled from a sample would be a plausible number and a false one,
     so the card states the absence instead. Adding the column means adding the counter first.
2. **API keys** — the caller's own keys, and since 091 the place they are ISSUED and REVOKED
   (§4.10 is a link now). Columns: key (name + `dpk_RGAX…` prefix), kind, **reach**, created,
   expires, last used, and a revoke action.
   - **Reach** is whatever decides what the key may do, and that differs by kind: scopes for a
     `user` key, the bound paths (`/nyc/**`) for an `endpoint` key, the promotion route family
     for a `server` key. An empty scope cell on the last two would read as "this key can do
     nothing", which is the opposite of true ([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)).
   - Every timestamp is **relative in the cell, absolute (UTC) on hover**: "is this about to
     expire?" and "exactly when?" are both real questions, and a cell with one of them sends the
     reader to the database.
   - Revoked AND **expired** keys keep their row and lose the revoke affordance. §7.3 refuses
     both, so a table that showed an expired key as live would disagree with the authenticator.
   - The list is the caller's OWN keys — the list endpoint never accepts a user filter, and
     admins get no cross-user view of keys here.

   **The create form** — one modal, in the order the owner ratified: **Kind → Scope → Name →
   Expiry → Bindings**, with scope and bindings CONDITIONAL on the kind.

   | Field | What it does |
   |---|---|
   | **Kind** | Radio cards, one sentence each. `user` is labelled **"Agent / API key"**: one kind, two surfaces (an agent's key over MCP and a program's key over REST are the same credential, and two names would invent a distinction the system does not make). `server` is rendered for **admins only** — `ApiKeyService` refuses it to anyone else, and a form that offers a refused option lies |
   | **Scope** | A select of the CAPABILITIES with their meanings — `read` (list and inspect, runs nothing), `execute` (run released pipelines, includes read), `author` (create and change templates, pipelines, datasources, includes execute), `admin` (everything, including users and workspaces). Filtered to the caller's own scopes (§7.4's subset rule); the server refuses a superset regardless. **Hidden for `endpoint` and `server`**, whose inputs are DISABLED rather than merely hidden — a disabled input is not submitted, and a scope on a scopeless kind is refused, not dropped |
   | **Name** | Not unique, and the hint says so: `api_keys.name` carries no uniqueness constraint and two people in one workspace may legitimately both have an "agent" key. The prefix is what identifies a key |
   | **Expiry** | A select — Never / 1 / 7 / 30 / 90 days / custom date — resolved SERVER-side from the same table the page renders from, so the select cannot offer a value the server refuses. A custom date expires at the **end** of that day, UTC (a human typing a date means "valid through that day"). A bad one is `400 auth.api_key.expiry_invalid` with a stable `details.reason`, never a silent fallback to "never": failing toward a BROADER credential is the wrong way to fail |
   | **Bindings** | `endpoint` kind only — the published-endpoint picker, showing the tree nodes in the `/nyc/**` form 074 defined. Only **literal** prefixes are offered: `EndpointAuthorizer` walks the ancestors of the CONCRETE request path, so a node containing a `{variable}` segment would be a checkbox that authorises nothing. The root `/` is always offered and always first |

   **The create result** shows the secret ONCE in a persistent inline panel (§5.1's hard rule — a
   6s toast never carries anything the user must keep), beside the prefix, kind, scope-or-
   bindings and expiry of what was actually minted. The same response refreshes the whole table
   out-of-band (at TABLE level: a `tbody` OOB element nested in the response dies in the
   browser's fragment parser) and points an info toast at the panel.

   **One fragment, three renders.** The page, the post-create refresh and the rows a revoke
   swaps in all render `api/console :: keysTable` / `:: keyRows` from one row model
   (`ApiKeyRows`). Before 091 the revoke path hand-built its rows in Kotlin and a parity test
   kept the two markups "byte-for-byte" alike; there is one markup now.
3. **MCP server** — the connection JSON, the live tool count and the P32 rule. The count is
   `McpToolCatalog.NAMES.size` at request time, never a literal, exactly as the marketing site
   renders it. The header name is `ApiKeyCredential.HEADER`. The server URL comes from
   `datapipelines.auth.base-url`, the deployment's DECLARED external origin — never derived
   from the request, for the reason `OidcConfig` documents: a hostile `Host` /
   `X-Forwarded-Host` would otherwise choose a URL a reader is invited to paste into an agent's
   config next to a live API key. Unset, the card shows the `{host}/mcp` placeholder the
   marketing site already uses rather than guessing.

---

## 5. htmx Usage Pattern

Standard pattern for list/filter/pagination. The page (`GET /pipelines`) renders the shell **and** the initial fragment; every subsequent refresh hits the partial endpoint (`GET /partials/pipelines`) and swaps the fragment only.

**One BrowseModel per list screen (normative, 097).** A list screen is one `<Entity>BrowseModel` + a page controller + a partial controller. The page controller renders the shell AND the initial fragment **through the model**; the partial controller renders every subsequent fragment **through the same model**; neither filters, pages nor projects on its own. The model owns the query, the search, the paging and every attribute the fragment reads; the controllers own only their route's extra chrome.

This is a rule because the alternative was tried on three screens:

- **Datasources** had the two controllers and no model. Each grew its own `filter()`, and they diverged — the page searched 3 fields where the partial searched 10, so `GET /datasources?q=postgres` (a reload, a shared link, a boosted navigation) came back EMPTY while typing the same word into the same box returned rows.
- **Executions** had a page controller that projected nothing: it rendered a spinner and let `hx-trigger="load"` fetch the rows, so the screen's first paint was an empty frame waiting on a request the browser had not made yet — and the page ignored the filters in its own URL.
- **Admin users** had no projection layer in either direction: the rows were built as HTML strings in Kotlin, where no template audit could see them.

The convention is drift-tested by `BrowseModelConventionTest`, which asserts both halves of every pair inject the same model AND that every `*PartialController` is either half of a pair or carries a written exemption (the dashboard's, the execution detail's, the pipeline editor's node-SQL seam, the API console's).

**First paint is the same everywhere, with ONE exception.** Every list screen renders shell + initial fragment. The **dashboard** is the deliberate exception (`dashboard.html`): it is four independent panels — stats, recent executions, recent pipelines, health — that load in parallel behind skeletons, so there is no single list to project and no reason to make the slowest panel decide when the page appears. It is also the only screen where a skeleton is the right first frame, because the alternative is a page that arrives late rather than a page that fills in.

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

**Loading state.** Every htmx request gets a signal at three levels (085 §D — previously the top bar was boosted-navigation only, and partials leaned on their own spinners alone):
- **The 2px bar under the nav (`#app-progress`) shows for EVERY request**, boosted or partial. `shell.js` keeps an in-flight COUNT — shown on 0→1, hidden on →0 — so a tree expand and a detail load running together cannot hide it early. The counting pair is `htmx:beforeRequest` / `htmx:afterRequest`, because afterRequest is the one terminal event htmx 2.0.10 fires on every outcome: `htmx:afterSettle` never fires for an aborted request, a network error or a timeout, and a bar that can stick on is worse than none.
- **The originating control goes busy.** `shell.js` marks the requesting element with the shell's own `.app-busy` class for the flight — deliberately NOT htmx's `.htmx-request`, which htmx 2.0.10 applies to the `hx-indicator` TARGET instead whenever the element carries `hx-indicator` (the tree leaves do), leaving the control itself unmarked. A `<button>` in that state is non-interactive (`pointer-events: none` — `shell.js` adds `aria-disabled="true"` for the duration, never the `disabled` property, which would drop focus mid-flight; htmx already guards re-triggering) and draws an `::after` spinner ring in the `.ds-spinner` idiom, absolutely positioned so the control's box never changes (the no-layout-shift rule below stands). A folder `<summary>` is the deliberate exception: it gets the class but stays operable while its level loads (collapse/re-expand mid-fetch is pinned behaviour) and its busy signal is the chevron spinning in place. Mutating buttons (`Save`, `Generate key`, `Test connection`) still additionally set `hx-disabled-elt="this"` against double-submit, and elements carrying their own `hx-indicator` spinner keep it — the busy state is additive, never a replacement.
- **A slow swap target gets a delayed skeleton.** A request still in flight 150ms after `htmx:beforeRequest` marks its target `aria-busy="true"` and gains ONE skeleton row (`.ds-skeleton .ds-skeleton-table-row .app-target-skeleton`); a faster swap never flashes either. Timers and skeletons pair per target — concurrent requests into different panes are normal — and the terminal event removes both before the swap lands. The detail panes (`#template-detail`, `#pipeline-detail`) are the primary beneficiaries; the mechanism is generic.
- **The click itself is acknowledged before any of the above** (103 §3.5): a boosted navigation dims and `aria-disabled`s the clicked link in the same frame as the press, and a status pill joins it after 150ms. That layer is specified in §3.5 — it is a property of the SHELL, not of a request, and it is cleared on a different union of events than the bar is.
- Reduced motion keeps every state and drops every motion: the bar's slide, the control ring's spin (it goes dashed-static, the `.ds-spinner` precedent), the chevron's spin (a static accent chevron instead) and the skeleton's shimmer are all covered by their own `prefers-reduced-motion` blocks.

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

**Typed confirm (102) — the irreversible actions ask the user to TYPE the thing that will be destroyed.** A destructive verb that cannot be undone (the version purge, the entity purge) does not arm its button on a click alone: the dialog renders a field naming exactly what to type — `v4` for a version, the entity's NAME for a purge — and the `ds-button-danger` stays disabled until the typed value matches. It is the second use of the convention after the CLI's `--clean` (same reason: the action deletes rows, not state), and it is enforced TWICE: client-side for the button state, and server-side — the `confirm` form field is checked before the lifecycle service runs, and a mismatch is `400 pipeline.version.confirm_mismatch` / `template.version.confirm_mismatch` (§5.1 Shape C), so a dialog forged or scripted around the field still cannot purge anything. Reversible verbs (Release, Discard, Restore, Switch) do not type-confirm — their one click names one version, and the undo is a sibling verb.

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

**The no-workspace page is NOT one of them (114).** `workspaces/none` — reached when a principal
has zero ACTIVE memberships — renders in the app shell, says what happened ("You're not a member of
any active workspace"), says who to ask, and offers the create form to a super admin. Nothing
failed: their credential is fine and their membership is absent, which `error/403` would describe
wrongly. To that person a deactivated workspace and one that never existed look the same
([Auth §11A.3](auth.md#11a3-deactivation)), so the page names neither. See §4.13 for the
reachability gap this round left open and the one-line fix it needs.

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
| 2026-09-11 | v1.38 | 119 open-source signals | **§4.15 — the site reads as free and open source on the first screen** (owner: the fold did not answer "is this something I buy"). Hero CTA is the install itself — a copyable `./app.sh --start` via the standard `.code-block`/`.copy-btn` pair (zero new clipboard JS), labelled *Run it on your machine — free, one command, no account*; the strip gains the **Price · $0 · AGPL-3.0 · self-hosted · no phone-home** cell (cites deployment.md §10, §4.3, §11); nav gains **Pricing** plus the ★GitHub star badge and the licence chip, and its last item is host-aware (**Try the live demo** on the public origin, **Sign in** elsewhere) via `SiteOriginAdvice`; `/pricing` page with `SiteFaqs.PRICING` and the dated no-paid-tier promise (freshness guard extended to it); `CONTACT_EMAIL` renders from one constant in the footer, /pricing and a new /security report-a-vulnerability section; "free" now in the home FAQ cost answer and the open-source section; `SiteOpenSourceSignalsTest` (red at birth: the fold carried neither "free" nor the licence on the first screen). |
| 2026-09-11 | v1.37 | 119 site batch 4 | **§4.15 — one vertical rhythm for every public page (measured, not felt).** New `--site-gap-head` token (20px phone / 24px desktop) applied by ONE selector per structure (`.section .container > h2 + *`), ledes keeping 16px and passing the gap on; card padding/gap 24 from 64rem; the card's internal rhythm set once on `.card`; `align-items: start` in feature groups + the 90-word card body budget (`SiteCardBudgetTest`, red at birth on 11 cards, surplus moved to cited-docs links); `.group-title` takes the kicker treatment; `.hero-shot` clamped to `--site-hero-max` so the proof strip starts in the fold (phone order: copy → numbers → picture); the home dateline folded into its pill; `.shot img` framed while the pre-re-shoot light captures ship; long machine tokens in prose break instead of widening the page. All pinned by `SiteRhythmBrowserTest` (real viewports, 1440/390). |
| 2026-09-11 | v1.34 | 116 demo-data page | **§4.15 — the `/demo-data` public page**: the three published sample-data families documented table by table, with the rule that the family tables are GENERATED from the manifests vendored at `resources/site/demo/` and pinned to the deploy versions (`SiteDemoDataGuardsTest`: pin agreement, row completeness, derived-and-bound row counts, non-vacuity). `SiteDemoData` parses the three manifests once at startup, fail-fast; a family with no licence stamp renders "not yet verified" and claims no licence. FAQ group `SiteFaqs.DEMO_DATA` (5) joins `ALL`; linked from the footer, the home `#demo` lede, `/how-it-works`'s where-to-next and `/dp-lake`; `PublicPaths` + §8.3 row (37); lighthouse script gains the page. |
| 2026-09-10 | v1.33 | 115 site batch 3 | **§4.15 — the home page speaks to the buyer; the engineering moved to `/how-it-works`.** The homepage sells the outcome in a fixed fold (hero → before/after → the artifact) held to buyer vocabulary by the new `SiteBuyerLanguageTest` (no pipeline/DAG/federated/Iceberg/DuckDB/Parquet/MCP/JDBC/staging from `<main` to the end of `#artifact`; `/how-it-works` must carry ≥6 of those words — the mechanism moved, nothing hidden), with the H1 retargeted to "no data team required" (the pillar keeps the SQL-MCP-server keyword). New sections: verified-by-you, what-you-get (today/next-month badges), who-it's-for, a halved demo; the home FAQ became `SiteFaqs.HOME`'s eight buyer questions, which open `/faq`; the home page's next-month promise carries the roadmap pages' dated `status` markup, so the freshness guard covers it. The engine strip, the agent loop, "What's in the box" and the security teaser moved verbatim to the new `/how-it-works` engineering page (`SiteFaqs.APIS_AND_OPERATIONS`; nav's first item; full nine-edit mechanics incl. the `PublicPaths`/§8.3 row). `WebsiteFactsGuardTest`'s rendered tool-count leg and demo-name sweep followed the moved sections. |
| 2026-09-11 | v1.36 | 118 learned semantic layer | New **§4.5b** (datasource learned facts): a read-only Facts dialog on every datasources-list row (`data-read`, every member) and the same table inline on the LAKE detail — trust badges, drift message, conflict and provenance badges, the D-S9 pipeline link. §4.5's row gains the Facts button. |
| 2026-09-11 | v1.35 | owner testing round, day 1 | §3.4: **the shell is the viewport** — `<main>` scrolls, the document never does (`AppShellBrowserTest`); default theme `dark`. Fixes from the walk: an anchor styled as a button kept the base `a:hover` link colour and vanished on the accent ground (login's "Continue with Google") → `a.ds-button*` hover rules in app.css, pinned on the computed style after a real hover; version rows (§4.3/4.6) are one line on a pane ≥ 560px (container query) and two lines below it; the editor's result table (§4.4) carries its frame on the scroll viewport (right edge and corners always visible) and its header row is sticky while the body scrolls — the same frame rule for `.u-scroll-x > .ds-table` (execution result partial). |
| 2026-09-10 | v1.34 | 114 RBAC screens | §4.3e (role visibility — the whole verb inventory and the flag that renders each), §3.4 (the role badge beside the switcher), §4.5a (datasource grants), §4.13 rewritten (members with three checkboxes, deactivation, the active-workspace rule), §6 (the no-workspace page), and a "Rendered for" note on §4.3/§4.4/§4.5/§4.6/§4.7/§4.10/§4.17. |
| 2026-09-09 | v1.31 | 102 lifecycle dialogs | **§4.3d (new) — every lifecycle verb 101 shipped is reachable from a confirm dialog**, one partial per verb, in both explorers (`#px-dialog` / `#tx-dialog`) and both editors (`#pe-dialog` / `#te-dialog`); the template twins are addressed by name in the query. The 094 datasource-delete shape throughout: refusal branches render NO button, the POST re-runs the guard, refusals are §5.1 Shape C with the real 4xx, success is Shape A (re-rendered detail + toast + `HX-Trigger: lifecycle-changed` for the tree badge) — entity purges and editor verbs answer `HX-Redirect` with a flash toast. **Typed confirm (§5.1)** on the two irreversible verbs — type `v4`, type the entity's NAME — enforced client-side for the button and server-side as `400 *.confirm_mismatch` before the service runs; second use after the CLI's `--clean`. §4.3b's header carries at most ONE destructive verb (owner rule) and the version rows move their verbs into a per-row ⋯ menu where a §3.5-refused verb is absent, not disabled. §4.4/§4.7: the editors' native `window.confirm`/`window.alert` sites are gone (a static test pins zero), including the pre-101 discard text that had become false. Two new codes: `pipeline.version.confirm_mismatch` / `template.version.confirm_mismatch` (§13.13/§13.9, landed with constants and catalog rows in the same commit). |
| 2026-09-09 | v1.30 | 097 the hybrid boundary made uniform | **§2.1 gains the FOURTH shape it never described** — a "Page-route mutation (PRG)" row plus its response contract (a `redirect:` back to the page, `?ok=`/`?error=` KEYS the layout maps to copy, and the §5.1 toast the `#toast-flash` bin turns them into — 076 §B had already retired the banners the round brief expected to find) and the two reasons a mutation may live there: its success changes the SHELL, or it must work without JS. The eight handlers are enumerated and justified one by one in `MutatingHandlerScopeFloorTest.PAGE_ROUTE_MUTATIONS`, whose new arm fails on any mutating handler outside `/partials` and `/api` that is not on that list — so the scheduler cannot invent a third idiom. **§5 gains the BrowseModel rule** (one `<Entity>BrowseModel` + a page controller + a partial controller; neither filters, pages or projects on its own), drift-tested by `BrowseModelConventionTest` over five pairs, and states the first-paint rule with the dashboard as its ONE exception. The rule is written because three screens had already paid for its absence: datasources' two hand-written filters had diverged (`GET /datasources?q=postgres` returned nothing while the typed search returned rows), executions painted a spinner and fetched its rows on a load trigger while ignoring the filters in its own URL, and admin users built its `<tr>`s as strings in Kotlin. All three now project through a model; the admin table and four other Kotlin-built markup sites became Thymeleaf fragments, and `InlineWidthAuditTest` widened from templates to every module's `main/kotlin` with an empty allowlist. **§4.7** — the template editor's ~135-line inline script left the template for `static/js/template-editor/lifecycle.js`: one CSRF reader (`static/js/csrf.js`), `htmx.ajax` for the `/partials` render (§2.1 as written), an in-page confirmation and toasts in place of `window.confirm`/`alert`, and nodes in place of `innerHTML`. |
| 2026-09-08 | v1.26 | 099 draft-first (D55/D56) | **§4.3** — a new pipeline appears as `v1 draft`: the leaf and search rows name the WORKING version (the draft's number when one exists), because `p.currentVersion` alone rendered `vnull` once creation stopped releasing. The Release action stays the editor's and renders for a v1 draft exactly as for any later one. **§4.8/§4.9** — the executions list and detail label a **DRAFT** run beside the version (`data-draft-run`), from versioning §8's derivation; a v1 run is routinely a draft run now, so the number alone no longer says whether a result came from reviewed content. |
| 2026-09-08 | v1.29 | 091 keys — one page for three kinds | **§4.10 reduced to a LINK; §4.18 card 2 became the key management screen.** One table, one form, one row model: kind, prefix, reach (scopes \| bindings \| the promotion route family), created, expires and last used — each relative in the cell and absolute UTC on hover — plus revoke. Revoked AND expired keys keep their row and lose the affordance, because §7.3 refuses both. The form's order is the owner's ruling — **Kind → Scope → Name → Expiry → Bindings** — with scope and bindings conditional on the kind and their inputs DISABLED rather than merely hidden (a disabled input is not submitted, and a scope on a scopeless kind is refused, not dropped). Kind renders as radio cards with a sentence each; `user` is labelled "Agent / API key" (one kind, two surfaces) and `server` (091's third kind) is admin-only. Scope lists the CAPABILITIES with their meanings, never HTTP verbs. Expiry is a select resolved server-side from the same table the page renders from, with `400 auth.api_key.expiry_invalid` for anything unusable — never a silent fallback to "never". The binding picker offers only LITERAL published prefixes, because the authorizer walks the concrete request path's ancestors and a `{variable}` node would authorise nothing. The Kotlin row builder and its parity test are deleted: the page, the post-create OOB refresh and the post-revoke rows now render one fragment. |
| 2026-09-07 | v1.28 | UI round 3 — the boosted first frame, the fonts, and a login page that ignores your session (090) | **§3.0 (new, normative): no page template carries its own `<link rel="stylesheet">`.** The explorers' sheet lived inside the `content` fragment — inside `#app-main`, the boosted-swap target — so htmx replaced the region and the browser only then discovered the link; an inserted stylesheet does not block an already-painted document. Measured at the first animation frame after `htmx:afterSwap`: at 1920 the tree painted 1640px wide with the detail pane's left edge EQUAL to its own (the panes stacked), 2280px at 2560 expanded, 2452px collapsed — settling to 480px and a 24px gap a frame later. That unstyled frame is BOTH of the round's reports: "the detail section is very close with the side menu" and "css is applied after data load". `template-tree.css` moved to the layout head. **The reported cause did not reproduce:** the pane's 260px floor was never beaten — six settled measurements (1440/1920/2560 × rail expanded/collapsed) gave 432 or 480px with `min-width` computing 260px, no persisted width existed, and no flex override was in the cascade. **CLS was the wrong instrument** and is recorded as such: `PerformanceObserver('layout-shift')` scored the broken navigation 0.0000, because the panes were inserted wrong rather than moved — `ExplorerPaneGeometryBrowserTest` asserts first-frame geometry at 3 widths × 2 rail states × 2 explorers, and keeps the < 0.05 budget for the font rule. **§3.3 amended: `font-display: optional`** replaces `swap`. With every font response held 1.2s, the `/dashboard` heading went 243.5 → 251.7px (+8.2px) and `/templates` 611.7 → 618.2px on arrival, at CLS 0 and 0.0075 — a horizontal reflow inside a block that does not move, which is text changing after it has been read. `optional` makes post-paint reflow structurally impossible; re-measured, first-frame and settled widths now agree exactly. `fallback` is the recorded alternative, with the size-adjust ratios already measured (Inter/system-ui 102.59%, JetBrains Mono/ui-monospace 109.48%). **§2 principle 4 rewritten, §4.1 and §4.14 amended: two layouts.** `login.html` decorated with `layouts/default`, which renders the shell whenever `authenticated` is true, so a signed-in visitor who opened `/login` in a second tab got the sign-in form inside a working app (owner's walk). New `layouts/auth.html` — brand, card, theme, htmx and the toast stack, no rail, no top bar, no `#app-main`, no `hx-boost`; `GET /login` answers `302 /dashboard` for a live session (on the PRINCIPAL, not on the cookie, so an expired session still gets the form); `OidcSignedInBounceFilter` closes `/oauth2/authorization/*` ahead of Spring Security's redirect filter. The forced-change gate became its own view (`settings/password-forced`) over one shared card partial, because a conditional decorator via `__${...}__` preprocessing is resolved at PARSE time and cached — the first request through would freeze the layout for all of them. **§B, second half:** `[x-cloak]{display:none}` moved from the page-scoped `pipeline-editor.css` to `app.css`, so the attribute is not inert everywhere else, and the editor's Alpine ROOT is cloaked (its six panes already were). New guards: `ExplorerPaneGeometryBrowserTest`, `AuthLayoutRenderTest`, `AlpineCloakAuditTest` (with two non-vacuity floors), `OidcSignedInBounceFilterTest`, four `LoginGoldenPathBrowserTest` cases. Still page-scoped and carrying §3.0's defect, named rather than left to be rediscovered: `pipeline-editor.css`, `template-editor.css`, `docs.css`. |
| 2026-09-07 | v1.27 | shell polish — a signal for every server trip (085 §D) | §5.1's Loading state rewritten: the 2px `#app-progress` bar was boosted-navigation only; the owner asked for "some kind of an indicator" on every trip, so the bar now shows for EVERY htmx request as an in-flight COUNT between `htmx:beforeRequest` and `htmx:afterRequest` (afterRequest is the one terminal event htmx 2.0.10 fires on success, error status, network error, abort AND timeout — afterSettle never fires for an aborted request, which is why the old settle/error listener set could have stranded the bar on). The originating control goes busy under the shell's OWN `.app-busy` marker — htmx 2.0.10's `.htmx-request` lands on the `hx-indicator` target instead when the element carries `hx-indicator` (the tree leaves do), so it cannot mark the control itself. A busy `<button>` gets `pointer-events: none` plus an absolutely-positioned `::after` spinner ring (the `.ds-spinner` idiom, no layout shift; a tree row's trailing badges go `visibility:hidden` for the flight to make room), and `shell.js` sets `aria-disabled="true"` — never the `disabled` property, which would drop focus mid-flight. Folder summaries are the exception: they stay operable mid-fetch (the §C hammer pins collapse/re-expand while a level loads) and their signal is the chevron spinning in the row's own icon slot. A request still in flight after 150ms marks its swap target `aria-busy="true"` and appends ONE `.ds-skeleton` row (`.app-target-skeleton`), per-target paired, removed by the terminal event before the swap — slow swaps show a skeleton, fast swaps never flash one. Reduced motion keeps every state and drops every motion (dashed-static ring, static accent chevron, the vendored skeleton's own static surface). §3.2's boosted-bar passage now points at §5.1. Pinned by four new `shell.test.mjs` cases (counter interleave, `.app-busy`/aria-disabled set and cleared, skeleton only after the delay, per-target pairing) and the new `ShellBusyBrowserTest` (bar active during a throttled expand and gone after settle, the in-flight control non-interactive, the skeleton present past 150ms and absent on a fast swap, and the bar provably not stuck after an hx-sync replace abort). |
| 2026-09-07 | v1.26 | shell polish — tree guides + one icon set (085) | **§A — the explorer trees draw real guide geometry** (owner: "connecting lines are not accurate"): the vertical guide was a `border-left` on the level CONTAINER that neither met the parent chevron nor stopped at the last child; it is now `::before`/`::after` on each row's `<li>` — the vertical starts at the parent row's chevron centre and stops at the LAST row's own centre even when that row is an expanded folder (the li wraps the subtree, so a bottom-anchored line would run down inside it), and every row carries a horizontal tick into its chevron/file icon. All lengths derive from tokens (`--tpl-indent`/`--tpl-row-h`/`--tpl-guide-x` on `.tplx-tree`); the unicode ▸ disclosure marker is the sprite's chevron-right rotated on `details[open]` (reduced-motion keeps the state, drops the transition), folder rows gain folder/folder-open (two icons, CSS picks one) and leaves file-code; the per-row border-bottom hairline is gone — the mock draws rows with whitespace and a hover tint, and no component boundary loses its line. Search results stay a flat, guide-free list. **§B — one icon source for the whole app** (owner: "probably we need different icons which can look more professional"): the sprite grows 12 → 40 glyphs, exactly the referenced set, per-icon SHA-256 in `vendor-manifest.json`, the ISC text vendored as `LICENSE.lucide`; the sixteen ad-hoc inline shell SVGs, the eight `&times;` close glyphs and the five `&larr;`/`&rarr;`/`→` arrows are all sprite references now (toast's client builder kept in parity — `ToastMarkupParityTest` + `toast.test.mjs`), and `graph.js`'s CALCULATOR draws `calculator` instead of the `file` stand-in. The size mapping (sm = chrome/actions, md = canvas, xs = tight inline) is recorded in §3.4; `IconSpriteAuditTest` enforces referenced = vendored = manifest-subset in both directions. |
| 2026-09-06 | v1.25 | the §3.3 faces, applied (082 §D) | §3.3's decision table had been prose for three rounds while the MARKUP disagreed with it in six cells. Swept mechanically, not eyeballed, and fixed: **§4.9 node stats** renders its node id in mono (it was in the body face) and its Context `key → value` under `.u-mono` rather than `.num` — `.num` is the NUMERIC column treatment (right-aligned, one size down, mono), and only the mono third of it was ever right for an identifier; **§4.5 datasources** sets its JDBC URL and username in mono; **§4.13 workspaces** sets the workspace name in mono; **§4.17 promotion** gives the pipeline path the same `.app-path` treatment the execution lists use. The **Started** column reported as monospace is not: verified against 079's own screenshots and the stylesheets, dates render in Inter with `tabular-nums`, which is §3.3.2 exactly — the wide, even figures are tabular numerals, not a mono face, and no change was made. New guard `TableTypeFaceAuditTest`: the date rule swept over every app template (no timestamp may sit in a mono/`.num` cell) plus §3.3's mono list transcribed as data. Explicitly NOT changed: the admin-users ID column and the execution-family UUID links, which §3.3 does not list as mono columns. |
| 2026-09-08 | v1.22 | feedback and atmosphere (103) | §3 gains **§3.5** — the click-time pending state (`is-pending` / `is-pending-scope`, the 150ms `#app-status-pill`, the three-ending clear), the `app-enter` entrance inside the 0.05 CLS budget, the `.app-backdrop` ground (dot grid + brand glow, tokens only), card elevation and the interactive-card hover lift, heading weight (`--text-2xl`, Inter only) and `.app-eyebrow`, and the control transitions. §5.1's loading state gains a pointer to it. Text contrast over the backdrop is measured on every vendored theme by `ShellFeelBrowserTest`. No architecture change: no router, no view-transitions dependency, no new library. |
| 2026-09-04 | v1.21 | structural contrast floors (064) | §2 gains principle 8 — the WCAG 1.4.11 non-text floors: `border-default`/`hover`/`focus` at 3:1, `border-subtle` at 2:1, `surface-selected` at 1.5:1 with a `border-focus` accent bar; pane/card boundaries are `border-default` lines, never tints. Text floors unchanged. Enforced upstream by the design-system audit and on the vendored CSS by `VendoredNonTextContrastTest`. The explorer's pane split and the tree's selected-row bar follow the floors (§4.6). |
| 2026-09-02 | v1.17 | template tree UI (047) | §4.6 rewritten as the template **tree**: the one-route/two-shape fragment contract (`/partials/templates` with and without `prefix`, plus `/partials/templates/versions`), levels as server-side prefix queries with no client-side tree assembly at any size, `<details>`-driven lazy expansion with no JS of our own, per-level paging through the shared §5 pager against each level's own derived id (the ROOT level's id stays `#template-list-wrapper`, so the existing swap contract carries over unchanged), and the decided **browse-vs-search** rule — a non-empty `q` is a FLAT list of full paths, not a pruned tree. The four §9.1 absences are recorded as absences and guarded by render assertions: no folder CRUD, no empty-folder state, no `type` control on the edit form, no rename affordance anywhere. The list screen gains a `type` filter (046's column) and a real Create modal (§5.1 Shape A) whose name `pattern`/`maxlength` are RENDERED FROM the server's grammar rather than retyped beside it, and whose `dialect` is disabled-and-absent for `type=html`. §4.7 records `type`/`dialect` as read-only values on the editor. §4.4 records the pipeline editor's read-only template reference (one-line truncation with the full path on `title`, at both call sites and in the `template-missing` state) **and the rule that a future template picker reuses §4.6's prefix fragment rather than building its own client-side tree** — written on the screen the picker would be built on. |
| 2026-09-03 | v1.20 | graph node cards + explorer geometry (059) | §4.4 gains the card row: the pipeline editor's nodes are cards with the facts INSIDE (name/type/datasource·dialect/template@v/run line, HTML overlay, status dot, ports, fit controls) — the 2026-09-02 operator reversal of the 031 label-below look; full contract in Pipeline Editor §5.3 (v1.6). §4.6's layout paragraph records the two CSS corrections seen in 058's own 1920px screenshot: the explorer grid is the VIEWPORT's width (the centered app-canvas cap does not apply to this screen — the owner asked three times for the real estate), and the selected template TOP-ALIGNS with the tree's first row (`#template-detail` keeps the empty state's centered-flex classes for its whole life; the partial's root now stretches, cancelling both centres — the 300px inter-pane gap and the header at y=550 were this missing). The quiet empty/not-found states still centre. |
| 2026-09-02 | v1.19 | template explorer layout (058) | §4.6 rewritten as the template **explorer**: two panes, full height below the page header (the 041 layout math) — tree LEFT (~30%, natively resizable, 260px floor) and the SELECTED template RIGHT (header with full path/badges/Open-in-editor above the versions table; quiet `Select a template` empty state). The owner's spec was Windows file explorer, not a refinement of 047's full-width accordion. **A selection swaps `#template-detail`'s innerHTML and nothing else** — pinned at the fragment-contract level by the new `TemplateExplorerRenderTest` (the detail fragment contains no tree markup, no tree swap target, no OOB). `/partials/templates/versions` now answers `partials/template-detail` (header from `findLatest` + 047's versions table unchanged; a dead name renders a quiet not-found). Search renders IN THE LEFT PANE — full-path `role=listbox` rows that select exactly like tree leaves (047's browse-vs-search rule and the §4.4 future-picker note both survive). Keyboard per the spec: up/down moves selection, right/left expands/collapses, Enter opens the editor — `static/js/template-explorer.js` owns selection, `aria-selected`/`aria-expanded` and the roving tabindex (root level is `role=tree`, nested levels `role=group`); leaves carry `hx-sync="#template-detail:replace"` so a keyboard sweep cannot race a stale detail load. Everything underneath 047 is unchanged: server-side prefix levels one request per expansion, virtual folders, the four absences, per-level paging, filters, the create modal. |
| 2026-09-02 | v1.18 | version selection in the template editor (054) | §4.7: the version dropdown was inert (it blanked the query string and reloaded the same version). Selecting a version now LOADS it read-only — body in the preview surface as a `<pre>` (never a disabled textarea), version number, RELEASED/DRAFT badge, `released_at`/`released_by` — swapping the new `#template-source` root via `GET /partials/templates/editor/source` (§5's idiom: the page paints the same fragment the swap returns). The editable textarea carries the working version and nothing else, so no selection can make a RELEASED row the write target. **Edit** (`POST /partials/templates/editor/edit`) decides nothing itself: a draft exists ⇒ that draft opens and NOTHING is written; otherwise the selected version is copied into a new draft through the same `TemplateDraftService` the REST write uses, based on the CURRENT RELEASE's hash. The §4.7 Save bullet now states the honest position: no save affordance is on the screen, and none is rendered while a RELEASED version is displayed. |
| 2026-09-04 | v1.20 | SEO (073) | §4.15 gains the thirteen intent-cluster pages the public site now serves (pillar, six engine pages over one template, client setup, AI data pipeline, text-to-SQL agent, two comparisons, federated query), the shared `site/_layout.html` chrome, and the guard list; new §4.15a records `/robots.txt` and the generated `/sitemap.xml`. §4.16 **Documentation is now public**: both routes are `permitAll` (the viewer renders packaged Markdown, reaches no principal, workspace or datastore, and the same content is public on GitHub), with an anonymous public chrome + SEO head and the application chrome unchanged for signed-in readers. Page titles, descriptions and canonicals moved out of the templates into the `SitePages` registry, which `/sitemap.xml` and the SEO guards read — the homepage `<h1>` is untouched. |
| 2026-09-02 | v1.19 | promotion (055) | New **§4.17 promotion screen** — the ONLY way to promote (Versioning §10.1 D8: no MCP tool, no schedule; the round's fence excluded `modules/mcp-server` to make that mechanical). Lists exactly §10.2's set, computed in the sender service rather than in the template so the screen cannot drift from the rule. Three distinguishable states — no target configured, in sync, target unreachable/refusing — because the operator's next step differs in each; the refusal CODE is shown in place, not on a generic error page. The Promote action is session-only and re-runs every §10.3 guard against a fresh inventory server-side. The nav gains a Promotion link. |
| 2026-08-31 | v1.16 | recurrence defect round (034) | §4.5: the OOB whole-table rule recorded beside the swap-root rule — a table partial travels as a whole `<table>` on any out-of-band path; a `<tbody>` carrying `hx-swap-oob` nested in a `<div>` is silently discarded by the browser's fragment parser (030 F-1, previously known only from the §4.10 changelog note). Doc-only; a mechanical guard was judged disproportionate (the shape is only visible to a real HTML parser — a regex over templates cannot tell an OOB `<tbody>` from a legitimate one inside a `<table>`). |
| 2026-08-31 | v1.15 | website + docs in-app (033) | §4.2 Dashboard moved from `/` to `GET /dashboard` — `/` is now the public marketing site (new §4.15, app-served, cache-defended per OPEN-ITEMS T46, no rate limiter); new §4.16 Documentation — the packaged spec set rendered in-product at `/docs` (session-only), with the §A link-rewrite rule (packaged slug or canonical GitHub URL, never a dead relative href) and `th:utext` doc-body insertion. Navbar gains the Docs entry; error pages and login/workspace-switch redirects point at `/dashboard`. The root `README.md` website pointer and the `website/` directory are gone (the app's vendored design system is the single copy). |
| 2026-08-05 | v1.0 | initial draft | UI screens inventory: 12 screens (login, dashboard, pipeline list/editor, datasource list, template list/editor, execution history/detail, API keys, user settings, admin users), htmx patterns, error pages |
| 2026-08-07 | v1.1 | consistency campaign | Per [SPEC-REVIEW-2026-08.md](SPEC-REVIEW-2026-08.md) §2.12: route convention §2.1 (pages / `/partials/**` / `/api/v1/**`, htmx never calls the JSON API) and all `hx-*` endpoints re-pointed at `/partials/**` incl. §4.10 API keys [1]; template-editor context form replaced with free-form key-value/JSON input — templates no longer declare variables [1b, D3]; §5 htmx example fixed (`hx-include` + `th:attr` `hx-vals` instead of `${q}` interpolation) [2]; §4 scope column declared a view of the authoritative [Auth §7.6](auth.md#76-operation-matrix--two-axes-authoritative) matrix, datasource test corrected to `author`, key scopes ⊆ creator's scopes [3, D15]; §4.11 theme preference persisted on the `users` row via `PATCH /partials/profile/theme`, not session state [4]; §4.11 provider badge renders the configured provider `display-name` [5]; §4.9 result panel rebuilt on the uniform cursor with the TTL-expired state and `format`-parameter downloads [6, D9]; new §5.1 standard states (empty / loading via `hx-indicator` / errors via the `response-targets` extension into `#toast`) [7]; CSRF via `dp_csrf` cookie + `DP-CSRF-Token` header wired in the layout [D10] |
| 2026-08-28 | v1.9 | workspaces surfaces slice | New **§4.13 Workspaces** screen (create per mode, open-join, owned-workspace member management, switch) + navbar **workspace switcher** (§3: POST /workspace/switch re-stamps the session claim; hx-headers carries DP-Workspace for partials). §4.5 datasource list re-grounded: workspace-scoped listing, workspace/readonly columns, register modal with the D8-gated `global` (admin-only, visible-disabled) and `readonly` checkboxes, Register hidden for gated-off members. |
| 2026-08-30 | v1.11 | datasources SPA table + toasts | §4.5: search/dialect/pager re-fetch only the list fragment into the stable `#datasource-list-wrapper` swap root (the id moved onto the fragment root — it previously died with the page's placeholder div); the connection test result is a §5.1 toast, ending the row-swap/"Back to list" contract that broke the table layout; the dead View button (REST JSON via hx-get) removed. New §5.1 **Notifications** state: `#toast` stack, server-rendered `partials/toast`, layout-global `toast.js` lifecycle. |
| 2026-08-30 | v1.10 | local password auth | §4.1 Login: local form + divider + provider buttons, only enabled methods render; `credentials`/`locked` banners join the `?error=` idiom. §4.12 admin users: create local user (one-time password shown once), reset, disable local, unlock; `Local` column. New §4.14 Change password — the §5A.4 forced-change screen. |
| 2026-08-31 | v1.12 | table component rollout + live list controls | §4.3/§4.6: the pipelines and templates swap roots moved onto the fragment roots (`#pipeline-list-wrapper` / `#template-list-wrapper`) — the ids previously died with the page's `th:replace`d host div, so both pagers had never worked — and the search inputs gained their `hx-*` wiring (they were inert). §4.3's unimplementable datasource filter was deleted (deferred: `PipelineRecord` carries no datasource; serving it needs a join through the pipeline definition). New §5 **shared pager** fragment (`partials/pager`, caller-built URLs, nullable `total`). New §5.1 **Search** rule: a screen's search covers every rendered column — datasources search now matches URL, username, dialect wire value and workspace; template search matches the dialect column (repository-level ILIKE). `.ds-table`/`.ds-badge`/`.ds-empty` adopted on the three list partials, API keys, admin users (rows are Kotlin-built), workspaces and the template editor's imports table; the undefined `.ds-empty-state` class is gone. §4.8 keeps its `#execution-table`/`innerHTML` contract; its pager's `hx-vals` offsets are now server-rendered via `th:attr` (they previously reached the browser as an unprocessed `[[...]]` literal). Two new mechanical guards: no unquoted literal inside a `th:attr` assignation, and every `hx-target` id a rendered list page references must exist in that page. |
| 2026-08-31 | v1.13 | toast application rollout | Every mutation now reports its outcome through the §5.1 toast — successes AND refusals. §5.1 rewritten: the `response-targets` prescription is gone (the extension was never loaded; the layout's `hx-target-error` was dead config and is removed) and replaced by the four delivery shapes — A (content + OOB toast), B (toast-only, `hx-swap="none"`), C (refusal: real 4xx + `HX-Retarget: #toast` + `HX-Reswap: beforeend`, admitted by `toast.js`'s new `bridgeErrors`, the twelve-line `htmx:beforeSwap` bridge that exists because htmx never swaps 4xx/5xx on its own), and D (the ONE client-side builder `DpToast.show`, for stream-borne events with no HTTP response; `createElement` + `textContent` only). Toasts bound for the stack travel WRAPPED in the new `partials/toast-oob` fragment, because htmx swaps a non-`outerHTML` OOB element's children, not the element. The hard rule is carved into §5.1: a 6s toast never carries anything the user must keep — one-time secrets stay in their persistent inline panels and toasts only point at them. Previously INVISIBLE refusals are now delivered: admin user-create 400/409 (§4.12, Shape C), the unknown-theme 400 (§4.11, Shape C), and the password-change failures (§4.14 — field-level validation, so they stay inline, delivered by the screen's own `htmx:responseError` listener, the one screen where Shape B and inline errors coexist). §4.5 register drops `HX-Redirect`: success closes the modal, refreshes the list and toasts without a page reload, while the modal refusal stays inline (the screen keeps its own error path — no `HX-Retarget`). §4.10: creating a key now refreshes the key table out-of-band (the table markup is one extracted fragment, `settings/api-keys :: keysTable` — swapped at TABLE level, because a `tbody` OOB element nested in the response is destroyed by the browser's fragment parser: table-only tags outside table context are dropped tokens) and the dead `HX-Trigger: keyRevoked` header is gone. §4.9 cancel toasts; its `ResponseStatusException` refusals stay full error pages (recorded gap: `UiExceptionHandler` has no htmx-aware branch). §4.13's redirect flash renders as a server-side toast in the layout's `#toast` stack instead of the banner, copy verbatim. New guards: `ToastOobFragmentRenderTest` (nesting), `ToastMarkupParityTest` + `toast.test.mjs` (one markup contract, server and client), and the `bridgeErrors`/`show` JS tests under `editorJsTest`. |
| 2026-08-31 | v1.14 | execute page redesign (032) | §4.4 Pipeline Editor expanded from the bare pointer into the rows that touch this document's shared contracts: the new `READ_RESOURCES` node-SQL read partial (`GET /partials/pipelines/{id}/nodes/{nodeId}/sql`, spec §8.3) with the deliberately-NOT-a-toast copy confirmation (live region + 1.5s label swap); the result grid moved onto the shared `.ds-table` (bespoke `.pe-result-table` styles deleted; paging stays the client-side cursor contract, restyled to the shared pager's look); and the SSE terminal events land on §5.1's Shape D — `pipeline_completed`/`execution_aborted` toast via `DpToast.show` (stream-borne, no HTTP response), `pipeline_failed` keeps the error modal — with all three announcing on the live region. No §5.1 amendment was needed: Shape D already existed (v1.13) and the copy button does not use it. |
| 2026-09-03 | v1.20 | datasource credentials (061/T84) | §4.5 gains the **Last test** column: an `ok`/`failed`/`never tested` badge with the timestamp and the driver's message on hover, from the datasource row's stored outcome ([Datasources §8.1B](datasources.md#81b-the-last-tests-outcome-is-stored-and-listed)). It exists because listing never connects — on 2026-09-02 this screen showed a datasource as fine while every execution failed at CONNECT. The §5.1 Search rule follows the new column (`ok` / `failed` / `never tested` all match). No polling is added. §4.5's delete note now points at the any-version in-use guard (T79). |
| 2026-09-05 | v1.22 | pipeline folders (067) | §4.3 becomes the **Pipelines Explorer**: pipeline names are folder paths, so the flat table is a tree LEFT + selected pipeline RIGHT — the §4.6 shape, reusing `template-tree.css` and `template-explorer.js` (which now finds its pane by a `data-explorer-pane` marker rather than a hard-coded id, so one file serves both screens). One level per request; a non-empty `q` is a flat list of full paths; the detail pane is read-only and carries settings, parameters, versions and Open in editor. **T108:** the permanently-`disabled` "Create Pipeline" button and its "Phase 2 other worktree" tooltip are deleted — an affordance the server does not have, advertised with an internal note — replaced by a sentence naming MCP as the authoring path and the roadmap as the plan. |
| 2026-09-06 | v1.24 | shell v2 + the API section (079) | **The shell became a rail + top bar** (new **§3.4**, normative): 232px collapsible rail grouped Build/Operate/Organisation with Dashboard above, workspace switcher as a card, Pipelines/Templates counts behind a 60s `NavCounts` TTL (no general metadata cache existed to reuse), collapsed state on `<html>` written by the layout's ONE inline script before first paint; a top bar with a breadcrumb derived from `AppNav` (and re-derived client-side from the active link after a boosted swap — `ShellRenderTest` asserts the table and the markup agree), a search field that is a `<div>` wired to nothing, a light/dark toggle and an avatar menu (OIDC `picture` when stored, initials otherwise; Escape/outside-click/arrow keys; `aria-expanded` + `role="menu"`). The 076 boost contract is unchanged. The active item gains a 3px `--border-focus` bar the mock does not have, because §2.8 says a tint alone cannot carry selection. **One theme preference, five controls**: the design system ships one stylesheet per look, so light/dark/auto and the six palettes are nine values of `users.theme_preference` — there is no mode column and none was invented. **New §4.18, the API section** at `/api-console` (`read`): published endpoints (released version, exact-node binding count, and NO calls column — nothing in the system records endpoint serves, stated rather than sampled), a read-only key list showing an endpoint key's BINDINGS instead of its absent scopes, and the MCP card with `McpToolCatalog.NAMES.size` and a `base-url`-derived URL (never request-derived). **§4.11 Settings** is a card grid (owner ruling, option (a)) with scopes as chips, a Mode row, a disabled Compact density and no `.app-reading`. **§3.1 amended**: Settings/Admin/Workspaces leave the reading column. **§3.3 amended**: the headline is `--text-xl` in the app shell, and **§3.3 gains the faces** — Inter and JetBrains Mono vendored under `static/vendor/fonts` (OFL-1.1, owner-confirmed AGPL-compatible), which closes the gap that `light`, `dark` and `minimal` named neither face at all. **§4.17 promotion** flashes become toasts, with `toast.js` draining any `[data-toast-flash]` bin. `InlineWidthAuditTest` widened from "no inline width" to **no inline `style=` and no literal colour on any app template**, allowlist empty and asserted empty; the 330 attributes became the `u-*` utility layer plus semantic `app-*` classes, all token-only. |
| 2026-09-05 | v1.23 | app shell + coherence (076) | One width policy (new **§3.1**, normative): every screen full-bleed with one gutter — `--app-content-max`, `.app-main-bleed` and the editor's `fullBleed` opt-in deleted (the opt-in became the rule); reading content in `.app-reading` (90ch, left); inline `max-width`/`grid-template-columns` out of every app template (`InlineWidthAuditTest`). **Boosted navigation** (new **§3.2**): `hx-boost` on nav + `<main id="app-main">` with the swap policy in `shell.js` (NOT inherited attributes — that would hijack partial swaps), `#app-progress` as the loading signal, client-mirrored active-section state, the five full-navigation routes marked `hx-boost="false"` (`ShellRenderTest`), editor teardown/re-bind semantics, and server redirect flashes rendered into a hidden `#toast-flash` bin inside the swapped region (`WorkspacesUiControllerTest` re-pinned). **Bootstrap out**: the 5.3.8 webjar, its layout `<link>` and every lockfile/verification reference deleted — no app screen used a Bootstrap class; what its reboot silently provided (`<code>`, `<pre>`, `<small>`, `<strong>`) is restated on tokens in app.css (`LayoutStylesheetOrderTest` rewritten, `SiteAssetAuditTest` sweeps for the reference). One type/density scale (new **§3.3**): `.ds-headline` titles everywhere (`TypeScaleAuditTest`), tabular tables, dates always proportional, mono for identifiers only with the per-column decision table. **T114**: execution lists show the pipeline display name (machine path on hover) via ONE web-side batch query per page — no dag/pipeline-contract change, no request per row. §4.9's node-stats table gains the Context column (`context_key → value` for CALCULATOR nodes — the claim `pipeline-contract.md`/`dag-executor.md` always made, now true). |
| 2026-09-05 | v1.24 | mandatory folders (077) | Both explorers' ROOT level holds **folders only** ([Template Hierarchy §4.1](template-hierarchy-design.md#41-grammar)): a name carries a folder, so neither browse model queries the root's leaves and both level fragments lose the `prefix.isEmpty() ? name : name.substring(…)` label branch. §4.6's create modal gains the helper line `folder/name — e.g. test/scratch`; the templates empty state says to author one under a folder. A pre-077 flat PIPELINE can still exist (no migration gate on that side) and stays reachable by search, by `pipelines_list` and by its own URL — it is simply not a row the root level draws. |
| 2026-09-09 | v1.28 | resizable panes (104) | **Panes the user can size, one mechanism for two of them** (`static/js/splitter.js`: a pure clamp/step core under `node --test` plus a `role="separator"` DOM adapter — pointer capture, touch, arrows ±16px / Shift ±64px / Home / End, double-click reset, `localStorage` per pane key). **§4.4** the editor dock's 232px fixed height becomes a drag on its top edge (floor 120px, ceiling = stage − 160px), remembered as `dp.pane.editor-dock`, restored before first paint, and the Cytoscape canvas follows it: graph.js's stage `ResizeObserver` now routes through `handleStageResize` — `cy.resize()` always, re-fit only if the view was still the fit, which also fixes the rail-collapse and window-resize cases that silently had the same defect. **New §4.3a** (shared by §4.3 and §4.6): the tree pane's native `resize` corner is replaced by a handle ON the divider, floor 260px, ceiling 40vw, one key for both explorers so the width follows the user. Sizes travel as ONE CSS custom property on `<html>` (`--pe-dock-pane-h`, `--tplx-tree-w`) with the shipped default as the `var()` fallback, so reset is "remove the property"; `InlineWidthAuditTest`'s KDoc names that shape as the allowed one. §5.1 unchanged; no agent-facing surface changes, so the Skill is untouched. |
| 2026-09-08 | v1.25 | 094 datasource lifecycle | **§4.5** gains three things. A collapsed **"Connection pool — defaults"** section in the register modal AND in the new **edit dialog**: the eight tunable HikariCP keys ([Datasources §5](datasources.md#5-connection-pool-configuration)) with unit, meaning and a help line naming the LAYER each prefilled default came from; `readOnly` mirrored disabled (it is §5.6-refused, so a free field would reject every value); refused keys never rendered; a field left at its default not persisted; the dialect select re-fetches the section because the default is the dialect's to decide. An **Edit** row action — name and dialect disabled (immutable, and re-pointing a live datasource would take every pipeline with it), a blank secret keeps the stored credential, `global` admin-only with a hidden companion field so an UNCHECKED box is still a deliberate write. A **Delete** row action whose dialog asks the usage question FIRST and renders the referencing nodes with their versions; the in-use branch has no button at all, the unused branch's confirm names the datasource. Both dialogs are fetched as whole backdrops into one empty `#ds-dialog` (nothing opens them; closing empties the container) and close on a `data-ds-saved` marker a refusal never carries. |
| 2026-09-09 | v1.32 | the shell fits a phone (110) | **New §3.6 (normative) — the shell at three widths**: ≥1100px as today; 768–1099px the rail STARTS collapsed by a pure-CSS default (`rail-expanded`, stamped pre-paint by the layout's one inline script, is a stored-"0" user's opt-out; the collapse toggle acts on the VISUAL state, so its first click there expands); <768px the SAME `<aside class="app-rail">` becomes an off-canvas drawer — `rail-open` on `<html>` set/cleared by `shell.js`, never persisted, opened by the topbar's `#rail-open` (the sprite's new `menu` glyph; hidden ≥768 by CSS), closed by `#rail-close`, Escape, the `.app-rail-backdrop` scrim, or any BOOSTED navigation (the same one-shot `applyBoostSwap` arms for the §3.5 entrance decides the close, so a background partial settling cannot slam it shut). Opening moves focus to the first nav link; closing returns it; body scroll locks via the same class. The search MOVES into the drawer's head (a second inert copy of the §3.4 placeholder — hidden is not moved). §B: crumbs truncate to the leaf with the full path on `title` below 1100; every `.ds-table` scrolls inside its own box at every width (`display:block` + `overflow-x:auto` — one rule, wrapper-less tables included, oob fragments untouched); `.app-main` gets `min-width: 0` (the bare-`1fr` track was letting any page's min-content widen the document); `.app-modal` collapses to one full-width rule below 768 with a sticky close; the toast stack sits at the BOTTOM below 768; touch targets are `--field-height-lg` in the drawer and topbar. §4.4/§4.7: the editors are desktop-first BY DECISION — below 768 a `.app-wide-screen-note` band names the entity, its lifecycle badge and the explorer link, editor untouched underneath. Guards: the §D walk now covers 390 and 768 (`AppShellBrowserTest`), and `MobileShellBrowserTest` drives the drawer's open/close/focus/motion contract end to end. |
