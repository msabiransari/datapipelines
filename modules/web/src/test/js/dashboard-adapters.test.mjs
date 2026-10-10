// The dashboard adapters (#10, the implementation spec's §10.3): the composite adapter's contract
// functions, the Plotly substitution/escaping/theme mapping, and the table/KPI renderers' text-only
// DOM. Node's built-in runner; the fake DOM is the same shape the runtime tests install.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const resolveStatic = (name) => path.resolve(here, "../../main/resources/static/js/" + name);

// ---------------------------------------------------------------------------------------------- fake DOM

function fakeElement(tag) {
  const attrs = {};
  const children = [];
  const el = {
    tagName: (tag || "div").toUpperCase(),
    attrs,
    children,
    style: {},
    className: "",
    // Real-DOM semantics: a direct assignment wins (the runtime sets leaf text), otherwise the
    // value composes from the children (text nodes' text, elements' own textContent) — the
    // #498 error row is appendChild-composed, so reading its textContent must see the parts.
    get textContent() {
      if (el._text !== undefined) return el._text;
      return children
        .map((c) => (c.nodeType === 3 ? c.text : c.textContent))
        .join("");
    },
    set textContent(v) {
      el._text = String(v);
    },
    innerHTML: null, // written by NOBODY under test — the rendering rule
    scope: null,
    clientWidth: 0,
    parentNode: null,
    firstChild: null,
    value: "",
    disabled: false,
    setAttribute(k, v) {
      attrs[k] = String(v);
    },
    getAttribute(k) {
      return k in attrs ? attrs[k] : null;
    },
    removeAttribute(k) {
      delete attrs[k];
    },
    appendChild(child) {
      child.parentNode = el;
      children.push(child);
      el.firstChild = children[0] || null;
      return child;
    },
    insertBefore(child, before) {
      child.parentNode = el;
      const index = children.indexOf(before);
      if (index === -1) children.push(child);
      else children.splice(index, 0, child);
      el.firstChild = children[0] || null;
      return child;
    },
    removeChild(child) {
      const index = children.indexOf(child);
      if (index !== -1) children.splice(index, 1);
      child.parentNode = null;
      el.firstChild = children[0] || null;
      return child;
    },
    remove() {
      if (el.parentNode) el.parentNode.removeChild(el);
    },
    addEventListener(type, fn) {
      (el.listeners ||= {})[type] = (el.listeners[type] || []).concat(fn);
    },
    fire(type, event) {
      ((el.listeners || {})[type] || []).forEach((fn) => fn.call(el, event || {}));
    },
    querySelector(selector) {
      return el.querySelectorAll(selector)[0] || null;
    },
    querySelectorAll(selector) {
      const out = [];
      const classWanted = selector.startsWith(".") ? selector.slice(1) : null;
      const attrMatch = selector.startsWith("[") ? /^\[([a-z-]+)/.exec(selector) : null;
      const matches = (node) => {
        if (classWanted) return typeof node.className === "string" && node.className.split(/\s+/).includes(classWanted);
        if (attrMatch) return node.attrs && node.attrs[attrMatch[1]] !== undefined;
        return false;
      };
      const walk = (node) => {
        for (const child of node.children || []) {
          if (matches(child)) out.push(child);
          walk(child);
        }
      };
      walk(el);
      return out;
    },
  };
  return el;
}

function installDom() {
  const doc = { body: fakeElement("body"), head: fakeElement("head"), cookie: "" };
  doc.getElementById = () => null;
  doc.createElement = (tag) => fakeElement(tag);
  doc.createTextNode = (text) => ({ text: String(text), nodeType: 3 });
  doc.querySelectorAll = () => [];
  globalThis.document = doc;
  globalThis.window = {
    document: doc,
    getComputedStyle: () => ({ color: "color(srgb 0.1 0.2 0.3)" }),
    requestAnimationFrame: (callback) => {
      callback();
      return 1;
    },
  };
  return doc;
}

function uninstallDom() {
  delete globalThis.document;
  delete globalThis.window;
}

// --------------------------------------------------------------------------------------------------- tests

test("the plotly substitution lands bound arrays at their paths and escapes every bound value", async () => {
  const plotly = require(resolveStatic("datapipelines-dashboard-plotly.js"));
  const stored = {
    data: [{ type: "bar", x: null, y: null, marker: {} }],
    layout: { title: { text: "Stored title" } },
  };
  const out = plotly._internal.substitute(
    stored,
    {
      "data[0].x": ['<img src=x onerror="alert(1)">', "2026-01"],
      "data[0].y": [10, 20],
      "layout.title.text": "<a href='javascript:alert(1)'>steal</a>",
    },
    plotly._internal.escapeMarkup,
  );
  // The stored configuration is never mutated.
  assert.equal(stored.data[0].x, null);
  assert.equal(stored.layout.title.text, "Stored title");
  // Bound strings arrive escaped: the img, its handler and the javascript: URL are TEXT.
  assert.equal(out.data[0].x[0], "&lt;img src=x onerror=&quot;alert(1)&quot;&gt;".replace(/&quot;/g, '"') === "" ? "" : "&lt;img src=x onerror=\"alert(1)\"&gt;".replace("<", "&lt;").replace(">", "&gt;") || out.data[0].x[0]);
  assert.ok(!out.data[0].x[0].includes("<"), "no raw < from data: " + out.data[0].x[0]);
  assert.ok(out.data[0].x[0].startsWith("&lt;img"), "the tag is escaped text: " + out.data[0].x[0]);
  assert.equal(out.data[0].y[0], 10, "numbers pass through unescaped");
  assert.ok(!out.layout.title.text.includes("<a href"), "the javascript: anchor is escaped text: " + out.layout.title.text);
  // A path that resolves nowhere is skipped, never invented.
  const skipped = plotly._internal.substitute({ data: [] }, { "data[5].x": [1] }, plotly._internal.escapeMarkup);
  assert.deepEqual(skipped.data, []);
});

test("the xss trio renders as text through the plotly adapter's escaping", async () => {
  const plotly = require(resolveStatic("datapipelines-dashboard-plotly.js"));
  for (const nasty of ['<img onerror="alert(1)">', '<a href="javascript:alert(1)">click</a>', "<b>bold</b>"]) {
    const escaped = plotly._internal.escapeMarkup(nasty);
    assert.ok(!escaped.includes("<"), nasty + " survived as markup: " + escaped);
    assert.ok(!escaped.includes("<a") && !escaped.includes("<img") && !escaped.includes("<b>"), "no element survives: " + escaped);
  }
});

test("the theme mapping reads the chart tokens through a probe and never invents a colour", async () => {
  installDom();
  try {
    const plotly = require(resolveStatic("datapipelines-dashboard-plotly.js"));
    let probes = 0;
    const realCreate = globalThis.document.createElement;
    globalThis.document.createElement = (tag) => {
      probes += 1;
      return realCreate(tag);
    };
    globalThis.document.body.removeChild = () => {};
    const theme = plotly._internal.themeLayout({ tokens: { series: "categorical" } });
    assert.ok(probes > 0, "the tokens were resolved through probe elements");
    // getComputedStyle answers color(srgb …); the mapping normalises to rgb() for Plotly's validation.
    assert.match(theme.paper_bgcolor, /^rgb\(/, String(theme.paper_bgcolor));
    assert.match(theme.plot_bgcolor, /^rgb\(/);
    assert.ok(Array.isArray(theme.colorway) && theme.colorway.length === 6, "the six series hues");
    for (const colour of theme.colorway) assert.match(colour, /^rgb\(/);
    // An unknown token name changes nothing (the open map is ignored).
    const unknown = plotly._internal.themeLayout({ tokens: { series: "striped" } });
    assert.equal(unknown.colorway, undefined, "only the known names are honoured");
    // Without a DOM there is no colour and no invention.
    delete globalThis.document;
    const bare = plotly._internal.themeLayout({});
    assert.deepEqual(bare, {});
  } finally {
    uninstallDom();
  }
});

test("#386: the size defaults sit UNDER the author — compact margin, automargin on the axes, the stored keys win", async () => {
  const plotly = require(resolveStatic("datapipelines-dashboard-plotly.js"));
  const { withSizeDefaults, mergeLayout, COMPACT_MARGIN, COMPACT_TITLED_TOP } = plotly._internal;
  // Absent margin: the compact one, every side — never Plotly's own (the key would be absent).
  const bare = withSizeDefaults({});
  assert.deepEqual(bare.margin, { l: COMPACT_MARGIN.l, r: COMPACT_MARGIN.r, t: COMPACT_MARGIN.t, b: COMPACT_MARGIN.b, pad: 0 });
  assert.ok(COMPACT_MARGIN.t < 20 && COMPACT_MARGIN.l < 20, "compact means well under Plotly's 80/100: " + JSON.stringify(COMPACT_MARGIN));
  assert.equal(bare.xaxis.automargin, true, "x automargin defaults on");
  assert.equal(bare.yaxis.automargin, true, "y automargin defaults on");
  assert.deepEqual(withSizeDefaults(undefined).margin, bare.margin, "no stored layout at all is the same case");
  // A title opens the top margin (both title shapes), an empty one does not.
  assert.equal(withSizeDefaults({ title: { text: "Units" } }).margin.t, COMPACT_TITLED_TOP);
  assert.equal(withSizeDefaults({ title: "Units" }).margin.t, COMPACT_TITLED_TOP);
  assert.equal(withSizeDefaults({ title: { text: "" } }).margin.t, COMPACT_MARGIN.t);
  // The author's margin wins KEY BY KEY: t survives, the absent sides take the compact default.
  const stored = { margin: { t: 120 }, title: { text: "Units" }, xaxis: { automargin: false, title: { text: "Month" } } };
  const merged = withSizeDefaults(stored);
  assert.equal(merged.margin.t, 120, "the stored top margin survives the defaults");
  assert.equal(merged.margin.l, COMPACT_MARGIN.l, "an absent side is compact");
  assert.equal(merged.xaxis.automargin, false, "the author's automargin=false stands");
  assert.equal(merged.xaxis.title.text, "Month", "the rest of the axis is untouched");
  assert.equal(merged.yaxis.automargin, true, "the other axis still defaults on");
  assert.equal(stored.margin.l, undefined, "the stored configuration is never mutated");
  assert.equal(stored.yaxis, undefined);
  // A numbered axis the author declared defaults on too.
  assert.equal(withSizeDefaults({ yaxis2: { overlaying: "y" } }).yaxis2.automargin, true);
  // The theme still wins over the author's COLOURS and leaves the size keys alone (the opposite precedence).
  const themed = mergeLayout(merged, { paper_bgcolor: "rgb(1,2,3)", xaxis: { gridcolor: "rgb(4,5,6)" } });
  assert.equal(themed.paper_bgcolor, "rgb(1,2,3)");
  assert.equal(themed.xaxis.gridcolor, "rgb(4,5,6)");
  assert.equal(themed.xaxis.automargin, false, "the theme does not touch automargin");
  assert.equal(themed.margin.t, 120, "the theme does not touch the margin");
});

test("#386: the plotly renderer hands Plotly the compact margin and automargin on every render", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    const plotly = require(resolveStatic("datapipelines-dashboard-plotly.js"));
    plotly.register(runtime);
    const calls = [];
    globalThis.window.Plotly = {
      react: (el, data, layout, config) => {
        calls.push({ layout });
        return Promise.resolve();
      },
      Plots: { resize: () => {} },
      purge: () => {},
    };
    const host = fakeElement("div");
    const handle = runtime._internal.renderers().plotly.create({
      host,
      occurrence: { name: "v", config: { data: [{ type: "bar", x: null }], layout: { margin: { r: 40 } } }, presentation: null },
    });
    assert.equal(await handle.renderData({ name: "v" }, 2, { "data[0].x": [1, 2] }), "rendered");
    const layout = calls[0].layout;
    assert.equal(layout.margin.t, plotly._internal.COMPACT_MARGIN.t, "no title: the compact top");
    assert.equal(layout.margin.r, 40, "the stored right margin wins");
    assert.equal(layout.xaxis.automargin, true);
    assert.equal(layout.yaxis.automargin, true);
  } finally {
    uninstallDom();
  }
});

test("the plotly renderer resolves rendered on the plot and answers no-data for empty rows", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    const plotly = require(resolveStatic("datapipelines-dashboard-plotly.js"));
    plotly.register(runtime);
    const host = fakeElement("div");
    const handle = runtime._internal.renderers().plotly.create({
      host,
      occurrence: { name: "v", config: { data: [{ type: "bar", x: null }] }, presentation: null },
    });
    assert.equal(await handle.renderData({ name: "v" }, 0, {}), "no-data");
    // A fake Plotly: react records the call; the afterplot event races the promise — both settle.
    const calls = [];
    globalThis.window.Plotly = {
      react: (el, data, layout, config) => {
        calls.push({ data, layout, config });
        return Promise.resolve();
      },
      Plots: { resize: () => {} },
      purge: () => {},
    };
    const outcome = await handle.renderData({ name: "v" }, 2, { "data[0].x": ["<b>not markup</b>", "y"] });
    assert.equal(outcome, "rendered");
    assert.equal(calls.length, 1);
    assert.equal(calls[0].data[0].x[0], "&lt;b&gt;not markup&lt;/b&gt;", "the bound markup arrives escaped");
    assert.ok(calls[0].config.responsive, "the shipped config defaults hold");
    assert.equal(calls[0].config.displaylogo, false);
  } finally {
    uninstallDom();
  }
});

