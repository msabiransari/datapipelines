# Toast Application Rollout Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Every mutation in the UI reports its outcome through the §5.1 Notifications toast — successes AND refusals — while surfaces that must PERSIST (one-time secrets, field-level validation) stay exactly where they are.

**Architecture:** The toast system is merged and generic (028, `09c726e`): `partials/toast :: toast(variant, title, message)` renders `.ds-toast-{variant}`; the layout's `#toast` stack (`layouts/default.html:70`) receives appends; `static/js/toast.js` arms auto-dismiss and close via a `MutationObserver` on the stack, and also arms toasts already present at `DOMContentLoaded` (`toast.js:54-56`). This plan is an ADOPTION pass — but adoption needs one piece of infrastructure the previous revision assumed away: **htmx does not swap 4xx/5xx responses at all**, so no error path can deliver a toast until that is bridged. Task 1 builds the bridge; Tasks 2-7 convert.

**Tech Stack:** Thymeleaf, htmx 2.0.10 (webjar), Spring controllers. Tests: JUnit 5 + Kotest + MockK (controller shape assertions), Thymeleaf render tests in the `DatasourcesTemplateRenderTest` shape, **and `node --test` via the `editorJsTest` Gradle task** — Task 1 changes `static/js/toast.js`, so `modules/web/src/test/js/toast.test.mjs` grows with it. NO new dependencies.

**Spec:** `docs/ui-screens.md` §5.1 Standard States (Notifications, Error rendering) + the per-screen §4.x rows; changelog row in the last commit.

**The hard rule (carved into §5.1 by Task 1):** a toast auto-dismisses after 6s (`toast.js:18`), so it NEVER carries anything the user must keep. One-time secrets (`partials/api-key-created.html`, the admin one-time-password notice) stay persistent inline; a toast may POINT at them. Field-level validation stays inline at the form. `401` stays `HX-Redirect: /login`. Errors inside the register modal stay in the modal (the 022/F9 choreography).

---

## Verified state — read before Task 1, do not re-derive

Every claim was checked on 2026-08-31 against the working tree and, for htmx behaviour, against the vendored source in `org.webjars.npm:htmx.org:2.0.10` (`dist/htmx.js`; extract the jar from the Gradle cache to re-check).

### htmx mechanics that decide this plan's design

1. **A non-`outerHTML` OOB swap uses the OOB element's CHILDREN, not the element** (`htmx.js:1466-1512`, `oobSwap`): `isInlineSwap(swapStyle, target)` returns true only for `outerHTML` when no extension is registered (`htmx.js:isInlineSwap`), and for everything else `fragment = asParentNode(oobElementClone)` — the comment in the source says it outright: *"if this is not an inline swap, we use the content of the node, not the node itself."* So a toast delivered with `beforeend` must be **wrapped**:

   ```html
   <div hx-swap-oob="beforeend:#toast"><div class="ds-toast ds-toast-success" role="status">…</div></div>
   ```

   Putting `hx-swap-oob="beforeend:#toast"` directly on the `.ds-toast` div appends its *children* — the close button, title and body — bare into the stack. No `.ds-toast` node is created, so `toast.js` arms nothing, nothing auto-dismisses, and the layout looks broken with no error anywhere. **This is the single easiest way to get this plan wrong.**

2. **htmx does not swap error responses.** Default `htmx.config.responseHandling` (`htmx.js:264-268`) is `[{code:'204',swap:false},{code:'[23]..',swap:true},{code:'[45]..',swap:false,error:true}]`. `HX-Retarget` and `HX-Reswap` ARE read (`htmx.js:4862-4867`) but only set `responseInfo.target` / `swapOverride`; the swap itself is gated on `if (beforeSwapDetails.shouldSwap)` at `htmx.js:4896`. **The previous revision's shape (b) — "`HX-Retarget: #toast` + `HX-Reswap: beforeend`, the body IS the toast" — silently does nothing on any 4xx.** The one documented escape hatch is the `htmx:beforeSwap` event, which can flip `shouldSwap` (`htmx.js:4881-4887`). Task 1 uses it.

3. **`hx-swap="none"` + OOB works, and is the right "toast only" shape.** In `swap()`, `findAndSwapOobElements(fragment, …)` runs *before* the main swap (`htmx.js:1936`), and `swapWithStyle` has `case 'none': return`. So a 2xx response whose body is just an OOB toast wrapper, requested by a control with `hx-swap="none"`, delivers the toast and touches nothing else — no response headers needed.

4. `allowNestedOobSwaps` defaults to `true` (`htmx.js`, config), so the OOB wrapper need not be a top-level node of the response.

5. **The repo already has a correct OOB precedent — do not "fix" it.** `AdminUsersPartialController.oneTimeNotice` emits `<div id="admin-notice" hx-swap-oob="true" …>`, which is the `outerHTML` (inline) form: the element itself replaces `#admin-notice`. That is right as written. Only the `beforeend` stack form needs the wrapper.

### Mutation-endpoint inventory (`grep -rn '@Post|@Patch|@Delete|@PutMapping' modules/web/src/main/kotlin/co/datapipelines/web/ui/`)

