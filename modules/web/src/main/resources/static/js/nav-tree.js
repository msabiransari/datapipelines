/*
 * #350 (workspace spec §5, D7/D8) — the global sidebar's NAVIGATING TREES.
 *
 * The layout renders one branch per artifact family that browses as a folder tree
 * (`[data-nav-branch]`: Pipelines, Dashboards; Templates is the declared next use): the item
 * link, a toggle beside it, and a panel holding an optional search box and a lazy,
 * server-backed tree (`[data-nav-tree]`). The rows, the expansion (<details> + htmx, one
 * prefix level per request) and the keyboard (template-explorer.js's nav context) are the
 * existing machinery. This file owns what the SIDEBAR adds around them, and only that:
 *
 * 1. THE LIFECYCLE. The toggle opens/closes the panel; the first open fetches the ROOT level
 *    into the placeholder (`data-nav-root-url`). Nothing is fetched for a tree nobody opens.
 *
 * 2. THE ADMISSION GUARD. Every request whose target sits in a tree is stamped with the
 *    tree's live GENERATION; a swap is admitted only if (a) its generation is still current —
 *    a search typed or cleared, or a reset, makes every older folder/search response stale —
 *    and (b) its `DP-Nav-Stamp` (`<workspace>|<lens>`, PipelinePartialController) matches the
 *    tree: the workspace the document was rendered for, and the lens the tree's rows were
 *    rendered under. A stale response is dropped without a sound (it is the past, not a
 *    failure); a foreign workspace is refused with a VISIBLE notice and never joins the rows;
 *    a changed lens resets the tree under the new lens, so lensed and unlensed rows never mix.
 *
 * 3. FAILURE AND RETRY. A refused/failed level (4xx/5xx, network) leaves a visible row with a
 *    Retry in place of its spinner — the request's own URL, re-issued into the same target.
 *    Built with DOM text sinks, never markup strings. No toast: the failure is where it happened.
 *
 * 4. THE WIDTH. Opening, fetching, searching, folding and font/viewport changes re-measure the
 *    VISIBLE rows' max-content width (closed levels render nothing, so hidden descendants cost
 *    nothing) and write ONE custom property, `--app-rail-tree-fit`, on <html> (the splitter's
 *    shape — 104 §A). app.css clamps it between `--app-rail-tree-min` and `--app-rail-tree-max`
 *    and only while a tree is open (`rail-tree-open`), the rail is not icon-collapsed and the
 *    width band is not the phone drawer — so an explicit collapse always wins over a late
 *    measurement, by selector, not by a race. Past the maximum the tree's own region scrolls
 *    sideways; the rest of the rail and the page do not move. No ResizeObserver: the measured
 *    content does not depend on the rail's width, so there is no loop to break.
 *
 * 5. THE STATE. Per workspace AND family, in localStorage: open, the open folders' PATHS,
 *    scroll offsets, and (rail key) the open flag + fit for rail.js's pre-paint. Never a row,
 *    a count, a query or anything from a pipeline body; another workspace's key is never read.
 *    A full-document load (a workspace graph entry) restores it incrementally: the root, then
 *    each remembered folder whose parent is open, one level per request — plus the folders
 *    above the CURRENT page's leaf (`[data-nav-current]` in #app-main, or the item's own path
 *    prefix), which is marked aria-current and walked to through its level's pager (bounded)
 *    when it is not on the first page. Boosted swaps and history restores keep the rail (it is
 *    outside #app-main) and only re-mark the current leaf.
 *
 * Pure halves are exported for `node --test` (modules/web/src/test/js/nav-tree.test.mjs).
 */
