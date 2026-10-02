// #374 — the Parameter Sets workspace's PURE model (static/js/parameter-workspace/model.js): every decision the
// page takes from its two server-rendered JSON blocks, with no DOM and no network. The glue, the graph and the
// inspector render what this returns; what is pinned here is that a malformed block is a REFUSAL (never a
// default), that the graph is the set's structure and invents nothing, that the inspector's rows are TEXT and
// LINKS composed from encoded parts, and that the keyboard traversal lands where the spec says.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const model = require(path.resolve(here, "../../main/resources/static/js/parameter-workspace/model.js"));

const SET = {
  parameters: [
    { name: "year", label: "Year", type: "STRING", kind: "SELECT", source: { template: { id: "nyc/parameters/year_options.sql", version: 1 }, datasource: "sample-trips" } },
    { name: "month", label: "Month", type: "STRING", kind: "SELECT", depends_on: ["year"], source: { template: { id: "nyc/parameters/month_options.sql", version: 1 }, datasource: "sample-trips" } },
    { name: "zone", label: "Zone", type: "STRING", kind: "SELECT", depends_on: ["year", "month"], source: { template: { id: "nyc/parameters/zone_options.sql", version: 1 }, datasource: "sample-trips" } },
    { name: "measure", label: "Measure", type: "STRING", kind: "SELECT", source: { constants: [{ value: "trips", display_value: "Trips", is_default: true }, { value: "revenue", display_value: "Revenue" }] } },
    { name: "min_trips", label: "Minimum trips", type: "INTEGER", kind: "INPUT", default_value: 0, constraints: { min: 0 } },
  ],
};

const block = (over) => JSON.stringify({ parameterSetId: "7d1f", hasBody: true, canEvaluate: true, viewedVersion: 3, versionRows: [], ...over });

test("the workspace block is the ONE source of the version: a valid block parses, every malformed one is null", () => {
  assert.equal(model.parseWorkspace(block()).viewedVersion, 3);
  for (const bad of [
    null,
    "",
    "   ",
    "{not json",
    "[]",
    "42",
    block({ parameterSetId: "" }),
    block({ parameterSetId: 7 }),
    block({ hasBody: "yes" }),
    block({ canEvaluate: 1 }),
    block({ viewedVersion: 0 }),
    block({ viewedVersion: -2 }),
    block({ viewedVersion: 1.5 }),
    block({ viewedVersion: "3" }),
    block({ viewedVersion: null }),
  ]) {
    assert.equal(model.parseWorkspace(bad), null, `must refuse: ${bad}`);
  }
});

test("a page with no body that still claims evaluate is refused - nothing to evaluate", () => {
  assert.equal(model.parseWorkspace(block({ hasBody: false, canEvaluate: true, viewedVersion: null })), null);
  const choose = model.parseWorkspace(block({ hasBody: false, canEvaluate: false, viewedVersion: null }));
  assert.equal(choose.hasBody, false);
});

test("the source badge: constants, template, a plain input, else none", () => {
  assert.equal(model.sourceKind(SET.parameters[0]), "template");
  assert.equal(model.sourceKind(SET.parameters[3]), "constants");
  assert.equal(model.sourceKind(SET.parameters[4]), "input");
  assert.equal(model.sourceKind({ kind: "SELECT" }), "none");
  assert.equal(model.sourceKind(null), "none");
});

test("the graph is the set's structure: a node per parameter, an edge per depends_on, parent to child", () => {
  const { nodes, edges } = model.graphElements(SET);
  assert.deepEqual(nodes.map((n) => n.id), ["year", "month", "zone", "measure", "min_trips"]);
  assert.deepEqual(nodes.map((n) => n.badge), ["template", "template", "template", "constants", "input"]);
  assert.deepEqual(edges.map((e) => [e.source, e.target]), [["year", "month"], ["year", "zone"], ["month", "zone"]]);
});

test("a dangling dependency is skipped, never invented as a node; a nameless entry is dropped", () => {
  const { nodes, edges } = model.graphElements({ parameters: [{ name: "a", kind: "INPUT", depends_on: ["ghost"] }, { kind: "INPUT" }, null] });
  assert.deepEqual(nodes.map((n) => n.id), ["a"]);
  assert.deepEqual(edges, []);
  assert.deepEqual(model.graphElements(null), { nodes: [], edges: [] });
});

test("dependents are direct and transitive, in display order, and a cycle cannot loop", () => {
  assert.deepEqual(model.dependentsOf(SET, "year"), { direct: ["month", "zone"], transitive: ["month", "zone"] });
  assert.deepEqual(model.dependentsOf(SET, "month").direct, ["zone"]);
  assert.deepEqual(model.dependentsOf(SET, "zone"), { direct: [], transitive: [] });
  const chain = { parameters: [{ name: "a" }, { name: "b", depends_on: ["a"] }, { name: "c", depends_on: ["b"] }] };
  assert.deepEqual(model.dependentsOf(chain, "a"), { direct: ["b"], transitive: ["b", "c"] });
  const cyclic = { parameters: [{ name: "a", depends_on: ["b"] }, { name: "b", depends_on: ["a"] }] };
  assert.deepEqual(model.dependentsOf(cyclic, "a").transitive, ["b"]);
});

const row = (rows, label) => rows.find((r) => r.label === label);

