/*
 * The template editor's behaviour (097 §D) — the draft lifecycle, the render-context rail
 * and the preview call.
 *
 * It was a ~135-line `<script th:inline="javascript">` in templates/templates/editor.html,
 * the only inline script body in the app. Being inline it was outside
 * `StaticJsCsrfAuditTest`'s `static/js/**` sweep, outside `node --test`, and outside every
 * review that reads JS as JS; it had also grown its own CSRF reader, its own `innerHTML` row
 * builder, two blocking `window.alert`/`confirm` dialogs and a raw `fetch` at a `/partials`
 * URL — which §2.1 gives to htmx, not to fetch.
 *
 * What the file talks to, and how (§2.1):
 *   - `/api/v1/templates/release` and `/api/v1/templates/draft/discard` by `fetch`, with the
 *     `DP-CSRF-Token` header from the ONE reader (js/csrf.js) and the `If-Match` draft hash;
 *   - `/partials/templates/render` through `htmx.ajax`, like the pipeline editor's node-SQL
 *     seam (pipeline-editor/init.js) — a partial is a fragment for htmx to swap, and going
 *     through htmx is also what makes the CSRF header the layout's job rather than this
 *     file's.
 *
 * Nothing here writes markup as a string or an inline style: rows are built with
 * `createElement`, the tab state is a class toggle (`app-tab-active`/`app-tab-idle`), and a
 * failure is a `DpToast` or the `.app-error-card` built node — never `window.alert`, which
 * blocks the page and cannot be styled, tested or dismissed by the app (§5.1 shapes A–D).
 *
 * Testability: an IIFE exporting its state machine for `node --test`
 * (modules/web/src/test/js/lifecycle.test.mjs); in the browser it publishes
 * `window.TplLifecycle` plus the handful of global names the page's `onclick` attributes
 * still use.
 */
