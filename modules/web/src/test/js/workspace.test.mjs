// #348 — the workspace version state (workspace.js / workspace spec §3.4); #348-b owns
// its validation: bounded positive integer, body presence and pipeline identity, and the
// ONE read path that full-document load, boost and cached history restoration all share.
//
// The rule: a run targets the version the page is VIEWING — released or draft, always
// sent. A state the page could not read (block missing or unparsable), one that fails
// validation (fractional, overflowing, no body, identity mismatch), is a REFUSAL, never a
// default: the server's execute-default (the working version) can be a different body
// than the one the person is looking at, and "some version ran" is the wrong-body failure
// this module exists to prevent.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const workspacePath = path.resolve(here, "../../main/resources/static/js/pipeline-editor/workspace.js");

const DATA = JSON.stringify({ id: "p1", name: "p", nodes: [], parameters: {} });

function freshWindow() {
  const loaded = globalThis.window;
  globalThis.window = {};
  globalThis.document = {
    readyState: "complete",
    addEventListener: function () {},
    cookie: "",
    getElementById: function () {
      return null;
    },
  };
  return loaded;
}

/** Loads the ACTUAL module against the given blocks; returns the window it published into. */
function loadWith(block, dataBlock) {
  globalThis.document.getElementById = (id) => {
    if (id === "pipeline-workspace") return block === undefined ? null : { textContent: block };
    if (id === "pipeline-data") return dataBlock === undefined ? null : { textContent: dataBlock };
    return null;
  };
  delete require.cache[require.resolve(workspacePath)];
  require(workspacePath);
  return globalThis.window;
}

function readAgain(block, dataBlock) {
  globalThis.document.getElementById = (id) => {
    if (id === "pipeline-workspace") return block === undefined ? null : { textContent: block };
    if (id === "pipeline-data") return dataBlock === undefined ? null : { textContent: dataBlock };
    return null;
  };
  globalThis.window.PEWorkspaceRead();
}

test("a valid state publishes the viewed version and the execute rule returns it", () => {
  freshWindow();
  loadWith('{"pipelineId":"p1","viewedVersion":3,"hasBody":true,"canExecute":true}', DATA);
  assert.deepEqual(globalThis.window.PEWorkspace, { pipelineId: "p1", viewedVersion: 3, hasBody: true, canExecute: true });
  assert.equal(globalThis.window.PEWorkspaceInvalid, false);
  assert.equal(globalThis.window.PEWorkspaceLogic.executeVersion(globalThis.window.PEWorkspace), 3);
});

test("a released view pins its own version exactly as a draft view does", () => {
  freshWindow();
  loadWith('{"pipelineId":"p1","viewedVersion":1,"hasBody":true}', DATA);
  assert.equal(globalThis.window.PEWorkspaceLogic.executeVersion(globalThis.window.PEWorkspace), 1);
});

test("the choose-a-version state resolves NO version to run", () => {
  freshWindow();
  loadWith('{"pipelineId":"p1","viewedVersion":null,"hasBody":false,"canExecute":false}', DATA);
  assert.equal(globalThis.window.PEWorkspaceLogic.executeVersion(globalThis.window.PEWorkspace), null);
});

test("a malformed workspace block records the refusal, never a silent default", () => {
  const restored = freshWindow();
  loadWith('{"viewedVersion": 2, ', DATA);
  assert.equal(globalThis.window.PEWorkspaceInvalid, true);
  assert.equal(globalThis.window.PEWorkspace, null);
  assert.equal(globalThis.window.PEWorkspaceLogic.executeVersion(globalThis.window.PEWorkspace), null);
  globalThis.window = restored;
});

test("a missing workspace block records the refusal too - a page with no pin cannot run", () => {
  const restored = freshWindow();
  loadWith(undefined, DATA);
  assert.equal(globalThis.window.PEWorkspaceInvalid, true);
  assert.equal(globalThis.window.PEWorkspace, null);
  globalThis.window = restored;
});

