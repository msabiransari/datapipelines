// #358 — the workspace runtime's activation discipline (static/js/pipeline-editor/runtime.js).
//
// The runtime is the ONE initializer a restored or freshly-swapped `.pe-root` can get:
// the root is served (and cached) with `x-ignore`, the fragment's scripts ride in an
// inert catalog, and this module removes the ignore inside its single `mutateDom` init.
// These tests pin that discipline at the module level (the browser suite proves the
// counts on the real app); the harness stubs the DOM surfaces the runtime touches and
// simulates a script load by invoking the created element's `onload` — the Alpine
// entry "boots" by installing the Alpine double on `window`, exactly what the real
// file does.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const runtimePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/runtime.js");

const WORKSPACE_BLOCK = JSON.stringify({ pipelineId: "p1", viewedVersion: 2, hasBody: true, canExecute: true });
const DATA_BLOCK = JSON.stringify({ id: "p1", name: "demo", nodes: [] });

const CATALOG = [
  "/vendor/dagre/dagre.min.js",
  "/vendor/cytoscape/cytoscape.min.js",
  "/js/pipeline-editor/workspace.js",
  "/js/pipeline-editor/init.js",
  "/vendor/alpinejs/alpine.min.js",
];

function freshElement() {
  return {
    isConnected: true,
    attrs: {},
    setAttribute(name, value) {
      this.attrs[name] = value;
    },
    removeAttribute(name) {
      delete this.attrs[name];
    },
    getAttribute: () => null,
    querySelectorAll: () => [],
  };
}

/**
 * Fresh browser surfaces + the ACTUAL runtime. `deferred` keeps created scripts
 * unresolved so a test can interpose (a history save between `enter()` and
 * activation) before resolving them through the returned `pending` hook.
 */
