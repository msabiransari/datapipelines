# UI Screens Inventory

**Status:** v1.124
**Owner:** datapipelines.co core
**Depends on:** [Pipeline Editor](pipeline-editor.md), [Design System](pipeline-editor.md#34-design-system-acmedesign-tokens), [REST API](rest-api.md), [Auth & Security](auth.md), [Templates](templates.md), [Configuration Reference](configuration.md)
**Last updated:** 2026-10-09 (#465; #412; #462; #460; #459; #416; #442a; #398; #392; #426; #420; #408; #422; #402; #401, #407; #399; #415; #383; #376; #400, #409; #374; #386, #387; #364; #350, #395; #371; #349; L4b, #353; 348-c, #358; L3b, #10)

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
7. **Scopes are not asserted here.** The per-screen scope column in §4 is a convenience view of the authoritative matrix in [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative).
8. **Structure must survive a bad monitor — non-text contrast floors (WCAG 1.4.11).** Boundaries a user needs to identify a component are NOT text and have their own floors, enforced by the design-system audit (its `npm run build` runs it first; the vendored copy is guarded here by `VendoredNonTextContrastTest` in `modules/web`): `border-default`/`border-hover`/`border-focus` at **3:1** on the surface they are drawn on (inputs, panes, cards), `border-subtle` at **2:1** (separators — 3:1 makes every table heavy), `surface-selected` at **1.5:1** with a left accent bar of `border-focus` width `3px` (a tint alone cannot reach 3:1 without turning grey). A pane or card boundary is a `border-default` line, never a tint alone. Text keeps its 4.5:1 floors, unchanged.
9. **Every table is the data table (282, §3.7).** One house component — `.dt-frame` › `.dt-viewport` › `table.ds-table`, one rule set (`data-table.css`) and one enhancer (`data-table.js`) — draws every table in the app: the header stays while the rows scroll, the scrollbar starts under the header (the cap), columns sort and resize, a wide table's first column can hold, and loading and empty are rows of the same table. No screen styles a table of its own; a new table is framed or `DataTableCssTokenTest` names it. A grid library was assessed and set aside (#282: AG Grid's theming injects styles at runtime, and the CSP is `style-src 'self'` with no nonce).

### 2.1 Route Convention

Three disjoint URL spaces, plus one shape that lives on the first of them. A given URL belongs to exactly one space, and the response media type follows from the space — not from the caller.

| Space | Prefix | Returns | Called by |
|---|---|---|---|
| **UI pages** | root paths — `/`, `/pipelines`, `/pipelines/{id}`, `/executions/{id}`, `/settings/api-keys`, `/admin/users` | full HTML document (Thymeleaf layout + content) | browser navigation |
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

**Partials are a presentation layer, not a second implementation.** A `/partials/**` controller calls the *same* application service as its REST counterpart and renders the result into a Thymeleaf fragment. `POST /partials/api-keys` and `POST /api/v1/auth/api-keys` ([REST §16.1](rest-api.md#161-api-keys-creation-is-on-the-keys-page--keys-v2)) differ only in how the response is serialized — same service, same validation, same refusal.

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
│  Schedules   │                                                       │
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

`pipeline-editor.css` and `docs.css` are still page-scoped and carry the same defect (`template-editor.css` was
hoisted by #398, with the template workspace's own sheet).
The fix is identical (hoist the link) and belongs to whichever round owns those surfaces.

### 3.1 Shell and width policy (076, normative)

Measured on the owner's ~3,000px window (2026-09-05): four content widths across eleven screens — a 1600px cap on most, bespoke narrower columns on Settings, full-bleed on Templates and the editor. The policy that replaces them:

1. **Every app screen is full-bleed.** `<main>` spans the viewport minus one gutter (`var(--gap-lg)`), exactly as the editor always has. `--app-content-max`, `.app-main-bleed` and the editor's `fullBleed` opt-in are deleted — the opt-in became the rule. The nav spans the viewport with the same gutter. `EditorLayoutRenderTest` pins the editor and a list page rendering the SAME `<main>`.
2. **Reading content gets a reading column, not a different container.** Docs prose, empty states and forms sit in `.app-reading` (`max-width: 90ch`, LEFT-aligned inside the full-bleed main — never centred). Tables and trees never use it. **Amended 079 §D:** Settings, Admin and Workspaces do NOT — a 90ch measure is right for prose and wrong for a grid of independent cards, which is what left three quarters of a wide window empty; Settings is a two-column card grid at ≥1100px and one column below (owner ruling 2026-09-05, option (a)). `.app-reading` stays on Docs, where the content really is prose.
3. **Width lives in classes, never inline.** No app template carries an inline `max-width` or `grid-template-columns` — modals, search inputs, auth cards and the stats grid use the `app.css` classes (`app-modal*`, `app-search-input`, `app-card-auth`, `app-stats-grid`, …). `InlineWidthAuditTest` scans the templates and fails the build on a regression.

### 3.2 Boosted navigation (the app shell, 076)

`hx-boost="true"` on the `<nav>` and on `<main id="app-main">`. A section click — or any in-content navigation — fetches the SAME full page (there is no second template variant) and swaps only the main region; the nav, the workspace switcher and the toast stack persist, the URL pushes, back/forward ride htmx history. `#app-main` also carries **`hx-history-elt` (#358)**: the history cache snapshots THE MAIN REGION, not the `<body>` — footer scripts are never cached and never re-executed on a Back.

- **The history cache is region-scoped, and its entries are shape-guarded (#358).** Without `hx-history-elt` htmx snapshots the whole body — footer scripts included — and a cached restore re-created and re-executed every one of them (a second Alpine, a second observer, stacked bindings). The shell owns two defences, one rule (a body-shaped entry serialises the `<main id="app-main">` OPENING TAG as a CHILD; a main-shaped one cannot contain it — and the guard matches that serialised start tag, `shell.js` `historyEntryIsBodyShaped`, never the bare attribute literal `id="app-main"`, which user text may carry (#364)): at init a one-time purge drops every body-shaped entry from `sessionStorage`, and a body-shaped entry that is still HIT at restore time is dropped and the restore takes a full fetch — htmx's own `refreshOnHistoryMiss` policy (a cancelled `htmx:historyCacheHit` in htmx 2.0.10 would simply die, never falling through to the server fetch). Pages whose scripts want transient state (sizes, placements) keep the 287 registry discipline below. **The workspaces' own entries sit beside it (#402):** an in-page version or tab switch (the pipeline workspace, §4.4; the dashboards workspace) pushes an entry whose state is `{dpWorkspace: {family, version, tab}}` — never `htmx: true`, so htmx's popstate ignores it and `static/js/workspace/history.js`'s ONE window listener replays it in page; such an entry never enters the cache, and the helper keeps htmx's own current-path record (`htmx-current-path-for-history`, an htmx 2.0.10 internal pinned by `workspace-history.test.mjs`) on the live URL, so a boosted leave after a switch is snapshotted under the URL the reader returns to (a cache HIT). An entry of ours that pops while another screen is showing is handed to htmx's own restore.
- **The swap policy lives in `static/js/shell.js`, not in attributes.** `hx-target`/`hx-select`/`hx-swap` are deliberately NOT set on `<main>`: htmx inherits them into every child request, which would retarget the screens' partial swaps (search results, tree levels, dashboard stats) at `#app-main`. Instead `shell.js` listens for `htmx:beforeSwap` and, only for requests htmx flags as boosted, retargets at `#app-main` with `select="#app-main"` and `outerHTML show:window:top`.
- **Full navigations remain** — marked `hx-boost="false"` and pinned by `ShellRenderTest`: `/logout`, the OIDC redirects and file downloads (external links too — htmx 2 rejects cross-origin requests). **Amended 090 §C:** `/login` and the forced-change gate no longer opt OUT of boosting; they render `layouts/auth`, which has no `hx-boost` and no `#app-main` at all, so there is no boosting to opt out of. Removing the machinery beats marking each link.
- **The progress signal.** A document load used to say "loading" with a white flash; a swap must not flash, so one 2px bar under the nav (`#app-progress`, tokens only, reduced-motion respected) does the talking. Since 085 §D it shows for EVERY htmx request, boosted or partial, counted in flight between `htmx:beforeRequest` and `htmx:afterRequest` — the full loading-state contract (busy control, delayed skeleton) is §5.1's.
- **Active-section state.** Server-computed from `currentPath` for the first paint; after swaps `shell.js` mirrors the same rule (Dashboard exact, others prefix) off `data-nav-section` + `window.location.pathname`.
- **Scripts re-arm per swap — or never re-execute at all.** Page scripts whose tags ride inside `#app-main` re-execute on arrival; anything document-level installs ONCE per session. The pipeline editor is the deep case, owned by its runtime since #358: its vendors and modules ride in an inert catalog and its `.pe-root` is served — and cached — with `x-ignore`, so a cached history restore replays only the guarded `runtime.js` bootstrap, whose single `mutateDom` activation (context read, then destroy-before-bind) is the ONE initializer the restored root can get. The editor tears down on host-replacing swaps — the execution stream is DETACHED, never cancelled (`dispose()`: the run continues server-side, visible on `/executions`), the Cytoscape instance is destroyed, timers and document listeners come off — and a run that outlives the navigation is re-attached on the restored page through the execution replay stream, its terminal event arriving exactly once. The shell's own pending/busy chrome comes off before every snapshot (`snapshotClean`) and is purged from restored pages (`htmx:historyRestore`), so a restored link never looks clicked or answers `aria-disabled`. `toast.js` and `template-explorer.js` follow the same idempotent-init contract; `editorJsTest` pins them. **Since #350 `template-explorer.js` is loaded ONCE by the layout's footer** (the sidebar's trees need it on every page) alongside the reusable REST sidebar host; an explorer page's own tag still rides inside `#app-main`, and its re-execution on a boosted visit is a guarded RE-INIT of the one module — never a second closure (the old per-closure `docWired` flag stacked a document `toggle`/`htmx:afterSwap` pair per visit; `template-explorer.test.mjs` re-requires the file to pin it, `PipelineSidebarTreeStateBrowserTest` counts the listeners per source file across boosted and history navigation).

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
**Organisation** with Home alone above them (D58, #10 L3b — the landing item `Dashboard` was
RENAMED Home; the route `/dashboard` and its exact-match section are unchanged), then the
collapse control at the foot. The nav packs to the top; the free space below it is deliberate.
Build's **Pipelines** (#350), **Templates** (#398) and **Dashboards** (#10 L3b, D58) items are
**Navigating-tree branches (#460).** Pipelines, Templates, Dashboards, Visualizations and Parameter Sets
share the reusable REST search-and-tree. The catalog link and separate disclosure button retain their
own actions. A hidden panel initializes on first open; initialization requests only the root and leaves
all folders closed. Expanding a folder automatically pages its immediate children, including folders and
leaves beyond 200, without fetching grandchildren, up to the **level ceiling**: ten 200-row pages per load
(`PAGE_CEILING` in `tree/state.mjs`, #465). A level stopped at the ceiling states how many rows it holds and
offers **Load more**, which continues from the kept cursor with a fresh ten-page budget; a page that lands
re-renders only its own level, never the whole panel. Complete valid levels remain in memory and keep keyed
DOM rows, focus and scroll during navigation.

**Refresh (#465).** The named Refresh button, a return to the tab (`visibilitychange`, every mounted tree)
and an affected-parent invalidation after a change all refresh the same way: the level is refetched into a
hidden staging level and swapped in when that completes (or stops at the ceiling), so its rows, focus and
scroll stay while the request runs and no Loading row appears. Every visible open level below then reloads
the same way, after its parent; a closed level held in memory is emptied so its next expand fetches it
fresh; a folder the refetch no longer lists leaves the open set with its cached levels. A failed refresh
keeps the rows it had and offers Retry. In search mode the result set refreshes in the same staged way.

Name search uses the family's `/api/v1/{family}/tree/search`, literal case-insensitive canonical/display
name matching, and all matching artifacts plus their ancestors. Every returned path opens automatically;
search never crawls browse endpoints. The named lucide **Clear search** button and deleting the query
cancel pending search and browse work, collapse every folder and show only the normal top level. A late
reply cannot undo this reset. Partial results remain visible with an incomplete state and Retry; an empty
complete answer differs from an error. See [REST §24](rest-api.md#24-first-party-navigation-trees).

The widget's source, root, context, href and activation callback are host parameters. Selection-only hosts
use buttons. The common renderer owns roving focus, arrow navigation, Home/End and activation; focus is
separate from current-page selection. Labels use text sinks and fixed vendored lucide icons.

The global rail's pointer and keyboard separator adjusts from its 232px default through the full available
app width, with no reserved main-content minimum. Collapse and Reset stay reachable at that boundary.
Opening a tree never changes user width. `dp-rail-width:<workspace>` stores only the preferred width; a
smaller viewport clamps it temporarily without replacing that preference. `dp-tree-panel:<workspace>:<family>`
stores only panel visibility. Queries, results and descendant expansions are never persisted or restored;
fresh initialization does not reveal the current selection. Workspace/account switches create a fresh document.

Artifact, catalog and version navigation preserves the rail through `#app-main` swaps. The navigation host
validates the destination and prepares dependencies before requesting the final freshly authorized boosted
response; this adds one preparation GET (two server renders per navigation, by design). The shell's progress
bar shows from the click: preparation counts as one in-flight request (#465). Failed or superseded preparation leaves the outgoing page usable,
and late final replies are rejected. Signed-in charts share one lazily loaded vendored 3D Plotly bundle;
standalone previews keep their isolated bundle selection. Cached history contains inert page markup and
mounts each restored runtime once. Pipeline disposal detaches observation without cancelling execution;
dashboard disposal retains refresh cancellation. Sidebar view tokens bind caches to exact admitted membership;
foreign responses refuse admission, and a changed view clears cached levels before Retry.

- **The brand mark (163, #157, "D1").** Three rings converging into an outlined tile with three
  rising bars — the tile is a 4-unit `currentColor` stroke and the bars are `currentColor`
  fills; nothing in the geometry depends on the theme, so there is no filled slab to go wrong
  in dark mode. It exists once, as the `partials/brand-mark.html` fragment, and is included by
  both `.app-brand-tile` copies in the rail (expanded and collapsed) and by the public site's
  header and footer, which retired their separate line icon so the product carries one mark.
  The ceremony screens carry the same fragment since 161 (#161) — the auth layout's brand link
  and the login card's brand had kept the retired filled tile as inline copies, which is what
  `BrandMarkParityRenderTest` now renders and what its source sweep of every template forbids.
- **Counts.** Pipelines and Templates carry a badge from `NavCounts`, one cheap `COUNT(*)`
  each behind a 60-second TTL keyed by workspace. There is no general metadata cache in this
  tree to reuse — `DatasourceMetadataCache` is keyed by datasource name and `AuthCache` lives
  in `modules/auth`, which by design cannot see the `pipelines` or `templates` tables — so
  `NavCounts` copies the former's discipline (ConcurrentHashMap, injected ticker, lazy expiry,
  misses never cached) rather than its instance. A count that cannot be read renders **no
  badge**, never a zero.
- **The Admin item follows the reader's authority (143, T315).** It leads only where its
  reader may go, or it is absent: a **super admin** gets `/admin/users` (instance user
  administration, `user.manage`, boosted like every item); a **workspace admin** gets
  `/workspaces#workspace-members` — the ACTIVE workspace's members section on Workspaces
  (`workspace.members.manage` is judged against the active workspace, §4.13), as a **full
  navigation** (`hx-boost="false"`, so the browser scrolls to the fragment); a viewer, an
  author or a pure promoter gets **no Admin item**. Both booleans (`navAdminUsers`,
  `navAdminMembers`) are `UiWorkspaceAdvice`'s, derived by `RoleModel.shell` from the same
  predicates as the verb booleans — never from the role label — so every full paint (login,
  a workspace switch) carries them, including screens whose controllers stamp no roles; a
  boosted swap keeps the rail as painted. The instance entry is judged WITHOUT the workspace
  (the authority is the user's, so a super admin whose active workspace is gone keeps it) and
  narrowed by the credential axis like every rung (no key holds `admin`, O-2). Active state
  is the path rule unchanged: `/admin/*` lights Admin; on `/workspaces` the Workspaces item is
  the active one and the members shortcut never lights by itself.
- **The collapsed state** is a class on `<html>`, written by `/js/rail.js` — a
  parser-blocking script in the layout's `<head>` — before the first paint and toggled by
  `shell.js` afterwards. It was the layout's one inline script until 188 (#188): the enforced
  Content-Security-Policy has no `'unsafe-inline'`, so **no template carries an inline
  `<script>`, an `on*=` handler or a `style=` attribute** (`InlineScriptAuditTest`; the
  browser suite holds every page to zero CSP violations). The reason the rail script cannot
  be deferred still holds — a deferred script runs after the document paints, so the rail
  would render at 232px and snap to 60px on every navigation — which is why it is its own
  blocking file rather than part of `shell.js`.
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
  folders, file-code on leaves. A leaf row keeps the chevron SLOT, empty, so its glyph and
  label sit level with a sibling folder's — one indent right of its parent's, at every depth
  (owner, 2026-09-12: with the glyph in the chevron's slot a leaf read as its folder's
  sibling); `TreeIndentAndVersionMenuBrowserTest` measures it in both explorers.

- **The role badge (114).** A `.ds-badge.app-ws-role` under the workspace name inside the
  switcher card, carrying the role the signed-in person holds IN THAT WORKSPACE — `viewer`,
  `author`, `promoter`, `workspace admin`, or `super admin` for an instance super admin in any
  workspace, membership or not (D7). It is the membership's ONE role ([`WorkspaceRole`](enums.md#8c-workspacerole--the-one-role-a-membership-holds), D1), narrowed to
  nothing else since #215 slice (b): scopes are gone and no key renders a screen (the MCP key is confined to `/mcp`). `data-role` is the
  stable hook the tests read. It sits INSIDE the `app-rail-label` span, so the collapsed rail
  hides it with the workspace name rather than leaving a word with nothing to qualify; at all
  three widths (§3.6) it is the same element in the same place. The role decides what the rest
  of the app renders — the inventory is [§4.3e](#43e-role-visibility--every-verb-and-the-role-boolean-that-renders-it-114-normative).

- **The switcher's options are full-paint facts (#256).** The `<select>` renders from
  `UiWorkspaceAdvice.workspaceOptions` (`listOwn(...).filter { it.workspaceActive }`) on every
  FULL paint only — a boosted swap replaces `#app-main` and cannot reach the rail. So any
  page-route mutation whose success changes the option list navigates in FULL (`hx-boost="false"`,
  097 §2.1 — the same rule the switch form has always followed): the create form since #170,
  and deactivate / reactivate / delete since #256, whose guard asserts each verb's before/after
  option list with no reload the test performs (`WorkspacesCreateBrowserTest`: deactivate → the
  option is absent without a reload, reactivate → present, delete → absent). The switch itself
  is the third shell-changing verb and re-issues the session cookie — a full navigation by
  construction.

**The top bar** (`--header-height`). Breadcrumb (`<group> / <page>`, group muted, page bold),
the search field **palette** (161), the light/dark toggle, and the avatar menu. The **MCP-key
chip** (179) sat between the toggle and the menu until keys v2 (233, A15) retired it — see below.
From 768 to 1099px the search field itself hides (§3.6) and a search **icon button** stands in
(#159): it reveals the same palette in place, and ⌘K drives it too.

- **The MCP-key chip is retired** (keys v2 — 233, A15, 2026-09-25). 179 (D16, ruling 7) put
  the caller's one login-minted `user` key in the top bar with Copy and delete-to-rotate, and
  #213 made the copy show-once. Keys v2 retired the login mint: every key, `mcp` included, is
  created on the Keys page (§4.19) by a member whose role allows it, with the role the creator
  may give, and its plaintext is shown once at creation. The chip's template, its two verbs
  (`mcp-key-copy`, `mcp-key-delete`), `GET /partials/mcp-key/chip` and the shell's copy handler
  are gone; `GET /partials/mcp-key/secret` (`mcp_key.own`) survives for the V37-migrated keys'
  sealed copies with no page element calling it — #248 decides its fate. `/settings/api-keys`
  (§4.10) points at the Keys page.

- **The breadcrumb** is server-rendered from `AppNav.crumbFor(currentPath)` and re-derived
  client-side after a boosted swap from the active rail link's own `data-nav-group` /
  `data-nav-label`. `ShellRenderTest` asserts the Kotlin table and the rendered markup agree
  for every link, so the highlighted section and the crumb cannot drift apart.
- **The search field is real (161, #155).** A `<input role="combobox">` in the top bar — and
  the same control in the drawer's head below 768px — opens a palette on focus/click or
  **⌘K / Ctrl+K** and fetches `GET /partials/search?q=…` over htmx (`input changed
  delay:200ms`; a BLANK query fetches nothing — `shell.js` cancels the request, so an empty
  box never claims to have matched nothing). The answer (`SearchController` /
  `SearchBrowseModel`, workspace- and role-scoped like the screens it jumps to) groups up to
  8 hits each under **Pipelines / Templates / Executions**, every hit a link (pipeline → its
  editor, template → the template editor, execution → its detail; executions show status and
  started-at), with a "more…" row per group opening the FILTERED list page (`?q=`, `?status=`,
  `?pipeline_id=`). The keyboard is the combobox contract: ↑/↓ move the active row (focus
  stays in the input; `aria-activedescendant`), Enter opens it, Esc or an outside click
  closes, and any boosted navigation closes the palette. Both copies (top bar + drawer) share
  the behaviour through `data-search-*` hooks; ⌘K drives whichever copy is displayed, and in the
  768–1099px band — where neither is (#159) — it drives the band's entry button, a topbar icon
  button lit only in that band, which reveals the topbar copy in place (no third copy of the
  control: same ids, same fetch) and stands down when the palette closes. The search is read-only for every
  role — a viewer sees what a viewer may open.
- **The avatar** renders the OIDC `picture` claim when there is one and initials otherwise.
  The claim IS stored: `users.profile_picture_url`, written by `OidcSuccessHandler` through
  `UserRepository` on every login, and rendered on Settings since 025.
- **The menu** carries the signed-in identity, Appearance, the theme swatches, Settings, API
  keys, **Report a problem** and Log out (which keeps its POST form and `hx-boost="false"`).
  Report a problem (127) is the one external choice: the GitHub bug form, `target="_blank"
  rel="noopener"`, never boosted, its URL read off the same `SitePages` constant the site
  renders from (surfaced to every template by `SiteOriginAdvice`) — the app tab never
  navigates. Escape, an outside
  click, and **choosing any item** (a mode, a swatch, Settings, API keys, Report a problem,
  Log out) close it,
  with focus returned to the avatar; arrow keys move focus within it without activating
  anything; the trigger carries `aria-expanded` and the popover `role="menu"`. (Until
  2026-09-13 only Escape and an outside click closed it: a theme change left the menu hanging,
  and Settings — a boosted swap of `#app-main` alone — carried it open onto the next screen.)

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
2. **Every table is the data table component (§3.7) — frame, viewport, sticky header, the cap — with `font-variant-numeric: tabular-nums`** (`data-table.css`, which folded app.css's old `.ds-table` block in 282). Dates and timestamps are ALWAYS proportional — never inside a `.num`/mono cell.
3. **Mono is for identifiers only**: machine names, ids, template refs (`path @ vN`), SQL, keys/prefixes, `context_key → value`. Display names, usernames, badges and dates are prose. The per-table decision:

| Screen | Mono columns | Prose columns |
|---|---|---|
| Dashboard recent executions / §4.8 history | (pipeline machine path on `title` only) | display name, status, triggered_by/via, started_at, duration (`.num`) |
| §4.9 node stats | node id, Context `key → value` | rows in/out, duration (`.num`) |
| §4.19 API keys | key prefix | name, kind, role, acts as, created by, associations, created/expires/last used |
| §4.12 admin users | — | email, display name, provider, created, status, actions (`.num`) |
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
| ≥ 1100 px | as today (expanded; `rail-collapsed` on user choice, remembered); **user resizing reaches the full available width**, independent of tree content | as today | `--gap-lg` |
| 768–1099 px | **starts collapsed** (icons only) unless the user expanded it — same class, same `localStorage` key, one more rule: the default flips at this width; an expanded rail keeps the user's preferred width, bounded by the viewport — the icon default hides the trees with every label | search text hidden (the existing 900 rule moves to this breakpoint) and the search ICON BUTTON stands in (#159: it reveals the topbar copy in place; ⌘K drives it too), crumbs truncate to the LEAF with the full path in `title` (106's pattern on `h2.tplx-detail-title`) | `--gap-lg` |
| < 768 px | **off-canvas drawer**: not in the grid (`grid-template-columns: 1fr`), `position: fixed`, `z-index: var(--z-drawer)`, translated off-screen; opened by a hamburger button that appears FIRST in the topbar; closed by Escape, backdrop tap, or any boosted navigation; the drawer keeps its full 232px with a tree open (#350: the phone breakpoint wins over the desktop minimum) and the tree scrolls sideways inside it | brand tile → hamburger, crumbs (leaf only), workspace switcher as its avatar only, user menu; **nothing wraps**; the search moves INTO the drawer's head (it is hidden today — `app.css` — it must not disappear, it must move) | `--gap-md` |

Rules the table rides on, all testable:

1. **One element, one class.** The drawer is the SAME `<aside class="app-rail">` — no second nav. Its state is `rail-open` on `<html>`, set/cleared by `shell.js` beside `rail-collapsed`, exported for `node --test`, and NEVER persisted: a drawer is closed on every load.
2. **The default flip is pure CSS**, so it survives a live resize. The user's explicit choice travels the SAME key and the SAME pre-paint script: a stored `"1"` carries `rail-collapsed` (as always); a stored `"0"` carries `rail-expanded`, which opts out of the 768–1099 default.
3. **Closing is a union**: `#rail-close`, Escape (a document-level listener installed once — never per-swap), the `.app-rail-backdrop` scrim, and `htmx:afterSettle` for BOOSTED navigations only (the same one-shot `applyBoostSwap` arms for the entrance decides the close, so a background partial settling while the drawer is open cannot slam it shut).
4. **Focus is choreographed on change only**: opening moves focus to the first nav link, closing returns it to `#rail-open`, and `aria-expanded` mirrors the state — but a swap that arrives while the drawer is closed moves nothing. Body scroll is locked while open (`overflow: hidden` on `<html>` via the same class, no inline style).
5. **Motion is `transform` on the drawer and `opacity` on the backdrop**, both silenced under `prefers-reduced-motion: reduce`.
6. **Touch targets**: every control in the drawer and the topbar is at least `var(--field-height-lg)` tall below 768px; the nav links get the same minimum at that width only.

### 3.7 The data table (282, normative)

Owner, 2026-09-27: *"UI is the first thing people see."* Before 282 the app's tables were plain HTML: a card scrolled sideways (`.app-card-table`, `overflow-x: auto`), the PAGE scrolled the rows, every header scrolled away, and in the editor's result dock the viewport's scrollbar ran beside the header (the owner's report: *"a vertical scrollbar in the header"*). #282 is the house table that replaces them, ported from the owner-approved preview (the store's `notes/design/282-data-table-v2/`, *"Love this."*) — the preview is the look; this section is the contract.

**Markup** — the server renders it; nothing here builds a cell from data:

```html
<div class="dt-frame [dt-scroll | dt-fill] [dt-flush] [dt-nowrap] [dt-freeze] [dt-fit] [dt-compact] [dt-zebra]"
     [data-dt-paged]>
  <div class="dt-viewport" tabindex="-1">
    <table class="ds-table"> <thead>…</thead> <tbody>…</tbody> </table>
  </div>
</div>
```

The table stays a `.ds-table` (the vendored primitive's type, padding and hover) directly inside the viewport; a table swapped out-of-band keeps its bare `<table>` fragment root (§4.5's rule) — the frame is on the PAGE, around it (`api/keys.html`'s `keysTable`).

**Files and load order.** `static/css/data-table.css` loads from the layout head right AFTER `app.css` (it folds app.css's table layers and must win over them) and BEFORE the page sheets (`template-tree.css`, `schedules.css`) whose table tweaks build on it — [Pipeline Editor §3.4](pipeline-editor.md#34-design-system-acmedesign-tokens) lists the order. `static/js/data-table.js` loads after `shell.js` (Enter on a row reuses shell.js's click handler). Both are vendored house code: no dependency, no build step, one IIFE. Every colour, radius, gap and type size is a token; the lengths with no token (the drag floor `--dt-col-min`, the dense columns' automatic ceiling `--dt-col-max`, the handle `--dt-handle`, the viewport heights `--dt-max-h`) are custom properties on the frame, and the script reads the sheet's numbers (resolving rem and em itself — `getComputedStyle` hands a custom property back unresolved) rather than carrying its own.

**The header and the cap.** The header cells are `position: sticky; top: 0` inside the viewport, `border-collapse: separate` so their bottom line (an inset shadow) travels with them, on an opaque `--surface-inset`; body cells are opaque too (`--dt-surface`: `--surface-default`, `--surface-raised` inside a card) so rows pass UNDER the header and the frozen column. The frame's `::after` is the cap: `--dt-sbw` wide (the viewport's measured `offsetWidth − clientWidth`, 0 on overlay scrollbars — nothing is drawn) and `--dt-head-h` tall (the header's measured height), painted as the header, so the scrollbar visibly starts where the body starts. The script re-measures on a `ResizeObserver` (deferred one frame — a size change inside its callback loops it), on `document.fonts.ready`, and on every row change.

**FIXED or PAGE-FLOW — decided per table (the list below; the next lane does not re-decide):**

- **FIXED** (`dt-scroll`): the viewport has a height — `--dt-max-h`, `min(40rem, 60dvh)` by default, `dt-h-sm` 22rem (the ceiling `tplx-scroll` gave a growing list), or a page class setting the variable (`.sch-messages-scroll`, 24rem) — and the gutter is reserved (`scrollbar-gutter: stable`), so the header never jumps when rows arrive. **`dt-fill`** is FIXED by the parent: the frame is a flex item taking the rest of a column (the result dock).
- **PAGE-FLOW** (the default): no height of its own. While the table fits its frame the script sets **`dt-fits`** and the viewport's overflow becomes `clip` — not a scroll container — so the header sticks to the page's own scroller: `<main>` (§3.4, *the shell is the viewport*), under the top bar. Measured on the lane instance, not assumed: Chromium stops a `top: 0` sticky cell at the scroller's CONTENT edge, i.e. `<main>`'s `--gap-lg` (24px) below the bar, with rows showing through the band above the header; so while it fits the script sets **`--dt-flow-top`** to MINUS the scroller's top padding, and the header lands on the bar's bottom edge (60 = 60 at 1440×600 on `/admin/users`). The same measurement puts a table in a dialog on the dialog's edge. A table WIDER than its frame (a narrow window, a drag) drops `dt-fits` and keeps a bounded box (`--dt-flow-max-h`, the window below the bar) that scrolls both ways with the header sticking inside it — that is also the no-script state, so a table can never widen the document (110 §B's rule, kept). The decision compares the table's width with the FRAME's inner width, never the viewport's, so a scrollbar appearing cannot flip it back and forth.

| Template | Table | Kind | Modifiers |
|---|---|---|---|
| `partials/executions.html` (§4.8) | the executions list | FIXED | `dt-nowrap`, `data-dt-paged` (20 a page); empty = a state row |
| `partials/recent-executions.html` + `dashboard.html` (§4.2) | recent executions | FIXED `dt-h-sm` | `dt-flush` (the card's body), `dt-nowrap`; loading = three skeleton rows under the same `head` fragment; empty = a state row |
| `pipelines/editor.html` (§4.4) | the result dock | FIXED by its pane (`dt-fill`) | `dt-nowrap`, `dt-freeze`, `data-dt-paged` |
| `partials/execution-result.html` (§4.9) | the execution's result page | FIXED | `dt-nowrap`, `dt-freeze`, `data-dt-paged` |
| `partials/datasource-facts.html` (§4.5b) | learned facts (dialog and lake detail) | FIXED | prose wraps |
| `schedules/run.html` (§4.20) | a run's Messages | FIXED (`.sch-messages-scroll`, 24rem) | — |
| `schedules/detail.html` (§4.20) | next five occurrences; parameters | FIXED `dt-h-sm` | `dt-fit` |
| `pipelines/editor.html` (§4.4, #349) | the Parameters tab's declared schema | FIXED `dt-h-sm` | `dt-fit` (client-rendered rows; the observer upgrades) |
| `partials/pipeline-versions.html` (§4.3b/§4.4, #349) | the version history | FIXED `dt-h-sm` | `dt-fit`; the ⋯ verb menus ride the rows; `data-version-row` is the client's viewed-mark hook |
| `partials/pipeline-runs.html` (§4.3b/§4.4, #349) | the pipeline's runs | FIXED `dt-h-sm` | rows carry `data-href` (the keyboard contract); visibility unchanged |
| `partials/pipeline-usage.html` (§4.3b/§4.4, #349) | endpoints / parents / dashboards / schedules | PAGE-FLOW `dt-fit` | one table per half; the parent links name the canonical workspace |
| `admin/users.html` (§4.12) | users | PAGE-FLOW | — |
| `api/keys.html` (§4.19) | keys | PAGE-FLOW | `dt-flush`; dates sort by `data-sort-value` |
| `api/console.html` (§4.18) | published endpoints | PAGE-FLOW | `dt-flush` |
| `partials/datasources.html` (§4.5) | datasources | PAGE-FLOW | `dt-flush`, `dt-freeze`, `data-dt-paged` (25 a page) |
| `partials/datasource-grants.html` (§4.5a) | grants (dialog) | PAGE-FLOW | — |
| `workspaces/index.html` (§4.13) | your workspaces; members | PAGE-FLOW | — |
| `promotion/index.html` (§4.17) | the plan (promoter's form; reader's copy) | PAGE-FLOW | — |
| `executions/detail.html` (§4.9) | the execution family | PAGE-FLOW | — |
| `partials/execution-node-stats.html` (§4.9) | node stats | PAGE-FLOW | — |
| `partials/execution-node-operations.html` (§4.9) | node operations | PAGE-FLOW | `dt-freeze` (nine columns) |
| `templates/editor.html` (§4.7) | imports | PAGE-FLOW | — |

Dense data is FIXED; a short list a person reads top to bottom is PAGE-FLOW. `templates/endpoints/**` is not in the sweep (lane 274's tree at the time); a table not yet framed keeps the pre-282 box (its own border, `display: block; overflow-x: auto`) from the fallback rule in `data-table.css`, and the markup audit names it.

**Modifiers.** `dt-flush` — the card's own border closes the box (a separator line meets the card head); `dt-nowrap` — one line per row, an ellipsis where a column is narrower than its value; `dt-freeze` — the first column is sticky at the left and casts a soft shadow once the viewport has scrolled sideways (`is-scrolled-x`); `dt-fit` — the frame hugs its table ("never the full row", was `.tplx-fit-table`); `dt-compact` and `dt-zebra` — the preview's density and zebra switches, available, used by no screen yet.

**Sort** (client-side, over the rendered rows). Every header cell with text becomes a `<button class="dt-sort">` (the label and the sprite's `chevron-right`, turned) unless it carries `data-sort="off"` (the Actions and Send columns). A click walks **none → ascending → descending → none**, sets `aria-sort` on that cell (`none` on the others) and MOVES the rendered `<tr>` nodes; it never rebuilds a row. The type is the header's `data-type` (`number`, `date`, `text`), else `number` for a `.num` column, else text; a cell sorts by its `data-sort-value` when the server gave one (a relative age's instant — the keys page), else a `<time datetime>` inside it, else the chosen option of a `<select>` in it (a member's role), else its text. **Nulls sort last in both directions** (an empty cell or a lone dash); equal keys keep their natural order. A paged list sorts the page it shows, and the button's title says so (`data-dt-paged` → "Sort this page by …"); **a page-sized replacement clears the client sort** (288): when every data row is new to the table — the htmx pagers swapping their frame, or any keyed-by-content re-render — the new page shows the server's order and every `aria-sort` returns to `none`, so the state the label claims is always the state the rows are in. A row edit and an append keep the sort. **A held sort names itself** (288): while a column is sorted, its button's title becomes "Sorting this page by X — click for highest first / — click to clear" beside the `aria-sort`, which matters on the one producer whose paging keeps the sort (the dock's cursor paging re-renders its rows in place, index-keyed, so the held sort persists by element identity — never silently). State rows (a single cell spanning the row, `.dt-state-row`, `.dt-skeleton-row`) never move.

**Resize.** Every header cell carries a resize handle at its right edge (`.dt-resizer`, `--dt-handle` wide; `data-resize="off"` opts out). A drag freezes the rendered widths onto a `<colgroup>` the script owns and switches the table to `table-layout: fixed` with its width the sum of its columns, so a drag changes ONE column's width and no other (the table grows or shrinks); `--dt-col-min` is the floor; a double-click gives the column back its width from before the drags. A `dt-nowrap` table is frozen at load (each column at most `--dt-col-max`, the slack shared so it still fills its viewport) and re-fitted when its frame changes width until a person has dragged; a wrapping table stays in the browser's own layout until the first drag. Widths are not remembered across page loads.

**Keyboard rows.** A row that carries `data-href` (the executions tables, `app-row-clickable`) is focusable; **Enter** dispatches a click on it, so shell.js's one delegated handler navigates (§3.4's 188 note — the script never reads or writes `data-href`); **↑/↓** move between such rows. **The viewport is keyboard-scrollable** (288): the markup renders `tabindex="-1"` (programmatically focusable only — the pre-upgrade, no-script state), and the enhancer flips it to **`tabindex="0"`** so Tab reaches a FIXED viewport and its arrows scroll it, the sheet's `.dt-viewport:focus-visible` ring (2px `--border-focus`, inside the box) marking the focus. **Only a viewport that actually scrolls gets the tab stop** (301): the enhancer checks the computed overflow — a fitting page-flow frame is `overflow: clip`, not a scroll container, and its inert `-1` stays — and content that overflows the container, re-checked in `measure()` on every state change, so the tab order holds only the viewports the arrows answer.

**States are rows of the same table**, so the header never disappears with the rows: the empty state is a `.dt-state-row` (one cell across the row holding `.dt-state` — the title in `<b>`, the follow-up beside it); the loading state is `.dt-skeleton-row`s of `.dt-skel` bars (a shimmer, off under `prefers-reduced-motion`), server-rendered — the dashboard's recent-executions placeholder. Both are Thymeleaf, never script-built. The columns' LABELS never change between states; their widths follow the rows (auto layout), so a skeleton's columns are not the loaded rows' widths.

**When tables arrive.** At load, on `htmx:afterSwap` / `htmx:oobAfterSwap` for the swapped subtree, and — because three of the producers are not htmx (Alpine's dock rows, the schedules page's cloned `<template>`s, a htmx swap of a `tbody` alone) — through one document-level `MutationObserver` that upgrades a new table and re-runs the (idempotent) ensure steps when a table's rows change: a header a renderer rewrote gets its button back, a new row is focusable, an active sort is re-applied (a replaced page clears it, the sort rule above). The observer pays **one discovery query per top-level root, not one per added element** (288 #2): a batch's nodes with no ancestor in the batch carry the whole subtree, so a swap of a large canvas costs a constant number of subtree queries, not one per node. A table is upgraded once (`data-dt-ready` and the instance map). **The history snapshot carries no CSSOM styles:** htmx caches the page (the history element is `#app-main` since #358 — the layout's `hx-history-elt` scopes the cache to the workspace region, so footer scripts are never cached at all) as markup before a boosted swap, and a width serialised into a `style` attribute would come back on Back as an inline style the CSP refuses. The strip runs from shell.js's ONE `htmx:beforeHistorySave` listener (287): scripts that write such styles register a cleanup on `window.__dpHistoryStyleCleanups` — data-table's (frame, table, cols), the version menu's placement (which also closes open menus: the snapshot, like the shell's own skeletons, carries no transient chrome), and the pipeline workspace runtime's (it disarms its root with `x-ignore`, empties the generated canvas DOM and strips the styles its libraries wrote) — and each strips only its own write set; the restored table is upgraded again by the observer below. The hand-rolled modals need no cleanup: they show and hide through the `u-backdrop-hidden` CLASS, never an inline `display`, so there is nothing to serialise.

**CSP** (`script-src 'self'`, `style-src 'self'`, no nonce — `SecurityHeaders`): no template carries a `style` attribute (`InlineScriptAuditTest`, `InlineWidthAuditTest`), and the script writes no `style` attribute and parses no markup — widths and the measured variables go through the CSSOM (`el.style.width`, `style.setProperty`), which the policy permits. The preview's `<col style>` and skeleton widths were prototype shortcuts; the product sets widths through the CSSOM and the skeleton's bar lengths by position in CSS.

**The legacy table classes** — `.ds-table`'s boxed block (app.css, 076 §D / 079 §E): folded into `data-table.css` (the frame draws the box; an unframed table keeps it as the fallback); `.app-card-table`: reduced to ONE rule, a card whose body is a flush data table (`padding: 0; overflow: clip` — `clip`, never a scroll container, or it would take a page-flow header's scroller away from `<main>`); its identifier-breaking rules (098 §A) moved to `data-table.css` for every wrapping frame, its header-wrap rule retired; `.u-scroll-x` and its `:has(> .ds-table)` frame rule: retired (their one user, the execution result, is a frame); `.tplx-fit-table` (template-tree.css): retired, now `dt-fit`; `.pe-result-table-container` (pipeline-editor.css): reduced to the dock's inset and flex placement — the frame draws the box and its viewport scrolls, 032's rule (the box belongs to what does not scroll) kept; `.tplx-scroll` stays for the one non-table list that wears it (the template detail pane). `.facts-table` and `.mini-table` are the MARKETING SITE's (`static/site/css/site.css`, `templates/site/**`), which loads neither `app.css` nor this layout — untouched.

**Guards.** `DataTableCssTokenTest` (tokens only; the load order; the folded layers gone from their old sheets; every app template's `<table>` directly inside a `.dt-viewport` in a `.dt-frame`), `data-table.test.mjs` on `node --test` (the type, the cell value, the comparator with nulls last, the stable sort, the cycle, the width clamp and fill, the length resolution), `AppCssTokenAuditTest` (the card clips, the viewport scrolls), and `DataTableBrowserTest` in a real browser WITH scrollbars (Playwright hides them by default, which would prove the cap only at 0px): the executions list's header holds at `scrollTop = 200`, `--dt-sbw` equals the measured scrollbar and the cap is that wide and the header's height tall, a header click reorders the page and sets `aria-sort`, a drag moves one column; the dock's header holds and its frozen column sits at the viewport's left at `scrollLeft = 300`; a page-flow header sticks under the top bar; zero CSP violations; screenshots light and dark at 1440 / 1024 / 400.

**Not in scope:** a grid library (the CSP, above), virtual scrolling (every list is server-paged), server-side sort (a paged list sorts its page, and says so), remembered column widths, sorting across pages.

---

## 4. Screen Catalog

**On the "Auth required" column:** it restates, per screen, the permission the REST operations that screen drives declare. The **authoritative** definition is the permission catalog in [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative) — if this column and that matrix ever disagree, the matrix wins and this doc is wrong. There are no scopes since #215 ([Auth §7.5](auth.md#75-key-roles)): a screen renders by the session's ROLE. Actions the current principal's role does not hold are **not rendered** (not merely disabled), and the server re-checks on every partial request — the UI is a convenience, never the enforcement point.

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

Failure states are inline banners in the `?error=` idiom: `expired`, `domain_not_allowed`, `oidc_error` (OIDC), `identity_mismatch` (#187 — the email is already linked to a different sign-in identity; nothing was changed, and the banner says to ask an administrator for a reset, [Auth §4.2](auth.md#42-user-provisioning)); `credentials` (unknown email or wrong password — deliberately identical, [Auth §5A.5](auth.md#5a5-enumeration-resistance-and-the-password-policy)), `locked` (per-account lockout), `inactive` (deactivated account, either method — and, since 180, where a live session lands when its account is deactivated mid-life: the JWT filter clears the cookie and the entry point sends the next page navigation here, [Auth §11A.3](auth.md#11a3-deactivation)).

### 4.2 Dashboard

| Attribute | Value |
|---|---|
| URL | `GET /dashboard` |
| Auth required | Yes (`read`); the Recent executions panel follows `execution.read` (D11, 177) — a promoter's dashboard does not draw it — and the stats tiles count the runs `visibleTo` admits, decided in SQL: every run with `execution.read_all`, own plus the workspace's SCHEDULED runs (#250 — R3) with `execution.read`, and for a promoter (who holds neither) their OWN runs (#293 — the answer the explorers' Runs tabs and the search palette already gave; the tiles used to read zero). The **Pipelines** tile counts the caller's VIEW (178): for a promoter, the released-and-newer set the lens admits ([Auth §11A.1](auth.md#11a1-the-404-rule)), the same number the rail badge and the explorer show |
| Purpose | Landing page — overview of recent activity |
| Design primitives | `.ds-card`, `.ds-badge`, the data table (§3.7) |
| JS | None |
| htmx | Yes — refresh sections independently (`hx-get="/partials/recent-executions"`, rendered only for `canReadExecutions`) |

Content:
- **Recent executions** (last 10): pipeline **display name** (the machine folder-path name on hover; T114 — the truncated pipeline UUID is gone everywhere), status badge, duration, timestamp. Clickable → execution detail.
- **My pipelines** (top 5 by updated_at): name, description, version. Clickable → pipeline editor.
- **Quick stats**: total pipelines, total executions today, success rate.

### 4.3 Pipelines — the catalog page and the sidebar tree (#350)

| Attribute | Value |
|---|---|
| URL | `GET /pipelines` (`?q=` — the deep link every "find this pipeline" link uses: the search palette's "All pipelines matching", datasource delete, the node-SQL and Details child links, the schedules detail, the workspace's phone note) |
| Auth required | Yes (`read`) |
| Purpose | The **catalog**: one flat, server-paged list of full paths — every pipeline the caller may read (newest first, the service's own page) or the matches of `q` — each row a link into the pipeline's workspace (§4.4). The folder TREE is the sidebar's (§3.4) |
| Design primitives | `.ds-input`, `.ds-badge`, `.ds-button`, `.ds-empty`; the rows are `template-tree.css`'s `.tpl-results` / `a.tpl-result` |
| JS | none of its own — the sidebar's `tree/sidebar.mjs` owns **Browse folders** (`data-nav-tree-reveal`), which opens the sidebar's Pipelines tree (the phone drawer first; an icon-collapsed rail is expanded — the reader's explicit choice) and puts focus in its search |
| htmx | the search input (`#pipeline-filter-q`, `input changed delay:300ms, search`, `hx-sync="this:replace"`) and the shared §5 pager re-fetch ONLY the list into its stable root `#pipeline-list-wrapper` (`GET /partials/pipelines`, `outerHTML`); the sidebar uses REST tree/search (§3.4) |

**Owner ruling 2026-10-02 (#350):** with the tree in the sidebar, the page keeps the catalog the
workspace spec retains (§3.2: "#350 removes the redundant tree panel from the landing page") — a
flat list, not a second tree and not a detail pane: a pipeline is read, run and managed in ONE
place, its workspace. Since 067 pipeline names are **folder paths**
([Template Hierarchy §14](template-hierarchy-design.md)); the folders are browsed in the sidebar.

- **Separate catalog and tree transport.** `GET /partials/pipelines` (`pipeline.read`, the lens)
  serves the catalog's flat list. Legacy `scope=nav` and prefix fragments remain compatible;
  the sidebar uses the REST tree/search routes (§3.4, rest-api §24), with independent bounded
  continuation for complete immediate levels and name-search matches.
- **Every row is a link** to the canonical workspace `/pipelines/{id}` — no version, the
  current-first rule (§4.4) — through the persistent main-content navigation host.
- **A new pipeline appears as `v1 draft`** (D55, 099): every row's version badge names the
  **working** version — the draft's number when one exists, the released one otherwise — beside
  the "pending release" badge. A pre-077 flat name (no folder) is an ordinary catalog row; the
  sidebar tree's root lists folders only.
- **No verbs.** The catalog renders no lifecycle verb: Release, Discard, Restore, Switch and the
  purges are the workspace's (§4.4, §4.3d). The authoring note says how pipelines arrive (agents
  over the MCP server, D1); the pre-067 "Create Pipeline" stub (T108) and the "browser authoring
  is on the roadmap" sentence are gone — definitions are AI-native.
- **Empty and no-match are different answers:** an empty workspace (no `q`) says how pipelines
  arrive; a search that matches nothing offers Clear search; a lensed caller whose target cannot
  be read gets the promotion page's sentence (178).
- There is deliberately **no datasource filter**: the one shipped before v1.12 was labelled
  datasources but populated from `${dialects}`, the controller never had the parameter, and
  `PipelineRecord` carries no datasource field (deferred).
- **What moved, and where (spec capability inventory):** folder browse, search, counts, paging,
  loading/empty/refusal/retry → the sidebar tree (§3.4); the selected pipeline's header, reading
  and acting columns (§4.3b) → the workspace's header and tabs (§4.4, #349); the lifecycle verbs
  → the workspace's header and Versions tab (§4.3d — #395 made Switch/Discard/Restore work
  there); the tree-badge refresh → an explicit Refresh or affected-parent invalidation.
- Guards: `PipelineSidebarTreeBrowserTest`, `PipelineSidebarTreeStateBrowserTest`,
  `PipelinePartialControllerTest`, `PipelineUiControllerTest`, `PipelineExplorerRenderTest`,
  `nav-tree.test.mjs`, `template-explorer.test.mjs`.

#### 4.3a The divider handle — the pane explorers (104)

> **Since #350 the pipelines page, and since #398 the templates page, have no pane, divider or
> drawer** — §4.3a and §4.3c describe the pane the SCHEDULES page (§20) still draws, the one page
> explorer left; the lake-table tree (§4.5) still inherits the pane class. The shared
> `dp.pane.explorer-tree` key keeps its name.


The tree pane's width was a stylesheet's guess, and it was re-guessed three times: `clamp(260px, 30vw, 480px)` left the tree 14% of the owner's 3491px window, `26vw` uncapped gave it 908px for a column of short names, and the divider itself then had to be drawn. The pane did carry the browser's native `resize: horizontal` — at the pane's **bottom-right corner**, which is where nobody looks for a divider, so every round was spent editing CSS instead.

The native resizer is gone. In its place, the same `static/js/splitter.js` the editor's dock uses (§4.4), bound to a `role="separator"` handle overlaying `.tplx-detail`'s left border — the pane boundary the eye already reads as the divider. Since 141 the grip is a **visible pill at rest** (`3px × 40px`, `--border-default`; brand on hover/focus/drag) and every handle carries `Drag to resize · double-click to reset` as both its tooltip and its `aria-description` — the grip was transparent at rest until then, and a resize nobody can see is a resize nobody uses (the owner never found it):

| | |
|---|---|
| Pointer | Drag the handle. `setPointerCapture`, so the drag survives leaving the 12px strip; `touch-action: none`, so a touch drag is a resize and not a scroll |
| Keyboard | Focus it (it is in the tab order) and use ←/→: ±16px, Shift ±64px; Home = floor, End = ceiling. `aria-valuemin`/`max`/`now` move with it |
| Reset | Double-click — the remembered width is forgotten and the pane falls back to `clamp(260px, 22vw, 40rem)`, which is the stylesheet's own default and not a number JS repeats |
| Bounds | Floor 260px, ceiling 40vw. A window that shrinks past a remembered width pulls it back in |
| Memory | `localStorage` key `dp.pane.explorer-tree`, **shared by the pane explorers** (Schedules and the lake-table tree) — the width follows the user between them |

The size lands as ONE CSS custom property, `--tplx-tree-w`, written on `<html>` (not on the pane: `#app-main` is the boosted-swap target, so a property on the pane would die with every navigation). `template-tree.css` derives both the pane's `width` and the handle's `x` from it through a single `--tplx-tree-size`, with the default as the `var()` fallback — which is what makes "reset" mean "remove the property". The module is a parser-blocking script above the markup, so a remembered width is the width of the first painted frame; `ExplorerPaneGeometryBrowserTest` measures the default at four viewport widths, the drag, the reload and the reset, and holds the CLS budget.

`InlineWidthAuditTest` is unaffected and stays green: it bans a static `style="…"` attribute in a template, and this is a script setting one custom property on the root — the shape its KDoc now names as the allowed one.

The datasource lake-table tree (§4.5) uses the same pane class and so inherits the remembered width, but has no detail pane beside it and therefore no handle.

#### 4.3b The detail pane — reading and acting (106, owner-approved mock 2026-09-08; REMOVED by #401)

> **#401 removed the pipeline detail pane**: the fragment (`partials/pipeline-detail.html`), its
> route (`GET /partials/pipelines/detail`, off the `pipeline.read` §7.6 row in the same commit)
> and the explorer legs of the lifecycle dialogs — no page has rendered the pane since #350 (the
> catalog's rows open the workspace, §4.4, whose header and tabs carry everything below, #349's
> capability map). `PipelineBrowseModel.fillDetail` and its region fills left with it. The text
> below is the record of what the pane was; the runs and usage fragments it lazy-loaded live on
> as the workspace's tabs.


The pane used to be a stack of four tables (badges, Settings, Parameters, Versions) in one
column. The owner's mock, layout B, splits it into what a reader does and what an operator
does, and both explorers render the same shape.

**1 — Header.** The folder path is an EYEBROW (`.tplx-detail-path`, mono, muted) and the leaf
name is the `h2`; the full path still rides on `title`. The actions sat on the right: **Open**
(primary, the canonical read workspace — #348) plus the lifecycle verbs 101 shipped, opened as
§4.3d dialogs since 102 —
`Release v<n>…` when a draft exists, **Discard v<cur>…** when the current version is released,
**Purge pipeline…** only in the `{D}` shape, **Switch…** at ≥ 2 live eligible versions, at most
one destructive among them. Each hx-get its dialog partial into `#px-dialog` — until #401, which
removed the pane and with it these headers and their target (the dialogs now open only from the
workspace's Versions tab, §4.4, and answer `HX-Redirect` only); the POST went
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

*Checks* (140): when the working version's body declares `checks[]`, Overview gains a Checks
sub-section — the count in the header note, a **Run checks** button (the execute floor; hidden
from a credential that cannot execute), and the list itself, lazy-loaded on paint
(`hx-trigger="load"`) from the read-only partial:

| Fragment | Route | Model |
|---|---|---|
| `partials/pipeline-checks` | `GET /partials/pipelines/{id}/versions/{v}/checks` (read, session) | the version's check definitions, each with its latest `pipeline_check_runs` row — "not run" when never run |
| same, refreshed | `POST /partials/pipelines/{id}/versions/{v}/checks/run` (execute floor, session) | a FRESH run on the shared `PipelineCheckRunner`, `via = ui`, the session principal, the request's correlation id |

One row per check: the verdict chip in the run-status idiom (`app-chip-ok` pass /
`app-chip-bad` fail / `app-chip-warn` error), the name, its datasource, the expectation as one
compact clause (`= 74.62 ± 0.01`, `74 ≤ x ≤ 75`, `6 rows`), the SERVER's observed value (an
error names its reason instead — `observed` exists only because a run produced it), and
when · via. Both routes are session-only (`auth.session.required` for a key — the REST twins
of rest-api §5.16 are the key surface); the editor's Details pane (§4.4) shows the same count
read off the loaded body JSON and lazy-loads the same partial for the working version.

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

Versions are the **house table** (§3.7; #349, spec §4.4): `partials/pipeline-versions.html` is ONE
fragment — a FIXED `dt-h-sm` table, `dt-fit`, `tr[data-version-row]` rows — rendered here in the
explorer's detail pane and in the workspace's Versions tab alike (`PipelineExplorerRenderTest` pins
`<table class="ds-table">` and the absence of the old compact rows). The readable-width lesson
stands: the 106 round's first 1440px screenshot showed a version row's meta cell set one character
per line beside three ghost buttons in an `auto` track, so `ExplorerDetailBrowserTest` asserts the
widest cell's READABLE WIDTH and line count at 1440/1920/2560 rather than its presence. Each row states version · status · created · who · runs, marks the sticky
pointer as `current` (D60 — which is not "the latest released"), and carries a per-row overflow
menu (⋯) with exactly the verbs §3.5 allows that row's status — DRAFT → Release, Purge;
RELEASED → Discard, Switch-to (when not current); DISCARDED → Restore; a verb the table refuses
is absent, not disabled (§4.3d). The open menu is a `popover="manual"` in the browser's top
layer, placed under (or, out of room, above) its ⋯ by `lifecycle-dialog.js` — the tab panel
scrolls, and a menu positioned inside it was clipped whenever the list was shorter than the
menu (owner, 2026-09-12: one version, no menu). It closes on outside click, Escape, scroll,
resize and when a dialog opens.

**Runs** is this pipeline's last 20 executions, so reading a pipeline no longer means leaving for
§4.8 and filtering it back down. Its visibility is §4.8's (#275): an admin sees the
workspace's runs, a viewer or author her OWN runs plus every SCHEDULED run (R3, the same
`findVisible` read), and a promoter — who reaches this `pipeline.read` pane without
`execution.read` — her own only. The template workspace's Runs tab (§4.7) and the search palette's
executions group read the same way. A second surface over the same rows is not a wider
one. **Usage** is the published endpoints serving the pipeline and the live pipeline versions
pinning it, and it runs the **same query 101's discard refusal runs**
(`PipelineRepository.findLiveParentsPinningVersion`, the evidence behind
`pipeline.version.pinned`), so what the user reads before pressing Discard is what the server
will decide on — **for a caller whose lens narrows (a promoter), through both halves of the lens (#340): the parent pipeline's
NAME must be admitted AND only RELEASED parent versions are listed, so a draft's number never reaches her (the query returns
DRAFT parent versions too, and the badge counts only what the tab lists)**. **Dashboards joined the list with #320:** a heading lists the live dashboard versions whose
sources pin a version of this pipeline (`referencing_dashboards` — asked per version through the port
`PipelineService.refuseIfPinned` asks), through the dashboard lens, and each is refusal evidence, so it counts in the
badge. Schedules joined the list with #259: a third heading lists the live schedules
whose `target_ref` names the pipeline — name, a link to `/schedules?id=<id>` and the
enabled/paused/blocked state — read through the scheduler's lensed by-target read
(`ScheduleService.listByTarget`, over the indexed `target_ref` column), so a hidden target's
schedule is absent exactly as §20.1 hides it. A schedule is not refusal evidence — the discard
succeeds and the schedule then blocks (`pointer_null` / `target_not_found`) at its next
occurrence, which is the consequence the heading exists to show — so the tab badge counts
refusal evidence only, and the empty state speaks only when none of the three lists has a row.

`PipelineBrowseModel.fillDetail` supplied every region in ONE call (the method left with the
pane, #401), with the lifecycle flags
computed beside the query that produced the rows — a button is rendered because the server
would accept it, never because a template read a status string. Two web-side joins sit beside
`PipelineNames` for its reason (`dag` and `pipeline-contract` own the stamped rows and neither
may reach into `auth`): `ActorNames` (who made a version — every stamp is a bare `users.id`) and
`PipelineRunStats` (one `GROUP BY`, not one `COUNT` per row).

#### 4.3c Responsive — the pane explorers (106 §B)

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

#### 4.3d Lifecycle verbs — the two workspaces (102; the explorers' legs retired by #401 and #398)

Every version-lifecycle verb 101 shipped ([Versioning §3.5](versioning.md#35-the-lifecycle-table))
is reachable from a **confirm dialog**, one partial per verb, opened into a single per-screen
container — `#tx-dialog` (the template workspace, §4.7 — since #398 the template dialogs' ONLY
container; the explorer's pane and the editor page it replaced are gone), `#pe-dialog` (the
pipeline workspace, §4.4 — since #401 the pipeline dialogs' ONLY container; the explorer's
`#px-dialog` left with its pane) — emptied on close, `Escape` closed, focus
landing on the first control, the destructive button `ds-button-danger` and last in tab order.
The exemplar is §4.5's delete dialog (094 §B): the question is asked BEFORE the button exists,
the refused branch renders NO button, and the POST re-runs the guard because the screen is
never the authority. A dialog route is session-only (`LifecycleVerbs.requireSession`) — an API
key is refused `auth.session.required` exactly as the REST verbs refuse it; the dialogs call the
same services 101 wired, never the REST controllers over HTTP and never a second copy of a guard.

| Verb | Dialog (`GET`, into the container) | What it shows before its one button | Shapes that render it |
|---|---|---|---|
| Release | `…/lifecycle/release` | the draft's number, who last wrote it and when, every template pin with its status — a DISCARDED or MISSING pin is refused colour, says "release the template first", and the button is NOT rendered; else "Releasing makes v`<n>` the current version and locks it." **A DRAFT pin (142)** renders in a consent group instead: "Also release these `<N>` draft templates with the pipeline", a checkbox `releasePinnedTemplates` CHECKED by default, one row per pinned version naming the template, the version and — from the used-by service — "also pinned by `<K>` other draft pipeline(s)" (the promoter is releasing a shared object). Checked, Release is enabled and the POST cascades (versioning §5.3); unchecked, `lifecycle-dialog.js`'s consent arm withholds the submit and the rows fall back to the refused wording ("release the template first" — pure CSS `:has()`, no script), so the order rule stays visible. The reload's flash then names what was released: "Released. Also released: `<template@version>`, …" — SERVER-DERIVED (#407): the release POST holds the cascade's own list once in the actor's session (`ReleaseFlash`), and the workspace GET the `HX-Redirect` lands consumes it (a crafted `?ok=`, another page, another actor or a minute gone by renders only "The draft is now the current version and is locked, and the draft templates it pinned were released with it."). When the draft declares `checks[]` (140): the dialog RUNS them as it opens (`POST …/checks/run?footer=release` on `hx-trigger="load"`), the Release submit starts DISABLED, and the run's own fragment decides the footer out-of-band — all pass enables Release; anything short of PASS keeps it withheld; both footer submits are consent-gated too and offers the **Override** disclosure: a required ≥ 10-char `overrideChecksReason` textarea (armed client-side at 10 trimmed chars by `lifecycle-dialog.js`'s min-chars arm — the dialogs' own layer, since Alpine is the editor's, not the explorers'), copy naming the overridden check ids and that the reason is recorded on the release's audit event, and a "Release anyway" submit riding the SAME release POST. A draft with no checks says "No checks on this version" and behaves exactly as before. **A pin that reads `needs_review` (7e)** — the pinned template version cites a retired learned fact ([Templates §3.4](templates.md#34-implements-and-drift)) — adds ONE warning row per such pin above the confirm (`[data-release-needs-review]`, a `needs review` warning badge, the pin, and "cites a retired fact: `<fact id>` — superseded by `<fact id>`", or `(retired: <reason>)` for a plain retirement), plus "Releasing is not blocked. Re-cite the successor with templates_update to clear the mark." A WARNING, never a refusal: the confirm is untouched in every footer shape, and the POST's own `warnings` (REST §5.10) come from the same port read. All text through `th:text`. | any with a DRAFT |
| Purge draft | `…/lifecycle/purge?version=v` | the version and its execution count; exact parent pipeline names, versions and nodes and dashboard names, versions and statuses appear when pinned, with NO destructive form (#462). A sole draft uses stored any-version dashboard pins, otherwise live/exact; parents remain live/exact. Unpinned, the button reads "Purge v`<n>` and `<k>` runs" and needs the typed confirm `v<n>`; POST rechecks pins | any with a DRAFT |
| Discard | `…/lifecycle/discard?version=v` | whether the version is current (then the §3.4 fallback: "v`<m>` becomes current" or "nothing eligible remains — the pipeline will have no current version and its endpoints will answer 503"); parents that pin it (listed, no button) **and, since #320, the dashboards whose sources pin it (`referencing_dashboards`, listed, no button)**; **the schedules that run the pipeline (273)** — from the SAME by-target read the Usage tab makes, lensed for the caller (a promoter's view is R3's; the route itself stays `pipeline.version.manage`) — one row per schedule naming it, its condition (`enabled`/`paused`/`blocked`, the Usage tab's vocabulary) and its stored next occurrence, under "Schedules that run this pipeline — discarding does not pause them: at its next run each blocks (the pointer is gone) until repointed or deleted" (the §5.2 `pointer_null` shape, said before the button); "Discard is reversible — Restore brings it back." | RELEASED rows |
| Restore | `…/lifecycle/restore?version=v` | whether restoring moves the pointer (v > current, or current is NULL); "Restoring makes v`<n>` a live release again." | DISCARDED rows |
| Purge entity | `…/lifecycle/purge-entity` | only in the `{D}` shape (else the `last_release` branch, no button); the exclusive draft templates the service offers, with a checkbox "also purge these `<k>` templates (they are pinned by nothing else)"; **since #335, beneath the offer, the draft templates the offer KEEPS** — a parameter set or a visualization also pins them (`ExclusiveDraftTemplates.keptIds`, the list the REST response reports as `kept_draft_templates`) — each with the sets and visualizations that hold it, under "`<k>` draft template(s) stay — something else pins them" (nothing kept renders nothing; unlensed, the verb is author-or-above); typed confirm is the entity's NAME | `{D}` |
| Switch | `…/lifecycle/switch` | the live versions as radio rows (RELEASED always; DRAFT under development posture), the current one marked, discarded ones disabled with "restore first"; "endpoints published on this pipeline serve v`<n>` after this." Not authoring-gated (§3.1: the receiver's rollback lever) | ≥ 2 live eligible versions |

**The Release form posts the hash the dialog read (#416).** All four families' Release dialogs
(pipelines, templates, dashboards, visualizations) carry ONE hidden input in the submitted form —
`bodyHash` (pipelines, templates, dashboards) or `body_hash` (visualizations, beside
`release_pinned_templates`) — filled from the SAME draft read that supplies the dialog's version and
last-writer facts, placed before the checks footer on the pipeline dialog so the run's out-of-band
Release / Override submit posts it too. The POST binds it as REQUIRED (a missing value is a 400 at
binding, before any read) and releases AT it: the draft is read only to refuse the no-draft case, and
its CURRENT hash is never the one released. A draft that changed after the dialog opened is therefore
`pipeline.version.conflict` / `template.version.conflict` / `dashboard.version.conflict` /
`visualization.version.conflict` (409, Shape C), and the visualization evidence gate, which judges the
CURRENT body, still answers first when the new draft has no green run. A refused no-draft dialog renders
no form and so no hash. The hash is an optimistic-concurrency token already present in the version
reads, not a credential. `ReleaseDialogHashRenderTest` pins the rendered field per family;
`LifecycleDialogBrowserTest` drives a pipeline dialog stale and sees the 409 with nothing released.

The templates twin is addressed by NAME in query/body ([§9.6](template-hierarchy-design.md#96-addressing-the-name-never-travels-in-a-url-path-segment-normative-measured)):
`GET /partials/templates/lifecycle/{verb}?name=&version=`. It has every dialog above except
Switch — templates are pinned by version; there is no served pointer to switch. The pipeline
purge-draft POST rides the **versioned** purge (`DELETE /api/v1/pipelines/{id}/versions/{v}`
beneath): the dialog names an explicit version, so there is no two-writer hash protocol to
honour, and the typed confirm carries the same fact the button states.

**Success is `HX-Redirect`; refusal is Shape C everywhere.** No Shape A leg is left for either
family: #401 removed the pipelines explorer's and #398 the templates explorer's, each with the
pane it re-rendered (the `toast-oob` splice, the `lifecycle-changed` payload and the tree badge
rewrite it drove, the `from` ternaries). A POST that lands answers `HX-Redirect` onto the
workspace's Versions tab (`/pipelines/{id}?tab=versions&ok=…`, `/templates/{name}?tab=versions&ok=…`)
with the layout's flash toast, or — for the verb that removes the whole entity — onto the
family's catalog (`/templates?ok=template_purged`). The page's draft state (`PEDraft`, the
version chips) is embedded across the document, so the honest refresh is the document itself.
Every refusal — `pinned` with its pinners, `last_release`, `not_released`, `not_discarded`,
`not_eligible`, `not_draft`, `version.conflict`, `authoring.disabled`, `template.in_use` —
arrives as §5.1 Shape C carrying the real 4xx, never a native dialog.
**#395 (with #350), then #401, then #398: on both workspaces that is true of EVERY verb.** Release
and the purges always answered the redirect; Switch, Discard and Restore (the Versions tab's row
verbs) used to post to the explorer's `#pipeline-detail` from the workspace too — an element the
workspace does not have, so htmx refused the swap target and no request was ever sent. Their
dialogs are `from`-aware (#395: `from=editor`: no target, `hx-swap="none"`, a hidden `from`),
and their POSTs answer `HX-Redirect` onto
`/pipelines/{id}?tab=versions&ok=discarded|restored|switched` with the layout's flash
(`Version discarded` / `Version restored` / `Current version switched`); the toast states what
happened and the reloaded Versions tab shows the pointer the service left. The templates'
dialogs carry the same `from` (#398: the workspace sends `editor`, and an absent value resolves
there too — the dialogs' one page is the workspace) and add no switch: templates are pinned by
version (§4.7).

**One destructive verb per entity per view** (owner rule). The detail header — and since #395 the
pipeline WORKSPACE's header, now that the explorer pane is gone — shows at most one of Purge
pipeline / Discard / Purge draft — `{D}` gets Purge pipeline beside Release (on the workspace: the
entity dialog with its exclusive draft templates and #335's kept list, in place of Purge draft); a released
current gets Discard; a draft with no header-destructive gets Purge draft; everything else
lives on the row it names. The version rows (§4.3b's compact list) carry their verbs in a
per-row overflow menu (⋯): DRAFT → Release, Purge; RELEASED → Discard, Switch-to (when not
current); DISCARDED → Restore. A verb the §3.5 table refuses for that row's shape is NOT
rendered — absent, not disabled — so the table and the screen cannot disagree; the POST still
re-runs the guard.

**No native `window.confirm` / `window.alert` anywhere in the lifecycle UI** — a static test
pins it (the four editor sites and the explorers' verb wiring are grep'd; the `hx-confirm` on
`api/console.html` is htmx's own attribute, not a native call, and is excluded by path).

#### 4.3e Role visibility — every verb, and the role boolean that renders it (114, normative)

**The role decides what is rendered; the server decides what is allowed** (RBAC design §7).
Round 1 (112) made the server right: every verb is refused by role through the two-axis matrix.
This is the other half — a verb the ACTIVE membership's role cannot perform is **not rendered**.

Rendering a verb the server will refuse is not a safe default: it teaches a person the product
is broken, and the refusal arrives after they have already decided to act. Round 112 shipped
exactly that state — a viewer saw Release, Purge, Register and New key, clicked, and got a 403
with no explanation the screen could give (`ScopeInterceptor` answers before the handler, and
htmx does not swap a 4xx, so the click simply did nothing).

**Hide, don't disable.** A verb the role cannot perform is absent — no greyed button with a
tooltip; a viewer is not being teased. The exceptions are two, both stated at their sites: the
editor's Release stays *disabled with its reason* when the DRAFT is invalid for someone who CAN
release (a state refusal, and the reason is actionable), and the promotion screen keeps its plan
readable for every role the page admits while naming who can send it (§4.17).

One helper answers the question for every screen — `web/ui/RoleModel`, stamping `canRead`,
`canExecute`, `canReadExecutions`, `canAuthor`, `canReadPromotion`, `canPromote`,
`canAdminWorkspace`, `isSuperAdmin` and `roleLabel` into the model (the two `canRead*` booleans
are 177's — a page-wide read that follows its own §7.6 row). No controller derives a role boolean
of its own. Every verb control carries a `data-verb` attribute, which is both the tests' hook and
the inventory: `grep -rn 'data-verb=' modules/web/src/main/resources/templates` is the list, and a
control outside a role guard fails `RoleVisibilityRenderTest` — **with no exemptions** since 177:
the dialog fragments a route used to guard alone stamp the role model and guard their verb in the
markup too.

Each boolean narrows for an API-key principal by the key's SCOPE as well as its issuer's role
([Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative) — the credential axis), so a
`read` key is never shown an author's affordances because the person who minted it is an author.

| Screen | Verb(s) | Rendered when | §7.6 permission |
|---|---|---|---|
| Pipelines catalog (§4.3, since #350) | none — the rows open the workspace | — | `pipeline.read` |
| Pipeline workspace (§4.4) | Purge draft, Release; Purge pipeline in the `{D}` shape (#395); the Versions tab's Discard, Restore, Purge | `canAuthor` | `pipeline.version.manage` / `pipeline.release` / `pipeline.delete` |
| Pipeline workspace (§4.4) | Switch served version (the Versions tab's Switch to) | `canAuthor` (O-1 collapsed onto the author with D8) | `pipeline.switch_version` |
| Pipeline editor | Execute, Cancel | `canExecute` — **viewer-level** (D3); never the promoter (D5) | `pipeline.execute` / `execution.cancel` |
| Templates catalog and workspace (§4.6/§4.7) | Create, Edit, Discard, Restore, Purge, Release | `canAuthor` | `template.create` / `template.update` / `template.version.manage` / `template.delete` / `template.release` |
| Template workspace (§4.7) | the Source tab's Edit, and the Render tab's context form and **Preview** (143, #398) | `canAuthor` — everyone else reads the working version in the read-only pane | `template.update` / `template.render` (the preview POST) |
| Template workspace — the transform face (§4.7, 7d) | **Save draft** (`transform-save`), **Run suite** (`transform-run-suite`), the four editable panes | `canAuthor` **and** the displayed version is the working DRAFT — everyone else (and an author on a released version, who gets **Edit**) reads the four panes as `<pre>` blocks | `template.update` / `template.evaluate` |
| Shell (§3.4) | the **Admin** item (143) | `navAdminUsers` (super admin → `/admin/users`) or `navAdminMembers` (workspace admin → `/workspaces#workspace-members`); absent otherwise | `user.manage` / `workspace.members.manage` |
| Shell (§3.4) | the **Executions**, **Promotion** and **Workspaces** rail items (177) | `navExecutions` (= `canReadExecutions`), `navPromotion` (= `canReadPromotion`), `navWorkspaces` (workspace admin, super admin, or a principal with no workspace — the no-workspace page is the one screen that explains their state) | `execution.read` / `promotion.read` / `workspace.read` |
| Shell (§3.4) | the header search (161, #155) | every role — a READ over what the session's workspace already shows; each result is a link to a page the destination screen's own guards govern | `pipeline.read` |
| Dashboard (§4.2) | the Recent executions panel (177) | `canReadExecutions` — the promoter's dashboard does not draw it; the stat tiles are not a verb and count the promoter's OWN runs (#293) | `execution.read` |
| Every pipeline/template read — the catalogs and sidebar trees (§4.3/§4.6), the workspaces' tabs, the header search, the rail badges, the dashboard tile (178) | not a verb: WHAT the screen shows. A **promoter** sees the LENS — released pipelines and templates newer than the promotion target's, and the endpoints of those pipelines; a hidden object is absent (its URL is the not-found page, its id 404s), a visible object's pending draft is invisible (the detail panes, the editors' read-only view, the version lists, the node-SQL section, the checks pane and the used-by list show the release only — 178b), and when the target cannot be read the lists are empty and say why | the principal's `LensedView`, passed into every read ([Auth §11A.1](auth.md#11a1-the-404-rule)) | `pipeline.read` / `template.read` / `endpoint.read` (the cell is `lens`) |
| Datasources (§4.5) | Register, Edit, Delete | `canAdminWorkspace` | `datasource.manage` |
| Datasources | **Test** | `canExecute` — the connection test **follows execute** (ratified 2026-09-20) | `datasource.test` |
| Datasource grants (§4.5a) | Grants, Grant, Revoke | `isSuperAdmin` | `datasource.grant` |
| API keys (§4.19) | New API key / Delete / Edit associations / Edit dashboard folders (L5) — a `server` key's Delete: `isSuperAdmin` only (#215) | `canAdminWorkspace` or `isSuperAdmin` | `api_key.create` / `api_key.revoke` (+ `server_key.revoke` for a server key) / `api_key.bind` (179, D17) / `dashboard.key.bind` (L5) |
| Top bar (§4.3e) | MCP key copy / delete-to-rotate | every role, own key (`mcpKey != null`) | `mcp_key.own` (179, D16) |
| Promotion (§4.17) | the page itself | `canReadPromotion` — author, promoter, admins (owner rule 13) | `promotion.read` |
| Promotion (§4.17) | Promote — and, since 143, the whole submission form (Send column, selection boxes) | `canPromote` in the SOURCE workspace; an author gets the plan as a plain table | `promotion.promote` |
| Workspaces (§4.13) | the page itself | `canAdminWorkspace` or `isSuperAdmin` (D13) — the switcher in the chrome stays every member's | `workspace.read` / `workspace.switch` |
| Workspaces (§4.13) | Members: add (with a role), change role (the dropdown's Save — one htmx partial), revoke the member's key (#200, only when one is live), remove, revoke invitation | `canAdminWorkspace`, **in the ACTIVE workspace** | `workspace.members.manage` |
| Workspaces | Display name | `canAdminWorkspace` | `workspace.update` |
| Workspaces | Create, Deactivate, Reactivate, Delete | `isSuperAdmin` | `workspace.create` / `workspace.lifecycle` |
| Execution detail (§4.9) | Cancel | `canExecute` **and** the execution is RUNNING | `execution.cancel` |
| Schedules (§4.20) | New schedule, Edit, Pause / Resume, Unblock, Run now, Delete (and the form's Save, the delete dialog's confirm) | `canAuthor` — the author / workspace admin / super admin set, which is exactly the five write rows (`SchedulesUiControllerTest` pins it); the schedule's STATE then picks among the rendered ones (Pause while enabled, Resume while paused, Unblock and no Run now while blocked) | `schedule.create` / `schedule.update` / `schedule.pause` (pause, resume, unblock) / `schedule.run` / `schedule.delete` |
| Schedules (§4.20) | the run's execution link and its pipeline messages | `canReadExecutions` — a promoter reads the scheduler's trail only | `execution.read` |
| Shell (§3.4) | the **Schedules** rail item | every role — `schedule.read` is every member's (the promoter through the lens) | `schedule.read` |
| Lifecycle dialogs (§4.3d) | the Release / Discard / Purge / Restore / Switch confirm buttons | `canAuthor` (177 — the route already refuses the wrong role; the markup now says so too) | the dialog's own verb's permission |
| Datasource dialogs (§4.5) | Save changes, Delete confirm | `canAdminWorkspace` | `datasource.manage` |
| Shell (§3.4) | the role badge | always | — |

A viewer reaches the PIPELINE editor through the explorer's **Open in editor** link and executes
there (122): the page route's floor is `pipeline.execute` — the permission the screen exists to
perform for its lowest role, D3 — so the verbs this table gives the viewer are actually
reachable, and the read-only line on that screen is 114 §A's. A **promoter** does not reach it at
all (D5: no execute), which is the same answer the rail gives by not drawing Executions. **The
TEMPLATE workspace floors at `read` (143, T315, owner ruling; the editor page became the workspace
in #398):** the tree's and the catalog's links render for every reader, so the page they lead to
must open for every reader — read-only, like the pipeline workspace. `readOnly` is the author permission combined with the version rule
(`TemplateSourceModel.fill` since 7d — moved unchanged out of `TemplateEditorController.fillSource` so the transform face's routes share the one rule), on the page and on the source partial; the editable
textarea, Preview, the context rail and Release are an author's; the read-only line renders for a
viewer and for a promoter — a promoter, through the lens (178): a template the lens hides opens as not-found, exactly as an unknown name does. Every version-row Open carries its row's version.

Three rows are worth reading twice, because each is a place a reasonable guess is wrong:

- **Datasource Test follows EXECUTE, not admin** (ratified 2026-09-20). Every role that may run
  a pipeline against the datasource may ask whether it answers — viewer included — and the
  promoter, who runs nothing, may not. Register/Edit/Delete stay the workspace admin's; the MCP
  twin `datasources_test` sits on the same row.
- **Key REVOKE is `view`, not `author`.** Only ISSUANCE carries the author gate (§7.4). Hiding
  Revoke from a viewer would strand a DEMOTED author with a live key they are not allowed to
  withdraw — the opposite of what O-2 is for.
- **Member verbs follow the ACTIVE workspace.** `ScopeInterceptor` judges
  `workspace.members.manage` against `principal.workspace`, never the workspace named in the
  path, so an admin of X working in Y cannot manage X's members until they switch. §4.13's
  member table therefore renders for the active workspace only, and names the others.

### 4.4 Pipeline Editor

Fully specified in [Pipeline Editor spec](pipeline-editor.md). Only the rows that touch THIS document's shared contracts are noted here:

- **The route is the version-explicit read page (#348), composed by #349:** `GET /pipelines/{id}?version=N&tab=flow|overview|parameters|runs|usage|versions`, floored at `pipeline.read` — the page renders the caller's lens, so a promoter reads admitted RELEASED content and never a draft. An explicit version is shown exactly or answered with the house 404 (never clamped, never re-resolved); invalid version syntax is the house 400. The default is the ACTUAL current pointer (a development-posture current draft shows as the draft it is), then an accessible draft, then a **Choose a version** state over the admitted history — never "latest release", never a hidden pointer. The old `/pipelines/{id}/editor` URL is a compatibility redirect into this page. Entry links preserve the rail through prepared main-content swaps.
- **Six tabs, one page (#349, spec §4.1):** Flow (the graph + the dock) is the default; Overview, Parameters, Runs, Usage and Versions are the reading surfaces. A tab change is NAVIGATION ONLY — it swaps no execution state and cancels no run; the URL carries `tab=` (absent for Flow), and since #402 each user tab change PUSHES an entry of the workspace's own (`workspace/history.js`, §3.2) — Back/Forward re-select the tab in page; re-selecting the active tab mints nothing. The tab set is the closed set the server resolves (unknown → flow; `runs` without the execution read → flow BEFORE any runs read), mirrored client-side in `tabs.js`. **Runs renders — and lazily fetches — only for a caller with the execution read**, and its absence costs nothing: a promoter's strip is five tabs and their dock is Node Details alone, proven on the wire (zero execution requests across every tab). Runs and Usage load ONCE, on the tab's first open, generation-stamped. The Overview pane is client-rendered from the page's ONE lens-filtered workspace block (the admitted history, the record facts, the datasource dialect map across every admitted body); the Parameters tab composes the viewed version's declared schema in the house table with the RUN OVERRIDES as a separate section, reachable beside Execute through the topbar's Run parameters button; the Versions tab composes the explorer's fragment (per-row verbs, dialogs into `#pe-dialog` with `from=editor` — a success reloads the whole workspace). The settings sidebar is GONE — its description and parameters moved to Overview and Parameters, and no second sidebar renders (§4.1's "no duplicate full description/settings sidebar").
- **Overview last run visibility (#392):** Last run is the latest run visible to the caller under the Runs tab's #275 rule; a promoter sees only their own.
- **The viewed version moves IN PAGE (#349, spec §4.3):** the header selector and the Versions tab's Open links apply a version through the admitted REST version read without a document reload — URL (a pushed entry of the workspace's own once the body lands, #402; a refused read pushes nothing), chip, body block, workspace pin, selector marks and Versions-tab marks move as one transition, and the active run keeps its stream and identity. The page/view state key is workspace + pipeline + viewed version + request generation: every in-page read (node SQL, checks, runs, usage) carries the token it was issued under and a stale response — success OR failure — is cancelled at `htmx:beforeSwap` and never paints (A→B→A included; the token rides the REQUEST, since a newer request re-stamps any sink-carried copy). A superseded in-flight read is ABORTED by the one `hx-sync="this:replace"` requester — htmx's default drops a request while one is in flight, and this build's `abort` mode aborts without issuing (both verified in the vendored 2.0.10 source). Run input drafts are PER VERSION within the page: switching schemas cannot reuse another version's fields, revisit restores compatible overrides, and nothing persists (no localStorage, no navigation URL).
- **Back/Forward replay the in-page switches (#402, spec A12):** v1 → v2 → Overview, then Back twice, lands on v2/Flow and then v1/Flow, and Forward replays both — on the LIVE instance (no reload, the same runtime epoch and activation count, one component), without touching an active run's stream or identity. A replay re-issues the SAME lensed version read (a version the read refuses is the house banner, never a body) and never mints an entry. The page's workspace block and the root's `data-active-tab` are kept truthful on every switch (the pin fields, the rows' viewed mark and the active tab; the admitted rows, the Overview facts and the dialect map kept), because they are what a cached restore re-reads: a boosted leave after a switch restores from the cache with its selector intact, and Back from there replays the workspace's entries in the restored instance. An entry carries `version` and `tab` only — never a parameter value.
- **Run facts attach only on their own version (#349, spec §4.2):** the execution tabs (Results | Errors | Events) render only for a caller with the execution read and belong to ONE captured run, named by the identity strip — pipeline, run version, status, execution id and the effective SUBMITTED parameters (the declared keys, never the resolved Context's platform tiers) — with **View run's version** beside it when the page is viewing another version. The graph paint, the node states, the Details pane's run-derived rows (Last run, the measured operation, the calculator Context resolutions, the child-execution link) all gate on run version = viewed version; a v1 run's progress never wears the v2 body. Node Details follows the VIEWED version and stays in the bottom dock beside the execution tabs — there is no right inspector.

- **Rendered for (114):** Release is `canPromote`, Purge draft is `canAuthor`, Execute and Cancel are `canExecute` (viewer-level, D-R3) — [§4.3e](#43e-role-visibility--every-verb-and-the-role-boolean-that-renders-it-114-normative). A viewer's editor LOADS and is read-only: the verbs are absent and one quiet `.app-note` line reads `Read-only — you are a viewer in <workspace>`. No toast: nothing failed.
- **Draft lifecycle actions (102):** the topbar's Release / Discard draft buttons open the §4.3d dialogs in `#pe-dialog` — the release dialog shows the draft's pins, offers to release DRAFT template pins with the pipeline (142, checked by default) and refuses on a DISCARDED or MISSING one; the discard dialog is the PURGE (versioning §5.4 — the pre-102 confirm text claiming "an executed draft is kept as history" was false and is gone). A success answers `HX-Redirect` back to the editor with a flash toast (the page's draft state is document-wide); a refusal is §5.1 Shape C. No native `confirm`/`alert` remains on this screen (the pre-102 `draft.js` carried three — a static test pins the count at zero).
- **Measured node operations (149):** while a node runs its card footer shows the measured operation from `node_progress` — Querying / Fetching / Waiting for tempdb / Writing / Committing with the live cumulative count — and the Details pane carries Operation, Progress, Rows, Time in and Commit rows; the a11y node list carries the same as each row's description ([Pipeline Editor §5.3, §8.1, §10.7](pipeline-editor.md)). The arrows and the write are the next bullet's (151, #127).
- **Dependency arrows are orderings; the write is a port (151, #127):** every `depends_on` entry is one **dependency** edge — "the node it points to waits for the node it leaves", which is all a dependency on a DDL node means too — whose states are static facts about its two nodes: `active` while the target runs (dashed brand, **still**), `satisfied` once the source completed (`--edge-done`), `unmet` once the source failed or was aborted. **No count ever rides an edge** (the pre-151 client copied the producer's `rows_out` onto every outgoing arrow, so one staged table read by two consumers looked like two writes) and **nothing moves along an edge** (the rAF dash flow is retired). What a node writes, and where, is its **output port**: one row on the producer's own card — a stub with the port dot leading into the destination (`tempdb.stg`, `warehouse.facts`, `caller`; a DML statement names its source, no table; DDL, an output-less PIPELINE and a CALCULATOR have none) — driven by the #125 `node_progress` samples through the 149 reducer: `waiting for tempdb connection` (amber, still), `writing · N written` (the ONLY write-flow on the canvas, on the stub), `committing`/`finalizing`, `committed · N rows` / `N written · commit not observed` / `failed · rolled back`, and `one statement` for a CTAS whose write is inside the statement. A lost stream freezes every indicator and appends "— stream lost" rather than inventing an outcome; the recovery poll settles End from the polled status and closes open operations as not observed. The legend gains **Depends on** / **Output write** chips (not aria-hidden — they are the graph's key); the Details pane gains `Depends on` (each ordering with its state) and `Required by` rows; tapping an arrow announces what it means and selects the waiting node ([Pipeline Editor §5.3a](pipeline-editor.md)). Guarded by `graph-dependency-edges.test.mjs`, `graph-output-port.test.mjs`, `sse-stream-loss.test.mjs`, `PipelineEditorArrowClarityBrowserTest` (one port with `committed · 900,000 rows`, two unlabelled dependencies, a real run) and `PipelineEditorLeaseWaitBrowserTest` (a real wait on one staging connection).
- **Execution boundaries (150, #126; redesigned by 151, #144):** the canvas derives two view-only **Start** and **End** markers from the authored graph — Start connects to every node with no `depends_on`, every leaf connects to End. Since 151 they are **shapes, not pills**: Start a disc with the `play` glyph, End a rounded square with `square`, the word under the shape, in a compact transparent box so nothing about them reads as a card. **Start is the run button** for a viewer who may execute — the SAME server-rendered `canExecute` that renders the toolbar's Execute, stamped on `.pe-root` as `data-can-execute` (never re-derived in JS): `role="button"`, focusable, Enter/Space, `aria-label="Start execution"`, filled in the run button's own accent; its activation calls the same `executePipeline()` the toolbar calls — one code path, parameters and draft pin identical. **While the run is active the same disc is the Cancel control (159, #148)**: named `Cancel execution`, the word `Cancel`, the toolbar Cancel's square glyph and danger fill under the running pulse, never `aria-disabled`, and its activation calls the same `cancelExecution()` the toolbar's Cancel calls — the mirror of Execute → Running… + Cancel; on any terminal state it is Start again. A viewer who may not execute sees the plain marker in every state (reading `Running…` while the run is active). A press on the disc is a button press, not a canvas gesture: it is stopped before Cytoscape in the capture phase, because Cytoscape's own mousedown re-rendered the disc under a held button and a hand's click then never became a click (the cause of "Start does nothing" on the live editor); keyboard focus stays on the disc across its re-renders. In a browser session every workspace member may execute (D-R3), viewers included; a render without the right draws a plain outlined disc (`role="img"`, no affordance — `RoleVisibilityRenderTest`). They are still not executable NODES — no authored JSON, no NodeType, no statistics, unselectable, nothing to open, synthetic ids that cannot collide with an authored id (the kind is data). **End is never a trigger**: a muted outline while idle, then the authoritative outcome's fill and word — `Finished` (success) on `pipeline_completed`, `Failed` (danger) on `pipeline_failed`, `Stopped` (warning) on `execution_aborted` — with the run clock's elapsed time under it; never a node event, so one finished branch never reads as a finished execution, and eligible roots (execution started, node not yet) show no `active` connector until their `node_started`. Boundary connectors are their own kind and never labelled; the minimap keeps the two silhouettes; `#cy-canvas` is `role="group"` (an image role would hide the button from assistive tech). A cancelled-or-never-started node ends Aborted beside them (#135). ([Pipeline Editor §5.3b](pipeline-editor.md); `graph-markers.test.mjs`, `PipelineEditorStartMarkerBrowserTest`, `PipelineEditorBoundariesBrowserTest`.)
- **The v2 canvas (080, owner-approved mock):** dotted-grid stage, the mock's node card (type-accent icon tile, id + eyebrow, mono facts, footer state + run numbers), bezier edges with three states, minimap, legend, controls and the fit-that-never-zooms-in ceiling. Its tokens are ONE block at the top of `app.css` (`/* app tokens (080): node-type accents */`) — the five node-type accent pairs plus `--brand(-soft)`, `--border-faint`, `--grid-dot` and the `--edge*` strokes — bridged to design-system tokens so every theme re-skins it. 079's shell v2 is told the block exists and must not redeclare it.
- **The dock (080):** Details | Results | Errors | Events — the 065 inspector overlay is a tab now (owner ruling 2026-09-05), the dock is always present, the chevron collapse is the only contraction, and there is still no close. **Notifications:** unchanged in shape — `pipeline_completed` and `execution_aborted` toast via `DpToast.show` (§5.1 Shape D), `pipeline_failed` keeps the modal — but exactly-once is now structural: the 076 afterSettle rescue stacked Alpine components on a history-restored root (one click → N executions → N toasts), and it destroys the stale tree before re-binding (`editor-toast-once.test.mjs`). Every SSE event of every kind also lands in the **Events** tab in arrival order. **The dock's height is the user's (104).** It was a fixed 232px that could only be collapsed and restored — the owner's report was *"it has a fixed height. You can min/max it but cannot change the height. I want it to be flexible and the user should be able to drag the height."* A `role="separator"` handle straddles the dock's top border (drag, or focus it and use the arrows: ±16px, Shift ±64px, Home/End for the floor and ceiling, double-click to reset). Since 141 the grip is a visible pill at rest (`40px × 3px`, `--border-default`; brand on hover/focus/drag) rather than a transparent strip that only lit on hover. The floor is 120px and the ceiling leaves the canvas a 160px readable strip. The size is one CSS custom property (`--pe-dock-pane-h`) written on `<html>` and remembered in `localStorage` under `dp.pane.editor-dock`; `static/js/splitter.js` is a parser-blocking script ABOVE the markup, so a remembered height is the height of the FIRST frame rather than a shift onto it. Collapse still wins while it is on — expanding restores the remembered height. **The canvas follows:** `.pe-body` is `flex: 1`, so a taller dock is a shorter stage, and graph.js's stage `ResizeObserver` now routes through `handleStageResize` — `cy.resize()` always, re-fit ONLY if the view was still the fit (082/098: fit never zooms IN, and a user who has panned to a corner keeps their view). One path also covers the rail collapsing and the window resizing. The row transition is switched off for the duration of a drag, which is also the only motion `prefers-reduced-motion` users would have met here.
- **The settings sidebar is withdrawn (#349, spec §4.1) — 141's resize contract went with it.** 141 made the 280px settings sidebar the user's width (a `splitter.js` handle, `--pe-sidebar-w` on `<html>`, the `dp.pane.editor-sidebar` key, a 220px floor and a 50vw ceiling, a drawer below 1024px). The owner's workspace ruling of 2026-09-30 withdraws the PANE: the graph owns the available width, and the description and the declared parameters live in the Overview and Parameters tabs (the six-tabs bullet above; [Pipeline Editor §4.3](pipeline-editor.md)). `pe-sidebar` and the `editor-sidebar` splitter occur zero times in `editor.html`, `pipeline-editor.css` and `init.js`; `PipelineWorkspaceLayoutBrowserTest` pins their absence (no settings sidebar, no second splitter handle), re-reads the dock's drag contract as the composition's control and asserts the tab strip stays reachable while the dock is collapsed. The 141 sidebar-resize suite is deleted with the pane; the dock's own resize (the bullet above) is unchanged and remains `PipelineEditorDockResizeBrowserTest`'s.
- **SQL section (§8.3 there):** the Details tab loads `GET /partials/pipelines/{id}/nodes/{nodeId}/sql` (a `pipeline.read` read partial, htmx.ajax on selection) and highlights it client-side with the zero-dependency `sql-highlight.js`; the copy confirmation is a live-region announcement plus a 1.5s button-label swap — deliberately NOT a toast (high-frequency, self-evident). CALCULATOR and PIPELINE nodes skip the fetch (client-built evaluation / child mapping).
- **The data blobs cannot break out of their script blocks (185):** the editor's two `<script type="application/json">` blocks (`#pipeline-data`, `#pipeline-lifecycle`) are inserted with `th:utext`, so the controller writes both through `ScriptSafeJson.forScriptBlock` — a closing-tag sequence inside any free-text field the pipeline carries (display name, description, node labels, template names) is escaped and the JSON parses back unchanged client-side; `ScriptBlockUtextAuditTest` holds the document's closed `th:utext` allowlist and `PipelineEditorJsonRenderTest` reads the render back through a real HTML parser.
- **Result grid:** the execution result table is the data table (§3.7) — a frame FIXED by its pane (`dt-fill`): the header and the first column hold while a wide result scrolls both ways, the scrollbar starts under the header, the columns sort (this page) and resize; the bespoke `.pe-result-table` styles are gone. Paging stays client-side cursor paging (the §10.5 contract there).
- **Template reference (§9.4 there):** a node's template is a read-only reference display — `acme/finance/monthly_revenue @ v3`, one line with the FULL reference on `title`, in the Details tab's key/value grid **and** in the server-rendered `partials/pipeline-node-sql` **and** in the `template-missing` empty state. **There is no template picker on this screen**; template selection happens through pipeline JSON authoring, import and MCP. **If a picker is ever added, it reuses §4.6's prefix fragment — it does not get its own client-side tree.**
- **The TRANSFORM node (7d, #7; [transform-nodes design §9.3](superpowers/specs/2026-09-09-transform-nodes-design.md)):** its card wears its own accent (`TYPE_TOKEN.TRANSFORM = "transform"` → `app.css`'s `--type-transform`, a teal derived from the theme's info and success accents — no literal colour) and the sprite's `code` glyph (the rail's template glyph: a TRANSFORM is a pinned function), and carries three fact lines: **the language and the pin** — `jsonata · …/order_lines.jsonata @ v3`; the language is the pinned template's `type`, which is not on the node JSON, so the card reads `transform · …` until `graph.js` resolves the pin the way it resolves datasource dialects (one `GET /api/v1/templates/versions` per distinct pin, `template.read`, capped at 50; a failure or a lens-hidden version leaves the honest `transform`); **what it reads and where it writes** — `2 inputs → tempdb.order_lines`, `→ caller`, or `→ $line_count` for a value-mode node's Context key; **rejects and strict** when set — `rejects → tempdb.order_rejects · strict`. The output is fact line 2, not a port row (a TRANSFORM's write is not a `node_progress` operation today). Every value passes through `buildCardHtml`'s escaper (a template name planted with `<script>` renders as text — `graph-transform-card.test.mjs`). The **Details** pane lists Template, Language and Mode (both read off the resolved pin — the mode is the CONTRACT's, never the node's; `resolving…` until the lookup lands), Inputs as bound (`orders ← stg_orders · tz ← $org_timezone`), Output, Rejects and Strict, plus the node deadline; no Query Timeout row (a TRANSFORM runs no statement — `SettingsRules`' statement types). The definition pane shows the node as a function call — the template, its language and mode, the inputs map, the output and the rejects — escaped by construction; nothing is fetched (a TRANSFORM has no SQL to render). A pinned version marked `needs_review` (lane 7e's flag, read off the resolved pin) shows the marker on the card's kind line and in the Details header and rows. The legend names **Transform**. Read-only, as every node is. (`TransformNodeCardBrowserTest` — three TRANSFORM shapes saved through 7c's validator.)
- **Failure display (057/T85, re-homed by 065, consolidated by 080):** `node_failed`'s `error` object — the failure record — renders in the dock's **Errors tab**: one entry per failed node, newest last, `node id · code` as its summary line, then the message, correlation id, details, the rendered SQL and the exception chain **root-cause-first**, one Copy button. The 065 per-node inspector section is gone with the overlay — the tab is the record's one home beside the modal's one-line summary. `pipeline_failed`'s execution-level record joins the same list (deduped on node + code + message). `PEErrorDetails.build` remains the one view-model (`sse-node-failure.test.mjs` pins it). Under `error-detail=structured` the SQL and Exception sections are simply absent — no apology. The recovery poll's banner names the error code.
- **Full-bleed pages (065):** `layouts/default.html` caps every page at `--app-content-max` (1600px); a page opts out by setting a `fullBleed` model attribute (`app-main-bleed`). **The pipeline editor is the only page that sets it.** `EditorLayoutRenderTest` pins both directions.
- **Editor entry preserves the rail (#460):** the host validates the destination and dependencies before
  the boosted main-content swap. The pipeline runtime's inert catalog and exclusive activation prevent
  Alpine from initializing before its dependencies; cached history mounts once. See Pipeline Editor §4.1.
- **Phone widths (110): desktop-first by decision, not omission.** Below 768px the page renders a `.app-wide-screen-note` band above the workspace — "Open on a wider screen to inspect the graph" (the pre-#348 "to edit" named an authoring act this read page does not carry), the pipeline's name, its viewed-version badge (the same `viewedLabel` the topbar's chip reads) and a link back to the Pipelines explorer — while the page itself stays rendered and scrollable underneath; no editor script is touched.

### 4.5 Datasource List

| Attribute | Value |
|---|---|
| URL | `GET /datasources` |
| Auth required | Yes (`read` to browse; `author` to test a connection; workspace-bound create behind the `member-datasources-enabled` gate, global create/manage `admin` — workspaces D8) |
| Purpose | Browse, test, register, EDIT and DELETE datasource connections in the active workspace |
| Design primitives | the data table (§3.7), `.ds-badge`, `.ds-button`, `.ds-modal` |
| JS | `static/js/toast.js` (layout-global: arms every `.ds-toast` appended to `#toast` — auto-dismiss + close) |
| htmx | Yes — test connection button (`hx-post="/partials/datasources/{name}/test"`, `hx-target="#toast"`, `hx-swap="beforeend"` — the result is a §5.1 Notifications toast, never a row swap), search + dialect filter + pager (`hx-get="/partials/datasources"` into `#datasource-list-wrapper`, `outerHTML` — the fragment root carries the id, so the swap target survives every refresh; the pager is the shared §5 fragment), register modal (`hx-post` on `/partials/datasources`, `#register-result` target — success is §5.1 Shape A: the success node closes the modal, the refreshed list and the toast ride along out-of-band, no `HX-Redirect` and no page reload). **A table partial travels as a whole `<table>` on any out-of-band path**: a `<tbody>` (or `<tr>`, `<td>`…) carrying `hx-swap-oob` nested in a `<div>` is silently DISCARDED by the browser's HTML fragment parser — table-only tags outside table context are dropped tokens, so the swap "succeeds" with empty content and no error anywhere (030 F-1; §4.10's keys table is the reference shape) |

Content: table of the ACTIVE workspace's datasources (name + `readonly` badge, dialect badge, workspace column — `global` or the bound name, URL, username, **last test**). Per-row "Test" button (`author` scope) → connection result as a §5.1 Notifications toast; the table itself is never re-rendered mid-interaction. "Register Datasource" button → modal form with: name, display name, dialect dropdown, JDBC URL, username, password, description, and two checkboxes — `readonly` (always settable, [Datasources §5.7](datasources.md#57-readonly-datasources-flag-semantics-and-enforcement-layers)) and `global` (**admin-only; visible-disabled for everyone else**, workspaces D8: unchecked binds to the active workspace). The register action applies the SAME D8 rules as REST §9.1 (`DatasourceWorkspaceRules` — one component, two surfaces) and crosses the same registry save boundary. A register REFUSAL stays inline in the modal (the screen-local `htmx:responseError` path — the modal must not close over an error, so it deliberately carries no `HX-Retarget`).

**The Tables column (162, #156).** Every row carries a link to the detail page — "Lake tables" for a LAKE datasource (its registered catalog, [§4.5c](#45c-datasource-detail--a-read-only-tables-view-162-156)), "Tables" for every other dialect (its live schemas → tables → columns, same section). It is `data-read`, not `data-verb` — a viewer's row renders it exactly like every other row, the same [Datasource learned facts](#45b-datasource-learned-facts-118) precedent.

**The Last test column (061/T84).** Each row shows the outcome of the LAST connection test against that datasource ([Datasources §8.1B](datasources.md#81b-the-last-tests-outcome-is-stored-and-listed)): a `ds-badge-success` **ok** or `ds-badge-danger` **failed** badge, the timestamp, and the driver's message in the cell's `title`. A datasource nobody has probed says **never tested** rather than rendering blank, because an empty cell on a health column reads as health. This column exists because LISTING a datasource does not connect to it: on 2026-09-02 this screen showed `sample-trips` as fine while every demo pipeline failed at CONNECT with `password authentication failed` — the screen could be silent but not wrong. Nothing on this screen polls; the column shows what the last test (the Test button, the REST probe, or the startup bootstrap check) found.

The listing is workspace-scoped exactly like REST §9.2 (`listVisible`: active-bound + global, repository-level); a datasource bound to another workspace is absent, and its by-name test behaves as not-found. Rename is not offered — a datasource name is immutable (delete + re-create, blocked while referenced — including by a HISTORICAL pipeline version, [Datasources §6.2](datasources.md#62-in-use-check-on-delete)). The search covers every column the table renders (the §5.1 Search rule): name and the `readonly` badge, the dialect wire value, the workspace column (the bound name or the literal `global`), the JDBC URL, the username, and the last-test state (`ok` / `failed` / `never tested`, so "show me the broken ones" is a search) — plus `description`, searchable though only the modal shows it.

**The Connection pool section (094).** The register modal and the edit dialog each carry a `<details>` labelled **"Connection pool — defaults"**, collapsed. Expanded it renders the eight tunable HikariCP keys ([Datasources §5](datasources.md#5-connection-pool-configuration)) — each with its unit, a one-line meaning, and a help line naming the layer the prefilled default came from ("Default 10 — HikariCP's own", "Default 2 — this server's"). `readOnly` is rendered DISABLED, mirroring the datasource's own `readonly` flag: it is §5.6-refused as a pool property, so a free field would be an input whose every value the validator rejects. Refused keys are never rendered at all. **A field left at its default is not persisted**, so a later change to a product default still reaches datasources created through this form. The dialect select re-fetches the section (`hx-get="/partials/datasources/pool-fields"`, `hx-target="#ds-pool-fields"`, `outerHTML`) because the effective default is the DIALECT's to decide. Out-of-range values are refused server-side and render inline like any other save refusal.

**Edit (094).** A per-row **Edit** action (`author`) fetches the dialog into the screen's single empty `#ds-dialog` container. The `name` and `dialect` are shown disabled — the name is immutable and re-pointing a live datasource at a different driver would silently take every pipeline that references it by name along. A blank secret KEEPS the stored credential (the stored one is never rendered back, [Datasources §7.1](datasources.md#71-encryption-at-rest)). The `global` checkbox is admin-only and carries a hidden companion field, because an unchecked HTML checkbox posts nothing and an admin must be able to un-global a datasource. Same D8 rules and same `registry.save` boundary as REST §9.4.

**Delete (094).** A per-row **Delete** action opens a dialog that asks the USAGE question FIRST, from the same any-version scan the REST `409 datasource.in_use` uses ([Datasources §6.2](datasources.md#62-in-use-check-on-delete)):

1. A member on a GLOBAL datasource is told admin is required, and nothing else is offered.
2. In use → the referencing rows, `pipeline › node (v3 released)`, with links. **There is no confirm button on this branch**, so the refusal cannot be clicked past.
3. Unused → a confirm that NAMES the datasource, and says that queries already running finish first (the §5.2 retirement lifecycle is what makes that true).

The POST re-runs the guard regardless: a pipeline can start referencing the datasource between the dialog opening and the button being pressed, and the screen is never the authority. Success is §5.1 Shape A — the success node closes the dialog, the refreshed list and the toast ride along out-of-band. Both dialogs are delivered as a WHOLE backdrop rather than a body swapped into a pre-rendered shell: `.u-backdrop` is `display: flex`, so the dialog is on screen the moment htmx swaps it, with no open() script to fall out of step with the markup, and closing is emptying the container. A 4xx refusal renders inline and does NOT close the dialog — the success node carries a `data-ds-saved` marker the refusal never has, which is the same distinction the register modal draws with `data-error` (022/F9).

**Rendered for (114).** Register, Edit and Delete are `canAdminWorkspace` — [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative) puts `datasource.manage` on the workspace admin, not on `author`: a datasource is a live database credential. **Test** renders by `canExecute` (`datasource.test` — the connection test **follows execute**, ratified 2026-09-20: every role that may run a pipeline against the datasource may ask whether it answers; the promoter, who runs nothing, may not). An author or viewer sees the list, the badges, the Last test column and Test; a promoter sees no action at all. Register additionally needs the DEPLOYMENT's `member-datasources-enabled` gate — two gates, both required — so a non-admin on a locked-down server sees no Register even if their role would allow it (the demo shape: open datasource creation is an SSRF primitive from the server's network position). The `global` checkbox's admin-only rule is unchanged.

**§4.13's workspaces screen** owns workspace lifecycle; **[§4.5a](#45a-datasource-grants-114)** owns which workspaces can SEE a datasource.

### 4.5a Datasource grants (114)

| Attribute | Value |
|---|---|
| URL | `GET /partials/datasources/{name}/grants` (a dialog into `#ds-dialog`) |
| Auth required | Yes — **super admin** (`datasource.grant`, [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative)) |
| Purpose | Decide which workspaces can see a datasource at all (RBAC design §4, D-R7) |
| Design primitives | `.u-backdrop`, `.ds-card`, the data table (§3.7), `.ds-badge`, `.ds-empty`, `.ds-select` |
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
| URL | `GET /partials/datasources/{name}/facts` (a dialog into `#ds-dialog`); the same table inline on every datasource's detail page (`GET /datasources/{name}`, [§4.5c](#45c-datasource-detail--a-read-only-tables-view-162-156)) |
| Auth required | Yes — any member (`semantic.read`): the facts are a READ, and which rows a workspace sees is the store's own predicate, not the screen's |
| Purpose | Show what agents LEARNED about a datasource that its schema could not say — units, time zones, sampling, grain, what coded values mean, joins, caveats — with trust badges ([learned-semantic-layer design](superpowers/specs/2026-09-11-learned-semantic-layer-design.md) §7.3) |
| Design primitives | `.u-backdrop`, `.ds-card`, the data table (§3.7), `.ds-badge`, `.ds-empty` |
| htmx | Yes — the whole-backdrop-into-`#ds-dialog` contract of §4.5's dialogs (094 §A/§B, 114 §C.2) |

**Read-only in round 1.** One row per fact, oldest first: the OBJECT it is about (`schema.table.column`), the kind badge (plus a `workspace` badge on a WORKSPACE-scope fact — one organisation's meaning, D-S1), the fact text (plus a `conflict` badge when another live fact of the same kind sits on the same refs — D-S5: both shown, neither wins), the TRUST badge — `observed`/`verified` success, `needs_review` warning, `stale` danger, `asserted` default — with the drift message under it when the read-time check demoted it (§6: "column X no longer exists"), the evidence summary (or "none — asserted"), and the provenance: when, through what (`mcp`/`session`/`api_key`), a `via another workspace` badge when the active workspace did not record it, and the source pipeline as a link ONLY when this workspace can read it (D-S9). Recording, verifying and retiring from the UI are round 2; the empty state says so and names the verbs that exist today (the `semantics_*` MCP tools).

**Rendered for.** The row's **Facts** button is every member's — it is `data-read`, not `data-verb`, because a viewer's datasources screen renders no verbs (§4.3e) and this button changes nothing, exactly like the LAKE row's **Tables** link. The dialog and the detail section render the same fragment (`partials/datasource-facts`) from the same model (`DatasourceFactsModel`), so the two cannot drift. An invisible datasource answers the inline refusal an unknown name gets (the §5.3 gate, before the service runs).

### 4.5c Datasource detail — a read-only Tables view (162, #156)

| Attribute | Value |
|---|---|
| URL | `GET /datasources/{name}` |
| Auth required | Yes — any member (`datasource.read`): a viewer may read, the same floor as the list |
| Purpose | Show every datasource's tables, and every discovered-schema dialect's columns, read-only |
| Design primitives | `.tpl-tree`, `.tpl-level`, `.tpl-folder`, `.tpl-leaf`, `.ds-badge`, `.ds-empty` — the 058/067 explorer's shared classes |
| JS | `template-explorer.js` (unchanged: the `<details>`/`<summary>` disclosure and keyboard nav it already drives) |
| htmx | Yes — one level per request, `hx-trigger="click once"`, `hx-target="next .tpl-level"`, `outerHTML` — the same lazy-per-level contract as the LAKE tree below |

Until this round the detail route served only LAKE; every other dialect's row had no Tables button and no detail page at all. Now every visible datasource has one, and the SAME page branches on dialect:

**LAKE** keeps its round-089 registry tree exactly as before: the dp-lake catalog's registered tables as a read-only namespace tree, one level per request, format and partition-column badges on each leaf, no selection and no form (registration is REST/MCP-only, R10). The section heading and the list's button both read **"Lake tables"**.

**Every other dialect** gets a NEW tree, live through [`SchemaIntrospector`](datasources.md#7a-schema-introspection) — the same read the REST `/api/v1/datasources/{name}/schemas|tables|columns` endpoints and the `datasources_get_schemas/get_tables/get_columns` MCP tools use, called directly rather than through those `author`-scoped surfaces (a viewer may read this screen). The section heading and the list's button both read **"Tables"**. Three levels, at most:

1. **Schemas**, as namespace folders — omitted entirely on a schemaless dialect (SQLite): the root level IS the tables level, the introspector's own "an empty schemas list is not an error" answer.
2. **Tables**, one schema's own (or, on a schemaless dialect, the whole datasource's), paged 25 at a time exactly like the list itself; each row shows its raw JDBC type (`TABLE`, `VIEW`, …) and expands into its own columns level. Unlike a LAKE namespace folder (which cannot be empty — it is derived from the child that produced it), a real schema can hold zero tables, and renders that as its own empty state.
3. **Columns** of one table — name, canonical type, nullability — unpaged, static rows with no action (a column has no verb on this screen, exactly like a LAKE leaf).

**A failed introspection** (an unreachable datasource, a driver that cannot report its current schema for an unqualified columns read) renders the catalogued §13 code and message INLINE, in the level that failed — never a blank pane, never a stack trace, and never a toast: a toast is for an ACTION that failed, and this is a read the person is already looking at.

**Rendered for.** Read-only by construction on every dialect: no `data-verb` anywhere in the tree, the same role floor as the list itself (a viewer may open it). The list's **Tables**/**Lake tables** button is `data-read`, exactly like the Facts button above. An invisible or unknown datasource name redirects to the listing, on every route this section adds.

### 4.6 Templates — the catalog page and the sidebar tree (#398)

| Attribute | Value |
|---|---|
| URL | `GET /templates` (`?q=` — the deep link every "find this template" link uses; the search covers id/path, display name, description and the dialect badge's wire value, the §5.1 Search rule) |
| Auth required | Yes (`read` to browse; `author` to create — the create button is role-gated per [§4.3e](#43e-role-visibility--every-verb-and-the-role-boolean-that-renders-it-114-normative)) |
| Purpose | The **catalog**: one flat, server-paged list of full paths — every template the caller may read when `q` is empty, or the matches of `q` — with the dialect and type filters, and (an author's) the create modal. Each row links into the template workspace (§4.7). The folder TREE is the sidebar's (§3.4) |
| Design primitives | `.ds-input`, `.ds-badge`, `.ds-button`, `.ds-empty`; the rows are `template-tree.css`'s `.tpl-results` / `a.tpl-result`; the create modal is `.u-backdrop` + `.ds-card` |
| JS | the create modal's lifecycle (`/js/template-create-modal.js`: open/close, inline refusal, dialect-conditional-on-type, the transform blocks); none of its own otherwise — the sidebar's `tree/sidebar.mjs` owns **Browse folders** (`data-nav-tree-reveal`), which opens the sidebar's Templates tree and puts focus in its search |
| htmx | the search input and the two filter selects re-fetch ONLY the list into its stable root `#template-list-wrapper` (`GET /partials/templates`, `outerHTML`); the sidebar uses REST tree/search (§3.4) |

**#398 (the #396 ruling's first reuse of the workspace pattern):** with the tree in the sidebar,
the page keeps the catalog the pipelines ruling retained (§4.3's owner ruling, read onto
templates) — a flat list, not a second tree and not a detail pane: a template is read,
rendered, evaluated and version-managed in ONE place, its workspace (§4.7). Since 067 template
names are **folder paths** ([Template Hierarchy §14](template-hierarchy-design.md)); the
folders are browsed in the sidebar.

- **Separate catalog and tree transport.** `GET /partials/templates` (`template.read`, the lens)
  serves the catalog's flat list. Legacy `scope=nav` and prefix fragments remain compatible;
  the sidebar uses the REST tree/search routes (§3.4, rest-api §24), with independent bounded
  continuation for complete immediate levels and name-search matches.
- **Every row is a link** to the canonical workspace `/templates/{name}` — no version: the
  release-first rule (§4.7) resolves it, and a draft is an explicit choice — through prepared
  main-content navigation, preserving the sidebar.
- **The badges a row carries** (nothing disappears): the type (`sql`/`html`/`jsonata`/`javascript`,
  rendered from `TemplateType`, so the filter and the create modal cannot drift from the enum),
  the dialect when the type carries one, the compact **draft** badge (versioning §7: unreleased
  edits stay visible) and the **needs review** marker (7d renders, 7e computes — a version citing
  a retired or superseded fact is MARKED on read, never blocked).
- **The filters (`dialect`, `type`) are the CATALOG's** (exact matches on the version row; the
  dialect match is repository-level `ILIKE`, so a `sqlite` query finds templates whose names
  never mention it). The sidebar's tree carries its search alone — the filters narrow the
  catalog's list and the tree's folders are the unfiltered lens's answer (§3.4's rule: the rail
  searches, the page filters).
- **No lifecycle verbs.** The catalog renders no verb: Release, Edit, the purges, discard and
  restore are the workspace's (§4.7, §4.3d). Create stays here (Q1(b), owner ruling
  2026-10-02 — the browser keeps authoring templates; the engineer's edit-and-test loop runs in
  the workspace).
- **Create** (`author`/`admin`) is the modal posting to `POST /partials/templates`, §5.1 Shape
  A: the success node closes the modal and the refreshed catalog rides along out-of-band with
  the toast. It keeps its **`type` selector** and makes `dialect` conditional — required for
  `sql`, disabled and absent for `html` and the transform types (the control is *disabled*, not
  merely hidden, so it does not post; the controller drops it either way and `chk_type_dialect`
  is the database's backstop). The name field's `pattern` and `maxlength` are **rendered from
  the server's own grammar** (`TemplateNameGrammar`) — never a regex retyped beside it; the
  server validates every write regardless and its rejection is the one that counts (§9.5).
  There is no rename affordance on this form or anywhere else: `name` is a create-time input,
  full stop.
- **A transform type (7d).** Choosing `jsonata` or `javascript` hides and disables `dialect`
  (as for `html`) and shows the **Contract / Invariants / Tests** field — three JSON textareas,
  disabled (so not posted) for any other type — prefilled with the design record's §2.2
  example. A transform carries its contract, invariants and tests inside its version, one
  empty-input case is mandatory, and the create runs the suite (7b's gate). The blocks bind
  through the same binder the workspace's Save uses (7b's strict deserializer — a typo is
  `template.contract_invalid` `unknown_field`, naming its block); a refusal lands in the
  modal's slot as `[pane] code — message`.
- **Empty and no-match are different answers:** an empty workspace (no `q`) says how templates
  arrive (the agent, or the create form, under a folder); a search that matches nothing offers
  Clear search; a lensed caller whose target cannot be read gets the promotion page's sentence
  (178).

#### 4.6a The sidebar tree (§3.4's Templates branch)

The Templates item links the catalog beside a separate toggle and a panel
(`#nav-tree-templates`) mounted with the reusable REST search-and-tree component (§3.4).
The root contains closed folders only; expansion exhausts immediate-child pages. Search
returns all matches and ancestors, initially expanded; clear closes every folder. A leaf
enters its workspace through prepared main-content navigation and is marked `aria-current`
when loaded. User width is authoritative; only panel visibility and preferred width persist.
Guards: `TemplateSidebarTreeBrowserTest`, `ReusableRestTreeBrowserTest`, `ShellRenderTest`.

#### 4.6b What moved where (the §4.6-explorer capability inventory)

Every capability the old two-pane explorer offered has a new home and an executed guard —
"nothing disappears" (#5):

| Capability (old explorer) | New home | Guard |
|---|---|---|
| Folder browse, one level per request, counts, paging, empty/overflow | the sidebar tree (§4.6a) | `TemplateSidebarTreeBrowserTest`, `TemplateTreeRenderTest`, `TemplatePartialControllerTest` |
| Search (flat full-path list) | the sidebar's search (nav) and the catalog (page) | `TemplateSidebarTreeBrowserTest`, `TemplateUiControllerTest` |
| Dialect/type filters | the catalog's controls (§4.6) | `TemplateUiControllerTest`, `TemplatesGoldenPathBrowserTest` |
| The detail pane's header verbs (Release, Discard, Purge draft/template) | the workspace header + Versions tab (§4.7) | `TemplateWorkspaceBrowserTest`, `LifecycleDialogBrowserTest` |
| "Open in editor" | the workspace IS the destination; the old route redirects | `TemplateEditorControllerTest`, `TemplateWorkspaceRouteTest` |
| Overview card (chips, description, References, created) | the workspace's Overview tab (§4.7) | `TemplateWorkspaceBrowserTest`, `TemplateWorkspaceControllerTest` |
| Used by card (pipelines, parameter sets, visualizations; both lens halves) | the workspace's Used by tab | `TemplateWorkspaceControllerTest`, `TemplateWorkspaceBrowserTest` |
| Versions table (status, in-use phrase, provenance, ⋯ verbs) | the workspace's Versions tab (house table) | `TemplateWorkspaceBrowserTest`, `TemplateLifecycleDialogControllerTest` |
| Source tab (the body, read-only) | the workspace's Source tab | `TemplateWorkspaceBrowserTest`, `ViewerEditorRenderTest` |
| Runs tab (derived runs, #275 visibility) | the workspace's Runs tab (lazy-once) | `TemplateWorkspaceBrowserTest`, `TemplatePartialControllerTest` |
| `needs_review` marker (leaf, row, detail) | catalog rows, tree leaves, the workspace chip | `TemplateTreeRenderTest`, `TemplateWorkspaceControllerTest` |
| The create modal | the catalog page (Q1(b), unchanged verbs) | `TemplateCreatePartialTest`, `TemplateCreateTransformTest` |

### 4.7 The Template Workspace

| Attribute | Value |
|---|---|
| URL | `GET /templates/{*name}?version={N}&tab={source\|overview\|render\|runs\|used-by\|versions}` — the name IS the id, the folder path; it travels in the path HERE only (the capture-everything variable; `TemplateWorkspaceRouteTest` pins the routing: a dotted, multi-segment name captures whole and the literal `/templates` and `/templates/editor` routes win). Everywhere else the name stays a query parameter (§9.6) |
| Auth required | Yes — the page floors at `read` (`template.read`, the pipelines workspace's rule): the screen's lowest role reads it. Edit and Save post to `template.update` routes; Preview posts to `template.render`; Run suite posts to `template.evaluate`; the lifecycle verbs are their own rows — [§4.3e](#43e-role-visibility--every-verb-and-the-role-boolean-that-renders-it-114-normative) |
| Purpose | THE place a template is read, rendered, evaluated and version-managed: version-explicit (the current release by default; an explicit version never falls back; a draft is explicit and visibly a draft), tabbed, with nothing of the old explorer or editor missing (§4.6b) |
| Design primitives | `.ds-card`, `.ds-button`, `.ds-badge`, `.ds-form` + `static/css/template-editor.css` (the source column and the transform face, unchanged) and `static/css/template-workspace.css` (the top bar, the selector, the tab strip — design tokens only) — both head-loaded (§3.0 is normative: no page template carries its own stylesheet link) |
| JS | `/js/template-editor/workspace.js` (the tab state machine — pure, node-tested; the tab strip and `?tab=` URL are its DOM glue), `/js/template-editor/lifecycle.js` (the Render tab's context rows, the Key/Value ⇄ JSON toggle, the preview call), `/js/template-transform-face.js` (the unsaved-changes marker, the result scroll), `/js/lifecycle-dialog.js` (§4.3d's dialogs into `#tx-dialog`); the preview output is highlighted with the shared dependency-free SQL tokenizer (032) — the editable textarea is deliberately plain |
| htmx | the version selector and every Open are FULL navigations (`hx-boost="false"` — the resolution is server-side, R5 by construction); the Runs tab lazy-loads once (`GET /partials/templates/runs`); **Edit** posts to `/partials/templates/editor/edit` (`#tpl-edit-refusal`, `innerHTML`; success answers `HX-Redirect` onto the workspace with the draft explicit); **Save draft** posts the face to `/partials/templates/transform-face/save`; **Preview** posts to `/partials/templates/render` |

**The resolution order (workspace spec §3.1, read onto a family without execution).** The
caller's lens narrows every read; the house 404 covers every absence the URL can name — an
unknown name, a foreign one, a lens-hidden one, and an explicit version that is absent or not
admitted (a draft asked through a promoter's lens is the same 404 an unknown number is, so a
status cannot be probed by number). Never clamp, never fall back:

1. an explicit admitted version — its own body;
2. the current RELEASE (`findLatest` resolves the served pointer to a live release row);
3. no current release and an admitted draft (the draft-only shape) — that draft, labelled;
4. otherwise the choose-a-version state over the admitted history, or the empty state when
   nothing is admitted — no Edit, no Save, no render affordance until a body is selected.

A malformed `version` is the house 400 (`PipelineWorkspaceModel.parseRequestedVersion`, the
pipelines twin's own function); an unknown `tab` resolves to Source.

**The tabs — the floor (#398; six, the pipelines workspace's shape, regrouped only where the
family differs):**

- **Source** (the default): the VIEWED version's body — the source column for `sql`/`html`
  (R5: a selected version is read-only, with its badge and release provenance; the editable
  textarea carries the WORKING draft only), or the **transform face** for `jsonata`/`javascript`
  ([4.7a](#47a-the-transform-face-7d-7--the-source-tab-of-a-transform)); beside it, the version's Imports table. **Edit** is
  the one way out of the read-only view (Q1(b): kept on the working draft's surface, as the
  editor page offered it): it copies the selected version into a new draft — or opens the draft
  that already exists, writing nothing — and lands on the workspace with the draft's version
  explicit. There is deliberately no save affordance on an sql/html column: the draft body is
  written through `PUT /api/v1/templates` (the id is in the body) or the face's Save.
- **Overview**: the chips (`v<n>`, draft/released, `type`, `dialect`, `engine`, the
  `needs_review` marker and the facts it cites), the description, the **References** reading —
  the identifiers the body interpolates, labelled as a derived scan and never as a declared
  contract (a template declares no parameters; only a pipeline does; a transform shows its
  contract's declared mode and inputs instead) — and the created provenance plus the
  current/draft pointers.
- **Render** (authors, non-transform): the Render Context panel — Key/Value rows and the JSON
  textarea over one underlying value — and the preview (`POST /partials/templates/render` of
  the STORED version the page is viewing; a blank render is "(empty output)"). A reader is not
  offered a control the server would refuse (143): the tab is not drawn, and a `tab=render`
  request resolves to Source before any render state is read. A transform has no Render tab
  (its preview would feed a Freemarker render a transform does not have).
- **Runs**: the derived fragment (`GET /partials/templates/runs`, lazy on the tab's first
  open) — the recent executions of the pipelines that pin this template. The derivation is
  stated on the fragment: an execution names a pipeline and a version, never the templates its
  nodes rendered, so this answers "has anything that uses this template run lately" and claims
  no more (the fan-out over pinning pipelines is capped, `USED_BY_FANOUT`); the visibility
  rules are the execution-history screen's (#275).
- **Used by**: every pin of any version, from any stored aggregate — pipelines, and since #320
  parameter sets and visualizations, each under its own lens (178b), from the same evidence
  the `template.in_use` refusal names. One row per PIN (two nodes pinning two versions is two
  facts); the header counts objects per kind ("2 pipelines · 1 parameter set", "nothing" when
  none pins it); the per-version "N uses" phrases stay on the version rows, lens-true (#340).
- **Versions**: the admitted history as the house table (§3.7) — version, status badge, created
  (ago · actor · via), the lens-true uses phrase, and the per-row ⋯ menu (Open = the canonical
  with `?version=` explicit; Release, Purge, Discard, Restore by the row's status AND the
  caller's role). **No Switch** — templates are pinned by exact version, so there is no served
  pointer a human would roll back (§4.6 records the absence; `template.switch_version` stays
  REST-only). The header carries the one destructive (102 §B.1): Purge template in the {D}
  shape, else Discard of the resolved release, else Purge draft — beside Release when a draft
  exists, all `canAuthor`.

**State and roles.** A tab change is in-page navigation only (`hidden` attributes; no fetch is
cancelled — a template does not execute); it rewrites `?tab=` with `history.replaceState` and
no pushed entry — the pipelines workspace's own contract (#349 deviation 3); Back/Forward
across in-page switches is #402's problem there too. A version switch is a FULL navigation
(there is no active run to protect, so nothing justifies in-page state): every tab's facts
re-resolve server-side, which is what keeps a stale Overview from lying about a switched
version. The sidebar tree's foreign-row guard (the `DP-Nav-Stamp` admission, §3.4) applies to
every workspace navigation as to any page. A promoter's lens: a hidden template, draft or
version answers the family's 404 — never a disabled row; no route returns a row the CSS hides.
Role-forbidden controls are absent (RoleVisibilityRenderTest); the fragments' endpoints
enforce their own permission and lens independently of the page's markup.

**Lifecycle dialogs (102, §4.3d).** Every dialog is addressed by NAME in the query; the
dialogs' GETs carry `from` (the workspace sends `editor`, the surface the POSTs serve), and
every success answers `HX-Redirect` onto the workspace's Versions tab with a layout flash
(`?tab=versions&ok=released|draft_purged|discarded|restored`) — or onto the catalog
(`?ok=template_purged`) when the verb removed the whole template (the tree must lose the
leaf). The pre-read guards are unchanged: a refused branch opens with no button; the POST
re-runs the guard; the typed confirm names the version (the entity purge's names the
template's NAME — §5.1's typed-confirm convention). The explorer's Shape A leg (the
detail-pane re-render and the `lifecycle-changed` badge rewrite) is gone with the pane; the
redirect's fresh page re-reads the tree under the same per-workspace state.

#### 4.7a The transform face (7d, #7) — the Source tab of a transform

Specified by the [transform-nodes design §9.3](superpowers/specs/2026-09-09-transform-nodes-design.md).
A `jsonata` / `javascript` template's Source tab is the **transform face**
(`partials/template-transform-face :: face`, keeping the column's stable `#template-source`
id): four panes — **Body** (labelled with its language), **Contract**, **Invariants**,
**Tests** — two by two above 1100px, one column below. Each is a plain `<textarea>` with the
create modal's mono class (no editor component, no grammar, no CDN — the record's §11 defers
a code editor). The three block panes are JSON, pretty-printed from the stored version on
every paint **losslessly**: an absent optional stays absent, but a `null` inside the data (a
test row's `"customer_id": null`, a JSON-null expected output) is kept — `TransformFaceModelTest`
pins the round trip. There is no Render tab and no Freemarker preview (a transform renders
nothing; `templates_render` refuses the type).

| Route | Permission | What it does |
|---|---|---|
| `GET /partials/templates/transform-face?name=&version=` | `template.read` | the face for a version — the same `TemplateSourceModel` rule as the page decides `readOnly` |
| `POST /partials/templates/transform-face/save` | `template.update` | **Save draft** — the four panes through 7b's write path: its strict deserializer, the `TemplateValidator` (the save gate runs the whole suite) and `TemplateDraftService` (the service `PUT /api/v1/templates` uses) under the draft's `body_hash` precondition |
| `POST /partials/templates/transform-face/run-suite` | `template.evaluate` | **Run suite** — the panes AS TYPED, nothing written |

**Editable only on the working DRAFT, for an author.** A viewer, a promoter, a RELEASED
working version and any older version picked in the selector render the four panes as
read-only `<pre>` blocks the size of the textareas (never disabled controls). An author on a
released version gets the existing **Edit**, which copies it — body AND the three blocks —
into a new draft (7d fixed `asDraft`, which carried the body alone and made the copy a
`blocks_missing` refusal); so Save always writes an existing draft in place and never creates
one behind the header's back.

**Save draft.** A success re-renders the face over the stored draft — the panes as stored, the
new hash, and a `Saved — draft vN now hashes to …` line. A refusal is retargeted into the
result region only (`HX-Retarget: #tf-result`), so the panes keep exactly what was typed, and
each refusal names its **pane** (a pane that is not JSON; 7b's `unknown_field` by its path;
`syntax_error`/`freemarker_forbidden` → Body; `contract_invalid` → Contract, or Tests for
`empty_case_missing`/`row_case_lists_table`/`expect_shape`; `invariant_invalid` → Invariants;
`test_failed` → Tests), 7b's code and its message and details; a stale hash is
`template.version.conflict` against the whole draft. Save needs a draft
(`template.version.not_draft` otherwise — Edit is the way in).

**Run suite evaluates the panes as typed (owner ruling 2026-09-25).** 7b's save gate runs the
whole suite and refuses any failing case, so a SAVED draft is always green — a run over the
stored version could never show red. Run suite therefore takes the four panes, unsaved,
through 7b's machinery in two passes under ONE `suite-timeout-seconds` budget: the
`TemplateValidator` (exactly what Save would run — a static refusal names its pane and the
suite does not run, as at save; its verdict is the result's "Save would accept / refuse with …"
line, so a green list and a refusing Save cannot both appear), then each case through
`TransformTestRunner.runCase` (the same runner, pool and per-case limits) for what the gate's
one line cannot say. The result list: `N of M cases pass`, and per case its verdict; a failing
case shows **the first difference's path** (`$.rejects[0].reason`), **expected and actual** as
text in `<pre>`, or the expected refusal and the code the run refused with; **every invariant**
with holds/is false and its message; a case past the deadline says `not run — the suite
timeout was reached`, never silently dropped. After a Run suite or a Save refusal the result
region is scrolled into view (`template-transform-face.js` — it lands under four tall panes).
The first keystroke in a pane shows an `unsaved changes` badge: Release publishes the last
SAVED draft, never what is typed.

Every user-supplied string — the panes, case and invariant names and messages, the diff sides,
refusal messages — renders through `th:text`, never `th:utext` (`TransformFaceRenderTest`
plants markup in each pane). Release and Purge draft are the §4.3d dialogs, unchanged.


### 4.8 Execution History

| Attribute | Value |
|---|---|
| URL | `GET /executions` |
| Auth required | Yes — `execution.read` (D11, 177): a viewer or author sees their OWN runs plus every SCHEDULED run of the workspace (#250 — R3, the same `findVisible` read the REST listing does), a workspace admin (`execution.read_all`) every run of the workspace (endpoint-key runs included), a **promoter is refused by role** (`auth.role_required`) and the rail does not draw the Executions item for one ([§4.3e](#43e-role-visibility--every-verb-and-the-role-boolean-that-renders-it-114-normative)) |
| Purpose | Browse past executions, filter by pipeline/status/date |
| Design primitives | the data table (§3.7), `.ds-badge`, `.ds-input` |
| JS | None |
| htmx | Yes — filters (pipeline, status, date range), pagination (`hx-get="/partials/executions"` into `#execution-table`, `innerHTML`, with `hx-include="#execution-filters"` re-sending the filter form by id; the pager offsets are server-rendered into `hx-vals` via `th:attr` literal substitution) |

Content: table of executions (pipeline **display name** — machine path on hover, T114 —, version **with a `DRAFT` label when that version was a draft when it ran** (versioning §8's derivation, `data-draft-run` for a test to read), status badge, triggered_by, triggered_via badge, started_at, duration). The label matters more since D55: a v1 run is routinely a draft run, so the number alone would not say whether a result came from reviewed content. The execution detail screen carries the same badge beside its `v1`. Clickable → execution detail. This screen deliberately keeps its own `#execution-table` / `innerHTML` / `hx-include="#execution-filters"` contract rather than adopting the §5 outerHTML one — it satisfies every §5.1 guarantee (stable target, controls outside the fragment, spinner, toasts), and the pager's `hx-vals` offsets are server-rendered via `th:attr="hx-vals=|{...}|"` (a plain-attribute `[[...]]` inlining reaches the browser unprocessed — Thymeleaf processes inlining in text nodes, not attribute values).

### 4.9 Execution Detail

| Attribute | Value |
|---|---|
| URL | `GET /executions/{execution_id}` |
| Auth required | Yes — `execution.read` + ownership of the execution (D11: own unless `execution.read_all`; another member's run is the 404, never 403; a promoter is refused by role). Cancelling a running execution is `execution.cancel` (`canExecute`; another member's run needs `execution.cancel_all`) |
| Purpose | View execution metadata, node stats, result, replay events |
| Design primitives | `.ds-card`, the data table (§3.7), `.ds-badge`, `.ds-code-block` |
| JS | Light — result preview pagination if large |
| htmx | Yes — result pagination (`hx-get="/partials/executions/{id}/result?offset=..."`), cancel (`hx-delete="/partials/executions/{id}"` — success is §5.1 Shape A: the cancelled-state badge swap plus an OOB toast; the 403/404/409 refusals are `ResponseStatusException`s answered with full error pages by `UiExceptionHandler` — a recorded gap for partial requests, not a toast) |

Content:
- **Header**: pipeline name + version, status badge, timing, triggered_by + via.
- **Node operations table** (149): what each node DID, from the durable `node_progress` record ([REST API §6.4.9](rest-api.md#649-node_progress)) — operation, real destination, last observed state, fetched/written counts, elapsed, the commit badge (`committed` — also on a failed node whose commit was confirmed / `rolled back` — a confirmed undo / `not committed` / **`not observed`** — a terminal sample without commit evidence, e.g. a driver that never confirmed the commit before the deadline) and the per-state time share; an operation never observed to end says "(not observed to end)" and carries no commit badge; beyond event retention the card says "No node operations recorded". `partials/execution-node-operations`, derived by `NodeOperationHistory` (last sample per node).
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

### 4.10 API Keys — the pointer (091; repointed 179)

**This screen is a pointer.** 179 split the keys in two (D16/D17) and pointed here at the
top-bar chip; keys v2 (233, A15) retired the chip and the login mint, so since 2026-09-25 every
key — MCP keys with the role the creator may give, endpoint and server keys — is created on the
Keys page (§4.19), its plaintext shown once at creation, revoked there to rotate. This screen
says exactly that, reads no keys at all, and links to `/api-keys` only for the roles that reach
it.

| Attribute | Value |
|---|---|
| URL | `GET /settings/api-keys` |
| Auth required | Yes — any authenticated principal (the key VERBS live on §4.19 and §4.3e — [§4.3e](#43e-role-visibility--every-verb-and-the-role-boolean-that-renders-it-114-normative)) |
| Purpose | Point at the Keys page (§4.19) — every key is created there since keys v2 retired the top-bar chip |
| Design primitives | `.ds-card`, `.app-empty`, `.ds-button` |
| JS | None |
| htmx | No |

**Why the route survives.** `AppNav`'s off-rail breadcrumb table and every bookmark point here;
answering them with a 404 to save one template would be a worse deal than rendering a sentence
and the links.

### 4.11 User Settings

**Amended 079 §D** — a two-column card grid at ≥1100px (Profile, Appearance, Session,
Password, API), one column below; `.app-reading` is gone from this screen (§3.1). The API card's
**Role** row (#215 — it was a Scopes row until scopes were removed) is one chip: the session's
role in the active workspace. Appearance gains a Mode row (Light / Dark /
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
| Auth required | Yes — any signed-in person, own profile only (no permission requirement) |
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
| Design primitives | the data table (§3.7), `.ds-badge`, `.ds-button` |
| JS | None |
| htmx | Yes — search/pagination (`hx-get="/partials/admin/users"`), activate/deactivate and admin grant/revoke (`hx-patch="/partials/admin/users/{id}/{action}"`, row-level swap), identity reset (#187, its own literal route `hx-patch="/partials/admin/users/{id}/identity-reset"` — `user.identity_reset`, [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative)) |

Content: table of all users (email, display_name, `is_active` and `is_admin` as `.ds-badge` variants — success/danger for status, primary/default for role — local-access status). Admin can toggle `is_active` and `is_admin` per user, and — for local accounts ([Auth §5A.1](auth.md#5a1-accounts)) — create local users, reset passwords, disable local access, and clear lockouts.

- Partials delegate to [REST §16.3](rest-api.md#163-user-administration-super-admin--usermanage) (`activate`, `deactivate`, `grant-admin`, `revoke-admin`), which writes the `auth.user.*` audit events. Every row action keeps its `#user-row-{id}` outerHTML swap and reports the outcome as a success toast naming the action and the user's email (§5.1 Shape A).
- **Create local user** (rendered only when local accounts are enabled): email + optional display name, plus the OPTIONAL workspace and its role (113 §B.3, [Auth §4.6](auth.md#46-invitations)) — a workspace name field and the same single-select role dropdown the workspaces page uses (D22: `viewer` / `author` / `promoter` / `workspace admin`). When the workspace is named, the new account's membership is written in the same act: no invitation is needed because the user row exists by the time the membership is written ([Auth §4.6](auth.md#46-invitations) rule 1), and a workspace the super admin cannot add to is refused as a toast note while the USER ROW still stands (the fix is the workspaces screen). The server generates a random one-time password shown to the admin exactly once (out-of-band notice — PERSISTENT and inline per §5.1's hard rule; the success toast only points at it) with `must_change_password = TRUE` — there is no email flow, so the admin conveys it out-of-band ([Auth §5A.1](auth.md#5a1-accounts)). A taken email answers `409` and an invalid email `400`, both as danger toasts (§5.1 Shape C) — before the toast bridge existed these refusals were invisible: htmx never swapped the 4xx bodies and the screen had no error listener.
- **Reset PW** issues a new one-time password under the same rules (and clears any lockout); **Disable local** clears the hash (account becomes OIDC-only); **Unlock** clears the lockout only. The `Local` column shows `local`, `local · locked`, or `—` (OIDC-only).
- **Reset identity** (#187) — the explicit answer to a refused login with `?error=identity_mismatch`: returns the row to the bootstrap placeholder so the NEXT OIDC sign-in with that email claims it (audited `auth.user.identity_reset`, [Auth §4.2](auth.md#42-user-provisioning)). Liveness, admin flag, password and memberships are untouched — a deactivated user stays deactivated. Never needed after a mere role change; only when an account must move to a different sign-in identity.
- **When mail is configured** (137, [Configuration §3.27](configuration.md#327-mail), [Auth §5A.8](auth.md#5a8-mail-the-welcome-mail-and-the-new-user-notice)) the create and reset responses **do not show the one-time password** — it was emailed to the user, and two copies of a credential are one too many. The same persistent `#admin-notice` box says *Emailed to \<address\> — sending…* and polls its own outcome (`hx-get="/partials/admin/users/{id}/mail/{kind}?act={act}"`, every 2 s, swapping itself) until the claim row is terminal: *sent* (with the first-login sentence) or *failed: \<error\>* (with "reset the password to send again"). A terminal render carries no `hx-get`. The toast says the password was emailed and, as before, only points at the notice. `AdminUsersPartialController` reads `MailProperties.enabled` — one Thymeleaf branch in `partials/admin-user-saved`; with mail off the password renders exactly as above.
- Deactivation copy states the effect window: existing JWTs and API keys stop working within the liveness-cache TTL (~60s — `datapipelines.auth.api-keys.cache-ttl-seconds`, default 60), not instantly and not at JWT expiry. Since 180 the refusal is `auth.principal_deactivated` on every API surface, a page navigation lands on `/login?error=inactive`, and the promotion peer folds a deactivated owner into its one answer ([Auth §11A.3](auth.md#11a3-deactivation)). Nothing is revoked: Activate restores the same sessions and keys.
- There are no scopes ([Auth §7.5](auth.md#75-key-roles)): what a person may do is their membership's role in each workspace, and the "grant admin" toggle is the one INSTANCE authority (`users.is_admin`, super admin) — there is no per-user permission editor to build. The list shows people only (`kind = 'human'`, [Auth §4.7](auth.md#47-key-identities)): a key's identity is managed through its key, and every row action answers 404 for one.

### 4.13 Workspaces (workspaces design §9; members and deactivation rewritten by 114)

| Attribute | Value |
|---|---|
| URL | `GET /workspaces` |
| Auth required | Yes — **a workspace admin's or a super admin's page** (D13, `workspace.read` → workspace admin; [§4.3e](#43e-role-visibility--every-verb-and-the-role-boolean-that-renders-it-114-normative), [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative)). A viewer, author or promoter is refused by role (`auth.role_required`) and the rail does not draw the Workspaces item for them; what every member keeps is the **switcher** in the chrome (§3.4), whose `POST /workspace/switch` is its own row (`workspace.switch`, every role) and re-issues the session token. A principal with NO active workspace still reaches the page — it renders the no-workspace state below, the one screen that explains their situation. Per-verb on the page: members and the display name need `canAdminWorkspace` **in the ACTIVE workspace**; create, deactivate, reactivate and delete need `isSuperAdmin`. The members section carries `id="workspace-members"` (143): it is where the rail's Admin item lands a workspace admin |
| Purpose | Administer the workspaces you administer: their members and roles, the display name, and — for a super admin — create, deactivate, reactivate and delete |
| Design primitives | the data table (§3.7), `.ds-badge`, `.ds-button`, `.ds-input`, `.ds-card`, `.ds-empty`, `.app-note` |
| JS | One `onchange` submit on the rail switcher (`<noscript>` fallback button included). Nothing else — the flags-era "tick author when admin is ticked" listener went with the checkboxes (177) |
| htmx | **One partial** (177, D22): the member row's role Save posts `POST /partials/workspaces/{name}/members/{userId}/role` and swaps the row (`hx-target="closest tr"`, `outerHTML`) with a success toast out-of-band; a refusal is a toast alone (`HX-Retarget: #toast`) and the row keeps the selection the server still holds. Every other verb is a plain CSRF-protected form post with `redirect:` outcomes (`?ok=`/`?error=` query state), rendered into the layout's `#toast` stack |

Content, in order:

**A create form**, super admins only (D-R11 removed the provisioning modes, and with them
`workspace.creation_forbidden` and the joinable list; 114 removed the stale "provisioning mode on
this server" footnote and the dead join section that survived them). It navigates in FULL —
`hx-boost="false"`, the switch form's rule — because creating a workspace changes the SHELL: the
rail's switcher is filled on every full render, and a boosted swap (which replaces only `#app-main`)
left the new workspace out of the switcher until a manual reload (#170).

**Your workspaces** — name, the role you hold there, the active marker, and the verbs. Every
workspace on the instance for a super admin, with a `inactive` badge and a **Reactivate** verb on
the deactivated ones, because this is the screen that brings one back; a member never sees a
deactivated workspace at all ([Auth §11A.3](auth.md#11a3-deactivation) — deactivation must not be
a signal anybody can read). **Deactivate**, **Reactivate** and **Delete** are super-admin verbs
(`workspace.lifecycle`); deactivation purges nothing, ever, and Reactivate is one row away,
which is why neither carries a typed confirm. All three navigate in FULL — `hx-boost="false"`
(#256, the create form's rule): each success changes the SHELL (the switcher's options come
from `workspaceOptions`, filtered to ACTIVE workspaces, on every full render), and a boosted
swap replaced only `#app-main`, so a deactivated workspace stayed selectable and a reactivated
one missing until a manual reload. `WorkspacesCreateBrowserTest` extends #170's no-reload
assertion to each verb: deactivate → the option is absent, reactivate → present, delete →
absent. The role label is the membership's ONE role
(`viewer` / `author` / `promoter` / `workspace admin`) printed by the same `RoleModel.labelOf` the
shell badge and the members table use — the row's own word ([`WorkspaceRole`](enums.md#8c-workspacerole--the-one-role-a-membership-holds), D1).

**The degraded reads (#336).** The members listing and the key-owner read can fail — a metadata
store refusal — and a failed read is a degraded state, distinct from the empty one: the members
section renders a `ds-empty` notice, **"Members could not be loaded"** (`data-members-degraded`)
or **"Key owners could not be loaded"** (`data-key-owners-degraded`), fixed text only, the rest of
the page and the members table unchanged beneath it. The failure's evidence is the controller's
log line (class + SQLState), never the page. A catalogued REFUSAL (`AuthException` — the role and
visibility verdicts) is not a failure: it renders exactly as before, the empty state, because the
role model above has already filtered what this caller sees.

**Members of the ACTIVE workspace** (114 §C.1; the row rewritten by 177, D22; the key state and
verb by #200) — one section: name/email plus the member's KEY state ("has a key" / "no key" —
the login-minted credential's existence, never an id or prefix), **one role dropdown** per member
(`<select name="role">`, the four workspace roles, the member's current role selected) with a Save,
**Revoke key** (only when a live key exists — a button over "no key" would revoke nothing and say
otherwise; the member's session is untouched and their next sign-in mints fresh), and Remove. Each
row is the `partials/workspace-member-row` fragment — the same markup the Save's response swaps back
in, so the page and the response cannot drift. The add form at the foot carries the same dropdown
(defaulting to `viewer`), so a member arrives with the role the operator meant: before 114 it
posted `email` only and every member added from the UI was a viewer. Every verb goes through the SAME
`WorkspaceService` methods the REST surface calls (`addMember`, `setMemberRole`, `revokeMemberKey`,
`removeMember`) — there is no second code path, so the last-admin rule is enforced once.

- Changing a role is a **REPLACE** of one value: the dropdown shows the role the server holds, Save
  posts the selected one, and the swapped-in row shows what the database now holds. A role outside
  the four (a hand-edited form) is refused before the service — a `400` toast on the partial,
  `?error=unknown_role` on the add form — never stored.
- **Nobody administers their own row** (#208, owner ruling 2026-09-22): the signed-in member's row draws no Save, Revoke key or Remove, and the service refuses the three anyway with `409 workspace.self_membership` (banner / partial toast: "Ask another workspace admin"). **A super admin's row reads "super admin"** and carries no role dropdown — the instance authority outranks whatever membership role the row holds.
- **The last admin cannot be demoted or removed** — `409 workspace.last_admin`, rendered as a §5.1
  error toast that names the remedy ("This is the last workspace admin. Give someone else the
  workspace admin role first."). A workspace nobody administers cannot be repaired from inside it;
  the refused row keeps its selection.
- **Only the ACTIVE workspace gets a member table.** `ScopeInterceptor` judges
  `workspace.members.manage` against `principal.workspace`, never the workspace named in the path,
  so an admin of X working in Y had every member form for X rendered and every one of them refused
  — invisibly, because the interceptor answers before the handler and htmx does not swap a 4xx.
  The other administered workspaces are named in an `.app-note` with the Switch that reaches them.
- **Pending invitations** (113, wired at the 113/114 merge): ghost rows under the members
  table — email, an `invited` badge, the role the invitation carries, "Becomes a member at
  first sign-in", and Revoke (`POST /workspaces/{name}/invitations/revoke`, email as a form
  field, `workspace.members.manage` — the same guard as the member rows). Never mixed into the
  member rows. The add form's outcome toast distinguishes `member_added` from `member_invited`
  (no account with that email yet), so "added" is never said of someone who cannot sign in.

The **switcher in the rail** (§3.4) drives the active workspace and carries the role badge; the
screen's Switch buttons POST the same `/workspace/switch`. Expected refusals and successes alike
render as §5.1 toasts; the generic error page is reserved for the unexpected (§6).

**The no-workspace state** — a principal with zero ACTIVE memberships (never added, removed from
the last one, or every workspace they belong to deactivated) gets `workspaces/none` instead of an
empty list: what happened, who to ask, and — super admins only — the create form. It is
deliberately not an error page: nothing failed, and `error/403` would name the wrong problem.
Reachable at the 113/114 merge: `ScopeMatrix.allowed` lets a SESSION through `workspace.read`
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
| Purpose | The product marketing page, written for the BUYER (115): the question, the reviewed answer, and where it goes — hero with the worked example, the seven capability sections with their captures (175), the video slot, benefits, learned context, review, today/planned, FAQ |
| Design primitives | The vendored design system via `/vendor/design-system/**` (the same copy the app serves; the retired `website/` directory carried a second vendored copy) |
| JS | `static/site/js/site.js` — theme toggle + copy-to-clipboard only; fully readable without it |
| htmx | No |

**The screenshots are a command, not a folder** (070 §C, extended 093, 175). `./gradlew siteShots -PshotsUrl=… -PshotsEmail=… -PshotsPassword=…` drives a REAL demo deployment with Playwright and overwrites `static/site/img/**`; `-PshotsHeroOut=<dir>` additionally writes the hero's poster (2400 px wide, device scale 2) and OG (1200x630) copies somewhere outside the app. Three sets: `site` (the default — light theme asserted), `app` (every screen at two widths in both themes), and `home` (175 — the home page's seven capability figures at DPR 2, `-PshotsTheme=dark|light` driven through the settings select and restored afterwards, read-only so it can run against the live deployment; every non-GET request is logged). It refuses rather than photographs: a promotion screen with no usable target, a full-length key still on screen, and — 093 — a pipeline-editor canvas whose node surface resolved to the brand colour are all SKIPPED with the reason printed, because the site's own rule is that a screen which renders broken is reported, not photographed.

**The page shows the captures themselves (169, reshaped 175).** The homepage tells the product story as one section per core capability — connect, agent-authored pipelines, no warehouse, the run's result, the release, the API, workspaces (`#cap-connect` … `#cap-workspaces`) — each with ONE full-width figure from the driver's `home` set: 1440×900 at `deviceScaleFactor = 2` (2880×1800 PNGs, `home-*.png`), photographed in the app's DARK theme on the light page. Three of the seven are the LIVE demo deployment read-only (`home-connect`, `home-agent-builds`, `home-no-warehouse` — the set never executes, publishes, mints, probes or creates there, and logs every non-GET request); `home-run-result.png`, `home-serve-api.png` and `home-workspaces.png` need state a read-only account has no business manufacturing on prod (a fresh SUCCESS execution, published endpoint + keys, two memberships), so they come from a throwaway demo stack — `home-review-release.png` too, whose release dialog is seeded, photographed and never submitted. `/how-it-works#application` keeps the three reserved frames (`#shot-pipeline` → `editor-hero.png`, `#shot-results` → `execution-result.png`, `#shot-release` → `release-step.png`) with the two-column gallery of every remaining `site`-set shot below. Every `<img>` names a packaged file under `/site/img/`, declares its intrinsic size, describes what is on screen in its alt, and lazy-loads except the first capability figure; `SiteRenderTest`'s image guard holds all of it and `SiteSeoMetaTest`'s alt sweep reads the render. Re-running the siteShots command — `-PshotsSet=home -PshotsTheme=dark -PshotsOnly=<names>` when a round only re-took a few — refreshes the page; a hand-taken or retouched picture has no path in.

Content: a single static page (`templates/site/index.html` + `static/site/**`), served by the app since v1.15 — the marketing site and the product are one deployment (owner decision 2026-08-31). The only dynamic fact, the MCP tool count, is baked at render time from `McpToolCatalog` (a compile-time constant — no DB access on any public route). Defence is `Cache-Control: public` on both `/` and `/site/**`, deliberately NOT the login rate limiter (OPEN-ITEMS T46: its `remoteAddr` key is the load balancer's address behind the documented deployment, so a limiter would let one client 429 the homepage). Signed-in users hitting `/` get the marketing page too — no auto-redirect; the dashboard is one nav link away at `/dashboard`. Emergency static fallback: `./gradlew :modules:web:websiteExport` renders the same template with facts baked ([Deployment §6.7](deployment.md#67-marketing-site--in-product-docs)).

**Since 073 the public site is fifteen pages, not one** (089 added `/dp-lake`). The homepage keeps its `<h1>`; its `<title>`, meta description, canonical and `og:`/`twitter:` tags now come from the `SitePages` registry, which is also what `/sitemap.xml` lists and what the SEO guards measure — one set of strings, three consumers. Each additional page is a Thymeleaf template under `templates/site/`, served by `SitePagesController` in the same shape as `/`: GET-only, anonymous, constant content, short public cache window, no datastore.

**Since 115 the homepage speaks to the buyer, and the engineering has its own page.** The home page's reader is a founder or product owner whose customers are asking to see their data, so it sells the outcome — the sentence, the result table, the URL the app calls — in a fixed section order: hero → before/after → the artifact → verified-by-you → what-you-get (today/planned badges) → who-it's-for → demo → open source → roadmap → FAQ (eight buyer questions). Two house rules hold above the fold: the buyer's vocabulary only — never pipeline, DAG, federated, Iceberg, DuckDB, Parquet, MCP, JDBC or staging (`SiteBuyerLanguageTest` holds the fold from `<main` to the end of `#artifact` to that, and asserts the words landed on `/how-it-works` instead — the mechanism moved, nothing was hidden) — and show the artifact, not the process. Unshipped features, including embedded dashboards, are labelled planned without release dates across the marketing site (#120). The roadmap pages retain their last-updated datelines for content freshness; there is no mocked dashboard screenshot. The engineering sections the homepage used to carry (engine strip, "What's in the box", the security teaser) live on `/how-it-works` under the tutorial (174: the agent loop the page used to open with is retired — the executed eight-step tutorial IS the loop); its FAQ is `SiteFaqsHub.HOW_IT_WORKS`; the home FAQ is now `SiteFaqs.HOME`'s eight buyer questions, which also open `/faq`.

**Since 124 every number the site states is derived, never typed.** `SiteFacts` (`ui/site/SiteFacts.kt`) is built once per request from the code that owns each fact — the engine count and the copy's engine list from `Dialect.entries` (display names from the one `SitePages.ENGINES` map), the tool counts from `McpToolCatalog`, the calculator kinds from `CalculatorRegistry`, the API-key kinds from `ApiKeyKind`, the learned-fact kinds and scopes from the semantics enums, and the demo families' engine counts from the vendored manifests via the handler — and `PublicPage.render` exposes it to every template as `facts`; the titles, descriptions and FAQ answers in `SitePages`/`SiteFaqs` are string templates over it, with spelled-out numbers from one `numberWord()` helper. The ruling behind it (owner, 2026-09-13): **eight engines, not six** — H2 is a legitimate engine an engineer uses like Postgres or MySQL and LAKE is dp-lake, so every dialect has its `/mcp-server/{engine}` page and `SiteEngineFactsGuardTest` holds the page set EQUAL to `Dialect.entries` (a ninth dialect cannot ship without a page, a page cannot outlive its dialect); tempdb is implicit and is never counted or listed. The guard that keeps it true is `SiteHandTypedCountsGuardTest`: it sweeps every site template and every Kotlin file under `ui/site/` and fails the build on a spelled-out or numeric count adjacent to engines, tools, calculators, kinds, node types or error codes outside a `${facts…}` expression — nothing is allowlisted, so a count that is not a fact of the code is removed from the copy (red at base on 40 hits, 11 of them the "six engines"/"eight engines" disagreement itself).

| URL | Template | What it is |
|---|---|---|
| `/mcp-server-for-sql-databases` | `site/pillar.html` | The cluster pillar: the credential problem in the searcher's words, the engines, the governance guarantees, the full MCP tool list |
| `/mcp-server/{engine}` — one per dialect | `site/engine.html` | ONE template over the `SitePages.ENGINES` rows: dialect, driver, license, whether the driver is in the published image, client config, the seeded pipeline that reads that engine |
| `/add-mcp-server-to-claude-code` | `site/add-mcp-server.html` | The four-step client setup — run a server, mint a scoped key, paste one config block, check the tool list — with the 401/403 troubleshooting table |
| `/ai-data-pipeline` | `site/ai-data-pipeline.html` | What an agent-authored pipeline is here: real schemas in, a versioned JSON artifact out, run governed |
| `/text-to-sql-agent` | `site/text-to-sql-agent.html` | The after-state a text-to-SQL tool leaves out. States plainly that we do not generate SQL |
| `/compare/airflow`, `/compare/dbt` | `site/compare-airflow.html`, `site/compare-dbt.html` | Honest comparisons, each leading with "use them instead when…" |
| `/compare/dagster-vs-airflow` | `site/compare-dagster-vs-airflow.html` | An EDITORIAL comparison (173 §B): what Dagster, Airflow and Prefect are for, in their own documentation's words (every product claim cites its source URL in the claim comment), when a team chooses each, and datapipelines named once as the thing that composes with any of them. `SiteFaqsCluster.COMPARE_DAGSTER_AIRFLOW` |
| `/federated-query` | `site/federated-query.html` | The staging join, with `nyc/mobility/revenue_by_borough`'s real SQL and the limits of an in-memory staging database |
| `/dp-lake` | `site/dp-lake.html` | dp-lake (089): Parquet and Iceberg on S3 read in place, dp-catalog, partition pruning, the four-engine showcase pipeline, and the honest limits — egress, the Iceberg metadata-file rule, no scheduler |
| `/how-it-works` | `site/how-it-works.html` | The tutorial page (174): `/how-it-works` is the product's step-by-step guide — run it locally, mint the agent's key, connect Claude Code / Cursor / Copilot over MCP (the client blocks are one fragment shared with `/add-mcp-server-to-claude-code`, so the two pages cannot drift), watch the agent author and execute a federated pipeline over the demo data, release it, publish the endpoint, scope a key to it and call it — every command, output block and capture executed on a real deployment before it was written. 169's `#application` gallery stays; the 115 engineering depth (mechanism, engine strip, "What's in the box", security teaser) follows under its own opener — where every term the buyer's home page keeps below the fold now lives — and `SiteFaqsHub.HOW_IT_WORKS` closes it. The agent-loop section the depth used to open with is retired: the executed tutorial IS the loop |
| `/demo-data` | `site/demo-data.html` | The demo data, table by table (116): what the three published sample-data families hold, where each came from, what to ask it, and the licence each ships under |
| `/pricing` | `site/pricing.html` | The pricing page (119 §C.4): there is no price — AGPL-3.0, self-hosted, every feature; what free costs you honestly; the no-paid-tier promise under the roadmap's dated `status` markup (the freshness guard covers it, so the page carries a `data-roadmap-updated` dateline); AGPL in two sentences; the owner's contact offer from `CONTACT_EMAIL`; `SiteFaqs.PRICING` |
| `/semantic-layer` | `site/semantic-layer.html` | The learned semantic layer (119 §B): the category term answered honestly — the learn-with-evidence loop, the two scopes, the trust ladder, drift at read, today/next in the dated `status` markup, the dbt/Cube comparison, the three `semantics_*` tools and the twelve-kind table rendered verbatim from `docs/enums.md` §19 (`SiteSemanticLayerGuardsTest` holds page and doc to one truth), with the real `facts[]` wire example quoted from 118's E2E capture; `SiteFaqs.SEMANTIC_LAYER` |

Chrome is shared: `templates/site/_layout.html` owns the `<head>`, the header and the footer site map, and the homepage uses its header/footer fragments — two copies of a nav is how one of them stops linking a page that exists. The footer's engine column is built from the registry, so a new engine page is linked from every page the moment its row lands.

**One vertical rhythm, tokened (119 §A).** Measured on 2026-09-11 at 1440×1000, the gap between a section's `h2` and its first block was a different number in every section (0, 16, 20, 24 and 32px). The rule now: ONE token, `--site-gap-head` (20px phone, 24px from 48rem), applied by ONE selector on the section layout — `.section .container > h2 + *`, with `.faq-group > h2 + *` and `.faq-block > h2 + *` for the two structures that wrap a section h2 one level deeper — and never by a block's own margin. A `.section-lede` deliberately keeps 16px under its h2 (the `h2 + .section-lede` override) and passes the head gap on to ITS first sibling (`.section-lede + *`). Cards: 24px padding and 24px grid gutters from 64rem (20/16 below); internal rhythm set once on `.card` (title → body 8px flex gap, body → a trailing spec link 12px); `align-items: start` inside `.feature-group` so a card is as tall as its text — and a card body is capped at **90 words** (`SiteCardBudgetTest`; the surplus moves into a "read the spec →" link to the cited docs section). The hero shot clamps to `--site-hero-max` (`min(44vh, 620px)` desktop) so the proof strip starts inside the fold; on a phone the hero stacks copy → numbers → picture. Group labels (`.group-title`) take the kicker treatment with `--space-8` above and `--space-4` below, no rule under them. All of it is held in a real browser by `SiteRhythmBrowserTest` (computed styles on `/`, `/how-it-works`, `/demo-data`, `/faq` at 1440×1000 and 390×844: head gaps, card padding, row-stretch, fold, and `scrollWidth == clientWidth`).

**The demo-data page's tables are generated from vendored manifests (116), pinned to the deploy versions.** The three published `manifest.json` files — one per sample-data family, at exactly the version `deploy/env/defaults.env` pins (`SAMPLE_VERSION`, `SAMPLE_TRADE_VERSION`, `SAMPLE_LAKE_VERSION`) — are vendored at `modules/web/src/main/resources/site/demo/`, parsed once at startup by `SiteDemoData` (missing or unparsable fails fast, the `DocsCatalog` posture), and rendered as the family tables: engine, table, row count, sizes, object paths and the licence stamps are model fields, never typed rows. The prose adds only what no manifest carries — grain and key columns from the families' DDL files, windows from the locks' param lines, and the attribution sentences from the manifests' provenance rows. `SiteDemoDataGuardsTest` holds each vendored manifest to its deploy pin, holds the rendered rows to exact completeness against the manifests, and proves the row counts are bound (not literal); a repack that bumps a pin and forgets the page fails the build. A family whose manifest carries no licence stamp renders a visible "not yet verified" status and makes no licence claim.

**Guards** (all in `modules/web/src/test/.../ui/site/`): `SiteSeoMetaTest` renders every registry page through its real controller and asserts title/description/canonical/`og:`, the 70- and 155-character display limits, one `<h1>` with no skipped levels, and a descriptive `alt` on every image; `SiteClaimCitationTest` extends the 024b claim rule to every site template; `SiteBuyerLanguageTest` (115) holds the home page's fold to buyer vocabulary and pins the moved vocabulary on `/how-it-works`; `SiteEngineFactsGuardTest` parses [Datasources §4.1](datasources.md#41-dialect-catalog) and [Deployment §3.5](deployment.md#35-jdbc-driver-matrix-what-ships-in-the-image), holds the engine-page set equal to `Dialect.entries`, and asserts every row's driver, license and bundled flag against them; `SiteHandTypedCountsGuardTest` (124) fails the build on a hand-typed count anywhere in site copy; `SiteJsonLdTest` parses the homepage's three `application/ld+json` blocks; `SitemapControllerTest` asserts the sitemap against the registry and the packaged docs; `SiteKeywordCoverageTest` (173) pins every measured phrase to its page and surface; `LlmsTextTest` holds `/llms.txt` to the sitemap; the E2E sweep fetches every `<loc>` anonymously.

**The nav is host-aware (119 §C.1).** The header's last item is `/login` on every deployment, but its label is computed from the request: on the public origin (`SITE_ORIGIN`, the same constant the canonical tag uses) it reads **Try the live demo**; on a customer's own deployment it reads **Sign in** (`SiteOriginAdvice` live; `SitePageRenderer`'s serverName offline — the static export passes the public host, so the exported site reads "Try the live demo"). The GitHub link wears a static star count baked from the build-time `GITHUB_STARS` constant (refreshed by hand at release; no visitor's browser fetches api.github.com), an `AGPL-3.0` chip beside it links `/pricing`, and `SiteOpenSourceSignalsTest` pins both host branches, the badge, and the one-contact-address rule (`CONTACT_EMAIL`; a mailto sweep fails any page carrying a second address).

**Every measured search phrase has a named surface, and a guard keeps it there (173).** The keyword study (private store `notes/2026-09-04-seo-keywords.md` + addendum 4) measured 64 phrases; `SiteKeywordCoverageTest` is that plan as a table — phrase → registry page → surface (`TITLE`, `H1`, `H2` or a `BODY` sentence inside `<main>`) — matched exactly the way the store's `sweep.py` matches (lower-case, dash variants folded, visible text only). A rewrite that drops a heading or trims the sentence fails naming the phrase, the page and the surface. The renames it needed on eleven pages are recorded as retirements in `site-baseline-outline.json` with their phrase, and every registry route beyond the 59-route baseline is listed under the fixture's `additions` with its reason. The engine pages carry the searcher's other name for an engine ("PostgreSQL", "MSSQL") as a `searchAliases` row field rendered into one opening sentence — never a template `if`.

**Structured data (073 §D, 173 §C).** The homepage publishes three `application/ld+json` blocks — `SoftwareApplication` (the description is `SITE_SUMMARY`, the same sentence `/llms.txt` opens with), `Organization` (name, url, the 512px brand logo, `sameAs` → the repository) and `FAQPage` — and every public doc page publishes a `TechArticle` (headline = the doc's H1, description, canonical) plus a `BreadcrumbList` (Docs → page), written from Kotlin by `DocJsonLd` through Jackson so a title cannot break the JSON. `SiteJsonLdTest` and `DocsJsonLdTest` parse every block off the render. No `WebSite` block: the site declares no search action, so it would add nothing.

**The Lighthouse budget (111 §C)** is a script, not a build gate: `scripts/site-lighthouse.sh` serves `modules/web/build/website-export` (run `./gradlew :modules:web:websiteExport` first; the export strips the claim comments — the citation guards read the source templates, not the export) on a free local port and drives **pinned** Lighthouse 12.8.2 in headless Chrome over `/`, `/how-it-works`, `/faq`, `/tableau`, `/published-api` and `/for/saas-teams`, measuring the performance, accessibility, best-practices and SEO categories. **95 is the floor in every category on every page** — a lower score names the page, the category and the number, and exits 1; the fix is the cause, never the floor. The first floor failure was structural, not per-page: five render-blocking stylesheets and a parser-blocking script put ~370 KB (two fonts included) on the critical chain, and the same pages scored 85–92. The fixes live in the head, not in the harness: `site-chrome.css` bundles the whole chain into one generated request (`scripts/build-site-chrome.sh` regenerates it; `SiteCssBundleParityTest` fails the build when any source drifts), the theme swap sheet loads disabled, the two JetBrains Mono faces load in a late `media="print"` sheet that `site.js` flips before paint (slow connections keep the fallback — the same trade `font-display: optional` already makes), and the body/LCP face is preloaded. The server is python3's own `http.server` building blocks with HTTP/1.1 keep-alive and gzip — the bare one-liner speaks HTTP/1.0 with raw bytes and scored the identical pages 1–3 points lower, a harness property, not a page one. The version is pinned so scores are comparable across runs, and the script is deliberately NOT wired into `./gradlew build`: the run needs the network (npx) and a Chrome binary, and the build gate stays hermetic. Run it from the handback and re-run it at merge.

### 4.15a Crawler surfaces (073)

| URL | Served by | Notes |
|---|---|---|
| `/robots.txt` | `static/robots.txt` | Allow all, plus the `Sitemap:` line. The private surface is deliberately not listed — `robots.txt` is public, and the filter chain is the control |
| `/sitemap.xml` | `SitemapController` | Generated from `SitePages.ALL` + the docs index + every packaged doc slug. No `lastmod`, deliberately (145 §7): a build timestamp on every URL told crawlers every page had changed on every deploy |
| `/llms.txt` | `LlmsTxtController` → `LlmsText.index` | The agent index (173 §C.1), in the llmstxt.org shape: H1, a blockquote summary (`SITE_SUMMARY`), then the four `/explore` groups as `- [title](absolute url): description` lines, docs linked at their `.md` twins, and the directory itself. `text/markdown`, the pages' 5-minute public cache. Derived from the same registries as the sitemap — `LlmsTextTest` holds every marketing `<loc>` in it and nothing beyond the sitemap |
| `/llms-full.txt` | `LlmsTxtController` → `LlmsText.full` | The same header, then every packaged doc's link-rewritten Markdown under `# <title>` with a `Source:` line naming its `.md` address (the doc's own leading H1 is folded into the separator) |
| `/docs/{slug}.md` — and `/docs/{slug}` with `Accept: text/markdown` | `DocsController.markdown` (one handler, two patterns; Spring's `produces` negotiation picks it only when Markdown is asked for) | The packaged Markdown itself (173 §C.2) with the §4.16 link rewrite applied to the source: a relative link to a packaged doc becomes `/docs/{slug}.md#fragment`, anything else the GitHub URL. Unknown slug → a Markdown 404 naming `/llms.txt`. The HTML page carries `<link rel="alternate" type="text/markdown" href="/docs/{slug}.md">`; `SiteDocsE2eTest` fetches every twin anonymously and walks every site-relative link in the served text to a 200 |



### 4.16 Documentation (in-product)

| Attribute | Value |
|---|---|
| URL | `GET /docs` (grouped index), `GET /docs/{slug}`; since 173 `GET /docs/{slug}.md` (the raw Markdown — §4.15a) |
| Auth required | **No, since 073** — both routes are `permitAll`. The viewer renders Markdown packaged in the jar (`DocsCatalog`'s only collaborator is a `ClassLoader`); no principal, no workspace and no datastore is reached on either route, and the same content is already public in the AGPL repository |
| Purpose | The operations manual and spec set for the version being run — packaged into the jar, so the docs can never describe a different server |
| Design primitives | `.ds-card` (index); `.doc-body` typography from `static/css/docs.css` (token-derived) |
| JS | None |
| htmx | No |

Content: the packaged `docs/*.md` set (the exclusion policy — `docs/superpowers/`, `semantic-layer-research.md`, `SPEC-REVIEW-2026-08.md` — lives in `modules/web/build.gradle.kts`), grouped Operations manual / Contracts / Reference, rendered to HTML once at startup. Relative links resolve in-app to `/docs/{slug}` or rewrite to their canonical GitHub URL; heading anchors use the same slug algorithm `scripts/docs-audit.sh` validates against. Rendered markdown is inserted as data (`th:utext`) — `${...}` placeholders in config examples display verbatim.

**Two chromes, one body (073).** An anonymous request renders `docs/index-public` / `docs/doc-public` — the marketing header and footer, an SEO head (`<title>` = the doc's H1 + " — datapipelines.co docs", a meta description taken from the doc's first real paragraph, a canonical at `https://datapipelines.co/docs/{slug}`, `og:` tags and breadcrumbs) and no link into the signed-in app. A signed-in request keeps `docs/index` / `docs/doc` in the application chrome, unchanged: making the docs public must not evict a logged-in reader to the marketing site. `DocsController` chooses on the `authenticated` model attribute `UiWorkspaceAdvice` already fills, so there is one definition of "is there a principal". The meta description skips each spec's `**Status:** …` metadata block by design — it describes the file's bookkeeping, not its subject.

The signed-in index's page header carries the app's second **Report a problem** link (127 — the first is §3.4's avatar-menu item): the same bug-form URL from the same constant, a new tab.

---

### 4.17 Promotion (055)

| Attribute | Value |
|---|---|
| URL | `GET /promotion` |
| Auth required | Yes — **session only** for the Promote action (`POST /promotion/promote`); the listing needs `read` |
| Rendered for (114) | The PLAN renders for every member — it is a read, and hiding it would leave an author unable to see what is waiting. **Promote** renders for `canPromote` **in the SOURCE workspace** (the ACTIVE one: `PromotionUiController` reads `principal.requireWorkspace()` and the interceptor judges `promotion.promote` against that same context; no target-side role is consulted). A member without it reads a line naming who to ask — the one place this round explains an absence rather than leaving one, because a promotion screen with no button and no words reads as broken. **Since 143 the reader's plan is a plain table** (`data-promotion-plan="read-only"`: Pipeline / Here / On target) — no `<form>`, no Send column, no selection boxes; the form with its controls renders for `canPromote` only |
| Purpose | Push released content from this deployment to its one configured higher environment ([Versioning §10](versioning.md#10-promotion-ui-driven-separate-use-case)) |
| Endpoints called | The target's [REST API §18](rest-api.md#18-promotion-endpoints-receiver) pair, server-side. The browser never talks to the target |
| Design primitives | the data table (§3.7; the listing), `.ds-empty` (the three empty states — the "Could not read the target" one is reused by every list screen a promoter sees while the target is unreadable, 178), `.ds-badge` (the target label and `absent`) |
| JS | None |
| htmx | No — a plain form POST with a redirect flash. A promotion is a whole-environment action, not a fragment swap |

**#194 lane D — parameter-set rows.** The plan carries the workspace's parameter sets in the
same `PromotableView` computation (the record's §8.3): a released set newer than the target's
is one more row (Name / Here / On target), pushed AFTER the templates and BEFORE the pipelines
when selected. The rows ride the model (`Plan.promotableParameterSets`); the table markup is
owned by the 282 round. **Since 313 the rows are their own table** — the pipelines table's
frame, Send column and role guard, heading "Parameter sets", checkboxes `name="parameter_set"`
— rendered whenever the plan carries set candidates, for the promoter inside the form and for
a reader as a second read-only table; the "Nothing to promote" empty state covers BOTH lists,
and the success flash names the applied set count (`?ok=promoted&…&parameter_sets=N`, the
toast's sentence). A set-only selection is a valid submit; `nothing_selected` fires only when
BOTH lists arrive empty.

**#10 L1c — the transfer families' rows.** The same shape twice more: headings "Visualizations"
and "Dashboards", checkboxes `name="visualization"` / `name="dashboard"` (`value` = the
artifact's NAME, the address the promotion travels by), the same Send column and role guard —
inside the form for a promoter, read-only tables (`data-promotion-plan-visualizations` /
`-dashboards`) for a reader. A dashboard row brings its pinned visualizations with it: the
sender derives their entries (§10.4's skip rule — already on the target at the same version and
hash is omitted, idempotent) and the receiver lands the batch's visualizations before any
dashboard (D61). The plan's lists are the LENS: the dashboards whose every source pipeline the
pipeline lens admits AND that are newer than the target's entry — a dashboard the target already
holds at the same version and hash is hidden — and the pins of admitted dashboards that are
newer. The empty state and the form's condition now cover ALL FOUR lists; the flash carries
`visualizations=N dashboards=N`; the action reads the two new fields and calls the sender's
six-argument `promote`. Guards: `PromotionUiControllerTest` (the six-argument call, the counts
in the flash), `PromotionTwoDeploymentE2eTest` Orders 60–61 (the sender's promote; the form post
promotes both families to uat, version and hash equal, read from uat's database),
`PromotionTransferBrowserTest` (the family tables inside the form for a promoter, light and
dark screens; the reader arm with no input anywhere — against a stub target, the browser harness
boots one app).

**178 — the listing IS the lens.** The plan this page renders is `PromotableView`, the same
computation every other read a promoter makes narrows by ([Auth §11A.1](auth.md#11a1-the-404-rule),
[Versioning §10.2](versioning.md#102-the-listing-rule-what-the-ui-shows)): what is listed here is
exactly what a promoter sees in the explorers, the search, the rail badges and over MCP, and
nothing a promoter sees elsewhere is missing here. The inventory behind it is cached per
workspace (`inventory-cache-ttl-seconds`, default a minute), so the page and the lens agree
within a window; a successful Promote invalidates the entry. The third empty state ("Could not
read the target", with the code) is the one place the target's state is shown as an ERROR to a
promoter — every list screen shows the same sentence as its empty state instead, and no read
ever answers a 502.

**The `body_invalid` flash is a toast (L1c-c).** The receiver's count bounds and strict readers refuse
`visualization.validation.body_invalid` / `dashboard.validation.body_invalid` — the aggregate batch ceiling among
them — and the action flashes that code's last segment. Until L1c-c the key was UNMAPPED in this page's bin, and an
unmapped key renders nothing: a push that did not happen was silent on the very screen that issued it. The page now
renders the toast ("the target's validation refused the batch — for example a count bound on the batch's size — and
rolled all of it back"), pointing the operator at the REST answer, which names the bound, the count and the key.
Apostrophes inside these fragment-expression literals are TYPOGRAPHIC (`’`): an `&#39;` entity is decoded before the
expression parses, where the apostrophe terminates the string literal — the `missing_datasources` and `key_invalid`
branches shipped with exactly that latent render-500 and were fixed in the same round.

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

### 4.18 API section (079 §C, closes T139; read-only since 179)

| Attribute | Value |
|---|---|
| URL | `GET /api-console` |
| Auth required | Yes — `read` (`endpoint.read`), the same permission `EndpointsController.list` declares |
| Purpose | Everything a program uses to talk to this workspace, in one place: published endpoints, the API keys associated with each, the MCP connection |
| Design primitives | `.ds-card`, the data table (§3.7), `.app-chip`, `.app-code`, `.app-empty` |
| JS | None |
| htmx | None — since 179 the page is a read-only inventory. The key verbs moved to `/api-keys` (§4.19) |

**Why the route is `/api-console` and not something under `/api`.** That prefix is the
programmatic surface, split in two — the `/api/v1` REST envelope and the published
endpoints (`/api/<category>/…`, R-EP5) this page lists — and §2.1's three-URL-space rule says a page never lives in the
JSON space. `api-console` is a different first path segment from `api`, so Spring's
segment-wise matching cannot let either shadow the other. It needs no `SecurityConfig` entry:
that chain's `permitAll` list is explicit and everything else is `.anyRequest().authenticated()`.
Because `@RequiredScope` is enforced on any path once declared (the `/api` and `/partials`
prefixes govern only where an UNannotated handler is default-denied), the annotation is a real
gate here. No key reaches it: an `endpoint`-kind key reaches only the published-endpoint
surface ([Auth §7.7](auth.md#77-key-kinds-and-published-endpoint-bindings)) and the MCP key only `/mcp` (#215 B2).

**Read-only about everything on it (179).** The keys card and its minting modal lived here
from 091 to 179; they moved to `/api-keys` (§4.19), the workspace admin's page (D17). What
remains is the inventory — and, for the roles `api_key.read` admits, a **Manage** link per
endpoint row and **Manage API keys** in the header. Your own MCP key is not here at all: it is
the top bar's chip (§4.3e), minted at sign-in (D16).

Two cards.

1. **Published endpoints** — from `EndpointPublishService.list(principal)`, one batch lookup
   for pipeline names and one for released versions (never a query per row, the rule
   `PipelineNames` was written for). Columns: path, pipeline, timeout, **API keys**.
   - The method is always **GET**: `PublishedEndpointController` refuses everything else with
     `Allow: GET`, so it is a fact about the surface rather than a column that could vary.
   - The version shown is the **released** one, not `pipelines.current_version`. An endpoint
     pins a PIPELINE and serves its latest release, so a draft number would describe something
     no call will ever run. A pipeline with no release renders "no released version" — an
     endpoint can outlive the release it was published against, and that is worth seeing.
   - **API keys** names the keys associated at the EXACT node (179: names, not a count —
     "which keys" is the operator's question). It is deliberately not the EFFECTIVE
     authorization, which walks ancestors and takes the nearest node carrying any binding
     (`EndpointAuthorizer`); and no associations does not mean nobody can call it — a `user`
     key pinned to this workspace with `execute` still may. The **Manage** link (admins only)
     leads to the association editor on `/api-keys`.
   - **There is no "calls in the last 24 h" column, and the mock has one.** Nothing in this
     system records endpoint serves: no counter column on `published_endpoints`, no Micrometer
     counter, no query. The only trace is an append-only `endpoint.served` audit row whose one
     production reader is a per-execution boolean and whose KDoc argues explicitly against
     broadening it. A column filled from a sample would be a plausible number and a false one,
     so the card states the absence instead. Adding the column means adding the counter first.
2. **MCP server** — the connection JSON, the live tool count and the confinement note. The
   count is `McpToolCatalog.NAMES.size` at request time, never a literal, exactly as the
   marketing site renders it. The header name is `ApiKeyCredential.HEADER`. The server URL
   comes from `datapipelines.auth.base-url`, the deployment's DECLARED external origin —
   never derived from the request, for the reason `OidcConfig` documents: a hostile `Host` /
   `X-Forwarded-Host` would otherwise choose a URL a reader is invited to paste into an agent's
   config next to a live API key. Unset, the card shows the `{host}/mcp` placeholder the
   marketing site already uses rather than guessing. The key the config wants is created on
   the Keys page (§4.19 — keys v2 A15 retired the top-bar chip and the login mint).

---

### 4.19 API keys (keys v2 — the ONE creation path for every kind)

| Attribute | Value |
|---|---|
| URL | `GET /api-keys` |
| Auth required | Yes — any authenticated principal; the page lists keys the caller CREATED (`mcp_key.own`), and its verbs are `mcp_key.create` (create), `mcp_key.revoke_own` (a creator's delete — the service checks `created_by`) / `api_key.revoke` + `server_key.revoke` (any key of the workspace), `api_key.bind` (associations) |
| Purpose | Create, delete and associate keys of every kind — the `mcp` key an agent presents at `/mcp`, the `endpoint` keys programs call published endpoints with, the promotion peer's `server` key, and (L5, #367) the `dashboard` keys an external application's backend presents at the runtime routes |
| Design primitives | `.ds-card`, the data table (§3.7), `.app-chip`, `.app-picker`, `.app-modal` |
| JS | The create modal, the kind-conditional role/associations fields, and the select-the-secret reveal |
| htmx | `hx-post="/partials/api-keys"` (create, into `#keyCreated`); `hx-delete="/partials/api-keys/{id}"` (delete, into `#keys-table-body`); `hx-post="/partials/api-keys/{id}/bindings"` (save an association SET); `hx-post="/partials/api-keys/{id}/dashboard-bindings"` (L5 — save a dashboard key's FOLDER set) — each with a §5.1 Shape A out-of-band piece |

The table lists the keys the signed-in person CREATED in this workspace: name + `dpk_…` prefix
(12 characters, D16's length), kind, **role** (the role CHOSEN at creation — keys v2 A13/A14: a
member role on an `mcp` key, `api caller` on an `endpoint` key, `promotion receiver` on a `server`
key, `dashboard viewer` on a `dashboard` key), **acts as** (the key's own identity, rendered "<key name> (API key)" — what its runs and
received versions are attributed to, [Auth §4.7](auth.md#47-key-identities)), **created by** (the
person, `api_keys.created_by`), associated endpoints (`/nyc/**` form; a `dashboard` key's FOLDERS, `finance/dashboards/**` form; the root reads as the
whole-tree wildcard), created, expires, last used — relative in the cell, absolute (UTC) on
hover, like every table here. Deleted and expired keys keep their row and lose their verbs.

- **Create** — one modal (**New API key**): **Kind → Role → Name → Expiry → Associations**. Kind
  is one radio card per kind the caller may create, each sentence naming what the kind reaches
  (`ApiKeyForm.kindChoices`): **MCP key** (`mcp` — "an agent's key; it reaches /mcp and nothing
  else"; `mcp_key.create`, held by author, promoter and workspace admin) with a **Role** select
  offering exactly the roles the SUBSET RULE allows the caller (A14 — an author is offered
  `author` only, a promoter `promoter` only, a workspace admin or super admin all three;
  `RolePermissions.offerable` is the one predicate, and no key can carry `viewer` — A15 — or
  `super_admin` — B1); **API key** (`endpoint`, the api caller role — "the paths you bind it to
  are its whole reach"; `api_key.create`, workspace admins and super admins) and, to a super
  admin only, **Server key** (`server`, the promotion receiver role, no bindings;
  `server_key.create`), and — to the same holders as the API key — **Dashboard key** (`dashboard`,
  the `dashboard_viewer` role, L5 #367: "the folders you bind it to are the dashboards it may
  render and refresh; an unbound key serves nothing"; `api_key.create`). It takes NO bindings at
  create — they are the card's own editor. The service re-checks both the per-kind permission and the subset rule
  (`WorkspaceService.requireIssuancePermission`, `RolePermissions.offerable`), so a forged
  request meets the same refusals the dialog never offers.
  Expiry is the same server-resolved select §4.18 used to host (a custom date expires at the
  end of that day, UTC; a bad one is `400 auth.api_key.expiry_invalid`). Associations are the
  published-path picker: only LITERAL prefixes are offered, because `EndpointAuthorizer` walks
  the concrete request path's ancestors and a `{variable}` node would authorise nothing.
  A name already taken by a live key of the workspace is `409 auth.key_name_taken` (A18).
  The secret is shown ONCE in a persistent inline panel; the response refreshes the whole
  table out-of-band (at TABLE level — a `tbody` OOB element dies in the browser's fragment
  parser) and points a toast at the panel.
- **Delete** — revokes the key. The creator's own delete (`mcp_key.revoke_own`, the service
  checks `created_by`) and the workspace admin's any-key delete (`api_key.revoke`; a `server`
  key's is `server_key.revoke` and stays a super admin's — #215, owner ruling 2026-09-24 — so a
  workspace admin sees the row but not the verb, and the service refuses a hand-crafted delete
  with `auth.role_required`). Revoking an `endpoint` or `server` key deactivates its identity in
  the same transaction; removing the member who CREATED a key revokes it too (A17/B6), and the
  row then reads "created by <name> (removed)".
- **Edit associations** — a per-row disclosure with the picker pre-checked to the key's
  current bindings; Save posts the whole SET and the service writes the delta (add/remove),
  so a checkbox never maps to "add" or "remove" by itself. A `dashboard` key's row (L5) has
  its OWN disclosure — **Edit dashboard folders** — posting to its own route with the FOLDER
  picker (the workspace's dashboard-name folders, root first), never a branch inside the
  endpoint form; the route is `dashboard.key.bind`.

**One fragment, three renders.** The page, the post-create refresh and the rows a delete or
association save swaps in all render `api/keys :: keysTable` / `:: keyRows` from one row model
(`ApiKeyRows`).

### 4.20 Schedules

| Attribute | Value |
|---|---|
| URL | `GET /schedules` — optional `?id=<schedule>` selects one (the tree opens down to its leaf) and `&run=<run>` opens that run; the address bar follows the reader's selection |
| Auth required | Yes — `schedule.read`, every member (a promoter through the promoter lens: schedules of the pipelines the lens admits). Its verbs are the five `schedule.*` write rows ([Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative)) — author, workspace admin, super admin — and render by `canAuthor` (§4.3e) |
| Purpose | Browse the workspace's schedules as a folder explorer; read when one runs next and what its runs did; create, edit, pause, resume, unblock, Run now and delete them |
| Design primitives | The explorer chrome is §4.3's (`template-tree.css` — `tplx-*`, `tpl-*`, the divider, the drawer below 1100px); `.ds-card`, the data table (§3.7), `.ds-badge`, `.app-chip`, `.app-alert`, `.ds-field`, `.app-modal`; the page's own rules are `schedules.css` (tokens only, head-loaded — §3.0) |
| JS | `static/js/schedules/` — `model.js` (pure: levels, search, presets, the merged trail, refusal → field, parameter coercion, idempotency; `schedules-model.test.mjs`), `api.js` (the ONE request path: §20 with the session and `DP-CSRF-Token`), `dom.js`, `explorer.js`, `detail.js`, `run.js`, `form.js`, `page.js`; plus the shared `template-explorer.js` (keyboard), `explorer-detail.js` (drawer) and `splitter.js` |
| htmx | **None.** The scheduler design revision §6 (owner, 2026-09-22): the UI uses REST for every schedule operation, reads included, and no parallel partial route exists — the page is a server-rendered SHELL whose every read and write is a `fetch` to [REST §20](rest-api.md#20-schedules) |

**Why no partial, not even for reads.** The pipelines explorer renders its tree from `/partials/pipelines`; a Schedules twin would be a second list query beside §20.1 that the promoter lens and the page envelope would have to be kept equal to. So the markup the page draws is `<template>` skeletons rendered by Thymeleaf on the page (`schedules/explorer.html`, `detail.html`, `run.html`, `form.html`) — a verb outside the reader's role is not in the skeleton at all — and the scripts clone them and fill `data-slot` elements with `textContent`, never `innerHTML` (names, parameter values, reasons and trail text are user data). Outcomes arrive as REST JSON, which carries no fragment to splice, so success and refusal toasts are §5.1 Shape D (`DpToast.show`, the one client-side builder).

**The explorer (left).** §4.3's tree, classes, ARIA and keyboard, with one difference §20 imposes: §20.1 answers a folder's whole SUBTREE by `prefix`, not one level, so a level's direct sub-folders are derived from the names (`model.buildLevel`) — the template-hierarchy §9.1 rule ("no client-side tree assembly") is relaxed here because there is no folder-level REST read and the list is bounded by `max-schedules-per-workspace` (100 by default). It is still one read per level: the root without a prefix, a folder on its FIRST opening with `prefix=<folder>` — so an opened folder shows the server's current answer. A leaf carries the condition badge when the schedule is paused or blocked. **Search** replaces the tree with a flat list of full paths matching every column the list renders — path, pipeline, pattern, its preset wording, zone, condition (§5.1). **Empty**: an author's copy offers New schedule; a viewer's says who creates schedules; a promoter's says the lens shows released pipelines' schedules only. **Loading** is the skeleton rows under `aria-busy`; a failed read renders the envelope's user message, code and correlation id with Try again.

**The detail (right)** — §4.3b's three regions from three reads (§20.4, §20.9, §20.10):

- **Header** — the folder eyebrow and the leaf; the verbs by role, then by state: Delete… (the one destructive verb, a confirm dialog carrying `If-Match`; soft — runs and executions stay), Pause (enabled) / Resume (paused), Edit, **Run now** (refused while blocked, so hidden then). Each verb's success re-renders from the schedule the server returned.
- **Blocked** — when §20.4 carries `blocked`: the reason in words (scheduler.md §5.2) with its code, since when, a link to the run that blocked it, and the recovery verb beside it — **Unblock** (which re-validates first; its refusal is a toast and the block stays). A reader's copy names who can unblock.
- **Reading** — Overview: condition, executor, missed-run policy; the pipeline (linked to its editor for a reader who may open it, else to the explorer's search) with `current version` and the version the latest run used; the preset wording with the pattern under it, the timezone, the policy, the next due time (or why there is none: paused / blocked), revision and updated/created **by name** (#261 — `created_by_name` / `updated_by_name`, the ids §20 carried beside them). **Next five occurrences** — §20.9 verbatim: the wall clock the scheduler computed in the schedule's zone, the UTC offset then in force and the UTC instant (the DST rule is the server's; the browser never re-derives a time). **Parameters** — the literal values.
- **Acting** — the **Runs** card (§20.10, 20 at a time, "Show older runs"): state + reason, origin (`scheduled` / `catch-up` / `manual`), **due** (the occurrence; a manual run's row says when it was asked for) and **started** on their own lines — read against each other — **took** (the EXECUTION's own duration: §20's `execution_duration_ms` answers it straight off the run since #258 — for runs finished before that field existed the page falls back to the per-execution §10.2 read), Details and the execution link (`/executions/{id}`, rendered for `canReadExecutions`). While a run is queued, starting or running the card re-reads every 4 s; the schedule is re-read when a run ends (it may have blocked). A failed refresh keeps the runs shown and marks them stale — one toast per outage — until a refresh answers. The answer owns the selection it started for: a late reply for a schedule no longer selected, or a page since left, marks nothing, toasts nothing and arms no poll; the outage (and its one toast) starts fresh with every selection and after a successful reload.

**A run** (the Details dialog, §20.11): state, reason, origin; due or requested — a manual run's row names **who requested it** (`requested_by_name`, #261) — started, finished, took, the admission window and capacity retries; what ran (`prepared`: pipeline, version, status, body hash); the execution link; the frozen job and parameters — and, beside them (slice 3), **resolved parameters** (`prepared.resolved_parameters`): the literal map the run executed with, its bindings already resolved to dates on the run's frozen reference. **Messages** is the record's R10: the run's own trail (`schedule_run_events`) and — when it launched an execution and the reader holds `execution.read` — that execution's durable events (§10.3A `?format=json`), merged in ONE time order, each line labelled `scheduler` or `pipeline`; the trail's `requested_by` reads as the person's name (#261); the pane says which case it shows when the pipeline half is absent (no execution; a role that reads no executions; past retention — the 410; a failed read). An **unknown** run names its recovery in order: open the execution and check its targets, then unblock — and says why Run now is not a retry.

**The form** (create and edit, one dialog): **Name** — the pipelines' folder-path grammar, no leading slash (the help text says so — the endpoint paths DO start with one), checked by the server on save (the existing folders are suggested); **Pipeline** — suggestions from §5.7's search over the pipelines the reader may read, the job fixed to `{pipeline, version: "current"}` plus the optional `parameter_bindings` (slice 3); **When** — presets that WRITE a five-field pattern (every hour at :MM, every day at, every week on, every month on day) or a custom pattern, the timezone (region ids the scheduler accepts, the browser's own preselected on create) and **Next five**, re-read from §20.3 as the pattern or zone changes; **If occurrences are missed** — skip / run the latest once; **Parameters** — the declared parameters of the version the pipeline's current pointer names now, as literal values (R7; blank = the declared default). A `DATE` parameter's field adds the **binding source** selector — **Fixed value** (the default) / **Today** / **Yesterday** (§20.2): a preset hides the fixed-value input by class and travels in `parameter_bindings`, each run resolving the value on the schedule's frozen reference; the selection round-trips, so an edit re-opens with the preset selected. Other types keep the plain field. A §20 refusal lands beside its field with the code's OWN user message — every field-placed schedule code has a per-code line in the catalogue (#280; the family line "Check the reported field and try again." is what the person saw before): the name grammar and name-taken at the name; the pattern, the minimum interval and the zone at their fields; the pipeline field's refusals (`target_not_found`, `target_not_released` — no released version to follow — `payload_invalid`, `executor_unknown`); the binder's per-parameter refusals at the parameters. The pipeline is also named at PICK time: the moment the typed or picked pipeline resolves with no current version, the pipeline field shows the release-first line — the same sentence the save's `target_not_released` carries, cleared when a pipeline with a current version is picked — while the suggestions stay complete (a list entry's DRAFT status also covers a released pipeline with a draft in flight, so marking or filtering it would lie). Any refusal that names no field (the schedule state codes, the binding refusals, idempotency replays) is a toast. Create carries a fresh `Idempotency-Key` per attempt, reused only to retry the same request after no answer; edit carries `If-Match`, and a stale revision (`409 schedule.revision_conflict`) is shown inside the form with **Reload the current schedule** (the form then shows the server's schedule; the unsaved changes are replaced) — save again.

**Notifications (#442a)** follows Parameters in the author-only form: comma-separated Recipients (empty = no mail), then Started, Succeeded, Failed/cancelled/could-not-start, Outcome unknown and Schedule blocked. The last three default checked; create/edit round-trip the settings. Server refusals land on the Notifications fieldset. An explicit note says: “Scheduler mail is not enabled on this deployment — these settings are saved, but no mail is sent.” Details show only recipient count and selected event labels (or “Off — no recipients”), plus “mail not enabled on this deployment”; addresses never appear there. REST reveals addresses only to readers with schedule-update permission.

**Not in this slice**: notification delivery (442b) and any key or role assignment (R2: a schedule fires as the system identity). The agent manual has no scheduling area (schedules are REST and UI only, record §6).

Guards: `SchedulesUiControllerTest` (the route's row, `canAuthor` = the five write rows, the zone list against the scheduler's parser), `SchedulesPageRenderTest` (the role ladder at the render; no htmx request attribute and no partial route; the script order), `SchedulesCssTokenTest`, `schedules-model.test.mjs`, `ApiErrorCatalogUserMessageTest` (#280 — every field-placed code carries its override), and the browser suites `SchedulesBrowserTest` (explorer by prefix and search; create with field-level refusals asserting the catalogue's own words, the draft-only pipeline named at pick time and refused at save with the release-first words, and the next five equal to §20.9; the stale revision; pause/resume/block/unblock; Run now with the merged trail; delete; the role ladder and a viewer's refused POST from the page's own request path; light/dark; no overflow at 1100/1440/1920) and `SchedulesShotsBrowserTest` (the picture set).

### 4.21 Dashboards — the catalog, the sidebar tree and the workspace (#400; the L3b pages before it)

**The catalog.**

| Attribute | Value |
|---|---|
| URL | `GET /dashboards` (`?q=&offset=` the deep links) |
| Auth required | Yes — `dashboard.read`, every role (a promoter through the lens: released dashboards whose every source pipeline her pipeline lens admits; a hidden one is absent from the list) |
| Purpose | The flat catalog landing: every dashboard the caller may read — or the matches of `q` — one row each, a row linking the workspace |
| Design primitives | §4.3's catalog shape ([Pipelines](#43-pipelines--the-catalog-page-and-the-sidebar-tree-350), #350's owner ruling — one flat, server-paged list, no tree, no detail pane): `.app-catalog-search`, `.tpl-results`, the shared pager; `dashboards.css` dresses the page's own bits |
| JS | None of its own — the search control is the htmx SPA pattern (a debounced `hx-get` into the stable `#dash-list-wrapper` root, focus and value preserved) |
| htmx | Yes — the search and the pager re-fetch `GET /partials/dashboards` (`scope=page`) into `#dash-list-wrapper`, `outerHTML` |

Content: the flat rows at their WORKING version — a row's badge names the version and marks a
draft (versioning §7: unreleased edits stay visible; a draft-ONLY dashboard's row says so —
the list page's "opening a dashboard with no release says so" promise survives as a ROW
state, and opening one is the workspace's choose-a-version state below, not an error).
**Rows preserve the rail** through prepared main-content navigation (#460, §3.4). Empty state:
dashboards are authored through MCP. Browse folders opens the shared REST sidebar host. The tree
loads immediate levels completely, searches canonical/display names with fully expanded ancestors,
and Clear collapses to the normal root. The catalog retains its flat search and pager contract.

**The workspace.**

| Attribute | Value |
|---|---|
| URL | `GET /dashboards/{id}?version=N&tab=…` — one URL names one dashboard, one viewed version and one tab (`GET /dashboards/{id}/preview?version=N` is a 303 redirect onto it: #369's deep links survive) |
| Auth required | Yes — `dashboard.read` (D50 makes every reader an executor; the page must NOT declare `dashboard.execute` to "simplify"). Absent, foreign or lens-hidden → the family's 404. A NAMED version that does not resolve (absent, DISCARDED, or draft under a narrowing lens) → the family's 404 NAMING the version. No served release and none named → the choose-a-version state (#409): the heading shows the NAME, the draft is offered with its preview link, and "no release yet" says what state the dashboard is in — never the empty `h1` and the "not found" the old board page answered for a dashboard the tree had just linked |
| Purpose | The tabbed, version-explicit workspace (#396's ruling): the Board tab is today's board page exactly; Overview, Refreshes, Versions and Keys are the other reading surfaces; the Versions tab carries the lifecycle verbs' dialogs. The browser NEVER authors — agents author over MCP; a person releases from the Versions tab |
| Tabs (the floor) | **Board** — the runtime mounts the released or named version's grid, the refusal region stands in for a board that cannot run, and the events pane sits beside it exactly as the L3b page had it. **Overview** — the viewed version's definition, read-only: the sources with their pinned pipeline versions and statuses, the parameter set, the pinned visualizations with each pin's status (RELEASED, or DRAFT: previewable in a draft, released before publication, #459), the layout summary; no authoring control anywhere. **Refreshes** — the caller's refreshes full width (the board pane keeps its own beside the board), version-independent as #369 documents. **Versions** — the admitted history with served/draft/discarded markers, created and released relative in the cell and absolute UTC on hover (§3.7; the keys rule, §4.19), and the lifecycle dialogs: Release (the one D61 consent for a DRAFT visualization pin), Purge draft, Discard, Restore, Purge version, Switch (make current), Purge dashboard — each a confirm dialog into `#dp-dialog` whose POST calls the SAME service the REST route wires and answers `HX-Redirect` back onto this tab with a flash toast. **Keys** — the `dashboard` keys bound to this dashboard, read-only, linking the Keys page's editor; rendered only for a caller with `dashboard.key.bind` (workspace admin, super admin) |
| Grid rows | Every row of the board's grid is one `--dashboard-row-unit` (app.css, `var(--space-20)` = 5rem) — a fixed track, so a slot of `h` rows is `h` units plus its gaps tall and a chart fills its slot instead of collapsing (#371). The layout's `breakpoint_px` (640 px when absent) is measured on the board host: a narrower board places every item across the full width, stacked in grid order (row, then column) with its row span unchanged. A 767 px viewport gives a 719 px board and keeps 6/6/3/9; a 1280 px viewport gives a 694 px board, which keeps the default grid but stacks with a configured 700 px threshold. Widening the user-resizable navigation rail (#460's separator, keyboard included) narrows the board below 640 px; resetting the rail (Home on the separator) restores the grid. A 0-wide host keeps its stored grid until the observer sees it laid out; without `ResizeObserver`, the stored grid holds (#412; implementation spec §3.2, dashboards.md §2.2/§6.2). A chart in a 2-row slot keeps a plot area of at least half its slot: compact margins, the axes' labels sized by `automargin` (#386, dashboards.md §6.3). The visualization test preview (§4.22) mounts the same grid under the same rule |
| Design primitives | `dashboards.css` (the workspace chrome, the runtime's emitted classes and the page's layout) and the vendored `plotly.css` (the §6.6 design-around's real stylesheet) — both head-loaded (§3.0 is normative: no page template carries its own stylesheet link) |
| JS | The board: `static/js/datapipelines-dashboard.js` + the three renderers and `static/js/dashboards-page.js` — singleton libraries with a per-container mount: it reads exactly two data attributes (the dashboard id on the container; the named version, when the URL named one, on `data-dp-dashboard-version`; the default resolution writes NONE, so the glue's init stays `version: "released"`), and `window.__dpPage` is its test seam. The workspace: `static/js/dashboards/workspace.js` over the SHARED tab core `static/js/workspace/tabs.js` (the one admission and transition rule; the pipeline editor's `pipeline-editor/tabs.js` runs the same machine since #420) — in-page tab switches (each one PUSHES a tab-only entry through `static/js/workspace/history.js`, #402 — Back/Forward re-select in page; a restored root re-wires once and re-paints the tab it left), lazy-once tab reads, and the Board pane's reveal running the runtime instance's OWN `resize()` (a chart booted into a hidden pane re-fits). The dialogs: `static/js/lifecycle-dialog.js` (the fifth container, `#dp-dialog`) |
| htmx | The events pane's bounded poll (below), the lazy tabs' one read, the dialogs' GET/POST pairs. Every link that names a version or board uses prepared main-content navigation and one lazily loaded compatible Plotly bundle; the tab strip never navigates. A lifecycle POST answers `HX-Redirect` — a full navigation back onto the Versions tab, the flash bin rendering the toast |


The signed-in shell lazily loads the vendored 3D superset bundle once and reuses it for
both 2D and 3D viewed versions ([Dashboards §6.4](dashboards.md)). Inert dependency markup
is validated before replacement; cached restoration mounts the new container exactly once.
The runtime's incompatible/two-bundle refusal remains. Standalone previews retain their
isolated bundle choice. A refusal or choose-a-version page mounts no chart.

**The events pane** — the caller's refreshes of the open dashboard, newest first, beside the
board and again on the Refreshes tab (`GET /partials/dashboards/{id}/refreshes`, the REST
refreshes route's own read through `DashboardRuntime`, so the lens and the
own/`execution.read_all` visibility are the route's; it floors at `dashboard.execute` with
that route — the page that embeds it stays on `dashboard.read`). Each row shows status,
scope and age, and links its executions to `/executions/{id}` — for a reader who reads
executions; a promoter's refresh names none ([REST §23.3](rest-api.md),
[Dashboards §5.7](dashboards.md)). **Updates are a bounded poll**
(`hx-trigger="load delay:15s, every 15s"`, the pane re-fetching ITSELF with `outerHTML`),
deliberately not the runtime's notification hooks: the pane's source of truth is the DURABLE
refresh record (including the `execution.read_all` reader's wider view), the hooks fire only
for refreshes THIS browser started and would need a second client-side row renderer, and the
poll keeps working when the runtime instance failed to boot. The first re-fetch is delayed
(the server just rendered the rows); the poll dies with the page — leaving is a full
navigation, so the element and its timer leave the DOM with it.

Guards: `DashboardUiRenderTest` (the tree level's contract and the lens fixture; the
catalog's flat rows and the draft-only row state; the ONE-bundle contract at the render for
a 2d and a 3d board; the named version's attribute channel and the un-boosted selector; the
choose-a-version state (#409) and the refusal state with the name kept; the pane's rows,
execution links and poll attributes), `DashboardWorkspaceControllerTest` (the resolution
table, the tab set, the permission-derived affordances, #409's no-runtime-call), the new
`DashboardWorkspaceModelTest` (the version resolution: explicit → `findServedVersion` or the
404 naming the version; default → the served release; no served release → the
choose-a-version state), `DashboardLifecycleDialogControllerTest` (the seven verbs' golden
paths, the typed confirm before the service, the #332 audit order, the redirect targets,
the session gate), `ShellRenderTest` (Home; the Dashboards item, its lazy branch and its
search), `ReadFloorTest` (the pages and the read tabs in `DASHBOARDS`, the Keys tab in its
own `dashboard.key.bind` family, the pane in `DASHBOARD_RUNTIME`, the dialogs in
`LIFECYCLE_DIALOGS`), `RoleVisibilityRenderTest` (the ladder: no verb for a viewer or a
promoter, the Keys pane below ws_admin absent, every verb guarded),
`MatrixRowReachabilityTest`/`RoleWalkE2eTest`/`PermissionSeamE2eTest` (the §7.6 Surfaces
cells), `workspace-tabs.test.mjs` + `dashboard-workspace.test.mjs` (the shared core and the
glue's decision rules on node --test), and the browser suites `DashboardPagesBrowserTest` +
`DashboardWorkspaceBrowserTest` + `DashboardPageConformanceBrowserTest` (the §10.5 page
half: bootstrap, freshness, the parameter lock, notifications, abort, connection loss,
disposal across a back/forward pass, two instances of one dashboard on one page; the tab
strip's in-page swaps and the hidden board's re-fit; #409's state and its booting draft
link; the promoter's lens with the no-POST collector; the dialogs into `#dp-dialog`; zero
CSP violations on every tab in both themes; light/dark screens).

---

### 4.22 Visualization test preview (session-less — #353)

| Attribute | Value |
|---|---|
| URL | `GET /visualizations/{id}/preview?session=<preview capability>` (`&theme=light\|dark` optional) |
| Auth required | **No session** — the FIRST capability-authenticated page: its ONLY credential is the preview capability in `?session=` (a `PublicPaths` entry, auth.md §8.3), minted by a test session's start and valid for THAT visualization version until the session's results are submitted or its deadline passes; the starter's current `visualization.update` is re-checked on every load. No cookie is read and none is set. Every refusal — wrong, expired, revoked, another visualization's, a malformed id — renders the one **unavailable** state with HTTP 404, byte-identical whatever the cause |
| Purpose | An agent's browser (Playwright) checks each saved test case against the real renderer before it submits its verdicts ([Dashboards §3.4.1](dashboards.md)); a person may open the same link |
| Design primitives | A standalone page with NO layout (no rail, no top bar, no htmx, no CSRF token — rendering one would set a cookie): the design-system sheets, `app.css`, `dashboards.css` (`dp-preview*`, the runtime's status chips) and the vendored `plotly.css` in its own head — the one page outside §3.0's head-loading rule, because it has no layout to load them; `.ds-card` per case, `.ds-empty` for the unavailable state |
| JS | L3a's vendored runtime + the three renderers (the ONE Plotly bundle the server chose, declared on its tag) and `static/js/visualization-preview.js` — the glue, a FILE: it reads the page's one `<script type="application/json" id="dp-preview-data">` block (written through `ScriptSafeJson`) and mounts one runtime instance per case in the runtime's **fixture mode** — no request leaves the page |
| htmx | None |

Content: the visualization's display name, folder-path name and version, the deadline, and one card
per test case in the body's order — the case name and its assertions as text, then the board the
runtime mounts. Each case section is `section[data-dp-case="<name>"]` and gains `data-dp-ready="true"`
when its instance's bootstrap held (or `data-dp-error="<code>"`); the chart's status chip is the
composite adapter's `.dp-dashboard-status[data-dp-state]` (`success`/`ready`, `no-data`, `error` with
the evaluator's code). The page never echoes its capability, carries `<meta name="referrer"
content="no-referrer">`, and renders case names and assertion labels through `th:text` only; the
agent's notes and environment never appear here.

Guards: `VisualizationPreviewControllerTest` (the one 404 state, the per-case configuration and
results, the script-safe block, the closed theme set), `VisualizationTestSurfacesE2eTest` (no cookie
in or out, the 404 body-equal across causes, confinement), `VisualizationPreviewBrowserTest` (both
themes under the live CSP with zero violations, the rendered trace count per case, the whole
start → preview → screenshot → submit → upload → release flow), `PublicPathsTest`,
`PublicRouteWalkerTest`, `PublicContractE2eTest`.

**The signed-in twin (#399).** A person reads the same per-case rendering on the workspace's
Preview tab ([§4.24](#424-visualizations--the-catalog-the-sidebar-tree-and-the-workspace-399-396)):
the same builder writes the same configuration block, keyed by the version instead of the run and
carrying no deadline, behind `visualization.read` and the session. This page stays the agent's
capability page — its public route admits nothing of the workspace.

---

### 4.23 Parameter Sets (the catalog and the workspace page — #374, #357 S1)

**The catalog page.**

| Attribute | Value |
|---|---|
| URL | `GET /parameter-sets` (`?q=` searches it, `?offset=` pages it) |
| Auth required | Yes — `parameter_set.read`, every role (a promoter through the lens: only sets whose current version is RELEASED and admitted; a hidden set is absent — not listed, not counted, not paged, not a search hit) |
| Purpose | Find a parameter set and open its workspace |
| Design primitives | The house list: `.ds-empty` for the empty state, the shared §5 pager; `parameter-sets.css` (`ps-*`), head-loaded (§3.0 is normative) |
| JS | None of its own |
| htmx | The route's rows are the same read the sidebar's tree is built from; a row preserves the rail through main-content navigation |

Content: a flat, server-paged list (25 rows per page) of every set the lens admits — folder-path name,
display name and the version chip — or, under `?q=`, the matches: a case-insensitive substring of the
name, the display name or the description (#415), `ParameterSetService.search`'s answer, the same lensed
read the sidebar's branch searches with. It is the page `/parameter-sets` always meant to be (owner ruling
2026-10-02 for the artifact families: the page is a CATALOG, the tree lives in the rail, §3.4). The SPA
search control re-fetches ONLY the list fragment into the stable `#parameter-set-list-wrapper` root
(§4.3's contract: the control is never re-rendered, so focus and value survive); `?q=` stays the deep
link every "find this parameter set" link uses.
`GET /partials/parameter-sets/tree` retains the catalog and legacy prefix presentations.
The sidebar uses its REST tree/search routes (§3.4, rest-api §24), under the same read lens.

**The workspace page.**

| Attribute | Value |
|---|---|
| URL | `GET /parameter-sets/{id}?version=N&tab=workspace` — one URL names one set, one VIEWED version and one tab |
| Auth required | Yes — `parameter_set.read`. A set the caller cannot see — absent, another workspace's, lens-hidden — is the family's ONE house 404; so is an explicit `?version=` that does not exist or is not admitted (under a narrowing lens a draft number is the same 404 an unknown number is, so a status cannot be probed by number). A `?version=` that is not a positive integer is the house 400 |
| Purpose | Read a set's structure at a chosen version and, for a role that may evaluate, drive its live form |
| Design primitives | `parameter-sets.css`; a Cytoscape + dagre graph on design tokens only (colours resolved through a probe element — the editor graph's recipe); `.ds-badge`, `.ds-empty`, the shared form controls |
| JS | `static/js/parameter-workspace/{model,graph,inspector,workspace,runtime}.js` and the L3a dashboard runtime's parameters-only entry, `DatapipelinesDashboard.initParameters` (`static/js/datapipelines-dashboard.js`) — files only, no inline script (CSP). The vendors ride an inert `<template id="ps-runtime-scripts">` catalog that `runtime.js` loads in order, once per document; the only executable tag is the guarded bootstrap, so an htmx history restore never re-executes a bare script |
| htmx | None beyond the shell's boost; the version links are plain links |

**Which version the page shows** — the resolution, in order: (1) an explicit admitted `?version=N` shows ITS
body, never a nearby one (no clamping); (2) otherwise the set's CURRENT pointer, when the caller's lens
admits that version; (3) otherwise an accessible DRAFT, labelled as one; (4) otherwise the **choose-a-version**
state over the admitted history — no graph, no form, no invented pointer. The header chip prints the one
fact string (`vN · released · current`, `vN · draft`), and the version links (`nav.ps-versions`,
`aria-current` on the viewed one) are the in-page switch. A promoter's lens admits RELEASED versions only, so
her page carries no draft pointer and her version list is the released ones. `?tab=` is the closed set
`workspace | history`; any other value is `workspace`, and `history` resolves to the workspace pane until
the history surface lands (S3).

**Content.** Two script-safe JSON blocks (`#ps-data`, the set's body through `ScriptSafeJson`; `#ps-workspace`,
the ONE source of the displayed and the submitted version — a malformed block is a visible refusal with ZERO
requests, never a default); the **graph** (`#ps-graph`: one node per parameter, an edge per `depends_on`, the
badge the source kind) with a text live region for its selection — and, since #383, with the evaluation's LIVE
STATE: each applied frame moves its parameter's node through waiting → admitted → running → resolved (a failed
selector → failed, carrying the frame's catalogued code; a parameter without a terminal state fails from the
`evaluation_failed` terminal frame), the terminal response's `reset` parameters carry a dashed reset mark, the
live region announces one short sentence per frame (`city is running`, never the frame's JSON), and the page
keeps a frame log (`window.PSWorkspace.frames`, in memory only, reset per mount — never persisted) the guards
read; the **inspector** (`#ps-inspector`: the selected
parameter's definition and, once evaluated, its state — value, origin, reset, hidden, disabled, options,
errors — as TEXT only, plus the stream's code and detail for a parameter a live frame marked failed); and the
**live form** (`#ps-form-host`).

**The live form — roles.** The form is rendered, and `initParameters` is called, only for a role that may
evaluate (`parameter_set.evaluate`: viewer, author, workspace admin, super admin). A promoter's page renders
the structure (graph and inspector) and **no control and no request** — `[data-ps-read-only]` carries the
hint; the markup is not a CSS-hidden control. The form POSTs the OBSERVED evaluation
`POST /api/v1/parameter-sets/{id}/evaluations` (rest-api §21.5 — §21.3's act streamed as Server-Sent Events)
with the VIEWED version, the WHOLE selection set, a fresh v4 `evaluation_id` per attempt (minted by the
runtime, never stored) and the `instance_id`, the session credential and the `DP-CSRF-Token` double-submit
header; the page adds no route, no scope row and no permission. The frames drive the graph's live state
(above); `evaluation_completed`'s response applies the form state exactly as the plain evaluate's did. A
parameter VALUE leaves the form only in that POST body — never a URL, history state,
storage or log line. A change made while an evaluate is pending SUPERSEDES it — the person's newest selection is the one worth
answering, so the pending attempt is finished and its STREAM is closed (the server's disconnect grace then
aborts the evaluation, §21.5), and every frame whose `evaluation_id` is not the current attempt's is dropped
before it can touch state or DOM (a dashboard's form keeps
the refusal instead, `parameters.locked`). A stream that ends without a terminal frame is the transport
failure ([Dashboards §6.6](dashboards.md#66-the-states-notifications-and-the-csp)'s rule): the form keeps its
last committed selections and the lock is released by the deadline, never earlier; a `: revoked` comment shows
the runtime's revoked notice the same way. A refused evaluate renders the server's code and message in `#ps-form-error`;
`evaluation_failed` ends the attempt with its catalogued code and every unfinished node fails on the graph.

Guards: `ParameterSetsWorkspaceModelTest` (the resolution order, never-clamp, the lens-hidden 404, the draft
arms, the tab set), `ParameterSetsBrowseModelTest`, `ParameterSetsUiControllerTest` (the routes' scopes, the 400/404,
the evaluate flag per role), `ParameterSetsPartialControllerTest` (the tree route's dispatch and the nav stamp,
#415), `ParameterSetsRenderTest` (the page's markup contract: the blocks, the form only
for an evaluator, the promoter's page, the draft label, the choose-a-version state, the script-safe
blocks; #415: the search fragment's listbox, its pager's `q`, the two empty states),
`ParameterSetServiceIntegrationTest` (the search's columns, its literal metacharacters, its lens truth and
its blank-`q`-is-`listAll` rule, #415), `ShellRenderTest` (the Parameter Sets branch and its search input),
`ReadFloorTest` (the `PARAMETER_SETS` family and
its floor), `BrowseModelConventionTest`, `MatrixRowReachabilityTest`/`RoleWalkE2eTest` (the §7.6 Surfaces
cells), `parameter-set-init.test.mjs` (the runtime's parameters-only entry),
`parameter-set-stream.test.mjs` and `ParameterWorkspaceStreamBrowserTest` (#383 — the streamed evaluation:
the observed route's transport, the supersede that closes the prior attempt's stream, the id check that drops
another attempt's frames, the §6.6 path, `: revoked`, the state machine in `parameter-workspace-graph-state.test.mjs`,
and the browser witness for spec §12's scenarios 3, 6, 7 and 10 with the promoter arm),
`parameter-workspace-model.test.mjs`, `SampleDataParameterSetsContentTest`
and `ExampleContentSeederTest` (the demo set `nyc/parameters/geo_filters` is seeded through the seeder, §4.23's
demo content), and the browser suite `ParameterSetPagesBrowserTest` (the catalog search, the branch search and
the keyboard, #415).

**History — the evaluation records (#376, #357 S3; workspace spec §6.4, R3).** The header carries two section links
under it, `Workspace` and `History` (`nav.ps-tabs`, main-content links, `aria-current="page"` on the current one; the
viewed `version` rides along): one canonical URL each, `?tab=workspace` (the default) and `?tab=history`. The History
arm renders **no live form, no graph and no body block** — the workspace state block says `hasBody: false`, so opening
the tab evaluates nothing (an evaluate would be a record of its own). It paints the house table (§3.7: the fixed
`dt-frame dt-scroll dt-nowrap` frame, `data-dt-paged`, `data-table.js` from the shell — no second renderer), one row per
evaluation record of the SET, whoever ran it — this page, a dashboard, the REST route or an MCP client: **Started** (UTC),
**Caller** (`PAGE`, `DASHBOARD`, `REST`, `MCP`), **Principal** (the person's name, or `key dpk_…`), **Version**, **Status**
(the house chip; the enum on `data-status`), **Valid**, **Outcomes** and **Queries** (counts), **Took**. 25 a page,
newest first; Previous/Next page `GET /partials/parameter-sets/{id}/evaluations?offset=` (the same browse model the page
painted with). A row's Started cell is a button that loads `GET /partials/parameter-sets/{id}/evaluations/{evaluationId}`
into the region below the table: the record's header (caller, principal, correlation id when one exists — a dashboard
refresh's id —, status with its catalogued `outcome_code`, the stamps), the per-parameter outcomes as recorded
(`resolved` / `reset` / `error` + the code and its reason word), and the statement attempts — parameter, datasource,
template pin, queued / started / ended to the millisecond, outcome (`EXECUTED`, `REFUSED`, `FAILED`, `TIMEOUT`, `ABORTED`;
`in flight` while open), rows, code — so two selectors that ran together show OVERLAPPING ranges, as stored, never
re-serialised. A record never holds a value, a selection, SQL or a driver message, so none can render; every field is
`th:text`.

Roles: the tab and both partials are `parameter_set.read` (the owner's §11.2 ruling — no new permission): viewer, author,
workspace admin and super admin read every record; a promoter reads through the LENS — a set the lens hides is the
workspace page's own 404 on the page and on both partials (same status, same body), and under a narrowing lens only the
records of the versions it admits (released) are listed or openable, so a draft's parameter names never reach a promoter.
No REST or MCP history surface (§11.6).

Guards: `ParameterSetEvaluationsBrowseModelTest` (the lens before any read, the version narrowing, the pager, the text
projection), `ParameterSetHistoryRenderTest` (the section links, the History arm without form/graph/body, the row's open
target, the pager, escaping), `ParameterSetsUiControllerTest` (the history arm's state block), `BrowseModelConventionTest`
(the page and the partial share the model), `ReadFloorTest`, `RoleWalkE2eTest`, `ParameterEvaluationHistoryE2eTest`
(scenarios 9 and 10: every caller's record, the promoter's 404 parity, the draft record hidden from the lens), and the
browser suite `ParameterSetHistoryBrowserTest` (both themes, the overlapping attempts, a timed-out attempt).

**Demo content.** Every personal workspace seeded with the NYC demo family gets `nyc/parameters/geo_filters`
(RELEASED v1): a year → month → pickup-zone cascade over the `sample-trips` Postgres datasource through three
pinned selector templates, a hard-coded `measure` choice and a typed `min_trips` input. The seeder derives
the set's id per workspace and states the body's shape hash; the examples file carries neither.

### 4.24 Visualizations — the catalog, the sidebar tree and the workspace (#399, #396)

**The catalog.**

| Attribute | Value |
|---|---|
| URL | `GET /visualizations` (`?q=&offset=` the deep links) |
| Auth required | Yes — `visualization.read`, every role (a promoter through the lens: the visualizations a dashboard her lens admits pins; a hidden one is absent — not listed, not counted, not paged) |
| Purpose | The flat catalog landing: every visualization the caller may read — or the matches of `q` over name, display name and description — one row each, a row linking the workspace |
| Design primitives | §4.21's catalog shape exactly (`.app-catalog-search`, `.tpl-results`, the shared pager); `visualizations.css` (head-loaded, §3.0) dresses the page's own bits with design tokens only |
| JS | None of its own — the search is the htmx SPA pattern (a debounced `hx-get` into the stable `#viz-list-wrapper` root) |
| htmx | Yes — the search and the pager re-fetch `GET /partials/visualizations` (`scope=page`) into `#viz-list-wrapper`, `outerHTML` |

Content: flat rows at their WORKING version, with draft badges and the existing paged catalog search.
Rows and version links preserve the rail through prepared main-content navigation. The Visualizations
sidebar branch uses the shared REST component (§3.4), with complete lazy levels, server name search
and expanded ancestors, and clear-to-root. The signed-in chart loader reuses one compatible 3D bundle.

**The workspace.**

| Attribute | Value |
|---|---|
| URL | `GET /visualizations/{id}?version=N&tab=…` — one URL names one visualization, one viewed version and one tab. No `version` views the current release, else the working draft |
| Auth required | Yes — `visualization.read`. Absent, foreign or lens-hidden → the family's 404; a named version that is DISCARDED or does not resolve for the caller → the family's 404. A malformed `version` (not a positive integer) is a 400 whose message is a constant and never echoes the input. `tab` is the closed set below — anything else is Preview, never an error |
| Purpose | The tabbed, version-explicit workspace (#396's ruling, §4.21's shape): read a visualization's version, its test evidence and the dashboards that pin it; a person releases and manages versions from the Versions tab. The browser NEVER authors — agents author over MCP |
| Tabs (the floor) | **Preview (test fixtures)** — the DEFAULT. The viewed version's saved test cases rendered in the runtime's **fixture mode**, ONE case at a time behind a case selector (a case mounts on its first selection; a ten-case visualization boots one chart, not ten). The tab's label and its lead sentence say it shows fixtures; no case → the empty state, verbatim: "no test cases — the preview renders a visualization's test-case fixtures; live data runs inside a dashboard". No request leaves the pane — the configuration block is §4.22's, built by the SAME builder (`PreviewViews.page`), keyed `workspace:<id>:v<n>` and carrying no expiry. **Overview** — the viewed version's definition, read-only: name, display name, description, the renderer and its bundle, the inputs and their columns, the transform pin with its status and bindings, the test-case count and the body hash. **Evidence** — the version's test runs, newest first, and it SAYS its cap ("the newest 100 runs at most"); a run's detail shows its screenshot through the existing screenshot route (whose rules it follows — the image only when a screenshot was stored), else the text "no screenshot", and its cases, environment and mechanical report as text. **Used by** — the dashboard versions that pin this visualization, read through the caller's DASHBOARD lens (`DashboardService.pinnedBy`), each linking `/dashboards/{id}`; none → "no dashboard pins this visualization". **Versions** — the admitted history with current/draft/discarded markers, created and released relative in the cell and absolute UTC on hover (§3.7; the keys rule, §4.19), each version a full-navigation link onto `?version=`, the lifecycle verbs (below) and the Export link to `GET /api/v1/visualizations/{id}/export` |
| Lifecycle dialogs | Each verb is a GET (the dialog, into `#dp-dialog`) + POST pair under `/partials/visualizations/{id}/lifecycle/…`, session-only, CSRF-protected, the POST calling the SAME service verb the REST route calls and answering `HX-Redirect` — built server-side from the id and a whitelisted `from` — back onto the Versions tab (the entity purge onto the catalog), the flash bin rendering the toast. **release** (the refusals listed BEFORE the button in the service's order — no test case, the transform pin not released, then the evidence gate; the ONE `release_pinned_templates` consent checkbox when the pin is a DRAFT, D61; the form posts `body_hash`, the hash of the draft the dialog read, and the release is made AT it — #416, §4.3d), **purge-draft** and **purge-version** (typed confirm; the dashboards pinning that version listed), **discard** (names the version the current pointer falls back to, by the repository's D60 rule), **restore** (says whether the pointer moves), **switch**, **purge-entity** (typed confirm naming the visualization; only a sole draft). The buttons render only for a role whose `holds` admits the verb's §7.6 row — a viewer and a promoter see none |
| Design primitives | `dashboards.css` (the workspace frame and the runtime's emitted classes), `visualizations.css` and the vendored `plotly.css` — all head-loaded (§3.0) |
| JS | L3a's vendored runtime + the three renderers (the ONE Plotly bundle the server chose for the VIEWED version, declared on its tag; none in the choose-a-version state), `static/js/visualization-preview.js` (§4.22's glue, exposing its per-case mount as `window.DatapipelinesPreviewMount`), `static/js/workspace/panes.js` (the pane glue — the strip, the lazy panes, and the tab switches as pushed tab-only history entries of the `visualizations` family through `static/js/workspace/history.js` (#426): Back/Forward re-select in page through the helper's ONE window listener, a cached restore re-wires the strip once (the guard is an expando, never the `data-dp-ws-wired` attribute) and shows the tab it left, an entry the root cannot replay is handed to htmx — over the SHARED tab core `static/js/workspace/tabs.js`) and `static/js/visualizations/workspace.js` (the closed tab set and the one-case-at-a-time Preview) |
| htmx | The lazy tabs' one read each (a hidden tab causes no fetch), the evidence run detail, the dialogs' GET/POST pairs. Every link that names a version is a FULL navigation (the one-bundle rule); the tab strip never navigates |

Every case name, assertion label, run field and JSON document renders through `th:text`
(`textContent` in the glue); the Preview tab's configuration block is the page's one `th:utext`
slot, written through `ScriptSafeJson`. The workspace is NOT the session-less capability page
of §4.22: the public `/visualizations/{id}/preview` route admits none of these routes.

Guards: `VisualizationUiRenderTest` (the tree level, the flat search, the catalog, the ONE
bundle for a 2d and a 3d version, the hidden panes, the choose-a-version state, the Preview's
empty text verbatim, its selector and escaping, the Evidence cap and the screenshot / "no
screenshot" split, the Used-by link and empty state, the verbs per role, every dialog's states),
`VisualizationUiControllerTest` (the version resolution, the closed tab set, the non-echoing 400,
DISCARDED and lens-hidden 404s, the affordances per role, the lensed catalog),
`VisualizationPartialControllerTest` (the tree, the tabs' 400/404s, the evidence filter and cap,
the dashboard lens on Used by), `VisualizationLifecycleDialogControllerTest` (the verbs' audits,
the typed confirm before the service, the redirect whitelist, the session gate),
`VisualizationLifecycleDialogModelTest` (the refusal order, the consent, the D60 fallback, the
pinners per version), `VisualizationPreviewBlockTest` (the workspace block is the capability
block field for field), `ShellRenderTest` (the Visualizations branch and its search),
`ReadFloorTest` (the pages and tabs in `VISUALIZATIONS`, the dialogs in `LIFECYCLE_DIALOGS`),
`ScriptBlockUtextAuditTest`, `MutatingHandlerScopeFloorTest`, `PublicPathsTest` /
`PublicRouteWalkerTest`, `MatrixRowReachabilityTest`/`RoleWalkE2eTest`/`PermissionSeamE2eTest`
(the §7.6 Surfaces cells), `visualization-workspace.test.mjs` (the glue's decision rules on
node --test), and the browser suites `VisualizationSidebarTreeBrowserTest` +
`VisualizationWorkspaceBrowserTest` (every tab in both themes under the live CSP with zero
violations, the promoter's page issuing no lifecycle request, the 2d and 3d bundles, three widths).


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
    <!-- every table is the data table (§3.7): frame › viewport › table -->
    <div class="dt-frame" data-dt-paged>
      <div class="dt-viewport" tabindex="-1">
        <table class="ds-table">
            <tr th:each="p : ${pipelines}">
                <td><a th:href="@{'/pipelines/' + ${p.id} + '/editor'}" th:text="${p.displayName}">Name</a></td>
                <td th:text="${p.description}">Desc</td>
                <td><span class="ds-badge ds-badge--neutral" th:text="'v' + ${p.currentVersion}">v1</span></td>
            </tr>
        </table>
      </div>
    </div>

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

**Empty state.** When a collection legitimately has zero rows, the partial returns a `.ds-empty` block — `.ds-empty-title` naming what is missing, `.ds-empty-description` with the follow-up, and `.ds-empty-actions` for the action if the user has scope for it ("No pipelines yet — Create pipeline"). These are the only empty-state classes; `.ds-empty-state` is **not a class** — no stylesheet defines it (four templates once used it and every pixel came from the inline styles beside it). Distinguish the two empties: *nothing exists* gets the create action; *nothing matched the filter* gets "No pipelines match "…" — Clear filters". An empty table with only a header row is not an acceptable empty state. **In a data table (§3.7, 282) the empty state is a ROW of the same table** — `.dt-state-row` › `.dt-state`, the title in `<b>` and the follow-up beside it — so the header stays and the columns do not disappear with the rows (the executions list, recent executions; the keys and admin-users tables already carried theirs as a spanning row); a screen whose empty case has no table to keep (a dialog saying "Granted to nobody", a list screen before its first item) keeps `.ds-empty`.

**Search.** A screen's server-side search covers every column that screen renders; where a column is derived, the search matches the rendered text (a dialect enum's wire value, an unbound workspace's `global` literal, a `readonly` restriction badge). A search that silently ignores a visible column reads as "no results" to the user.

**Loading state.** Every htmx request gets a signal at three levels (085 §D — previously the top bar was boosted-navigation only, and partials leaned on their own spinners alone):
- **The 2px bar under the nav (`#app-progress`) shows for EVERY request**, boosted or partial. `shell.js` keeps an in-flight COUNT — shown on 0→1, hidden on →0 — so a tree expand and a detail load running together cannot hide it early. The counting pair is `htmx:beforeRequest` / `htmx:afterRequest`, because afterRequest is the one terminal event htmx 2.0.10 fires on every outcome: `htmx:afterSettle` never fires for an aborted request, a network error or a timeout, and a bar that can stick on is worse than none.
- **The originating control goes busy.** `shell.js` marks the requesting element with the shell's own `.app-busy` class for the flight — deliberately NOT htmx's `.htmx-request`, which htmx 2.0.10 applies to the `hx-indicator` TARGET instead whenever the element carries `hx-indicator` (the tree leaves do), leaving the control itself unmarked. A `<button>` in that state is non-interactive (`pointer-events: none` — `shell.js` adds `aria-disabled="true"` for the duration, never the `disabled` property, which would drop focus mid-flight; htmx already guards re-triggering) and draws an `::after` spinner ring in the `.ds-spinner` idiom, absolutely positioned so the control's box never changes (the no-layout-shift rule below stands). A folder `<summary>` is the deliberate exception: it gets the class but stays operable while its level loads (collapse/re-expand mid-fetch is pinned behaviour) and its busy signal is the chevron spinning in place. Mutating buttons (`Save`, `Generate key`, `Test connection`) still additionally set `hx-disabled-elt="this"` against double-submit, and elements carrying their own `hx-indicator` spinner keep it — the busy state is additive, never a replacement.
- **A slow swap target gets a delayed skeleton.** A request still in flight 150ms after `htmx:beforeRequest` marks its target `aria-busy="true"` and gains ONE skeleton row (`.ds-skeleton .ds-skeleton-table-row .app-target-skeleton`); a faster swap never flashes either. Timers and skeletons pair per target — concurrent requests into different panes are normal — and the terminal event removes both. The detail panes (`#template-detail`, `#pipeline-detail`) are the primary beneficiaries; the mechanism is generic. **The target is resolved once, up front** (`swapTargetFor`): a boosted navigation's htmx target is `<body>` until the beforeSwap retarget, and a skeleton on body sits outside the 100dvh shell — the one place that can grow the document — so a boosted request's skeleton goes into `#app-main`. **The history snapshot carries no transient chrome**: htmx snapshots the page during the swap, before the terminal event, so `htmx:beforeHistorySave` strips every live skeleton and busy mark first, and `htmx:historyRestore` / `pageshow` purge any orphan a cache written earlier still holds. (2026-09-13: the owner's `/dashboard` carried a document scrollbar — a body-level skeleton row restored from the history cache after a slow boosted navigation away and a Back.)
- **The click itself is acknowledged before any of the above** (103 §3.5): a boosted navigation dims and `aria-disabled`s the clicked link in the same frame as the press, and a status pill joins it after 150ms. That layer is specified in §3.5 — it is a property of the SHELL, not of a request, and it is cleared on a different union of events than the bar is.
- **A data table's first-paint placeholder is skeleton ROWS of the same table** (§3.7, 282): the dashboard's recent-executions panel renders the partial's own header (`recent-executions :: head`) over three `.dt-skeleton-row`s, so the header is in place before the rows arrive. The delayed skeleton above is unchanged (shell.js's, for any slow swap).
- Reduced motion keeps every state and drops every motion: the bar's slide, the control ring's spin (it goes dashed-static, the `.ds-spinner` precedent), the chevron's spin (a static accent chevron instead) and the skeleton's shimmer are all covered by their own `prefers-reduced-motion` blocks.

**Error rendering.** A partial request that fails returns the **standard REST error envelope** ([REST §4.2](rest-api.md#42-error-envelope)) rendered into an HTML fragment — the same `code` / `message` / `user_message` / `correlation_id`, not a bespoke error format. No htmx extension is loaded: htmx never swaps 4xx/5xx responses on its own (`responseHandling` maps `[45]..` to `{swap: false}`), so a refusal that should surface as a toast keeps its real 4xx status, carries the `partials/toast-oob` fragment as its body, and sets the `HX-Retarget: #toast` + `HX-Reswap: beforeend` response headers; `toast.js`'s `bridgeErrors` listener flips `shouldSwap` on `htmx:beforeSwap` — only when the server asked for `#toast` by header, so an ordinary error behaves exactly as before (Shape C under **Notifications**).

- The retarget leaves the success target untouched — a failed refresh never blanks the panel it was going to replace.
- The server renders the envelope into a `.ds-toast-danger` fragment: `user_message` as the headline, `code` and `correlation_id` in small text (the correlation id is what a user quotes in a support request), and `doc_url` as a link when the envelope carries one.
- **Field-level validation errors** (`400` with per-field `details`) are the exception: they render inline next to the offending inputs, because a toast that vanishes is the wrong place for "this field is required".
- **Modal-scoped errors** stay in the modal: a screen whose error must not dismiss its context (the §4.5 register modal) keeps its own `htmx:responseError` path and does NOT retarget to the stack.
- **A malformed typed value** (#408) — a `UUID`, `Int`, `Long` or `Boolean` path or query value that does not convert (`/dashboards/not-a-uuid`, `?version=abc`) — is the caller's error, never the 500. A malformed **path** value names nothing, so it answers exactly as a well-formed absent id does: the §6 404 page, or the 404 toast with the absent id's code. A caller cannot tell the two apart. A malformed **query or form** value is a `400` with the REST answer to the same value (`pipeline.execution.invalid_parameter_type`, "Parameter '<name>' is missing or not of the expected type."), which names the parameter and never echoes the value. The split follows the parameter's binding (path or query), never the value's shape; the line is DEBUG, never ERROR. A parameter type no converter can read is our defect, whatever the caller sent, and stays the 500.
- **`401`** is not a toast — the partial responds with `HX-Redirect: /login?expired=true`, which sends the browser to the login page (§6). Rendering a login form inside a swapped fragment would nest a page inside a panel.
- **`403`** renders a toast and, where the affordance should not have been visible at all, the swap also removes it — a scope-gated action becoming visible is a UI bug, and the toast copy says "you don't have permission", never "something went wrong".

**Notifications.** Success and failure alike are reported as **toasts**: the layout carries a single `#toast` stack (`.ds-toast-stack`, `aria-live="polite"`, top-right below the header), and a partial that has something to report returns the server-rendered `partials/toast` fragment (`.ds-toast` + one of the design-system variants `success` / `danger` / `warning` / `info`, title + body + close button) — the panel, table, or form that fired the request is NOT re-rendered, so a notification can never break layout. `static/js/toast.js` is loaded once by the layout and owns the whole lifecycle: a `MutationObserver` arms each appended toast with an auto-dismiss timer (6s) and its close button; exit is the design system's own `.exiting` animation. Markup is built client-side in exactly one place, `DpToast.show`, for events that carry no HTTP response; everything else is server-rendered (the 025 theme-swap rule: fragments are rendered by Thymeleaf).

**The hard rule.** A toast auto-dismisses after 6s, so it NEVER carries anything the user must keep. One-time secrets (`partials/api-key-created.html`, the admin one-time-password notice) stay persistent inline; a toast may POINT at them ("shown below, copy it now") and nothing more. Field-level validation stays inline at the form.

**Delivery shapes.** htmx 2.0.10 swaps the CHILDREN of an out-of-band element for any swap style other than `outerHTML` ("we use the content of the node, not the node itself" — `oobSwap` in `htmx.js`), so a toast bound for the stack is always wrapped: `partials/toast-oob :: oob(variant, title, message)` renders the `hx-swap-oob="beforeend:#toast"` wrapper with the `.ds-toast` as its child. Putting `hx-swap-oob` on the `.ds-toast` itself appends the close button, title and body BARE into the stack — no `.ds-toast` node, no arming, no auto-dismiss, and no error anywhere (`ToastOobFragmentRenderTest` pins the nesting).

- **Shape A — content + toast.** The response is the normal swap content with the `toast-oob` fragment spliced in; the triggering control keeps its `hx-target`/`hx-swap`.
- **Shape B — toast only.** The control sets `hx-swap="none"`; the response body is the `toast-oob` fragment alone. No response headers.
- **Shape C — refusal as toast.** The response keeps its real 4xx status, its body is the `toast-oob` fragment, and it sets `HX-Retarget: #toast` + `HX-Reswap: beforeend`; `bridgeErrors` (see **Error rendering**) is what lets htmx swap it.
- **Shape D — client-originated (the exception, not a convenience).** `DpToast.show(variant, title, message)` in `toast.js` builds the one toast shape and appends it to `#toast`, for events that arrive with no HTTP response to attach an OOB swap to — the pipeline editor's SSE terminal events (`pipeline_completed`, `execution_aborted`, `pipeline_failed`) — and for the one screen whose every outcome is a REST JSON answer by design, the Schedules page (§4.20; the scheduler design revision §6 forbids it a partial route, so no response of its carries a fragment). It is built with `createElement` + `textContent`, never `innerHTML`: titles and bodies carry abort reasons, node ids and error text, none of which is trusted markup. Any outcome that arrives on an HTTP response uses A, B or C — nothing else in the codebase gets a second toast builder. Both markup definitions — `partials/toast.html` and `show` — assert ONE contract: root classes `ds-toast ds-toast-{variant}`, `role="status"`, exactly three children in the order close / title / body, pinned by `ToastMarkupParityTest` (server) and `toast.test.mjs` (client).

**Typed confirm (102) — the irreversible actions ask the user to TYPE the thing that will be destroyed.** A destructive verb that cannot be undone (the version purge, the entity purge) does not arm its button on a click alone: the dialog renders a field naming exactly what to type — `v4` for a version, the entity's NAME for a purge — and the `ds-button-danger` stays disabled until the typed value matches. It is the second use of the convention after the CLI's `--clean` (same reason: the action deletes rows, not state), and it is enforced TWICE: client-side for the button state, and server-side — the `confirm` form field is checked before the lifecycle service runs, and a mismatch is `400 pipeline.version.confirm_mismatch` / `template.version.confirm_mismatch` (§5.1 Shape C), so a dialog forged or scripted around the field still cannot purge anything. Reversible verbs (Release, Discard, Restore, Switch) do not type-confirm — their one click names one version, and the undo is a sibling verb.

---

## 6. Error Pages

These are **full-page** errors — the result of a browser navigation to a page URL. Errors raised by an htmx partial swap never navigate here; they follow the toast/inline rule in §5.1. The single bridge between the two is `401` on a partial, which returns `HX-Redirect: /login?expired=true` and lands on the first row below.

| Page | URL | Content |
|---|---|---|
| 401 (Unauthorized) | `GET /login?expired=true` | Login page with "Your session has expired" message |
| 400 (Bad Request) | rendered in place (`error/400`) | "That request could not be read" + link to dashboard. The sentence under it is the form's ("Something in the form that was sent is missing or malformed") by default; a malformed value in a GET's address (#408, §5.1) says "The address names a value this page cannot read. Check the link and try again." Neither names the value |
| 403 (Forbidden) | `GET /error?status=403` | "You don't have permission to access this page" + link back to dashboard |
| 404 (Not Found) | `GET /error?status=404` | "Page not found" + link to dashboard. A malformed id answers as an absent one (#408, §5.1) |
| 500 (Server Error) | `GET /error?status=500` | "Something went wrong" + correlation_id for support |
| Login errors | `GET /login?error={code}` | Login page with error message (domain_not_allowed, inactive, oidc_error, identity_mismatch) |

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
| 2026-10-09 | v1.124 | #465 REST tree client polish — renumbered at merge after #412's v1.123 | §3.4: staged refresh that keeps rows and reloads visible open levels; the ten-page level ceiling with Load more; the progress bar during navigation preparation. |
| 2026-10-09 | v1.123 | #412 the dashboard breakpoint follows board width, default 640 — renumbered in recovery after #460's v1.122 | **§4.21, Grid rows:** the threshold measures the board host; the 767 px viewport's 719 px board keeps the grid, a configured 700 px threshold stacks the 694 px board at 1280, and widening the user-resizable navigation rail narrows the default board below 640. A 0-wide board holds its stored grid until reveal. |
| 2026-10-06 | v1.122 | #460 reusable REST navigation — renumbered at merge after #462's v1.121 | §3.4/§3.6: reusable REST trees, complete expanded name search and clear reset; user rail width up to the viewport; prepared artifact/version navigation and chart history. |
| 2026-10-06 | v1.121 | #462 draft purge pins | §4.3d purge dialog: escaped named parent/dashboard blockers, sole/nonsole scope and no destructive form while pinned; direct POST rechecks. |
| 2026-10-06 | v1.120 | 459 merge follow-up | **§4.21's dashboard workspace, Overview tab**: the sources show their pinned pipeline versions and statuses (not "releases"), and a DRAFT pin is previewable in a draft and released before publication (#459). The R1 "release hint" wording is withdrawn; #459 superseded #369's R1. |
| 2026-10-04 | v1.119 | #416 the Release dialogs post the hash they read | **§4.3d** gains the paragraph "The Release form posts the hash the dialog read": all four families' dialogs carry one hidden `bodyHash` (`body_hash` for visualizations) from the draft read the dialog already shows, the POST requires it (400 at binding when missing) and releases AT it, so a draft changed after the dialog opened is `*.version.conflict` (409) instead of being released silently; the draft is still read only for the no-draft refusal. The visualizations lifecycle-dialogs row (§4.24) names the field. No route, scope or matrix row changes. |
| 2026-10-03 | v1.118 | 442a (#442) — renumbered at merge after 398b's v1.117 | Saved schedule notification settings: recipients and five event choices, validated and shown by role; omitted PUT settings preserved; mail remains off pending part b. |
| 2026-10-03 | v1.117 | #398 the Templates workspace — the sidebar tree and the tabbed, version-explicit workspace (#396's first reuse of the pipeline pattern) — renumbered at merge after 392's v1.116 | **§3.4**: Build's Templates item joins Pipelines and Dashboards as a **navigating-tree branch** — item link + separate toggle + a panel (`#nav-tree-templates`) whose search re-fetches `GET /partials/templates?scope=nav` into `#template-nav-root`; a leaf is a full-document link into `/templates/{name}` (release-first; a draft is an explicit choice); every Templates answer carries `DP-Nav-Stamp`. **§4.6 rewritten** ("Templates — the catalog page and the sidebar tree"): `/templates` is the flat, paged CATALOG with the dialect/type filters and the create modal (Q1(b), owner ruling 2026-10-02 — the browser keeps authoring templates, so Create/Edit/Save stay); `GET /partials/templates` gains `scope` (`nav`; any other value is the catalog — same route, permission and lens) and the stamp; the two-pane explorer body, its divider and its detail pane retire (`GET /partials/templates/versions`, `partials/template-detail.html` and the `partials/templates.html` dispatcher went with their Surfaces cells; `template-tree.css`'s orphaned `.tplx-excerpt` went with them — the rest of the tplx- chrome stays, schedules and the pipelines Overview share it). **§4.7 rewritten** ("The Template Workspace"): `GET /templates/{*name}?version=&tab=` is the canonical page (`TemplateWorkspaceRouteTest` pins the capture routing), `/templates/editor` is a 302 into it; six tabs — Source (default; the column fragments and the transform face reused, Edit kept), Overview, Render (authors, non-transform), Runs (lazy), Used by, Versions (house table, per-row verbs, no Switch); the resolution order is the pipelines one (release first, explicit-admitted-or-404, draft-only and choose-a-version states); lifecycle dialogs are `from`-aware and redirect onto `?tab=versions&ok=…` (the catalog for a purge that removes the template) with a layout flash — the explorer's Shape A leg is gone with the pane; tab switches are in-page (`replaceState`, no pushed entry — #402's contract), version switches are full navigations. §4.6b carries the capability inventory (every old-explorer row → new home → guard). No new permission; no role cell changed; `template.read`'s Surfaces cell gains the canonical route and the editor-as-redirect and loses the retired detail route. Guards: `TemplateSidebarTreeBrowserTest`, `TemplateWorkspaceBrowserTest`, `TemplateWorkspaceControllerTest`, `TemplateWorkspaceModelTest`, `TemplateWorkspaceRouteTest`, `ShellRenderTest`, the re-aimed template suites, `RoleWalkE2eTest`, the falsifications in the handback. **Rework (398b):** `template-editor.css` and `template-workspace.css` load from the layout head, not from inside `#app-main` — the first round shipped them in the `content` fragment, §3.0's defect on a boosted arrival; `ViewerEditorRenderTest` pins both sheets before `</head>` and none inside `<main>`. |
| 2026-10-03 | v1.116 | #392 the Overview's Last run follows the caller's Runs-tab visibility | **§4.4**: the Overview's Last run is the latest run visible to its caller, through the same #275 read as the Runs tab; a promoter sees only their own. |
| 2026-10-03 | v1.115 | #426 the visualizations workspace's tab switches take the shared history helper — renumbered at merge after 420's v1.114 | **§4.24:** the JS cell — `workspace/panes.js` takes a `family` (the visualizations glue passes `visualizations`) and pushes a tab-only entry per user switch through `workspace/history.js` (#402) instead of `replaceState`, re-selects on Back/Forward in page through the helper's one window listener (the per-root `popstate` listener is gone; it survives only on the no-family/no-helper path), guards the wiring with the `__dpWsWired` expando so a restored root — whose cloned markup carries `data-dp-ws-wired="1"` — wires again, and re-writes `data-active-tab` on every apply so the restore shows the tab it left; `history.js` loads between `tabs.js` and `panes.js`. The htmx cell keeps "the tab strip never navigates". No action, route or permission changes (no §7.6 row). The parameter-set workspace is NOT moved onto the helper: its sections are full-document links (`hx-boost="false"`, `?version=&tab=history`), there is no in-page switch to make navigable. The templates workspace adopts the helper after #398's rework lands (follow-up to #426). |
| 2026-10-03 | v1.114 | #420 the pipeline editor re-adopts the shared workspace tab core — renumbered at merge after 408's v1.113 | **§4.21**: the clause "the pipeline editor keeps its own `pipeline-editor/tabs.js`" is withdrawn; §4.4's workspace six-tab machine (Flow, Overview, Parameters, Runs, Usage, Versions) is no longer a private copy — `pipeline-editor/tabs.js` delegates admission and transition to `workspace/tabs.js` (loaded first by the editor's runtime catalog), and its 18 named getters read `this` so the CSP build's reactive proxy tracks them (#400's first adapter closed over the raw object and was reverted). No tab, pane, route or role changes. |
| 2026-10-03 | v1.113 | #408 a malformed typed path or query value — renumbered at merge after 422's v1.112 | **§5.1** gains the malformed-typed-value rule: a path value that does not convert answers as a well-formed absent id (the 404 page or toast, indistinguishable), and a query or form value is a `400` with REST's `pipeline.execution.invalid_parameter_type` naming the parameter, never the value. It is never the shell-wide 500, and a parameter type no converter reads stays the 500. **§6** gains the `400` row that has existed since 098 (the form sentence by default, the address sentence for #408), and the 404 row says a malformed id answers as an absent one. |
| 2026-10-03 | v1.112 | #422 the dashboards and visualizations Versions tables take the keys page's time shape | **§4.21, §4.24:** the Versions tab's created and released cells are RELATIVE (`3 days ago`) with the absolute UTC stamp (`2026-10-01 09:00 UTC`) on `title` — the keys rule (§4.19: relative in the cell, absolute UTC on hover), `RelativeTime.since`/`absolute` computed in the model's fill (`fillVersions(…, now)`), never the template and never the JVM's zone; an unreleased row keeps the em dash. The dashboards partial's root also carries `dp-versions-pane` (`.dp-versions-pane > * { min-width: 0 }`, the visualizations pane's `.viz-versions` fix) so its framed table scrolls inside its own viewport at 390 px instead of pushing `<main>` sideways. |
| 2026-10-03 | v1.111 | #402 Back/Forward across the workspaces' in-page switches (spec A12) — renumbered at merge after 401's v1.110 | **§3.2**: the workspaces' own history entries sit beside the region-scoped cache — `{dpWorkspace: {family, version, tab}}`, ignored by htmx's popstate, replayed by `workspace/history.js`'s one window listener, never cached; htmx's current-path record kept on the live URL so a boosted leave after a switch is a cache HIT; an entry that pops under another screen is handed to htmx's restore. **§4.4**: tab and version switches PUSH (no longer replaceState); Back/Forward replay on the live instance; the workspace block and the root's tab attribute stay truthful for a restore (a snapshot taken after a switch used to restore with an EMPTY selector). **§4.21** (the dashboards workspace's JS row): tab switches push through the helper; a restored root re-wires once (its guard is an expando — the cloned `data-dp-ws-wired` attribute had left every restored strip dead) and re-paints the tab it left. |
| 2026-10-03 | v1.110 | #401/#407 the pipelines explorer residue — the pane and the dialogs' explorer legs removed; the workspace's release flash names the cascaded templates | **§4.3b**: the detail pane's fragment (`partials/pipeline-detail.html`), its route `GET /partials/pipelines/detail` (off the `pipeline.read` §7.6 Surfaces cell in the same commit) and `PipelineBrowseModel.fillDetail` with its region fills are REMOVED — no page has rendered the pane since #350; the section is the record. **§4.3d**: the pipeline dialogs' `#px-dialog` shapes are gone (`hx-target` ternaries collapsed to the editor form, the POSTs' `from` legs with them — every pipeline verb answers `HX-Redirect`); Shape A is the templates explorer's. `lifecycle-dialog.js` drops `px-dialog` and the `pipeline-detail` close; `explorer-detail.js` drops the pipeline half (the `leafId` branch, the drawer check); `pipeline-versions.html` defaults to the workspace's `#pe-dialog`/`from=editor`. **§4.3d, the Release row**: the reload's flash NAMES the cascade — "Released. Also released: `<template@version>`, …" — server-derived (#407): the release POST holds the list once in the actor's session (`ReleaseFlash`), the workspace GET consumes it; a crafted `?ok=`, another page, another actor or a minute gone by renders the generic sentence only. No permission, role or scope row changed (one route retired with its handler, same commit); no new route. Guards: `MatrixRowReachabilityTest` (red on the row before the handler), `RoleWalkE2eTest`, `ReleaseFlashTest`, `PipelineLifecycleDialogControllerTest` (the held-names assert), `PipelineExplorerRenderTest` (30→21 tests), `LifecycleDialogRenderTest`, `ReleaseCascadeBrowserTest` (the names in the toast + the consumed/crafted replay), `lifecycle.test.mjs`, `explorer-detail.test.mjs`. |
| 2026-10-03 | v1.109 | #399 the Visualizations workspace (#396) — renumbered at merge after 415's v1.108 | **§3.4**: Build gains **Visualizations** between Dashboards and Parameter Sets, the navigating-tree pattern's fourth use (lucide `chart-line`; link → the catalog, lazy tree WITH the search box, leaf → the workspace; no engine change). **New §4.24**: the flat catalog `GET /visualizations` (`q` over name, display name and description, `ILIKE … ESCAPE`), the sidebar branch `GET /partials/visualizations/tree`, and the version-explicit workspace `GET /visualizations/{id}?version=&tab=` with five tabs — Preview (test fixtures, the default: one case at a time in the runtime's fixture mode, the configuration built by §4.22's builder, the empty state verbatim), Overview, Evidence (states its 100-run cap; the screenshot or "no screenshot"), Used by (the pinning dashboards through the dashboard lens) and Versions (the seven lifecycle dialogs with the one `release_pinned_templates` consent and the refusal list, and the Export link). DISCARDED and lens-hidden versions are the family's 404; a malformed version is a non-echoing 400. **§4.22**: the signed-in twin paragraph. No permission, row or role changed; the routes are in auth.md §7.6's visualization Surfaces cells. Guards: the render/controller/model tests, `VisualizationPreviewBlockTest`, `ReadFloorTest`, `ScriptBlockUtextAuditTest`, the two browser suites. |
| 2026-10-03 | v1.108 | #415 the Parameter Sets name search (follow-up to #374) — renumbered at merge after 383's v1.107 | **§3.4**: the Parameter Sets panel gains the search box in the Pipelines branch's exact markup — the third branch the shared engine serves with zero JS lines changed; a non-empty `q` swaps the flat results into the panel's root (`#params-tree-nav`), clearing returns the tree, and a set the lens hides is never a hit (the service filters it, never the template). **§4.23**: `GET /parameter-sets` gains `?q=` and the SPA search control — a case-insensitive substring of name, display name or description through the new `ParameterSetService.search` (everything lens → SQL `ILIKE … ESCAPE '\'` with the term's `%`/`_`/`\` literal; a narrowing lens → in memory over the admitted sets); `partials/parameter-set-list` is retired for the `partials/parameter-sets` dispatcher + `partials/parameter-set-search` (the dashboards' shape, one family over). No new route, permission, scope row or role changed: the same two routes on `parameter_set.read` gained a query parameter. Guards: `ParameterSetServiceIntegrationTest`, `ParameterSetsBrowseModelTest`, `ParameterSetsPartialControllerTest` (new), `ParameterSetsUiControllerTest`, `ParameterSetsRenderTest`, `ShellRenderTest`, `ParameterSetPagesBrowserTest`, the falsifications in the handback. |
| 2026-10-03 | v1.107 | #383 the workspace's observed evaluation — the client half (workspace spec §4.2/§6.2/§6.3, #357 S2b) — renumbered at merge after 376's v1.106 | **§4.23**: the live form now STREAMS `POST /api/v1/parameter-sets/{id}/evaluations` (rest-api §21.5 — the observed evaluation; the version, the whole selection set, a fresh v4 `evaluation_id` per attempt, the CSRF header) through the same `initParameters` entry; the graph is the evaluation's LIVE STATE (waiting → admitted → running → resolved, failed with the frame's catalogued code, D3's unfinished sweep from `evaluation_failed`, the terminal response's reset marks), the live region announces one sentence per frame, a supersede CLOSES the prior attempt's stream (the server's grace aborts it), another attempt's frames are dropped before any state or DOM change, a stream ending without a terminal frame takes dashboards §6.6 (lock released by the deadline, never earlier), and the page keeps an in-memory frame log (`window.PSWorkspace.frames`) the guards read. No route, permission, scope row or role changed; no new page markup (the log is data, not a pane). Guards: `parameter-set-stream.test.mjs`, `parameter-workspace-graph-state.test.mjs`, `ParameterWorkspaceStreamBrowserTest` (scenarios 3/6/7/10 + the promoter arm), `ParameterSetFormBrowserTest`'s captures tightened to the exact `/evaluations` path. |
| 2026-10-03 | v1.106 | #376 the Parameter Sets History tab (workspace spec §6.4, #357 S3) — renumbered at merge after 400's v1.105 | **§4.23** gains **History**: the `Workspace` / `History` section links (one canonical URL each, `?tab=`), the History arm with no live form, graph or body block (so the tab evaluates nothing), the house table of the set's evaluation records — every caller's, 25 a page, newest first — its pager partial and the record detail partial (header, per-parameter outcomes, the statement attempts to the millisecond); `parameter_set.read`, the lens server-side (the page's own 404 on both partials; a narrowing lens lists only the versions it admits). |
| 2026-10-02 | v1.105 | #400 the Dashboards workspace — the searchable sidebar tree, the flat catalog, the tabbed version-explicit workspace (#409 closes with it) | **§3.4**: the Dashboards panel gains the search box in the Pipelines branch's exact markup (the shared engine serves it with no JS change; a non-empty `q` swaps the flat results into the panel's root, clearing returns the tree). **§4.21 rewritten**: `GET /dashboards` is the flat CATALOG in §4.3's shape (owner ruling 2026-10-02 — the L3b tree page retired; folders are the sidebar's axis), each row a full-document link into the workspace, a draft-only dashboard's row saying so; `GET /dashboards/{id}?version=&tab=` is the tabbed, version-explicit WORKSPACE — Board (today's board page exactly: the same bundle block, the unchanged `dashboards-page.js`, the events pane beside the board), Overview (the definition, read-only, with each pin's status and the R1 release hint), Refreshes, Versions (the history and the seven lifecycle dialogs into `#dp-dialog`, `HX-Redirect` back with a flash) and Keys (`dashboard.key.bind`, read-only, linking the Keys page); #409: a dashboard with no served release opens the choose-a-version state — the heading NAMES it, the draft is offered, the runtime config never called, no `not_found`; the preview route answers 303 onto the workspace (the deep links survive); every version switch a full navigation (the one-bundle rule), tab switches in-page over the SHARED tab core (`static/js/workspace/tabs.js`; the pipeline editor's adoption was reverted at merge — #420), the hidden Board re-fitting through the runtime's own `resize()`. **§4.21's preview gap closed**: the #369 draft preview is documented here for the first time, as the workspace's named-version view (the chip succeeded the banner). `dashboards-page.js` unchanged; no new permission; no REST route changed. Guards: the falsifications in the handback. |
| 2026-10-02 | v1.104 | #374 the Parameter Sets screens (workspace spec §6.2/§6.3, #357 S1) — renumbered at merge after 386's v1.103 | **§3.4**: Build gains **Parameter Sets**, the navigating-tree pattern's third use (lucide `sliders-horizontal`; link → the catalog, lazy tree beside it with no search box, leaf → the workspace). **New §4.23**: the flat paged catalog `GET /parameter-sets` and the canonical workspace `GET /parameter-sets/{id}?version=N&tab=` — the version-explicit resolve order (explicit admitted version, never clamped → the lens-visible current → an accessible draft, labelled → choose-a-version), the house 404/400, a static Cytoscape graph + inspector and the live parameter form through the dashboard runtime's new parameters-only `initParameters` entry against the EXISTING evaluate route (role-hidden: a promoter's page has no control and no request). The demo family seeds `nyc/parameters/geo_filters`. No permission, scope row or role changed; the routes are listed in auth.md §7.6's `parameter_set.read` Surfaces cell. Guards: the render/controller/model tests, `ReadFloorTest`, `SampleDataParameterSetsContentTest`, `ParameterSetPagesBrowserTest`, the falsifications in the handback. |
| 2026-10-02 | v1.103 | #386/#387 the board's small slots and its breakpoint | **§4.21's Grid rows row** gains the collapse below `breakpoint_px` (768 px default; every item full width in grid order, the row unit kept, a crossing re-places and resizes) and the 2-row chart's plot-area floor (compact margins + `automargin`, dashboards.md §6.3). |
| 2026-10-02 | v1.102 | #364 the history shape guard names the `<main>` element | **§3.2** — the #358 shape guard decides "body-shaped" by the serialised OPENING TAG `<main … id="app-main"` (attribute order tolerated), not by the substring `id="app-main"`. The substring test was fooled by user text — innerHTML serialisation escapes `<`, `>` and `&` in a text node but not the double quote — so a page whose text carried the literal (a pipeline description) read as body-shaped and reloaded on every Back/Forward. The test is one module-scope function in `shell.js` (`historyEntryIsBodyShaped`, exposed for `node --test`); `PipelineWorkspaceHistoryBrowserTest` gains the cache-hit-with-no-reload case for a description carrying the literal. No route, scope, template or policy change: a genuinely body-shaped entry is still purged and vetoed. |
| 2026-10-02 | v1.101 | #350 the pipeline tree in the expanding global sidebar (workspace spec §5, D7/D8) | **§3.4**: Build's Pipelines and Dashboards items are **navigating-tree branches** — one normative pattern (item link + separate toggle + a panel with the lazy server-backed tree in a bounded scroll region), the leaf a full-document link (pipelines → the canonical workspace, current-first), the explorers' keyboard engine in its NAV context, `aria-current` on the viewed page's leaf; the rail FITS an open tree's visible rows between `--app-rail-tree-min` 320px and `--app-rail-tree-max` 400px and the region scrolls sideways past the max with full labels; an icon collapse wins by selector; visible failure + Retry; the generation + `DP-Nav-Stamp` (workspace\|lens) admission guard; per-workspace+family state (paths and offsets only) restored incrementally, `rail.js` painting the remembered width before the first frame. The Dashboards branch adopts the pattern (its `<details>` disclosure retired — one tree engine in the rail). **§3.2**: `template-explorer.js` loads once from the layout; a re-executed page tag re-inits. **§3.6**: the tree-open widths per band (desktop; tablet only when expanded; the phone drawer keeps 232px). **§4.3 rewritten**: `/pipelines` is the flat, paged CATALOG (owner ruling 2026-10-02) — no tree, no detail pane, every row a full-document link into the workspace, `?q=` kept as the deep link; `GET /partials/pipelines` gains `scope` (`nav`; any other value, `page` included, is the catalog — same route, permission and lens). §4.3a/§4.3c now describe the templates explorer only; §4.3b is the record of what moved (its fragment and the explorer lifecycle legs have no page — #401). **§4.3d/§4.3e (#395)**: the workspace's Switch/Discard/Restore never sent their request (their dialogs targeted the explorer's `#pipeline-detail`); they are `from`-aware now and redirect onto the Versions tab with a flash, and the workspace header keeps the one-destructive rule (Purge pipeline in the `{D}` shape). **§4.21**: the sidebar's Dashboards branch is the pattern. No permission or role changed; no new route. Guards: `PipelineSidebarTreeBrowserTest`, `PipelineSidebarTreeStateBrowserTest`, `nav-tree.test.mjs`, `template-explorer.test.mjs`, the controller/render tests, the falsifications in the handback. |
| 2026-10-02 | v1.100 | #371 the dashboard grid row unit | **§4.21's board table** gains the **Grid rows** row: every row of the board's grid is one `--dashboard-row-unit` (app.css), so a Plotly figure has its slot's height on the board page (it measured 34 px for a 4-row slot before). The preview-scoped copy of the rule (#353, §4.22) is retired — one unscoped rule serves both pages. Routes, permissions, roles: none changed. |
| 2026-10-01 | v1.99 | #349 the unified pipeline workspace — six tabs, the bottom Node Details dock, house tables, view/run ownership | **§3.7**: four surfaces join the house table — the Parameters tab's declared schema (`dt-fit`, client-rendered rows the observer upgrades), and the versions/runs/usage fragments (one shape for the explorer pane and the workspace; the ⋯ verb menus and `data-version-row` ride the rows; runs keep the `data-href` keyboard contract; the parent links name the canonical workspace). **§4.4**: the composition — six tabs (Flow the default; Runs omitted with NO lazy fetch for a caller without the execution read; Runs/Usage lazy-once; Overview client-rendered from the lens-filtered workspace block; the sidebar withdrawn, its content in Overview and Parameters), the IN-PAGE version switch (selector and Versions-tab Open; URL via replaceState; one generation/token discipline over node SQL, checks, runs and usage; per-version run-input drafts, nothing persisted), and run ownership (the identity strip with the effective SUBMITTED parameters and View run's version; graph paint, node states and the Details pane's run rows gate on run version = viewed version). The dock keeps Node Details beside Results/Errors/Events; the execution tabs render only with the execution read. No route, permission or role changed. Guards: `PipelineWorkspaceTabsBrowserTest`, `PipelineWorkspaceRunOwnershipBrowserTest`, the promoter tab-omission arm in `PipelineWorkspacePromoterAdmittedBrowserTest`, `PipelineWorkspaceLayoutBrowserTest` (which replaces the 141 sidebar-resize suite — the pane it resized is withdrawn by the same ruling), `tabs.test.mjs`, `workspace-view.test.mjs`, the falsifications in the handback. **Corrected at merge (orchestrator, review F1/F2):** §4.4's 141 sidebar-resize bullet now records the withdrawal (the pane is gone; `PipelineWorkspaceLayoutBrowserTest` pins it) and §4.3b's "compact rows, never a table" now states the shared house-table fragment. |
| 2026-10-01 | v1.98 | L4b (#353) the visualization test preview | **New §4.22** — the first session-less, capability-authenticated page: `GET /visualizations/{id}/preview?session=`, its standalone head (no layout, no CSRF token, no cookie), the per-case cards mounted by the vendored runtime in its fixture mode, the one byte-identical 404 state for every refusal, and its guards. |
| 2026-10-01 | v1.97 | #358 the history cache is region-scoped, and its restores are single-init (lane 348-c) — renumbered at merge after L3b's v1.94 | **§3.2**: `#app-main` carries `hx-history-elt` — the cache snapshots the workspace region, never the body, so footer scripts are never cached and never re-executed on a Back; a one-time purge plus a restore-time shape guard (drop + full fetch — htmx's own `refreshOnHistoryMiss`, since a cancelled `htmx:historyCacheHit` in 2.0.10 simply dies) refuse any body-shaped entry saved by a pre-scoped build, keyed on the one honest tell (`id="app-main"` as a CHILD). **§3.2's scripts bullet rewritten:** the pipeline editor's restore is single-init through its runtime (inert catalog, `x-ignore`, one `mutateDom` activation — the 080 rescue is gone); a run outliving a navigation is detached, never cancelled, and the restored page re-attaches through the replay stream (terminal event exactly once). **The shell's transient chrome** (`is-pending`/`app-busy`/their `aria-disabled`) comes off before every snapshot and is purged from restored pages — a restored link no longer looks clicked (the #358 browser suite caught it as a dead `.pe-back` on the restored page). **§3.7's data-table paragraph**: "the history element is `document.body`" corrected to `#app-main`. Routes, permissions, roles: none changed. |
| 2026-09-30 | v1.96 | 348-b (#348) the workspace's version-context corrections — renumbered at merge after L3b's v1.94 | **Correction of the record first:** v1.92's sentence "the version rows' Open links carry their row's version" described the intended end state — the code still targeted the unqualified `/editor` URL at that tip, so every row opened the default. **§4.3:** the version rows' Open now really enters `/pipelines/{id}?version=<row>` (full document), and the detail header's Open is the canonical `GET /pipelines/{id}` with the old "in editor" wording gone (tree/search rows keep the redirect-covered URLs). **§4.4:** the page's serialized projection carries only the LENS-VISIBLE current pointer — a development-posture current draft's number can no longer leak into `current_version` of the script JSON for a promoter (the model's `currentVisible`, not the raw index value; PipelineResponses' REST shape untouched). The client's version context has ONE validated initialization path (`workspace.js`: bounded positive integer, body presence, pipeline identity) shared by full load, boost and cached-history restore — the restore re-reads the block through it (clearing stale refusal flags) — and the workspace's node-SQL preview REFUSES visibly with zero requests when no valid pin exists (the legacy working-version default stays with callers that omit the parameter). The cached-restore leg's own residual defects (duplicated subtrees, stacked bindings, Cytoscape CSP re-init) are shell-owned: #358. |
| 2026-09-30 | v1.95 | 348 (#348) the version-explicit pipeline workspace — renumbered at merge after L3b's v1.94 | **§2.1**: `/pipelines/{id}` is a UI page route. **§4.3**: the explorer's Open action (detail header) and every historical `/pipelines/{id}/editor` link enter the canonical read workspace; the version rows' Open links carry their row's version (they targeted the editor with none — every row's link was the same URL, the design record's second migration trap). **§4.4**: the route statement — `GET /pipelines/{id}?version=N&tab=…` at the `pipeline.read` floor, explicit-version-or-404, current-pointer-first default, choose-a-version/empty states, the header's read-only version selector, and the old editor URL as a compatibility redirect (preserving a valid version/tab; the execute floor of 122 is gone with the page it guarded). Execute pins the VIEWED version on the wire (released included); the node-SQL GET carries an optional `version` the workspace always sends; the page's JSON blocks are `#pipeline-data` + `#pipeline-workspace` (the lifecycle draft-pin block and `draft.js` are gone). Floor/auth wording lives in auth.md v3.26; the page's layout itself is #349's. |
| 2026-10-01 | v1.94 | L3b (#10) the first-party dashboard pages — row added at merge (the lane's commits carried the sections without one) | **§3.4**: the landing item is **Home** (D58; the route `/dashboard` and its exact-match section unchanged) and the Build group gains **Dashboards** — the link to the tree page with a lazy disclosure beside it that expands ONE level of the tree in place (`/partials/dashboards/tree?scope=nav`, the same fragment the page renders). **New §4.21** Dashboards: the tree page `/dashboards` (one level per request; a leaf opens the board as a FULL navigation, `hx-boost="false"`) and the board page `/dashboards/{id}` (the server-declared ONE Plotly bundle on its script tag, the glue as a file, the in-page refusal state for a board that cannot run, the events pane's bounded poll). |
| 2026-09-30 | v1.93 | L1c-c (#10) transfer limits and evidence | **§4.17**: the `body_invalid` flash renders its toast (it was unmapped, hence silent — a refused batch said nothing on the screen that issued it), the toast text points at the REST answer's bound/count/key, and the two shipped branches whose `&#39;` entities sat inside fragment-expression literals (`missing_datasources`, `key_invalid`) — a latent render-500 on those flashes — now use typographic apostrophes. Guard: `PromotionErrorFlashRenderTest` (the body_invalid toast renders; the unmapped-key closure unchanged; red-first with the branch removed). No route, permission or role changed. |
| 2026-09-29 | v1.92 | L1c (#10 L1c) the transfer families on the promotion page — renumbered at merge after 340's v1.91 | **§4.17**: the two family tables in the parameter-set mould's shape inside the same form (`name="visualization"` / `name="dashboard"`, the Send column and role guard), the read-only arm, the four-list empty state and form condition, the flash's `visualizations=N dashboards=N`, and the action's six-argument `promote`. The plan lists are the lens (the dashboard's newer-than-target arm stated). Guards named in the section. No route, permission or role changed. |
| 2026-09-30 | v1.91 | 340 (#340, #335) lens residue + the purge dialog's kept list | **§4.3b, the Usage tab:** the parents half applies both halves of the promoter lens — the parent NAME, and under a narrowing view RELEASED parent versions only (the live-parents query returns DRAFT ones; a draft's number never reaches a promoter, 178b) — and the badge counts what the tab lists. **§4.6, the template screen:** the per-version count's pipeline arm honours the lens too (admitted pipelines, RELEASED working versions, re-derived from rows like the set and visualization arms; the whole view keeps the aggregate). **§4.3d, the purge-entity dialog:** names the draft templates the offer keeps and the sets/visualizations that hold them (#335), from the same port the REST `kept_draft_templates` reads. No route, permission or role changed. Guards: `PipelineBrowseModelUsageTest`, `TemplateUsageTest`, `PipelineLifecycleDialogModelTest`, `PipelineLifecycleDialogRenderTest`, each falsified. |
| 2026-09-30 | v1.90 | lane 336-b (#336) | **§4.20**: the refresh answer owns its selection — both completion arms are guarded by the page generation and the originating schedule, so a late reply for a superseded selection (or a page since left) marks nothing, toasts nothing and arms no poll; the outage flag lives on the selection's own state, so the first failure of a NEW selection toasts even though the previous one's did. Correction of the 336 review's F1; the v1.89 stale+toast contract is unchanged. No route, permission or role changed. |
| 2026-09-30 | v1.89 | lane 336 (#336 D8) | **§4.20**: a failed runs refresh keeps the runs shown and MARKS them stale — a `data-slot="runs-stale"` note plus one toast per outage (never one per poll) — until a refresh answers; the poll keeps its existing cadence. A malformed 2xx body rejects like an error (the same stale+toast path). No route, permission or role changed. |
| 2026-09-30 | v1.88 | lane 336 (#336 D4) | **§4.13 gains the degraded reads**: a failed members listing or key-owner read renders "Members could not be loaded" / "Key owners could not be loaded" (`ds-empty` notices, `data-members-degraded` / `data-key-owners-degraded`, fixed text — the failure's class + SQLState live in the controller's log line); a catalogued refusal still renders the empty state it always did. No route, permission or role changed. |
| 2026-09-30 | v1.87 | 320 (#320) dependency guards | **§4.6 the template screen:** the "Used by" card lists the parameter sets and the visualizations that pin the template beside the pipelines (its header counts each kind), and the per-version count's unit is "uses" — the guard refuses on all three, so the card and the refusal name the same things. **§4.3d/§4.6 the dialogs:** the template Purge, Discard and Purge-entity dialogs pre-read the SAME composed evidence the release service refuses with (the sole-draft purge asks the entity rule) and list sets and visualizations beside pipelines, buttonless; the pipeline Discard dialog and the **Usage tab** list the dashboards whose sources pin the version (lensed by the dashboard lens; a dashboard counts in the tab's badge — it is refusal evidence). |
| 2026-09-29 | v1.86 | 313 (#313, with #312) the promotion page offers parameter sets | **§4.17 Promotion**: the plan's parameter-set rows render as their own table — the pipelines table's frame, Send column and `canPromote` role guard, heading "Parameter sets", checkboxes `name="parameter_set"` — inside the form for a promoter and as a second read-only table for a reader; the "Nothing to promote" empty state fires only when BOTH lists are empty, and the success flash carries `parameter_sets=N` into the toast's sentence. The action reads the second field and calls the sender's four-argument `promote` (§8.3's order: templates → sets → pipelines); a set-only selection is a valid submit. Guards: `PromotionSetsRenderTest` (the sets table inside the same role guard; red with the table removed), `PromotionUiControllerTest` (the four-argument call, the counts in the flash), `PromotionTwoDeploymentE2eTest` Order 55 (the form post promotes a set to uat), `PromotionSetsBrowserTest` (light/dark screens of the page with a set candidate, against a stub target — the browser harness boots one app). No route, permission or role changed. |
| 2026-09-28 | v1.85 | 301 (#301, #273, #149) UI residue — the 287 security pass's observations 2–9, the discard dialog's schedules, the facts link | **§4.3d, the Discard dialog**: it now lists **the schedules that run the pipeline** (273) — the SAME by-target read the Usage tab makes, lensed for the caller, one row per schedule with its condition and stored next occurrence, under a note that says the consequence (the discard succeeds; each schedule then blocks at its next run — the §5.2 `pointer_null` shape — until repointed or deleted). **§3.7, keyboard viewports**: the `tabindex="0"` tab stop lands only on a viewport that actually scrolls (301) — the enhancer checks the computed overflow and the content, re-checked in `measure()`; a fitting page-flow frame (`overflow: clip`) keeps its inert `-1`. The history snapshot cleanups run on the **live page** an instant before it is snapshotted (301 — htmx clones only after the event), wording corrected in the shell and the enhancer; the app.css modal comment matches the class toggle the scripts actually do; the token test's duration rule reads the longhand properties too; the paged-sort rule's every-row-new decision is a node-tested seam (`allRowsNew`) and the dock's ancestor walk is linear in the batch (a Set). **The datasource facts' source-pipeline link is a full document load** (149) — it carried the one boosting `<a>` into the editor, which cannot initialise on a swap. Guards: `DataTableBrowserTest` (a non-scrolling frame keeps `-1`; the evidence names in the observer KDoc corrected to the numbers), `DataTableCssTokenTest` (longhand), `data-table.test.mjs` (`allRowsNew`), `PipelineLifecycleDialogRenderTest`/`RoleVisibilityRenderTest` (the schedules evidence, the promoter contract), `DiscardDialogSchedulesBrowserTest` (two schedules; a promoter sees no Discard verb), `DatasourceFactsLinkBrowserTest` (the full load, red on the boosting tree at the issue's own symptom). No route, permission or role changed — the dialog's read rides `pipeline.version.manage` as before. |
| 2026-09-28 | v1.84 | 298 (#293) every surface answers as `visibleTo` | §4.2 and the role-guard table: the dashboard's stat tiles count a promoter's OWN runs (`listVisibleTo`'s third arm, in SQL) instead of zero — the explorers' Runs tabs and the search palette already did; the Recent executions panel stays `execution.read` and undrawn for a promoter. The API console's Legacy paths row withholds Unpublish when its echoed path was cut (a database-written path past the grammar's 200 characters — an unpublish of the echo would address another path) and says so on the row. Default ruling by the orchestrator, recorded as a decision the owner may overturn. |
| 2026-09-28 | v1.83 | 194d (#194) parameter sets — numbered at merge after 287's v1.82 | **§4.17 Promotion**: the plan carries parameter-set rows through `Plan.promotableParameterSets` (the record's §8.3 — pushed after templates, before pipelines); the table markup stays 282's. **§4.6/§4.7 the template explorer/detail**: the per-version in-use count and the used-by list now cover PARAMETER SETS too (`application`'s `TemplateUsage` composition) — a set pinning a template version is one user, counted beside the pipelines, and the `template.in_use` delete refusal names `referencing_parameter_sets`. The lens covers sets on every read (the same `LensedView`). |
| 2026-09-28 | v1.82 | 287 (#287, #288) the modals' Back path + the data table's five follow-ups — renumbered at merge after 286's v1.81 | **§3.7 amended.** The **history snapshot carries no style a script wrote through ONE seam** (287): shell.js's one `htmx:beforeHistorySave` listener runs the `window.__dpHistoryStyleCleanups` registry — data-table's write set (frame, table, cols) and the version menu's placement (closing open menus first, the shell's no-transient-chrome rule) registered there; data-table's own listener is gone. The three hand-rolled modals (`#register-modal`, `#key-modal`, `#create-template-modal`) show and hide through the existing `u-backdrop-hidden` **class**, never an inline `display`, so no `style` attribute exists to serialise and Back carries 0 CSP violations (`ModalBackBrowserTest` walks all four writers: open → close/placed → boosted away → Back). **The paged sort's state is never silent** (288 #1): a page whose rows are NEW to the table (the htmx pagers' whole-frame swap; any keyed-by-content re-render) clears the client sort — the server's order shows, `aria-sort` back to `none` everywhere; a held sort NAMES itself in the button title ("Sorting this page by X — …"), which is the truth for the dock's cursor paging, whose index-keyed in-place re-render keeps the held sort by element identity. A row edit and an append keep the sort. **The observer pays one discovery query per top-level root** (288 #2): a batch's added nodes with no ancestor in the batch carry the whole subtree, and a root inside an upgraded table is one of its rows — refresh's target walk owns it. **Durations are tokens** (288 #3): the skeleton's shimmer takes `--duration-slower` (the ds-spin precedent); the frame's length custom properties stay — they are the component's own contracts, read by name from the script, the §3.7-sanctioned mechanism. **The viewport is keyboard-scrollable** (288 #4): the enhancer flips the server's `tabindex="-1"` to `0`, arrows scroll, `.dt-viewport:focus-visible` draws the ring inside the box. The stale `#app-main` history comment corrected (288 #5). Guards: `ModalBackBrowserTest` (new), `DataTableBrowserTest` extended (the htmx page swap, the dock's held-sort title, the keyboard viewport, the discovery count), the token test's duration and focus-ring rules, `data-table.test.mjs` +2 (`topLevelRoots`, `activeSortTitle`). No route, permission or role changed. |
| 2026-09-28 | v1.81 | 286 (#275) R3 on the Runs tabs and the palette — numbered after 282's v1.80 (renumber at merge if 287 lands first, keep both) | **§4.3b**: the pipeline explorer's Runs tab (and, by the same read, the template explorer's Runs tab and the search palette's executions group) follows §4.8's R3 — own runs plus every SCHEDULED run for a viewer or author; they read own-only until #275. A promoter reaches these `pipeline.read` / `template.read` panes without `execution.read` and sees her own runs only. No route, permission or role changed. |
| 2026-09-27 | v1.80 | 282 (#282) the data table — numbered after 280's v1.79 (in flight; renumber at merge if 280 has not landed, keep both) | **New §3.7 (normative) — the house data table**: `.dt-frame` › `.dt-viewport` › `table.ds-table`, one rule set (`data-table.css`, after app.css) and one enhancer (`data-table.js`, after shell.js), ported from the owner-approved preview: the header sticky in its viewport over opaque rows, the frame's `::after` CAP over the scrollbar track beside the header (measured `--dt-sbw` / `--dt-head-h`, 0 on overlay scrollbars), client sort over the rendered rows (`aria-sort`; nulls last both ways; "Sort this page by …" on a paged list), resize on a `<colgroup>` with `table-layout: fixed` (a drag changes one column), a frozen first column (`dt-freeze`), keyboard rows (Enter through shell.js's `data-href` handler), states as rows of the same table, re-init on htmx swaps and on non-htmx row producers (a MutationObserver), the history snapshot stripped of CSSOM styles; CSP-clean (no `style` attribute anywhere; CSSOM only). The 19 templates' 22 tables swept, each decided FIXED or PAGE-FLOW (the §3.7 table); PAGE-FLOW's header sticks to `<main>` under the top bar — measured: Chromium stops a sticky cell at the scroller's content edge, so the script sets `--dt-flow-top` to minus the scroller's padding. **§2 principle 9**, **§3.3 rule 2**, the primitives rows, §4.4's result grid, the §5 example, **§5.1** (empty and first-paint loading as rows of the table). Legacy classes: app.css's boxed `.ds-table` block folded, `.app-card-table` reduced to one clip rule, `.u-scroll-x` and `.tplx-fit-table` retired, `.pe-result-table-container` reduced to the dock's inset; `.facts-table` / `.mini-table` are the marketing site's, untouched. Guards: `DataTableCssTokenTest`, `data-table.test.mjs`, `DataTableBrowserTest`, `AppCssTokenAuditTest`'s card rule moved to the viewport. No route, permission or role changed. |
| 2026-09-27 | v1.79 | 280 (#280) the form says what is wrong | **§4.20, the form**: every field-placed §20 refusal renders the code's OWN user message, not the family line — nine per-code `ApiErrorCatalog` rows (the name grammar with no leading slash, name-taken, the pattern, the minimum interval, the zone, the pipeline's `target_not_found` / `target_not_released` / `payload_invalid` / `executor_unknown`); `ApiErrorCatalogUserMessageTest` holds the set to `model.js`'s `FIELD_BY_CODE`. A draft-only pipeline is named at PICK time — the release-first sentence at the pipeline field, cleared when a schedulable pipeline is picked; the suggestions stay complete (a list entry's DRAFT status also covers a released pipeline with a draft in flight). The name help says a leading slash is not part of a name. The form's browser suite asserts the catalogue's words and covers the draft-only flow (red on the base at each step, logged). |
| 2026-09-27 | v1.78 | 254 (#254, #255, #256) three small UI defects, the switcher and the rail honest | §3.4: the switcher's options are full-paint facts — every page-route mutation that changes the option list navigates in FULL; the three lifecycle verbs join the create form (#256): deactivate → the option absent without a reload, reactivate → present, delete → absent (`WorkspacesCreateBrowserTest` extends #170's no-reload assertion to each verb's before/after list; on the base the boosted swap left the rail every option rendered before the mutation). §4.7: the panel head yields before the rail scrolls (#255) — `.te-panel-head` wraps and its h2 carries `min-width: 0`, so at the splitter's 220px floor the `Key/Value` / `JSON` tabs drop below the `Render Context` heading instead of holding the rail open at `scrollWidth 228 > clientWidth 220`; the walk pins `scrollWidth == clientWidth` light and dark at 1100/1440/1920 and at the floor (`TemplateEditorContextRowBrowserTest`). |
| 2026-09-27 | v1.77 | 250 (#250, #259) R3 on the UI + the Usage tab's schedules | **§4.8 / §4.2**: the executions screen and the dashboard's recent executions and run figures read `findVisible` — a viewer or author sees their OWN runs plus every SCHEDULED run of the workspace (#250, rest-api §10.1's R3), a scheduled run's row stating its origin (`SCHEDULE`) in the via column. **§4.3b**: the pipelines explorer's Usage tab gains a third heading — the live schedules whose target names the pipeline (#259), name + link to `/schedules?id=<id>` + enabled/paused/blocked, read through the lensed `ScheduleService.listByTarget`; a schedule is not refusal evidence, so the tab badge does not count it and the empty state now names all three absences. `ExecutionHistoryBrowserTest` holds a viewer's list to scheduled-visible / other's-own-absent; `ExplorerDetailBrowserTest` reads the third heading off the tab. |
| 2026-09-26 | v1.76 | scheduler lane 3 (#9, slice 3) — numbered after origin/main's v1.75 (242b/230 may take it; renumber at merge, keep both) | **§4.20, the form**: a `DATE` parameter's field gains the **binding source** selector — Fixed value (default) / Today / Yesterday; a preset hides the fixed-value input by class and travels in `payload.parameter_bindings`, resolved per run on the schedule's frozen reference; the selection round-trips on edit; a literal binding shows its value and keeps its envelope while untouched. **§4.20, the run dialog**: beside the frozen parameters, **resolved parameters** (`prepared.resolved_parameters`) — what the run executed with. No new route, verb or role; the browser suites gain the bindings cases (`SchedulesBindingsBrowserTest`). |
| 2026-09-26 | v1.75 | scheduler lane 2 (#9, slice 2) — numbered after origin/main's v1.74 (243) | **§4.20 Schedules, new**: `GET /schedules` (`schedule.read`) — a shell whose every read and write is a call to REST §20 (the scheduler design revision §6: no partial route, reads included): the folder explorer (§20.1 by prefix, a level's folders derived from the names, search over every rendered column), the detail (blocked reason with Unblock beside it; overview; the next five from §20.9 with their offsets; parameters; runs with due / started / took and the execution link), a run's dialog with the MERGED Messages (the run's trail + the execution's durable events, one time order, labelled by source; an unknown run's recovery named), and the one form for create and edit (presets that write the pattern, the timezone, a live §20.3 preview, the current version's declared parameters, field-level refusals, idempotency per attempt, the stale-revision 409 in the form with a reload). §3 the rail's Operate group gains **Schedules** after Executions, for every role. §4.3e gains the page's verb rows (`canAuthor` = the five write rows) and the execution half's `canReadExecutions`. §5.1 Shape D names the page as the second client-originated toast case. |
| 2026-09-26 | v1.74 | 243 (#243, #170, #159) three small UI defects, measured and pinned | §4.7: a Render Context row fits its rail (#243) — the rows' inputs carry `min-width: 0` and the remove control never yields width, so the row's key, value and × end inside the rail at 1100/1440/1920 and at the splitter's 220px floor, static row and Add-Row clone alike (`TemplateEditorContextRowBrowserTest` measures the edges; on the base the row was 476px in a 320px rail). §4.13: the create form navigates in FULL — `hx-boost="false"` (#170) — because a boosted swap replaces only `#app-main` and the rail's switcher (filled on every full render) kept the pre-create options until a manual reload (`WorkspacesCreateBrowserTest` asserts the row lands in BOTH the table and the switcher). §3.4/§3.6: the 768–1099px band has a search entry point (#159) — a topbar icon button lit only in that band reveals the topbar copy in place (no third copy of the control) and ⌘K drives it, where previously neither copy was displayed and the chord did nothing (`MobileShellBrowserTest`'s band arm, red on the base; plus the phone-width drawer arm the issue named, and HeaderSearchBrowserTest's desktop arm pinning the button absent at ≥1100px). |
| 2026-09-25 | v1.73 | 233 landing (#233, #248) the chip's retirement recorded | §3.4: the top bar's inventory no longer lists the MCP-key chip; the chip bullet becomes its retirement record (keys v2 A15 — every key created on the Keys page, plaintext once at creation; `GET /partials/mcp-key/secret` survives for the V37-migrated sealed copies with no caller, #248). §4.10: `/settings/api-keys` points at the Keys page, not the top bar. The two chip browser tests, the shell's dead copy handler and the screenshot tool's chip wait left with it. |
| 2026-09-25 | v1.72 | 7e (#7) the semantic link | §4.3d **the Release dialog warns on a `needs_review` pin**: one row per pinned template version citing a retired learned fact ("cites a retired fact: … — superseded by …"), above the confirm, never blocking it — the same port read the REST release's `warnings` come from. §4.6: 7d's `needs_review` marker is now computed (the tree/search rows' own flag, the detail's working version, the editor's displayed version, the node card's pin read). No new verb, no new route. |
| 2026-09-25 | v1.71 | 7d (#7) the transform UI | **§4.7a (new): the transform face** — four textarea panes over a `jsonata`/`javascript` template (losslessly pretty-printed blocks), editable only on the working draft for an author; **Save draft** (`POST /partials/templates/transform-face/save`, `template.update` — 7b's deserializer, validator and draft service under the draft's hash; refusals name their pane) and **Run suite** (`…/run-suite`, `template.evaluate` — the panes as typed, owner ruling 2026-09-25: the gate's verdict plus a per-case list with the first difference's path, both sides and every invariant); `GET /partials/templates/transform-face` for the version select. §4.3e gains the face's two verbs. **§4.6:** the four types in the filter and the create modal, the modal's Contract/Invariants/Tests field prefilled with the record's example, the transform detail's Mode and Inputs, the `needs_review` marker behind 7e's flag. **§4.4:** the TRANSFORM node card (its accent and glyph, the language resolved from the pin, inputs → output, rejects + strict) and its Details rows. The `readOnly` rule's home is `TemplateSourceModel` (moved from `fillSource`, unchanged). |
| 2026-09-24 | v1.70 | 215c (#215) after the key model | **§4.19 describes the Keys page and dialog as slice (b) built them**: the role column is fixed by the kind (`api caller` / `promotion receiver`), with nothing left for a slice to offer, since each kind carries exactly one role (record §3.1, A1); the Acts-as cell renders "<key name> (API key)"; the New API key dialog is one card per kind, each naming its fixed role and its create permission (`api_key.create` for workspace admins and super admins, `server_key.create` for super admins only), with no role field and no admin role. The record's §6(c) is marked delivered. No screen, route or verb changed. |
| 2026-09-24 | v1.69 | 215b (#215) key identities | **§4.19: the Keys table gains Role and Acts as** — the key's role (`api caller` / `promotion receiver`, fixed by the kind until slice (c)) and the key's own identity, which its runs and received versions are attributed to; Created by is `api_keys.created_by`. **§4.11: the API card's Scopes row is a Role row** (the session's role in the active workspace). §4.12: the users list shows people only (`kind = 'human'`). §3.4's role badge no longer narrows for a key (no key renders a screen, B2); §4.18's refusal note and §3.3's column table follow. |
| 2026-09-24 | v1.68 | 215a (#215) the permission catalog | **§4.3e's last column is the §7.6 catalog permission** a verb answers to (`pipeline.release`, `datasource.test`, `workspace.members.manage`, …) instead of the retired operation, and every other section names permissions the same way; the booleans and every template are unchanged. §4.19: **a `server` key's Delete renders for a super admin only** (owner ruling 2026-09-24, `server_key.revoke`) — a workspace admin sees the row without the verb. §4.5's "Rendered for" paragraph corrected: Test renders by `canExecute` (`datasource.test` follows execute since 2026-09-20 — the template already did; the prose still said `canAdminWorkspace`). |
| 2026-09-22 | v1.67 | #208 own row + super admin row | §4.13: no verbs on the caller's own member row (`409 workspace.self_membership` behind them); a super admin member reads "super admin" with no role dropdown. |
| 2026-09-21 | v1.66 | 200 (#200) membership-bound keys | §4.13 members row: the member's key state ("has a key" / "no key" — never an id or prefix) and a **Revoke key** verb, drawn only when a live key exists, inside the same `canAdminWorkspace` guard; posts `POST /workspaces/{name}/members/{userId}/key/revoke` through the ONE `WorkspaceService.revokeMemberKey` the REST twin calls, and toasts `member_key_revoked` (the member stays; the next sign-in mints fresh). §4.3e's members row lists the verb. |
| 2026-09-23 | v1.66 | 213 (#213) show-once MCP key | **§3.4: the MCP-key chip is copyable ONCE** — the first Copy's fetch destroys the server-held copy in the same statement that serves it, the chip re-renders without a reload (`GET /partials/mcp-key/chip`, swapped in by the copy handler) with no Copy button and a "Copied already — delete it and sign in again to get a new key you can copy" title (the pre-V31 wording is gone; V32 cleared every such key), and a reload changes nothing. The chip's three states (copyable / copied / none) are spelled out; §4.10's pointer says "copy it once". `McpKeyShowOnceBrowserTest` pins click → clipboard value → no-Copy → rotate; `RoleVisibilityRenderTest` pins the states. |
| 2026-09-21 | v1.65 | 191 (#187) — OIDC identity | Login page gains `?error=identity_mismatch` (the email is already linked to a different sign-in identity; nothing changed, ask an administrator). §4.12 gains the **Reset identity** verb — its own literal route and `USER_IDENTITY_RESET` row, rendering the shared row-swap + toast. |
| 2026-09-21 | v1.64 | 188 (#188) security headers + CSP | Doc-only for the screens (no visible behaviour changes). §3.4: the rail's first-paint script is `/js/rail.js`, not an inline block — the enforced CSP (`script-src 'self'; style-src 'self'`, no `'unsafe-inline'`) forbids every inline script, `on*=` handler and `style=` attribute in a template; the five page scripts and twenty-three handlers moved into `/js` (`api-keys.js`, `datasources.js`, `template-create-modal.js`, `password-card.js`, `rail.js`; the rest into `shell.js` and `template-editor/lifecycle.js` as `data-action` controls), the executions rows navigate by `data-href`, and the type-coloured editor elements carry `data-type` instead of a style attribute. The pipeline editor's route alone allows `'unsafe-eval'` for Alpine (#195). Guards: `InlineScriptAuditTest`, the browser suite's zero-violation rule, `SecurityHeadersTest`. |
| 2026-09-21 | v1.63 | 180 (#180) roles R4 — deactivation | Doc-only. §4.11: `?error=inactive` is also where a session deactivated mid-life lands (cookie cleared by the filter, redirect by the entry point). §4.12: the deactivation window sentence names the knob and the new code `auth.principal_deactivated`; nothing is revoked, Activate restores. |
| 2026-09-21 | v1.62 | 185 (#185) script-block escaping | §4.4: the editor's two JSON blobs are written through `ScriptSafeJson.forScriptBlock`, so a closing-tag sequence inside any free-text field the pipeline carries cannot close the `<script type="application/json">` block the blob is inserted into (stored XSS, #185). One shared escaper with the docs' and FAQ's JSON-LD writers; guarded by `PipelineEditorJsonRenderTest` (jsoup) and `ScriptBlockUtextAuditTest`'s closed `th:utext` allowlist. |
| 2026-09-21 | v1.61 | 179 (#179) roles R3 — keys | **§4.3e / §3.4: the top bar gains the MCP-key chip** (D16, ruling 7) — prefix, Copy (fetches the secret from `GET /partials/mcp-key/secret`; never in the page) and delete-to-rotate for every role on their own key. **§4.18 read-only again**: the keys card and its modal left the console for **§4.19 `/api-keys`** (new; `MANAGE_API_KEYS` — ws_admin + super admin), which carries create (Kind → Name → Expiry → Associations), delete and per-key association; the console's endpoints table gains the associated-keys-by-name column with an admins-only Manage link, and keeps the MCP card. **§4.10 repointed**: the settings screen points at the top bar (your MCP key) and §4.19 (the workspace's). The avatar menu's "API keys" link leads to `/api-keys` for the roles that hold `MANAGE_API_KEYS` and is absent otherwise. `ApiKeysPageRenderTest` pins the page; `ApiConsoleRenderTest` pins the console's new shape. |
| 2026-09-21 | v1.60 | 178 (#178) roles R2 | **The promoter lens on every screen** ([Auth §11A.1](auth.md#11a1-the-404-rule)): the pipeline and template explorers, detail panes, the editors' read-only view, the header search, the dashboard's Pipelines tile and the rail badges show a promoter the released-and-newer set and nothing else (§4.2, §4.3e's new row, §4.7); a hidden object's URL is the not-found page. New fragment `partials/lens-unavailable` — the promotion page's "Could not read the target" sentence, rendered by the list fragments (`pipeline-tree-level`, `pipeline-search`, `template-tree-level`, `template-search`, `search`) in place of their empty state when a lensed principal's list is empty because the target could not be read (fail closed). §4.17: the plan is the lens's own computation, cached per workspace. |
| 2026-09-20 | v1.59 | 177 (#177) roles R1 | §4.13 the workspaces page is a **workspace admin's** (D13: `WORKSPACES_READ` → `ws_admin`; the rail draws the item for admins and for a principal with no workspace; the switcher stays every member's under its own `WORKSPACE_SWITCH` row); the members table is **one role dropdown per row** (D22) with a Save through the one htmx partial (`POST /partials/workspaces/{name}/members/{userId}/role`, row swap + toast), the add form takes the same dropdown, the checkbox script is gone, the last-admin toast names the workspace admin role. §4.12's create form takes the dropdown too. §4.3e rewritten to the ratified rows: Release and Switch are `canAuthor` (D8), datasource **Test** is `canExecute` (follows execute — ratified), the promotion page is `canReadPromotion` (rule 13), the Executions rail item and the dashboard's runs are `canReadExecutions` (D11 — the promoter has neither), the lifecycle and datasource dialogs guard their verbs in the markup (the route-guarded exemption list is empty). §3.4 the badge prints the one role (`workspace admin`, not `admin`). §4.4/§4.7 the promoter's editors are read-only with no Release. Vocabulary: role and permission; the word "flags" leaves this document (D21). |
| 2026-09-19 | v1.58 | 161 (#161) the login page renders the one brand mark | §3.4 brand mark: `login.html`'s card brand and `layouts/auth.html`'s brand link had kept the retired filled-tile SVG inline (163's sweep rendered the rail, the top bar and the site only); both now include `partials/brand-mark.html` exactly as `layouts/default.html` does — no CSS change, the tile and mark are sized by class. `BrandMarkParityRenderTest` renders the login page and the forced-password gate as two more surfaces (each divergence named) and sweeps every template source for the retired rect. |
| 2026-09-19 | v1.57 | 172 (#172) | The `/api-console` route rationale reworded for the re-rooted published-endpoint tree (R-EP5); no screen, route or verb changed. |
| 2026-09-17 | v1.55 | 159 / #148 Start runs on a human press; Cancel from the marker | §4.4: the Start disc now starts a run from a HAND's click — the live "Start does nothing" was Cytoscape's mousedown re-rendering the disc under a held button (no click ever dispatched); the press is stopped before Cytoscape. While the run is active the disc is the **Cancel** control (`Cancel execution`, the word Cancel, square glyph, danger fill, never `aria-disabled`, the toolbar's own `cancelExecution()`), Start again on any terminal state; a viewer who may not execute keeps the plain marker; focus survives the disc's re-render. ([Pipeline Editor §5.3b](pipeline-editor.md); `PipelineEditorStartMarkerBrowserTest` presses the disc the way a hand does.) |
| 2026-09-17 | v1.54 | 151 addendum / #144 boundary redesign | §4.4: Start and End are **shapes** (a disc and a rounded square, the word under them), not pills; **Start runs the pipeline** for a viewer who may execute (`role="button"`, Enter/Space, the toolbar's own `canExecute` stamped as `data-can-execute`, the same `executePipeline()`), `aria-disabled` + pulse while Running…; End's fill is the outcome, with the run clock's elapsed; `#cy-canvas` is `role="group"`. Corrects v1.51's "never executable" reading: the markers are still not executable NODES, but Start is a run TRIGGER. In a browser session viewers may execute (D-R3), so they get the button too. |
| 2026-09-17 | v1.53 | 151 / #127 arrows vs. writes | §4.4: a `depends_on` edge is an ORDERING (`dependency` kind; `active` still / `satisfied` / `unmet`), never a transfer — no copied row count on any edge, no motion along one (the rAF flow retired); the write is the producer's **output port** row (destination, kind word, measured state from the 149 reducer: waiting amber and still, writing the only flow, committed/not observed/rolled back honest words, CTAS `one statement`); stream loss freezes without inventing; the recovery poll settles End and closes open operations unobserved; legend `Depends on` / `Output write`; Details `Depends on` / `Required by`; arrow tap announces its meaning. |
| 2026-09-17 | v1.52 | 155 / #112 empty rail reclaims its width | §4.7: a template-editor rail with NOTHING to show (a reader — viewer or pure promoter — on a template with no imports: the Render Context panel is author-only, the Imports table follows the template) is not rendered, its splitter handle goes with it, and `.te-body` collapses to one column so the read-only source takes the width. Author context, the Imports read, remembered `--te-side-w` and the narrow-viewport clamp are untouched (TemplateEditorRailBrowserTest pins all four). |
| 2026-09-17 | v1.51 | 150 / #126 execution boundaries + #135 abort pulse | §4.4: the canvas derives view-only **Start** and **End** markers — Start to every root, every leaf to End — dashed-border pills whose End word is the authoritative execution outcome (Finished/Failed/Stopped), never a node event; boundary connectors are a distinct element kind for #127, never row-transfer labelled; eligible roots show no flow until `node_started`. A cancelled-or-never-started node ends Aborted beside them (#135: the terminal `node_progress` sample or the `pipeline_failed` sweep stops the running pulse; minimap and a11y agree). |
| 2026-09-17 | v1.50 | 149 correction / #125 review | §4.9: the Node operations table's commit column says `not observed` for a terminal operation without commit evidence, keeps `committed` on a failed node whose commit was confirmed. |
| 2026-09-16 | v1.49 | 149 / #125 measured node operations | §4.4: the editor card footer shows the measured operation while a node runs, the Details pane its Operation/Progress/Rows/Time in/Commit rows, the a11y list the same as a description. §4.9: the execution detail page gains the **Node operations** table derived from the durable `node_progress` record — destination, last state, counts, commit badge, time share; never a commit that was not observed. |
| 2026-09-15 | v1.48 | 143 viewer editor + navigation (T315) | **Readers open templates read-only; the rail's Admin follows authority.** §4.7/§4.3e: `GET /templates/editor` floors at `read` (the explorer's Open links led every reader to a 403); `readOnly` = author capability ∧ version rule on the page AND the source partial, so a viewer or a pure promoter sees every version — the working one included — in the read-only pane with the version select and Imports kept; the editable textarea, Preview and the context rail are `canAuthor`; every version-row Open carries `version=`. §3.4: the Admin item renders only where it leads — `/admin/users` for a super admin (judged without the workspace, key-narrowed), `/workspaces#workspace-members` (a full navigation) for a workspace admin — and is absent for viewers, authors and pure promoters; `UiWorkspaceAdvice` stamps `navAdminUsers`/`navAdminMembers` from `RoleModel.shell` on every render. §4.13: the members section is `#workspace-members`. §4.17: a reader's plan is a plain table, no form/Send/checkboxes. `AdminUsersController` and the matrix are untouched. **Found on the way (JarSmokeE2eTest):** a super admin's `read` key rendered as an author — the auth predicates short-circuit on `superAdmin` before their key conjunct — which the editor's old floor had hidden; `RoleModel` now states the scope conjunct on `canAuthor`/`canPromote` too, so the key gets the reader's page (the predicate itself is unchanged; the interceptor already refused the writes). Evidence: `ViewerEditorRenderTest`, `AdminNavRenderTest`, `ViewerAccessBrowserTest`, `ViewerAccessE2eTest`, `RoleModelTest`. |
| 2026-09-15 | v1.47 | 142 release cascade UI | §4.3d/§4.4 **the Release dialog cascades.** A DRAFT template pin is no longer a dead end: the dialog (both explorers' and both editors' one partial) renders a consent group — "Also release these N draft templates with the pipeline", checkbox `releasePinnedTemplates` checked by default, one row per pinned version with "also pinned by K other draft pipelines" from the used-by service — and Release is enabled; unchecking withholds Release (`lifecycle-dialog.js`'s consent arm, the third of the typed-confirm / min-chars family, folded into ONE evaluator with the 140 min-chars gate so both can withhold the same button) and restores the pre-142 refused wording by CSS `:has()`. The POST posts the flag; the toast lists the templates released with the pipeline; the editor leg's flash is `released_with_templates`. DISCARDED / MISSING pins keep the refused branch with no button. `ReleaseCascadeBrowserTest` covers the checked group, the shared line, both toggles and the audited landing. |
| 2026-09-14 | v1.46 | 140 release checks UI | §4.3b/§4.3d **the release checks are on screen.** New `partials/pipeline-checks` (definitions + latest runs, read-only GET; the same fragment re-rendered from the FRESH outcomes of `POST …/checks/run`, `via = ui`, session-only on both) behind `PipelineChecksPartialsController`. The explorer detail's Overview gains a Checks sub-section when the working body declares checks (lazy `hx-get` + a Run checks button at the execute floor); the editor's Details pane shows the count read off the loaded body and lazy-loads the same partial. The Release dialog on a checked draft runs the checks as it opens: the submit starts disabled and the run's fragment decides the footer OOB — all pass enables Release; anything short of PASS withholds it and offers the Override disclosure (required ≥ 10-char reason armed by `lifecycle-dialog.js`'s min-chars pair, which re-arms on swaps inside an open dialog; the overridden ids named, the reason audited on `pipeline.version.released`). |
| 2026-09-14 | v1.45 | 137 mail notices | §4.12 admin users: with mail configured the create/reset notice shows "Emailed to \<address\>" with the send's outcome (sending → polled `/partials/admin/users/{id}/mail/{kind}?act=` → sent / failed with the error) and never the one-time password; mail off is unchanged. |
| 2026-09-13 | v1.44 | 127 beta feedback setup | §3.4 **the avatar menu gains "Report a problem"** — the GitHub bug form, `target="_blank" rel="noopener"`, the fourth `menuitem` between API keys and Log out, its URL read off the new `SitePages.REPORT_PROBLEM_URL` constant through `SiteOriginAdvice` (no template types it); the v1.42 close-on-choice rule covers it, and `AppShellBrowserTest` pins the item count, the arrow-key order, the new tab and the app tab not navigating. §4.16's signed-in docs index header carries the same link from the same constant. The lucide sprite gains the `bug` glyph (manifest subset + sha recorded). The public side is §4.15-adjacent: the beta line renders from `SitePages.RELEASE_STAGE` on the hero and in the site footer, `/roadmap` gains the known-limitations section (docs/ROADMAP.md §2.2 read the other way), and `/faq` gains the "Support and feedback" group the footer links as `#support-and-feedback`. |
| 2026-09-13 | v1.43 | owner testing round, day 3 — the scrollbar | §5.1 **the skeleton never lands on body, and never in the history snapshot.** The owner's `/dashboard` document scrollbar (R11): `document.body`'s last child was a `.app-target-skeleton` row, static, 64 px past the viewport. Root cause: a boosted navigation's htmx target is `<body>` at `beforeRequest` (shell.js retargets at `beforeSwap`), so a navigation slower than the 150 ms arm appended the row to body — outside the `100dvh` shell; htmx snapshotted the page for history with the row in it (the snapshot is taken during the swap, before `afterRequest` removes the live one), and Back restored it as markup no tracker owned. `AppShellBrowserTest`'s guard could not see it: it measures plain loads, never a boosted-then-Back path. Fix in shell.js: `swapTargetFor` resolves a boosted request to `#app-main` at BOTH ends; `htmx:beforeHistorySave` strips live skeletons and busy marks; `htmx:historyRestore` and `pageshow` purge orphans (heals caches poisoned before the fix). Pinned: four `shell.test.mjs` cases through the real listeners; `ShellBusyBrowserTest` holds a boosted GET past the arm, asserts the row is inside main, settles, goes Back, and asserts no skeleton, no body busy mark and a zero-overflow document. |
| 2026-09-13 | v1.42 | owner testing round, day 3 | §3.4 **the avatar menu closes on a choice.** It closed only on Escape and an outside click, so choosing a mode left it hanging over the page and Settings / API keys — boosted swaps of `#app-main` alone — carried it open onto the next screen (the settle path closes only the phone drawer). shell.js: any `[role="menuitem"]`, `[role="menuitemradio"]` or `.app-swatch` inside `#app-user-menu` closes the menu and returns focus to the avatar (`MENU_SELECTION`, unit-tested through the real body listener; `AppShellBrowserTest` chooses a mode and Settings and waits for `[hidden]`). The document-scroll guard now also walks both editors and a 1636×1850 window (owner report of a document scrollbar, 2026-09-13, not reproduced in the harness — see the ledger). |
| 2026-09-13 | v1.41 | 124 site facts derived | **§4.15 — the site's numbers are derived, not typed** (owner ruling 2026-09-13: eight engines, not six — H2 is a legitimate engine, LAKE is dp-lake, tempdb implicit and never counted). New `SiteFacts` (engine count/list from `Dialect.entries`, tool counts from `McpToolCatalog`, calculator kinds, API-key kinds, learned-fact kinds/scopes, demo engine counts from the manifests) exposed to every template by `PublicPage.render`; `SitePages`/`SiteFaqs` titles, descriptions and answers are string templates over it, one `numberWord()` helper for prose. H2 and dp-lake gain `/mcp-server/{engine}` pages; `SiteEngineFactsGuardTest` tightened from containment to EQUALITY with `Dialect.entries`; new `SiteHandTypedCountsGuardTest` fails the build on a hand-typed count in any site template or `ui/site/` Kotlin file (red at base on 40 hits; green only when every count renders from `${facts…}`). |
| 2026-09-13 | v1.40 | 122 viewer executes (owner testing round, day 2) | **The route caught up with the record.** D-R3 and §4.3e already gave the viewer a read-only pipeline editor with Execute; the route refused them at the door — `GET /pipelines/{id}/editor` was floored at `MUTATE_PIPELINES_TEMPLATES`, so the explorer's Open link (the only door to Execute) answered 403 `auth.scope.insufficient`. The floor is now `EXECUTE_PIPELINE` — the operation the screen exists to perform for its lowest role — so an `execute` key and a viewer session reach the page while a `read` key stays out; the template editor keeps the author floor (§4.7 note corrected to match). §4.3e states the Open route and the 114 read-only line; RoleVisibilityBrowserTest walks a viewer explorer → Open → Execute (zero ≥400 responses, terminal state reached) and JarSmokeE2eTest pins the key boundary (`execute` 200 / `read` 403) in one test. |
| 2026-09-12 | v1.39 | owner testing round, day 2 | Two defects the owner saw everywhere a tree or a version list shows. §3.4 icons: **a leaf row keeps the chevron slot** (`template-tree.css .tpl-leaf` pads it; the guide's tick runs on to the file glyph), so a leaf's glyph and label sit level with a sibling folder's and one indent right of its parent's — before, the glyph sat IN the chevron slot and every leaf's label landed at its parent's x. §4.3b: **the ⋯ menu is a top-layer popover** (`popover="manual"` on `.tplx-vmenu-list`, placed by `lifecycle-dialog.js`'s pure `menuPlacement` — under the ⋯, above it when the viewport has no room below, clamped inside it) — it was `position: absolute` inside `.tplx-tabpanel`, which scrolls, so on a one-row list the open menu fell into the panel's scrollable overflow and never showed. `TreeIndentAndVersionMenuBrowserTest` pins both in both explorers (label offsets to the px; the menu's centre hit-tests to the menu). |
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
| 2026-08-07 | v1.1 | consistency campaign | Per [SPEC-REVIEW-2026-08.md](SPEC-REVIEW-2026-08.md) §2.12: route convention §2.1 (pages / `/partials/**` / `/api/v1/**`, htmx never calls the JSON API) and all `hx-*` endpoints re-pointed at `/partials/**` incl. §4.10 API keys [1]; template-editor context form replaced with free-form key-value/JSON input — templates no longer declare variables [1b, D3]; §5 htmx example fixed (`hx-include` + `th:attr` `hx-vals` instead of `${q}` interpolation) [2]; §4 scope column declared a view of the authoritative [Auth §7.6](auth.md#76-operation-matrix--the-permission-catalog-authoritative) matrix, datasource test corrected to `author`, key scopes ⊆ creator's scopes [3, D15]; §4.11 theme preference persisted on the `users` row via `PATCH /partials/profile/theme`, not session state [4]; §4.11 provider badge renders the configured provider `display-name` [5]; §4.9 result panel rebuilt on the uniform cursor with the TTL-expired state and `format`-parameter downloads [6, D9]; new §5.1 standard states (empty / loading via `hx-indicator` / errors via the `response-targets` extension into `#toast`) [7]; CSRF via `dp_csrf` cookie + `DP-CSRF-Token` header wired in the layout [D10] |
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

| 2026-09-19 | v1.57 | 173 (#173) SEO and AI-readiness coverage before indexing | §4.15: the `/compare/dagster-vs-airflow` editorial page; every measured search phrase pinned to a page and surface by `SiteKeywordCoverageTest` (heading renames on eleven pages, retired with reasons in the outline fixture; `/for/analysts` retargeted to "AI data analyst", owner ruling); engine `searchAliases` ("PostgreSQL", "MSSQL"); the homepage `Organization` block and the docs' `TechArticle` + `BreadcrumbList`. §4.15a: `/llms.txt`, `/llms-full.txt`, `/docs/{slug}.md` (+ `Accept: text/markdown`), and the sitemap row corrected — `lastmod` has been absent since 145. §4.16: the `.md` twin. |
| 2026-09-18 | v1.56 | the header search becomes real (161) | **§3.4 amended — the placeholder is gone (#155)**. The shell's search is a real `<input role="combobox">` (and the same control in the drawer's head), opening a palette on focus/click or ⌘K / Ctrl+K and fetching `GET /partials/search?q=…` over htmx (`input changed delay:200ms`; a BLANK query never reaches the server — `shell.js` cancels it). `SearchController` / `SearchBrowseModel` answer Pipelines / Templates / Executions, ≤ 8 each, workspace- and role-scoped through the screens' own queries (`PipelineService.list`, `TemplateRepository.list`, the execution-history fork), every hit a link (editor / template editor / execution detail; executions show status + started-at) and a "more…" row per group to the FILTERED list page. Combobox keyboard: ↑/↓ move the active row (`aria-activedescendant`; focus stays in the input), Enter opens it, Esc / outside click / any boosted navigation close. §4.3e gains the search's read-only inventory row. Guards: `ShellRenderTest` (real input, no `aria-hidden` search), `SearchControllerTest` / `SearchPartialRenderTest`, the palette half of `shell.test.mjs`, `RoleVisibilityRenderTest`'s viewer arm, and `HeaderSearchBrowserTest` (⌘K → type → arrow → Enter → editor, screenshot). |