test("the inspector's rows: identity, type, default and constraints are text, in the spec's order", () => {
  const rows = model.inspect(SET.parameters[4], SET, null);
  assert.deepEqual(rows.slice(0, 2).map((r) => r.label), ["Label", "Name"]);
  assert.equal(row(rows, "Type").value, "INTEGER");
  assert.equal(row(rows, "Kind").value, "INPUT");
  assert.equal(row(rows, "Cardinality").value, "SINGLE");
  assert.equal(row(rows, "Required").value, "no");
  assert.equal(row(rows, "Default").value, "0");
  assert.equal(row(rows, "Constraints").value, "min: 0");
  assert.equal(row(rows, "Depends on").value, "nothing");
});

test("a decimal's precision and scale print with its type", () => {
  const rows = model.inspect({ name: "amount", type: "DECIMAL", precision: 12, scale: 2, kind: "INPUT" }, SET, null);
  assert.equal(row(rows, "Type").value, "DECIMAL(12,2)");
});

test("a template pin renders as LINKS composed from encoded parts - never inlined, never a raw name", () => {
  const rows = model.inspect(SET.parameters[1], SET, null);
  const links = row(rows, "Source (template pin)");
  assert.equal(links.kind, "links");
  assert.equal(links.links[0].text, "template nyc/parameters/month_options.sql@1");
  assert.equal(links.links[0].href, "/templates/editor?name=nyc%2Fparameters%2Fmonth_options.sql&version=1");
  assert.equal(links.links[1].href, "/datasources/sample-trips");
  const hostile = model.inspect({ name: "p", kind: "SELECT", type: "STRING", source: { template: { id: "a/b?x=1&y=<script>", version: 2 }, datasource: "d/../x" } }, SET, null);
  const hostileLinks = row(hostile, "Source (template pin)").links;
  assert.ok(!hostileLinks[0].href.includes("<"), "the id is encoded");
  assert.ok(!hostileLinks[0].href.includes("&y="), "a name's ampersand cannot open a second query parameter");
  assert.equal(hostileLinks[1].href, "/datasources/d%2F..%2Fx");
});

test("constants render as a table: value as JSON, label as text, the default marked", () => {
  const options = row(model.inspect(SET.parameters[3], SET, null), "Options (constants)");
  assert.equal(options.kind, "table");
  assert.deepEqual(options.body, [['"trips"', "Trips", "yes"], ['"revenue"', "Revenue", ""]]);
});

test("dependency lines name parents, direct dependents and, past one hop, everything downstream", () => {
  const rows = model.inspect(SET.parameters[0], SET, null);
  assert.equal(row(rows, "Depends on").value, "nothing");
  assert.equal(row(rows, "Dependents").value, "month, zone");
  assert.equal(row(rows, "All downstream"), undefined, "no extra row when downstream equals direct");
  const chain = { parameters: [{ name: "a", kind: "INPUT" }, { name: "b", kind: "INPUT", depends_on: ["a"] }, { name: "c", kind: "INPUT", depends_on: ["b"] }] };
  assert.equal(row(model.inspect(chain.parameters[0], chain, null), "All downstream").value, "b, c");
});

test("the stored expressions are labelled as stored, and an absent one says so", () => {
  const withExpr = { ...SET.parameters[1], disabled_expression: { op: "is_null", arg: { ref: "year" } } };
  const rows = model.inspect(withExpr, SET, null);
  assert.match(row(rows, "Disabled when (stored expression)").value, /"op": "is_null"/);
  assert.match(row(rows, "Hidden when (stored expression)").value, /^never/);
});

test("the last evaluation row appears only once an evaluation ran, and carries origin, reset, flags and errors", () => {
  assert.equal(row(model.inspect(SET.parameters[1], SET, null), "Last evaluation"), undefined);
  assert.equal(row(model.inspect(SET.parameters[1], SET, {}), "Last evaluation"), undefined);
  const last = { state: { value: "03", origin: "default", reset: true, hidden: false, disabled: true, errors: [{ code: "parameter.value.invalid", message: "bad" }] } };
  const text = row(model.inspect(SET.parameters[1], SET, last), "Last evaluation").value;
  assert.match(text, /value: "03"/);
  assert.match(text, /origin: default/);
  assert.match(text, /reset: the previous selection was dropped/);
  assert.match(text, /disabled: yes/);
  assert.doesNotMatch(text, /hidden: yes/);
  assert.match(text, /error parameter\.value\.invalid: bad/);
});

test("keyboard traversal: Down/Up clamp at the ends, Home/End jump, Right goes to a dependent, Left to a parent", () => {
  assert.equal(model.neighbour(SET, "year", "ArrowDown"), "month");
  assert.equal(model.neighbour(SET, "min_trips", "ArrowDown"), "min_trips");
  assert.equal(model.neighbour(SET, "year", "ArrowUp"), "year");
  assert.equal(model.neighbour(SET, "zone", "Home"), "year");
  assert.equal(model.neighbour(SET, "year", "End"), "min_trips");
  assert.equal(model.neighbour(SET, "year", "ArrowRight"), "month");
  assert.equal(model.neighbour(SET, "zone", "ArrowLeft"), "year");
  assert.equal(model.neighbour(SET, "measure", "ArrowRight"), "measure", "no dependent: stay");
  assert.equal(model.neighbour(SET, "year", "ArrowLeft"), "year", "no parent: stay");
  assert.equal(model.neighbour(SET, "year", "x"), "year", "an unrelated key never moves the selection");
  assert.equal(model.neighbour(SET, "ghost", "ArrowDown"), "year", "an unknown current falls to the first");
  assert.equal(model.neighbour({ parameters: [] }, "a", "Home"), null);
});

test("readBlock admits only a JSON object", () => {
  assert.deepEqual(model.readBlock('{"a":1}'), { a: 1 });
  for (const bad of ["[1]", "1", '"s"', "null", "{", "", undefined]) assert.equal(model.readBlock(bad), null);
});