test("the table renderer builds text-only cells, formats, caps at page_size, and answers no-data", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    require(resolveStatic("datapipelines-dashboard-table.js")).register(runtime);
    const host = fakeElement("div");
    const handle = runtime._internal.renderers().table.create({
      host,
      occurrence: {
        name: "t",
        config: {
          columns: [
            { label: "<b>Day</b>", values: "d", format: "date" },
            { label: "Amount", values: "a", format: "number", align: "right" },
          ],
          page_size: 2,
        },
      },
    });
    assert.equal(await handle.renderData({ name: "t" }, 0, {}), "no-data");
    const outcome = await handle.renderData(
      { name: "t" },
      3,
      { d: ["2026-09-01", "2026-09-02", "2026-09-03"], a: [1.5, 2, "<script>alert(1)</script>"] },
    );
    assert.equal(outcome, "rendered");
    const table = host.children[0];
    assert.equal(table.tagName, "TABLE");
    const headCells = table.children[0].children[0].children;
    assert.equal(headCells[0].textContent, "<b>Day</b>", "labels are TEXT, not markup");
    assert.ok(!headCells[0].children.length, "the label built no element children");
    const rows = table.children[1].children;
    assert.equal(rows.length, 2, "page_size caps the rows");
    assert.equal(rows[0].children[1].textContent, Number(1.5).toLocaleString());
    assert.equal(rows[0].children[1].style.textAlign, "right");
    // The third row's script is nowhere: every cell's textContent carries the escaped-or-raw TEXT.
    const cells = [];
    for (const row of rows) for (const cell of row.children) cells.push(cell.textContent);
    assert.ok(!cells.some((text) => text.includes("<script>")), "no markup in any cell: " + cells.join("|"));
  } finally {
    uninstallDom();
  }
});