function loadRuntime({ deferred = false, preAlpine = false } = {}) {
  const created = [];
  const pending = [];
  const ops = [];
  const alpineDouble = {
    nextTick: async () => {},
    mutateDom: (fn) => fn(),
    destroyTree: (el) => ops.push(["destroyTree", el]),
    initTree: (el) => ops.push(["initTree", el]),
    dataNamed: [],
    data(name) {
      this.dataNamed.push(name);
    },
  };
  let root = freshElement();
  const main = { id: "app-main", querySelector: (sel) => (sel === ".pe-root" ? root : null) };
  const doc = {
    readyState: "complete",
    head: {
      appendChild: (el) => {
        created.push(el.src);
        // The simulated execution of the vendored file: Alpine installs itself.
        if (el.src.indexOf("alpine") !== -1) globalThis.window.Alpine = alpineDouble;
        if (deferred) pending.push(el);
        else el.onload();
      },
    },
    createElement: () => ({ remove() {} }),
    addEventListener: () => {},
    getElementById: (id) => {
      if (id === "app-main") return main;
      if (id === "pe-runtime-scripts") {
        return {
          content: {
            querySelectorAll: () => CATALOG.map((src) => ({ getAttribute: (a) => (a === "src" ? src : null) })),
          },
        };
      }
      if (id === "pipeline-data") return { textContent: DATA_BLOCK };
      if (id === "pipeline-workspace") return { textContent: WORKSPACE_BLOCK };
      return null;
    },
    querySelector: () => null,
  };
  globalThis.window = {};
  globalThis.document = doc;
  if (preAlpine) globalThis.window.Alpine = alpineDouble;
  globalThis.window.PEWorkspaceRead = () => ops.push(["PEWorkspaceRead"]);
  globalThis.window.pipelineEditor = function () {};
  globalThis.console = { error: () => {} };

  delete require.cache[require.resolve(runtimePath)];
  require(runtimePath);

  return {
    runtime: globalThis.window.PEPipelineRuntime,
    alpine: alpineDouble,
    created,
    ops,
    window: globalThis.window,
    root: () => root,
    /** Simulate a restored root: a FRESH element (expandos do not survive the snapshot). */
    restoreRoot() {
      root = freshElement();
      root.setAttribute("x-ignore", ""); // what the before-save cleanup re-armed
      return root;
    },
    /** Resolve deferred script loads (in order). */
    resolvePending() {
      const elements = pending.splice(0);
      elements.forEach((el) => el.onload());
    },
    /** The last function registered into the shell's history-cleanup registry. */
    saveCleanup() {
      return globalThis.window.__dpHistoryStyleCleanups.at(-1);
    },
  };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test("the activation removes x-ignore, reads the context, and initializes the tree EXACTLY once", async () => {
  const fx = loadRuntime();
  await settle();

  assert.equal(fx.root().attrs["x-ignore"], undefined, "the root is live — the ignore came off");
  assert.equal(fx.root().attrs["data-pe-runtime-epoch"], fx.runtime.epoch, "the per-document identity is stamped");
  assert.deepEqual(
    fx.ops.map((op) => op[0]),
    ["destroyTree", "PEWorkspaceRead", "initTree"],
    "destroy-before-bind, context BEFORE the component binds, one init",
  );
  assert.equal(fx.runtime.activations, 1);
  assert.ok(fx.created.includes("/vendor/alpinejs/alpine.min.js"), "the catalog's Alpine loaded");
});

test("a restored root (fresh element, same document) re-enters under the SAME runtime identity", async () => {
  const fx = loadRuntime();
  await settle();
  const epochBefore = fx.runtime.epoch;

  fx.restoreRoot();
  await fx.runtime.enter(); // what the replayed bootstrap's enter() does

  assert.equal(fx.root().attrs["x-ignore"], undefined, "the restored root is activated");
  assert.equal(fx.runtime.activations, 2, "one activation per root");
  assert.equal(fx.runtime.epoch, epochBefore, "the epoch never changes within a document");
});

test("the replayed bootstrap re-enters instead of re-defining the runtime", async () => {
  const fx = loadRuntime();
  await settle();
  const before = fx.runtime;
  const createdBefore = fx.created.length;

  delete require.cache[require.resolve(runtimePath)];
  require(runtimePath); // what a cached restore's replay does

  assert.equal(globalThis.window.PEPipelineRuntime, before, "the guard keeps ONE runtime per document");
  await settle();
  assert.equal(fx.created.length, createdBefore, "the replay loads nothing again");
});

test("the singleton gate: a catalog entry whose global already exists is skipped, not re-loaded", async () => {
  const fx = loadRuntime({ preAlpine: true });
  await settle();

  assert.equal(
    fx.created.filter((src) => src.indexOf("alpine") !== -1).length,
    0,
    "no second Alpine is loaded",
  );
  assert.ok(fx.created.includes("/vendor/dagre/dagre.min.js"), "the other vendors still load");
  assert.deepEqual(
    fx.ops.map((op) => op[0]),
    ["destroyTree", "PEWorkspaceRead", "initTree"],
    "the pre-existing Alpine still yields exactly one activation",
  );
});

test("an Alpine that booted without the editor still gets its component — registered at activation", async () => {
  const fx = loadRuntime();
  globalThis.window.__peComponentRegistered = false;
  await settle();

  assert.deepEqual(
    fx.alpine.dataNamed,
    ["pipelineEditor"],
    "the activation registers the component directly when alpine:init was missed",
  );
});

test("a history save between enter and activation invalidates it — no init on a dead root", async () => {
  const fx = loadRuntime({ deferred: true });
  const activation = fx.runtime.enter();
  const inFlight = settle();

  // The history save fires while the activation is still awaiting its dependencies:
  // the cleanup invalidates the generation, the root leaves, the deps arrive late.
  fx.saveCleanup()({ querySelector: () => fx.root() });
  fx.resolvePending();
  await inFlight;
  await activation;

  assert.deepEqual(fx.ops, [], "a generation invalidated by a save never initializes");
  assert.equal(fx.runtime.activations, 0);
});

test("the before-save cleanup arms x-ignore, strips styles, empties the generated DOM — and is a no-op without a root", () => {
  const fx = loadRuntime();
  const generated = { emptied: 0 };
  const styledOps = [];
  const styled = [{ removeAttribute: (a) => styledOps.push(a) }];
  const canvas = { replaceChildren: () => generated.emptied++ };
  const root = fx.root();
  root.querySelectorAll = (sel) =>
    sel === "#cy-canvas, #pe-minimap" ? [canvas] : sel === "[style]" ? styled : [];
  root.attrs["data-pe-runtime-epoch"] = "stale";

  const main = { querySelector: (sel) => (sel === ".pe-root" ? root : null) };
  fx.saveCleanup()(main);

  assert.equal(generated.emptied, 1, "the canvas DOM the runtime generated is emptied");
  assert.deepEqual(styledOps, ["style"], "style attributes are stripped from the snapshot");
  assert.equal(root.attrs["x-ignore"], "", "the ignore is re-armed for the cached markup");
  assert.ok(!("__peActivation" in root), "the activation bookkeeping dies with the snapshot");

  const before = generated.emptied;
  fx.saveCleanup()({ querySelector: () => null }); // every other page: a no-op, not a walk
  assert.equal(generated.emptied, before);
});
