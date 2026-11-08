// The chart kind (design record 2026-11-06 §5.2): the library-neutral core, the converter registry and mount
// judgement, the chart adapter's handle, and the Plotly converter's text, layout and host-side rules — on Node's
// built-in runner with a minimal fake DOM. The runtime's side (options.charts, warnings, the zero-row empty state)
// is in dashboard-runtime.test.mjs; the browser draws are BarChartBrowserTest's.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const resolveStatic = (name) => path.resolve(here, "../../main/resources/static/js/" + name);

const chart = require(resolveStatic("datapipelines-dashboard-chart.js"));
const plotly = require(resolveStatic("datapipelines-dashboard-plotly.js"));
plotly.register(chart);
const { convert, labelWidth, MARGIN_RIGHT, LABEL_PAD_PX } = plotly._internal;
const TOKENS = chart._internal.DEFAULT_TOKENS;

// ---------------------------------------------------------------------------------------------- fake DOM

function fakeElement(tag) {
  const attrs = {};
  const children = [];
  const listeners = {};
  const el = {
    tagName: (tag || "div").toUpperCase(),
    attrs,
    children,
    style: {},
    parentNode: null,
    set innerHTML(v) {
      throw new Error("innerHTML written: " + v);
    },
    setAttribute(k, v) {
      attrs[k] = String(v);
    },
    getAttribute(k) {
      return k in attrs ? attrs[k] : null;
    },
    appendChild(child) {
      child.parentNode = el;
      children.push(child);
      return child;
    },
    removeChild(child) {
      const index = children.indexOf(child);
      if (index !== -1) children.splice(index, 1);
      child.parentNode = null;
      return child;
    },
    addEventListener(type, fn) {
      (listeners[type] ||= []).push(fn);
    },
    fire(type) {
      (listeners[type] || []).splice(0).forEach((fn) => fn({}));
    },
  };
  return el;
}

/** A document whose cascade answers [declared] custom properties; every other property is unset (""). */
function installDom(declared, lang) {
  const created = [];
  const doc = {
    body: fakeElement("body"),
    documentElement: { getAttribute: (k) => (k === "lang" ? lang || null : null) },
    createElement: (tag) => {
      const el = fakeElement(tag);
      created.push(el);
      return el;
    },
  };
  const resolve = (value) => {
    const match = /^var\((--[a-z0-9-]+)\)$/.exec(value || "");
    return match ? declared[match[1]] : undefined;
  };
  globalThis.document = doc;
  globalThis.window = {
    document: doc,
    getComputedStyle: (el) =>
      el.tagName === "SPAN"
        ? { color: resolve(el.style.color), fontFamily: resolve(el.style.fontFamily) }
        : { getPropertyValue: (property) => declared[property] || "" },
  };
  return { doc, created };
}

function uninstallDom() {
  delete globalThis.document;
  delete globalThis.window;
}

// ---------------------------------------------------------------------------------------------- fixtures

const BAR = {
  mark: { type: "bar" },
  encoding: { x: { field: "region", type: "nominal", sort: null }, y: { field: "amount", type: "quantitative", aggregate: "sum" } },
};
const HORIZONTAL_LABELLED = {
  mark: { type: "bar", labels: "values" },
  encoding: { y: { field: "region", type: "nominal", sort: null }, x: { field: "amount", type: "quantitative", aggregate: "sum" } },
};
const COLUMNS = { region: "STRING", amount: "DECIMAL" };
const spec = (config, columns) => ({ display_name: "Sales", description: null, config, columns: columns || COLUMNS });

/** Every string anywhere in [value], with its path. */
function strings(value, at, out) {
  out = out || [];
  if (typeof value === "string") out.push([at, value]);
  else if (Array.isArray(value)) value.forEach((v, i) => strings(v, at + "[" + i + "]", out));
  else if (value && typeof value === "object") Object.keys(value).forEach((k) => strings(value[k], at + "." + k, out));
  return out;
}

const valueArrays = (option) => option.data.map((trace) => (trace.orientation === "h" ? trace.x : trace.y));

// ------------------------------------------------------------------------------------------------- tests

