// The chart kind (design record 2026-11-14 §5.2): ONE adapter over the js-rendered engine. Its states, its resolver
// (look contract §3.7), its measure, its local redraws and its dispose, on Node's built-in runner with the engine loaded
// from the engine module's resources and a small fake browser (support/js-rendered.mjs). The runtime's side (init
// without `charts`, the refusal's sentence on the card, the warnings) is in dashboard-runtime.test.mjs; the browser's
// own CSS grammar and the page are the browser suite's (LA4, A4).

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import path from "node:path";
import { readdirSync, readFileSync } from "node:fs";
import { loadEngine, fakeBrowser, descendants, supports, STATIC_JS, ENGINE_DIR } from "./support/js-rendered.mjs";

const require = createRequire(import.meta.url);
const R = loadEngine();
globalThis.JsRendered = R;
globalThis.CSS = { supports };
// The interaction layer is loaded directly before the adapter, as every page loads it (brief 990 D1).
const layer = require(path.join(STATIC_JS, "datapipelines-chart-interactions.js"));
globalThis.DatapipelinesChartInteractions = layer;
const chart = require(path.join(STATIC_JS, "datapipelines-dashboard-chart.js"));
const { resolveTheme, parseColour, warningsOf, WARNING_TEXT } = chart._internal;

// ---------------------------------------------------------------------------------------------- fixtures

const BAR = {
  mark: { type: "bar" },
  encoding: { x: { field: "region", type: "nominal", sort: null }, y: { field: "amount", type: "quantitative", aggregate: "sum" } },
};
const COLUMNS = { region: "STRING", amount: "DECIMAL" };
const ROWS = { region: ["North", "South"], amount: [3, 5] };

/** The chart kind's create, as the runtime calls it after register(). */
function create(context) {
  const runtime = { registerRenderer: (spec) => (runtime.spec = spec) };
  chart.register(runtime);
  return runtime.spec.create(context);
}

function mount(options = {}) {
  const browser = fakeBrowser(options);
  const occurrence = Object.assign({ name: "sales", display_name: "Sales", description: null, config: BAR, columns: COLUMNS }, options.occurrence || {});
  const handle = create({ host: browser.host, occurrence });
  const svg = browser.host.childNodes.find((c) => c.tagName === "svg");
  return { browser, handle, svg };
}

const marks = (svg) => descendants(svg).filter((e) => /(^| )dp-mark( |$)/.test(e.getAttribute("class") || ""));
const texts = (svg) => descendants(svg).filter((e) => e.tagName === "text").map((e) => e.textContent);

/** The engine's own answer for a bar config over [columns] at 600 × 400 (the oracle of the core cases below). */
function render(config, columns, types) {
  return R.render({ type: "bar", document: { display_name: "Sales", config }, columns, types, frame: { w: 600, h: 400 }, theme: "light", measure: null, hidden: [] });
}
const tickTexts = (out) => out.primitives.filter((p) => p.role === "tick" && p.k === "text").map((p) => p.text);

// ------------------------------------------------------------------------------------------- registration

test("the kind registers as chart, major 1, with create only: no judge, no bundle, no library list", () => {
  const runtime = { registerRenderer: (spec) => (runtime.spec = spec) };
  chart.register(runtime);
  assert.deepEqual(Object.keys(runtime.spec).sort(), ["create", "kind", "version"]);
  assert.equal(runtime.spec.kind, "chart");
  assert.equal(runtime.spec.version, "1");
  assert.doesNotThrow(() => chart.register(null), "no runtime on the page: nothing to register");
});

// ------------------------------------------------------------------------------------------------- states

test("rendered: the engine's picture through writeDom, the runtime's acknowledgement with no warning", async () => {
  const { handle, svg } = mount();
  const ack = await handle.renderData({ name: "sales" }, 2, ROWS);
  assert.deepEqual(ack, { outcome: "rendered", warnings: [] });
  assert.equal(marks(svg).length > 0, true, "marks drawn");
  assert.equal(svg.getAttribute("viewBox"), "0 0 600 400", "the host's frame");
  assert.ok(texts(svg).includes("North") && texts(svg).includes("South"), JSON.stringify(texts(svg)));
  assert.ok(handle.index().series.length === 1, "the data index of the draw is kept for the interaction layer");
});

test("no-data (c): renderEmpty draws the engine's empty state and the previous marks are gone; every measure null is no-data too", async () => {
  const { handle, svg } = mount();
  await handle.renderData({ name: "sales" }, 2, ROWS);
  assert.ok(marks(svg).length > 0);
  assert.deepEqual(await handle.renderEmpty(), { outcome: "no-data", warnings: [] });
  assert.equal(marks(svg).length, 0, "no mark after the empty state");
  assert.ok(descendants(svg).length > 0, "the empty state is drawn, not a blank svg");
  assert.deepEqual(await handle.renderData({ name: "sales" }, 2, { region: ["North", "South"], amount: [null, null] }), { outcome: "no-data", warnings: [] });
  assert.equal(marks(svg).length, 0);
  assert.equal((await handle.renderData({ name: "sales" }, 1, { region: ["North"], amount: [1] })).outcome, "rendered");
});

