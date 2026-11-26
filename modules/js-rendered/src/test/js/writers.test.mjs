// The writers (record §4.3, §7, A8; brief 985 Security, G6). writeDom builds with createElementNS, setAttribute and
// textContent only (a recording fake document proves which calls it makes); writeString escapes every text node and
// attribute value; neither writes href, a style attribute or an event attribute, and every attribute NAME is one of
// the writers' constants, never a document value. The hostile fixture (A8) is text in both.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, chartFixtures, inputOf } from "./engine.mjs";

const R = engine();
const FIXTURES = chartFixtures();
const hostile = FIXTURES.find((f) => f.wire === "bar" && f.name === "hostile");
/** The hostile fixture of every type (brief 989 Security: line and area have their own). */
const HOSTILES = FIXTURES.filter((f) => f.name === "hostile");
const bar = (name) => FIXTURES.find((x) => x.wire === "bar" && x.name === name);
/** The elements a picture may hold; the line family adds the dot and an area's fade (a gradient and its two stops). */
const TAGS = new Set(["g", "line", "path", "rect", "svg", "text", "circle", "linearGradient", "stop", "title", "desc"]);
const FADE = new Set(["linearGradient", "stop"]);

/** Every attribute name either writer may write. */
const NAMES = new Set(["xmlns", "width", "height", "viewBox", "class", "x", "y", "rx", "x1", "y1", "x2", "y2", "d", "cx", "cy", "r", "fill", "stroke", "stroke-width", "opacity",
  "text-anchor", "font-size", "font-family", "font-weight", "transform", "text-decoration", "data-series", "data-legend", "aria-label",
  // Lane 989: a line's round joins and ends, and an area's fade (the gradient's id is made by the writer, never a document value).
  "stroke-linejoin", "stroke-linecap", "id", "gradientUnits", "offset", "stop-color", "stop-opacity",
  // Lane 990 (D4, D6): the picture is one image; a legend entry is a button, pressed while its series is shown.
  "role", "aria-pressed"]);

/** A fake document that records every call the DOM writer makes; any other member is undefined, so its use throws. */
function fakeDocument() {
  const calls = [];
  function element(tag, ns) {
    const el = {
      tag, ns, attrs: [], children: [], text: null,
      setAttribute(n, v) {
        calls.push("setAttribute");
        el.attrs.push([n, v]);
      },
      appendChild(c) {
        calls.push("appendChild");
        el.children.push(c);
        return c;
      },
      removeChild(c) {
        calls.push("removeChild");
        el.children.splice(el.children.indexOf(c), 1);
        return c;
      },
      get firstChild() {
        return el.children.length ? el.children[0] : null;
      },
      set textContent(t) {
        calls.push("textContent");
        el.text = t;
      },
    };
    return el;
  }
  const doc = {
    createElementNS(ns, tag) {
      calls.push("createElementNS");
      return element(tag, ns);
    },
  };
  const svg = element("svg", R.write.NS);
  svg.ownerDocument = doc;
  return { svg, calls };
}

function walk(el, f) {
  f(el);
  for (const c of el.children) walk(c, f);
}

test("writeDom uses createElementNS, setAttribute, textContent (and appendChild/removeChild) only", () => {
  const { svg, calls } = fakeDocument();
  svg.children.push({ old: true });
  R.writeDom(R.render(inputOf(hostile.fixture, 960, 540)).primitives, svg);
  assert.ok(!svg.children.some((c) => c.old), "the old children are removed");
  assert.deepEqual([...new Set(calls)].sort(), ["appendChild", "createElementNS", "removeChild", "setAttribute", "textContent"]);
  let elements = 0;
  walk(svg, (el) => {
    if (el.tag !== "svg") elements++;
    assert.equal(el.ns, R.write.NS);
  });
  assert.ok(elements > 20, "elements built: " + elements);
});