test("the core orders by the column's type and keeps exact values exact: BIGINTEGER 2 before 10, two values float64 cannot tell apart, the mean HALF-EVEN at scale + 6", () => {
  const byType = (type, values) =>
    chart.core.model(
      spec({ mark: { type: "bar" }, encoding: { x: { field: "n", type: "nominal", sort: "ascending" }, y: { type: "quantitative", aggregate: "count" } } }, { n: type }),
      { n: values },
      TOKENS,
    ).categories.map((k) => k.full);
  assert.deepEqual(byType("BIGINTEGER", ["10", "2"]), ["2", "10"], "an exact column orders by value");
  assert.deepEqual(byType("STRING", ["10", "2"]), ["10", "2"], "a text column orders by code point");
  assert.deepEqual(byType("BIGINTEGER", ["9007199254740993", "9007199254740992"]), ["9007199254740992", "9007199254740993"]);
  const mean = chart.core.model(
    spec({ mark: { type: "bar" }, encoding: { x: { field: "b", type: "nominal", sort: null }, y: { field: "v", type: "quantitative", aggregate: "mean" } } }, { b: "STRING", v: "BIGDECIMAL" }),
    { b: ["all", "all", "all"], v: ["1", "1", "0"] },
    TOKENS,
  );
  assert.equal(mean.cells[0][0].aggregate, "0.666667", "rounded, not truncated past scale + 6");
  const tie = chart.core.model(
    spec({ mark: { type: "bar" }, encoding: { x: { field: "b", type: "nominal", sort: null }, y: { field: "v", type: "quantitative", aggregate: "mean" } } }, { b: "STRING", v: "BIGDECIMAL" }),
    { b: Array(128).fill("all"), v: ["1"].concat(Array(127).fill("0")) },
    TOKENS,
  );
  assert.equal(tie.cells[0][0].aggregate, "0.007812", "1/128 = 0.0078125 is a tie: HALF-EVEN keeps the even digit");
});

test("no-data (c): empty columns convert to the no-marks option - no drawn value, the no-data warning - and so does a chart whose every measure is null", () => {
  const empty = convert(spec(BAR), {}, TOKENS);
  assert.ok(valueArrays(empty.option).every((values) => values.every((v) => v === null)), JSON.stringify(empty.option.data));
  assert.ok(empty.warnings.some((w) => w.field === "rule.no-data"), JSON.stringify(empty.warnings));
  const nulls = convert(spec(BAR), { region: ["North", "South"], amount: [null, null] }, TOKENS);
  assert.ok(valueArrays(nulls.option).every((values) => values.every((v) => v === null)));
  assert.ok(nulls.warnings.some((w) => w.field === "rule.no-data"), "no marks is no data, as the server's mark_count says");
  const drawn = convert(spec(BAR), { region: ["North"], amount: [1] }, TOKENS);
  assert.ok(!drawn.warnings.some((w) => w.field === "rule.no-data"));
});

