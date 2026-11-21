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
/** The types for tests only (brief 1049 D5): one classic script per type, `<name>.js`, in the template's own form. */
export const TEST_TYPES = join(MODULE, "src/test/js/types");
/** Their fixtures, `_types/<name>/*.json`: the underscore keeps them out of chartFixtures() and every product guard. */
export const TYPE_FIXTURES = join(FIXTURES, "_types");

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
 * copy of a file). The tables are read at render time, so a test that changes a table value does it on its own engine
 * after loading, and no other test sees the change.
 */
export function engine(edit) {
  const srcs = sources();
  if (edit) edit(srcs);
  const ctx = vm.createContext({});
  vm.runInContext(concatenation(srcs) + "\nthis.JsRendered = JsRendered;", ctx, { filename: "js-rendered-engine.js" });
  return ctx.JsRendered;
}

/** The names of the test types (their files under src/test/js/types/, without `.js`), sorted. */
export function testTypes() {
  return readdirSync(TEST_TYPES).filter((f) => f.endsWith(".js")).map((f) => f.slice(0, -3)).sort();
}

/** The key of a test type's source in the map engineWith hands to `edit`. */
export function testTypeKey(name) {
  return "test/types/" + name + ".js";
}

/**
 * A fresh engine with test types registered (brief 1049 D5): the concatenation of LOAD-ORDER.txt, then the named test
 * types' files in the order given, in ONE context. edit(srcs) may change any of them first; a test type's source is
 * under testTypeKey(name). A later lane adds a file and a fixture directory and edits neither function.
 */
export function engineWith(names, edit) {
  const srcs = sources();
  for (const n of names) srcs[testTypeKey(n)] = readFileSync(join(TEST_TYPES, n + ".js"), "utf8");
  if (edit) edit(srcs);
  const text = concatenation(srcs) + names.map((n) => srcs[testTypeKey(n)] + "\n").join("");
  const ctx = vm.createContext({});
  vm.runInContext(text + "\nthis.JsRendered = JsRendered;", ctx, { filename: "js-rendered-engine-with-test-types.js" });
  return ctx.JsRendered;
}

/** The fixtures of one test type: [{wire: name, name, path, fixture}], sorted by name; `expect.marks` is hand-written. */
export function typeFixtures(name) {
  const dir = join(TYPE_FIXTURES, name);
  return readdirSync(dir)
    .filter((f) => f.endsWith(".json"))
    .sort()
    .map((f) => ({ wire: name, name: f.slice(0, -5), path: join(dir, f), fixture: JSON.parse(readFileSync(join(dir, f), "utf8")) }));
}

/** The render input of a test type's fixture: inputOf, with the fixture's own `theme` when it has one. */
export function typeInputOf(fx, w, h, extra = {}) {
  return inputOf(fx, w, h, Object.assign(fx.theme === undefined ? {} : { theme: fx.theme }, extra));
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