test("the kpi renderer renders a zero, formats, carries the unit and the comparison", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    require(resolveStatic("datapipelines-dashboard-kpi.js")).register(runtime);
    const host = fakeElement("div");
    const handle = runtime._internal.renderers().kpi.create({
      host,
      occurrence: {
        name: "k",
        config: { label: "Revenue", value: "v", format: "currency", unit: "M", comparison: { label: "vs last", value: "c" } },
      },
    });
    assert.equal(await handle.renderData({ name: "k" }, 0, {}), "no-data");
    await handle.renderData({ name: "k" }, 1, { v: [0], c: [1234.5] });
    assert.equal(host.querySelector(".dp-dashboard-kpi-number").textContent, (0).toLocaleString(undefined, { style: "currency", currency: "USD" }), "a zero KPI renders");
    assert.equal(host.querySelector(".dp-dashboard-kpi-unit").textContent, " M");
    assert.equal(host.querySelector(".dp-dashboard-kpi-comparison-label").textContent, "vs last");
    await handle.renderData({ name: "k" }, 1, { v: [9876.54], c: [1] });
    assert.equal(host.querySelector(".dp-dashboard-kpi-number").textContent, (9876.54).toLocaleString(undefined, { style: "currency", currency: "USD" }));
    // A bound string cannot become markup.
    await handle.renderData({ name: "k" }, 1, { v: ["<img onerror=alert(1)>"], c: [1] });
    const rendered = host.querySelector(".dp-dashboard-kpi-number").textContent;
    assert.ok(!rendered.includes("<"), "the kpi value is text: " + rendered);
  } finally {
    uninstallDom();
  }
});

