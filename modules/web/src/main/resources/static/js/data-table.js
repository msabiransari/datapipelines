/*
 * The house data table (282, #282; ui-screens.md §2 rule 9 and §3.7). Vendored house code:
 * no dependency, no build step, one IIFE loaded from the layout for every page.
 *
 * It UPGRADES the server-rendered `.dt-frame > .dt-viewport > table.ds-table` that
 * data-table.css already draws — frame, one scroll viewport, sticky header — with the parts
 * CSS cannot do alone:
 *
 *  - the header CAP: `--dt-sbw` (the viewport's real scrollbar width, offsetWidth −
 *    clientWidth, 0 on overlay scrollbars) and `--dt-head-h` (the header row's height), which
 *    size the frame's ::after over the scrollbar track beside the header;
 *  - PAGE-FLOW frames (no `dt-scroll` / `dt-fill`): `dt-fits` while the table fits its frame,
 *    which turns the viewport's overflow off so the header sticks to the page's own scroller
 *    (<main>, §3.4); a table wider than its frame keeps the bounded box and scrolls inside it;
 *  - client SORT over the rendered rows (`aria-sort`; nulls last in both directions; a paged
 *    list sorts the page it shows — the button says so);
 *  - column RESIZE on a `<colgroup>` with `table-layout: fixed` (a drag changes one column);
 *  - the frozen first column's shadow (`is-scrolled-x`) and keyboard rows (Enter opens a
 *    `data-href` row through shell.js's own click handler; ↑/↓ move between rows).
 *
 * CSP (`script-src 'self'`, `style-src 'self'`, no nonce): nothing here writes a `style`
 * attribute or parses markup. Widths and the two measured variables go through the CSSOM
 * (`el.style.width`, `style.setProperty`), which the policy permits; the only elements created
 * are the sort button, its sprite icon, the resize handle and the `<colgroup>` skeleton — never
 * a cell, never from data. Sort moves the rendered `<tr>` nodes; `data-href` is never read.
 *
 * Tables arrive four ways — the first paint, htmx swaps (incl. out-of-band), Alpine (the
 * editor's result dock) and cloned `<template>`s (the schedules page) — so a document-level
 * MutationObserver finds new tables and re-applies the ensure steps when a table's rows change;
 * `htmx:afterSwap` / `htmx:oobAfterSwap` upgrade their subtree at once. Every step is
 * idempotent, and a table is initialised once (`data-dt-ready` plus the instance map).
 */