(function () {
  "use strict";

  var selectedTab = "kv";

  function doc() {
    return typeof document === "undefined" ? null : document;
  }

  function byId(id) {
    var d = doc();
    return d ? d.getElementById(id) : null;
  }

  /** The template this page is editing — a data attribute, so no server value is inlined into JS. */
  function templateId() {
    var page = doc() && doc().querySelector("[data-template-id]");
    return (page && page.getAttribute("data-template-id")) || "";
  }

  function toast(variant, title, message) {
    if (typeof window !== "undefined" && window.DpToast) window.DpToast.show(variant, title, message);
  }

  function csrfToken() {
    return typeof window !== "undefined" && window.DpCsrf ? window.DpCsrf.token() : "";
  }

  /* ------------------------------------------------------------------ the draft lifecycle */

  /**
   * versioning §5.3/§5.4: release and discard are hash-guarded POSTs to the REST surface,
   * through the same CSRF double-submit pair every cookie-authenticated fetch uses.
   *
   * Discard asks first — and asks IN THE PAGE (`#tpl-discard-confirm`, the house backdrop +
   * `.app-modal` idiom), because a `window.confirm` is a browser dialog the product cannot
   * style, cannot test through the browser suite and cannot dismiss from its own code.
   */
  function lifecycle(action) {
    if (action === "discard") {
      openConfirm();
      return null;
    }
    return submit("release");
  }

  function openConfirm() {
    var modal = byId("tpl-discard-confirm");
    if (modal) modal.classList.remove("u-backdrop-hidden");
  }

  function closeConfirm() {
    var modal = byId("tpl-discard-confirm");
    if (modal) modal.classList.add("u-backdrop-hidden");
  }

  function confirmDiscard() {
    closeConfirm();
    return submit("discard");
  }

  /**
   * §9.6: the template name never travels in a URL path segment (a `%2F` is refused 400
   * below routing), so both verbs carry it in the JSON body.
   */
  function submit(action) {
    var btn = byId(action === "release" ? "tpl-release-draft" : "tpl-discard-draft");
    if (!btn) return null;
    var id = btn.getAttribute("data-id") || templateId();
    var hash = btn.getAttribute("data-hash");
    btn.disabled = true;
    var url = action === "release" ? "/api/v1/templates/release" : "/api/v1/templates/draft/discard";
    return fetch(url, {
      method: "POST",
      headers: { "DP-CSRF-Token": csrfToken(), "If-Match": hash, "Content-Type": "application/json" },
      credentials: "same-origin",
      body: JSON.stringify({ name: id }),
    })
      .then(function (response) {
        if (response.ok) {
          if (typeof window !== "undefined" && window.location) window.location.reload();
          return null;
        }
        return response
          .json()
          .catch(function () {
            return {};
          })
          .then(function (err) {
            throw new Error((err && err.error && err.error.message) || "HTTP " + response.status);
          });
      })
      .catch(function (err) {
        btn.disabled = false;
        toast("danger", action === "release" ? "Release refused" : "Discard refused", err.message);
        return null;
      });
  }

  /* ------------------------------------------------------------------ the context rail */

  /** The tab state is CLASSES, not styles written onto the elements from script. */
  function switchTab(tab) {
    selectedTab = tab;
    var kv = byId("context-kv");
    var json = byId("context-json");
    if (kv) kv.classList.toggle("u-hidden", tab !== "kv");
    if (json) json.classList.toggle("u-hidden", tab !== "json");
    setTabButton(byId("tab-kv-btn"), tab === "kv");
    setTabButton(byId("tab-json-btn"), tab === "json");
    return selectedTab;
  }

  function setTabButton(btn, active) {
    if (!btn) return;
    btn.classList.toggle("app-tab-active", active);
    btn.classList.toggle("app-tab-idle", !active);
    btn.classList.toggle("u-semibold", active);
  }

  /** `createElement`, never an `innerHTML` string — the row carries user input a moment later. */
  function addContextRow() {
    var d = doc();
    var rows = byId("context-rows");
    if (!d || !rows) return null;
    var row = d.createElement("div");
    row.className = "context-row";
    row.appendChild(textInput(d, "key"));
    row.appendChild(textInput(d, "value"));
    row.appendChild(removeButton(d));
    rows.appendChild(row);
    return row;
  }

  function textInput(d, placeholder) {
    var input = d.createElement("input");
    input.type = "text";
    input.placeholder = placeholder;
    return input;
  }

  function removeButton(d) {
    var button = d.createElement("button");
    button.className = "ds-button ds-button-ghost ds-button-sm";
    button.type = "button";
    button.addEventListener("click", function () {
      removeContextRow(button);
    });
    var svg = d.createElementNS ? d.createElementNS("http://www.w3.org/2000/svg", "svg") : d.createElement("svg");
    svg.setAttribute("class", "ds-icon ds-icon-sm");
    svg.setAttribute("aria-hidden", "true");
    svg.setAttribute("focusable", "false");
    var use = d.createElementNS ? d.createElementNS("http://www.w3.org/2000/svg", "use") : d.createElement("use");
    use.setAttribute("href", "/vendor/icons/lucide-sprite.svg#x");
    svg.appendChild(use);
    button.appendChild(svg);
    return button;
  }

  function removeContextRow(btn) {
    var row = btn && btn.closest ? btn.closest(".context-row") : null;
    if (row) row.remove();
  }

  /**
   * The render context: whichever tab is showing. A bare number is sent as a number — the
   * server's parameter types are typed, and "42" is not 42.
   */
  function buildContextJson() {
    if (selectedTab === "json") {
      var textarea = byId("contextJsonTextarea");
      return textarea ? textarea.value : "{}";
    }
    var obj = {};
    var d = doc();
    var rows = d ? d.querySelectorAll("#context-rows .context-row") : [];
    Array.prototype.forEach.call(rows, function (row) {
      var inputs = row.querySelectorAll("input");
      var key = inputs[0].value.trim();
      var val = inputs[1].value.trim();
      if (key) obj[key] = /^[0-9]+(?:\.[0-9]+)?$/.test(val) ? Number(val) : val;
    });
    return JSON.stringify(obj);
  }

  /* ------------------------------------------------------------------ the preview */

  /**
   * The preview goes through `htmx.ajax`: `/partials/templates/render` is a FRAGMENT
   * endpoint, and §2.1 says fragments are htmx's. The swap is htmx's too, so nothing here
   * assigns `innerHTML`; the highlighting pass runs after the swap has landed.
   */
  function renderPreview() {
    var btn = byId("previewBtn");
    var spinner = byId("previewSpinner");
    var pane = byId("previewPane");
    if (!pane || typeof htmx === "undefined") return null;
    var version = (byId("versionSelect") && byId("versionSelect").value) || "1";
    /* Whichever body is on screen: the editable textarea on the working version, the
       read-only <pre> on a selected one. The server renders the STORED version either
       way — this only keeps the request honest about what the author is looking at. */
    var body =
      (byId("templateBody") && byId("templateBody").value) ||
      (byId("versionBody") && byId("versionBody").textContent) ||
      "";
    if (btn) btn.disabled = true;
    if (spinner) spinner.classList.remove("u-hidden");
    var url =
      "/partials/templates/render?name=" + encodeURIComponent(templateId()) + "&version=" + encodeURIComponent(version);
    return htmx
      .ajax("POST", url, {
        target: "#previewPane",
        swap: "innerHTML",
        values: { body: body, context: buildContextJson() },
      })
      .then(function () {
        /* 041 D4 — rendered SQL gets the shared tokenizer's treatment; the pass is a no-op
           on shapes without a <pre> (the error card). */
        if (typeof window !== "undefined" && window.TplPreviewHighlight) {
          window.TplPreviewHighlight.highlightPreview(pane);
        }
      })
      .catch(function (err) {
        showPreviewFailure(pane, (err && err.message) || "The preview request failed.");
      })
      .finally(function () {
        if (btn) btn.disabled = false;
        if (spinner) spinner.classList.add("u-hidden");
      });
  }

  /** The failure card, built as nodes: the message is text, and text is never markup. */
  function showPreviewFailure(pane, message) {
    var d = doc();
    if (!d || !pane) return;
    var card = d.createElement("div");
    card.className = "ds-card app-error-card u-p-md";
    var p = d.createElement("p");
    p.className = "u-danger u-text-sm";
    p.textContent = message;
    card.appendChild(p);
    pane.textContent = "";
    pane.appendChild(card);
  }

  var api = {
    lifecycle: lifecycle,
    confirmDiscard: confirmDiscard,
    closeConfirm: closeConfirm,
    switchTab: switchTab,
    addContextRow: addContextRow,
    removeContextRow: removeContextRow,
    buildContextJson: buildContextJson,
    renderPreview: renderPreview,
    showPreviewFailure: showPreviewFailure,
    selectedTab: function () {
      return selectedTab;
    },
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") {
    window.TplLifecycle = api;
    // The page's `onclick` attributes call these by name; they are the file's public
    // surface as much as `TplLifecycle` is.
    window.tplLifecycle = lifecycle;
    window.switchTab = switchTab;
    window.addContextRow = addContextRow;
    window.removeContextRow = removeContextRow;
    window.renderPreview = renderPreview;
  }
})();