test("an identity mismatch between the block and the body blob is a refusal", () => {
  const restored = freshWindow();
  loadWith('{"pipelineId":"p1","viewedVersion":2,"hasBody":true}', JSON.stringify({ id: "other", nodes: [] }));
  assert.equal(globalThis.window.PEWorkspaceInvalid, true);
  assert.equal(globalThis.window.PEWorkspace, null);
  globalThis.window = restored;
});

test("a missing body blob leaves identity unjudged - the block stands", () => {
  freshWindow();
  loadWith('{"pipelineId":"p1","viewedVersion":2,"hasBody":true}', null);
  assert.equal(globalThis.window.PEWorkspaceInvalid, false);
  assert.equal(globalThis.window.PEWorkspaceLogic.executeVersion(globalThis.window.PEWorkspace), 2);
});

test("only a POSITIVE BOUNDED INTEGER is a pin: fractional, overflow and non-numbers refuse", () => {
  freshWindow();
  loadWith('{"pipelineId":"p1","viewedVersion":2,"hasBody":true}', DATA);
  const rule = globalThis.window.PEWorkspaceLogic.executeVersion;
  assert.equal(rule({ pipelineId: "p1", viewedVersion: 2.5, hasBody: true }), null, "fractional");
  assert.equal(rule({ pipelineId: "p1", viewedVersion: Infinity, hasBody: true }), null, "Infinity (1e400)");
  assert.equal(rule({ pipelineId: "p1", viewedVersion: NaN, hasBody: true }), null);
  assert.equal(rule({ pipelineId: "p1", viewedVersion: 2147483647, hasBody: true }), 2147483647, "the boundary is admitted");
  assert.equal(rule({ pipelineId: "p1", viewedVersion: 2147483648, hasBody: true }), null, "past the boundary");
  assert.equal(rule({ pipelineId: "p1", viewedVersion: "2", hasBody: true }), null, "a string is not a number");
  assert.equal(rule({ pipelineId: "p1", viewedVersion: 0, hasBody: true }), null);
  assert.equal(rule({ pipelineId: "p1", viewedVersion: -1, hasBody: true }), null);
  assert.equal(rule({ pipelineId: "p1", viewedVersion: 2, hasBody: false }), null, "no body, nothing to run");
  assert.equal(rule(null), null);
  assert.equal(rule(undefined), null);
  assert.equal(rule({ viewedVersion: 2, hasBody: true }), null, "no pipeline identity");
});

test("a restored VALID context clears a stale invalid flag - the history-restore contract", () => {
  const restored = freshWindow();
  loadWith('{"viewedVersion": 2, ', DATA); // refusal recorded
  assert.equal(globalThis.window.PEWorkspaceInvalid, true);

  // The rescue re-reads from the restored document through the SAME path.
  readAgain('{"pipelineId":"p1","viewedVersion":2,"hasBody":true}', DATA);
  assert.equal(globalThis.window.PEWorkspaceInvalid, false, "the stale flag is cleared");
  assert.equal(globalThis.window.PEWorkspaceLogic.executeVersion(globalThis.window.PEWorkspace), 2);
  globalThis.window = restored;
});

test("a restored INVALID context records the refusal again - no stale pin survives", () => {
  const restored = freshWindow();
  loadWith('{"pipelineId":"p1","viewedVersion":2,"hasBody":true}', DATA);
  assert.equal(globalThis.window.PEWorkspaceInvalid, false);

  readAgain('{"viewedVersion": 2, ', DATA);
  assert.equal(globalThis.window.PEWorkspaceInvalid, true);
  assert.equal(globalThis.window.PEWorkspace, null);
  globalThis.window = restored;
});

for (const hasBody of [undefined, null, "true"]) {
  test(`a non-boolean body flag refuses (${String(hasBody)})`, () => {
    freshWindow();
    const page = loadWith('{"pipelineId":"p1","viewedVersion":2,"hasBody":true}', DATA);
    assert.equal(page.PEWorkspaceLogic.executeVersion({ pipelineId: "p1", viewedVersion: 2, hasBody }), null);
  });
}

