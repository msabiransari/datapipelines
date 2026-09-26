/*
 * #9 slice 2 — the Schedules page's wiring: one delegated click listener for every
 * `data-sch-action`, the search box, the folder toggle, Escape and the backdrop for dialogs,
 * and the deep link (`?id=<schedule>&run=<run>`) on arrival.
 *
 * Under hx-boost this file re-executes on every visit to /schedules (its tag rides inside
 * #app-main — 076 §B). The page ROOT is a fresh element each visit and gets fresh state and
 * its own listeners; the DOCUMENT-level listeners (Escape) are installed once per session.
 */
(function () {
  "use strict";

  var S = (window.DpSchedules = window.DpSchedules || {});

  var UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

  /**
   * One module's function, if that module is on the page: the explorer, the detail, the run
   * dialog and the form are separate files, and a click must never throw because one of them
   * did not load (a 404'd script is a missing verb, not a dead page).
   */
  function call(module, fn) {
    var m = S[module];
    if (!m || typeof m[fn] !== "function") return undefined;
    return m[fn].apply(null, Array.prototype.slice.call(arguments, 2));
  }

  function onClick(event) {
    var t = event.target;
    if (!t.closest) return;
    // A click on a dialog's BACKDROP (not inside its card) closes it, as Escape does.
    if (t.matches && t.matches("[data-sch-dialog]")) {
      S.closeDialog();
      return;
    }
    var leaf = t.closest("#schedule-tree-pane [data-leaf-id]");
    if (leaf) {
      call("detail", "show", leaf.getAttribute("data-leaf-id"));
      return;
    }
    var action = t.closest("[data-sch-action]");
    if (!action || action.getAttribute("aria-disabled") === "true") return;
    var current = call("detail", "current");
    switch (action.getAttribute("data-sch-action")) {
      case "create":
        call("form", "openCreate");
        break;
      case "edit":
        if (current) call("form", "openEdit", current.schedule.id);
        break;
      case "reload-form":
        call("form", "reload");
        break;
      case "pause":
      case "resume":
      case "refresh-runs":
      case "more-runs":
        call("detail", { pause: "pause", resume: "resume", "refresh-runs": "refreshRuns", "more-runs": "moreRuns" }[action.getAttribute("data-sch-action")]);
        break;
      case "unblock":
        S.closeDialog(true);
        call("detail", "unblock");
        break;
      case "run-now":
        call("detail", "runNow");
        break;
      case "delete":
        call("detail", "confirmDelete");
        break;
      case "confirm-delete":
        call("detail", "remove");
        break;
      case "open-run":
      case "open-blocking-run":
        if (current) call("run", "open", current.schedule.id, action.getAttribute("data-run-id"));
        break;
      case "close-dialog":
        S.closeDialog();
        break;
      case "clear-search":
        var box = document.getElementById("schedule-filter-q");
        if (box) {
          box.value = "";
          box.focus();
        }
        S.explorer.setQuery("");
        break;
      case "retry":
        S.explorer.load();
        break;
      default:
        break;
    }
  }

  var searchTimer = null;

  function onSearch(event) {
    if (searchTimer) clearTimeout(searchTimer);
    var value = event.target.value;
    searchTimer = setTimeout(function () {
      searchTimer = null;
      if (S.live()) S.explorer.setQuery(value);
    }, 150);
  }

  var docWired = false;

  function init() {
    var root = document.querySelector("[data-schedules-root]");
    if (!root || root.__schWired) return;
    root.__schWired = true;
    if (S.state && S.state.pollTimer) clearTimeout(S.state.pollTimer);
    S.state = { root: root, all: null, query: "", openFolders: {}, selectedId: null };

    // The root, its detail pane and the dialog host are the three regions a click can land in.
    root.addEventListener("click", onClick);
    var host = document.getElementById("sch-dialog");
    if (host && !host.__schWired) {
      host.__schWired = true;
      host.addEventListener("click", onClick);
    }
    var pane = document.getElementById("schedule-tree-pane");
    if (pane) pane.addEventListener("toggle", S.explorer.onToggle, true);
    var box = document.getElementById("schedule-filter-q");
    if (box) {
      box.addEventListener("input", onSearch);
      box.addEventListener("search", onSearch);
    }
    if (!docWired) {
      docWired = true;
      document.addEventListener("keydown", function (event) {
        if (event.key === "Escape" && S.live() && S.dialog()) {
          event.preventDefault();
          S.closeDialog();
        }
      });
    }

    var params = new URLSearchParams(window.location.search);
    var id = params.get("id");
    var run = params.get("run");
    S.explorer.load().then(function () {
      if (!S.live() || !id || !UUID.test(id)) return;
      var known = (S.state.all || []).filter(function (s) { return s.id === id; })[0];
      if (known) S.explorer.reveal(known.name);
      call("detail", "show", id, run && UUID.test(run) ? function () { call("run", "open", id, run); } : null);
    });
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
  else init();

  // Re-runnable init (a test drives it after replacing the page's markup); the page's OWN
  // request path is `window.DpSchedulesApi`, which the browser suite calls to prove the server
  // refuses a hidden verb's POST from exactly the fetch the page would make (§4.3e).
  S.init = init;
})();