test("the hostile fixture is text in the DOM: every hostile string is a text node, never an element or a name", () => {
  assert.deepEqual(HOSTILES.map((f) => f.wire), ["area", "bar", "box", "funnel", "gauge", "heatmap", "line", "pie", "scatter", "waterfall"]);
  for (const f of HOSTILES) {
    const { svg } = fakeDocument();
    R.writeDom(R.render(inputOf(f.fixture, 960, 540)).primitives, svg);
    const tags = new Set(), texts = [];
    walk(svg, (el) => {
      tags.add(el.tag);
      if (el.text !== null) texts.push(el.text);
      for (const [n] of el.attrs) assert.ok(NAMES.has(n), f.wire + ": attribute name " + n);
    });
    for (const t of tags) assert.ok(TAGS.has(t), f.wire + ": element " + t);
    if (f.wire === "bar") assert.deepEqual([...tags].sort(), ["g", "line", "path", "rect", "svg", "text"]);
    assert.ok(texts.some((t) => t.includes("<script>alert(1)</script>") || t.startsWith("<script>")), f.wire + ": the hostile text is drawn as text: " + texts.join(" | "));
    assert.ok(texts.includes("__proto__"), f.wire + ": a name __proto__ is drawn");
  }
});

test("writeDom sets what it is handed: each colour its primitive's literal, the theme's font, no var() (look contract §9)", () => {
  // A theme as the page's adapter hands it: tokens resolved off the stylesheet, so the picture carries them as values.
  const theme = { base: "dark", tokens: { "--chart-grid": [1, 2, 3], "--chart-canvas": [250, 251, 252], "--font-sans": "Inter, \"Noto Sans\", sans-serif" } };
  let colours = 0, fonts = 0, pictures = 0;
  let fades = 0;
  const cases = ["look-one-series", "look-eight-series", "hostile", "look-empty-zero-rows"].map(bar)
    .concat(["two-series", "hostile"].map((n) => FIXTURES.find((x) => x.wire === "line" && x.name === n)))
    .concat(["stacked", "hostile"].map((n) => FIXTURES.find((x) => x.wire === "area" && x.name === n)));
  for (const f of cases) {
    const name = f.wire + "/" + f.name;
    const prims = R.render(inputOf(f.fixture, 960, 540, { theme })).primitives;
    const { svg } = fakeDocument();
    R.writeDom(prims, svg, theme);
    const els = [], gradients = new Map();
    walk(svg, (el) => {
      if (el.tag === "linearGradient") gradients.set(Object.fromEntries(el.attrs).id, el);
      else if (el.tag !== "svg" && !FADE.has(el.tag)) els.push(el);
      for (const [n, v] of el.attrs) assert.ok(!String(v).includes("var("), name + ": " + n + "=" + v);
    });
    // The DOM is the plan's tree in document order, one element per primitive (a fade's gradient and stops are the
    // faded path's paint, not primitives): pair them and compare value by value.
    assert.equal(els.length, prims.length, name + ": one element per primitive");
    for (let i = 0; i < prims.length; i++) {
      const p = prims[i], a = Object.fromEntries(els[i].attrs);
      if (p.fill !== undefined && p.k === "path" && p.fade !== null) {
        // The faded fill names its gradient, whose two stops carry the primitive's colour and the fade's opacities.
        const id = /^url\(#(dp-fade-[A-Za-z0-9_-]+)\)$/.exec(a.fill);
        assert.ok(id && gradients.has(id[1]), name + " #" + i + " fade: " + a.fill);
        const stops = gradients.get(id[1]).children.map((c) => Object.fromEntries(c.attrs));
        assert.deepEqual(stops.map((x) => x["stop-color"]), [p.color, p.color], name + " #" + i + " stop colours");
        assert.deepEqual(stops.map((x) => Number(x["stop-opacity"])), [p.fade[0], p.fade[1]], name + " #" + i + " stop opacities");
        colours++;
        fades++;
      } else if (p.fill !== undefined) {
        assert.equal(a.fill, p.fill === null ? "none" : p.color, name + " #" + i + " fill");
        colours++;
      }
      if (p.stroke !== undefined && p.stroke !== null) {
        assert.equal(a.stroke, p.strokeColor, name + " #" + i + " stroke");
        colours++;
      }
      if (p.k === "text") {
        assert.equal(a["font-family"], theme.tokens["--font-sans"], name + " #" + i + " font");
        fonts++;
      }
    }
    pictures++;
  }
  assert.equal(pictures, 8);
  assert.ok(fades >= 3, "faded fills compared: " + fades);
  assert.ok(colours > 150 && fonts > 50, "colours " + colours + ", fonts " + fonts + " compared");
  const grid = R.render(inputOf(bar("look-one-series").fixture, 480, 360, { theme })).primitives.filter((p) => p.stroke === "--chart-grid");
  assert.ok(grid.length > 0 && grid.every((p) => p.strokeColor === "#010203"), "the grid carries the resolved colour");
  // No theme: the engine's default font, as a literal.
  const { svg } = fakeDocument();
  R.writeDom(R.render(inputOf(bar("look-one-series").fixture, 480, 360)).primitives, svg);
  const families = [];
  walk(svg, (el) => {
    for (const [n, v] of el.attrs) if (n === "font-family") families.push(v);
  });
  assert.ok(families.length > 0 && families.every((v) => v === "Noto Sans"), families.join(" | "));
});

test("writeString escapes every text and attribute value: the hostile fixture holds no element of its own, in both forms", () => {
  for (const f of HOSTILES) hostileString(f);
});

function hostileString(f) {
  const prims = R.render(inputOf(f.fixture, 960, 540)).primitives;
  for (const form of ["page", "standalone"]) {
    const label = f.wire + " " + form;
    const svg = R.writeString(prims, form, "light");
    assert.equal((svg.match(/<script/gi) || []).length, 0, label + ": a script element");
    assert.equal((svg.match(/<img/gi) || []).length, 0, label + ": an img element");
    assert.ok(svg.includes("&lt;script&gt;"), label + ": the script text is escaped");
    assert.ok(svg.includes("&amp;"), label);
    assert.ok(svg.includes("&quot;"), label);
    const names = [...svg.matchAll(/\s([A-Za-z:-]+)="/g)].map((m) => m[1]);
    for (const n of names) assert.ok(NAMES.has(n), label + ": attribute name " + n);
    // Inside tags only (a value escapes >, so a tag ends at the first >): the hostile TEXT holds "onload=" as text.
    // A value holds no raw quote (escaped), so name="value" pairs parse exactly; a value may hold " onmouseover=" as text.
    const attrNames = (svg.match(/<[^>]*>/g) || []).flatMap((tag) => [...tag.matchAll(/\s([^\s="]+)="[^"]*"/g)].map((m) => m[1]));
    assert.ok(attrNames.length > 100, "attributes parsed: " + attrNames.length);
    assert.deepEqual(attrNames.filter((n) => /^(href|xlink:href|style|on[a-z]+)$/i.test(n)), [], label + ": href, style or an event attribute");
    for (const n of attrNames) assert.ok(NAMES.has(n), label + ": attribute name " + n);
    assert.ok(/onload=/.test(svg), label + ": the hostile text is there, as text (non-vacuity of the check above)");
    const tags = new Set([...svg.matchAll(/<([A-Za-z]+)/g)].map((m) => m[1]));
    for (const t of tags) assert.ok(TAGS.has(t), label + ": element " + t);
    if (f.wire === "bar") assert.deepEqual([...tags].sort(), ["g", "line", "path", "rect", "svg", "text"]);
  }
}

test("no fixture's picture writes an attribute name outside the writers' constants, nor href, style or an event", () => {
  let pictures = 0;
  for (const f of FIXTURES) {
    for (const form of ["page", "standalone"]) {
      const svg = R.writeString(R.render(inputOf(f.fixture, 960, 540)).primitives, form, "dark");
      for (const m of svg.matchAll(/\s([A-Za-z:-]+)="/g)) assert.ok(NAMES.has(m[1]), f.name + " " + form + ": attribute name " + m[1]);
      assert.ok(!/<(script|style|foreignObject|a|image|use)\b/i.test(svg), f.name + ": a forbidden element");
      for (const m of svg.matchAll(/<([A-Za-z]+)/g)) assert.ok(TAGS.has(m[1]), f.wire + "/" + f.name + ": element " + m[1]);
      pictures++;
    }
  }
  assert.ok(pictures >= 182, "pictures checked: " + pictures);
});

test("escape covers & < > \" ' and the three white-space controls", () => {
  assert.equal(R.escape("&<>\"'\t\n\r"), "&amp;&lt;&gt;&quot;&#39;&#9;&#10;&#13;");
  assert.equal(R.escape("plain"), "plain");
});

test("the two forms: page writes var(--token, value) and classes; standalone writes literal colours", () => {
  const f = bar("look-one-series");
  const prims = R.render(inputOf(f.fixture, 480, 360)).primitives;
  const page = R.writeString(prims, "page", "light"), alone = R.writeString(prims, "standalone", "light");
  assert.ok(page.includes('fill="var(--chart-canvas, '), "page form names the token");
  assert.ok(page.includes('font-family="var(--font-sans, '), "page form names the font token");
  assert.ok(!alone.includes("var("), "standalone form names no token");
  assert.ok(alone.includes('class="dp-mark"'), "classes in both forms");
  assert.ok(alone.startsWith('<svg xmlns="http://www.w3.org/2000/svg" width="480" height="360" viewBox="0 0 480 360" class="dp-chart" role="img">'), alone.slice(0, 120));
  assert.throws(() => R.writeString(prims, "inline", "light"), /form is page or standalone/);
});

test("nested groups write as nested elements, every tag closed, the legend entries inside their group", () => {
  const f = bar("look-eight-series");
  const svg = R.writeString(R.render(inputOf(f.fixture, 960, 540)).primitives, "standalone", "light");
  let depth = 0, legend = 0, maxDepth = 0;
  for (const m of svg.matchAll(/<(\/?)([a-z]+)\b[^>]*?(\/?)>/g)) {
    if (m[1]) depth--;
    else if (!m[3]) {
      depth++;
      if (m[2] === "g" && m[0].includes('class="dp-legend')) legend++;
    }
    maxDepth = Math.max(maxDepth, depth);
  }
  assert.equal(depth, 0, "every element closed");
  assert.equal(legend, 8, "eight legend entries");
  assert.ok(maxDepth >= 3, "groups nest inside the svg: " + maxDepth);
  assert.throws(() => R.write.plan([{ k: "group", role: "mark", n: 2, series: null, legend: null, label: null, on: true }], "page", "x"), /counts more records than follow/);
});

test("the text a writer draws is the shown text, the full text in the primitive (I4's carrier)", () => {
  const f = bar("look-long-labels");
  const r = R.render(inputOf(f.fixture, 360, 240));
  const cut = r.primitives.filter((p) => p.k === "text" && p.shown !== null);
  assert.ok(cut.length > 0, "something is cut at 360 × 240");
  const svg = R.writeString(r.primitives, "standalone", "light");
  for (const t of cut) assert.ok(svg.includes(">" + R.escape(t.shown) + "<"), t.shown);
});

// ---------------------------------------------------------------- lane 990: D4 (the legend button), D6 (the description)

test("D4: every legend entry is written as a button, pressed while its series is shown, in both writers", () => {
  const f = bar("look-hidden-series");
  const out = R.render(inputOf(f.fixture, 960, 540));
  const entries = out.primitives.filter((p) => p.k === "group" && p.legend !== null);
  assert.ok(entries.length >= 2 && entries.some((e) => !e.on), "a struck entry is among them: " + entries.length);
  const svg = R.writeString(out.primitives, "standalone", "light");
  const buttons = [...svg.matchAll(/<g class="dp-legend( dp-off)?"[^>]*role="button" aria-pressed="(true|false)"/g)];
  assert.equal(buttons.length, entries.length, "one button per entry");
  for (const b of buttons) assert.equal(b[2], b[1] ? "false" : "true", "pressed = shown: " + b[0]);
  const { svg: dom } = fakeDocument();
  R.writeDom(out.primitives, dom);
  const pressed = [];
  walk(dom, (el) => {
    const a = Object.fromEntries(el.attrs);
    if (a.role === "button") pressed.push(a["aria-pressed"]);
  });
  assert.deepEqual(pressed, Array.from(entries, (e) => (e.on ? "true" : "false")));
});

test("D6: describe names the type, the series, the mark count and the description; both writers write it as text, first", () => {
  const f = FIXTURES.find((x) => x.wire === "line" && x.name === "two-series");
  const out = R.render(inputOf(f.fixture, 960, 540));
  const about = R.write.describe(out, { display_name: "Revenue <b>", description: "Monthly & yearly" });
  const names = out.summary.series.map((s) => s.title).join(", ");
  assert.equal(about.title, "Revenue <b>");
  assert.equal(about.desc, "Line chart; series: " + names + "; " + R.text.value(out.summary.marks, "integer") + " marks. Monthly & yearly");
  assert.equal(R.write.describe({ type: "bar", state: "no-data", summary: { series: [], marks: 0 } }, {}).desc, "Bar chart; no data.");
  const svg = R.writeString(out.primitives, "standalone", "light", about);
  assert.ok(svg.includes('role="img"><title>Revenue &lt;b&gt;</title><desc>Line chart; series: '), svg.slice(0, 300));
  assert.ok(svg.includes("Monthly &amp; yearly</desc>"));
  assert.ok(!R.writeString(out.primitives, "standalone", "light").includes("<title>"), "no about: no title");
  const { svg: dom } = fakeDocument();
  R.writeDom(out.primitives, dom, "light", about);
  assert.equal(Object.fromEntries(dom.attrs).role, "img");
  assert.deepEqual(dom.children.slice(0, 2).map((c) => [c.tag, c.text]), [["title", about.title], ["desc", about.desc]]);
  assert.throws(() => R.writeDom(out.primitives, dom, "light", { title: 1 }), /about is \{title, desc\}/);
});

test("append writes primitives after a picture's children, removes nothing, and returns exactly what it made", () => {
  const { svg, calls } = fakeDocument();
  const out = R.render(inputOf(bar("look-one-series").fixture, 480, 360));
  R.writeDom(out.primitives, svg);
  const before = svg.children.length;
  const tip = [];
  R.prim.group(tip, { role: "tooltip" }, [R.prim.rect({ role: "tooltip", x: 1, y: 2, w: 30, h: 20, fill: "--chart-tip-surface", color: "#ffffff" }), R.prim.text({ role: "tooltip", x: 4, y: 14, text: "<b>x</b>", size: 12, fill: "--chart-tip-ink", color: "#0f172a" })]);
  const removed = calls.filter((c) => c === "removeChild").length;
  const made = R.write.append(tip, svg, "light");
  assert.equal(calls.filter((c) => c === "removeChild").length, removed, "nothing removed");
  assert.equal(made.length, 1);
  assert.equal(svg.children.length, before + 1);
  assert.equal(svg.children[before], made[0]);
  assert.equal(Object.fromEntries(made[0].attrs).class, "dp-tooltip");
  assert.equal(made[0].children[1].text, "<b>x</b>", "a text node, never markup");
});