test("#459: table cells use native binding paths with null configuration placeholders", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    require(resolveStatic("datapipelines-dashboard-table.js")).register(runtime);
    const host = fakeElement("div");
    const handle = runtime._internal.renderers().table.create({
      host,
      occurrence: { name: "t", config: { columns: [{ label: "Country", values: null }] } },
    });
    assert.equal(await handle.renderData({ name: "t" }, 2, { "columns[0].values": ["EU", "<img onerror=alert(1)>"] }), "rendered");
    const rows = host.children[0].children[1].children;
    assert.equal(rows.length, 2);
    assert.equal(rows[0].children[0].textContent, "EU");
    assert.equal(rows[1].children[0].textContent, "<img onerror=alert(1)>");
    assert.equal(rows[1].children[0].children.length, 0);
  } finally {
    uninstallDom();
  }
});

test("#459: KPI values and comparison use native binding paths", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    require(resolveStatic("datapipelines-dashboard-kpi.js")).register(runtime);
    const host = fakeElement("div");
    const handle = runtime._internal.renderers().kpi.create({
      host,
      occurrence: { name: "k", config: { label: "Total", value: null, format: "integer", comparison: { label: "Previous", value: null } } },
    });
    assert.equal(await handle.renderData({ name: "k" }, 1, { value: [0], "comparison.value": [10] }), "rendered");
    assert.equal(host.querySelector(".dp-dashboard-kpi-number").textContent, "0");
    assert.equal(host.querySelector(".dp-dashboard-kpi-comparison-value").textContent, "10");
  } finally {
    uninstallDom();
  }
});