(function () {
  "use strict";

  var STATE_PREFIX = "dp-nav:";
  var RAIL_PREFIX = "dp-nav-rail:";
  var STAMP_HEADER = "DP-Nav-Stamp";
  var TREE_OPEN_CLASS = "rail-tree-open";
  var FIT_PROPERTY = "--app-rail-tree-fit";
  var MEASURING_CLASS = "is-measuring";
  /* Storage is input: a hand-edited or hostile entry must stay bounded and shapeless. */
  var MAX_FOLDERS = 200;
  var MAX_PATH = 512;
  var MAX_FIT = 2000;
  /* A paged level is walked page by page to reach the current leaf — at most this many pages
     (25 rows each), so a reveal can never become a crawl of a huge folder. */
  var MAX_PAGE_WALK = 20;

  // ------------------------------------------------------------------ pure halves

  function stateKey(family, workspace) {
    return STATE_PREFIX + family + ":" + workspace;
  }

  function railKey(workspace) {
    return RAIL_PREFIX + workspace;
  }

  function finiteOr(value, fallback) {
    return typeof value === "number" && isFinite(value) && value >= 0 ? value : fallback;
  }

  /* Whatever storage holds, the state that comes out is the closed shape below. */
  function parseState(raw) {
    var empty = { open: false, folders: [], top: 0, left: 0 };
    if (typeof raw !== "string" || raw === "") return empty;
    var data;
    try {
      data = JSON.parse(raw);
    } catch (e) {
      return empty;
    }
    if (!data || typeof data !== "object") return empty;
    var folders = Array.isArray(data.folders)
      ? data.folders.filter(function (f) {
        return typeof f === "string" && f.length > 0 && f.length <= MAX_PATH;
      }).slice(0, MAX_FOLDERS)
      : [];
    return { open: data.open === true, folders: folders, top: finiteOr(data.top, 0), left: finiteOr(data.left, 0) };
  }

  function serializeState(state) {
    return JSON.stringify({
      open: !!state.open,
      folders: (state.folders || []).slice(0, MAX_FOLDERS),
      top: Math.round(finiteOr(state.top, 0)),
      left: Math.round(finiteOr(state.left, 0)),
    });
  }

  function parseRail(raw) {
    var none = { open: false, fit: 0 };
    if (typeof raw !== "string" || raw === "") return none;
    try {
      var data = JSON.parse(raw);
      if (!data || typeof data !== "object") return none;
      var fit = finiteOr(data.fit, 0);
      return { open: data.open === true, fit: fit > MAX_FIT ? 0 : Math.round(fit) };
    } catch (e) {
      return none;
    }
  }

  /* "a/b/c" -> ["a", "a/b"]: the folders a leaf at that path sits under, outermost first. */
  function ancestorsOf(path) {
    if (typeof path !== "string" || path === "") return [];
    var parts = path.split("/");
    var out = [];
    for (var i = 1; i < parts.length; i++) out.push(parts.slice(0, i).join("/"));
    return out;
  }

  /* Closing a folder forgets it AND everything under it: re-opening it later starts closed
     inside, which is what the reader last saw. */
  function forgetFolder(folders, path) {
    return folders.filter(function (f) {
      return f !== path && f.indexOf(path + "/") !== 0;
    });
  }

  function rememberFolder(folders, path) {
    if (folders.indexOf(path) !== -1) return folders;
    var next = folders.concat([path]);
    return next.length > MAX_FOLDERS ? next.slice(next.length - MAX_FOLDERS) : next;
  }

  function parseStamp(header) {
    if (typeof header !== "string" || header.indexOf("|") === -1) return null;
    var at = header.lastIndexOf("|");
    return { workspace: header.slice(0, at), lens: header.slice(at + 1) };
  }

  /* The admission decision for one tree swap. `stamp` null = the family's fragments carry no
     stamp (the dashboards route): the generation still guards it. */
  function admit(requestGen, treeGen, stamp, treeWorkspace, treeLens) {
    if (requestGen !== treeGen) return "stale";
    if (!stamp) return "admit";
    if (treeWorkspace && stamp.workspace !== treeWorkspace) return "foreign-workspace";
    if (treeLens && stamp.lens !== treeLens) return "lens-changed";
    return "admit";
  }

  /* The rail width that shows `contentWidth` of rows whole: the content plus everything the
     rail draws around the tree region (its padding, the panel's indent, a vertical scrollbar).
     The bounds are CSS's (the tokens), so this never clamps to a number JS would repeat. */
  function fitWidth(contentWidth, railWidth, regionClientWidth) {
    var chrome = Math.max(0, railWidth - regionClientWidth);
    var fit = Math.ceil(contentWidth + chrome);
    return fit > 0 && fit <= MAX_FIT ? fit : 0;
  }

  /* "/pipelines/<id>/..." under the item link "/pipelines" -> "<id>"; anything else -> null. */
  function currentIdFor(sectionHref, pathname) {
    if (!sectionHref || !pathname || pathname.indexOf(sectionHref + "/") !== 0) return null;
    var rest = pathname.slice(sectionHref.length + 1).split("/")[0];
    return rest ? decodeURIComponent(rest) : null;
  }

  // ------------------------------------------------------------------ DOM glue

  var trees = [];

  function storage() {
    try {
      return window.localStorage;
    } catch (e) {
      return null; // privacy modes throw on the getter itself
    }
  }

  function readKey(key) {
    var s = storage();
    if (!s) return null;
    try {
      return s.getItem(key);
    } catch (e) {
      return null;
    }
  }

  function writeKey(key, value) {
    var s = storage();
    if (!s) return;
    try {
      s.setItem(key, value);
    } catch (e) {
      /* A preference that cannot be saved is not a reason to break the tree. */
    }
  }

  function treeFor(el) {
    for (var i = 0; i < trees.length; i++) {
      if (el && trees[i].panel.contains(el)) return trees[i];
    }
    return null;
  }

  function byFamily(family) {
    for (var i = 0; i < trees.length; i++) if (trees[i].family === family) return trees[i];
    return null;
  }

  function scroller(t) {
    return t.panel.querySelector("[data-nav-tree-scroll]");
  }

  function searchBox(t) {
    return t.panel.querySelector("[data-nav-tree-search]");
  }

  function rootEl(t) {
    return document.getElementById(t.rootId);
  }

  function searching(t) {
    var box = searchBox(t);
    return !!(box && box.value.trim() !== "");
  }

  function folderPathOf(summary) {
    var label = summary.querySelector(".tpl-label[title]");
    return label ? label.getAttribute("title") : null;
  }

  function persist(t) {
    var region = scroller(t);
    writeKey(
      stateKey(t.family, t.workspace),
      serializeState({
        open: t.open,
        folders: t.folders,
        top: region ? region.scrollTop : 0,
        left: region ? region.scrollLeft : 0,
      }),
    );
  }

  function persistRail() {
    if (!trees.length) return;
    var anyOpen = trees.some(function (t) { return t.open; });
    writeKey(railKey(trees[0].workspace), JSON.stringify({ open: anyOpen, fit: lastFit }));
  }

  function syncRailClass() {
    var anyOpen = trees.some(function (t) { return t.open; });
    document.documentElement.classList.toggle(TREE_OPEN_CLASS, anyOpen);
  }

  // ---- 4. the width

  var lastFit = 0;
  var fitQueued = false;

  function measure(t) {
    var region = scroller(t);
    var root = rootEl(t);
    if (!t.open || !region || !root || !root.getClientRects().length) return 0;
    var rail = t.panel.closest(".app-rail");
    if (!rail) return 0;
    t.panel.classList.add(MEASURING_CLASS);
    var content = root.getBoundingClientRect().width;
    t.panel.classList.remove(MEASURING_CLASS);
    return fitWidth(content, rail.getBoundingClientRect().width, region.clientWidth);
  }

  function fitNow() {
    fitQueued = false;
    var fit = 0;
    trees.forEach(function (t) {
      fit = Math.max(fit, measure(t));
    });
    if (fit > 0 && fit !== lastFit) {
      lastFit = fit;
      document.documentElement.style.setProperty(FIT_PROPERTY, fit + "px");
    }
    persistRail();
  }

  function scheduleFit() {
    if (fitQueued) return;
    fitQueued = true;
    window.requestAnimationFrame(fitNow);
  }

  // ---- 1. the lifecycle

  function setOpen(t, open) {
    t.open = open;
    t.panel.hidden = !open;
    t.toggle.setAttribute("aria-expanded", String(open));
    syncRailClass();
    if (open && !t.loaded) loadRoot(t);
    if (!open) t.restoring = false;
    persist(t);
    scheduleFit();
  }

  function loadRoot(t) {
    t.loaded = true;
    window.htmx.ajax("GET", t.rootUrl, { source: t.toggle, target: "#" + t.rootId, swap: "outerHTML" });
  }

  /* A changed lens (or a refused foreign answer) starts the tree over: a fresh generation,
     a placeholder in place of every row, and — for a lens change — the root re-fetched under
     the view the server now applies. The remembered folders stay remembered, so the restore
     re-opens the ones the new lens still admits. */
  function resetTree(t, reload) {
    t.gen += 1;
    t.lens = null;
    t.loaded = false;
    t.restoring = true;
    var root = rootEl(t);
    if (root) {
      var placeholder = document.createElement("div");
      placeholder.id = t.rootId;
      placeholder.className = "tpl-level tpl-level-pending";
      placeholder.setAttribute("data-nav-tree-root", "");
      root.replaceWith(placeholder);
    }
    if (reload && t.open) loadRoot(t);
  }

  // ---- 3. failure and retry

  function showFailure(target, message, url, reload) {
    if (!target || !target.isConnected) return;
    var row = document.createElement("p");
    row.className = "app-nav-tree-error";
    row.setAttribute("role", "status");
    var text = document.createElement("span");
    text.textContent = message;
    row.appendChild(text);
    var button = document.createElement("button");
    button.type = "button";
    button.className = "ds-button ds-button-ghost ds-button-sm";
    button.textContent = reload ? "Reload page" : "Retry";
    if (reload) button.setAttribute("data-nav-tree-reload", "");
    else {
      button.setAttribute("data-nav-tree-retry", "");
      button.setAttribute("data-nav-retry-url", url || "");
    }
    row.appendChild(button);
    target.replaceChildren(row);
    target.setAttribute("data-nav-failed", "");
  }

  function retry(button) {
    var target = button.closest("[data-nav-failed]");
    var url = button.getAttribute("data-nav-retry-url");
    if (!target || !url || !window.htmx) return;
    target.removeAttribute("data-nav-failed");
    target.replaceChildren();
    var spinner = document.createElement("span");
    spinner.className = "ds-spinner";
    spinner.setAttribute("aria-hidden", "true");
    target.appendChild(spinner);
    window.htmx.ajax("GET", url, { source: target, target: target, swap: "outerHTML" });
  }

  // ---- 5. restore, reveal and the current leaf

  function summaries(t) {
    return Array.prototype.slice.call(t.panel.querySelectorAll("details.tpl-folder > summary.tpl-summary"));
  }

  function summaryFor(t, path) {
    var all = summaries(t);
    for (var i = 0; i < all.length; i++) if (folderPathOf(all[i]) === path) return all[i];
    return null;
  }

  function currentHook(t) {
    var hooks = document.querySelectorAll("#app-main [data-nav-current]");
    for (var i = 0; i < hooks.length; i++) {
      if (hooks[i].getAttribute("data-nav-current") === t.family) return hooks[i];
    }
    return null;
  }

  function currentLeaf(t) {
    var hook = currentHook(t);
    var id = hook ? hook.getAttribute("data-nav-current-id") : currentIdFor(t.sectionHref, window.location.pathname);
    var path = hook ? hook.getAttribute("data-nav-current-path") : null;
    return id ? { id: id, path: path } : null;
  }

  /* Open every remembered folder (and every folder above the current leaf) whose summary is
     on screen and closed — one click each, which is the summary's own `click once` fetch the
     first time and a plain open after. Called after each swap lands, so a deep path unfolds
     one level per request and nothing unrelated is fetched. */
  function continueRestore(t) {
    if (!t.open || !t.restoring || searching(t)) return false;
    var cur = currentLeaf(t);
    var wanted = t.folders.concat(cur && cur.path ? ancestorsOf(cur.path) : []);
    var pending = false;
    summaries(t).forEach(function (summary) {
      var details = summary.parentElement;
      var path = folderPathOf(summary);
      if (!details.open && path && wanted.indexOf(path) !== -1) {
        summary.click();
        pending = true;
      } else if (details.open && details.querySelector(":scope > .tpl-level-pending")) {
        pending = true; // its level is still on the way
      }
    });
    if (!pending && walkToCurrent(t)) pending = true;
    if (!pending) finishRestore(t);
    return pending;
  }

  /* The current leaf's folder is open but the leaf is not on the page of the level that
     loaded: page forward, bounded. */
  function walkToCurrent(t) {
    var cur = currentLeaf(t);
    if (!cur || !cur.path || leafById(t, cur.id) || t.pageWalks >= MAX_PAGE_WALK) return false;
    var parents = ancestorsOf(cur.path);
    var summary = parents.length ? summaryFor(t, parents[parents.length - 1]) : null;
    var level = summary ? summary.parentElement.querySelector(":scope > .tpl-level") : null;
    if (!level || level.classList.contains("tpl-level-pending")) return false;
    var buttons = level.querySelectorAll(":scope > div > button");
    var next = buttons.length ? buttons[buttons.length - 1] : null;
    if (!next || next.disabled) return false;
    t.pageWalks += 1;
    next.click();
    return true;
  }

  function finishRestore(t) {
    if (!t.restoring) return;
    t.restoring = false;
    var region = scroller(t);
    if (region && t.savedScroll) {
      region.scrollTop = t.savedScroll.top;
      region.scrollLeft = t.savedScroll.left;
      t.savedScroll = null;
    }
    markCurrent(t, true);
  }

  function leafById(t, id) {
    var rows = t.panel.querySelectorAll(".tpl-leaf[data-leaf-id], .tpl-result[data-leaf-id]");
    for (var i = 0; i < rows.length; i++) if (rows[i].getAttribute("data-leaf-id") === id) return rows[i];
    return null;
  }

  /* aria-current/aria-selected on the leaf of the page being viewed, off everywhere else;
     optionally scrolled into the tree region's view (the region only — never the page). */
  function markCurrent(t, reveal) {
    var cur = currentLeaf(t);
    var rows = t.panel.querySelectorAll(".tpl-leaf, .tpl-result");
    var hit = null;
    for (var i = 0; i < rows.length; i++) {
      var on = !!cur && rows[i].getAttribute("data-leaf-id") === cur.id;
      if (on) hit = rows[i];
      if (on) {
        rows[i].setAttribute("aria-current", "page");
        rows[i].setAttribute("aria-selected", "true");
      } else if (rows[i].hasAttribute("aria-current") || rows[i].getAttribute("aria-selected") === "true") {
        rows[i].removeAttribute("aria-current");
        rows[i].setAttribute("aria-selected", "false");
      }
    }
    if (hit && reveal) {
      var region = scroller(t);
      if (region) {
        var r = region.getBoundingClientRect();
        var h = hit.getBoundingClientRect();
        if (h.top < r.top) region.scrollTop -= r.top - h.top;
        else if (h.bottom > r.bottom) region.scrollTop += h.bottom - r.bottom;
      }
    }
    if (window.templateExplorer) window.templateExplorer.maintainTabindex(t.panel);
  }

  // ---- the event wiring

  function onBeforeRequest(evt) {
    var d = evt.detail;
    var t = d && treeFor(d.target);
    if (!t || !d.xhr) return;
    d.xhr.__dpNavTree = t;
    d.xhr.__dpNavGen = t.gen;
  }

  function onBeforeSwap(evt) {
    var d = evt.detail;
    var t = d && d.xhr && d.xhr.__dpNavTree;
    if (!t) return;
    var stamp = parseStamp(d.xhr.getResponseHeader ? d.xhr.getResponseHeader(STAMP_HEADER) : null);
    var verdict = admit(d.xhr.__dpNavGen, t.gen, stamp, t.workspace, t.lens);
    if (verdict === "admit") {
      if (stamp && !t.lens && !d.isError) t.lens = stamp.lens;
      return;
    }
    d.shouldSwap = false;
    d.xhr.__dpNavRefused = verdict;
    if (verdict === "foreign-workspace") {
      resetTree(t, false);
      showFailure(rootEl(t), "This tab belongs to another workspace now — reload to browse it.", null, true);
    } else if (verdict === "lens-changed") {
      resetTree(t, true);
    }
  }

  function onFailure(evt) {
    var d = evt.detail;
    var t = d && d.xhr && d.xhr.__dpNavTree;
    if (!t || d.xhr.__dpNavRefused || d.xhr.__dpNavGen !== t.gen) return;
    var url = (d.pathInfo && d.pathInfo.finalRequestPath) || (d.requestConfig && d.requestConfig.path) || null;
    var status = d.xhr.status;
    var message = status === 403 || status === 401
      ? "You cannot read this level any more."
      : "This level could not be loaded.";
    showFailure(d.target, message, url, false);
    scheduleFit();
  }

  function onAfterSettle(evt) {
    var d = evt.detail || {};
    var target = d.target;
    // An outerHTML swap leaves `target` naming the REPLACED (detached) element, so the tree a
    // settle belongs to is the one its request was stamped with; a refused swap never settles.
    var t = (d.xhr && d.xhr.__dpNavTree) || (target && treeFor(target));
    if (t) {
      if (!continueRestore(t)) markCurrent(t, false);
      scheduleFit();
      return;
    }
    // A swap of the MAIN region (a boosted navigation or a history restore): the rail stayed,
    // the page beside it changed — re-mark each tree's current leaf and reveal it.
    if (target && target.id === "app-main") onPageChanged();
  }

  function onPageChanged() {
    trees.forEach(function (tree) {
      if (!tree.open) {
        markCurrent(tree, false);
        return;
      }
      tree.restoring = true;
      tree.pageWalks = 0;
      if (!continueRestore(tree)) markCurrent(tree, true);
    });
  }

  function onToggle(evt) {
    var details = evt.target;
    if (!details || details.tagName !== "DETAILS" || !details.classList.contains("tpl-folder")) return;
    var t = treeFor(details);
    if (!t) return;
    var summary = details.querySelector(":scope > summary");
    var path = summary ? folderPathOf(summary) : null;
    if (path) t.folders = details.open ? rememberFolder(t.folders, path) : forgetFolder(t.folders, path);
    persist(t);
    scheduleFit();
  }

  var scrollTimer = null;

  function onScroll(evt) {
    var t = treeFor(evt.target);
    if (!t || t.restoring) return;
    if (scrollTimer) clearTimeout(scrollTimer);
    scrollTimer = setTimeout(function () {
      scrollTimer = null;
      persist(t);
    }, 200);
  }

  function reveal(family) {
    var t = byFamily(family);
    if (!t) return;
    var shell = window.DpShell;
    if (shell) {
      var phone = window.matchMedia && window.matchMedia("(max-width: 767.98px)").matches;
      if (phone) shell.setRailOpen(document, true);
      else if (shell.railVisuallyCollapsed(document, window)) shell.setRailCollapsed(document, false, storage());
    }
    if (!t.open) setOpen(t, true);
    var box = searchBox(t);
    var focusTarget = box || (window.templateExplorer && window.templateExplorer.items(t.panel)[0]);
    if (focusTarget && focusTarget.focus) focusTarget.focus();
  }

  function onClick(evt) {
    var el = evt.target;
    if (!el.closest) return;
    var toggle = el.closest("[data-nav-tree-toggle]");
    if (toggle) {
      var t = treeFor(document.getElementById(toggle.getAttribute("aria-controls")));
      if (t) {
        t.restoring = !t.open;
        setOpen(t, !t.open);
      }
      return;
    }
    var revealer = el.closest("[data-nav-tree-reveal]");
    if (revealer) {
      reveal(revealer.getAttribute("data-nav-tree-reveal"));
      return;
    }
    var retryButton = el.closest("[data-nav-tree-retry]");
    if (retryButton) {
      retry(retryButton);
      return;
    }
    if (el.closest("[data-nav-tree-reload]")) {
      window.location.reload();
      return;
    }
    var clear = el.closest("[data-nav-tree-clear]");
    var cleared = clear && treeFor(clear);
    if (cleared) {
      var box = searchBox(cleared);
      if (box && window.htmx) {
        box.value = "";
        cleared.gen += 1;
        cleared.restoring = true;
        window.htmx.trigger(box, "search");
        box.focus();
      }
    }
  }

  function onInput(evt) {
    var box = evt.target;
    if (!box.matches || !box.matches("[data-nav-tree-search]")) return;
    var t = treeFor(box);
    if (!t) return;
    t.gen += 1; // every older folder/search answer is now the past
    t.restoring = box.value.trim() === ""; // back to browsing: re-open what was open
  }

  function onSearchKeydown(evt) {
    var box = evt.target;
    if (evt.key !== "ArrowDown" || !box.matches || !box.matches("[data-nav-tree-search]")) return;
    var t = treeFor(box);
    var first = t && window.templateExplorer ? window.templateExplorer.items(t.panel)[0] : null;
    if (first) {
      evt.preventDefault();
      first.focus();
      window.templateExplorer.maintainTabindex(t.panel);
    }
  }

  function setup(branch) {
    var panel = branch.querySelector("[data-nav-tree]");
    var toggle = branch.querySelector("[data-nav-tree-toggle]");
    var placeholder = panel && panel.querySelector("[data-nav-tree-root]");
    var link = branch.querySelector(".app-nav-link");
    if (!panel || !toggle || !placeholder || !placeholder.id) return;
    var workspace = panel.getAttribute("data-nav-workspace") || "";
    var family = panel.getAttribute("data-nav-tree");
    var state = parseState(readKey(stateKey(family, workspace)));
    var t = {
      family: family,
      workspace: workspace,
      panel: panel,
      toggle: toggle,
      rootId: placeholder.id,
      rootUrl: panel.getAttribute("data-nav-root-url"),
      sectionHref: link ? link.getAttribute("href") : null,
      open: false,
      loaded: false,
      gen: 0,
      lens: null,
      folders: state.folders,
      savedScroll: { top: state.top, left: state.left },
      restoring: true,
      pageWalks: 0,
    };
    trees.push(t);
    if (state.open) setOpen(t, true);
  }

  function init() {
    if (typeof document === "undefined" || !document.body || window.__dpNavTreeInit) return;
    if (!window.htmx) return;
    window.__dpNavTreeInit = true;
    Array.prototype.forEach.call(document.querySelectorAll("[data-nav-branch]"), setup);
    syncRailClass();
    var body = document.body;
    body.addEventListener("htmx:beforeRequest", onBeforeRequest);
    body.addEventListener("htmx:beforeSwap", onBeforeSwap);
    body.addEventListener("htmx:responseError", onFailure);
    body.addEventListener("htmx:sendError", onFailure);
    body.addEventListener("htmx:timeout", onFailure);
    body.addEventListener("htmx:afterSettle", onAfterSettle);
    body.addEventListener("htmx:historyRestore", onPageChanged);
    body.addEventListener("click", onClick);
    body.addEventListener("input", onInput);
    body.addEventListener("keydown", onSearchKeydown);
    document.addEventListener("toggle", onToggle, true);
    document.addEventListener("scroll", onScroll, true);
    window.addEventListener("resize", scheduleFit);
    window.addEventListener("pagehide", function () {
      trees.forEach(persist);
      persistRail();
    });
    if (document.fonts && document.fonts.ready) document.fonts.ready.then(scheduleFit);
    trees.forEach(function (t) { markCurrent(t, false); });
  }

  var api = {
    stateKey: stateKey,
    railKey: railKey,
    parseState: parseState,
    serializeState: serializeState,
    parseRail: parseRail,
    ancestorsOf: ancestorsOf,
    forgetFolder: forgetFolder,
    rememberFolder: rememberFolder,
    parseStamp: parseStamp,
    admit: admit,
    fitWidth: fitWidth,
    currentIdFor: currentIdFor,
    MAX_FOLDERS: MAX_FOLDERS,
    MAX_PAGE_WALK: MAX_PAGE_WALK,
    STAMP_HEADER: STAMP_HEADER,
    TREE_OPEN_CLASS: TREE_OPEN_CLASS,
    FIT_PROPERTY: FIT_PROPERTY,
    init: init,
    trees: function () { return trees.slice(); },
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") {
    window.DpNavTree = api;
    if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", init);
    else init();
  }
})();
