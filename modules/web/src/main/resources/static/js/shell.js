/*
 * 076 §B — the app shell under hx-boost (ui-screens.md §3.x).
 *
 * layouts/default.html puts hx-boost="true" on the <nav> and on <main
 * id="app-main">: a section click (or any in-content navigation) fetches the
 * FULL page — there is no second template variant — and swaps only the main
 * region. The nav, the workspace switcher and the toast stack persist; the URL
 * updates (boosted requests push by default); back/forward work through htmx
 * history. Full navigations remain only for the routes marked hx-boost="false"
 * (/login, /logout, OIDC redirects, the forced-change gate, downloads).
 *
 * This file owns the three client-side halves of that contract:
 *
 * 1. THE SWAP POLICY. hx-target/hx-select/hx-swap are deliberately NOT set on
 *    <main>: htmx inherits them into EVERY child request, which would retarget
 *    the screens' partial swaps (search results, tree levels, dashboard stats)
 *    at #app-main and break them. Instead the policy is applied here, on
 *    htmx:beforeSwap, only to requests htmx flags as boosted: retarget at
 *    #app-main, select only #app-main out of the full response, outerHTML so
 *    the main element itself is replaced, show:window:top so a section change
 *    reads like a navigation. Non-boosted (partial) requests pass through
 *    untouched.
 *
 * 2. THE PROGRESS INDICATOR. A document-per-click used to signal work with a
 *    white flash; a boosted swap must not flash, so something else has to say
 *    "loading". One 2px bar under the nav, shown between htmx:beforeRequest
 *    and htmx:afterSettle for boosted requests only (partials already carry
 *    their own htmx-indicator spinners).
 *
 * 3. THE ACTIVE-SECTION STATE. The nav highlight is server-computed
 *    (currentPath) for the first paint; after a boosted swap the nav is NOT
 *    re-rendered, so this mirrors the same rule client-side off
 *    window.location.pathname — Dashboard matches exactly, every other section
 *    is a prefix match on the link's data-nav-section.
 *
 * Testability: the module exports the pure halves for `node --test`
 * (modules/web/src/test/js/shell.test.mjs); init() is idempotent and installs
 * every listener on document.body, which survives all swaps.
 */
(function () {
  "use strict";

  var MAIN_ID = "app-main";
  var MAIN_SELECTOR = "#app-main";
  var SWAP_SPEC = "outerHTML show:window:top";

  /* The nav's active-section rule — mirrors layouts/default.html's server-side
     currentPath classappend exactly: Dashboard by equality, others by prefix. */
  function isActiveSection(section, path) {
    if (!section || !path) return false;
    if (section === "/dashboard") return path === "/dashboard";
    return path.indexOf(section) === 0;
  }

  function syncNavActive(doc, path) {
    var links = doc.querySelectorAll(".app-nav-link[data-nav-section]");
    for (var i = 0; i < links.length; i++) {
      var on = isActiveSection(links[i].getAttribute("data-nav-section"), path);
      links[i].classList.toggle("active", on);
    }
  }

  /* The boosted-swap policy, applied to htmx:beforeSwap's mutable detail (htmx
     reads back target, selectOverride and swapOverride after the event). Only
     boosted, non-error, actually-swapping requests are touched — an ordinary
     error response behaves exactly as before (toast.js's bridgeErrors owns the
     server-retargeted ones). */
  function applyBoostSwap(detail, main) {
    if (!detail || !detail.boosted || detail.isError || !detail.shouldSwap) return false;
    if (!main) return false;
    detail.target = main;
    detail.selectOverride = MAIN_SELECTOR;
    detail.swapOverride = SWAP_SPEC;
    return true;
  }

  function progressBar(doc) {
    return doc.getElementById("app-progress");
  }

  function showProgress(doc) {
    var bar = progressBar(doc);
    if (bar) bar.classList.add("active");
  }

  function hideProgress(doc) {
    var bar = progressBar(doc);
    if (bar) bar.classList.remove("active");
  }

  function init() {
    if (typeof document === "undefined" || !document.body) return;
    if (window.__dpShellInit) return; // the layout loads this once; never double-wire
    window.__dpShellInit = true;
    var doc = document;

    doc.body.addEventListener("htmx:beforeSwap", function (evt) {
      applyBoostSwap(evt.detail, doc.getElementById(MAIN_ID));
    });

    doc.body.addEventListener("htmx:beforeRequest", function (evt) {
      if (evt.detail && evt.detail.boosted) showProgress(doc);
    });
    var settleOrFail = function (evt) {
      if (evt.detail && evt.detail.boosted) hideProgress(doc);
    };
    doc.body.addEventListener("htmx:afterSettle", settleOrFail);
    doc.body.addEventListener("htmx:responseError", settleOrFail);
    doc.body.addEventListener("htmx:sendError", settleOrFail);

    var resync = function () {
      syncNavActive(doc, window.location.pathname);
    };
    doc.body.addEventListener("htmx:pushedIntoHistory", resync);
    // afterSettle also covers history restores (back/forward), which do not push.
    doc.body.addEventListener("htmx:afterSettle", resync);

    // htmx 2's selfRequestsOnly rejects a boosted CROSS-ORIGIN request — but only
    // after the boost handler already preventDefaulted the click, so without this
    // bridge an external link inside #app-main (the rendered docs carry canonical
    // GitHub URLs) would silently die. Falling back to a plain navigation keeps
    // the link a link.
    doc.body.addEventListener("htmx:invalidPath", function (evt) {
      var elt = evt.detail && evt.detail.elt;
      var href = elt && elt.getAttribute && elt.getAttribute("href");
      if (href && /^https?:\/\//.test(href)) window.location.assign(href);
    });

    syncNavActive(doc, window.location.pathname);
  }

  var api = {
    isActiveSection: isActiveSection,
    syncNavActive: syncNavActive,
    applyBoostSwap: applyBoostSwap,
    showProgress: showProgress,
    hideProgress: hideProgress,
    init: init,
    MAIN_SELECTOR: MAIN_SELECTOR,
    SWAP_SPEC: SWAP_SPEC,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") {
    window.DpShell = api;
    if (document.readyState === "loading") {
      document.addEventListener("DOMContentLoaded", init);
    } else {
      init();
    }
  }
})();