test("the composite adapter builds the grid, mounts renderers by kind and renders status text-only", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    runtime._internal.resetRenderers();
    const mounted = [];
    runtime.registerRenderer({
      kind: "plotly",
      version: "4",
      create: (context) => {
        mounted.push(context.occurrence.name);
        return { renderData: () => Promise.resolve("rendered"), dispose: () => {} };
      },
    });
    const container = fakeElement("div");
    const adapter = runtime.adapters(container);
    await adapter.mountLayout({ columns: 12, grid: [{ name: "chart", x: 0, y: 0, w: 6, h: 4 }] });
    assert.equal(container.children[0].style.gridTemplateColumns, "repeat(12, minmax(0, 1fr))");
    await adapter.mountVisualization({ name: "chart", renderer: { kind: "plotly", version: "4" }, config: {} }, { kind: "plotly", version: "4" });
    assert.deepEqual(mounted, ["chart"]);
    // An unknown kind refuses at mount.
    await assert.rejects(
      () => adapter.mountVisualization({ name: "x", renderer: { kind: "svg", version: "1" }, config: {} }, { kind: "svg", version: "1" }),
      (error) => error.code === "renderer.unsupported",
    );
    // Status: text only; the data flows through the per-kind handle.
    adapter.renderStatus({ name: "chart", type: "visualization" }, { state: "error", stale: false, reason: { code: "boom" } });
    const chip = container.querySelectorAll("[data-dp-state]")[0];
    assert.ok(chip, "a status chip exists");
    assert.ok(chip.textContent.includes("error") && chip.textContent.includes("boom"), chip.textContent);
    assert.ok(!chip.innerHTML, "the chip is text-built");
    // The contract's readSelections answers every rendered control's TYPED wire value.
    await adapter.renderParameters(compositeState());
    assert.deepEqual(adapter.readSelections(), { year: 2026 });
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

/** A ResizeObserver stand-in whose deliveries carry the host's content width. */
function installResizeObserver() {
  const observers = [];
  class FakeResizeObserver {
    constructor(callback) {
      this.callback = callback;
      this.targets = [];
      this.disconnectCount = 0;
      observers.push(this);
    }
    observe(target) {
      this.targets.push(target);
    }
    disconnect() {
      this.targets = [];
      this.disconnectCount += 1;
    }
    fire(width) {
      this.targets.forEach((target) => {
        target.clientWidth = width;
        this.callback([{ target, contentRect: { width } }]);
      });
    }
  }
  globalThis.window.ResizeObserver = FakeResizeObserver;
  return {
    observers,
    observed() {
      return observers.reduce((count, observer) => count + observer.targets.length, 0);
    },
    fire(width) {
      observers.forEach((observer) => observer.fire(width));
    },
  };
}

const SEEDED_GRID = [
  { name: "slowchart", x: 3, y: 4, w: 9, h: 2 },
  { name: "revenue", x: 0, y: 0, w: 6, h: 4 },
  { name: "total", x: 0, y: 4, w: 3, h: 2 },
  { name: "cells", x: 6, y: 0, w: 6, h: 4 },
];

const placement = (container, name) => {
  const slot = container.querySelectorAll("[data-dp-slot]").find((el) => el.getAttribute("data-dp-slot") === name);
  const s = slot.style;
  return [s.gridColumnStart, s.gridColumnEnd, s.gridRowStart, s.gridRowEnd].join(" / ");
};

test("#412: below breakpoint_px on the host width every item spans full width in grid order", async () => {
  installDom();
  try {
    const observer = installResizeObserver();
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    runtime._internal.resetRenderers();
    const container = fakeElement("div");
    container.clientWidth = 639;
    const adapter = runtime.adapters(container);
    await adapter.mountLayout({ columns: 12, grid: SEEDED_GRID });
    assert.equal(observer.observers.length, 1, "one observer watches the host");
    assert.equal(container.children[0].style.gridTemplateColumns, "repeat(12, minmax(0, 1fr))", "the track list stands; the items span it");
    // Grid order is row, then column — NOT the configuration's array order (slowchart is listed first).
    assert.equal(placement(container, "revenue"), "1 / span 12 / 1 / span 4");
    assert.equal(placement(container, "cells"), "1 / span 12 / 5 / span 4");
    assert.equal(placement(container, "total"), "1 / span 12 / 9 / span 2");
    assert.equal(placement(container, "slowchart"), "1 / span 12 / 11 / span 2");
    const defaultSlot = container.children[0].children.find((el) => el.getAttribute("data-dp-slot") === null);
    assert.equal(defaultSlot.style.gridColumnEnd, "span 12", "the default slot is full width too");
    assert.equal(defaultSlot.style.gridRowStart, undefined, "the default slot is never placed: it auto-flows after the items");
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

test("#412: host width crossings re-place and resize once per crossing, then dispose disconnects", async () => {
  installDom();
  try {
    const observer = installResizeObserver();
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    runtime._internal.resetRenderers();
    const resized = [];
    runtime.registerRenderer({
      kind: "plotly",
      version: "4",
      create: (context) => ({ renderData: () => Promise.resolve("rendered"), resize: () => resized.push(context.occurrence.name) }),
    });
    const container = fakeElement("div");
    container.clientWidth = 640;
    const adapter = runtime.adapters(container);
    await adapter.mountLayout({ columns: 12, grid: SEEDED_GRID });
    await adapter.mountVisualization({ name: "revenue", renderer: { kind: "plotly", version: "4" }, config: {} }, { kind: "plotly", version: "4" });
    await adapter.mountVisualization({ name: "slowchart", renderer: { kind: "plotly", version: "4" }, config: {} }, { kind: "plotly", version: "4" });
    const stored = { revenue: "1 / span 6 / 1 / span 4", cells: "7 / span 6 / 1 / span 4", total: "1 / span 3 / 5 / span 2", slowchart: "4 / span 9 / 5 / span 2" };
    for (const [name, expected] of Object.entries(stored)) assert.equal(placement(container, name), expected, name + " at 640 host px");
    assert.equal(observer.observed(), 1, "the composite host is observed");
    observer.fire(639);
    assert.equal(placement(container, "slowchart"), "1 / span 12 / 11 / span 2", "below: collapsed by the listener");
    assert.deepEqual(resized, ["revenue", "slowchart"], "the crossing resized each mounted renderer once");
    observer.fire(600);
    assert.deepEqual(resized, ["revenue", "slowchart"], "a second observation on the same side does not resize again");
    observer.fire(640);
    for (const [name, expected] of Object.entries(stored)) assert.equal(placement(container, name), expected, name + " restored");
    assert.deepEqual(resized, ["revenue", "slowchart", "revenue", "slowchart"]);
    const defaultSlot = container.children[0].children.find((el) => el.getAttribute("data-dp-slot") === null);
    assert.equal(defaultSlot.style.gridColumnEnd, "", "the default slot's span is cleared above the breakpoint");
    // Teardown: dispose removes the listener; a later crossing touches nothing.
    const slowchartSlot = container.querySelectorAll("[data-dp-slot]").find((el) => el.getAttribute("data-dp-slot") === "slowchart");
    const placementBeforeDispose = Object.assign({}, slowchartSlot.style);
    adapter.dispose();
    assert.equal(observer.observed(), 0, "dispose disconnected the observer");
    observer.fire(500);
    assert.deepEqual(slowchartSlot.style, placementBeforeDispose, "no placement after dispose");
    assert.deepEqual(resized, ["revenue", "slowchart", "revenue", "slowchart"], "no resize after dispose");
  } finally {
    uninstallDom();
  }
});

test("#412: breakpoint_px remains a clamped number and uses host width", async () => {
  installDom();
  try {
    installResizeObserver();
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    runtime._internal.resetRenderers();
    const mountAt = async (width, layout) => {
      const container = fakeElement("div");
      container.clientWidth = width;
      const adapter = runtime.adapters(container);
      await adapter.mountLayout(Object.assign({ columns: 12, grid: SEEDED_GRID }, layout));
      const result = { revenue: placement(container, "revenue") };
      adapter.dispose();
      return result;
    };
    assert.equal((await mountAt(499, { breakpoint_px: 500 })).revenue, "1 / span 12 / 1 / span 4", "499 < 500: collapsed");
    assert.equal((await mountAt(500, { breakpoint_px: 500 })).revenue, "1 / span 6 / 1 / span 4", "500: the stored grid");
    assert.equal((await mountAt(700, { breakpoint_px: 500 })).revenue, "1 / span 6 / 1 / span 4", "the configured threshold decides");
    assert.equal((await mountAt(640, {})).revenue, "1 / span 6 / 1 / span 4", "the 640 default is exclusive");
    assert.equal((await mountAt(639, {})).revenue, "1 / span 12 / 1 / span 4", "the default is 640");
    assert.equal((await mountAt(800, { breakpoint_px: 0 })).revenue, "1 / span 6 / 1 / span 4", "zero clamps to 1");
    assert.equal((await mountAt(800, { breakpoint_px: 20000 })).revenue, "1 / span 12 / 1 / span 4", "large numbers clamp to 10000");
    assert.equal((await mountAt(639, { breakpoint_px: "500px), (min-width: 0" })).revenue, "1 / span 12 / 1 / span 4", "strings use the default");
    assert.equal((await mountAt(639, { breakpoint_px: null })).revenue, "1 / span 12 / 1 / span 4", "null uses the default");
  } finally {
    uninstallDom();
  }
});

test("#412: a zero-width host holds the stored grid until it is revealed", async () => {
  installDom();
  try {
    const observer = installResizeObserver();
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    runtime._internal.resetRenderers();
    const container = fakeElement("div");
    const adapter = runtime.adapters(container);
    await adapter.mountLayout({ columns: 12, grid: SEEDED_GRID });
    assert.equal(placement(container, "slowchart"), "4 / span 9 / 5 / span 2");
    observer.fire(0);
    assert.equal(placement(container, "slowchart"), "4 / span 9 / 5 / span 2", "zero is not treated as narrow");
    observer.fire(639);
    assert.equal(placement(container, "slowchart"), "1 / span 12 / 11 / span 2", "revealing the host applies the stored breakpoint");
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

test("#412: without ResizeObserver the stored grid always holds", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    runtime._internal.resetRenderers();
    const container = fakeElement("div");
    container.clientWidth = 300;
    const adapter = runtime.adapters(container);
    await adapter.mountLayout({ columns: 12, grid: SEEDED_GRID });
    assert.equal(placement(container, "slowchart"), "4 / span 9 / 5 / span 2");
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});

/** One SELECT SINGLE (dropdown) parameter exactly as the writer sends it. */
function compositeState(overrides) {
  return Object.assign(
    {
      valid: true,
      values: { year: 2026 },
      parameters: [
        {
          name: "year",
          label: "Year",
          type: "INTEGER",
          kind: "SELECT",
          cardinality: "SINGLE",
          required: false,
          depends_on: [],
          presentation: { control: "dropdown" },
          dependents: [],
          state: {
            value: 2026,
            origin: "default",
            computed_default: 2026,
            reset: false,
            hidden: false,
            disabled: false,
            options: [
              { value: 2025, display_value: "2025", is_default: false },
              { value: 2026, display_value: "2026", is_default: true },
            ],
            errors: [],
          },
        },
      ],
      overrides_applied: {},
      parents: ["year"],
      parameter_revision: 3,
    },
    overrides || {},
  );
}

test("repeated evaluations REPLACE the parameter rows: no duplicates, no stale listeners, typed round trip", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    const container = fakeElement("div");
    const adapter = runtime.adapters(container);
    await adapter.mountLayout({ columns: 12, grid: [] });
    const commits = [];
    adapter.onCommit((enriched) => commits.push(enriched.name));
    await adapter.renderParameters(compositeState());
    await adapter.renderParameters(compositeState({ parameter_revision: 4 }));
    const rows = container.querySelectorAll("[data-dp-parameter]");
    assert.equal(rows.length, 1, "two renders, exactly ONE row: " + rows.length);
    // The rebuilt row's control still round-trips the TYPED value (an INTEGER stays a number).
    assert.deepEqual(adapter.readSelections(), { year: 2026 });
    // The rebuilt row's listener is LIVE: a change on the CURRENT control reports one commit.
    const select = rows[0].children[1];
    select.value = "0"; // the 2025 option (its INDEX into the wire values)
    select.fire("change");
    assert.deepEqual(commits, ["year"], "the rebuilt row's gesture is wired");
    assert.deepEqual(adapter.readSelections(), { year: 2025 }, "the typed value came back through the wire table");
    // A change on the ROW element (no listener lives there) commits nothing extra.
    rows[0].fire("change");
    assert.deepEqual(commits, ["year"], "no duplicate commit");
  } finally {
    uninstallDom();
  }
});

test("control types follow the definition: MULTI checkboxes read an array, INPUT reads typed text, radio renders radios", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    const container = fakeElement("div");
    const adapter = runtime.adapters(container);
    await adapter.mountLayout({ columns: 12, grid: [] });
    const state = compositeState();
    const multi = JSON.parse(JSON.stringify(state.parameters[0]));
    multi.name = "regions";
    multi.label = "Regions";
    multi.cardinality = "MULTI";
    multi.presentation = { control: "checkboxes" };
    multi.state.value = ["EU"];
    multi.state.options = [
      { value: "EU", display_value: "Europe", is_default: true },
      { value: "US", display_value: "United States", is_default: false },
    ];
    const free = JSON.parse(JSON.stringify(state.parameters[0]));
    free.name = "limit";
    free.label = "Limit";
    free.type = "INTEGER";
    free.kind = "INPUT";
    delete free.presentation;
    free.state.value = 42;
    free.state.options = null;
    const radioParam = JSON.parse(JSON.stringify(state.parameters[0]));
    radioParam.name = "grade";
    radioParam.label = "Grade";
    radioParam.presentation = { control: "radio" };
    radioParam.state.value = "B";
    radioParam.state.options = [
      { value: "A", display_value: "Alpha", is_default: false },
      { value: "B", display_value: "Beta", is_default: true },
    ];
    state.parameters = [state.parameters[0], multi, free, radioParam];
    state.values = { year: 2026, regions: ["EU"], limit: 42, grade: "B" };
    await adapter.renderParameters(state);
    const reads = adapter.readSelections();
    assert.deepEqual(reads.year, 2026);
    assert.deepEqual(reads.regions, ["EU"], "a MULTI reads the checked TYPED values as an array");
    assert.deepEqual(reads.limit, 42, "an INPUT's text parsed to the wire's number");
    assert.deepEqual(reads.grade, "B", "the radio group reads the TYPED selected value");
    // Toggle the second region box and change the free text: the reads follow, typed.
    const rows = container.querySelectorAll("[data-dp-parameter]");
    const regionBoxes = rows[1].children.filter((child) => child.tagName === "INPUT");
    regionBoxes[1].checked = true;
    assert.deepEqual(adapter.readSelections().regions, ["EU", "US"]);
    const limitInput = rows[2].children[1];
    limitInput.value = "not-a-number";
    assert.equal(adapter.readSelections().limit, "not-a-number", "unparsable text travels AS TEXT for the server to judge");
    limitInput.value = "7";
    assert.deepEqual(adapter.readSelections().limit, 7);
    limitInput.value = "";
    assert.equal(adapter.readSelections().limit, null, "an empty INPUT reads null");
  } finally {
    uninstallDom();
  }
});

/** The composite state with its one parameter reshaped as a BOOLEAN INPUT at [value]. */
function booleanInputState(value) {
  const state = compositeState();
  Object.assign(state.parameters[0], {
    name: "enabled",
    label: "Enabled",
    type: "BOOLEAN",
    kind: "INPUT",
    cardinality: "SINGLE",
    presentation: null,
  });
  state.parameters[0].state.value = value;
  state.parameters[0].state.options = null;
  state.values = { enabled: value };
  return state;
}

/** The composite state with its one parameter presented as a radio group. */
function radioState() {
  const state = compositeState();
  state.parameters[0].presentation = { control: "radio" };
  return state;
}

test("a BOOLEAN INPUT renders the house tri-state select and preserves unresolved null", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    const container = fakeElement("div");
    const adapter = runtime.adapters(container);
    await adapter.mountLayout({ columns: 12, grid: [] });
    await adapter.renderParameters(booleanInputState(null));
    const select = container.querySelectorAll("[data-dp-parameter]")[0].children[1];
    assert.equal(select.tagName, "SELECT", "the house tri-state control, not a text input");
    const optionValues = select.children.map((option) => option.getAttribute("value"));
    assert.deepEqual(optionValues, ["", "true", "false"], "exactly the three wire states: " + optionValues.join("|"));
    assert.equal(select.value, "", "an unresolved null displays as the unset option");
    assert.equal(adapter.readSelections().enabled, null, "the unresolved null reads null — never false");
    // Every one of the three displayed states reads back as its wire value.
    select.value = "true";
    const committed = adapter.readSelections().enabled;
    assert.equal(committed, true, "the visible true reads the wire boolean true");
    assert.equal(typeof committed, "boolean", "a JSON boolean, not the string \"true\"");
    select.value = "false";
    assert.equal(adapter.readSelections().enabled, false, "the visible false reads the wire boolean false");
    select.value = "";
    assert.equal(adapter.readSelections().enabled, null, "unset reads null again");
    // A re-render from a false state displays false (the control follows the server's state).
    await adapter.renderParameters(booleanInputState(false));
    const rebuilt = container.querySelectorAll("[data-dp-parameter]")[0].children[1];
    assert.equal(rebuilt.value, "false");
    assert.equal(adapter.readSelections().enabled, false);
  } finally {
    uninstallDom();
  }
});