(function () {
  "use strict";

  // ---------------------------------------------------------------- pure (node --test)

  var NULL_TEXT = ["", "—", "–", "-"];
  var NUMBER = /[-−]?\d[\d,  ]*(?:\.\d+)?/;

  /** A cell that says "no value": empty, or a lone dash of any width. */
  function isNullText(text) {
    return NULL_TEXT.indexOf(String(text == null ? "" : text).trim()) !== -1;
  }

  /** The column's sort type: a declared `data-type` wins, then `.num` (number), else text. */
  function columnType(declared, isNum) {
    if (declared === "number" || declared === "date" || declared === "text") return declared;
    return isNum ? "number" : "text";
  }

  /** The first number a cell carries — "v4", "1,234", "250 ms", "−3" — or null. */
  function parseNumber(text) {
    if (isNullText(text)) return null;
    var match = NUMBER.exec(String(text));
    if (!match) return null;
    var n = parseFloat(match[0].replace(/[,  ]/g, "").replace("−", "-"));
    return isFinite(n) ? n : null;
  }

  /**
   * An ISO-shaped date as epoch ms, or null: "2026-09-27 17:40" (local), "2026-09-27T17:40:00Z",
   * and RelativeTime.absolute's "2026-09-27 17:40 UTC".
   */
  function parseDate(text) {
    if (isNullText(text)) return null;
    var iso = String(text).trim().replace(/\s+UTC$/, "Z").replace(/^(\d{4}-\d{2}-\d{2}) /, "$1T");
    var ms = Date.parse(iso);
    return isFinite(ms) ? ms : null;
  }

  /** What a cell sorts by under its column's type; null sorts last whatever the direction. */
  function sortKey(text, type) {
    if (isNullText(text)) return null;
    if (type === "number") return parseNumber(text);
    if (type === "date") return parseDate(text);
    return String(text).replace(/\s+/g, " ").trim();
  }

  /** Nulls last in BOTH directions; numbers numerically; text by locale, digits as numbers. */
  function compareKeys(a, b, dir) {
    if (a === null && b === null) return 0;
    if (a === null) return 1;
    if (b === null) return -1;
    if (typeof a === "number" && typeof b === "number") return a === b ? 0 : (a < b ? -dir : dir);
    return String(a).localeCompare(String(b), undefined, { numeric: true, sensitivity: "base" }) * dir;
  }

  /** Stable: entries `{key, index}` with equal keys keep their natural order. */
  function sortEntries(entries, dir) {
    return entries.slice().sort(function (x, y) {
      return compareKeys(x.key, y.key, dir) || x.index - y.index;
    });
  }

  /** What one header click does: none → ascending → descending → none. */
  function nextSort(current) {
    if (current === "ascending") return "descending";
    if (current === "descending") return "none";
    return "ascending";
  }

  /** A column width in whole pixels, never below `min` nor above `max`. */
  function clampWidth(width, min, max) {
    return Math.round(Math.min(max, Math.max(min, width)));
  }

  /**
   * Whole-pixel widths that fill `available` exactly: each width is FLOORED (rounding seven
   * fractional columns up overflowed the viewport by a pixel or two — a horizontal scrollbar
   * under every dense table), the slack is shared in proportion and the remainder goes to the
   * last column. Widths already wider than `available` stay.
   */
  function fillWidths(widths, available) {
    var ints = widths.map(function (w) { return Math.max(0, Math.floor(w)); });
    var total = ints.reduce(function (s, w) { return s + w; }, 0);
    var room = Math.floor(available);
    if (!ints.length || total >= room || total === 0) return ints;
    var extra = room - total;
    var out = ints.map(function (w) { return w + Math.floor((extra * w) / total); });
    out[out.length - 1] += room - out.reduce(function (s, w) { return s + w; }, 0);
    return out;
  }

  /**
   * A length the sheet names (`--dt-col-min: 4rem`) in pixels, or null. getComputedStyle does NOT
   * resolve a custom property's units — it hands back "4rem" as written — so px, rem (against the
   * root's font size) and em (against the element's) are resolved here.
   */
  function lengthPx(raw, rootFontPx, fontPx) {
    var match = /^\s*(-?\d*\.?\d+)(px|rem|em)?\s*$/.exec(String(raw == null ? "" : raw));
    if (!match) return null;
    var n = parseFloat(match[1]);
    if (match[2] === "rem") return n * rootFontPx;
    if (match[2] === "em") return n * fontPx;
    return n;
  }

  /** A page-flow table fits when it is no wider than its frame's inner width. */
  function fits(tableWidth, frameInnerWidth) {
    return tableWidth <= frameInnerWidth + 0.5;
  }

  /** The sort button's title: a paged list sorts only the page it shows, and says so. */
  function sortTitle(label, paged) {
    return (paged ? "Sort this page by " : "Sort by ") + label;
  }

  /**
   * 288 #1 / 301 (#301): the rows of THIS batch are all new to [order] — a page replacement,
   * by DOM identity. The seam `index()`'s sort-clear rule reads; node-tested so the rule's
   * three shapes stay decided in one place: a replaced page clears the client sort, a row
   * edit / append / keyed same-page re-render (elements the order already knows) keeps it.
   */
  function allRowsNew(rows, order) {
    return rows.length > 0 && rows.every(function (tr) { return !order.has(tr); });
  }

  /**
   * The title while a sort is HELD (288 #1): the label states the active state, not just the
   * affordance. On a paged list whose paging re-renders rows in place (the editor's dock,
   * index-keyed), a held sort persists across pages by element identity — the title says so
   * beside the `aria-sort` it already names, so the held state is never silent.
   */
  function activeSortTitle(label, dir, paged) {
    var tail = dir === "ascending" ? " — click for highest first" : " — click to clear";
    return (paged ? "Sorting this page by " : "Sorting by ") + label + tail;
  }

  var api = {
    isNullText: isNullText,
    columnType: columnType,
    parseNumber: parseNumber,
    parseDate: parseDate,
    sortKey: sortKey,
    compareKeys: compareKeys,
    sortEntries: sortEntries,
    nextSort: nextSort,
    clampWidth: clampWidth,
    fillWidths: fillWidths,
    lengthPx: lengthPx,
    fits: fits,
    sortTitle: sortTitle,
    activeSortTitle: activeSortTitle,
    allRowsNew: allRowsNew,
    topLevelRoots: topLevelRoots,
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window === "undefined" || typeof document === "undefined") return;
  window.DpDataTable = api;

  // ---------------------------------------------------------------- the DOM half

  var SELECTOR = ".dt-frame > .dt-viewport > table.ds-table";
  var ICON = "/vendor/icons/lucide-sprite.svg#chevron-right";
  var SVG = "http://www.w3.org/2000/svg";
  var instances = new WeakMap();

  function childWithClass(el, cls) {
    for (var c = el.firstElementChild; c; c = c.nextElementSibling) {
      if (c.classList.contains(cls)) return c;
    }
    return null;
  }

  /** A length the sheet owns (`--dt-col-min`, `--dt-col-max`), read off the frame, in pixels. */
  function cssPx(el, name, fallback) {
    var value = lengthPx(getComputedStyle(el).getPropertyValue(name),
      parseFloat(getComputedStyle(document.documentElement).fontSize), parseFloat(getComputedStyle(el).fontSize));
    return value === null ? fallback : value;
  }

  /**
   * What a cell sorts by: its `data-sort-value` when the server gave one (a relative age's
   * instant), else a `<time datetime>` inside it (the machine instant beside the reader's
   * words), else the chosen option of a `<select>` in it (a member's role), else its text.
   */
  function cellValue(td) {
    if (!td) return null;
    if (td.hasAttribute("data-sort-value")) return td.getAttribute("data-sort-value");
    var time = td.querySelector("time[datetime]");
    if (time) return time.getAttribute("datetime");
    var select = td.querySelector("select");
    if (select && select.selectedIndex >= 0) return select.options[select.selectedIndex].text;
    return td.textContent;
  }

  function isStateRow(tr) {
    return tr.classList.contains("dt-state-row") || tr.classList.contains("dt-skeleton-row") ||
      (tr.cells.length === 1 && tr.cells[0].colSpan > 1);
  }

  function DataTable(table) {
    this.table = table;
    this.viewport = table.parentElement;
    this.frame = this.viewport.parentElement;
    // 288 (#288): a viewport is a scroll area, so it is keyboard-scrollable — tabindex="0"
    // (the markup renders -1, programmatically focusable only) with the sheet's focus ring.
    // 301 (#301): only a viewport that actually SCROLLS answers the arrows, so the tab stop
    // lands on it and on nothing else — a page-flow frame that fits (the sheet's
    // `overflow: clip`) is not a scroll container and gets its inert -1 back. Re-checked in
    // measure(), where every state change lands.
    this.sortCol = -1;
    this.sortDir = "none";
    this.widths = null; // whole-pixel column widths while locked (table-layout: fixed), else null
    this.natural = null; // the widths at lock time — what a double-click gives back
    this.userSized = false;
    this.frameWidth = -1;
    this.order = new WeakMap(); // <tr> → its natural position
    this.nextIndex = 0;
    table.setAttribute("data-dt-ready", "");
    this.wire();
    this.refresh();
  }

  DataTable.prototype.headCells = function () {
    var head = this.table.tHead;
    if (!head || !head.rows.length) return [];
    return Array.prototype.slice.call(head.rows[head.rows.length - 1].cells);
  };

  DataTable.prototype.dataRows = function () {
    var rows = [];
    Array.prototype.forEach.call(this.table.tBodies, function (body) {
      Array.prototype.forEach.call(body.rows, function (tr) {
        if (!isStateRow(tr)) rows.push(tr);
      });
    });
    return rows;
  };

  DataTable.prototype.isFlow = function () {
    return !this.frame.classList.contains("dt-scroll") && !this.frame.classList.contains("dt-fill");
  };

  /** The header's two affordances, created once per cell and re-created if a renderer wiped them. */
  DataTable.prototype.ensureHeader = function (cells) {
    var self = this;
    var paged = this.frame.hasAttribute("data-dt-paged");
    cells.forEach(function (th, i) {
      if (!th.hasAttribute("scope")) th.setAttribute("scope", "col");
      var button = childWithClass(th, "dt-sort");
      var sortable = th.getAttribute("data-sort") !== "off" && th.textContent.trim() !== "";
      if (sortable && !button) {
        button = document.createElement("button");
        button.type = "button";
        button.className = "dt-sort";
        var label = document.createElement("span");
        label.className = "dt-sort-label";
        Array.prototype.slice.call(th.childNodes).forEach(function (node) {
          if (!(node.nodeType === 1 && node.classList.contains("dt-resizer"))) label.appendChild(node);
        });
        var icon = document.createElementNS(SVG, "svg");
        icon.setAttribute("class", "ds-icon ds-icon-xs dt-sort-ic");
        icon.setAttribute("aria-hidden", "true");
        icon.setAttribute("focusable", "false");
        var use = document.createElementNS(SVG, "use");
        use.setAttribute("href", ICON);
        icon.appendChild(use);
        button.appendChild(label);
        button.appendChild(icon);
        th.insertBefore(button, th.firstChild);
      }
      if (button) {
        var text = button.textContent.trim();
        var title = i === self.sortCol && self.sortDir !== "none"
          ? activeSortTitle(text, self.sortDir, paged)
          : sortTitle(text, paged);
        if (button.title !== title) button.title = title;
        var state = i === self.sortCol ? self.sortDir : "none";
        if (th.getAttribute("aria-sort") !== state) th.setAttribute("aria-sort", state);
      }
      if (th.getAttribute("data-resize") !== "off" && !childWithClass(th, "dt-resizer")) {
        var handle = document.createElement("span");
        handle.className = "dt-resizer";
        handle.setAttribute("aria-hidden", "true");
        th.appendChild(handle);
      }
    });
  };

  /** One `<col>` per header cell; true when it had to be (re)built, which resets the widths. */
  DataTable.prototype.ensureCols = function (count) {
    var group = null;
    for (var c = this.table.firstElementChild; c; c = c.nextElementSibling) {
      if (c.tagName === "COLGROUP") { group = c; break; }
    }
    if (group && group.children.length === count) return false;
    if (group) group.parentNode.removeChild(group);
    group = document.createElement("colgroup");
    group.setAttribute("data-dt-cols", "");
    for (var i = 0; i < count; i++) group.appendChild(document.createElement("col"));
    this.table.insertBefore(group, this.table.firstChild);
    return true;
  };

  DataTable.prototype.cols = function () {
    var group = this.table.querySelector(":scope > colgroup");
    return group ? Array.prototype.slice.call(group.children) : [];
  };

  DataTable.prototype.ensureRows = function (rows) {
    rows.forEach(function (tr) {
      if (tr.hasAttribute("data-href") && !tr.hasAttribute("tabindex")) tr.setAttribute("tabindex", "0");
    });
  };

  /** Everything, idempotently: header, cols, rows, the active sort, widths, measurements. */
  DataTable.prototype.refresh = function () {
    var cells = this.headCells();
    if (!cells.length) return;
    this.ensureHeader(cells);
    if (this.ensureCols(cells.length)) {
      this.widths = null;
      this.natural = null;
      this.userSized = false;
      this.unlockStyles();
    }
    var rows = this.dataRows();
    this.ensureRows(rows);
    this.index(rows);
    if (this.sortDir !== "none") this.applySort();
    if (!this.userSized && this.frame.classList.contains("dt-nowrap")) this.lock(cssPx(this.frame, "--dt-col-max", 400));
    this.measure();
  };

  /** Natural positions: an unsorted table's DOM order IS natural; rows new to a sorted one queue after.
   *
   *  288 (#288): when EVERY data row is new to a sorted, PAGED table, the page was replaced
   *  (the htmx pagers swap the whole frame; the dock's cursor paging re-renders its rows) and
   *  the new page shows the SERVER's order: the client sort is cleared, not re-applied over
   *  rows the server ordered differently — the button's title already says the sort is per
   *  page ("Sort this page by …"), so the state the label claims is the state the rows are in.
   *  A row EDIT (some rows keep their natural index) and an APPEND (old rows keep theirs) keep
   *  the sort; a keyed re-render of the same rows reuses the same elements and keeps it too.
   *
   *  301 (#301): the every-row-new decision is [allRowsNew], node-tested — the guard for a
   *  future swap that keeps the table element has a red state of its own now.
   */
  DataTable.prototype.index = function (rows) {
    var self = this;
    if (this.sortDir !== "none" && this.frame.hasAttribute("data-dt-paged") && allRowsNew(rows, this.order)) {
      this.sortDir = "none";
      this.sortCol = -1;
      this.order = new WeakMap();
      this.nextIndex = 0;
      this.ensureHeader(this.headCells());
    }
    if (this.sortDir === "none") {
      this.nextIndex = 0;
      rows.forEach(function (tr) { self.order.set(tr, self.nextIndex++); });
      return;
    }
    rows.forEach(function (tr) { if (!self.order.has(tr)) self.order.set(tr, self.nextIndex++); });
  };

  DataTable.prototype.applySort = function () {
    var self = this;
    var rows = this.dataRows();
    if (rows.length < 2) return;
    var ordered;
    if (this.sortDir === "none") {
      ordered = rows.slice().sort(function (a, b) { return self.order.get(a) - self.order.get(b); });
    } else {
      var th = this.headCells()[this.sortCol];
      var type = columnType(th.getAttribute("data-type"), th.classList.contains("num"));
      var col = this.sortCol;
      var entries = rows.map(function (tr) {
        return { row: tr, index: self.order.get(tr), key: sortKey(cellValue(tr.cells[col]), type) };
      });
      ordered = sortEntries(entries, this.sortDir === "ascending" ? 1 : -1).map(function (e) { return e.row; });
    }
    if (ordered.every(function (tr, k) { return rows[k] === tr; })) return;
    var body = rows[0].parentNode;
    ordered.forEach(function (tr) { body.appendChild(tr); });
  };

  DataTable.prototype.toggleSort = function (col) {
    if (col < 0) return;
    this.sortDir = col === this.sortCol ? nextSort(this.sortDir) : "ascending";
    this.sortCol = this.sortDir === "none" ? -1 : col;
    this.ensureHeader(this.headCells());
    this.applySort();
    if (!this.isFlow()) this.viewport.scrollTop = 0;
  };

  DataTable.prototype.unlockStyles = function () {
    this.cols().forEach(function (col) { col.style.width = ""; });
    this.table.style.width = "";
    this.table.classList.remove("dt-fixed");
  };

  /**
   * Freezes the rendered column widths onto the `<col>`s (whole pixels, each at most `max`,
   * the slack shared so the table still fills its viewport) and switches to fixed layout.
   * False while the table is not rendered (a hidden pane): nothing to measure yet.
   */
  DataTable.prototype.lock = function (max) {
    var cells = this.headCells();
    var cols = this.cols();
    if (!cells.length || cols.length !== cells.length) return false;
    this.unlockStyles();
    var room = this.viewport.getBoundingClientRect().width - (this.viewport.offsetWidth - this.viewport.clientWidth);
    var measured = cells.map(function (th) { return th.getBoundingClientRect().width; });
    if (room <= 0 || measured.every(function (w) { return w === 0; })) return false;
    var widths = fillWidths(measured.map(function (w) { return Math.min(w, max); }), room);
    this.natural = widths.slice();
    this.applyWidths(widths);
    return true;
  };

  DataTable.prototype.applyWidths = function (widths) {
    var cols = this.cols();
    widths.forEach(function (w, i) { if (cols[i]) cols[i].style.width = w + "px"; });
    this.table.style.width = widths.reduce(function (s, w) { return s + w; }, 0) + "px";
    this.table.classList.add("dt-fixed");
    this.widths = widths;
  };

  DataTable.prototype.setWidth = function (col, width) {
    var widths = this.widths.slice();
    widths[col] = width;
    this.applyWidths(widths);
    this.measure();
  };

  DataTable.prototype.startResize = function (evt, handle) {
    var col = this.headCells().indexOf(handle.closest("th"));
    if (col < 0 || evt.button !== 0) return;
    evt.preventDefault();
    if (!this.widths && !this.lock(Infinity)) return;
    var self = this;
    var startX = evt.clientX;
    var startWidth = this.widths[col];
    var min = cssPx(this.frame, "--dt-col-min", 64);
    var root = document.documentElement;
    handle.classList.add("is-active");
    root.classList.add("dt-resizing");
    try { handle.setPointerCapture(evt.pointerId); } catch (e) { /* synthetic events carry no live pointer */ }
    function move(ev) {
      self.userSized = true;
      self.setWidth(col, clampWidth(startWidth + ev.clientX - startX, min, Infinity));
    }
    function up() {
      handle.classList.remove("is-active");
      root.classList.remove("dt-resizing");
      handle.removeEventListener("pointermove", move);
      handle.removeEventListener("pointerup", up);
      handle.removeEventListener("pointercancel", up);
    }
    handle.addEventListener("pointermove", move);
    handle.addEventListener("pointerup", up);
    handle.addEventListener("pointercancel", up);
  };

  /** The element whose scrolling a page-flow header sticks to: the nearest scroll container. */
  function scrollerOf(el) {
    for (var node = el.parentElement; node && node !== document.body; node = node.parentElement) {
      if (/^(auto|scroll|hidden|overlay)$/.test(getComputedStyle(node).overflowY)) return node;
    }
    return null;
  }

  /**
   * Whether THIS viewport can actually scroll: a scroll container (the sheet's `auto` states —
   * FIXED `dt-scroll`, `dt-fill`, the page-flow frame that stopped fitting) whose content
   * overflows it. `clip` (a fitting page-flow frame) is not a scroll container, so its
   * content width never makes it a tab stop.
   */
  DataTable.prototype.viewportScrolls = function () {
    var style = getComputedStyle(this.viewport);
    function container(o) {
      return o === "auto" || o === "scroll" || o === "overlay" || o === "hidden";
    }
    if (!container(style.overflowX) && !container(style.overflowY)) return false;
    return this.viewport.scrollHeight > this.viewport.clientHeight ||
      this.viewport.scrollWidth > this.viewport.clientWidth;
  };

  /** The tab stop follows the scrolling (301 #301): focusable only while the arrows answer. */
  DataTable.prototype.syncViewportFocus = function () {
    var desired = this.viewportScrolls() ? "0" : "-1";
    if (this.viewport.getAttribute("tabindex") !== desired) {
      this.viewport.setAttribute("tabindex", desired);
    }
  };

  /**
   * The cap's two numbers and, on a page-flow frame, whether the table fits it — and, while it
   * does, `--dt-flow-top`: MINUS its scroller's top padding. Chromium sticks a `top: 0` cell at
   * the scroller's CONTENT edge (measured: <main>'s 24px `--gap-lg` below the top bar, rows
   * showing through the band above the header); the negative inset puts it on the scroller's
   * visible edge — under the top bar on a page, at a dialog's edge in a dialog.
   */
  DataTable.prototype.measure = function () {
    this.syncViewportFocus();
    var frame = this.frame;
    var viewport = this.viewport;
    if (this.isFlow()) {
      var fitsNow = fits(this.table.offsetWidth, frame.clientWidth);
      frame.classList.toggle("dt-fits", fitsNow);
      var scroller = fitsNow ? scrollerOf(frame) : null;
      var pad = scroller ? parseFloat(getComputedStyle(scroller).paddingTop) || 0 : 0;
      frame.style.setProperty("--dt-flow-top", -pad + "px");
    }
    var head = this.table.tHead;
    frame.style.setProperty("--dt-sbw", Math.max(0, viewport.offsetWidth - viewport.clientWidth) + "px");
    frame.style.setProperty("--dt-head-h", Math.round(head ? head.getBoundingClientRect().height : 0) + "px");
    frame.classList.toggle("is-scrolled-x", viewport.scrollLeft > 0);
  };

  /** A new frame width re-fits an auto-sized dense table; any size change re-measures. */
  DataTable.prototype.onResize = function () {
    if (!this.table.isConnected) return;
    var width = Math.round(this.frame.getBoundingClientRect().width);
    if (width !== this.frameWidth) {
      this.frameWidth = width;
      if (!this.userSized && this.frame.classList.contains("dt-nowrap")) this.lock(cssPx(this.frame, "--dt-col-max", 400));
    }
    this.measure();
  };

  /** ResizeObserver work runs on the next frame: changing sizes inside its callback loops it. */
  DataTable.prototype.scheduleResize = function () {
    var self = this;
    if (this.resizeQueued) return;
    this.resizeQueued = true;
    requestAnimationFrame(function () {
      self.resizeQueued = false;
      self.onResize();
    });
  };

  DataTable.prototype.wire = function () {
    var self = this;
    var table = this.table;
    var viewport = this.viewport;
    table.addEventListener("click", function (evt) {
      var button = evt.target.closest && evt.target.closest(".dt-sort");
      if (button && table.contains(button)) self.toggleSort(self.headCells().indexOf(button.closest("th")));
    });
    table.addEventListener("pointerdown", function (evt) {
      var handle = evt.target.closest && evt.target.closest(".dt-resizer");
      if (handle && table.contains(handle)) self.startResize(evt, handle);
    });
    table.addEventListener("dblclick", function (evt) {
      var handle = evt.target.closest && evt.target.closest(".dt-resizer");
      if (!handle || !self.natural) return;
      var col = self.headCells().indexOf(handle.closest("th"));
      if (col >= 0) self.setWidth(col, self.natural[col]);
    });
    table.addEventListener("keydown", function (evt) {
      var tr = evt.target;
      if (!tr || tr.tagName !== "TR" || !tr.hasAttribute("data-href")) return;
      if (evt.key === "Enter") {
        evt.preventDefault();
        tr.click(); // shell.js's delegated [data-href] handler navigates — the same path as a click
        return;
      }
      if (evt.key !== "ArrowDown" && evt.key !== "ArrowUp") return;
      var next = evt.key === "ArrowDown" ? tr.nextElementSibling : tr.previousElementSibling;
      while (next && !(next.tagName === "TR" && next.hasAttribute("data-href"))) {
        next = evt.key === "ArrowDown" ? next.nextElementSibling : next.previousElementSibling;
      }
      if (next) {
        evt.preventDefault();
        next.focus();
      }
    });
    viewport.addEventListener("scroll", function () {
      self.frame.classList.toggle("is-scrolled-x", viewport.scrollLeft > 0);
    }, { passive: true });
    if (typeof ResizeObserver !== "undefined") {
      this.observer = new ResizeObserver(function () { self.scheduleResize(); });
      this.observer.observe(this.frame);
      this.observer.observe(table);
    }
  };

  DataTable.prototype.detach = function () {
    if (this.observer) this.observer.disconnect();
  };

  // ---------------------------------------------------------------- finding tables

  function tablesIn(root) {
    var found = [];
    if (!root || root.nodeType !== 1) return found;
    if (root.matches(SELECTOR)) found.push(root);
    Array.prototype.push.apply(found, root.querySelectorAll(SELECTOR));
    return found;
  }

  function upgrade(root) {
    tablesIn(root && root.nodeType === 9 ? root.documentElement : root).forEach(function (table) {
      if (!instances.has(table)) instances.set(table, new DataTable(table));
    });
  }

  function refreshFor(node, pending) {
    var el = node && (node.nodeType === 1 ? node : node.parentElement);
    var table = el && el.closest && el.closest("table[data-dt-ready]");
    var inst = table && instances.get(table);
    if (inst && pending.indexOf(inst) === -1) pending.push(inst);
  }

  function onMutations(records) {
    var pending = [];
    var added = [];
    var removed = [];
    records.forEach(function (record) {
      Array.prototype.forEach.call(record.removedNodes, function (node) {
        if (node.nodeType === 1) removed.push(node);
      });
      Array.prototype.forEach.call(record.addedNodes, function (node) {
        if (node.nodeType === 1) added.push(node);
      });
      refreshFor(record.target, pending);
    });
    // 288 (#288): one subtree query per TOP-LEVEL root, not per node — a swap that adds a
    // 500-node subtree used to query once per node (every child re-queried its own subtree,
    // O(n²) over the batch). Top-level roots carry the whole subtree; the rest is redundant.
    // A root INSIDE an upgraded table is one of its rows — refreshFor (the target walk) owns
    // it, and querying its (row-sized) subtree found nothing anyway.
    topLevelRoots(removed).forEach(function (node) {
      if (node.closest && node.closest("table[data-dt-ready]") && node.tagName !== "TABLE") return;
      var gone = node.matches("table[data-dt-ready]") ? [node] : Array.prototype.slice.call(node.querySelectorAll("table[data-dt-ready]"));
      gone.forEach(function (table) {
        var inst = instances.get(table);
        if (inst && !table.isConnected) inst.detach();
      });
    });
    topLevelRoots(added).forEach(function (node) {
      if (node.closest && node.closest("table[data-dt-ready]")) return;
      upgrade(node);
    });
    pending.forEach(function (inst) { if (inst.table.isConnected) inst.refresh(); });
  }

  /** The batch's elements with no strict ancestor also in the batch — one query per subtree.
   *
   *  301 (#301): the membership read is a Set, not `indexOf` over the batch — the dock's
   *  1,000-row page render put ~12 ancestors × 1,000 nodes through an O(n²·depth) scan;
   *  `inBatch.has` makes it O(n · depth). Measured before/after in the lane's evidence. */
  function topLevelRoots(nodes) {
    var inBatch = new Set(nodes);
    return nodes.filter(function (node) {
      for (var p = node.parentNode; p; p = p.parentNode) {
        if (inBatch.has(p)) return false;
      }
      return true;
    });
  }

  /**
   * htmx snapshots the page into its history cache as MARKUP before a boosted swap, and a
   * back navigation re-parses it: a CSSOM width serialised into a `style` attribute would come
   * back as an inline style the policy refuses. This cleanup runs on the LIVE page, an instant
   * before it is snapshotted and replaced (htmx clones the history element only AFTER the
   * event — 301 #301), so a restored table's widths are the re-measured ones: the restored
   * table is re-upgraded (the instance map, not the attribute, decides).
   *
   * 287 (#287): the strip runs from shell.js's one `htmx:beforeHistorySave` listener through
   * the window.__dpHistoryStyleCleanups registry — this write set is registered there instead
   * of this file listening on its own, so the snapshot has ONE seam and every script strips
   * only its own write set. The cleanup takes the history element (shell.js resolves
   * `evt.detail.historyElt`, which is `document.body` — no `hx-history-elt` in the
   * layout), so the walk covers every table on the page.
   */
  function beforeHistorySave(root) {
    tablesIn(root).forEach(function (table) {
      var frame = table.parentElement.parentElement;
      [frame, table].concat(Array.prototype.slice.call(table.querySelectorAll(":scope > colgroup > col"))).forEach(function (el) {
        el.removeAttribute("style");
      });
    });
  }

  // htmx's history restore re-creates every <script> in the cached body, so this file used to
  // run again on every Back — a second observer, a second DataTable per restored table and a
  // second registry entry each time (the 287 merge's security pass, O1). Wire once, the
  // shell.js idiom: the observer below lives on the persistent <body> and upgrades the
  // restored tables itself.
  if (window.__dpDataTableInit) return;
  window.__dpDataTableInit = true;
  upgrade(document);
  if (typeof MutationObserver !== "undefined") {
    new MutationObserver(onMutations).observe(document.body, { childList: true, subtree: true });
  }
  document.body.addEventListener("htmx:afterSwap", function (evt) { upgrade(evt.detail && evt.detail.target); });
  document.body.addEventListener("htmx:oobAfterSwap", function (evt) { upgrade(evt.detail && evt.detail.target); });
  window.__dpHistoryStyleCleanups = window.__dpHistoryStyleCleanups || [];
  window.__dpHistoryStyleCleanups.push(beforeHistorySave);
  if (document.fonts && document.fonts.ready) {
    document.fonts.ready.then(function () {
      tablesIn(document.documentElement).forEach(function (table) {
        var inst = instances.get(table);
        if (inst) inst.onResize();
      });
    });
  }
})();
