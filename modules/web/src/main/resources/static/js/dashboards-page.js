/*
 * dashboards-page.js (#10 L3b) — the board page's glue. The CSP allows no inline script, so
 * this FILE is the whole host: it reads the two data attributes the server wrote (the
 * dashboard id on the container; nothing else crosses into script data), boots the vendored
 * runtime with the first-party composite adapter and the session credential, and renders the
 * page's notification/refusal regions.
 *
 * Deliberately small: it owns NO protocol (the runtime does), NO rendering (the adapters do),
 * NO polling (the events pane's fragment re-fetches itself over htmx). What it owns is the
 * page contract:
 *
 *   - init with `credentials: "session"` and `version: "released"` — the only values the
 *     first-party page may use (dashboards.md §6.1: the server serves the current release
 *     and nothing else).
 *   - options.onNotification is the page's sink: notifications land in window.__dpPage AND,
 *     for a boot failure, in the refusal region the server pre-rendered.
 *   - window.__dpPage is the page's test seam (window.__dp is the conformance host's): ready,
 *     error, code, notifications, instance — the same shape, so a reader knows one.
 *
 * Disposal: links ONTO the board are never boosted (hx-boost="false", the one-bundle rule),
 * but LEAVING it can be (the layout's nav boosts), and htmx saves a snapshot of this document
 * when it goes, then restores that snapshot FIRST on every Back/Forward — a swap that is not
 * a page load. The browser suite measured both of a snapshot's failure modes: carrying the
 * mounted marker, the re-run init refuses DashboardAlreadyMounted; and (post-dispose) the
 * bare shell swaps in with NO script re-run, an empty region where the board was. Either way
 * the settled state is decided by the FETCH htmx performs after the snapshot swap: the server
 * renders the shell again, the glue re-runs, and the runtime mounts exactly once. The glue's
 * only duty is to make the SNAPSHOT harmless — dispose on htmx:beforeHistorySave, so the
 * cached markup is the page's own shell, unmarked, and the snapshot's glue pass (when one
 * runs) finds nothing mounted and refuses nothing. The pages' browser suite proves the
 * settled count across a back/forward pass.
 */
(function () {
  "use strict";

  window.__dpPage = { ready: false, error: null, code: null, notifications: [], instance: null };

  var container = document.getElementById("dp-board");
  if (!container) return;
  var id = container.getAttribute("data-dp-dashboard-id");
  if (!id) return;

  var runtime = window.DatapipelinesDashboard;
  if (!runtime) return;

  // The snapshot must never carry a mounted container OR Plotly's scratch: dispose BEFORE
  // htmx saves history, and strip the tester svg. The tester (#js-plotly-tester) is a BODY
  // child OUTSIDE the boosted swap region, so it survives every swap and would ride the
  // snapshot; htmx's restore settle then re-applies its style ATTRIBUTE — a style-src-attr
  // refusal (the pages' suite measured it; the conformance host has no htmx and could not).
  // Plotly recreates the tester on its next render; a runtime that ever cleans up after
  // itself retires this line. One listener per document; a failing dispose must not break
  // the save.
  document.body.addEventListener("htmx:beforeHistorySave", function () {
    if (window.__dpPage.instance) {
      try {
        window.__dpPage.instance.dispose();
      } catch (e) {
        /* the save proceeds either way */
      }
      window.__dpPage.instance = null;
    }
    var tester = document.getElementById("js-plotly-tester");
    if (tester && tester.parentElement) tester.parentElement.removeChild(tester);
  });

  function refusalRegion() {
    return document.getElementById("dp-board-refusal");
  }

  // The refusal state is the in-page answer to a board that cannot run: the server fills it
  // when the configuration read refused; the sink fills it when a later bootstrap step fails
  // (the runtime's `ready` rejection names the failing step and the family's code). Both put
  // a code and a sentence where the person is looking, never a blank pane.
  function showRefusal(code, message) {
    var region = refusalRegion();
    if (!region) return;
    var text = document.getElementById("dp-board-refusal-message");
    var codeEl = document.getElementById("dp-board-refusal-code");
    if (text && message) text.textContent = message;
    if (codeEl && code) codeEl.textContent = code;
    region.hidden = false;
  }

  try {
    var instance = runtime.init({
      server: { baseUrl: "", credentials: "session" },
      dashboard: { id: id, version: "released" },
      container: container,
      adapter: runtime.adapters(container),
      options: {
        onNotification: function (n) {
          window.__dpPage.notifications.push(n);
        },
      },
    });
    window.__dpPage.instance = instance;
    instance.ready
      .then(function () {
        window.__dpPage.ready = true;
      })
      .catch(function (error) {
        window.__dpPage.error = error;
        window.__dpPage.code = error && error.code ? error.code : String(error);
        showRefusal(window.__dpPage.code, error && error.message ? error.message : String(error));
      });
  } catch (e) {
    window.__dpPage.code = e && e.code ? e.code : e.name;
    window.__dpPage.error = String(e);
    showRefusal(window.__dpPage.code, e && e.message ? e.message : String(e));
  }
})();