test("a BOOLEAN INPUT keeps its disabled and hidden state semantics (D23)", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    const container = fakeElement("div");
    const adapter = runtime.adapters(container);
    await adapter.mountLayout({ columns: 12, grid: [] });
    // Disabled: the control is disabled and STILL reads (D23).
    const disabled = booleanInputState(true);
    disabled.parameters[0].state.disabled = true;
    await adapter.renderParameters(disabled);
    let row = container.querySelectorAll("[data-dp-parameter]")[0];
    assert.equal(row.children[1].disabled, true, "the tri-state select is disabled");
    assert.equal(adapter.readSelections().enabled, true, "the disabled value still reads");
    // Hidden: the row is display:none and STILL reads.
    const hidden = booleanInputState(false);
    hidden.parameters[0].state.hidden = true;
    await adapter.renderParameters(hidden);
    row = container.querySelectorAll("[data-dp-parameter]")[0];
    assert.equal(row.style.display, "none", "the hidden row is not displayed");
    assert.equal(adapter.readSelections().enabled, false, "the hidden value still reads");
  } finally {
    uninstallDom();
  }
});

test("radio groups are instance-owned: one group per adapter and parameter, never across adapters", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    const groupNames = (host) =>
      host
        .querySelectorAll("[data-dp-parameter]")[0]
        .children.filter((child) => child.tagName === "INPUT")
        .map((radio) => radio.getAttribute("name"));
    const hostA = fakeElement("div");
    const a = runtime.adapters(hostA);
    await a.mountLayout({ columns: 12, grid: [] });
    await a.renderParameters(radioState());
    const groupA = groupNames(hostA);
    assert.ok(groupA.length >= 2, "the radio group rendered its options");
    assert.ok(groupA.every((name) => name === groupA[0]), "one parameter's radios share ONE name within the instance: " + groupA.join("|"));
    // A SECOND adapter in the same document: a DIFFERENT group name for the same parameter.
    const hostB = fakeElement("div");
    const b = runtime.adapters(hostB);
    await b.mountLayout({ columns: 12, grid: [] });
    await b.renderParameters(radioState());
    const groupB = groupNames(hostB);
    assert.notEqual(groupA[0], groupB[0], "two adapters never publish the same radio group name");
    // A re-render rebuilds the group under the SAME instance-owned name (grouping survives).
    await a.renderParameters(radioState());
    assert.deepEqual(groupNames(hostA), groupA, "the re-rendered group kept its name");
    // Dispose and re-mount: a fresh adapter owns a fresh name.
    a.dispose();
    const a2 = runtime.adapters(hostA);
    await a2.mountLayout({ columns: 12, grid: [] });
    await a2.renderParameters(radioState());
    assert.notEqual(groupNames(hostA)[0], groupA[0], "the re-mounted adapter publishes a fresh group name");
  } finally {
    uninstallDom();
  }
});

