// The interaction layer over the legend's forms (brief 1054 A.7, D7; design record §9 row G6, §4.8; look contract C11,
// LA15): a legend of keys is none of the layer's entries (no stop of the keys, no click, no dimming, the picture stays
// an image), and a switch entry with a dot key is found by its dot (the one branch of D7), also where its name is cut
// to nothing. The engine is loaded as the page loads it, then the test-only types test_switch and test_keys from the
// engine module's test tree. chart-interactions.test.mjs, unedited, holds the switch of bar, line and area.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { readFileSync } from "node:fs";
import path from "node:path";
import vm from "node:vm";
import { loadEngine, fakeBrowser, descendants, STATIC_JS, ENGINE_DIR } from "./support/js-rendered.mjs";

const require = createRequire(import.meta.url);
const R = loadEngine();
const ENGINE_TESTS = path.resolve(ENGINE_DIR, "../../../../../../test");
for (const t of ["test_switch", "test_keys"]) vm.runInThisContext(readFileSync(path.join(ENGINE_TESTS, "js/types", t + ".js"), "utf8"), { filename: t + ".js" });
globalThis.JsRendered = R;
const layer = require(path.join(STATIC_JS, "datapipelines-chart-interactions.js"));
const I = layer._internal;

const BASE = R.measure.measurer(null).fn;
/** A measure under which a text that starts with "E" (the entry East) or the ellipsis is wider than any frame. */
const CUT_EAST = (t, s, w) => (t.charAt(0) === "E" || t.charAt(0) === R.measure.ELLIPSIS ? 1e4 : BASE(t, s, w));
const fixture = (type, name) => JSON.parse(readFileSync(path.join(ENGINE_TESTS, "fixtures/_types", type, name + ".json"), "utf8"));
const KEY_FIXTURES = ["two-squares-line", "square-line-dot", "eight-long-names", "legend-left", "legend-right", "legend-bottom", "one-key-alone", "id-equals-series", "markup-titles"];
function draw(type, name, frame, extra = {}) {
  const fx = fixture(type, name);
  return R.render(Object.assign({ type, document: fx.document, columns: fx.columns, types: fx.types, frame, theme: "light", measure: null, hidden: [] }, extra));
}
const modelOf = (out, measure = null) => {
  const m = I.modelOf(R, out, R.measure.measurer(measure).fn);
  m.theme = R.theme.resolve("light");
  return m;
};
function mounted(out, frame, measure = null) {
  const browser = fakeBrowser();
  const svg = browser.doc.createElementNS(R.write.NS, "svg");
  browser.host.appendChild(svg);
  R.writeDom(out.primitives, svg, "light");
  const toggles = [];
  const handle = layer.attach(svg, { output: out, theme: "light", measure, frame }, { toggle: (id) => toggles.push(id) });
  return { svg, handle, toggles };
}
/** The legend groups of a draw with their key and name primitives. */
function legendGroups(prims) {
  const out = [];
  prims.forEach((p, i) => {
    if (p.k === "group" && p.role === "legend") {
      const kids = prims.slice(i + 1, i + 1 + p.n);
      out.push({ g: p, key: kids.find((k) => k.k !== "text"), name: kids.find((k) => k.k === "text") || null });
    }
  });
  return out;
}
const centreOf = (k) => (k.k === "circle" ? { x: k.cx, y: k.cy } : { x: k.x + k.w / 2, y: k.y + k.h / 2 });
function nameCentre(t, measure = null) {
  const w = R.measure.measurer(measure).fn(t.shown !== null ? t.shown : t.text, t.size, t.weight);
  const a = R.tables.look("textAscent"), d = R.tables.look("textDescent");
  return { x: t.x + w / 2, y: t.y - (a * t.size - d * t.size) / 2 };
}
const KEYS = ["ArrowRight", "ArrowLeft", "ArrowUp", "ArrowDown", "Home", "End", "Tab"];
/** Every target the keys reach from entering and from each reached target, by every key (a closed walk). */
function reachable(model) {
  const seen = new Map(), todo = [null];
  while (todo.length > 0) {
    const t = todo.pop();
    for (const k of KEYS) {
      const r = I.keyTarget(model, t, k);
      if (r.target === null) continue;
      const id = JSON.stringify(r.target);
      if (!seen.has(id)) {
        seen.set(id, r.target);
        todo.push(r.target);
      }
    }
  }
  return [...seen.values()];
}

// ------------------------------------------------------------------------------------------- a legend of keys

