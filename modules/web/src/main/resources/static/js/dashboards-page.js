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
 *   - init with `credentials: "session"` and `version: "released"` — the released view's only
 *     value — or, since #369, the integer the preview page wrote into
 *     `data-dp-dashboard-version` (a DRAFT or named RELEASED version; dashboards.md §5.2/§7).
 *     The version crosses the SAME data-attribute channel the id rides; nothing else crosses
 *     into script data.
 *   - options.onNotification is the page's sink: notifications land in window.__dpPage AND,
 *     for a boot failure, in the refusal region the server pre-rendered.
 *   - window.__dpPage is the page's test seam (window.__dp is the conformance host's): ready,
 *     error, code, notifications, instance — the same shape, so a reader knows one.
 *
 * Disposal runs at history snapshot creation after navigation admission. The snapshot holds
 * inert shell markup; the persistent host replays this page mount against singleton libraries.
 * ONE history listener disposes the live instance and retains the runtime's refresh cancellation.
 */
(function () {
  "use strict";

  function mount(main) {
  var container = main && main.querySelector ? main.querySelector("#dp-board") : document.getElementById("dp-board");
  if (!container || container.__dpPageMounted) return;
  container.__dpPageMounted = true;
  var pageState = { ready: false, error: null, code: null, notifications: [], instance: null };
  window.__dpPage = pageState;

  var id = container.getAttribute("data-dp-dashboard-id");
  if (!id) return;
  // #369 — the draft preview names its version in the same data attribute channel the id rides
  // (an integer attribute, never a script body); the released view writes none and stays on
  // "released", the only value the first-party page may pass beside a number.
  var versionAttribute = container.getAttribute("data-dp-dashboard-version");
  var version = "released";
  if (versionAttribute !== null && versionAttribute !== "") {
    version = parseInt(versionAttribute, 10);
    // A version attribute that is not an integer is the server's defect, never a bootable page.
    if (!isFinite(version)) return;
  }

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
  if (!window.__dpBoardHistoryCleanup) {
  window.__dpBoardHistoryCleanup = true;
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

  }

  function refusalRegion() {
    return document.getElementById("dp-board-refusal");
  }

  // The refusal state is the in-page answer to a board that cannot run: the server fills it
  // when the configuration read refused; the sink fills it when a later bootstrap step fails
  // (the runtime's `ready` rejection names the failing step and the family's code). Both put
  // a code and a sentence where the person is looking, never a blank pane.
  function showRefusal(code, message) {
    if (main && (!main.isConnected || main.querySelector("#dp-board") !== container)) return;
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
      dashboard: { id: id, version: version },
      container: container,
      adapter: runtime.adapters(container),
      options: {
        onNotification: function (n) {
          pageState.notifications.push(n);
        },
      },
    });
    pageState.instance = instance;
    instance.ready
      .then(function () {
        pageState.ready = true;
      })
      .catch(function (error) {
        pageState.error = error;
        pageState.code = error && error.code ? error.code : String(error);
        showRefusal(pageState.code, error && error.message ? error.message : String(error));
      });
  } catch (e) {
    pageState.code = e && e.code ? e.code : e.name;
    pageState.error = String(e);
    showRefusal(pageState.code, e && e.message ? e.message : String(e));
  }
  }
  window.DashboardPageMount = mount;
  var main = document.getElementById("app-main");
  if (!window.DatapipelinesPageMountManaged && (!main || !main.querySelector("template[data-chart-assets]"))) mount(main);
})();
