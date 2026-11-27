// #1077: radar's model, radial marks and cards, checked independently from the rows.
import { test } from "node:test";
import assert from "node:assert/strict";
import { engine, inputOf, chartFixtures, MODULE } from "./engine.mjs";
import { createRequire } from "node:module";
import { join } from "node:path";

const R = engine();
test("radar registers band, value and series channels", () => {
  const entry = R.types().find((t) => t.name === "radar");
  assert.ok(entry, "radar registered");
  assert.deepEqual(JSON.parse(JSON.stringify(entry.channels)), [
    { key: "theta", required: true, node: { node: "shape", name: "band" } },
    { key: "r", required: true, node: { node: "shape", name: "value" } },
    { key: "color", required: false, node: { node: "shape", name: "series" } },
  ]);
});