test("a legend of keys: the model has no entry; the pointer at each key and each name finds none; the keys never stop on it", () => {
  let points = 0, walks = 0;
  for (const name of KEY_FIXTURES) {
    for (const frame of [{ w: 960, h: 540 }, { w: 300, h: 180 }]) {
      const out = draw("test_keys", name, frame);
      const groups = legendGroups(out.primitives);
      assert.ok(groups.length > 0, name + " draws a legend of keys at " + frame.w);
      const model = modelOf(out);
      assert.equal(model.entries.length, 0, name);
      for (const g of groups) {
        for (const pos of [centreOf(g.key), ...(g.name ? [nameCentre(g.name)] : [])]) {
          assert.equal(I.lookup(R, model, pos).entry, null, name + " at " + JSON.stringify(pos));
          points++;
        }
      }
      for (const t of reachable(model)) assert.notEqual(t.kind, "entry", name + ": a stop on the legend");
      walks++;
    }
  }
  console.log(`A.7 key-only: ${points} pointer places, ${walks} key walks`);
  assert.ok(points >= 40);
});

test("a legend of keys: a click on each key and name asks the host for nothing; hovering dims nothing; the picture stays an image", () => {
  const frame = { w: 960, h: 540 };
  for (const name of ["two-squares-line", "square-line-dot", "id-equals-series"]) {
    const out = draw("test_keys", name, frame);
    const { svg, toggles } = mounted(out, frame);
    assert.equal(svg.getAttribute("role"), "img", name);
    for (const c of svg.childNodes) assert.equal(c.getAttribute("aria-hidden"), null, name + ": aria-hidden on a child");
    const legendEls = descendants(svg).filter((e) => e.tagName === "g" && /dp-legend/.test(e.getAttribute("class") || ""));
    assert.equal(legendEls.length, legendGroups(out.primitives).length);
    for (const g of legendGroups(out.primitives)) {
      for (const pos of [centreOf(g.key), nameCentre(g.name)]) {
        svg.dispatch("pointermove", { clientX: pos.x, clientY: pos.y });
        assert.doesNotMatch(svg.getAttribute("class") || "", /dp-dimmed/, name + ": dimmed under the pointer");
        svg.dispatch("click", { clientX: pos.x, clientY: pos.y, target: svg });
      }
    }
    // A press whose target is the legend element itself (a screen reader's): no entry, so no switch either.
    for (const el of legendEls) svg.dispatch("click", { clientX: 0, clientY: 0, target: el });
    for (const k of ["ArrowRight", "Enter", " ", "End", "Enter"]) svg.dispatch("keydown", { key: k });
    assert.deepEqual(toggles, [], name + ": the host was asked for a switch");
  }
});

// ------------------------------------------------------------------------------------------- a switch with a dot key

test("a switch with a dot key: the pointer at the dot finds its entry, also where the name is cut to nothing", () => {
  const frame = { w: 960, h: 540 };
  const out = draw("test_switch", "three-forms", frame);
  const dot = legendGroups(out.primitives)[2];
  assert.equal(dot.key.k, "circle");
  assert.equal(I.lookup(R, modelOf(out), centreOf(dot.key)).entry, "string:East");
  const cut = draw("test_switch", "three-forms", frame, { measure: CUT_EAST });
  const g = legendGroups(cut.primitives).find((x) => x.g.legend === "string:East");
  assert.equal(g.name, null, "East's name is cut to nothing (no text drawn)");
  const model = modelOf(cut, CUT_EAST);
  const entry = model.entries.find((e) => e.id === "string:East");
  assert.ok(entry && entry.box, "the entry has a box from its dot alone");
  assert.deepEqual([entry.box.w, entry.box.h], [2 * g.key.r, 2 * g.key.r]);
  assert.equal(I.lookup(R, model, centreOf(g.key)).entry, "string:East");
});

test("a switch with a dot key: a click on the dot, Enter and Space on its entry each ask the host once; the picture is a group", () => {
  const frame = { w: 960, h: 540 };
  const out = draw("test_switch", "three-forms", frame, { measure: CUT_EAST });
  const { svg, handle, toggles } = mounted(out, frame, CUT_EAST);
  assert.equal(svg.getAttribute("role"), "group");
  const dot = legendGroups(out.primitives).find((x) => x.g.legend === "string:East").key;
  svg.dispatch("click", { clientX: dot.cx, clientY: dot.cy, target: svg });
  assert.deepEqual(toggles, ["string:East"]);
  for (let i = 0; i < 12 && JSON.stringify(handle.target()) !== JSON.stringify({ kind: "entry", id: "string:East" }); i++) svg.dispatch("keydown", { key: "ArrowRight" });
  assert.deepEqual(handle.target(), { kind: "entry", id: "string:East" });
  svg.dispatch("keydown", { key: "Enter" });
  svg.dispatch("keydown", { key: " " });
  assert.deepEqual(toggles, ["string:East", "string:East", "string:East"]);
});