test("refused: the draw rejects with the code's fixed sentence (error.refusal) and nothing of the chart stays drawn", async () => {
  const config = { mark: { type: "bar" }, encoding: Object.assign({}, BAR.encoding, { color: { field: "c", type: "nominal" } }) };
  const { handle, svg } = mount({ occurrence: { config, columns: { region: "STRING", c: "STRING", amount: "DECIMAL" } } });
  await handle.renderData({ name: "sales" }, 2, ROWS.region.length ? { region: ["a"], c: ["1"], amount: [1] } : {});
  assert.ok(marks(svg).length > 0);
  const nine = { region: Array(9).fill("a"), c: ["1", "2", "3", "4", "5", "6", "7", "8", "9"], amount: Array(9).fill(1) };
  const sentence = "Too many series to draw (9, the limit is 8). Group the smaller ones in the query.";
  await assert.rejects(
    () => handle.renderData({ name: "sales" }, 9, nine),
    (error) => error.message === sentence && error.refusal.code === "chart.too_many_series" && error.refusal.sentence === sentence,
  );
  assert.equal(descendants(svg).length, 0, "the previous picture is gone");
});

test("too-small: nothing is drawn and the draw resolves with chart.frame-too-small", async () => {
  const { handle, svg } = mount({ width: 10, height: 10 });
  const ack = await handle.renderData({ name: "sales" }, 2, ROWS);
  assert.deepEqual(ack, { outcome: "rendered", warnings: [{ code: "chart.frame-too-small", message: WARNING_TEXT["chart.frame-too-small"] }] });
  assert.equal(descendants(svg).length, 0);
});

test("a measure outside its wire form rejects the draw (the runtime reports render.failed), the value never echoed", async () => {
  const { handle } = mount();
  await assert.rejects(
    () => handle.renderData({ name: "sales" }, 1, { region: ["North"], amount: [{ secret: "x" }] }),
    (error) => /amount/.test(error.message) && !/secret|object/.test(error.message) && error.refusal === undefined,
  );
});

// --------------------------------------------------------------------------------------- the host contract

test("the host: one svg for the mount's life, the picture its own described image; dispose removes it and drops the columns", async () => {
  const { browser, handle, svg } = mount({ occurrence: { description: "Sales by region" } });
  assert.equal(svg.getAttribute("class"), "dp-chart");
  await handle.renderData({ name: "sales" }, 2, ROWS);
  // D6: the picture carries role img, its <title> and its <desc>; the host carries no role of its own (no nested image).
  assert.equal(svg.getAttribute("role"), "img");
  assert.equal(browser.host.getAttribute("role"), null);
  assert.equal(browser.host.getAttribute("aria-label"), null);
  const title = svg.childNodes.find((c) => c.tagName === "title");
  const desc = svg.childNodes.find((c) => c.tagName === "desc");
  assert.equal(title.textContent, "Sales");
  assert.match(desc.textContent, /^Bar chart; .* Sales by region$/);
  assert.equal(svg.childNodes[0], title, "the description first");
  assert.equal(browser.host.childNodes.length, 1, "one svg, never a second");
  handle.dispose();
  assert.equal(browser.host.childNodes.length, 0, "the picture is gone");
  assert.equal(handle.index(), null);
  assert.ok(browser.observers.resize.every((o) => !o.connected) && browser.observers.mutation.every((o) => !o.connected));
  assert.ok(browser.observers.media.every((m) => m.listeners.length === 0));
  handle.resize();
  assert.equal(browser.frames.length, 0, "a disposed chart schedules nothing");
  const quiet = mount();
  await quiet.handle.renderData({ name: "sales" }, 2, ROWS);
  assert.match(quiet.svg.childNodes.find((c) => c.tagName === "desc").textContent, /^Bar chart; [^.]*\.$/, "no description: the generated sentence alone");
});

// ------------------------------------------------------------------------------- the interaction layer (990)

const SERIES_BAR = {
  mark: { type: "bar" },
  encoding: { x: { field: "region", type: "nominal", sort: null }, y: { field: "amount", type: "quantitative", aggregate: "sum" }, color: { field: "channel", type: "nominal", sort: null } },
};
const SERIES_COLUMNS = { region: "STRING", channel: "STRING", amount: "DECIMAL" };
const seriesRows = (channels) => {
  const out = { region: [], channel: [], amount: [] };
  ["North", "South"].forEach((r, i) => channels.forEach((c, j) => (out.region.push(r), out.channel.push(c), out.amount.push(1 + i + j))));
  return out;
};
const drawnSeries = (svg) => new Set(descendants(svg).filter((e) => e.tagName === "g" && /(^| )dp-mark( |$)/.test(e.getAttribute("class") || "") && e.getAttribute("data-series")).map((e) => e.getAttribute("data-series")));