test("every Plotly sink is entity-escaped: names, tick text, hover text, titles, subtitles, legend titles and bar text", () => {
  const trio = ['<img src=x onerror="alert(1)">', '<a href="javascript:alert(1)">click</a>', "<b>bold</b> & 'q'"];
  const config = {
    mark: { type: "bar", labels: "values" },
    title: { text: trio[1], subtitle: trio[2] },
    encoding: {
      x: { field: "region", type: "nominal", sort: null, title: trio[0] },
      y: { field: "amount", type: "quantitative", aggregate: "sum", title: trio[2] },
      color: { field: "segment", type: "nominal", sort: null, title: trio[1] },
    },
  };
  const out = convert(spec(config, { region: "STRING", segment: "STRING", amount: "DECIMAL" }), { region: trio, segment: trio, amount: [1, 2, 3] }, TOKENS);
  const all = strings(out.option, "option");
  assert.ok(all.length > 20, "the walk saw the option's strings: " + all.length);
  for (const [at, text] of all) {
    assert.ok(!/[<>"]/.test(text) || at === "option.layout.font.family", at + " carries raw markup: " + text);
    assert.ok(!/&(?!amp;|lt;|gt;|quot;|#39;)/.test(text), at + " carries a raw ampersand: " + text);
  }
  // The texts are still THERE, as text: the sinks are escaped, not emptied.
  assert.ok(all.some(([, text]) => text.includes("&lt;img src=x onerror=&quot;alert(1)&quot;&gt;")));
  assert.equal(out.option.layout.title.text, "&lt;a href=&quot;javascript:alert(1)&quot;&gt;click&lt;/a&gt;");
  // Categories are positional ids, never a parsed value.
  assert.deepEqual(out.option.data[0].x, ["c0", "c1", "c2"]);
});

test("Q-L4: a horizontal bar's outside value labels widen the right margin to the widest label; a vertical, stacked or unlabelled bar keeps the reference margin", () => {
  const bindings = { region: ["North", "South"], amount: [1234567.891, 12] };
  const horizontal = convert(spec(HORIZONTAL_LABELLED), bindings, TOKENS);
  const texts = horizontal.option.data[0].text;
  const widest = Math.max(...texts.map((t) => labelWidth(t)));
  assert.equal(horizontal.option.layout.margin.r, widest + LABEL_PAD_PX, JSON.stringify(texts));
  assert.ok(horizontal.option.layout.margin.r > MARGIN_RIGHT, "the label needs more than the reference margin");
  assert.equal(horizontal.option.data[0].cliponaxis, false, "the label may leave the plotting rectangle");
  assert.equal(horizontal.option.data[0].textposition, "outside");
  // Only the value axis's range and the margin move: the rest of the layout is the reference's.
  const vertical = convert(spec(Object.assign({}, BAR, { mark: { type: "bar", labels: "values" } })), bindings, TOKENS);
  assert.equal(vertical.option.layout.margin.r, MARGIN_RIGHT);
  const unlabelled = convert(spec(Object.assign({}, HORIZONTAL_LABELLED, { mark: { type: "bar" } })), bindings, TOKENS);
  assert.equal(unlabelled.option.layout.margin.r, MARGIN_RIGHT);
  const stacked = convert(
    spec(
      {
        mark: { type: "bar", labels: "values" },
        encoding: {
          y: { field: "region", type: "nominal", sort: null },
          x: { field: "amount", type: "quantitative", aggregate: "sum", stack: "zero" },
          color: { field: "segment", type: "nominal", sort: null },
        },
      },
      { region: "STRING", segment: "STRING", amount: "DECIMAL" },
    ),
    { region: ["North", "North"], segment: ["a", "b"], amount: [1234567.891, 2] },
    TOKENS,
  );
  assert.equal(stacked.option.layout.margin.r, MARGIN_RIGHT, "a stacked label sits inside its bar");
  // The estimate grows with the text and never undercounts a wide glyph against a narrow one.
  assert.ok(labelWidth("1,234,567.89") > labelWidth("12"));
  assert.ok(labelWidth("MMM") > labelWidth("iii"));
});

test("the size frame (#386): draw lays the option over 8 px sides, t 36 titled plus a subtitle line, and keeps room for outside labels - the Q-L4 right margin, one label line on top - without changing the converted option", () => {
  const { sizeFrame, COMPACT_MARGIN, TITLED_TOP, SUBTITLE_LINE_PX, LABEL_LINE_PX } = plotly._internal;
  const bindings = { region: ["North", "South"], amount: [1234567.891, 12] };
  const frame = (config) => {
    const option = convert(spec(config), bindings, TOKENS).option;
    const before = JSON.stringify(option);
    const layout = sizeFrame(option);
    assert.equal(JSON.stringify(option), before, "the converted option is not changed");
    return { option, layout };
  };
  const plain = frame(BAR);
  assert.deepEqual(plain.layout.margin, COMPACT_MARGIN);
  assert.equal(plain.layout.xaxis.automargin, true, "the band axis grows the frame by its ticks");
  assert.equal(plain.layout.yaxis.automargin, true, "the value axis grows the frame by its ticks");
  assert.equal(frame(Object.assign({}, BAR, { title: { text: "Sales" } })).layout.margin.t, TITLED_TOP);
  const subtitled = frame(Object.assign({}, BAR, { title: { text: "Sales", subtitle: "By region" } }));
  assert.equal(subtitled.layout.margin.t, TITLED_TOP + SUBTITLE_LINE_PX);
  const horizontal = frame(HORIZONTAL_LABELLED);
  assert.equal(horizontal.layout.margin.r, horizontal.option.layout.margin.r, "Q-L4's right margin is kept");
  assert.ok(horizontal.layout.margin.r > COMPACT_MARGIN.r);
  assert.equal(horizontal.layout.margin.t, COMPACT_MARGIN.t);
  const vertical = frame(Object.assign({}, BAR, { mark: { type: "bar", labels: "values" } }));
  assert.equal(vertical.layout.margin.t, COMPACT_MARGIN.t + LABEL_LINE_PX, "a label line above the tallest bar");
  assert.equal(vertical.layout.margin.r, COMPACT_MARGIN.r);
  const stacked = convert(
    spec(
      {
        mark: { type: "bar", labels: "values" },
        encoding: {
          x: { field: "region", type: "nominal", sort: null },
          y: { field: "amount", type: "quantitative", aggregate: "sum", stack: "zero" },
          color: { field: "segment", type: "nominal", sort: null },
        },
      },
      { region: "STRING", segment: "STRING", amount: "DECIMAL" },
    ),
    { region: ["North", "North"], segment: ["a", "b"], amount: [3, 2] },
    TOKENS,
  ).option;
  assert.deepEqual(sizeFrame(stacked).margin, COMPACT_MARGIN, "a stacked label sits inside its bar");
  // An axis titled null: the option keeps the reference's `{text: ""}`; the drawn axis has no title, because Plotly's
  // automargin reserves a title line for any text but its own placeholder, the empty string included.
  assert.deepEqual(plain.option.layout.xaxis.title, { text: "region" }, "an absent title is the field name");
  assert.deepEqual(plain.layout.xaxis.title, { text: "region" }, "a drawn title is kept");
  const untitled = frame({
    mark: { type: "bar" },
    encoding: {
      x: { field: "region", type: "nominal", sort: null, title: null },
      y: { field: "amount", type: "quantitative", aggregate: "sum", title: null },
    },
  });
  assert.deepEqual(untitled.option.layout.xaxis.title, { text: "" });
  assert.deepEqual(untitled.option.layout.yaxis.title, { text: "" });
  assert.equal("title" in untitled.layout.xaxis, false, "no empty band title line is reserved");
  assert.equal("title" in untitled.layout.yaxis, false, "no empty value title line is reserved");
  assert.equal(untitled.layout.xaxis.automargin, true, "the rest of the axis is the converter's");
  assert.deepEqual(untitled.layout.yaxis.tickvals, untitled.option.layout.yaxis.tickvals);
});

test("tokens: each declared property through a FRESH probe in the host's cascade, color(srgb) normalised, unset properties keep the built-in defaults, the locale from the document's lang", () => {
  const { doc, created } = installDom(
    { "--chart-canvas": "color(srgb 0 0 0)", "--chart-series-2": "rgb(1, 2, 3)", "--font-sans": "Inter, sans-serif" },
    "de-DE",
  );
  try {
    const host = fakeElement("div");
    doc.body.appendChild(host);
    const tokens = chart._internal.resolveTokens(host);
    assert.equal(tokens.canvas, "rgb(0, 0, 0)");
    assert.equal(tokens.series[1], "rgb(1, 2, 3)");
    assert.equal(tokens.font, "Inter, sans-serif");
    assert.equal(tokens.locale, "de-DE");
    // Unset: the defaults, never the inherited text colour.
    assert.equal(tokens.plot, TOKENS.plot);
    assert.equal(tokens.series[0], TOKENS.series[0]);
    assert.equal(tokens.ink, TOKENS.ink);
    assert.equal(created.length, 3, "one fresh probe per DECLARED property, none for the unset ones");
    assert.equal(host.children.length, 0, "every probe is removed");
    assert.ok(created.every((el) => el.tagName === "SPAN" && el.attrs["aria-hidden"] === "true"));
  } finally {
    uninstallDom();
  }
  // A lang that is not a language tag is ignored; without a DOM the defaults stand.
  installDom({}, "en_US<script>");
  try {
    assert.equal(chart._internal.resolveTokens(fakeElement("div")).locale, "en-US");
  } finally {
    uninstallDom();
  }
  assert.deepEqual(chart._internal.resolveTokens(null), Object.assign({}, TOKENS, { series: TOKENS.series.slice() }));
  assert.equal(chart._internal.normaliseColor("color(srgb 1 0.5 0 / 0.5)"), "rgba(255, 128, 0, 0.5)");
  assert.equal(chart._internal.normaliseColor("hsl(1 2 3)"), null, "an unknown form is never passed to the library");
});

test("the registry and the mount judgement: an unknown library is refused, undeclared is chart.library_undeclared, a missing library or family is chart.converter_missing", () => {
  assert.throws(() => chart.registerConverter({ library: "d3", families: { bar: () => {} }, draw: () => {} }), /library identities/);
  assert.throws(() => chart.registerConverter({ library: "echarts", families: { bar: () => {} } }), /draw/);
  const occurrence = { name: "sales", config: BAR };
  assert.deepEqual(chart._internal.judge(occurrence, { charts: null }), {
    code: "chart.library_undeclared",
    message: "the page declared no chart library: init needs charts { library, instance } to draw a chart",
    details: { name: "sales" },
  });
  assert.equal(chart._internal.judge(occurrence, { charts: { library: "chartjs", instance: {} } }).code, "chart.converter_missing");
  assert.deepEqual(chart._internal.judge({ name: "x", config: { mark: { type: "line" } } }, { charts: { library: "plotly", instance: {} } }).details, {
    library: "plotly",
    family: "line",
    name: "x",
  });
  assert.equal(chart._internal.judge(occurrence, { charts: { library: "plotly", instance: {} } }), null);
  assert.deepEqual(chart.LIBRARIES, ["plotly", "echarts", "chartjs", "apexcharts", "vegalite", "highcharts", "amcharts5", "recharts"]);
  // Warnings become codes and fixed sentences: no count, no document text, one per code.
  const warnings = chart._internal.renderWarnings([
    { field: "rule.null-category", class: "data", message: "2 row(s) with a null category were not drawn." },
    { field: "rule.null-category", class: "data", message: "1 row(s) with a null series were not drawn." },
    { field: "legend.orient", class: "degraded", message: "anything" },
  ]);
  assert.deepEqual(warnings, [
    { code: "chart.rule.null-category", message: "rows with a null category or series were not drawn" },
    { code: "chart.degraded.legend.orient", message: "the chart library draws a field of this chart approximately" },
  ]);
});

test("the Plotly host side touches the declared instance ONLY through react, Plots.resize and purge, and draw settles on react or plotly_afterplot", async () => {
  const touched = new Set();
  const calls = [];
  const target = {
    react: (host, data, layout, config) => {
      calls.push(["react", data.length, config.displayModeBar, layout.margin]);
      return new Promise(() => {}); // never settles: the afterplot event must
    },
    Plots: { resize: (host) => calls.push(["resize", host.tagName]) },
    purge: (host) => calls.push(["purge", host.tagName]),
  };
  const instance = new Proxy(target, {
    get(object, key) {
      touched.add(String(key));
      return object[key];
    },
  });
  installDom({});
  try {
    const host = fakeElement("div");
    const handle = chart._internal.converters().plotly
      ? (function () {
          const runtime = { registerRenderer: (spec) => (runtime.spec = spec) };
          chart.register(runtime);
          return runtime.spec.create({ host, occurrence: { name: "sales", description: "Sales by region", config: BAR, columns: COLUMNS }, charts: { library: "plotly", instance } });
        })()
      : null;
    assert.ok(handle, "the Plotly converter is installed");
    assert.equal(host.attrs.role, "img");
    assert.equal(host.attrs["aria-label"], "Sales by region", "the description, set through setAttribute");
    const drawn = handle.renderData({ name: "sales" }, 1, { region: ["North"], amount: [1] });
    setTimeout(() => host.fire("plotly_afterplot"), 5);
    assert.deepEqual(await drawn, { outcome: "rendered", warnings: [] });
    handle.resize();
    handle.dispose();
    assert.deepEqual(calls, [["react", 1, false, plotly._internal.COMPACT_MARGIN], ["resize", "DIV"], ["purge", "DIV"]], "react draws over the size frame");
    assert.deepEqual([...touched].sort(), ["Plots", "purge", "react"], "nothing else of the instance is read");
    // A measure outside its wire form rejects the draw (the runtime reports render.failed); its text is not echoed.
    const bad = chart._internal.converters().plotly;
    await assert.rejects(
      () => handle.renderData({ name: "sales" }, 1, { region: ["North"], amount: [{}] }),
      (error) => /amount/.test(error.message) && !/object/.test(error.message),
    );
    assert.ok(bad, "the converter stays installed");
    // An instance without react cannot draw.
    const runtime = { registerRenderer: (spec) => (runtime.spec = spec) };
    chart.register(runtime);
    const broken = runtime.spec.create({ host: fakeElement("div"), occurrence: { name: "x", config: BAR, columns: COLUMNS }, charts: { library: "plotly", instance: {} } });
    await assert.rejects(() => broken.renderData({ name: "x" }, 1, { region: ["a"], amount: [1] }), /react/);
    // A create without a declaration throws the judgement's code.
    assert.throws(
      () => runtime.spec.create({ host: fakeElement("div"), occurrence: { name: "x", config: BAR }, charts: null }),
      (error) => error.code === "chart.library_undeclared",
    );
  } finally {
    uninstallDom();
  }
});
