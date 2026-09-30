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
    textContent: "",
    innerHTML: null, // written by NOBODY under test — the rendering rule
    scope: null,
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
    // The contract's readSelections answers every rendered control's value.
    await adapter.renderParameters({
      parameters: [{ definition: { name: "year", label: "Year" }, value: 2026, state: { hidden: false, disabled: false }, options: [{ value: 2026, display_value: "2026" }] }],
    });
    assert.deepEqual(adapter.readSelections(), { year: "2026" });
    adapter.dispose();
  } finally {
    uninstallDom();
  }
});
