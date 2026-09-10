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
 *    white flash; a swap must not flash, so something else has to say
 *    "loading". 085 §D widened the 076 rule — boosted requests only — to EVERY
 *    htmx request (owner: "we need some kind of an indicator"): one 2px bar
 *    under the nav, driven by an in-flight COUNT (show on 0→1, hide on →0) so
 *    a tree expand and a detail load running together cannot hide it early.
 *    The counting pair is htmx:beforeRequest / htmx:afterRequest — afterRequest
 *    is the ONE terminal event htmx 2.0.10 fires on every outcome (success,
 *    error status, network error, abort, timeout all pass through it; the
 *    obvious afterSettle never fires for an aborted or unsent request, and
 *    responseError/sendError/sendAbort/timeout all arrive AFTER afterRequest),
 *    so it is the only decrement point that can never strand the bar on.
 *    Nothing in this tree cancels beforeRequest, so the two always pair.
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
 * 085 §D added the rest of the every-request signal (§D owns the bar's count;
 * these are its two companions):
 *
 * 8. THE BUSY ORIGINATING CONTROL. The requesting element carries .app-busy
 *    for the flight — set HERE, not read off htmx's .htmx-request, because
 *    htmx 2.0.10 applies that class to the hx-indicator TARGET instead when
 *    the element carries hx-indicator (the tree leaves do), leaving the
 *    control itself unmarked exactly where the affordance is wanted. A
 *    <button> additionally gets aria-disabled="true" — the attribute, NOT the
 *    disabled property: htmx already guards re-triggering, and a
 *    really-disabled button loses focus mid-flight. <summary> and <input> get
 *    the class only (the chevron spin keys off it): a folder's summary must
 *    stay operable while its level loads (collapse / re-expand mid-fetch is
 *    pinned behaviour — ExplorerStressBrowserTest's hammer), and a search
 *    input must keep accepting keystrokes.
 *
 * 9. THE DELAYED SKELETON. A request still in flight 150ms after beforeRequest
 *    marks its swap target aria-busy="true" and appends ONE .app-target-skeleton
 *    row; a faster request never shows either (no flash on fast swaps). Timers
 *    and skeletons are paired PER TARGET — concurrent requests into different
 *    panes are normal — and the request's afterRequest cancels and removes
 *    everything, which always runs BEFORE the swap, so a skeleton never
 *    coexists with the content it was standing in for.
 *
 * 103 §A/§B added the FEEDBACK AND ATMOSPHERE layer — measured against
 * algoschool.app (notes T195), which has zero hx-boost and still reads as the
 * more native app because a click there is acknowledged immediately and the new
 * page arrives with motion:
 *
 * 10. THE PENDING NAVIGATION. A boosted link click marks the link `is-pending`
 *     + aria-disabled and its rail group `is-pending-scope` in the SAME frame as
 *     the press — before any request exists, which is the whole point; §D's bar
 *     lives at the top of the window, nowhere near the thing that was pressed.
 *     A status pill ("Loading…", role=status) joins them only after 150ms, the
 *     same no-flash arm as the skeleton. Cleared on htmx:afterSettle, on every
 *     htmx error/abort event (afterSettle never fires for those) and on
 *     `pageshow` (the bfcache restores the DOM with the class still on it and
 *     fires no htmx event at all). A form's pending rides on its SUBMIT BUTTON,
 *     because htmx reports the <form> as the requesting element and §D's
 *     per-element marking therefore never reaches the control that was pressed.
 *
 * 11. THE ENTRANCE. The new #app-main fades and slides 4px up over 150ms. The
 *     class is added on htmx:afterSETTLE — at afterSwap the element is still
 *     wearing `htmx-swapping htmx-added htmx-settling` and htmx replaces it a
 *     frame later, which starts the animation and then cancels it 15ms in
 *     (measured; every class-level assertion stayed green) — and removed on
 *     `animationend` plus a fallback timer, because under
 *     prefers-reduced-motion app.css sets `animation: none` and an animation
 *     that never runs never ends. Which swaps qualify is decided by
 *     applyBoostSwap and carried on a one-shot: htmx's swap events carry the
 *     SWAP's eventInfo, not the response's, and have no `boosted` flag.
 *
 * 110 §A added the PHONE DRAWER, the shell's third state:
 *
 * 12. THE DRAWER. Below 768px the SAME <aside class="app-rail"> becomes an
 *     off-canvas drawer — app.css takes it out of the grid, translates it
 *     off-screen and lights it under `rail-open`, a class on <html> like the
 *     collapse but NEVER persisted: a drawer is closed on every load. Opened by
 *     the topbar's #rail-open (display:none above the breakpoint, so the control
 *     cannot exist where the drawer cannot); closed by #rail-close, Escape, the
 *     scrim, or any BOOSTED navigation — the close hangs off the same one-shot
 *     applyBoostSwap arms for the entrance, so a background partial settling
 *     while the drawer is open cannot slam it shut, and setRailOpen's no-change
 *     early return keeps desktop swaps focus-neutral. Opening moves focus to the
 *     first nav link; closing returns it to the opener. From 768 to 1099px the
 *     rail's default flips to collapsed by pure CSS; the user's explicit choice
 *     travels the same dp-rail key (`rail-expanded` = a stored "0", stamped
 *     pre-paint by the layout's inline script).
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

  /* ------------------------------------------------------------------ 085 §D
     A signal for every server trip. One tracker instance counts the requests
     in flight (the bar shows on 0→1, hides on →0), marks the originating
     <button> aria-disabled for the duration, and arms the delayed skeleton on
     the swap target. Written as a factory over injected timers so
     `node --test` drives it with a fake clock and hand-rolled elements — the
     module's pure-function-over-`doc` contract (shell.test.mjs).

     The end() half is safe to call for a request that never began: the count
     clamps at zero and every per-element cleanup is keyed on state begin()
     recorded, so a stray terminal event can never hide a bar another request
     is still holding open or strip a marker it did not set. */
  var SKELETON_DELAY_MS = 150;
  var SKELETON_CLASS = "app-target-skeleton";
  /* Shell-owned, not htmx's .htmx-request: htmx 2.0.10 applies ITS class to
     the hx-indicator target when the element carries hx-indicator
     (addRequestIndicatorClasses), so the requesting control would go unmarked
     exactly where it carries its own spinner — the tree leaves do. One marker
     the shell sets and clears itself works for every element either way. */
  var BUSY_CLASS = "app-busy";

  function createBusyTracker(timers, delayMs) {
    var delay = typeof delayMs === "number" ? delayMs : SKELETON_DELAY_MS;
    var inFlight = 0;
    var busyMarked = []; // elements WE classed — only those get unclassed
    var ariaMarked = []; // buttons WE marked — only those get unmarked
    var targets = []; // { target, count, timer, skeleton } — one entry per swap target

    function targetEntry(target, create) {
      for (var i = 0; i < targets.length; i++) {
        if (targets[i].target === target) return targets[i];
      }
      if (!create) return null;
      var entry = { target: target, count: 0, timer: null, skeleton: null };
      targets.push(entry);
      return entry;
    }

    /* The 150ms arm: a fast request ends before this fires and never pays for
       the skeleton (no flash); a slow one gets aria-busy + ONE skeleton row in
       the pane it is about to replace. */
    function armSkeleton(doc, entry) {
      entry.timer = timers.setTimeout(function () {
        entry.timer = null;
        entry.target.setAttribute("aria-busy", "true");
        var skeleton = doc.createElement("div");
        skeleton.className = "ds-skeleton ds-skeleton-table-row " + SKELETON_CLASS;
        skeleton.setAttribute("aria-hidden", "true");
        entry.target.appendChild(skeleton);
        entry.skeleton = skeleton;
      }, delay);
    }

    function clearEntry(entry) {
      if (entry.timer !== null) {
        timers.clearTimeout(entry.timer);
        entry.timer = null;
      }
      entry.target.removeAttribute("aria-busy");
      if (entry.skeleton) {
        if (entry.skeleton.parentNode) entry.skeleton.parentNode.removeChild(entry.skeleton);
        entry.skeleton = null;
      }
      targets.splice(targets.indexOf(entry), 1);
    }

    function begin(doc, elt, target) {
      inFlight += 1;
      if (inFlight === 1) showProgress(doc);
      if (elt && elt.classList) {
        elt.classList.add(BUSY_CLASS);
        busyMarked.push(elt);
        if (elt.tagName === "BUTTON" && elt.getAttribute("aria-disabled") === null) {
          elt.setAttribute("aria-disabled", "true");
          ariaMarked.push(elt);
        }
      }
      if (target && target.setAttribute) {
        var entry = targetEntry(target, true);
        entry.count += 1;
        if (entry.count === 1) armSkeleton(doc, entry);
      }
    }

    function end(doc, elt, target) {
      inFlight = Math.max(0, inFlight - 1);
      if (inFlight === 0) hideProgress(doc);
      var classedAt = busyMarked.indexOf(elt);
      if (classedAt !== -1) {
        elt.classList.remove(BUSY_CLASS);
        busyMarked.splice(classedAt, 1);
      }
      var markedAt = ariaMarked.indexOf(elt);
      if (markedAt !== -1) {
        elt.removeAttribute("aria-disabled");
        ariaMarked.splice(markedAt, 1);
      }
      var entry = target && target.setAttribute ? targetEntry(target, false) : null;
      if (entry) {
        entry.count -= 1;
        if (entry.count <= 0) clearEntry(entry);
      }
    }

    return {
      begin: begin,
      end: end,
      inFlight: function () {
        return inFlight;
      },
    };
  }


  /* ------------------------------------------------------------------ 103 §A
     THE CLICK IS ACKNOWLEDGED.

     085 §D's bar answers "is the server working"; it does not answer "did my
     click land". Measured against algoschool.app (notes T195): that app has no
     hx-boost at all — every navigation is a document load — and still reads as
     the more native of the two, because a click there IMMEDIATELY dims the
     clicked link, marks it aria-disabled, and raises a "Loading page…" pill
     after a short delay. Our boosted swap gives the same trip no feedback until
     the 2px bar appears, and the bar is at the top of the window, nowhere near
     the thing the reader just pressed.

     This tracker is that feedback, and it is NAVIGATION-scoped rather than
     request-scoped (the busy tracker above owns the per-request half): one
     navigation is pending at a time, its link carries `is-pending`, its rail
     group carries `is-pending-scope`, and a pill appears only if the swap is
     still not here after 150ms — the same no-flash-on-a-fast-swap arm as the
     skeleton, for the same reason.

     Written as a factory over injected timers, like createBusyTracker, so
     `node --test` drives the whole state machine on a fake clock. */
  var PENDING_DELAY_MS = 150;
  var PENDING_CLASS = "is-pending";
  var PENDING_SCOPE_CLASS = "is-pending-scope";
  var PILL_ID = "app-status-pill";
  var PILL_ON_CLASS = "is-on";

  function statusPill(doc) {
    return doc.getElementById(PILL_ID);
  }

  /* The pill is present-but-`hidden` when off, not merely transparent: an
     aria-live region announces when its content BECOMES rendered, and a region
     that is permanently rendered with unchanging text announces nothing at all.
     `[hidden] { display: none !important }` (app.css) is what makes the
     attribute outrank the design system's display classes. */
  function showStatusPill(doc) {
    var pill = statusPill(doc);
    if (!pill) return false;
    pill.removeAttribute("hidden");
    pill.classList.add(PILL_ON_CLASS);
    return true;
  }

  function hideStatusPill(doc) {
    var pill = statusPill(doc);
    if (!pill) return false;
    pill.classList.remove(PILL_ON_CLASS);
    pill.setAttribute("hidden", "hidden");
    return true;
  }

  /* Is this click a BOOSTED navigation, and what is the group it belongs to?
     Returns the scope element (the rail's <nav>, the breadcrumb, or whatever
     element carries the hx-boost that will handle the click) or null for
     anything that is not a boosted same-document navigation — a fragment link,
     a new-tab link, a download, an element under hx-boost="false".

     Pure over the element's own `closest`, so the state machine is testable
     without a DOM: `closest("[hx-boost]")` is the SAME lookup htmx performs, so
     an element it will not boost cannot be pended here. */
  function boostedNavScope(link) {
    if (!link || !link.getAttribute) return null;
    var href = link.getAttribute("href");
    if (!href || href.charAt(0) === "#") return null;
    if (link.getAttribute("target")) return null;
    if (link.getAttribute("download") !== null) return null;
    if (!link.closest) return null;
    var boost = link.closest("[hx-boost]");
    if (!boost || boost.getAttribute("hx-boost") !== "true") return null;
    return link.closest(".app-nav") || link.closest("#app-crumbs") || boost;
  }

  /* A form submitted through htmx: the control the reader pressed is the submit
     button, not the <form> htmx reports as the requesting element, so the busy
     tracker's per-element marking never reaches it. */
  function submitControl(form) {
    if (!form || !form.querySelector) return null;
    return form.querySelector("button[type='submit'], button:not([type]), input[type='submit']");
  }

  function createPendingTracker(timers, delayMs) {
    var delay = typeof delayMs === "number" ? delayMs : PENDING_DELAY_MS;
    var marked = []; // { el, cls, aria } — only what WE set is ever unset
    var timer = null;

    function mark(el, cls, withAria) {
      if (!el || !el.classList) return false;
      if (el.classList.contains && el.classList.contains(cls)) return false;
      el.classList.add(cls);
      var aria = false;
      if (withAria && el.getAttribute && el.getAttribute("aria-disabled") === null) {
        el.setAttribute("aria-disabled", "true");
        aria = true;
      }
      marked.push({ el: el, cls: cls, aria: aria });
      return true;
    }

    /* The 150ms arm, per NAVIGATION rather than per element: a second pended
       control inside one navigation (a form's button and its scope) must not
       restart or duplicate the timer. */
    function arm(doc) {
      if (timer !== null) return;
      timer = timers.setTimeout(function () {
        timer = null;
        showStatusPill(doc);
      }, delay);
    }

    function begin(doc, elt, scope) {
      if (!mark(elt, PENDING_CLASS, true)) return false;
      if (scope && scope !== elt) mark(scope, PENDING_SCOPE_CLASS, false);
      arm(doc);
      return true;
    }

    function clear(doc) {
      if (timer !== null) {
        timers.clearTimeout(timer);
        timer = null;
      }
      for (var i = 0; i < marked.length; i++) {
        var entry = marked[i];
        entry.el.classList.remove(entry.cls);
        if (entry.aria) entry.el.removeAttribute("aria-disabled");
      }
      marked = [];
      hideStatusPill(doc);
      return true;
    }

    return {
      begin: begin,
      clear: clear,
      pendingCount: function () {
        return marked.length;
      },
      armed: function () {
        return timer !== null;
      },
    };
  }

  /* ------------------------------------------------------------------ 103 §B
     THE SWAP ARRIVES WITH MOTION.

     A boosted swap replaces the whole of #app-main between two frames, which is
     the one thing a document load never does badly: a page load fades in from
     the browser's own paint, a swap simply teleports. The entrance is a 150ms
     fade + 4px slide-up on the NEW #app-main — transform and opacity only, so
     it cannot move a box and cannot cost a layout shift (§B's CLS budget).

     The class comes off on `animationend`, and off ANYWAY after a fallback:
     under `prefers-reduced-motion: reduce` app.css sets `animation: none`, and
     an animation that never runs never ends. The fallback is what keeps the
     class from becoming permanent state on a reduced-motion machine.

     Which swaps get it is decided by applyBoostSwap, not re-derived here:
     htmx's afterSwap detail is the swap's own eventInfo and carries no
     `boosted` flag, so the beforeSwap that RETARGETED the response sets a
     one-shot and afterSwap consumes it. */
  var ENTER_CLASS = "app-enter";
  var ENTER_FALLBACK_MS = 250;

  function markEntrance(timers, el) {
    if (!el || !el.classList) return false;
    el.classList.add(ENTER_CLASS);
    var done = function () {
      el.classList.remove(ENTER_CLASS);
    };
    if (el.addEventListener) el.addEventListener("animationend", done, { once: true });
    timers.setTimeout(done, ENTER_FALLBACK_MS);
    return true;
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
  /* 110 §A: the mirror of the opposite choice. From 768 to 1099px the rail's DEFAULT
     is collapsed by a CSS media rule; a user who explicitly expanded it there (a
     stored "0") carries this class so the default cannot re-collapse their choice.
     The layout's inline script stamps it before the first paint; this file toggles
     it only on a REAL user click (storage provided), never on the init sync. */
  var EXPANDED_CLASS = "rail-expanded";
  /* 110 §A: the phone drawer's open state — a class on <html>, like the collapse.
     NEVER persisted: a drawer is closed on every load. */
  var RAIL_OPEN_CLASS = "rail-open";
  var THEME_HREF = /\/themes\/([a-z0-9-]+)\.css/;

  /* The rail's collapsed state lives on <html>, because the layout's one inline
     script has to set it before <body> exists (a flash of the wrong rail width
     on every navigation is exactly what the boosted shell exists to avoid).
     This toggles the SAME element and class, so there is one source of truth.

     `storage` doubles as the "this was a real user choice" flag: the init sync
     passes null and must not stamp rail-expanded (the default has to stay free
     to collapse at drawer-adjacent widths); a click passes localStorage and the
     expanded choice is recorded on <html> for the 768–1099 default to honour. */
  function setRailCollapsed(doc, collapsed, storage) {
    var root = doc.documentElement;
    if (!root) return collapsed;
    root.classList.toggle(COLLAPSED_CLASS, collapsed);
    if (storage) root.classList.toggle(EXPANDED_CLASS, !collapsed);
    syncCollapseButton(doc, collapsed);
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

  /* The width band where the rail's DEFAULT is collapsed without any class on
     <html> (110 §A). One string, shared by the state machine and its tests. */
  var DEFAULT_COLLAPSED_QUERY = "(min-width: 768px) and (max-width: 1099.98px)";

  /* What the READER sees, as opposed to what the class says: below 1100px the
     CSS default collapses an undecided rail, so the collapse button must offer
     EXPAND there even though `rail-collapsed` is absent — and the init sync must
     stamp aria-expanded accordingly. Pure over the injected window so `node
     --test` can drive both sides of the media query. */
  function railVisuallyCollapsed(doc, win) {
    if (railCollapsed(doc)) return true;
    if (!doc.documentElement.classList.contains(EXPANDED_CLASS) && win && win.matchMedia) {
      var mq = win.matchMedia(DEFAULT_COLLAPSED_QUERY);
      return !!(mq && mq.matches);
    }
    return false;
  }

  /* The button half of the collapse state, split out so the init sync can stamp
     aria-expanded/title from the VISUAL state without writing the class (the
     classless default must stay classless — the browser, not the document, owns
     that state). */
  function syncCollapseButton(doc, collapsed) {
    var button = doc.getElementById("rail-collapse");
    if (button) {
      button.setAttribute("aria-expanded", String(!collapsed));
      button.setAttribute("title", collapsed ? "Expand sidebar" : "Collapse sidebar");
    }
  }

  /* ---- 110 §A: the phone drawer ---- */

  function railOpen(doc) {
    return !!(doc.documentElement && doc.documentElement.classList.contains(RAIL_OPEN_CLASS));
  }

  function firstRailLink(doc) {
    return doc.querySelector ? doc.querySelector(".app-nav-link") : null;
  }

  /* Opens or closes the drawer, mirrors aria-expanded onto the opener, and does
     the focus choreography ONLY when the state actually changed: opening puts
     focus on the first nav link, closing returns it to the opener. The no-change
     early return is what makes this safe to call from htmx:afterSettle for every
     boosted navigation — a desktop swap (drawer closed) must not move focus. */
  function setRailOpen(doc, open) {
    var root = doc.documentElement;
    if (!root) return open;
    var wasOpen = railOpen(doc);
    root.classList.toggle(RAIL_OPEN_CLASS, open);
    var opener = doc.getElementById("rail-open");
    if (opener) opener.setAttribute("aria-expanded", String(open));
    if (open === wasOpen) return open;
    var target = open ? firstRailLink(doc) : opener;
    if (target && target.focus) target.focus();
    return open;
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
    if (pageEl) {
      pageEl.textContent = label;
      // 110 §A: below 1100px the group is hidden and the leaf truncates, so the
      // element carries the full path as its title — the same thing the server
      // rendered at first paint (ShellRenderTest keeps the two tables agreeing).
      pageEl.setAttribute("title", group ? group + " / " + label : label);
    }
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

    /* 103 §B: the boosted swap is decided HERE, so the entrance is armed here
       too — htmx's afterSwap event carries the swap's own info, not the
       response's, and cannot be asked whether the request was boosted. */
    var entranceDue = false;
    doc.body.addEventListener("htmx:beforeSwap", function (evt) {
      if (applyBoostSwap(evt.detail, doc.getElementById(MAIN_ID))) entranceDue = true;
    });
    /* On afterSETTLE, not afterSwap. Measured (ShellFeelBrowserTest's trace): at
       afterSwap the swapped element is still mid-ceremony — `htmx-swapping
       htmx-added htmx-settling` — and htmx's settle step replaces it again a
       frame later, so the entrance started and was CANCELLED 15ms in
       (`animationstart@771 … animationcancel@788`). The reader saw a flicker,
       every assertion about the class still passed, and only the animation
       events said so. Settle is when the element is final. */
    doc.body.addEventListener("htmx:afterSettle", function () {
      if (!entranceDue) return;
      entranceDue = false;
      markEntrance(window, doc.getElementById(MAIN_ID));
      /* 110 §A: a BOOSTED navigation closes the drawer — the same one-shot that
         decides the entrance decides the close, so a background partial settling
         while the drawer is open cannot slam it shut. setRailOpen's no-change
         early return keeps this a no-op at desktop widths. */
      setRailOpen(doc, false);
    });

    /* 085 §D — every request shows the bar; the originating button goes busy;
       a slow swap's target gets the delayed skeleton. The pair is
       beforeRequest/afterRequest: afterRequest is htmx 2.0.10's ONE terminal
       event that fires on every outcome — onload (any status), onerror,
       onabort and ontimeout all pass through it, while afterSettle never
       fires for an aborted or non-swapping request (verified against the
       vendored dist: xhr.onabort → afterRequest + sendAbort; xhr.onerror →
       afterRequest + sendError; only the swap path reaches afterSettle). One
       decrement point, so the bar cannot stick on — a stuck-on bar is worse
       than none. */
    var busy = createBusyTracker(window, SKELETON_DELAY_MS);
    doc.body.addEventListener("htmx:beforeRequest", function (evt) {
      if (!evt.detail) return;
      busy.begin(doc, evt.detail.elt, evt.detail.target);
    });
    doc.body.addEventListener("htmx:afterRequest", function (evt) {
      if (!evt.detail) return;
      busy.end(doc, evt.detail.elt, evt.detail.target);
    });

    /* 103 §A — the click's own acknowledgement. The pend is on the CLICK, not on
       htmx:beforeRequest: the whole point is that it lands before any request
       exists, in the same frame as the press. Clearing is deliberately spread
       over three kinds of ending, because only their union is total:

         - htmx:afterSettle — the swap arrived and the new screen is on screen
           (the normal path; afterRequest would clear it one frame too early,
           while the outgoing screen is still painted);
         - every htmx error/abort event — afterSettle NEVER fires for a response
           error, a network error, a timeout or an abort (085 §D's finding), and
           a dimmed link that stays dimmed is worse than no feedback at all;
         - pageshow — a back/forward restore from the bfcache re-paints the DOM
           exactly as it was left, `is-pending` included, with no htmx event of
           any kind to clear it.

       A form's pending rides on the SUBMIT BUTTON (the control the reader
       pressed); htmx reports the <form> as the requesting element, so the busy
       tracker never reaches it. */
    var pending = createPendingTracker(window, PENDING_DELAY_MS);
    doc.body.addEventListener("click", function (evt) {
      var link = evt.target.closest && evt.target.closest("a[href]");
      if (!link) return;
      var scope = boostedNavScope(link);
      if (scope) pending.begin(doc, link, scope);
    });
    doc.body.addEventListener("htmx:beforeRequest", function (evt) {
      var elt = evt.detail && evt.detail.elt;
      if (!elt || elt.tagName !== "FORM") return;
      var control = submitControl(elt);
      if (control) pending.begin(doc, control, elt);
    });
    var clearPending = function () {
      pending.clear(doc);
    };
    doc.body.addEventListener("htmx:afterSettle", clearPending);
    var terminal = ["htmx:responseError", "htmx:sendError", "htmx:timeout", "htmx:swapError", "htmx:sendAbort"];
    for (var t = 0; t < terminal.length; t++) doc.body.addEventListener(terminal[t], clearPending);
    window.addEventListener("pageshow", clearPending);

    var resync = function () {
      syncNavActive(doc, window.location.pathname);
      syncCrumbs(doc, window.location.pathname);
    };
    doc.body.addEventListener("htmx:pushedIntoHistory", resync);
    // afterSettle also covers history restores (back/forward), which do not push.
    doc.body.addEventListener("htmx:afterSettle", resync);

    /* 079 §A — the rail toggle. One listener on body, so it survives every swap
       (the rail itself is outside #app-main and is never replaced, but a
       document-level listener is the contract this file already keeps). 110 §A:
       the toggle targets the VISUAL state — in the classless default band the
       rail already reads collapsed, so the first click must EXPAND (writing the
       user's rail-expanded/"0" choice), not collapse an already-collapsed rail. */
    doc.body.addEventListener("click", function (evt) {
      var toggle = evt.target.closest && evt.target.closest("#rail-collapse");
      if (!toggle) return;
      setRailCollapsed(doc, !railVisuallyCollapsed(doc, window), window.localStorage);
    });

    /* 110 §A — the phone drawer. Open on the topbar's opener, close on the close
       button, on the scrim, on Escape, and on any boosted navigation (the
       afterSettle wiring above). The opener is display:none above 768px by CSS,
       so these listeners are inert at desktop widths by construction. */
    doc.body.addEventListener("click", function (evt) {
      if (evt.target.closest && evt.target.closest("#rail-open")) {
        setRailOpen(doc, true);
        return;
      }
      if (evt.target.closest && evt.target.closest("#rail-close")) {
        setRailOpen(doc, false);
        return;
      }
      if (evt.target.closest && evt.target.closest(".app-rail-backdrop")) {
        setRailOpen(doc, false);
      }
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
      /* 110 §A: Escape closes the drawer first — it is the top layer when open. */
      if (evt.key === "Escape" && railOpen(doc)) {
        setRailOpen(doc, false);
        return;
      }
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
    // brings the button's aria-expanded into line with the VISUAL state on the
    // first paint — and, deliberately, writes NO class: in the 768–1099 default
    // band the absence of a class IS the state, and the document must not
    // overwrite a decision the browser owns. (110 §A.)
    syncCollapseButton(doc, railVisuallyCollapsed(doc, window));
    // Same one-line sync for the drawer: closed on every load, opener aria-expanded
    // matching. (No state change, so setRailOpen's focus choreography cannot fire.)
    setRailOpen(doc, railOpen(doc));
  }

  var api = {
    isActiveSection: isActiveSection,
    syncNavActive: syncNavActive,
    applyBoostSwap: applyBoostSwap,
    showProgress: showProgress,
    hideProgress: hideProgress,
    createBusyTracker: createBusyTracker,
    createPendingTracker: createPendingTracker,
    boostedNavScope: boostedNavScope,
    submitControl: submitControl,
    showStatusPill: showStatusPill,
    hideStatusPill: hideStatusPill,
    markEntrance: markEntrance,
    PENDING_DELAY_MS: PENDING_DELAY_MS,
    PENDING_CLASS: PENDING_CLASS,
    PENDING_SCOPE_CLASS: PENDING_SCOPE_CLASS,
    PILL_ID: PILL_ID,
    PILL_ON_CLASS: PILL_ON_CLASS,
    ENTER_CLASS: ENTER_CLASS,
    ENTER_FALLBACK_MS: ENTER_FALLBACK_MS,
    SKELETON_DELAY_MS: SKELETON_DELAY_MS,
    SKELETON_CLASS: SKELETON_CLASS,
    BUSY_CLASS: BUSY_CLASS,
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
    EXPANDED_CLASS: EXPANDED_CLASS,
    RAIL_OPEN_CLASS: RAIL_OPEN_CLASS,
    setRailOpen: setRailOpen,
    railOpen: railOpen,
    railVisuallyCollapsed: railVisuallyCollapsed,
    syncCollapseButton: syncCollapseButton,
    DEFAULT_COLLAPSED_QUERY: DEFAULT_COLLAPSED_QUERY,
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
