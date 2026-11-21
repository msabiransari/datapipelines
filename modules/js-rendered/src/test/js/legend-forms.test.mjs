// Brief 1054 (lane G6, record §9 row G6): the legend's forms. A.1 what a model hands the legend (C.legendModel, every
// throw by its message); A.2 the switch with a key per entry (test_switch); A.3 the legend of keys (test_keys, D10: the
// contract's LA15 clause for the test type); A.4 the two writers; A.5 the description (summary.keys, describe); A.6 the
// look field and the tokens. The switch of bar, line and area is held by the existing files, unedited (D12).
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, engineWith, typeFixtures, typeInputOf, chartFixtures, inputOf, canonical } from "./engine.mjs";
import { WIDTHS, HEIGHTS } from "./geometry.mjs";

const TYPES = ["test_switch", "test_keys"];
const R = engineWith(TYPES);
const SWITCH = typeFixtures("test_switch");
const KEYS = typeFixtures("test_keys");
const fx = (list, name) => {
  const f = list.find((x) => x.name === name);
  assert.ok(f, "fixture " + name);
  return f.fixture;
};
const sw = (name) => fx(SWITCH, name);
const ks = (name) => fx(KEYS, name);
const plain = (v) => JSON.parse(JSON.stringify(v));
/** The emit's seven frames (emit.mjs FRAMES). */
const FRAMES = [[960, 540], [480, 360], [300, 180], [120, 40], [60, 20], [360, 240], [200, 140]];
const draw = (f, w, h, extra = {}, E = R) => E.render(typeInputOf(f, w, h, extra));
/** A copy of a fixture with its document's config.encoding.color.legend set (null: no legend). */
function withLegend(f, legend) {
  const c = plain(f);
  c.document.config.encoding.color.legend = legend;
  return c;
}

/** The legend groups of a picture, each with its children: [{g, kids}]. */
function legendGroups(prims) {
  const out = [];
  for (let i = 0; i < prims.length; i++) {
    const p = prims[i];
    if (p.k === "group" && p.role === "legend") out.push({ g: p, kids: prims.slice(i + 1, i + 1 + p.n) });
  }
  return out;
}
const legendPrims = (prims) => prims.filter((p) => p.role === "legend");
const keyOf = (e) => e.kids.find((k) => k.k !== "text");
const nameOf = (e) => e.kids.find((k) => k.k === "text");
const marks = (prims) => prims.filter((p) => p.role === "mark" && p.k !== "group" && p.k !== "text");
/** [x0, y0, x1, y1] around primitives (rect, circle, text by its anchor point). */
function bounds(list) {
  let x0 = Infinity, y0 = Infinity, x1 = -Infinity, y1 = -Infinity;
  for (const p of list) {
    const b = p.k === "rect" ? [p.x, p.y, p.x + p.w, p.y + p.h] : p.k === "circle" ? [p.cx - p.r, p.cy - p.r, p.cx + p.r, p.cy + p.r] : [p.x, p.y, p.x, p.y];
    x0 = Math.min(x0, b[0]);
    y0 = Math.min(y0, b[1]);
    x1 = Math.max(x1, b[2]);
    y1 = Math.max(y1, b[3]);
  }
  return [x0, y0, x1, y1];
}
/** Where the legend stands against the marks: top, bottom, left or right. */
function sideOf(prims) {
  const l = bounds(legendPrims(prims).filter((p) => p.k !== "group")), m = bounds(marks(prims));
  if (l[3] <= m[1]) return "top";
  if (l[1] >= m[3]) return "bottom";
  if (l[2] <= m[0]) return "left";
  if (l[0] >= m[2]) return "right";
  return "inside";
}

// ---------------------------------------------------------------- A.1 what a model hands (D2)