test("D4: the list of switched-off series: a click on an entry changes it and redraws at once; a refresh keeps it; dispose drops it", async () => {
  const { handle, svg } = mount({ occurrence: { config: SERIES_BAR, columns: SERIES_COLUMNS } });
  await handle.renderData({ name: "sales" }, 6, seriesRows(["Online", "Retail", "Store"]));
  assert.deepEqual([...drawnSeries(svg)].sort(), ["string:Online", "string:Retail", "string:Store"]);
  const entry = descendants(svg).find((e) => e.getAttribute("data-legend") === "string:Retail");
  assert.equal(entry.getAttribute("role"), "button");
  assert.equal(entry.getAttribute("aria-pressed"), "true");
  // A click on the entry's place (the layer finds the entry through the draw's primitives).
  const R2 = globalThis.JsRendered;
  const out = R2.render({ type: "bar", document: { display_name: "Sales", config: SERIES_BAR }, columns: seriesRows(["Online", "Retail", "Store"]), types: SERIES_COLUMNS, frame: { w: 600, h: 400 }, theme: "light", measure: null, hidden: [] });
  const box = layer._internal.modelOf(R2, out, R2.measure.measurer(null).fn).entries.find((e) => e.id === "string:Retail").box;
  svg.dispatch("click", { clientX: box.x + box.w / 2, clientY: box.y + box.h / 2 });
  assert.deepEqual(handle.hidden(), ["string:Retail"]);
  assert.ok(!drawnSeries(svg).has("string:Retail"), "redrawn at once, Retail off");
  const struck = descendants(svg).find((e) => e.getAttribute("data-legend") === "string:Retail");
  assert.equal(struck.getAttribute("aria-pressed"), "false");
  // A refresh that removes Retail keeps the id on the list; the one that brings it back hides it again.
  await handle.renderData({ name: "sales" }, 4, seriesRows(["Online", "Store"]));
  assert.deepEqual(handle.hidden(), ["string:Retail"]);
  await handle.renderData({ name: "sales" }, 6, seriesRows(["Online", "Retail", "Store"]));
  assert.deepEqual(handle.hidden(), ["string:Retail"]);
  assert.ok(!drawnSeries(svg).has("string:Retail"), "hidden again when it returns");
  // Enter on the entry by the keys alone brings it back.
  svg.dispatch("keydown", { key: "ArrowRight" });
  svg.dispatch("keydown", { key: "End" });
  svg.dispatch("keydown", { key: "ArrowRight" });
  const order = descendants(svg).filter((e) => e.getAttribute("data-legend")).map((e) => e.getAttribute("data-legend"));
  for (let i = order.indexOf("string:Online"); i < order.indexOf("string:Retail"); i++) svg.dispatch("keydown", { key: "ArrowRight" });
  svg.dispatch("keydown", { key: "Enter" });
  assert.deepEqual(handle.hidden(), [], "brought back without a pointer");
  assert.ok(drawnSeries(svg).has("string:Retail"));
  assert.equal(svg.listeners.length, 6, "the same listeners through every redraw");
  handle.dispose();
  assert.deepEqual(handle.hidden(), []);
  assert.equal(svg.listeners.length, 0, "dispose detached the layer: no listener left");
  assert.equal(descendants(svg).filter((e) => /dp-tooltip|dp-focus/.test(e.getAttribute("class") || "")).length, 0);
});

test("D1: a chart mounted where the interaction layer's global is missing is refused with a sentence naming the file", async () => {
  const saved = globalThis.DatapipelinesChartInteractions;
  delete globalThis.DatapipelinesChartInteractions;
  try {
    const { handle } = mount();
    await assert.rejects(handle.renderData({ name: "sales" }, 2, ROWS), (e) => e.message.includes("datapipelines-chart-interactions.js"));
  } finally {
    globalThis.DatapipelinesChartInteractions = saved;
  }
});

test("redraws are local, from the kept columns, one per animation frame: resize, the ResizeObserver, a theme change and the fonts", async () => {
  const { browser, handle, svg } = mount();
  await handle.renderData({ name: "sales" }, 2, ROWS);
  const before = svg.getAttribute("viewBox");
  browser.host.clientWidth = 320;
  handle.resize();
  handle.resize();
  assert.equal(browser.frames.length, 1, "two resizes in one frame are one redraw");
  browser.flushFrames();
  assert.equal(svg.getAttribute("viewBox"), "0 0 320 400", "redrawn at the new frame from the kept columns (was " + before + ")");
  assert.ok(marks(svg).length > 0);
  // The ResizeObserver on the host.
  const [resizeObserver] = browser.observers.resize;
  assert.equal(resizeObserver.target, browser.host);
  browser.host.clientWidth = 480;
  resizeObserver.fn();
  browser.flushFrames();
  assert.equal(svg.getAttribute("viewBox"), "0 0 480 400");
  // A theme change: an attribute of the document element, or the colour-scheme query.
  const [themeObserver] = browser.observers.mutation;
  assert.equal(themeObserver.target, browser.doc.documentElement);
  assert.deepEqual(themeObserver.options, { attributes: true });
  themeObserver.fn();
  assert.equal(browser.frames.length, 1);
  browser.flushFrames();
  const [scheme] = browser.observers.media;
  assert.equal(scheme.query, "(prefers-color-scheme: dark)");
  scheme.listeners[0]();
  assert.equal(browser.frames.length, 1);
  browser.flushFrames();
  // The fonts: once ready, every width is measured again and the chart redrawn.
  const measured = browser.fonts.length;
  browser.fontsReady();
  await new Promise((r) => setTimeout(r, 0));
  assert.equal(browser.frames.length, 1, "one redraw when the fonts are ready");
  browser.flushFrames();
  assert.ok(browser.fonts.length > measured, "the cache was dropped: texts measured again");
});