test("hidden and disabled parameters still render and still read (D23); overrides_applied wins; errors are text", async () => {  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    const container = fakeElement("div");
    const adapter = runtime.adapters(container);
    await adapter.mountLayout({ columns: 12, grid: [] });
    const state = compositeState();
    state.parameters[0].state.hidden = false;
    state.parameters[0].state.disabled = false;
    state.overrides_applied = { year: { visible: false, enabled: false } };
    await adapter.renderParameters(state);
    const row = container.querySelectorAll("[data-dp-parameter]")[0];
    assert.equal(row.style.display, "none", "the override hid the row");
    assert.deepEqual(adapter.readSelections(), { year: 2026 }, "the hidden value is still read");
    const select = row.children[1];
    assert.equal(select.disabled, true, "the override disabled the control");
    // The engine's own flags (no override): hidden rows render and read; disabled controls too.
    const state2 = compositeState();
    state2.parameters[0].state.hidden = true;
    state2.parameters[0].state.disabled = true;
    await adapter.renderParameters(state2);
    const row2 = container.querySelectorAll("[data-dp-parameter]")[0];
    assert.equal(row2.style.display, "none");
    assert.deepEqual(adapter.readSelections(), { year: 2026 }, "a disabled control's value still reads");
    // #498 — per-parameter errors render as accessible TEXT: the sentence first, the catalogued
    // code after it in the runtime's own <code> span (data-dp-code carries the codes). The only
    // element child is that span — the old `!problem.innerHTML` pin restated structurally, so a
    // future innerHTML write cannot pass while the fake's property stays null.
    const state3 = compositeState({ valid: false });
    state3.parameters[0].state.errors = [{ code: "parameter.evaluate.required", message: "a value is required", details: {} }];
    await adapter.renderParameters(state3);
    const problem = container.querySelectorAll(".dp-dashboard-parameter-error")[0];
    assert.ok(problem, "an error element exists");
    assert.ok(problem.textContent.includes("a value is required"), problem.textContent);
    assert.ok(problem.textContent.includes("parameter.evaluate.required"), problem.textContent);
    assert.ok(
      problem.textContent.indexOf("a value is required") < problem.textContent.indexOf("parameter.evaluate.required"),
      "the sentence precedes the code",
    );
    assert.deepEqual(
      [...problem.children].filter((c) => c.nodeType !== 3).map((c) => c.tagName),
      ["CODE"],
      "the only element child is the runtime's own code span",
    );
    assert.equal(problem.getAttribute("data-dp-code"), "parameter.evaluate.required");
    // A markup-looking message stays text: it produces no element, and the raw string is preserved.
    const state4 = compositeState({ valid: false });
    state4.parameters[0].state.errors = [{ code: "parameter.evaluate.required", message: '<img src=x onerror="alert(1)">', details: {} }];
    await adapter.renderParameters(state4);
    const problem4 = container.querySelectorAll(".dp-dashboard-parameter-error")[0];
    assert.deepEqual(
      [...problem4.children].filter((c) => c.nodeType !== 3).map((c) => c.tagName),
      ["CODE"],
      "the injected-looking message produced no element",
    );
    assert.ok(problem4.textContent.includes('<img src=x onerror="alert(1)">'), problem4.textContent);
    // While the row is in error, its design-system control wears the vendored error treatment;
    // a fresh render rebuilds the row without it (#498).
    const row3 = container.querySelectorAll("[data-dp-parameter]")[0];
    const control3 = row3.querySelectorAll(".ds-input")[0];
    assert.ok(/\bds-input-error\b/.test(control3.className), control3.className);
    await adapter.renderParameters(compositeState());
    const controlClean = container.querySelectorAll("[data-dp-parameter]")[0].querySelectorAll(".ds-input")[0];
    assert.ok(!/\bds-input-error\b/.test(controlClean.className), "a fresh render rebuilds the row without the error class");
  } finally {
    uninstallDom();
  }
});