const C = R.chrome;
const series = [{ id: "a", title: "A", colour: "--chart-series-1" }, { id: "b", title: "B", colour: "--chart-series-2" }];
const entry = (over = {}) => Object.assign({ id: "e1", title: "One", key: "legendKeyBar", colour: "--chart-series-1" }, over);
/** The engine runs in its own context: match a throw by its message (an anchored pattern), not by its realm. */
function throwsMessage(fn, re, why) {
  let message = null;
  try {
    fn();
  } catch (e) {
    message = e.message;
  }
  assert.ok(message !== null, why + ": no throw");
  assert.match(message, re, why);
}
const keysModel = (over = {}) => Object.assign({ series, keys: [entry(), entry({ id: "e2" })], keysAlone: false }, over);

/** Every throw of D2, each a model and the message it must give (A.1's floor: 12). */
const THROWS = [
  ["a series' legendKey names no look field", { series: [{ id: "a", title: "A", legendKey: "legendKeyNope" }] }, /^legend: series a: legendKey legendKeyNope is no look-table field$/],
  ["a series' legendKey names a field that is no key form", { series: [{ id: "a", title: "A", legendKey: "edge" }] }, /^legend: series a: legendKey edge holds 0\.5, not a key form \(square, line, dot\)$/],
  ["keys is not a list", keysModel({ keys: { id: "x" } }), /^legend: keys is not a list$/],
  ["an entry is not an object", keysModel({ keys: [entry(), "e2"] }), /^legend: keys\[1\] is not an object$/],
  ["an entry lacks a member", keysModel({ keys: [{ id: "e1", title: "One", key: "legendKeyBar" }] }), /^legend: keys\[0\]: colour is missing$/],
  ["an id is empty", keysModel({ keys: [entry({ id: "" })] }), /^legend: keys\[0\]: id is empty$/],
  ["an id stands twice", keysModel({ keys: [entry(), entry()] }), /^legend: keys\[1\]: id e1 stands twice$/],
  ["a colour is no colour token", keysModel({ keys: [entry({ colour: "--chart-line" })] }), /^legend: keys\[0\]: colour --chart-line is no colour token$/],
  ["keys beside shade", keysModel({ shade: { scale: null, legend: undefined, title: "v" } }), /^legend: a model hands keys or shade, not both$/],
  ["keys beside direct: true", keysModel({ direct: true }), /^legend: a model hands keys or direct: true, not both$/],
  ["keys without keysAlone", keysModel({ keysAlone: undefined }), /^legend: keys is handed without keysAlone true or false$/],
  ["keys with a keysAlone that is not true or false", keysModel({ keysAlone: "yes" }), /^legend: keys is handed without keysAlone true or false$/],
  ["keysAlone without keys", { series, keysAlone: true }, /^legend: keysAlone is handed without keys$/],
  // Beyond the floor: the closed entry, the kinds of id and title, an entry's key.
  ["an entry carries a member that is not one", keysModel({ keys: [entry({ extra: 1 })] }), /^legend: keys\[0\]: extra is not a member$/],
  ["an id is not a text", keysModel({ keys: [entry({ id: 7 })] }), /^legend: keys\[0\]: id is not a text$/],
  ["a title is not a text", keysModel({ keys: [entry({ title: null })] }), /^legend: keys\[0\]: title is not a text$/],
  ["an entry's key names no key form", keysModel({ keys: [entry({ key: "morePrefix" })] }), /^legend: keys\[0\]: key morePrefix holds \+, not a key form \(square, line, dot\)$/],
  ["an entry's key is no look field", keysModel({ keys: [entry({ key: "dot" })] }), /^legend: keys\[0\]: key dot is no look-table field$/],
  ["a colour that is no token at all", keysModel({ keys: [entry({ colour: "#ff0000" })] }), /^legend: keys\[0\]: colour #ff0000 is no colour token$/],
];

test("A.1: every defect of what a model hands its legend throws, naming the member (floor 12)", () => {
  for (const [why, m, re] of THROWS) throwsMessage(() => C.legendModel(m), re, why);
  console.log(`A.1 throws: ${THROWS.length} (floor 12)`);
  assert.ok(THROWS.length >= 12);
});

test("A.1: a model with neither member gives what the base gives; the colour tokens of either kind are taken", () => {
  const m = { series, legendKey: "legendKeyLine" };
  const lm = C.legendModel(m);
  assert.equal(lm.keys, null);
  assert.equal(lm.alone, null);
  assert.equal(lm.keyOf("a"), "legendKeyLine");
  assert.equal(C.legendModel({ series }).keyOf("a"), undefined, "no model legendKey: legendPrims takes legendKeyBar");
  const own = C.legendModel({ series: [{ id: "a", title: "A", legendKey: "legendKeyPoint" }, { id: "b", title: "B" }], legendKey: "legendKeyArea" });
  assert.deepEqual([own.keyOf("a"), own.keyOf("b")], ["legendKeyPoint", "legendKeyArea"]);
  // A token of the kind `colour` (alpha allowed) and one of the kind `opaque` are both colours.
  const ok = C.legendModel(keysModel({ keys: [entry({ colour: "--chart-ink-2" }), entry({ id: "e2", colour: "--chart-seq-3" })], keysAlone: true }));
  assert.deepEqual(plain(ok.keys.map((e) => e.colour)), ["--chart-ink-2", "--chart-seq-3"]);
  assert.equal(ok.alone, true);
  // Bar's series carry `key` (their grouping key) and hand no legendKey: no throw, every product fixture draws.
  const E = engine();
  for (const f of chartFixtures()) E.render(inputOf(f.fixture, 480, 360));
});

test("A.1: through the test types: a fixture naming a bad field or a bad token is refused by the template's defect throw", () => {
  const badKey = plain(sw("three-forms"));
  badKey.document.config.test.keys.East = "legendKeyRing";
  assert.throws(() => draw(badKey, 960, 540), /legend: series string:East: legendKey legendKeyRing is no look-table field/);
  const badColour = plain(ks("two-squares-line"));
  badColour.document.config.test.keys[0].colour = "--chart-text";
  assert.throws(() => draw(badColour, 960, 540), /legend: keys\[0\]: colour --chart-text is no colour token/);
  // The throw comes before any state: also in a no-data draw (the D6 statement checks the model).
  const empty = plain(ks("two-squares-line"));
  empty.columns.s = [];
  empty.document.config.test.keysAlone = 2;
  assert.throws(() => draw(empty, 960, 540), /keysAlone true or false/);
});

// ---------------------------------------------------------------- A.2 the switch with a key per entry (test_switch)

test("A.2: three-forms: three switch groups, a square of the key's side, a line of --chart-line, a dot of --chart-point at the key's centre", () => {
  const r = draw(sw("three-forms"), 960, 540);
  const es = legendGroups(r.primitives);
  assert.deepEqual(es.map((e) => [e.g.series, e.g.legend, e.g.label, e.g.on]), [["string:North", "string:North", "North", true], ["string:South", "string:South", "South", true], ["string:East", "string:East", "East", true]]);
  const side = R.tables.look("legendKey") * 12;
  const [sq, ln, dot] = es.map(keyOf);
  assert.equal(sq.k, "rect");
  assert.equal(sq.h, Math.round(side * 100) / 100);
  assert.equal(sq.w, sq.h);
  assert.equal(ln.k, "rect");
  assert.equal(ln.h, 2, "--chart-line");
  assert.equal(ln.w, sq.w);
  assert.equal(dot.k, "circle");
  assert.equal(dot.role, "legend");
  assert.equal(dot.r, 4, "--chart-point");
  assert.equal(dot.series, "string:East");
  // The dot's centre is the centre of the square every form occupies: its row's centre and half the key in.
  const name = nameOf(es[2]), gap = R.tables.look("legendKeyGap") * 12;
  assert.ok(Math.abs(dot.cx - (name.x - gap - side / 2)) < 0.011, "dot.cx " + dot.cx);
  assert.ok(Math.abs(dot.cy - (sq.y + sq.h / 2)) < 0.011, "the dot shares the square's row centre");
  for (const [k, token] of [[sq, "--chart-series-1"], [ln, "--chart-series-2"], [dot, "--chart-series-3"]]) {
    assert.equal(k.fill, token);
    assert.equal(k.opacity, 1);
  }
});

test("A.2: each entry hidden in turn: its group off, its key at --chart-dim, its name struck, its series not drawn, the others as before", () => {
  const f = sw("three-forms");
  const base = legendGroups(draw(f, 960, 540).primitives);
  for (let i = 0; i < 3; i++) {
    const id = base[i].g.series;
    const r = draw(f, 960, 540, { hidden: [id] });
    const es = legendGroups(r.primitives);
    assert.equal(es[i].g.on, false, id);
    assert.equal(keyOf(es[i]).opacity, 0.25, id + ": --chart-dim");
    assert.equal(nameOf(es[i]).struck, true);
    assert.equal(marks(r.primitives).filter((p) => p.series === id).length, 0, id + " not drawn");
    assert.equal(r.summary.series.find((s) => s.id === id).hidden, true);
    for (let j = 0; j < 3; j++) if (j !== i) assert.deepEqual(es[j], base[j], "entry " + j + " unchanged while " + id + " is hidden");
  }
});

test("A.2: the two fallbacks: a series without legendKey takes the model's legendKey, and legendKeyBar without one", () => {
  const forms = (name) => legendGroups(draw(sw(name), 960, 540).primitives).map((e) => (keyOf(e).k === "circle" ? "dot" : keyOf(e).h === 2 ? "line" : "square"));
  assert.deepEqual(forms("fallback-model-key"), ["square", "dot", "line"]);
  assert.deepEqual(forms("fallback-no-key"), ["line", "dot", "square"]);
});

test("A.2: the four written places, legend: null, and +n at the compact level", () => {
  for (const o of ["top", "bottom", "left", "right"]) assert.equal(sideOf(draw(sw("legend-" + o), 960, 540).primitives), o, o);
  assert.equal(legendPrims(draw(sw("legend-null"), 960, 540).primitives).length, 0);
  const r = draw(sw("eight-long-names"), 300, 180);
  assert.equal(r.level, "compact");
  assert.ok(legendPrims(r.primitives).some((p) => p.k === "text" && /^\+\d+$/.test(p.text)), "+n at compact");
});

// ---------------------------------------------------------------- A.3 the legend of keys (test_keys, D10)

test("A.3/D10: over every test_keys fixture at every frame of the sweep and of the emit: no legend group is a switch, no legend primitive has a series", () => {
  let pictures = 0, groups = 0;
  const frames = [...FRAMES];
  for (const w of WIDTHS) for (const h of HEIGHTS) frames.push([w, h]);
  for (const f of KEYS) {
    for (const [w, h] of frames) {
      const r = draw(f.fixture, w, h);
      pictures++;
      for (const p of legendPrims(r.primitives)) {
        if (p.k === "group") {
          groups++;
          assert.equal(p.legend, null, f.name + " " + w + "x" + h);
          assert.equal(p.label, null);
          assert.equal(p.on, true);
        }
        assert.equal(p.series, null, f.name + " " + w + "x" + h + ": a legend primitive with a series");
        if (p.k !== "group" && p.k !== "text") assert.equal(p.opacity, 1);
        if (p.k === "text") assert.equal(p.struck, false);
      }
    }
  }
  console.log(`A.3 key-only sweep: ${KEYS.length} fixtures x ${frames.length} frames = ${pictures} pictures, ${groups} key-only groups`);
  assert.ok(groups > 1000, "non-vacuity: key-only groups seen " + groups);
});

test("A.3: each key is filled with its entry's token, its colour the theme's value, in both themes; squares, a line and a dot", () => {
  for (const theme of ["light", "dark"]) {
    for (const name of ["two-squares-line", "square-line-dot"]) {
      const f = ks(name), want = f.document.config.test.keys;
      const es = legendGroups(draw(f, 960, 540, { theme }).primitives);
      assert.equal(es.length, want.length, name);
      es.forEach((e, i) => {
        const k = keyOf(e);
        assert.equal(k.fill, want[i].colour, name + " " + i + " token");
        assert.equal(k.color, R.theme.resolve(theme).css(want[i].colour), name + " " + i + " " + theme);
        assert.equal(nameOf(e).text, want[i].title);
      });
    }
  }
  const forms = (name) => legendGroups(draw(ks(name), 960, 540).primitives).map((e) => (keyOf(e).k === "circle" ? "dot" : keyOf(e).h === 2 ? "line" : "square"));
  assert.deepEqual(forms("two-squares-line"), ["square", "square", "line"]);
  assert.deepEqual(forms("square-line-dot"), ["square", "line", "dot"]);
  // At least one colour of the record's check is not a series colour.
  assert.ok(ks("two-squares-line").document.config.test.keys.some((k) => !R.theme.SERIES.includes(k.colour)));
});

test("A.3: none at minimal, under legend: null and for an empty list (the empty list draws as its legend: null twin)", () => {
  for (const f of KEYS) {
    const r = draw(f.fixture, 120, 40);
    assert.equal(r.level, "minimal");
    assert.equal(legendPrims(r.primitives).length, 0, f.name + " at minimal");
  }
  assert.equal(legendPrims(draw(ks("legend-null"), 960, 540).primitives).length, 0);
  const empty = ks("empty-list");
  for (const [w, h] of FRAMES) {
    const r = draw(empty, w, h);
    assert.equal(legendPrims(r.primitives).length, 0);
    assert.equal(canonical(r), canonical(draw(withLegend(empty, null), w, h)), w + "x" + h);
  }
});

test("A.3: one entry: keysAlone true keeps one key at the full and compact levels (none at minimal or under legend: null); false never has one", () => {
  const alone = ks("one-key-alone"), side = keyOf(legendGroups(draw(ks("two-squares-line"), 960, 540).primitives)[0]).w;
  const full = draw(alone, 960, 540);
  assert.equal(full.level, "full");
  let es = legendGroups(full.primitives);
  assert.equal(es.length, 1);
  assert.equal(keyOf(es[0]).w, side, "the same key");
  assert.equal(nameOf(es[0]).text, "Within range");
  assert.equal(sideOf(full.primitives), "top");
  const compact = draw(alone, 300, 180);
  assert.equal(compact.level, "compact");
  es = legendGroups(compact.primitives);
  assert.equal(es.length, 1);
  assert.equal(sideOf(compact.primitives), "bottom", "compact: one row at the bottom");
  assert.equal(legendPrims(draw(alone, 120, 40).primitives).length, 0);
  assert.equal(legendPrims(draw(withLegend(alone, null), 960, 540).primitives).length, 0);
  let pictures = 0;
  for (const w of WIDTHS) {
    for (const h of HEIGHTS) {
      assert.equal(legendPrims(draw(ks("one-key-not-alone"), w, h).primitives).length, 0, w + "x" + h);
      pictures++;
    }
  }
  assert.equal(pictures, 175);
});

test("A.3: legendPlace asked directly with one entry: alone true gives top, the written orient and compact; without it or false, null", () => {
  const one = [{ id: "e", title: "E" }];
  assert.equal(C.legendPlace(one, [], "full", undefined, false, true), "top");
  for (const o of ["top", "bottom", "left", "right"]) assert.equal(C.legendPlace(one, [], "full", { orient: o }, false, true), o);
  assert.equal(C.legendPlace(one, [], "compact", undefined, false, true), "compact");
  assert.equal(C.legendPlace(one, [], "full", undefined, false), null);
  assert.equal(C.legendPlace(one, [], "full", undefined, false, false), null);
  for (const alone of [true, false, undefined]) {
    assert.equal(C.legendPlace(one, [], "minimal", undefined, false, alone), null);
    assert.equal(C.legendPlace(one, [], "full", null, false, alone), null);
  }
});

test("A.3: the four written places and +n at the compact level", () => {
  for (const o of ["top", "bottom", "left", "right"]) assert.equal(sideOf(draw(ks("legend-" + o), 960, 540).primitives), o, o);
  const r = draw(ks("eight-long-names"), 300, 180);
  assert.equal(r.level, "compact");
  assert.ok(legendPrims(r.primitives).some((p) => p.k === "text" && /^\+\d+$/.test(p.text)), "+n at compact");
});

test("A.3: the hidden list is read by nothing: a list naming every series and every entry draws as the empty list", () => {
  let compared = 0;
  for (const f of KEYS) {
    const ids = (f.fixture.document.config.test.keys || []).map((k) => k.id);
    for (const [w, h] of FRAMES) {
      const r0 = draw(f.fixture, w, h);
      const all = ids.concat(r0.summary.series.map((s) => s.id));
      const r = draw(f.fixture, w, h, { hidden: all });
      assert.equal(canonical(r), canonical(r0), f.name + " " + w + "x" + h);
      for (const s of r.summary.series) assert.equal(s.hidden, false);
      compared++;
    }
  }
  // The trap: an entry's id equals a series id; that series stays drawn with the id on the list.
  const trap = draw(ks("id-equals-series"), 960, 540, { hidden: ["string:North"] });
  assert.ok(marks(trap.primitives).some((p) => p.series === "string:North"), "North is drawn");
  console.log(`A.3 hidden-list comparisons: ${compared}`);
});

// ---------------------------------------------------------------- A.4 the two writers (D5)

/** A fake document recording the tree the DOM writer builds (writers.test.mjs's form). */
function fakeDocument() {
  function element(tag) {
    const el = {
      tag, attrs: [], children: [], text: null,
      setAttribute(n, v) {
        el.attrs.push([n, v]);
      },
      appendChild(c) {
        el.children.push(c);
        return c;
      },
      removeChild(c) {
        el.children.splice(el.children.indexOf(c), 1);
        return c;
      },
      get firstChild() {
        return el.children.length ? el.children[0] : null;
      },
      set textContent(t) {
        el.text = t;
      },
    };
    return el;
  }
  const svg = element("svg");
  svg.ownerDocument = { createElementNS: (ns, tag) => element(tag) };
  return svg;
}
/** The DOM tree as the string writer writes an element (its own escape), to compare the two writers' pictures. */
function serialize(el) {
  let inner = el.text !== null ? R.escape(el.text) : "";
  for (const c of el.children) inner += serialize(c);
  const a = el.attrs.map(([n, v]) => " " + n + '="' + R.escape(v) + '"').join("");
  return "<" + el.tag + a + (inner === "" && el.text === null ? "/>" : ">" + inner + "</" + el.tag + ">");
}
function walk(el, f) {
  f(el);
  for (const c of el.children) walk(c, f);
}
function both(r, about) {
  const dom = fakeDocument();
  R.writeDom(r.primitives, dom, "light", about);
  const str = R.writeString(r.primitives, "standalone", "light", about);
  return { dom, str, body: str.slice(str.indexOf(">") + 1, -"</svg>".length) };
}

test("A.4: both writers give the same picture for both types; a key-only legend has no switch attribute, a switch three buttons with a dot circle", () => {
  let pictures = 0;
  for (const f of [...SWITCH, ...KEYS]) {
    for (const [w, h] of FRAMES) {
      const { dom, body } = both(draw(f.fixture, w, h));
      assert.equal(dom.children.map(serialize).join(""), body, f.wire + "/" + f.name + " " + w + "x" + h);
      pictures++;
    }
  }
  console.log(`A.4 writer pictures compared: ${pictures}`);
  for (const f of KEYS) {
    const { dom, str } = both(draw(f.fixture, 960, 540));
    let legendEls = 0;
    walk(dom, (el) => {
      const a = Object.fromEntries(el.attrs);
      assert.equal(a.role === "button" || "data-legend" in a || "aria-pressed" in a, false, f.name + ": a switch attribute");
      if (typeof a.class === "string" && a.class.startsWith("dp-legend")) {
        if (el.tag === "g") legendEls++;
        assert.equal("data-series" in a, false, f.name + ": data-series on a legend element");
        assert.equal("aria-label" in a, false, f.name + ": aria-label on a legend group");
      }
    });
    assert.equal((str.match(/role="button"|data-legend|aria-pressed/g) || []).length, 0, f.name);
    if (f.name === "two-squares-line") assert.equal(legendEls, 3);
  }
  const { dom, str } = both(draw(sw("three-forms"), 960, 540));
  const buttons = [];
  walk(dom, (el) => {
    const a = Object.fromEntries(el.attrs);
    if (a.role === "button") buttons.push([a["aria-pressed"], el.children.map((c) => c.tag)]);
  });
  assert.deepEqual(buttons, [["true", ["rect", "text"]], ["true", ["rect", "text"]], ["true", ["circle", "text"]]]);
  assert.equal((str.match(/role="button" aria-pressed="true"/g) || []).length, 3);
});

test("A.4: a title with markup characters and quotes is text in both writers", () => {
  const r = draw(ks("markup-titles"), 960, 540);
  const { dom, str } = both(r);
  const texts = [], tags = new Set();
  walk(dom, (el) => {
    tags.add(el.tag);
    if (el.text !== null) texts.push(el.text);
  });
  assert.ok(texts.includes("A <b>bold</b> & co"), texts.join(" | "));
  assert.ok(texts.includes("Quote \" and ' apostrophe"));
  assert.ok(!tags.has("b"));
  assert.ok(str.includes(">A &lt;b&gt;bold&lt;/b&gt; &amp; co<"), "escaped in the string");
  assert.ok(str.includes(">Quote &quot; and &#39; apostrophe<"));
  assert.equal((str.match(/<b>/g) || []).length, 0);
  // The description names them as text too, in both writers.
  const about = R.write.describe(r, ks("markup-titles").document);
  const s2 = R.writeString(r.primitives, "standalone", "light", about);
  assert.ok(s2.includes("legend: A &lt;b&gt;bold&lt;/b&gt; &amp; co, Quote &quot; and &#39; apostrophe; 3 marks.</desc>"), s2.slice(0, 400));
  const d2 = fakeDocument();
  R.writeDom(r.primitives, d2, "light", about);
  assert.equal(d2.children[1].text, "Test_keys chart; series: Amount; legend: A <b>bold</b> & co, Quote \" and ' apostrophe; 3 marks.");
});

// ---------------------------------------------------------------- A.5 the description (D6)

test("A.5: summary.keys names a key list's entries in order, {id, title} only, drawn or not; describe adds them after the series", () => {
  const cases = [
    ["two-squares-line", 960, 540, [["up", "Increase"], ["down", "Decrease"], ["total", "Opening and total"]], "Test_keys chart; series: Amount; legend: Increase, Decrease, Opening and total; 3 marks."],
    ["one-key-alone", 960, 540, [["tone", "Within range"]], "Test_keys chart; series: Amount; legend: Within range; 3 marks."],
    ["one-key-not-alone", 960, 540, [["tone", "Within range"]], "Test_keys chart; series: Amount; legend: Within range; 3 marks."],
    ["two-squares-line", 120, 40, [["up", "Increase"], ["down", "Decrease"], ["total", "Opening and total"]], "Test_keys chart; series: Amount; legend: Increase, Decrease, Opening and total; 3 marks."],
  ];
  for (const [name, w, h, want, desc] of cases) {
    const f = ks(name), r = draw(f, w, h);
    assert.deepEqual(plain(r.summary.keys), want.map(([id, title]) => ({ id, title })), name + " " + w + "x" + h);
    for (const k of r.summary.keys) assert.deepEqual(plain(Object.keys(k)), ["id", "title"]);
    const about = R.write.describe(r, f.document);
    assert.equal(about.desc, desc, name);
    const dom = fakeDocument();
    R.writeDom(r.primitives, dom, "light", about);
    assert.deepEqual(dom.children.slice(0, 2).map((c) => [c.tag, c.text]), [["title", "Test type test_keys"], ["desc", desc]]);
  }
  assert.equal(draw(ks("two-squares-line"), 120, 40).level, "minimal");
});

test("A.5: no summary.keys for an empty list, for every test_switch fixture, and for bar, line and area; their descriptions as before", () => {
  const empty = draw(ks("empty-list"), 960, 540);
  assert.equal("keys" in empty.summary, false);
  assert.equal(R.write.describe(empty, ks("empty-list").document).desc, "Test_keys chart; series: Amount; 3 marks.");
  for (const f of SWITCH) assert.equal("keys" in draw(f.fixture, 960, 540).summary, false, f.name);
  assert.equal(R.write.describe(draw(sw("three-forms"), 960, 540), {}).desc, "Test_switch chart; series: North, South, East; 6 marks.");
  const E = engine(), product = chartFixtures();
  for (const wire of ["bar", "line", "area"]) {
    const f = product.find((x) => x.wire === wire && x.name === "two-series") || product.find((x) => x.wire === wire && x.name === "look-eight-series");
    const r = E.render(inputOf(f.fixture, 960, 540));
    assert.equal(r.state, "rendered", wire + "/" + f.name);
    assert.equal("keys" in r.summary, false, wire + "/" + f.name);
    const type = wire[0].toUpperCase() + wire.slice(1), names = r.summary.series.map((s) => s.title);
    const desc = type + " chart; series: " + names.join(", ") + "; " + E.text.value(r.summary.marks, "integer") + " marks.";
    console.log(`A.5 ${wire}/${f.name}: ${desc}`);
    assert.equal(E.write.describe(r, {}).desc, desc);
  }
  // A no-data draw keeps "no data" whatever summary.keys holds.
  const nd = plain(ks("two-squares-line"));
  nd.columns.s = [];
  nd.expect.marks = 0;
  const rnd = draw(nd, 960, 540);
  assert.equal(rnd.state, "no-data");
  assert.equal(R.write.describe(rnd, {}).desc, "Test_keys chart; no data.");
});

// ---------------------------------------------------------------- A.6 the look field and the tokens (D8)

test("A.6: legendKeyPoint is a render field after legendKeyArea holding dot", () => {
  const names = Object.keys(R.tables.LOOK);
  assert.equal(names.indexOf("legendKeyPoint"), names.indexOf("legendKeyArea") + 1);
  assert.deepEqual(plain(R.tables.LOOK.legendKeyPoint), { value: "dot", consumer: "render", note: "§4.2: a point's legend key is a dot" });
});

/** The legend's primitives of a draw with one token changed, against the base: the fields that differ, by index. */
function legendDiff(f, theme, hidden = []) {
  const a = legendPrims(draw(f, 960, 540, { hidden }).primitives), b = legendPrims(draw(f, 960, 540, { hidden, theme }).primitives);
  assert.equal(a.length, b.length);
  const out = [];
  for (let i = 0; i < a.length; i++) for (const k of Object.keys(a[i])) if (JSON.stringify(a[i][k]) !== JSON.stringify(b[i][k])) out.push([i, a[i].k, k, a[i][k], b[i][k]]);
  return out;
}

test("A.6: --chart-point sizes the dot (capped at half the key's side), --chart-line the line key, --chart-dim a hidden dot; nothing else of the legend", () => {
  const f = sw("three-forms");
  const r3 = legendDiff(f, { base: "light", tokens: { "--chart-point": 3 } });
  assert.deepEqual(r3.map((d) => [d[1], d[2], d[3], d[4]]), [["circle", "r", 4, 3]]);
  const big = legendDiff(f, { base: "light", tokens: { "--chart-point": 9 } });
  const half = Math.round((R.tables.look("legendKey") * 12) / 2 * 100) / 100;
  assert.deepEqual(big.map((d) => [d[1], d[2], d[4]]), [["circle", "r", half]], "capped at half the key's side");
  const line = legendDiff(f, { base: "light", tokens: { "--chart-line": 3 } });
  assert.deepEqual(line.map((d) => [d[1], d[2]]).sort(), [["rect", "h"], ["rect", "y"]], "the line key's height (and its centred y)");
  const dim = legendDiff(f, { base: "light", tokens: { "--chart-dim": 0.5 } }, ["string:East"]);
  assert.deepEqual(dim.map((d) => [d[1], d[2], d[3], d[4]]), [["circle", "opacity", 0.25, 0.5]]);
});
