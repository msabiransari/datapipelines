// 058 — the templates screen's explorer layer: selection, focus and keyboard.
//
// The 047 tree needed no JS of its own — expansion is <details>/<summary> and htmx, and
// that is STILL all the expansion needs. What the two-pane layout adds is client state:
// WHICH leaf is selected (the right pane shows it), and keyboard navigation over the
// tree. Neither can be server-rendered, because neither survives an htmx swap unless
// something owns it across swaps. This file is that owner, and it owns ONLY that — every
// request still comes from server-rendered hx-* attributes.
//
// Keyboard (the owner's spec: "just like Windows file explorer"):
//   ArrowUp/ArrowDown  move selection (and focus) among the visible rows
//   ArrowRight         expand a collapsed folder; otherwise move down
//   ArrowLeft          collapse an expanded folder; otherwise move to the parent folder
//   Enter              open the selected template in the editor (toggle a folder)
//   Home/End           first/last visible row
//
// Selection and load are deliberately separate: the highlight moves on every keystroke,
// the DETAIL request is debounced (rapid arrows would otherwise fire a request per row —
// hx-sync="replace" makes the last one win, but not firing twenty is cheaper still). A
// mouse click loads immediately, because the click itself is the button's own hx-get.
//
// ARIA: the server renders role=tree/treeitem/group and role=listbox/option; this script
// owns aria-selected, aria-expanded and the roving tabindex (one row tabbable — APG tree
// pattern), initialising on load and re-initialising after every htmx swap that lands in
// the left pane, so rows that arrive in a swap join the same contract.
(function () {
  "use strict";

  var PANE_ID = "template-tree-pane";
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

  // ------------------------------------------------------------- DOM glue

  function pane() {
    return document.getElementById(PANE_ID);
  }

  function kindOf(el) {
    if (el.classList.contains("tpl-leaf")) return "leaf";
    if (el.classList.contains("tpl-result")) return "result";
    if (el.matches("summary.tpl-summary") && el.closest("details.tpl-folder")) return "folder";
    return null;
  }

  // A row is visible when every ancestor <details> above its own is open. Closed levels
  // simply do not render their children, so this is a walk, not a style read.
  function visible(item) {
    var scope = item.tagName === "SUMMARY" ? item.parentElement : item;
    for (var n = scope && scope.parentElement; n; n = n.parentElement) {
      if (n.id === PANE_ID) return true;
      if (n.tagName === "DETAILS" && !n.open) return false;
    }
    return false;
  }

  function items() {
    var p = pane();
    if (!p) return [];
    return Array.prototype.filter.call(
      p.querySelectorAll('[role="treeitem"], [role="option"]'),
      visible,
    );
  }

  function currentIndex(list) {
    var focused = document.activeElement;
    for (var i = 0; i < list.length; i++) {
      if (list[i] === focused || list[i].getAttribute("aria-selected") === "true") return i;
    }
    return -1;
  }

  function select(item, load) {
    var p = pane();
    if (!p) return;
    Array.prototype.forEach.call(
      p.querySelectorAll('[aria-selected="true"]'),
      function (s) { s.setAttribute("aria-selected", "false"); },
    );
    item.setAttribute("aria-selected", "true");
    maintainTabindex();
    if (load && item.matches(SELECTABLE)) scheduleLoad(item);
  }

  // The debounced detail load for keyboard-driven selection. A real click skips this —
  // the button's own hx-get is already firing.
  var loadTimer = null;

  function scheduleLoad(item) {
    if (loadTimer) clearTimeout(loadTimer);
    loadTimer = setTimeout(function () {
      loadTimer = null;
      item.click();
    }, 150);
  }

  // One row tabbable (APG roving tabindex): the selected one if it survives the swap,
  // else the first visible one. Rows render with NO tabindex attribute, so a browser
  // without this script still tabs through every row.
  function maintainTabindex() {
    var list = items();
    var chosen = null;
    for (var i = 0; i < list.length; i++) {
      if (list[i].getAttribute("aria-selected") === "true") { chosen = list[i]; break; }
    }
    if (!chosen && list.length > 0) chosen = list[0];
    list.forEach(function (item) {
      item.tabIndex = item === chosen ? 0 : -1;
    });
  }

  function parentFolderOf(item) {
    var scope = item.tagName === "SUMMARY" ? item.parentElement : item;
    for (var n = scope && scope.parentElement; n; n = n.parentElement) {
      if (n.id === PANE_ID) return null;
      if (n.tagName === "DETAILS") {
        var s = n.querySelector(":scope > summary");
        return s || null;
      }
    }
    return null;
  }

  function moveTo(list, index) {
    if (index < 0 || index >= list.length) return;
    var item = list[index];
    item.focus();
    select(item, true);
  }

  function onKeydown(event) {
    var target = event.target;
    if (!target.closest) return;
    var item = target.closest('[role="treeitem"], [role="option"]');
    if (!item || !pane() || !pane().contains(item)) return;

    var list = items();
    var kind = kindOf(item);
    var at = list.indexOf(item);

    switch (event.key) {
      case "ArrowDown":
        event.preventDefault();
        moveTo(list, nextIndex(list.length, at, 1));
        break;
      case "ArrowUp":
        event.preventDefault();
        moveTo(list, nextIndex(list.length, at, -1));
        break;
      case "ArrowRight":
        event.preventDefault();
        if (arrowRightOpens(kind, isExpanded(item))) {
          item.click();
        } else {
          moveTo(list, nextIndex(list.length, at, 1));
        }
        break;
      case "ArrowLeft":
        event.preventDefault();
        if (arrowLeftCloses(kind, isExpanded(item))) {
          item.click();
        } else {
          var parent = parentFolderOf(item);
          if (parent) { parent.focus(); select(parent, false); }
        }
        break;
      case "Enter":
        event.preventDefault();
        if (loadsDetail(kind)) {
          window.location.assign(item.getAttribute("data-editor-url"));
        } else {
          item.click();
        }
        break;
      case "Home":
        event.preventDefault();
        moveTo(list, nextIndex(list.length, -1, 1));
        break;
      case "End":
        event.preventDefault();
        moveTo(list, list.length - 1);
        break;
      default:
        break;
    }
  }

  function isExpanded(item) {
    var details = item.closest("details.tpl-folder");
    return !!details && details.open;
  }

  function init() {
    var p = pane();
    if (!p) return;
    p.addEventListener("keydown", onKeydown);
    p.addEventListener("click", function (event) {
      if (!event.target.closest) return;
      var item = event.target.closest('[role="treeitem"], [role="option"]');
      if (item && p.contains(item)) select(item, false);
    });
    // <details> toggle does not bubble; the capture phase still sees it, so one listener
    // keeps every folder summary's aria-expanded truthful without per-node handlers.
    document.addEventListener("toggle", function (event) {
      var t = event.target;
      if (t.tagName === "DETAILS" && t.classList.contains("tpl-folder")) {
        var s = t.querySelector(":scope > summary");
        if (s) s.setAttribute("aria-expanded", t.open ? "true" : "false");
      }
    }, true);
    // Rows that arrive in an htmx swap (a level, a search, a filter refresh) join the
    // same tabindex contract. Detail-pane swaps land outside the pane and are ignored.
    document.addEventListener("htmx:afterSwap", function (event) {
      var p2 = pane();
      if (p2 && event.target && p2.contains(event.target)) maintainTabindex();
    });
    maintainTabindex();
  }

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", init);
  } else {
    init();
  }

  // Exposed for editorJsTest (the init.js pattern): the navigation policy, DOM-free.
  window.templateExplorer = {
    nextIndex: nextIndex,
    arrowRightOpens: arrowRightOpens,
    arrowLeftCloses: arrowLeftCloses,
    loadsDetail: loadsDetail,
  };
})();
