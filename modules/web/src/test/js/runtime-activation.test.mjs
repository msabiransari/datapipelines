// #358 — the workspace runtime's activation discipline (static/js/pipeline-editor/runtime.js).
//
// The runtime is the ONE initializer a restored or freshly-swapped `.pe-root` can get:
// the root is served (and cached) with `x-ignore`, the fragment's scripts ride in an
// inert catalog, and this module removes the ignore inside its single `mutateDom` init.
// Since #494 the shell's page-script loader (page-assets.mjs) executes that catalog and
// calls `PipelineWorkspaceMount` after every entry loaded — its order, once-per-document
// and singleton-gate cases live in page-assets.test.mjs; these pin the activation the
// runtime still owns (the browser suite proves the counts on the real app). The harness
// stubs the DOM surfaces the runtime touches and installs the Alpine double the loader
// would have executed before the mount.

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
 * Fresh browser surfaces + the ACTUAL runtime, with the Alpine the loader executed before
 * the mount. `deferred` holds Alpine's nextTick so a test can interpose (a history save
 * between the mount and activation) before releasing it through `resolvePending`.
 */
function loadRuntime({ deferred = false } = {}) {
  const pending = [];
  const ops = [];
  const alpineDouble = {
    nextTick: () => (deferred ? new Promise((resolve) => pending.push(resolve)) : Promise.resolve()),
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
    createElement: () => ({ remove() {} }),
    addEventListener: () => {},
    getElementById: (id) => {
      if (id === "app-main") return main;
      if (id === "pipeline-data") return { textContent: DATA_BLOCK };
      if (id === "pipeline-workspace") return { textContent: WORKSPACE_BLOCK };
      return null;
    },
    querySelector: () => null,
  };
  globalThis.window = { Alpine: alpineDouble };
  globalThis.document = doc;
  globalThis.window.PEWorkspaceRead = () => ops.push(["PEWorkspaceRead"]);
  globalThis.window.pipelineEditor = function () {};
  globalThis.console = { error: () => {} };

  delete require.cache[require.resolve(runtimePath)];
  require(runtimePath);

  return {
    runtime: globalThis.window.PEPipelineRuntime,
    /** What the loader does once every catalog entry before runtime.js has executed. */
    mount: () => globalThis.window.PipelineWorkspaceMount(main),
    alpine: alpineDouble,
    ops,
    window: globalThis.window,
    root: () => root,
    /** Simulate a restored root: a FRESH element (expandos do not survive the snapshot). */
    restoreRoot() {
      root = freshElement();
      root.setAttribute("x-ignore", ""); // what the before-save cleanup re-armed
      return root;
    },
    /** Release the held nextTick(s). */
    resolvePending() {
      pending.splice(0).forEach((resolve) => resolve());
    },
    /** The last function registered into the shell's history-cleanup registry. */
    saveCleanup() {
      return globalThis.window.__dpHistoryStyleCleanups.at(-1);
    },
  };
}

const settle = () => new Promise((resolve) => setImmediate(resolve));

test("evaluating the runtime activates nothing: the loader's mount is the one trigger", async () => {
  const fx = loadRuntime();
  await settle();
  assert.deepEqual(fx.ops, []);
  assert.equal(fx.runtime.activations, 0);
  assert.equal(typeof fx.window.PipelineWorkspaceMount, "function");
});

test("the activation removes x-ignore, reads the context, and initializes the tree EXACTLY once", async () => {
  const fx = loadRuntime();
  await fx.mount();
  await fx.mount(); // a second mount call on the same live root binds nothing more

  assert.equal(fx.root().attrs["x-ignore"], undefined, "the root is live — the ignore came off");
  assert.equal(fx.root().attrs["data-pe-runtime-epoch"], fx.runtime.epoch, "the per-document identity is stamped");
  assert.deepEqual(
    fx.ops.map((op) => op[0]),
    ["destroyTree", "PEWorkspaceRead", "initTree"],
    "destroy-before-bind, context BEFORE the component binds, one init",
  );
  assert.equal(fx.runtime.activations, 1);
});

test("a restored root (fresh element, same document) re-enters under the SAME runtime identity", async () => {
  const fx = loadRuntime();
  await fx.mount();
  const epochBefore = fx.runtime.epoch;

  fx.restoreRoot();
  await fx.mount(); // what the loader does for the restored catalog

  assert.equal(fx.root().attrs["x-ignore"], undefined, "the restored root is activated");
  assert.equal(fx.runtime.activations, 2, "one activation per root");
  assert.equal(fx.runtime.epoch, epochBefore, "the epoch never changes within a document");
});

test("a second evaluation keeps the first runtime instead of re-defining it", async () => {
  const fx = loadRuntime();
  await fx.mount();
  const before = fx.runtime;
  const cleanups = globalThis.window.__dpHistoryStyleCleanups.length;

  delete require.cache[require.resolve(runtimePath)];
  require(runtimePath); // the loader never does this; the guard holds if anything else does

  assert.equal(globalThis.window.PEPipelineRuntime, before, "the guard keeps ONE runtime per document");
  assert.equal(globalThis.window.__dpHistoryStyleCleanups.length, cleanups, "no second history cleanup");
  await settle();
  assert.equal(fx.runtime.activations, 1, "the re-evaluation activates nothing");
});

test("an Alpine that booted without the editor still gets its component — registered at activation", async () => {
  const fx = loadRuntime();
  globalThis.window.__peComponentRegistered = false;
  await fx.mount();

  assert.deepEqual(
    fx.alpine.dataNamed,
    ["pipelineEditor"],
    "the activation registers the component directly when alpine:init was missed",
  );
});

test("a history save between enter and activation invalidates it — no init on a dead root", async () => {
  const fx = loadRuntime({ deferred: true });
  const activation = fx.mount();
  const inFlight = settle();

  // The history save fires while the activation is still awaiting Alpine's tick:
  // the cleanup invalidates the generation, the root leaves, the tick arrives late.
  fx.saveCleanup()({ querySelector: () => fx.root() });
  fx.resolvePending();
  await inFlight;
  await activation;

  // The save's own cleanup destroys the tree (Alpine is already there); the activation adds nothing.
  assert.deepEqual(fx.ops.map((op) => op[0]), ["destroyTree"], "a generation invalidated by a save never initializes");
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
