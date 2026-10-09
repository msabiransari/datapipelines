// 058 — the explorer layer: selection, focus and keyboard. Since 067 it serves BOTH
// explorers (templates and pipelines): one tree presentation, one keyboard contract, one
// file. Since #350 it is the app's ONE tree engine, serving every tree through a scoped
// CONTEXT rather than one pane:
//
//   - the PAGE context: an element carrying `data-explorer-pane` (the templates explorer —
//     the pipelines page has no tree since #350). A row SELECTS: the highlight moves and the
//     detail pane beside it loads (debounced for keyboard moves). Enter opens the editor.
//   - the NAV context: an element carrying `data-nav-tree` (the global sidebar's Pipelines
//     and Dashboards trees, workspace spec §5 / D8 — Templates is the declared next use). A
//     row NAVIGATES, so the arrow keys move FOCUS only (arrow-key "selection" would navigate
//     on every keystroke — L3b's reason for leaving the dashboards tree unwired, now solved by
//     the context rather than by opting out). Enter is the link's own activation.
//     aria-selected/aria-current there mark the CURRENT PAGE's leaf, owned by js/tree
//     (sidebar.mjs reads the hook; render.mjs writes the attributes).
//
// The 047 tree needed no JS of its own — expansion is <details>/<summary> and htmx, and
// that is STILL all the expansion needs. What this file adds is client state the server
// cannot render: the selection (page context), the roving tabindex and the keyboard.
//
// Keyboard (the owner's spec: "just like Windows file explorer"), both contexts:
//   ArrowUp/ArrowDown  move focus among the visible rows (page: selection follows)
//   ArrowRight         expand a collapsed folder; otherwise move down
//   ArrowLeft          collapse an expanded folder; otherwise move to the parent folder
//   Enter              page: open the selected row in the editor; nav: follow the link;
//                      a folder toggles in both
//   Home/End           first/last visible row
//
// Selection and load are deliberately separate in the page context: the highlight moves on
// every keystroke, the DETAIL request is debounced (rapid arrows would otherwise fire a
// request per row — hx-sync="replace" makes the last one win, but not firing twenty is
// cheaper still). A mouse click loads immediately, because the click itself is the button's
// own hx-get.
//
// ARIA: the server renders role=tree/treeitem/group and role=listbox/option; this script
// owns aria-expanded and the roving tabindex (one row tabbable per context — APG tree
// pattern) and, in the page context, aria-selected; it re-initialises after every htmx swap
// that lands in a context, so rows that arrive in a swap join the same contract.
//
// #350 — LOADED ONCE. The layout loads this file in its footer (the sidebar needs it on every
// page); an explorer page's own tag still rides inside #app-main and re-executes on a boosted
// visit. That re-execution is now a RE-INIT of the one module, never a second closure: the
// old per-closure `docWired` flag could not see a previous closure's listeners, so every
// boosted visit to an explorer stacked another document-level pair.
(function () {
  "use strict";

  if (typeof window !== "undefined" && window.templateExplorer && window.templateExplorer.loaded) {
    window.templateExplorer.init();
    return;
  }

  var PANE_SELECTOR = "[data-explorer-pane]";
  var NAV_SELECTOR = "[data-nav-tree]";
  var CONTEXT_SELECTOR = PANE_SELECTOR + ", " + NAV_SELECTOR;
  var ITEM_SELECTOR = '[role="treeitem"], [role="option"]';
  var SELECTABLE = ".tpl-leaf, .tpl-result";

  // ------------------------------------------------------------- pure decisions
  // Kept DOM-free so editorJsTest can pin them: the navigation POLICY, not the wiring.

  function nextIndex(count, current, delta) {
    if (count <= 0) return -1;
    var next = current + (delta < 0 ? -1 : 1);
    if (next < 0) return 0;
    if (next > count - 1) return count - 1;
    return next;
  }

  // ArrowRight: only a COLLAPSED folder opens; everything else moves down a row.
  function arrowRightOpens(kind, expanded) {
    return kind === "folder" && !expanded;
  }

  // ArrowLeft: only an EXPANDED folder closes; a leaf or collapsed child goes to its parent.
  function arrowLeftCloses(kind, expanded) {
    return kind === "folder" && expanded;
  }

  // Only leaves and search results carry a detail pane load; folders do not.
  function loadsDetail(kind) {
    return kind === "leaf" || kind === "result";
  }

  // #350: does a keyboard MOVE onto a row change the selection (and so load its detail)?
  // Only in the page context — in the sidebar a row is a link, and moving onto it must not
  // navigate.
  function selectionFollowsFocus(context, kind) {
    return context === "pane" && loadsDetail(kind);
  }

  // #350: what Enter does on a row, per context. "toggle" a folder; "open-editor" (page:
  // the row's data-editor-url); "follow" (nav: the browser's own link activation, so the
  // keydown is left alone); "none" for anything else.
  function enterAction(context, kind) {
    if (kind === "folder") return "toggle";
    if (loadsDetail(kind)) return context === "nav" ? "follow" : "open-editor";
    return "none";
  }

  // ------------------------------------------------------------- DOM glue

  function contextOf(el) {
    return el && el.closest ? el.closest(CONTEXT_SELECTOR) : null;
  }

  function contextName(root) {
    return root && root.hasAttribute && root.hasAttribute("data-nav-tree") ? "nav" : "pane";
  }

  function kindOf(el) {
    if (el.classList.contains("tpl-leaf")) return "leaf";
    if (el.classList.contains("tpl-result")) return "result";
    if (el.matches("summary.tpl-summary") && el.closest("details.tpl-folder")) return "folder";
    return null;
  }

  // A row is visible when every ancestor <details> above its own is open. Closed levels
  // simply do not render their children, so this is a walk, not a style read.
  function visible(item, root) {
    var scope = item.tagName === "SUMMARY" ? item.parentElement : item;
    for (var n = scope && scope.parentElement; n; n = n.parentElement) {
      if (n === root) return true;
      if (n.tagName === "DETAILS" && !n.open) return false;
    }
    return false;
  }

  function items(root) {
    if (!root) return [];
    return Array.prototype.filter.call(root.querySelectorAll(ITEM_SELECTOR), function (item) {
      return visible(item, root);
    });
  }

  function select(item, load, root) {
    if (!root) return;
    Array.prototype.forEach.call(root.querySelectorAll('[aria-selected="true"]'), function (s) {
      s.setAttribute("aria-selected", "false");
    });
    item.setAttribute("aria-selected", "true");
    maintainTabindex(root);
    if (load && item.matches(SELECTABLE)) scheduleLoad(item);
  }

  // The debounced detail load for keyboard-driven selection (page context). A real click
  // skips this — the button's own hx-get is already firing.
  var loadTimer = null;

  function scheduleLoad(item) {
    if (loadTimer) clearTimeout(loadTimer);
    loadTimer = setTimeout(function () {
      loadTimer = null;
      // A debounced load that fires after a boosted swap detached the row is a
      // ghost request for a pane that no longer exists (076 §B).
      if (item.isConnected === false) return;
      item.click();
    }, 150);
  }

  // One row tabbable per context (APG roving tabindex): the focused row if it is one of
  // them, else the selected one, else the first visible one. Rows render with NO tabindex
  // attribute, so a browser without this script still tabs through every row.
  function maintainTabindex(root) {
    var list = items(root);
    var focused = typeof document !== "undefined" ? document.activeElement : null;
    var chosen = null;
    for (var i = 0; i < list.length; i++) {
      if (list[i] === focused) { chosen = list[i]; break; }
    }
    if (!chosen) {
      for (var j = 0; j < list.length; j++) {
        if (list[j].getAttribute("aria-selected") === "true") { chosen = list[j]; break; }
      }
    }
    if (!chosen && list.length > 0) chosen = list[0];
    list.forEach(function (item) {
      item.tabIndex = item === chosen ? 0 : -1;
    });
  }

  function parentFolderOf(item, root) {
    var scope = item.tagName === "SUMMARY" ? item.parentElement : item;
    for (var n = scope && scope.parentElement; n; n = n.parentElement) {
      if (n === root) return null;
      if (n.tagName === "DETAILS") return n.querySelector(":scope > summary") || null;
    }
    return null;
  }

  function moveTo(list, index, root) {
    if (index < 0 || index >= list.length) return;
    var item = list[index];
    item.focus();
    var context = contextName(root);
    if (selectionFollowsFocus(context, kindOf(item))) select(item, true, root);
    else if (context === "pane") select(item, false, root);
    else maintainTabindex(root);
  }

  function focusOnly(item, root) {
    item.focus();
    if (contextName(root) === "pane") select(item, false, root);
    else maintainTabindex(root);
  }

  function onKeydown(event) {
    var target = event.target;
    if (!target.closest) return;
    var root = event.currentTarget;
    var item = target.closest(ITEM_SELECTOR);
    if (!item || !root.contains(item)) return;

    var list = items(root);
    var kind = kindOf(item);
    var at = list.indexOf(item);

    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        moveTo(list, nextIndex(list.length, at, 1), root);
        break;
      case "ArrowUp":
        event.preventDefault();
        moveTo(list, nextIndex(list.length, at, -1), root);
        break;
      case "ArrowRight":
        event.preventDefault();
        if (arrowRightOpens(kind, isExpanded(item))) item.click();
        else moveTo(list, nextIndex(list.length, at, 1), root);
        break;
      case "ArrowLeft":
        event.preventDefault();
        if (arrowLeftCloses(kind, isExpanded(item))) {
          item.click();
        } else {
          var parent = parentFolderOf(item, root);
          if (parent) focusOnly(parent, root);
        }
        break;
      case "Enter": {
        var action = enterAction(contextName(root), kind);
        if (action === "follow" || action === "none") break; // the link's own Enter
        event.preventDefault();
        if (action === "open-editor") window.location.assign(item.getAttribute("data-editor-url"));
        else item.click();
        break;
      }
      case "Home":
        event.preventDefault();
        moveTo(list, nextIndex(list.length, -1, 1), root);
        break;
      case "End":
        event.preventDefault();
        moveTo(list, list.length - 1, root);
        break;
      default:
        break;
    }
  }

  function isExpanded(item) {
    var details = item.closest("details.tpl-folder");
    return !!details && details.open;
  }

  function onClick(event) {
    if (!event.target.closest) return;
    var root = event.currentTarget;
    var item = event.target.closest(ITEM_SELECTOR);
    if (!item || !root.contains(item)) return;
    if (contextName(root) === "pane") select(item, false, root);
    else maintainTabindex(root);
  }

  // <details> toggle does not bubble; the capture phase still sees it, so one listener
  // keeps every folder summary's aria-expanded truthful without per-node handlers.
  // Closing a folder whose CHILD was selected moves the page selection to the folder: the
  // child is now invisible (closed levels do not render), and a hidden selection is both
  // unreadable and unannounceable. In the sidebar the selection is the current PAGE and
  // stays put; only the tab stop moves to the folder.
  function onToggle(event) {
    var t = event.target;
    if (!t || t.tagName !== "DETAILS" || !t.classList.contains("tpl-folder")) return;
    var s = t.querySelector(":scope > summary");
    if (s) s.setAttribute("aria-expanded", t.open ? "true" : "false");
    var root = contextOf(t);
    if (!root) return;
    if (!t.open && s && t.querySelector('[aria-selected="true"]')) {
      if (contextName(root) === "pane") select(s, false, root);
    }
    maintainTabindex(root);
  }

  // Rows that arrive in an htmx swap (a level, a search, a filter refresh) join the
  // same tabindex contract; a context that arrives in a swap (an explorer page) is wired.
  function onAfterSwap(event) {
    wireAll();
    var root = event.target ? contextOf(event.target) : null;
    if (root) maintainTabindex(root);
  }

  function wire(root) {
    if (!root || root.__tplxWired) return;
    root.__tplxWired = true;
    root.addEventListener("keydown", onKeydown);
    root.addEventListener("click", onClick);
  }

  function wireAll() {
    if (typeof document === "undefined" || !document.querySelectorAll) return;
    Array.prototype.forEach.call(document.querySelectorAll(CONTEXT_SELECTOR), function (root) {
      wire(root);
      maintainTabindex(root);
    });
  }

  // The two DOCUMENT-level listeners go on exactly once per document: this module runs once
  // (the guard at the top turns a re-execution into a call of init), and init is idempotent.
  var docWired = false;

  function init() {
    if (!docWired && typeof document !== "undefined" && document.addEventListener) {
      docWired = true;
      document.addEventListener("toggle", onToggle, true);
      document.addEventListener("htmx:afterSwap", onAfterSwap);
    }
    wireAll();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  // Exposed for editorJsTest (the init.js pattern): the navigation policy, DOM-free, plus
  // init itself so a re-init test (076 §B) and the schedules explorer can drive it. The
  // context helpers (contextOf, items, maintainTabindex) were nav-tree.js's (#350); since
  // #491 removed it no shipped script reads them (#466 tracks the remaining nav residue).
  window.templateExplorer = {
    loaded: true,
    nextIndex: nextIndex,
    arrowRightOpens: arrowRightOpens,
    arrowLeftCloses: arrowLeftCloses,
    loadsDetail: loadsDetail,
    selectionFollowsFocus: selectionFollowsFocus,
    enterAction: enterAction,
    contextOf: contextOf,
    items: items,
    maintainTabindex: maintainTabindex,
    init: init,
  };
})();