test("measure: one canvas context over the resolved font list and weight, each (text, size, weight) measured once", async () => {
  const { browser, handle } = mount({ declared: { "--font-sans": "Inter, sans-serif" } });
  await handle.renderData({ name: "sales" }, 2, ROWS);
  assert.ok(browser.fonts.length > 0, "the engine measured through the page's measure");
  assert.ok(browser.fonts.every((f) => /^\d{3} \d+(\.\d+)?px Inter, sans-serif$/.test(f)), JSON.stringify([...new Set(browser.fonts)]));
  const first = browser.fonts.length;
  handle.resize();
  browser.flushFrames();
  assert.equal(browser.fonts.length, first, "the redraw measured nothing new: every width came from the cache");
});

test("#473: a chart title that only repeats the card heading (the display name) is not drawn; one that says more, or carries a subtitle, stays", async () => {
  const config = Object.assign({}, BAR, { title: { text: " Sales " } });
  const repeated = mount({ occurrence: { display_name: "Sales", config } });
  await repeated.handle.renderData({ name: "s" }, 1, { region: ["North"], amount: [1] });
  assert.ok(!texts(repeated.svg).some((t) => t.trim() === "Sales"), JSON.stringify(texts(repeated.svg)));
  const more = mount({ occurrence: { display_name: "Sales by region", config } });
  await more.handle.renderData({ name: "s" }, 1, { region: ["North"], amount: [1] });
  assert.ok(texts(more.svg).some((t) => t.trim() === "Sales"), "the title says more than the heading");
  const subtitled = mount({ occurrence: { display_name: "Sales", config: Object.assign({}, BAR, { title: { text: "Sales", subtitle: "2026" } }) } });
  await subtitled.handle.renderData({ name: "s" }, 1, { region: ["North"], amount: [1] });
  const shown = texts(subtitled.svg).map((t) => t.trim());
  assert.ok(shown.includes("Sales") && shown.includes("2026"), "a subtitle keeps its title: " + JSON.stringify(shown));
  assert.equal(JSON.stringify(config), JSON.stringify(Object.assign({}, BAR, { title: { text: " Sales " } })), "the occurrence's config is not changed");
});

test("hostile text is text: every string reaches the DOM as a text node, never markup, an element or an attribute name", async () => {
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
  const { handle, svg } = mount({ occurrence: { config, columns: { region: "STRING", segment: "STRING", amount: "DECIMAL" } } });
  await handle.renderData({ name: "s" }, 3, { region: trio, segment: trio, amount: [1, 2, 3] });
  const all = descendants(svg);
  assert.ok(all.length > 20, "the walk saw the picture: " + all.length);
  const tags = new Set(all.map((e) => e.tagName));
  for (const tag of tags) assert.ok(["g", "rect", "path", "line", "text", "circle", "title", "desc"].includes(tag), "an element the writer makes: " + tag);
  for (const e of all) for (const name of Object.keys(e.attrs)) assert.ok(/^[a-z][a-z0-9-]*$/.test(name) && !/^on|^href$|^style$/.test(name), "attribute " + name);
  const shown = texts(svg).join("\n");
  assert.ok(trio.some((t) => shown.includes(t.slice(0, 8))), "the hostile texts are THERE, as text: " + shown.slice(0, 200));
});

// ----------------------------------------------------------------------------------- the resolver (§3.7)