| Endpoint | Today | Task |
|---|---|---|
| `DatasourcePartialController:75` `POST /partials/datasources/{name}/test` | **already a toast** (028 reference) | none — regression check only |
| `DatasourcePartialController:113` `POST /partials/datasources` | 2xx = `HX-Redirect: /datasources` with an EMPTY body; refusal = 400 + inline modal error via a bespoke `htmx:responseError` listener | Task 2 |
| `ApiKeysPartialController:26` `POST /partials/api-keys` | 2xx = `partials/api-key-created` into `#keyCreated` | Task 3 |
| `ApiKeysPartialController:65` `DELETE /partials/api-keys/{keyId}` | 2xx = Kotlin-built `<tr>` rows into `#keys-table-body`, plus `HX-Trigger: keyRevoked` | Task 3 |
| `UserSettingsController:73` `PATCH /partials/profile/theme` | 2xx = `partials/theme-swap` into `#theme-status`; 400 = error span (never displayed) | Task 4 |
| `UserSettingsController:106` `POST /partials/account/password` | 2xx/4xx = spans into `#password-change-result` (4xx never displayed) | Task 4 |
| `AdminUsersPartialController:49` `POST /partials/admin/users` | 2xx = row + OOB one-time notice; 400/409 = error span (never displayed) | Task 5 |
| `AdminUsersPartialController:70` `PATCH /partials/admin/users/{userId}/{action}` | 2xx = row outerHTML into `#user-row-{id}`; `reset-password` also emits the OOB notice | Task 5 |
| `ExecutionDetailPartialController:72` `DELETE /{id}/cancel` | 2xx = `partials/execution-cancelled`; refusals are `ResponseStatusException` 403/404/409 | Task 6 |
| `WorkspacesUiController:79,94,105,113,121,134` (6 endpoints) | **full-page form POSTs** → `redirect:/workspaces?ok=…` / `?error=…`, rendered as a banner (`workspaces/index.html:9-19`) — not htmx at all | Task 7 |
| `LocalLoginController:41` `POST /login` | full-page form, `?error=` on the login page | **out of scope** (pre-auth; no layout, no `#toast` stack) |
| `TemplateEditorController:44` `POST /partials/templates/{id}/versions/{version}/render` | a preview render, not a state mutation | **out of scope** |

### Live defects this plan owns

- **E1 — admin user-create refusals are invisible today.** `AdminUsersPartialController:57` returns 400 `errorSpan("A valid email address is required")` and `:63` returns 409 `errorSpan("An account with that email already exists")`. Both are 4xx, so htmx does not swap them, and `admin/users.html` has no `htmx:responseError` handler (its only script is the `DOMContentLoaded` table load at `:52-56`). The admin sees nothing at all.
- **E2 — creating an API key does not refresh the key table.** `ApiKeysPartialController.create` puts `keys` in the model, but `partials/api-key-created.html` never renders it — the response targets `#keyCreated` only. The new key appears only on reload.
- **E3 — `HX-Trigger: keyRevoked` (`ApiKeysPartialController:71`) has no listener anywhere** in templates or JS. Dead header.
- **E4 — same class as E1 on settings.** The theme 400 and every password-change 400 return spans that htmx will not swap into `#theme-status` / `#password-change-result`. Confirm per endpoint in Task 4 Step 1 before changing anything.
- **E5 — the `response-targets` extension §5.1 names is not loaded.** There is no `hx-ext` attribute anywhere in `layouts/default.html` (scripts at `:76-77` are htmx and toast.js only). `datasources/list.html:13-21` says so in a comment and works around it with a screen-local `htmx:responseError` listener. This is the drift the 028 handback raised; Task 1 resolves it one way or the other.
- **E6 — the previous revision contradicted itself on workspaces.** Task 3 asked to convert the workspaces actions, while "Explicitly NOT in this plan" excluded "server-side flash messages across full-page redirects" — which is exactly what those six endpoints are. Task 7 resolves it: keep the redirect, render the existing `?ok=`/`?error=` params as a toast.

### Landing zone

