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
 * 079 §A/§B added the SHELL CHROME's client half. The rail and the top bar live
 * OUTSIDE #app-main, so a boosted swap never re-renders them — everything the
 * server computed for the first paint has to be kept true here afterwards:
 *
 * 4. THE BREADCRUMB. Re-derived from the rail's own active link (which carries
 *    data-nav-group / data-nav-label), so the highlighted section and the crumb
 *    cannot disagree. `AppNav.crumbFor` produced the same pair server-side and
 *    `ShellRenderTest` asserts the table and the markup agree.
 *
 * 5. THE RAIL'S COLLAPSED STATE. A class on <html>, because the layout's one
 *    inline script must set it before <body> exists (otherwise the rail paints
 *    at 232px and snaps to 60px on every navigation). This file toggles the
 *    same class on the same element and persists it to localStorage.
 *
 * 6. THE THEME. The mode toggle, the Appearance segment and the palette
 *    swatches are all plain htmx PATCHes of the ONE preference
 *    (users.theme_preference — the design system ships one stylesheet per look,
 *    so light/dark/auto and the six palettes are nine values of one field). The
 *    response is an out-of-band swap of #theme-link plus a toast, and nothing
 *    in it describes the new state, so this reads the swapped href BACK and
 *    brings the icon, the segment, the swatches and <html data-theme> in line.
 *    Reading the href rather than the value we asked for is what makes a
 *    REFUSED write leave the controls exactly where they were.
 *
 * 7. THE AVATAR MENU. Open on click, close on Escape / outside click, arrow
 *    keys move within it (focus only — its buttons are htmx triggers and must
 *    never be activated by a keyboard move), aria-expanded on the trigger.
 *
 * Testability: the module exports the pure halves for `node --test`
 * (modules/web/src/test/js/shell.test.mjs); init() is idempotent and installs
 * every listener on document.body (plus one keydown on document, for the menu),
 * which survives all swaps.
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
      // 079 §A: the highlight is also STATE, not just paint — a screen reader
      // announces aria-current, and the CSS keys off both so the server-rendered
      // first paint and this mirror produce the same rule.
      if (on) links[i].setAttribute("aria-current", "page");
      else links[i].removeAttribute("aria-current");
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


  /* ------------------------------------------------------------------ 079 §A/§B
     The rail and the top bar are OUTSIDE #app-main, so a boosted swap never
     re-renders them. Everything below is the client-side half of a fact the
     server computed for the first paint: the highlighted section, the
     breadcrumb, the active theme, and the rail's collapsed state.

     All of it is written as pure functions over a `doc`, so `node --test` can
     drive them against a shimmed DOM (src/test/js/shell.test.mjs) without a
     browser and without htmx.
     ---------------------------------------------------------------------- */

  var RAIL_KEY = "dp-rail";
  var COLLAPSED_CLASS = "rail-collapsed";
  var THEME_HREF = /\/themes\/([a-z0-9-]+)\.css/;

  /* The rail's collapsed state lives on <html>, because the layout's one inline
     script has to set it before <body> exists (a flash of the wrong rail width
     on every navigation is exactly what the boosted shell exists to avoid).
     This toggles the SAME element and class, so there is one source of truth. */
  function setRailCollapsed(doc, collapsed, storage) {
    var root = doc.documentElement;
    if (!root) return collapsed;
    root.classList.toggle(COLLAPSED_CLASS, collapsed);
    var button = doc.getElementById("rail-collapse");
    if (button) {
      button.setAttribute("aria-expanded", String(!collapsed));
      button.setAttribute("title", collapsed ? "Expand sidebar" : "Collapse sidebar");
    }
    try {
      if (storage) storage.setItem(RAIL_KEY, collapsed ? "1" : "0");
    } catch (e) {
      /* Private modes throw on write. A preference that cannot be saved is not a
         reason to refuse to collapse the rail for this page. */
    }
    return collapsed;
  }

  function railCollapsed(doc) {
    return !!(doc.documentElement && doc.documentElement.classList.contains(COLLAPSED_CLASS));
  }

  /* The breadcrumb, re-derived from the rail itself: the active link carries its
     own group and label, and AppNav.crumbFor produced the same pair server-side
     (ShellRenderTest asserts the two agree for every link). Reading the DOM
     rather than shipping a second copy of the table is what keeps them agreeing
     after a swap. Off-rail screens (Settings) have no active link — their
     server-rendered crumb is left alone, since the client has nothing better. */
  function syncCrumbs(doc, path) {
    var crumbs = doc.getElementById("app-crumbs");
    if (!crumbs) return null;
    var links = doc.querySelectorAll(".app-nav-link[data-nav-section]");
    var active = null;
    for (var i = 0; i < links.length; i++) {
      if (isActiveSection(links[i].getAttribute("data-nav-section"), path)) active = links[i];
    }
    if (!active) return null;
    var group = active.getAttribute("data-nav-group") || "";
    var label = active.getAttribute("data-nav-label") || "";
    var groupEl = crumbs.querySelector(".app-crumb-group");
    var sepEl = crumbs.querySelector(".app-crumb-sep");
    var pageEl = crumbs.querySelector(".app-crumb-page");
    if (groupEl) {
      groupEl.textContent = group;
      groupEl.hidden = !group;
    }
    if (sepEl) sepEl.hidden = !group;
    if (pageEl) pageEl.textContent = label;
    return { group: group, label: label };
  }

  /* "…/themes/dark.css" -> "dark". The theme PATCH answers with an out-of-band
     replacement of #theme-link, so the href is the authoritative statement of
     which theme is now live — reading it back beats trusting what we asked for
     (the request can be refused; the OOB swap is the confirmation). */
  function themeFromHref(href) {
    var match = THEME_HREF.exec(href || "");
    return match ? match[1] : null;
  }

  function activeTheme(doc) {
    var link = doc.getElementById("theme-link");
    return link ? themeFromHref(link.getAttribute("href")) : null;
  }

  /* Everything the top bar shows about the theme, brought in line with the theme
     that is actually loaded: <html data-theme>, the sun/moon pair and what the
     toggle will ask for NEXT, the Appearance segment, the palette swatches, and
     the menu's theme name. No reload — that is the whole point of §B. */
  function applyTheme(doc, theme) {
    if (!theme) return null;
    if (doc.documentElement) doc.documentElement.setAttribute("data-theme", theme);

    var dark = theme === "dark";
    var icons = doc.querySelectorAll("[data-mode-icon]");
    for (var i = 0; i < icons.length; i++) {
      icons[i].hidden = icons[i].getAttribute("data-mode-icon") !== (dark ? "dark" : "light");
    }
    var toggle = doc.getElementById("mode-toggle");
    if (toggle) {
      var next = dark ? "light" : "dark";
      toggle.setAttribute("hx-vals", '{"theme":"' + next + '"}');
      toggle.setAttribute("title", "Switch to " + next);
      toggle.setAttribute("aria-label", "Switch to " + next);
    }
    var modes = doc.querySelectorAll("#app-appearance [data-mode]");
    for (var m = 0; m < modes.length; m++) {
      var on = modes[m].getAttribute("data-mode") === theme;
      modes[m].setAttribute("aria-pressed", String(on));
      modes[m].setAttribute("aria-checked", String(on));
    }
    var swatches = doc.querySelectorAll(".app-swatch[data-swatch]");
    for (var s = 0; s < swatches.length; s++) {
      swatches[s].setAttribute("aria-pressed", String(swatches[s].getAttribute("data-swatch") === theme));
    }
    var name = doc.getElementById("app-theme-name");
    if (name) name.textContent = theme;
    return theme;
  }

  /* ---- the avatar menu ---- */

  function menuItems(menu) {
    return menu ? menu.querySelectorAll('[role="menuitem"], [role="menuitemradio"]') : [];
  }

  function setMenuOpen(doc, open) {
    var menu = doc.getElementById("app-user-menu");
    var avatar = doc.getElementById("app-avatar");
    if (!menu || !avatar) return false;
    menu.hidden = !open;
    avatar.setAttribute("aria-expanded", String(open));
    return open;
  }

  function menuIsOpen(doc) {
    var menu = doc.getElementById("app-user-menu");
    return !!(menu && !menu.hidden);
  }

  /* Arrow keys move within the menu, wrapping at both ends; Home/End jump. The
     menu's own buttons are htmx triggers, so moving focus must never activate
     one — this only calls focus(). */
  function moveMenuFocus(doc, delta) {
    var menu = doc.getElementById("app-user-menu");
    var items = menuItems(menu);
    if (!items.length) return null;
    var current = -1;
    for (var i = 0; i < items.length; i++) {
      if (items[i] === doc.activeElement) current = i;
    }
    var next;
    if (delta === "first") next = 0;
    else if (delta === "last") next = items.length - 1;
    else next = (current + delta + items.length) % items.length;
    if (items[next] && items[next].focus) items[next].focus();
    return items[next];
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
      syncCrumbs(doc, window.location.pathname);
    };
    doc.body.addEventListener("htmx:pushedIntoHistory", resync);
    // afterSettle also covers history restores (back/forward), which do not push.
    doc.body.addEventListener("htmx:afterSettle", resync);

    /* 079 §A — the rail toggle. One listener on body, so it survives every swap
       (the rail itself is outside #app-main and is never replaced, but a
       document-level listener is the contract this file already keeps). */
    doc.body.addEventListener("click", function (evt) {
      var toggle = evt.target.closest && evt.target.closest("#rail-collapse");
      if (!toggle) return;
      setRailCollapsed(doc, !railCollapsed(doc), window.localStorage);
    });

    /* 079 §B — the avatar menu. Opening is a click on the avatar; closing is
       Escape, a click anywhere outside, or navigating. Arrow keys move within
       it without activating anything. */
    doc.body.addEventListener("click", function (evt) {
      var avatar = evt.target.closest && evt.target.closest("#app-avatar");
      if (avatar) {
        evt.stopPropagation();
        setMenuOpen(doc, !menuIsOpen(doc));
        return;
      }
      if (menuIsOpen(doc) && !(evt.target.closest && evt.target.closest(".app-user"))) {
        setMenuOpen(doc, false);
      }
    });
    doc.addEventListener("keydown", function (evt) {
      if (!menuIsOpen(doc)) return;
      if (evt.key === "Escape") {
        setMenuOpen(doc, false);
        var avatar = doc.getElementById("app-avatar");
        if (avatar && avatar.focus) avatar.focus();
        return;
      }
      var moves = { ArrowDown: 1, ArrowUp: -1, Home: "first", End: "last" };
      if (Object.prototype.hasOwnProperty.call(moves, evt.key)) {
        evt.preventDefault();
        moveMenuFocus(doc, moves[evt.key]);
      }
    });

    /* 079 §B — the theme. The PATCH answers with an out-of-band swap of
       #theme-link and a toast; nothing else in the response describes the new
       state, so the shell reads the swapped href back and brings the icon, the
       Appearance segment, the swatches and <html data-theme> in line with it.
       Reading the href (rather than the value we asked for) is what makes a
       REFUSED theme write leave the controls where they were. */
    doc.body.addEventListener("htmx:afterSettle", function () {
      applyTheme(doc, activeTheme(doc));
    });

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
    syncCrumbs(doc, window.location.pathname);
    // The rail's class is already on <html> (the layout's pre-paint script); this
    // brings the button's aria-expanded into line with it on the first paint.
    setRailCollapsed(doc, railCollapsed(doc), null);
  }

  var api = {
    isActiveSection: isActiveSection,
    syncNavActive: syncNavActive,
    applyBoostSwap: applyBoostSwap,
    showProgress: showProgress,
    hideProgress: hideProgress,
    init: init,
    setRailCollapsed: setRailCollapsed,
    railCollapsed: railCollapsed,
    syncCrumbs: syncCrumbs,
    themeFromHref: themeFromHref,
    activeTheme: activeTheme,
    applyTheme: applyTheme,
    setMenuOpen: setMenuOpen,
    menuIsOpen: menuIsOpen,
    moveMenuFocus: moveMenuFocus,
    MAIN_SELECTOR: MAIN_SELECTOR,
    SWAP_SPEC: SWAP_SPEC,
    RAIL_KEY: RAIL_KEY,
    COLLAPSED_CLASS: COLLAPSED_CLASS,
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