test("the resolver: a declared valid value is taken; an empty one is not declared; a refused one takes its default and is named once", () => {
  const { browser, svg } = mount({
    declared: {
      "--chart-grid": "#010203",
      "--chart-canvas": "rgb(255 255 255 / 0.5)",
      "--chart-series-2": "color(srgb 0.5 0.5 0.5)",
      "--chart-text": "0.75rem",
      "--chart-title": "18",
      "--chart-bar-radius": "1em",
      "--chart-ease": "0.12s",
      "--chart-dim": "1.4",
      "--chart-title-weight": "bold",
      "--chart-axis": "12px",
      "--chart-series-1": "rgb(59 91 219 / 0.8)",
      "--chart-on-fill-light": "rgba(255, 255, 255, 0)",
      "--chart-gap": "50%",
      "--chart-point": "-4",
      "--chart-ink": "   ",
      "--font-sans": "Inter, \"Noto Sans\", sans-serif",
    },
  });
  const { theme, refused } = resolveTheme(svg);
  assert.equal(theme.base, "light", "the page's default is the Light column (contract §3.7)");
  assert.deepEqual(theme.tokens["--chart-grid"], [1, 2, 3]);
  assert.deepEqual(theme.tokens["--chart-canvas"], [255, 255, 255, 0.5], "a surface may be translucent");
  assert.deepEqual(theme.tokens["--chart-series-2"], [128, 128, 128], "127.5 rounds up");
  assert.equal(theme.tokens["--chart-text"], 12);
  assert.equal(theme.tokens["--chart-title"], 18, "a bare number is CSS pixels");
  assert.equal(theme.tokens["--chart-bar-radius"], 14, "1em at a 14 px element");
  assert.equal(theme.tokens["--chart-ease"], 120);
  assert.equal(theme.tokens["--font-sans"], "Inter, \"Noto Sans\", sans-serif");
  assert.deepEqual(refused.sort(), ["--chart-axis", "--chart-dim", "--chart-gap", "--chart-on-fill-light", "--chart-point", "--chart-series-1", "--chart-title-weight"].sort());
  for (const name of refused) assert.equal(name in theme.tokens, false, name + " takes its default: not handed to the engine");
  assert.equal("--chart-ink" in theme.tokens, false, "white space only is empty: not declared, not refused");
  assert.equal(svg.childNodes.length, 0, "the probe is removed");
  // The engine accepts what the resolver hands it.
  assert.doesNotThrow(() => R.theme.resolve(theme));
  // The two stylesheet-only tokens are never read.
  assert.ok(!browser.reads.includes("--chart-pad") && !browser.reads.includes("--chart-tip-shadow"), "never read");
  assert.equal(browser.reads.length, Object.keys(R.theme.TOKENS).length, "every engine token read once");
});

test("the resolver's ranges and kinds, from the contract's example table: refused values never clamp", () => {
  const judge = (name, value) => {
    const { svg } = mount({ declared: { [name]: value } });
    const out = resolveTheme(svg);
    return out.refused.includes(name) ? "refused" : out.theme.tokens[name];
  };
  for (const v of ["12", "12px", "0.75rem", "calc(10px + 2px)"]) assert.equal(judge("--chart-text", v), 12, v);
  assert.equal(judge("--chart-text", "1em"), 14);
  for (const v of ["50%", "large", "twelve", "-4", "4", "120"]) assert.equal(judge("--chart-text", v), "refused", v);
  assert.equal(judge("--chart-gap", "0"), 0);
  for (const v of ["1.4", "25%"]) assert.equal(judge("--chart-dim", v), "refused", v);
  for (const v of ["0.12s", "120ms", "120"]) assert.equal(judge("--chart-ease", v), 120, v);
  assert.equal(judge("--chart-title-weight", "400"), 400);
  assert.equal(judge("--chart-title-weight", "bold"), "refused");
  for (const v of ["12px", "url(x.png)"]) assert.equal(judge("--chart-grid", v), "refused", v);
  assert.deepEqual(judge("--chart-plot", "transparent"), [0, 0, 0, 0], "a surface may be transparent");
  for (const v of ["rgb(59 91 219 / 0.8)", "transparent"]) assert.equal(judge("--chart-series-1", v), "refused", v);
  assert.equal(judge("--chart-seq-3", "rgb(0 0 0 / 0.5)"), "refused");
  assert.equal(judge("--chart-on-fill-light", "rgb(255 255 255 / 0)"), "refused");
});

test("the colours as the engine takes them: whole channels half up and cut, alpha only below 1, an unread form is null", () => {
  assert.deepEqual(parseColour("color(srgb 1 0.5 0 / 0.5)"), [255, 128, 0, 0.5]);
  assert.deepEqual(parseColour("color(srgb 1.2 -0.1 0.5)"), [255, 0, 128], "a channel outside the range is cut");
  assert.deepEqual(parseColour("rgb(1, 2, 3)"), [1, 2, 3]);
  assert.deepEqual(parseColour("rgba(1, 2, 3, 1)"), [1, 2, 3], "alpha 1 is opaque");
  assert.deepEqual(parseColour("rgb(10% 20% 30%)"), [26, 51, 77]);
  assert.deepEqual(parseColour("#ff000080"), [255, 0, 0, 128 / 255]);
  assert.equal(parseColour("hsl(1 2 3)"), null, "a form this function does not read is never passed on");
  assert.equal(parseColour("oklch(0.5 0.1 200)"), null);
});

test("warnings: codes and fixed sentences, one per code, and one chart.token-refused per refused token naming it", () => {
  const warnings = warningsOf(
    { warnings: [{ code: "chart.rule.null-category" }, { code: "chart.rule.null-category" }, { code: "chart.rule.non-finite" }, { code: "chart.text.width-missing" }] },
    ["--chart-grid", "--chart-dim"],
  );
  assert.deepEqual(warnings.map((w) => w.code), ["chart.rule.null-category", "chart.rule.non-finite", "chart.text.width-missing", "chart.token-refused", "chart.token-refused"]);
  assert.equal(warnings[0].message, "rows with a null category or series were not drawn");
  assert.deepEqual(warnings.slice(3).map((w) => w.token), ["--chart-grid", "--chart-dim"]);
  assert.ok(warnings.slice(3).every((w, i) => w.message.includes(["--chart-grid", "--chart-dim"][i])));
  assert.ok(warnings.every((w) => typeof w.message === "string" && !/\d/.test(w.message)), "no count, no document text");
});