`git status` shows `modules/web/src/main/resources/templates/settings/index.html` **modified and uncommitted** — the operator added Password and API Keys cards (+12 lines at `:58`). Task 4 edits that file. Branch only after it lands, and do not carry that change into this branch. Also uncommitted: the `partials/{pipelines,templates}.html` quoting hotfix and `ListPartialsRenderTest.kt` (the table plan's landing zone).

---

## Global Constraints

- Branch: `feat/toast-rollout` via worktree (`superpowers:using-git-worktrees`); merge after Task 8. Independent of the graph plan; land **before** the execute-page plan, which consumes the rule for its completion toast.
- Every dynamic htmx attribute goes through `th:attr` with QUOTED literals; a constant attribute (`hx-swap="none"`) needs no processing and stays plain. `TemplateHtmxRenderAuditTest` green throughout — note its sweep flags `hx-post|put|patch|delete="${…}"`, so any Kotlin-built button string must not introduce one.
- Every converted endpoint gets **both**: a controller test asserting the response shape (status, `variant`/`title`/`message` in the model, or the OOB attribute in a built string), and a render or JS test for the delivery mechanism.
- Toast copy: title is the outcome ("Datasource registered"), body names the subject and any next step. Never put a secret, a token, or a value the user must copy in either.
- Design tokens only; `./scripts/docs-audit.sh` exits 0 after docs commits; no AI attribution trailers.
- Full gate before merge: `./gradlew build ktlintCheck detekt editorJsTest --rerun-tasks`. `editorJsTest` is listed explicitly because this plan changes JS and the task is skipped-not-failed when node is absent — confirm the log says it RAN, not `SKIPPED`.

---

### Task 1: The delivery mechanics — OOB wrapper, the error bridge, and the rule

**Files:**
- Create: `modules/web/src/main/resources/templates/partials/toast-oob.html`
- Modify: `modules/web/src/main/resources/static/js/toast.js`
- Modify: `docs/ui-screens.md` §5.1
- Test: `modules/web/src/test/js/toast.test.mjs` (extend), `modules/web/src/test/kotlin/co/datapipelines/web/ui/ToastOobFragmentRenderTest.kt` (new)

**Interfaces produced — every later task consumes these:**
- `partials/toast-oob :: oob(variant, title, message)` — the `partials/toast` toast wrapped for the `#toast` stack. Splice it into any 2xx response with `<div th:replace="~{partials/toast-oob :: oob('success', 'Title', 'Body')}"></div>`.
- **Shape A (content + toast):** the response is the normal swap content, with the `toast-oob` fragment spliced in. The triggering control keeps its `hx-target`/`hx-swap`.
- **Shape B (toast only):** the control sets `hx-swap="none"` and the response body is the `toast-oob` fragment alone. No response headers.
- **Shape C (refusal):** the response keeps its real 4xx status, its body is the `toast-oob` fragment, and it sets `HX-Retarget: #toast` + `HX-Reswap: beforeend`. The `toast.js` bridge from Step 3 is what makes htmx swap it.

- [ ] **Step 1: Write the failing fragment render test.**

```kotlin
class ToastOobFragmentRenderTest {
    private val engine = SpringTemplateEngine().apply {
        setTemplateResolver(ClassLoaderTemplateResolver().apply {
            prefix = "templates/"; suffix = ".html"; characterEncoding = "UTF-8"
        })
    }

    @Test
    fun `the oob wrapper carries the swap attribute and WRAPS the toast`() {
        val html = engine.process("partials/toast-oob", webContext().apply {
            setVariable("variant", "success")
            setVariable("title", "Datasource registered")
            setVariable("message", "pg-prod is ready to use.")
        })

        // htmx 2.0.10 oobSwap: a non-outerHTML style swaps the oob element's CHILDREN,
        // so the .ds-toast MUST be a child of the element carrying hx-swap-oob.
        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        Regex("""hx-swap-oob="beforeend:#toast"[^>]*>\s*<div class="ds-toast""")
            .containsMatchIn(html) shouldBe true
        html shouldContain "ds-toast-success"
        html shouldContain "Datasource registered"
    }

    @Test
    fun `the attribute never lands on the toast itself`() {
        val html = engine.process("partials/toast-oob", webContext().apply {
            setVariable("variant", "danger"); setVariable("title", "t"); setVariable("message", "m")
        })
        Regex("""<div class="ds-toast[^"]*"[^>]*hx-swap-oob""").containsMatchIn(html) shouldBe false
    }
    // webContext() as in ListPartialsRenderTest.kt:110-115
}
```

- [ ] **Step 2: Run and verify RED** (template not found).

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.ToastOobFragmentRenderTest' -x verifyTestsExecuted`

- [ ] **Step 3: Implement `partials/toast-oob.html`.**

```html
<!-- The #toast-stack delivery wrapper for partials/toast (ui-screens.md §5.1).
     htmx 2.0.10 oobSwap: for any swap style other than outerHTML the oob element's
     CHILDREN are swapped, not the element ("we use the content of the node, not the
     node itself" — htmx.js). So the hx-swap-oob attribute lives on this wrapper and
     the .ds-toast is its child. Put the attribute on the toast itself and htmx
     appends the close button, title and body BARE into the stack: no .ds-toast node
     is created, toast.js arms nothing, and nothing auto-dismisses. -->
<div xmlns:th="http://www.thymeleaf.org"
     th:fragment="oob(variant, title, message)"
     hx-swap-oob="beforeend:#toast">
  <th:block th:replace="~{partials/toast :: toast(${variant}, ${title}, ${message})}"></th:block>
</div>
```

- [ ] **Step 4: Run and verify GREEN.** Same command as Step 2.

- [ ] **Step 5: Write the failing JS test for the error bridge.** In `modules/web/src/test/js/toast.test.mjs`, following its hand-rolled-DOM style:

```js
test("bridgeErrors swaps an error response that retargets the toast stack", () => {
  const toast = loadToast();
  const listeners = {};
  const root = { addEventListener: (name, fn) => { listeners[name] = fn; } };
  toast.bridgeErrors(root);

  const detail = {
    shouldSwap: false,
    xhr: { status: 409, getResponseHeader: (h) => (h === "HX-Retarget" ? "#toast" : null) },
  };
  listeners["htmx:beforeSwap"]({ detail });
  assert.equal(detail.shouldSwap, true);
});

test("bridgeErrors leaves an error that does NOT retarget the stack alone", () => {
  const toast = loadToast();
  const listeners = {};
  toast.bridgeErrors({ addEventListener: (n, f) => { listeners[n] = f; } });

  const detail = { shouldSwap: false, xhr: { status: 500, getResponseHeader: () => null } };
  listeners["htmx:beforeSwap"]({ detail });
  assert.equal(detail.shouldSwap, false);
});

test("bridgeErrors never downgrades a successful swap", () => {
  const toast = loadToast();
  const listeners = {};
  toast.bridgeErrors({ addEventListener: (n, f) => { listeners[n] = f; } });

  const detail = { shouldSwap: true, xhr: { status: 200, getResponseHeader: () => "#toast" } };
  listeners["htmx:beforeSwap"]({ detail });
  assert.equal(detail.shouldSwap, true);
});
```

- [ ] **Step 6: Run and verify RED.**

Run: `./gradlew :modules:web:editorJsTest`
Expected: FAIL — `toast.bridgeErrors is not a function`. If the log says `editorJsTest SKIPPED — node not on PATH`, install node ≥ 18; a skipped guard is not evidence.

- [ ] **Step 7: Implement the bridge in `static/js/toast.js`**, inside the IIFE, exported alongside `attach`/`arm`/`dismiss`:

```js
  /*
   * htmx does not swap 4xx/5xx AT ALL: htmx.config.responseHandling maps [45].. to
   * {swap:false}, and HX-Retarget/HX-Reswap only set the target — the swap itself is
   * gated on shouldSwap. So a refusal that wants to be a toast has to say so and be
   * let through here. This is the same job htmx's response-targets extension does;
   * doing it in twelve lines keeps the dependency count at zero (ui-screens.md §5.1).
   *
   * Deliberately narrow: it opts in ONLY when the server asked for #toast by header,
   * so an ordinary error still behaves exactly as before. isError is left true, so
   * htmx:responseError listeners and error telemetry still fire.
   */
  function bridgeErrors(root) {
    if (!root || !root.addEventListener) return;
    root.addEventListener("htmx:beforeSwap", function (event) {
      var detail = event && event.detail;
      if (!detail || detail.shouldSwap || !detail.xhr) return;
      if (detail.xhr.getResponseHeader("HX-Retarget") !== "#toast") return;
      detail.shouldSwap = true;
    });
  }
```

Add `bridgeErrors: bridgeErrors` to the exported `api`, and call it from the browser bootstrap beside `attach`:

```js
    document.addEventListener("DOMContentLoaded", function () {
      attach(document.getElementById("toast"));
      bridgeErrors(document.body);
    });
```

- [ ] **Step 8: Run and verify GREEN.**

Run: `./gradlew :modules:web:editorJsTest`

- [ ] **Step 9: Record the decision and the rule in `docs/ui-screens.md` §5.1.** Under **Notifications**, add the three delivery shapes A/B/C with the wrapper requirement and the reason (children-not-element), and the hard rule verbatim from this plan's header. Under **Error rendering**, replace the `response-targets` prescription with what the build actually does: no extension is loaded; refusals that should surface as toasts set `HX-Retarget: #toast` + `HX-Reswap: beforeend` and are admitted by `toast.js`'s `bridgeErrors`; field-level validation and modal-scoped errors are unchanged. Keep the `hx-target-error` example only if you also vendor the extension — see **Open decision** below.

- [ ] **Step 10: Run `./scripts/docs-audit.sh`** (exit 0) and **commit.**

```bash
git add modules/web/src/main/resources/templates/partials/toast-oob.html \
        modules/web/src/main/resources/static/js/toast.js \
        modules/web/src/test/js/toast.test.mjs \
        modules/web/src/test/kotlin/co/datapipelines/web/ui/ToastOobFragmentRenderTest.kt \
        docs/ui-screens.md
git commit -m "feat(web): toast delivery shapes and the 4xx swap bridge (030)"
```

---

### Task 2: Reference conversion — datasource register

**Files:**
- Modify: `modules/web/src/main/kotlin/co/datapipelines/web/ui/DatasourcePartialController.kt:113-173` (`register`, `refused`)
- Modify: `modules/web/src/main/resources/templates/datasources/list.html` (the modal form target and the screen-local error script)
- Test: `DatasourceUiControllerTest`, `DatasourcesTemplateRenderTest`

Today the success path is `HX-Redirect: /datasources` with an **empty body** — a full page navigation that discards any toast, and which is also why the modal's `MutationObserver` (`datasources/list.html:31-39`) never fires on success: it requires `#register-result` to gain children, and an empty body adds none. The modal "closes" only because the page reloads. Converting the success path therefore MUST keep a success node landing in `#register-result`, or the modal will stay open over the new list.

- [ ] **Step 1: Write the failing controller tests.**

```kotlin
    @Test
    fun `register success returns the refreshed list plus an OOB toast and no redirect`() {
        authenticate()
        every { datasources.exists("pg-new") } returns false
        every { datasources.save(any(), any()) } returns Unit
        every { registry.listVisible(null, workspaceId) } returns listOf(datasource(name = "pg-new"))

        val response = partialController().register(/* … the @RequestParam list … */)

        response.statusCode shouldBe HttpStatus.OK
        response.headers["HX-Redirect"] shouldBe null
        response.body!! shouldContain "hx-swap-oob=\"beforeend:#toast\""
        response.body!! shouldContain "Datasource registered"
        response.body!! shouldContain "pg-new"
    }

    @Test
    fun `a refusal keeps its 400 and stays inline in the modal`() {
        authenticate()
        every { datasources.exists("pg-dupe") } returns true

        val response = partialController().register(/* … */)

        response.statusCode shouldBe HttpStatus.BAD_REQUEST
        response.headers["HX-Retarget"] shouldBe null          // the modal owns this error
        response.body!! shouldContain "already exists"
        response.body!! shouldNotContain "hx-swap-oob"
    }
```

- [ ] **Step 2: Run and verify RED.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.DatasourceUiControllerTest' -x verifyTestsExecuted`

- [ ] **Step 3: Implement.** `register` returns 200 whose body is the success node for `#register-result` **plus** two OOB pieces: the refreshed list fragment (`hx-swap-oob="true"` on the `#datasource-list-wrapper` root — the inline/outerHTML form, per verified fact 5) and the `toast-oob` success toast. Render it through a small Thymeleaf template rather than string concatenation, so the list fragment is reused and not copied:

```html
<!-- partials/datasource-registered.html -->
<div xmlns:th="http://www.thymeleaf.org">
  <!-- The modal's success node: its arrival is what closes the modal (022/F9). -->
  <p style="color: var(--accent-success); font-size: var(--text-sm);"
     th:text="'Datasource ' + ${registeredName} + ' registered.'">registered</p>
  <!-- outerHTML OOB: the fragment root already carries id="datasource-list-wrapper". -->
  <th:block th:replace="~{partials/datasources :: fragment}"></th:block>
  <div th:replace="~{partials/toast-oob :: oob('success', 'Datasource registered', ${registeredName} + ' is ready to use.')}"></div>
</div>
```

Add `hx-swap-oob="true"` to the list fragment root only for this path — do **not** hard-code it into `partials/datasources.html`, which is also served as a primary swap. Cleanest: keep the primary `hx-target="#register-result"` on the form, and have the controller build the model by calling the same assembly `list(...)` uses (extract it into a private `listModel(model, q, dialect, offset)` — extract, never copy).

- [ ] **Step 4: Delete the now-redundant workaround.** With `bridgeErrors` in place the screen-local `htmx:responseError` listener (`datasources/list.html:17-21`) is only still needed because the modal refusal is **not** retargeted to the stack — it must stay inline. Keep the listener, and update its comment to explain why this screen still owns its error path rather than using the bridge (the modal must not close over an error). Removing it would silently break F9.

- [ ] **Step 5: Run and verify GREEN**, plus `DatasourcesTemplateRenderTest` and `TemplateHtmxRenderAuditTest`.

- [ ] **Step 6: Browser check** on the rebuilt demo stack (`./app.sh --start --demo`): register a datasource → the modal closes, the row appears without a page reload, one success toast appears and auto-dismisses; register a duplicate → the modal stays open with the inline refusal and NO toast; the per-row Test button still toasts (the 028 regression).

- [ ] **Step 7: Commit** `feat(web): datasource register reports via toast, no page reload (030)`.

---

### Task 3: API keys — create and revoke (fixes E2 and E3)

**Files:**
- Modify: `modules/web/src/main/kotlin/co/datapipelines/web/ui/ApiKeysPartialController.kt:26-71`, `modules/web/src/main/resources/templates/partials/api-key-created.html`, `modules/web/src/main/resources/templates/settings/api-keys.html`
- Test: `ApiKeysControllerTest`

- [ ] **Step 1: Write the failing tests.** Create: the secret panel STAYS the primary swap into `#keyCreated`, the response gains an OOB **info** toast pointing at it, **and** an OOB refresh of `#keys-table-body` (E2 — `keys` is already in the model and currently unused). Revoke: the rebuilt rows stay the primary swap, plus an OOB success toast.

```kotlin
    @Test
    fun `create returns the once-shown panel, refreshes the table, and points a toast at it`() {
        // … existing create fixture …
        val view = controller.create(name = "ci", scopes = "read", expiryDays = null, model = model)

        view shouldBe "partials/api-key-created"
        val html = render(view, model)
        html shouldContain "Your new API key (shown once)"          // the secret still persists
        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        html shouldContain "Copy it now"                             // the toast POINTS, never carries
        html shouldNotContain issuedPlaintext                        // …and never the secret itself
        html shouldContain "id=\"keys-table-body\""                  // E2: the table is refreshed
        html shouldContain "hx-swap-oob=\"true\""
    }

    @Test
    fun `revoke returns the rebuilt rows plus a success toast and drops the dead trigger`() {
        val response = controller.revoke(keyId)

        response.body!! shouldContain "hx-swap-oob=\"beforeend:#toast\""
        response.body!! shouldContain "revoked"
        response.headers["HX-Trigger"] shouldBe null   // E3: nothing has ever listened for keyRevoked
    }
```

- [ ] **Step 2: Run and verify RED.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.ApiKeysControllerTest' -x verifyTestsExecuted`

- [ ] **Step 3: Implement create.** Append to `partials/api-key-created.html` (its root is `<div th:fragment="content">`; add `xmlns:th` while you are there):

```html
  <!-- E2: the create response is the ONLY chance to refresh the table without a reload;
       `keys` was already in the model and rendered nowhere. -->
  <tbody id="keys-table-body" hx-swap-oob="true">
    <tr th:each="key : ${keys}" th:replace="~{settings/api-keys :: keyRow(${key})}"></tr>
  </tbody>
  <div th:replace="~{partials/toast-oob :: oob('info', 'API key created', 'The key is shown once on this screen — copy it now.')}"></div>
```

This needs the `<tr>` markup in `settings/api-keys.html` extracted into a `th:fragment="keyRow(key)"` so both the page and this OOB refresh render one definition. Extract it in this step; do not duplicate the row markup.

- [ ] **Step 4: Implement revoke.** Append the toast wrapper to the built string, and delete the `HX-Trigger` header (E3):

```kotlin
        return ResponseEntity.status(HttpStatus.OK).body(
            html +
                """<div hx-swap-oob="beforeend:#toast"><div class="ds-toast ds-toast-success" role="status">""" +
                """<button type="button" class="ds-toast-close" aria-label="Dismiss">&times;</button>""" +
                """<div class="ds-toast-title">API key revoked</div>""" +
                """<div class="ds-toast-body">The key can no longer authenticate.</div></div></div>""",
        )
```

Prefer rendering `partials/toast-oob` through the template engine if this controller already has one injected; hand-built HTML here only because `revoke` already builds its rows as strings. If you build it by hand, escape any interpolated value with the existing `esc(...)` helper — the key name is user-supplied.

- [ ] **Step 5: Run and verify GREEN.** Same command as Step 2.

- [ ] **Step 6: Commit** `feat(web): api key create/revoke report via toast; create refreshes the table (030)`.

---

### Task 4: Settings — theme and password (fixes E4)

**Files:**
- Modify: `modules/web/src/main/kotlin/co/datapipelines/web/ui/UserSettingsController.kt:73-160`, `modules/web/src/main/resources/templates/settings/index.html:33-40`, `modules/web/src/main/resources/templates/settings/password.html:15-29`, `modules/web/src/main/resources/templates/partials/theme-swap.html`
- Test: `UserSettingsControllerTest`, a render assertion on `partials/theme-swap`

- [ ] **Step 1: Confirm E4 per endpoint before changing anything.** Assert in a test that today's 400 bodies are spans with no `HX-Retarget` header — that is the evidence the errors are undeliverable, and it is what the fix flips. Record the result in the handback either way.

- [ ] **Step 2: Theme.** The `partials/theme-swap` response currently carries the OOB stylesheet `<link>` **and** a confirmation span for `#theme-status`. Keep the stylesheet OOB byte-for-byte (025 C1 — a hand-built `href` breaks under a non-root context path, and an OOB span once swapped over the real `<link>`), replace the span with the `toast-oob` fragment, delete the `#theme-status` div (`settings/index.html:40`), and change the select to `hx-swap="none"` (Shape B) — it no longer has a content target:

```html
        <select id="themeSelect" class="ds-select" style="flex:1"
                hx-patch="/partials/profile/theme" hx-include="this" name="theme"
                hx-swap="none">
```

Assert in the render test that the OOB stylesheet link is still present and still `@{...}`-resolved.

- [ ] **Step 3: Theme refusal.** The unknown-theme 400 becomes Shape C: keep the 400, body = `toast-oob` with variant `danger`, headers `HX-Retarget: #toast` and `HX-Reswap: beforeend`.

- [ ] **Step 4: Password.** Success → Shape B toast ("Password changed") plus an OOB reset of the form fields if the form is to stay usable; `#password-change-result` is deleted. Every failure (`WrongCurrentPassword`, `PolicyViolation`, `NoLocalAccount`, `AccountLocked`) is **field-level or credential-level validation**, which §5.1 keeps inline — so those KEEP `#password-change-result` and their current 400 spans, and the form keeps its `hx-target`. Do not toast them. This is the one screen where Shape B and inline errors coexist; say so in the template comment.

- [ ] **Step 5: The forced-change flow is untouched.** Confirm by test that the `must_change` interceptor path still renders its own screen and is not routed through a toast.

- [ ] **Step 6: Run.**

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.UserSettingsControllerTest' -x verifyTestsExecuted`

- [ ] **Step 7: Commit** `feat(web): settings theme and password report via toast (030)`.

---

### Task 5: Admin users (fixes E1)

**Files:**
- Modify: `modules/web/src/main/kotlin/co/datapipelines/web/ui/AdminUsersPartialController.kt:49-140`
- Test: `AdminUsersControllerTest`

- [ ] **Step 1: Write the failing tests**, one per action: `create` (success + both refusals), `activate`/`deactivate`/`promote`/`demote`, `disable-local`, `unlock`, `reset-password`.

```kotlin
    @Test
    fun `create refusals now reach the user as a toast`() {
        val response = controller.createLocalUser(email = "not-an-email", displayName = "")

        response.statusCode shouldBe HttpStatus.BAD_REQUEST          // the status is unchanged
        response.headers["HX-Retarget"] shouldBe "#toast"            // …and now deliverable
        response.headers["HX-Reswap"] shouldBe "beforeend"
        response.body!! shouldContain "ds-toast-danger"
        response.body!! shouldContain "valid email address"
    }

    @Test
    fun `create success keeps the one-time password inline and only points at it`() {
        val response = controller.createLocalUser(email = "a@b.c", displayName = "A")

        response.body!! shouldContain "id=\"admin-notice\" hx-swap-oob=\"true\""   // unchanged (fact 5)
        response.body!! shouldContain oneTimePassword                              // still inline
        response.body!! shouldContain "hx-swap-oob=\"beforeend:#toast\""
        val toastBody = response.body!!.substringAfter("beforeend:#toast")
        toastBody shouldNotContain oneTimePassword                                 // never in the toast
    }
```

- [ ] **Step 2: Run and verify RED**, confirming E1 concretely (today there is no `HX-Retarget` header at all).

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.AdminUsersControllerTest' -x verifyTestsExecuted`

- [ ] **Step 3: Implement.** Refusals (400 invalid email, 409 email taken) become Shape C — same status, `errorSpan` replaced by a danger toast body, `HX-Retarget: #toast` + `HX-Reswap: beforeend`. Successes stay Shape A: `create` keeps `buildUserRow(...) + oneTimeNotice(...)` and gains the toast wrapper; the row-swap actions keep their `#user-row-{id}` outerHTML swap and gain a toast naming the action and the user's email; `reset-password` keeps its notice and gets a pointer toast. **`oneTimeNotice`'s `hx-swap-oob="true"` form is correct and stays as it is** (verified fact 5).

- [ ] **Step 4: Run and verify GREEN.** Same command as Step 2.

- [ ] **Step 5: Commit** `fix(web): admin user refusals were never displayed; all actions toast (030)`.

---

### Task 6: Execution cancel

**Files:**
- Modify: `modules/web/src/main/kotlin/co/datapipelines/web/ui/ExecutionDetailPartialController.kt:72-92`, `modules/web/src/main/resources/templates/partials/execution-cancelled.html`
- Test: `ExecutionControllerTest` + a render assertion

- [ ] **Step 1: Failing render test.** The cancelled partial keeps the 027b-E `outerHTML` button swap (it is the persistent state) and gains the OOB toast:

```kotlin
    @Test
    fun `cancel renders the cancelled state and an OOB toast`() {
        val html = engine.process("partials/execution-cancelled", webContext().apply {
            setVariable("cancelled", true)
            setVariable("executionId", executionId)
        })

        html shouldContain "hx-swap-oob=\"beforeend:#toast\""
        html shouldContain "Execution cancelled"
        Regex("""hx-swap-oob="beforeend:#toast"[^>]*>\s*<div class="ds-toast""")
            .containsMatchIn(html) shouldBe true
    }
```

- [ ] **Step 2: Run and verify RED**, then splice `<div th:replace="~{partials/toast-oob :: oob('info', 'Execution cancelled', 'The run is stopping; the detail page will show its final state.')}"></div>` into `partials/execution-cancelled.html` and verify GREEN.

Run: `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.ExecutionControllerTest' --tests 'co.datapipelines.web.ui.TemplateHtmxRenderAuditTest' -x verifyTestsExecuted`

- [ ] **Step 3: Cancel refusals.** 403/404/409 are thrown as `ResponseStatusException`, so they are handled by `UiExceptionHandler`, which renders full error PAGES (`error/403`, `error/500`) — a whole document returned to an htmx partial request, and not swapped anyway. **Do not convert these here.** Record it as a finding: the UI exception handler has no htmx-aware branch, which is a broader gap than this plan.

- [ ] **Step 4: Commit** `feat(web): execution cancel reports via toast (030)`.

---

### Task 7: Workspaces — the redirect flash becomes a toast (resolves E6)

The six workspace mutations are full-page form POSTs returning `redirect:/workspaces?ok=…|error=…`, rendered as a banner at `workspaces/index.html:9-19`. Converting them to htmx partials would mean six new partial endpoints and a fragment extraction — out of proportion here, and the table-component plan already owns that screen's markup. **Keep the redirect; render the existing query parameters as a toast at page load.** `toast.js:54-56` arms toasts already present in the stack when it attaches, so no JS change is needed.

**Files:**
- Modify: `modules/web/src/main/resources/templates/workspaces/index.html:9-22`, `modules/web/src/main/resources/templates/layouts/default.html:70` (the stack renders a server-side toast when the model carries one)
- Test: `WorkspacesUiControllerTest` + a render assertion

- [ ] **Step 1: Failing render test** — rendering `workspaces/index` with `param.ok = "created"` produces a `.ds-toast-success` inside `#toast` and **no** banner element; with `param.error = "in_use"` produces `.ds-toast-danger` carrying the existing copy verbatim.

- [ ] **Step 2: Implement.** Move the nine `?error=` messages and the `?ok=` messages out of the banner and into a `th:block` inside the layout's `#toast` stack, keyed the same way. Copy the message text **verbatim** — it is reviewed wording (022/F8), not something to paraphrase while moving.

- [ ] **Step 3:** Confirm the six `action(...)` redirect targets are unchanged; this task does not touch `WorkspacesUiController`.

- [ ] **Step 4: Run** `./gradlew :modules:web:test --tests 'co.datapipelines.web.ui.WorkspacesUiControllerTest' -x verifyTestsExecuted` and **commit** `feat(web): workspace action results report via toast (030)`.

---

### Task 8: Spec sweep, gate, evidence, merge

- [ ] **Step 1: `docs/ui-screens.md` per-screen rows** — §4.5 datasources (register now swaps in place), §4.10 API keys, §4.11 user settings, §4.12 admin users, §4.13 workspaces, §4.9 execution detail: each names its delivery shape (A/B/C) and what deliberately stays inline. §5.1 already got the shapes and the hard rule in Task 1; add only what per-screen work revealed.

- [ ] **Step 2: Changelog.** Add the `v1.12` row (the file is at v1.11, `docs/ui-screens.md:468`) and bump **Status:** at line 3. **If the table-component plan lands in the same window, the two share one v1.12 row — renumber, never duplicate a version.**

- [ ] **Step 3: Full gate.**

Run: `./gradlew build ktlintCheck detekt editorJsTest --rerun-tasks`
Expected: BUILD SUCCESSFUL, and the log shows `editorJsTest: … on v…` — not `SKIPPED`. Read the log's last line, not the wrapper's exit code.

- [ ] **Step 4: Browser evidence** on the rebuilt demo stack: every converted action exercised — register (success + duplicate), key create + revoke, theme change + an unknown theme, password change + a wrong current password, each admin action + a duplicate email, execution cancel, a workspace create + an `in_use` delete. For each: the toast appears top-right, stacks with others, auto-dismisses at ~6s, and closes on click. Explicitly confirm the negatives: no secret appears in any toast, the one-time password and API key panels still persist, and the register modal still shows its refusal inline.

- [ ] **Step 5: Falsification, one per guard.** Revert each of these in a scratch copy and confirm RED: the OOB wrapper (move `hx-swap-oob` onto the `.ds-toast` — the render guard must fail), `bridgeErrors` (delete it — the JS test must fail), and the admin refusal headers. A guard that cannot go red is not a guard.

- [ ] **Step 6: Handback** at `datapipelines-orchestration/handbacks/030-toast-rollout.md`: evidence, the falsification runs, the Task 4 Step 1 verdict on E4, the Task 6 Step 3 finding on `UiExceptionHandler`, and the open decision below.

- [ ] **Step 7: Merge** from the MAIN checkout after operator review. Check `git symbolic-ref HEAD` — the ref, not the SHA — and `git status` for foreign modified files before committing there. After pushing, verify containment with `git merge-base --is-ancestor <your-sha> origin/main`, then a full build on main.

---

## Open decision for the operator — raise before Task 1 Step 9

**§5.1 prescribes the htmx `response-targets` extension; the build has never loaded it** (E5). Two ways to close the drift:

- **(A) Ship `bridgeErrors` (this plan's default).** ~12 lines in a file that already exists and is already tested, zero new dependencies, and it covers exactly the case this plan needs. The spec text changes to describe it. Cost: a house-local mechanism where the spec named a standard one, and `hx-target-error` never becomes available.
- **(B) Vendor `response-targets`.** Matches the spec as written and gives every screen `hx-target-error`. Cost: a new vendored file to pin and audit, an `hx-ext="response-targets"` on `<body>`, and it does the same job as (A) for this plan's purposes.

Both are sound. **(A) is the recommendation** — this plan needs one narrow behaviour, and a dependency added to avoid twelve lines is a dependency to maintain forever. If the operator picks (B), Task 1 Steps 5-8 are replaced by the vendoring, Shape C's headers become `hx-target-error="#toast"` on the control, and the JS test budget moves to a render assertion that the extension is enabled.

## Explicitly NOT in this plan

- The pipeline editor's banner, error-modal and announce surfaces — `2026-08-31-execute-page-redesign.md`.
- Any change to one-time-secret surfaces beyond a pointer toast: `partials/api-key-created.html` and `oneTimeNotice` keep showing their secret inline, persistently.
- Field-level validation (the password-change failures) — §5.1 keeps these inline, and this plan does not move them.
- `LocalLoginController` `POST /login` — pre-auth, renders without the app layout, so there is no `#toast` stack to append to.
- `TemplateEditorController`'s render preview — not a state mutation.
- Making `UiExceptionHandler` htmx-aware (Task 6 Step 3 records the gap) — it returns full error pages to partial requests, which is a broader contract change than a toast rollout.
- Converting the workspaces mutations to htmx partial endpoints — Task 7 keeps the redirect flow deliberately and only changes how its result is displayed.