test("JSON null on reread clears the old pin without throwing", () => {
  freshWindow();
  const page = loadWith('{"pipelineId":"p1","viewedVersion":2,"hasBody":true}', DATA);
  assert.doesNotThrow(() => readAgain("null", DATA));
  assert.equal(page.PEWorkspace, null);
  assert.equal(page.PEWorkspaceInvalid, true);
});

/* ============================================================ #349 — the composition
 * helpers on the same pure module: the per-version override store, the page/view
 * staleness rule, and the sink-token comparison the beforeSwap guard runs. */

function logic() {
  // Re-load the ACTUAL module into the fresh window, as loadWith does.
  delete require.cache[require.resolve(workspacePath)];
  require(workspacePath);
  return globalThis.window.PEWorkspaceLogic;
}

test("#349 override store: save/load round-trips one version's bag", () => {
  freshWindow();
  const store = logic().createOverrideStore();
  store.save(1, { region: "nyc", day: "mon" });
  assert.deepEqual(store.load(1, ["region", "day"]), { region: "nyc", day: "mon" });
});

test("#349 override store: a key the new schema does not declare is DROPPED, never leaked", () => {
  freshWindow();
  const store = logic().createOverrideStore();
  store.save(1, { region: "nyc", legacy_only: "keep me out" });
  assert.deepEqual(
    store.load(1, ["region", "fresh"]),
    { region: "nyc", fresh: "" },
    "changing schema cannot reuse another version's fields; unknown keys arrive empty",
  );
});

test("#349 override store: revisit restores the compatible overrides (A→B→A)", () => {
  freshWindow();
  const store = logic().createOverrideStore();
  store.save(1, { region: "nyc" });
  store.save(2, { region: "ldn" });
  assert.equal(store.load(1, ["region"]).region, "nyc");
  assert.equal(store.load(2, ["region"]).region, "ldn");
});

test("#349 stale: any carried key that disagrees with the current state is stale", () => {
  freshWindow();
  const stale = logic().stale;
  const current = { pipelineId: "p1", version: 2, generation: 7 };
  assert.equal(stale({ pipelineId: "p1", version: 2, generation: 7 }, current), false);
  assert.equal(stale({ pipelineId: "p1", version: 1, generation: 7 }, current), true, "older version");
  assert.equal(stale({ pipelineId: "p1", version: 2, generation: 6 }, current), true, "older generation");
  assert.equal(stale({ pipelineId: "other", version: 2, generation: 7 }, current), true, "other pipeline");
  assert.equal(stale(null, current), true);
});

test("#349 stale: A→B→A — the second A is a NEW generation, the first A's late response is stale", () => {
  freshWindow();
  const stale = logic().stale;
  // v1 requested under generation 1; the user moved to v2 (generation 2) and back to
  // v1 (generation 3). The first v1 response carries generation 1.
  assert.equal(stale({ pipelineId: "p1", version: 1, generation: 1 }, { pipelineId: "p1", version: 1, generation: 3 }), true);
  assert.equal(stale({ pipelineId: "p1", version: 1, generation: 3 }, { pipelineId: "p1", version: 1, generation: 3 }), false);
});

test("#349 stale: an entity-wide read (no version stamp) guards on pipeline and generation", () => {
  freshWindow();
  const stale = logic().stale;
  const current = { pipelineId: "p1", version: 5, generation: 9 };
  assert.equal(stale({ pipelineId: "p1", generation: 9 }, current), false);
  assert.equal(stale({ pipelineId: "p1", generation: 8 }, current), true);
});

test("#349 tokenMatches: a non-empty string equality, nothing else", () => {
  freshWindow();
  const matches = logic().tokenMatches;
  assert.equal(matches("t3", "t3"), true);
  assert.equal(matches("t2", "t3"), false);
  assert.equal(matches("", "t3"), false, "an unstamped sink cancels");
  assert.equal(matches(null, "t3"), false);
  assert.equal(matches(undefined, undefined), false, "absent on both sides is not a match");
});
