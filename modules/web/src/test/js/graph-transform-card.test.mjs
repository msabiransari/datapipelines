// 7d (#7, transform-nodes design §9.3) — the TRANSFORM node card (graph.js).
//
// Runs on Node's built-in runner (`node --test`, the 027b harness), graph-card.test.mjs's
// loader convention: graph.js is a browser IIFE that also publishes on module.exports, and
// the pure functions are driven with sentinel data. Pinned:
//
//   1. the type maps: TYPE_TOKEN.TRANSFORM = "transform" (app.css maps it to --type-transform)
//      and TYPE_ICONS.TRANSFORM = "code" — without the token the card falls back to `dql`
//      and wears the DQL accent (the falsification this lane logs);
//   2. the three fact lines — language + pin, inputs → output, rejects + strict — for the
//      three output shapes (tempdb with rejects, a value-mode Context key, the caller);
//   3. the language is `transform` until the pin resolves, then the template's type;
//   4. every interpolated value is escaped (a template name is user-authored);
//   5. applyTransformPins writes the facts, the needs-review marker and the editor's map.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const graphPath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/graph.js");

function loadGraph() {
  globalThis.window = {};
  delete require.cache[require.resolve(graphPath)];
  return require(graphPath);
}

// The record's §3.1 example node — row mode with rejects, strict.
const ROW = {
  id: "shape_orders",
  type: "TRANSFORM",
  template: { id: "acme/shape/order_lines.jsonata", version: 3 },
  inputs: { orders: "stg_orders", tz: "$org_timezone" },
  output: { target: "tempdb", table: "order_lines", rejects: "order_lines_rejected" },
  strict: true,
  depends_on: [],
};
const VALUE = {
  id: "threshold",
  type: "TRANSFORM",
  template: { id: "test/it_threshold.jsonata", version: 1 },
  inputs: { lines: "order_lines" },
  context_key: "threshold",
  depends_on: [],
};
const CALLER = {
  id: "summarize",
  type: "TRANSFORM",
  template: { id: "test/it_summarize.jsonata", version: 1 },
  inputs: { trips: "big_trips" },
  output: { target: "caller" },
  depends_on: [],
};

test("the TRANSFORM type maps: its own accent token and the `code` glyph", () => {
  const g = loadGraph();
  assert.equal(g.typeToken("TRANSFORM"), "transform", "red here = the card falls back to the DQL accent");
  assert.equal(g.typeToken("transform"), "transform", "the lookup is case-insensitive like every type's");
  assert.equal(g.iconForType("TRANSFORM"), "code");
});

test("a row-mode TRANSFORM card: language + pin, inputs → table, rejects + strict", () => {
  const g = loadGraph();
  const [el] = g.buildElements([ROW], { tempdb: { engine: "H2" } });

  assert.ok(el.classes.split(" ").includes("type-transform"), "the stylesheet and the a11y sweep read the class");
  assert.deepEqual(
    el.data.facts.map((f) => f.text),
    [
      "transform · acme/shape/order_lines.jsonata @ v3",
      "2 inputs → tempdb.order_lines",
      "rejects → tempdb.order_lines_rejected · strict",
    ],
  );
  assert.equal(el.data.facts[0].title, "acme/shape/order_lines.jsonata @ v3", "the full pin rides on title");
  assert.equal(el.data.transformPinKey, "acme/shape/order_lines.jsonata@3");
  assert.equal(el.data.output, null, "the output is fact line 2 — no port row for a TRANSFORM");

  const card = g.buildCardHtml(el.data);
  assert.match(card, /data-type="transform"/, "188: the card names its TYPE; app.css maps it to --type-transform");
  assert.match(card, /class="pe-card-kind">transform</);
  assert.match(card, /lucide-sprite\.svg#code/);
  assert.equal(card.includes("lucide-sprite.svg#db"), false, "#db is the fallback glyph and would call it a DQL");
  assert.equal(card.includes("needs review"), false, "no marker until the pin says so");
});

test("a value-mode TRANSFORM writes a Context key; a caller TRANSFORM says caller; neither has a rejects line", () => {
  const g = loadGraph();
  const els = g.buildElements([VALUE, CALLER], {});
  assert.deepEqual(els[0].data.facts.map((f) => f.text), ["transform · test/it_threshold.jsonata @ v1", "1 input → $threshold"]);
  assert.deepEqual(els[1].data.facts.map((f) => f.text), ["transform · test/it_summarize.jsonata @ v1", "1 input → caller"]);
  assert.ok(els[1].classes.split(" ").includes("caller"), "an explicit caller output marks the result node");
});

test("the language is the resolved pin's type; the needs-review flag comes with it", () => {
  const g = loadGraph();
  const pin = g.transformPinFrom({ type: "jsonata", contract: { mode: "row", rejects: true }, needs_review: true });
  assert.deepEqual(pin, { language: "jsonata", mode: "row", rejects: true, needsReview: true });
  assert.equal(g.transformPinFrom({ type: "jsonata", contract: { mode: "value" } }).needsReview, false, "absent until 7e: false");
  assert.equal(g.transformPinFrom(null), null);
  assert.equal(g.transformFacts(ROW, pin)[0].text, "jsonata · acme/shape/order_lines.jsonata @ v3");

  const card = g.buildCardHtml(Object.assign({}, g.buildElements([ROW], {})[0].data, { needsReview: true }));
  assert.match(card, /class="pe-card-review"[^>]*>· needs review</);
});

test("every value on a TRANSFORM card is escaped — a template name is user-authored", () => {
  const g = loadGraph();
  const hostile = Object.assign({}, ROW, {
    id: "n<b>1</b>",
    template: { id: "x/<script>alert(1)</script>", version: 1 },
    output: { target: "tempdb", table: "t\"><img src=x onerror=alert(2)>" },
  });
  const card = g.buildCardHtml(g.buildElements([hostile], {})[0].data);
  assert.equal(card.includes("<script>"), false);
  assert.equal(card.includes("<img"), false);
  assert.equal(card.includes("<b>1</b>"), false);
  assert.match(card, /&lt;script&gt;alert\(1\)&lt;\/script&gt;/);
  assert.match(card, /&lt;img src=x onerror=alert\(2\)&gt;/);
});

test("applyTransformPins writes the resolved facts, the marker and the editor's map — and skips unresolved pins", () => {
  const g = loadGraph();
  const PipelineGraph = globalThis.window.PipelineGraph;
  const el = g.buildElements([ROW, VALUE], {});
  const store = el.slice(0, 2).map((e) => Object.assign({}, e.data));
  const node = (d) => ({
    id: () => d.id,
    data: (k, v) => (v === undefined ? d[k] : (d[k] = v)),
  });
  const editor = { transformPins: {} };
  const fake = { cy: { nodes: () => ({ forEach: (fn) => store.map(node).forEach(fn) }) }, nodes: [ROW, VALUE], editor };

  PipelineGraph.prototype.applyTransformPins.call(fake, {
    "acme/shape/order_lines.jsonata@3": { language: "jsonata", mode: "row", rejects: true, needsReview: true },
  });

  assert.equal(store[0].facts[0].text, "jsonata · acme/shape/order_lines.jsonata @ v3");
  assert.equal(store[0].needsReview, true);
  assert.equal(store[1].facts[0].text, "transform · test/it_threshold.jsonata @ v1", "an unresolved pin keeps its honest card");
  assert.equal(editor.transformPins["acme/shape/order_lines.jsonata@3"].mode, "row");
});