test("composite dispose removes the mounted DOM; a host sibling stands; re-mount leaves exactly one set", async () => {
  installDom();
  try {
    const runtime = require(resolveStatic("datapipelines-dashboard.js"));
    runtime._internal.resetRenderers();
    runtime.registerRenderer({ kind: "plotly", version: "4", create: () => ({ renderData: () => Promise.resolve("rendered") }) });
    const container = fakeElement("div");
    const neighbour = fakeElement("div");
    neighbour.setAttribute("id", "host-owned");
    container.appendChild(neighbour);
    const adapter = runtime.adapters(container);
    await adapter.mountLayout({ columns: 12, grid: [] });
    await adapter.mountVisualization({ name: "v", renderer: { kind: "plotly", version: "4" }, config: {} }, { kind: "plotly", version: "4" });
    await adapter.renderParameters(compositeState());
    const mountedSets = () => container.querySelectorAll(".dp-dashboard").length;
    assert.equal(mountedSets(), 1, "one layout mounted");
    assert.ok(container.querySelectorAll(".dp-dashboard-parameters").length === 1, "one parameters pane");
    adapter.dispose();
    assert.equal(mountedSets(), 0, "the composite removed its grid");
    assert.equal(container.querySelectorAll(".dp-dashboard-parameters").length, 0, "the parameters pane went too");
    assert.ok(container.children.indexOf(neighbour) !== -1, "the host's own DOM stands");
    // A re-mount after disposal: exactly one fresh set, no leftovers.
    const second = runtime.adapters(container);
    await second.mountLayout({ columns: 12, grid: [] });
    await second.renderParameters(compositeState());
    assert.equal(mountedSets(), 1);
    assert.equal(container.querySelectorAll(".dp-dashboard-parameters").length, 1);
  } finally {
    uninstallDom();
  }
});
