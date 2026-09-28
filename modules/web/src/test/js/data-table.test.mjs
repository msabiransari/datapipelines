// 282 (#282) — the data table's decisions (static/js/data-table.js), DOM-free.
//
// What a header click does to the rows is decided here: the column's type, the value each cell
// sorts by, the comparator (nulls LAST in both directions), the stable order, the three-step
// cycle; and what a resize may do: the width clamp and the fill that makes a frozen layout
// cover its viewport exactly. The DOM halves (the cap, the sticky header, a drag, the frozen
// column) are the browser suite's to prove — DataTableBrowserTest.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const T = require(path.join(here, "../../main/resources/static/js/data-table.js"));

const sortTexts = (texts, type, dir) =>
  T.sortEntries(texts.map((t, i) => ({ key: T.sortKey(t, type), index: i, t })), dir).map((e) => e.t);

test("a cell with no value is empty or a lone dash of any width", () => {
  for (const t of ["", "  ", "—", "–", "-", null, undefined]) assert.equal(T.isNullText(t), true, JSON.stringify(t));
  for (const t of ["0", "-1", "—x", "none"]) assert.equal(T.isNullText(t), false, t);
});

test("the column type: a declared data-type wins, then .num, else text", () => {
  assert.equal(T.columnType("date", true), "date");
  assert.equal(T.columnType("text", true), "text");
  assert.equal(T.columnType(null, true), "number");
  assert.equal(T.columnType("bogus", false), "text");
  assert.equal(T.columnType(undefined, false), "text");
});

test("a number is the first one the cell carries, separators and the minus glyph included", () => {
  assert.equal(T.parseNumber("250 ms"), 250);
  assert.equal(T.parseNumber("1,234,567"), 1234567);
  assert.equal(T.parseNumber("v4"), 4);
  assert.equal(T.parseNumber("3 DRAFT"), 3);
  assert.equal(T.parseNumber("−3.5"), -3.5);
  assert.equal(T.parseNumber("-12"), -12);
  assert.equal(T.parseNumber("—"), null);
  assert.equal(T.parseNumber("absent"), null);
});

test("a date is an ISO-shaped string, with or without the T", () => {
  assert.equal(T.parseDate("2026-09-27T10:00:00Z"), Date.UTC(2026, 8, 27, 10, 0, 0));
  assert.ok(T.parseDate("2026-09-27 17:40") > T.parseDate("2026-09-27 09:05"));
  // RelativeTime.absolute's shape (the keys page's data-sort-value): "yyyy-MM-dd HH:mm UTC".
  assert.equal(T.parseDate("2026-09-27 17:40 UTC"), Date.UTC(2026, 8, 27, 17, 40));
  assert.equal(T.parseDate("3 days ago"), null);
  assert.equal(T.parseDate(""), null);
});

test("nulls sort LAST in both directions", () => {
  assert.deepEqual(sortTexts(["30 ms", "—", "5 ms", "", "100 ms"], "number", 1), ["5 ms", "30 ms", "100 ms", "—", ""]);
  assert.deepEqual(sortTexts(["30 ms", "—", "5 ms", "", "100 ms"], "number", -1), ["100 ms", "30 ms", "5 ms", "—", ""]);
  assert.deepEqual(sortTexts(["b", "-", "a"], "text", -1), ["b", "a", "-"]);
  // The comparator itself, both argument orders.
  assert.equal(T.compareKeys(null, 1, 1), 1);
  assert.equal(T.compareKeys(1, null, 1), -1);
  assert.equal(T.compareKeys(null, 1, -1), 1);
  assert.equal(T.compareKeys(1, null, -1), -1);
  assert.equal(T.compareKeys(null, null, -1), 0);
});

test("numbers compare as numbers, text by locale with digits read as numbers", () => {
  assert.deepEqual(sortTexts(["10", "9", "100"], "number", 1), ["9", "10", "100"]);
  assert.deepEqual(sortTexts(["node10", "node9", "Node2"], "text", 1), ["Node2", "node9", "node10"]);
  assert.deepEqual(sortTexts(["2026-09-27 10:00", "2026-09-26 23:59", "2026-09-27 09:00"], "date", -1),
    ["2026-09-27 10:00", "2026-09-27 09:00", "2026-09-26 23:59"]);
});

test("the sort is stable: equal keys keep their natural order in both directions", () => {
  const rows = [
    { key: "a", index: 0, id: "first" },
    { key: "b", index: 1, id: "b" },
    { key: "a", index: 2, id: "second" },
  ];
  assert.deepEqual(T.sortEntries(rows, 1).map((r) => r.id), ["first", "second", "b"]);
  assert.deepEqual(T.sortEntries(rows, -1).map((r) => r.id), ["b", "first", "second"]);
});

test("a header click walks none → ascending → descending → none", () => {
  assert.equal(T.nextSort("none"), "ascending");
  assert.equal(T.nextSort(undefined), "ascending");
  assert.equal(T.nextSort("ascending"), "descending");
  assert.equal(T.nextSort("descending"), "none");
});

test("the width clamp: whole pixels between the floor and the ceiling", () => {
  assert.equal(T.clampWidth(20, 64, Infinity), 64);
  assert.equal(T.clampWidth(120.6, 64, 400), 121);
  assert.equal(T.clampWidth(900, 0, 400), 400);
});