// --------------------------------------------------- every engine warning code has its own sentence (brief 1008 D2)

const GENERAL = "the chart was drawn with a warning";

/**
 * The engine's codes, read from its sources: every `.js` file under the engine's folder, `types/` included, found by
 * listing the folder (not LOAD-ORDER.txt, so a file the load order misses is still read); every quoted string (either
 * quote kind) that starts with `chart.`. A code is a refusal code when the engine's `sentence` answers it; otherwise a
 * warning code.
 */
function engineCodes() {
  const files = [];
  (function walk(dir) {
    for (const entry of readdirSync(dir, { withFileTypes: true })) {
      if (entry.isDirectory()) walk(path.join(dir, entry.name));
      else if (entry.name.endsWith(".js")) files.push(path.join(dir, entry.name));
    }
  })(ENGINE_DIR);
  const codes = new Set();
  for (const file of files) {
    for (const m of readFileSync(file, "utf8").matchAll(/(["'])(chart\.[^"'\\\s]*)\1/g)) codes.add(m[2]);
  }
  const refusals = [];
  const warnings = [];
  for (const code of [...codes].sort()) {
    let refusal = true;
    try {
      R.sentence({ code, details: {} });
    } catch {
      refusal = false;
    }
    (refusal ? refusals : warnings).push(code);
  }
  return { files: files.length, refusals, warnings };
}

test("every warning code the engine has carries its own fixed sentence, and every sentence a code the engine has", () => {
  const { files, refusals, warnings } = engineCodes();
  console.log(`engine codes: ${warnings.length} warning codes, ${refusals.length} refusal codes, ${files} files read`);
  assert.ok(files >= 20, `at least 20 engine files read, got ${files}`);
  assert.ok(warnings.length >= 6, `at least 6 warning codes, got ${warnings.length}: ${warnings.join(", ")}`);
  assert.ok(refusals.length >= 4, `at least 4 refusal codes, got ${refusals.length}: ${refusals.join(", ")}`);
  const keys = Object.keys(WARNING_TEXT);
  assert.deepEqual(warnings.filter((code) => !keys.includes(code)), [], "engine warning codes without a sentence");
  assert.deepEqual(keys.filter((code) => !warnings.includes(code)), [], "sentences for a code the engine does not warn with");
  const values = Object.values(WARNING_TEXT);
  for (const [code, text] of Object.entries(WARNING_TEXT)) {
    assert.ok(typeof text === "string" && text.trim() !== "", `${code}: a non-empty sentence`);
    assert.notEqual(text, GENERAL, `${code}: its own sentence, not the general one`);
    assert.equal(values.filter((v) => v === text).length, 1, `${code}: a sentence no other code has`);
  }
  assert.equal(WARNING_TEXT["chart.rule.null-position"], "rows with a null or unreadable position were not drawn");
  assert.equal(WARNING_TEXT["chart.rule.log-nonpositive"], "a value at or below zero was not drawn on a log axis");
  assert.equal(WARNING_TEXT["chart.rule.non-finite"], "a value is too large to draw and was not drawn");
});

test("a warning publishes its code and its fixed sentence, no other field of the engine's warning", () => {
  const MARKER = "zq-marker-1008";
  for (const sentence of [...Object.values(WARNING_TEXT), GENERAL]) assert.ok(!sentence.includes(MARKER), "the marker occurs in no sentence");
  const unknown = "chart.rule.not-known-to-the-adapter";
  for (const code of [...engineCodes().warnings, unknown]) {
    const hostile = { code, message: MARKER, text: MARKER, name: MARKER, details: { value: MARKER } };
    assert.deepEqual(warningsOf({ warnings: [hostile] }, []), [{ code, message: code === unknown ? GENERAL : WARNING_TEXT[code] }], code);
  }
});

test("a refused token reaches the draw's acknowledgement: the chart draws with the default, the warning names the token", async () => {
  const { handle, svg } = mount({ declared: { "--chart-grid": "12px" } });
  const ack = await handle.renderData({ name: "sales" }, 2, ROWS);
  assert.equal(ack.outcome, "rendered");
  assert.deepEqual(ack.warnings.map((w) => [w.code, w.token]), [["chart.token-refused", "--chart-grid"]]);
  const grid = descendants(svg)
    .filter((e) => /(^| )dp-grid( |$)/.test(e.getAttribute("class") || ""))
    .flatMap((e) => [e].concat(descendants(e)))
    .filter((e) => e.getAttribute("stroke") !== null);
  assert.ok(grid.length > 0, "grid lines drawn");
  const defaultGrid = "#" + R.theme.TOKENS["--chart-grid"][1].map((c) => c.toString(16).padStart(2, "0")).join("");
  for (const line of grid) assert.equal(line.getAttribute("stroke"), defaultGrid, "the default grid colour, a literal");
});

// --------------------------------------------- the old neutral core's cases, on the engine (D7 treatment 1)

test("the engine orders by the column's type and keeps exact values exact: BIGINTEGER 2 before 10, two values float64 cannot tell apart, the mean HALF-EVEN at scale + 6", () => {
  const byType = (type, values) =>
    render({ mark: { type: "bar" }, encoding: { x: { field: "n", type: "nominal", sort: "ascending" }, y: { type: "quantitative", aggregate: "count" } } }, { n: values }, { n: type })
      .index.series[0].tip.map((lines) => lines[0]);
  assert.deepEqual(byType("BIGINTEGER", ["10", "2"]), ["2", "10"], "an exact column orders by value");
  assert.deepEqual(byType("STRING", ["10", "2"]), ["10", "2"], "a text column orders by code point");
  assert.deepEqual(byType("BIGINTEGER", ["9007199254740993", "9007199254740992"]), ["9007199254740992", "9007199254740993"], "2^53 and 2^53 + 1 stay two categories, in value order");
  assert.equal(R.data.aggregate(["1", "1", "0"], "mean", "v").exact, "0.666667", "rounded, not truncated past scale + 6");
  assert.equal(R.data.aggregate(["1"].concat(Array(127).fill("0")), "mean", "v").exact, "0.007812", "1/128 = 0.0078125 is a tie: HALF-EVEN keeps the even digit");
});

test("ticks stay finite on the ranges float64 can barely carry, and an ordinary range keeps its ticks", () => {
  for (const [lo, hi] of [[0, 1.7e308], [0, Number.MAX_VALUE], [-1.7e308, 1.7e308], [0, 5e-324], [0, 1e-310]]) {
    const out = render(BAR, { region: ["a", "b"], amount: [lo, hi] }, COLUMNS);
    assert.equal(out.state, "rendered", lo + ".." + hi);
    const ticks = out.primitives.filter((p) => p.role === "tick");
    assert.ok(ticks.length > 0 && ticks.every((p) => Object.values(p).every((v) => typeof v !== "number" || Number.isFinite(v))), lo + ".." + hi);
  }
  assert.deepEqual(tickTexts(render(BAR, { region: ["a", "b"], amount: [0, 10] }, COLUMNS)).filter((t) => /^\d+$/.test(t)), ["0", "2", "4", "6", "8", "10", "12"]);
});

test("a stacked cell beyond float64 draws as a gap with the rule.non-finite warning, never a zero, and the axis stays finite", () => {
  const out = render(
    { mark: { type: "bar" }, encoding: { x: { field: "region", type: "nominal", sort: null }, y: { field: "amount", type: "quantitative", aggregate: "sum", stack: "zero" }, color: { field: "channel", type: "nominal", sort: null } } },
    { region: ["North", "North", "North", "South", "South"], channel: ["Online", "Online", "Retail", "Online", "Retail"], amount: [1e308, 1e308, 500, 400, 300] },
    { region: "STRING", channel: "STRING", amount: "DOUBLE" },
  );
  assert.equal(out.state, "rendered");
  assert.equal(out.summary.marks, 3, "1e308 + 1e308 past float64: a gap, three marks drawn");
  assert.deepEqual(out.summary.warnings.map((w) => w.code), ["chart.rule.non-finite"]);
  assert.deepEqual(out.index.series.map((s) => s.tip.map((t) => t[1])), [["Online: 400"], ["Retail: 500", "Retail: 300"]]);
  assert.ok(out.primitives.every((p) => Object.values(p).every((v) => typeof v !== "number" || Number.isFinite(v))), "every number finite");
});

test("a NUL category keeps its (category, series) pair distinct: no flat-key collision, all four cells drawn", () => {
  const out = render(
    { mark: { type: "bar" }, encoding: { x: { field: "region", type: "nominal", sort: null }, y: { field: "amount", type: "quantitative", aggregate: "sum", stack: "zero" }, color: { field: "channel", type: "nominal", sort: null } } },
    { region: ["a", "a\u0000string:b", "a", "a\u0000string:b"], channel: ["b\u0000string:c", "c", "c", "b\u0000string:c"], amount: [10, 20, 30, 40] },
    { region: "STRING", channel: "STRING", amount: "DOUBLE" },
  );
  assert.equal(out.summary.marks, 4);
  assert.deepEqual(out.index.series.map((s) => s.tip.map((t) => t[1])), [["b\u0000string:c: 10", "b\u0000string:c: 40"], ["c: 30", "c: 20"]], "each pair its own cell; a flat key would merge 10 and 20 into 30");
});

test("an authored domain at float64's edge draws: every tick finite", () => {
  const out = render(
    { mark: { type: "bar" }, encoding: { x: { field: "region", type: "nominal", sort: null }, y: { field: "amount", type: "quantitative", aggregate: "sum", scale: { domain: [0, 1.7e308] } } } },
    { region: ["North", "South"], amount: [1, 2] },
    COLUMNS,
  );
  assert.equal(out.state, "rendered");
  const ticks = out.primitives.filter((p) => p.role === "tick" && p.k === "text");
  assert.ok(ticks.length >= 2 && ticks.every((p) => Number.isFinite(p.x) && Number.isFinite(p.y)), JSON.stringify(ticks.map((p) => p.text)));
});

test("normalization keeps finite shares when their unscaled absolute total overflows", () => {
  const config = {
    mark: { type: "bar", labels: "values" },
    encoding: { x: { field: "region", type: "nominal", sort: null }, y: { field: "amount", type: "quantitative", aggregate: "sum", stack: "normalize", format: "percent" }, color: { field: "channel", type: "nominal", sort: null } },
  };
  for (const [values, expected] of [
    [[1e308, 1e308], ["50%", "50%"]],
    [[Number.MAX_VALUE, Number.MAX_VALUE], ["50%", "50%"]],
    [[-1e308, 1e308], ["−50%", "50%"]],
    [[0, 0], ["0%", "0%"]],
    [[-Number.MIN_VALUE, Number.MAX_VALUE], ["0%", "100%"]],
  ]) {
    const out = render(config, { region: ["A", "A"], channel: ["one", "two"], amount: values }, { region: "STRING", channel: "STRING", amount: "DOUBLE" });
    assert.deepEqual(out.summary.warnings, [], String(values));
    assert.deepEqual(out.index.series.map((s) => s.tip[0][1].split(": ")[1]), expected, String(values));
  }
});

// #1065 D6 (A.5) — the theme a host sets on the element handed to the runtime: the chart's one MutationObserver also
// watches `data-theme` on its scope root (`host.closest(".dp-scope")`). The suite's stand-in keeps one target per
// observer, so this case gives the observer a recording stand-in that delivers a change only to a registration whose
// options admit it (the platform's `attributeFilter` rule), and the host a `closest`.
function scopedMount(lookups) {
  const browser = fakeBrowser();
  const registrations = [];
  let observer = null;
  browser.win.MutationObserver = class {
    constructor(fn) {
      this.fn = fn;
      this.connected = false;
      observer = this;
    }
    observe(target, options) {
      registrations.push({ target, options });
      this.connected = true;
    }
    disconnect() {
      this.connected = false;
    }
  };
  const asked = [];
  browser.host.closest = (selector) => {
    asked.push(selector);
    return lookups.shift() || null;
  };
  const handle = create({ host: browser.host, occurrence: { name: "sales", display_name: "Sales", description: null, config: BAR, columns: COLUMNS } });
  /** An attribute change on [target], delivered as the platform would: only through a registration that admits it. */
  const change = (target, attribute) => {
    const admitted = registrations.some(
      (r) => r.target === target && r.options.attributes && (!r.options.attributeFilter || r.options.attributeFilter.includes(attribute)),
    );
    if (admitted && observer.connected) observer.fn([{ type: "attributes", target, attributeName: attribute }]);
  };
  return { browser, handle, registrations, observer: () => observer, asked, change };
}

test("#1065 D6: a data-theme change on the scope root redraws once, another attribute none, and dispose disconnects", async () => {
  const scope = { tagName: "DIV", className: "dp-scope" };
  const { browser, handle, registrations, observer, asked, change } = scopedMount([scope]);
  await handle.renderData({ name: "sales" }, 2, ROWS);
  console.log("1065-node A5 registrations=" + JSON.stringify(registrations.map((r) => ({ target: r.target.tagName, options: r.options }))));
  assert.deepEqual(asked, [".dp-scope"], "the scope root is looked for once when it is found at creation");
  assert.equal(registrations.length, 2);
  assert.equal(registrations[0].target, browser.doc.documentElement, "the document element's observation stays (the product's pages)");
  assert.deepEqual(registrations[0].options, { attributes: true });
  assert.equal(registrations[1].target, scope);
  assert.deepEqual(registrations[1].options, { attributes: true, attributeFilter: ["data-theme"] });
  change(scope, "data-theme");
  assert.equal(browser.frames.length, 1, "a data-theme change on the scope root schedules one redraw");
  browser.flushFrames();
  change(scope, "class");
  change(scope, "style");
  assert.equal(browser.frames.length, 0, "another attribute of the scope root schedules none");
  handle.dispose();
  assert.equal(observer().connected, false, "dispose disconnects the observer");
  change(scope, "data-theme");
  assert.equal(browser.frames.length, 0, "after dispose a theme change schedules nothing");
});

test("#1065 D6: a host not yet in a scope at creation is looked up once more at the first draw, and never again", async () => {
  const scope = { tagName: "DIV", className: "dp-scope" };
  const { browser, handle, registrations, asked, change } = scopedMount([null, scope, scope]);
  assert.equal(registrations.length, 1, "nothing to watch at creation but the document element");
  await handle.renderData({ name: "sales" }, 2, ROWS);
  assert.equal(registrations.length, 2, "the first draw found the scope root and watches it");
  assert.equal(registrations[1].target, scope);
  browser.flushFrames();
  handle.resize();
  browser.flushFrames();
  await handle.renderData({ name: "sales" }, 2, ROWS);
  console.log("1065-node A5 late lookups=" + asked.length);
  assert.deepEqual(asked, [".dp-scope", ".dp-scope"], "at creation and at the first draw, no other time");
  change(scope, "data-theme");
  assert.equal(browser.frames.length, 1);
});
