// The engine as the tests load it: the files of LOAD-ORDER.txt concatenated, in that order, and run in a fresh vm
// context, as the sandbox runs the same concatenation (D11). A test that changes a file or a table gets its own
// context, so no change leaks into another test.
import { readFileSync, readdirSync, statSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import vm from "node:vm";

export const MODULE = resolve(dirname(fileURLToPath(import.meta.url)), "../../..");
export const ENGINE = join(MODULE, "src/main/resources/co/datapipelines/jsrendered/engine");
export const FIXTURES = join(MODULE, "src/test/fixtures");

/** The file names of LOAD-ORDER.txt, in order. */
export function loadOrder() {
  return readFileSync(join(ENGINE, "LOAD-ORDER.txt"), "utf8").split("\n").filter((l) => l.trim() !== "");
}

/** {name: source} of every engine file. */
export function sources() {
  const out = {};
  for (const name of loadOrder()) out[name] = readFileSync(join(ENGINE, name), "utf8");
  return out;
}

/** The concatenation the sandbox compiles: every file in load order, each followed by a newline. */
export function concatenation(srcs = sources()) {
  return loadOrder().map((n) => srcs[n] + "\n").join("");
}

/**
 * A fresh engine: the JsRendered namespace of a new context. edit(srcs) may change the sources first (a falsification's
 * copy); after(JsRendered) runs before the type files would observe a change? No: tables are read at render time, so a
 * test changes JsRendered.tables.LOOK[f].value on its own engine.
 */
export function engine(edit) {
  const srcs = sources();
  if (edit) edit(srcs);
  const ctx = vm.createContext({});
  vm.runInContext(concatenation(srcs) + "\nthis.JsRendered = JsRendered;", ctx, { filename: "js-rendered-engine.js" });
  return ctx.JsRendered;
}

/** Every chart fixture: [{wire, name, path, fixture}], sorted by wire then name; `_layout` is not a chart fixture. */
export function chartFixtures() {
  const out = [];
  for (const wire of readdirSync(FIXTURES).sort()) {
    if (wire.startsWith("_") || !statSync(join(FIXTURES, wire)).isDirectory()) continue;
    for (const f of readdirSync(join(FIXTURES, wire)).sort()) {
      if (!f.endsWith(".json")) continue;
      out.push({ wire, name: f.slice(0, -5), path: join(FIXTURES, wire, f), fixture: JSON.parse(readFileSync(join(FIXTURES, wire, f), "utf8")) });
    }
  }
  return out;
}

/** The render input of a chart fixture at a frame, with nothing hidden unless the fixture says so. */
export function inputOf(fx, w, h, extra = {}) {
  return Object.assign({ type: fx.document.config.mark.type, document: fx.document, columns: fx.columns, types: fx.types, frame: { w, h }, theme: "light", measure: null, hidden: fx.hidden || [] }, extra);
}

/** The canonical JSON text of a render result: what the identity test compares byte for byte. */
export function canonical(result) {
  return JSON.stringify(result);
}