test("fill: the slack is shared in proportion and the widths sum to the room exactly", () => {
  const out = T.fillWidths([100, 200, 100], 1000.9);
  assert.equal(out.reduce((s, w) => s + w, 0), 1000);
  assert.ok(out[1] > out[0], "the wider column takes the larger share");
  // Fractional widths that exactly fill the room (the browser's own auto layout) must not
  // round UP past it: seven columns rounding up overflowed the viewport by a pixel or two and
  // put a horizontal scrollbar under every dense table (the first browser-suite screenshots).
  const measured = [402.6, 116.6, 124.6, 194.6, 122.6, 85.6, 93.4];
  assert.equal(T.fillWidths(measured, 1140).reduce((s, w) => s + w, 0), 1140);
  // Wider than the room: nothing shrinks.
  assert.deepEqual(T.fillWidths([700, 500], 1000), [700, 500]);
  assert.deepEqual(T.fillWidths([], 500), []);
  assert.deepEqual(T.fillWidths([0, 0], 500), [0, 0]);
});

test("a length the sheet names resolves to pixels: px, rem against the root, em against the element", () => {
  // A custom property is NOT resolved by getComputedStyle — "25rem" comes back as written, and
  // parseFloat alone read it as 25px (the first walk's every-column-25px lock).
  assert.equal(T.lengthPx("25rem", 16, 14), 400);
  assert.equal(T.lengthPx(" 4rem ", 16, 14), 64);
  assert.equal(T.lengthPx("2em", 16, 14), 28);
  assert.equal(T.lengthPx("120px", 16, 14), 120);
  assert.equal(T.lengthPx("120", 16, 14), 120);
  assert.equal(T.lengthPx("", 16, 14), null);
  assert.equal(T.lengthPx("calc(1px + 2px)", 16, 14), null);
});

test("a page-flow table fits when it is no wider than its frame", () => {
  assert.equal(T.fits(800, 800), true);
  assert.equal(T.fits(800.4, 800), true);
  assert.equal(T.fits(801, 800), false);
});

test("a paged list sorts the page it shows, and the title says so", () => {
  assert.equal(T.sortTitle("Started", true), "Sort this page by Started");
  assert.equal(T.sortTitle("Name", false), "Sort by Name");
});

test("a mutation batch's top-level roots: one subtree query per swap, not per node (288 #2)", () => {
  // parent + child + grandchild in one batch: the parent carries the whole subtree.
  const parent = { parentNode: null };
  const child = { parentNode: parent };
  const grandchild = { parentNode: child };
  assert.deepEqual(T.topLevelRoots([parent, child, grandchild]), [parent]);

  // siblings and disjoint trees all stay; an ancestor that arrives LATER in the same batch
  // still claims its descendants.
  const a = { parentNode: null };
  const b = { parentNode: null };
  const root = { parentNode: null };
  const nested = { parentNode: root };
  assert.deepEqual(T.topLevelRoots([a, b, nested, root]), [a, b, root]);

  // the walk stops at the batch boundary: a parent OUTSIDE the batch cannot dedupe a node.
  const outside = { parentNode: null };
  const insideChild = { parentNode: outside };
  assert.deepEqual(T.topLevelRoots([insideChild]), [insideChild]);

  // an element whose ancestor chain leaves the batch (through body/document) is a root.
  const body = { parentNode: null };
  const el = { parentNode: body };
  assert.deepEqual(T.topLevelRoots([el]), [el]);

  assert.deepEqual(T.topLevelRoots([]), []);
});

test("a held sort says so (288 #1): the title names the active state, beside the aria-sort", () => {
  assert.equal(T.activeSortTitle("Started", "ascending", true), "Sorting this page by Started — click for highest first");
  assert.equal(T.activeSortTitle("Started", "descending", true), "Sorting this page by Started — click to clear");
  assert.equal(T.activeSortTitle("Name", "ascending", false), "Sorting by Name — click for highest first");
  assert.equal(T.activeSortTitle("Name", "descending", false), "Sorting by Name — click to clear");
});

test("a page whose rows are ALL new is a replacement (288 #1, the index() seam — 301 #301)", () => {
  // Page 2 arrives as fresh elements: the client sort clears (the server's order shows).
  const page1 = [{}, {}, {}];
  const order = new FakeOrder();
  page1.forEach((tr) => order.set(tr, order.next++));
  assert.equal(T.allRowsNew([{}, {}, {}], order.map), true, "a replaced page clears the sort");

  // A row EDIT — some rows keep their natural index — keeps the sort.
  const kept = page1[0];
  assert.equal(T.allRowsNew([kept, {}, {}], order.map), false);

  // An APPEND — the old rows are still known — keeps it; so does a keyed re-render of
  // the same elements (the dock's index-keyed rows).
  assert.equal(T.allRowsNew(page1, order.map), false);
  assert.equal(T.allRowsNew([], order.map), false, "an empty batch decides nothing");
});

/** WeakMap without the DOM: the same has/set surface the decision reads. */
class FakeOrder {
  map = new Map();
  next = 0;
  has(tr) {
    return this.map.has(tr);
  }
  set(tr, i) {
    this.map.set(tr, i);
  }
}
