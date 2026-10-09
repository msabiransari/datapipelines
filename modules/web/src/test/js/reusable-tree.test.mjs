import { test } from "node:test";
import assert from "node:assert/strict";
import { createTreeState, PAGE_CEILING } from "../../main/resources/static/js/tree/state.mjs";

const folder = (path, parentKey = null) => ({ key: `folder:${path}`, parentKey, kind: "folder", path, name: path, hasChildren: true });
const leaf = (path, parentKey = null) => ({ key: `artifact:${path}`, parentKey, kind: "artifact", path, name: path, hasChildren: false });
const page = (nodes, nextCursor = null) => ({ nodes, nextCursor });
function deferred() { let resolve; const promise = new Promise(r => { resolve = r; }); return { promise, resolve }; }

test("initialization is shallow and a single expand exhausts folders and leaves", async () => {
  const calls = [];
  const source = { async loadChildren(parent, cursor) {
    calls.push([parent, cursor]);
    if (parent === null) return page([folder("a")]);
    if (cursor === null) return page(Array.from({ length: 200 }, (_, i) => folder(`a/f${i}`, "folder:a")), "leaves");
    return page(Array.from({ length: 205 }, (_, i) => leaf(`a/l${i}`, "folder:a")));
  } };
  const state = createTreeState({ source });
  await state.initialize();
  assert.deepEqual(calls, [[null, null]]);
  assert.equal(state.open.size, 0);
  await state.expand("folder:a");
  assert.equal(state.nodes("folder:a").length, 405);
  assert.equal(calls.length, 3);
  state.collapse("folder:a"); await state.expand("folder:a");
  assert.equal(calls.length, 3);
});

test("clear invalidates a browse reply whose transport ignores cancellation", async () => {
  const held = deferred(); let calls = 0;
  const source = { async loadChildren(parent) { calls += 1; return parent === null ? page([folder("a")]) : held.promise; } };
  const state = createTreeState({ source });
  await state.initialize();
  const pending = state.expand("folder:a");
  await state.clear(); held.resolve(page([folder("a/b", "folder:a")])); await pending;
  assert.equal(state.open.size, 0);
  assert.equal(state.nodes("folder:a").length, 0);
  assert.equal(state.status("folder:a").status, "unloaded");
  assert.equal(calls, 2);
});

test("server search exhausts over 200 matches with expanded paths and never browses ancestors", async () => {
  let browses = 0;
  const source = {
    async loadChildren() { browses += 1; return page([folder("ordinary")]); },
    async search(q, cursor) {
      const prefix = cursor === null ? "a" : "b";
      return page([folder(prefix), folder(`${prefix}/deep`, `folder:${prefix}`),
        ...Array.from({ length: 205 }, (_, i) => leaf(`${prefix}/deep/match${i}`, `folder:${prefix}/deep`))], cursor === null ? "more" : null);
    },
  };
  const state = createTreeState({ source }); await state.initialize(); await state.setQuery(" match ");
  assert.equal(state.search.nodes.size, 414); assert.equal(state.open.size, 4); assert.equal(browses, 1);
  state.collapse("folder:a"); assert.equal(state.open.has("folder:a"), false);
  await state.clear(); assert.equal(state.open.size, 0); assert.deepEqual(state.nodes().map(n => n.path), ["ordinary"]);
});

test("query reorder, redundant input and deleting query reject old replies", async () => {
  const held = deferred(); let searches = 0;
  const source = {
    async loadChildren() { return page([folder("normal")]); },
    async search(q) { searches += 1; return q === "a" ? held.promise : page([folder(q)]); },
  };
  const state = createTreeState({ source }); await state.initialize(); const old = state.setQuery("a");
  await state.setQuery(" a "); assert.equal(searches, 1);
  await state.setQuery("b"); held.resolve(page([folder("old")])); await old;
  assert.deepEqual(state.nodes().map(n => n.path), ["b"]);
  await state.setQuery(" "); assert.equal(state.open.size, 0); assert.equal(state.query, "");
  assert.deepEqual(state.nodes().map(n => n.path), ["normal"]);
});

test("later search pages respect a reader's manual fold", async () => {
  const held = deferred(); const appended = deferred();
  const source = { async search(q, cursor) { return cursor ? held.promise : page([folder("a")], "more"); } };
  const state = createTreeState({ source }, () => { if (state.search.nodes.size) appended.resolve(); });
  const pending = state.setQuery("q"); await appended.promise; state.collapse("folder:a");
  held.resolve(page([folder("a"), leaf("a/hit", "folder:a")])); await pending;
  assert.equal(state.open.has("folder:a"), false);
});

test("partial failure retains rows and retry resumes without duplicate rows", async () => {
  let fail = true;
  const source = { async loadChildren(parent, cursor) {
    if (cursor === null) return page([folder("a")], "more");
    if (fail) throw new Error("Network");
    return page([folder("a"), folder("b")]);
  } };
  const state = createTreeState({ source }); await state.initialize();
  assert.equal(state.status().status, "incomplete"); assert.equal(state.status().complete, false);
  assert.equal(state.nodes().length, 1); fail = false; await state.retry();
  assert.equal(state.nodes().length, 2); assert.equal(state.status().complete, true);
});

test("nonprogressing continuation fails visibly instead of silently completing", async () => {
  const state = createTreeState({ source: { async loadChildren() { return page([folder("a")], "same"); } } });
  await state.initialize(); assert.equal(state.status().status, "incomplete"); assert.equal(state.status().complete, false);
});

test("sibling requests and independently mounted instances share no mutable state", async () => {
  const heldA = deferred(); const heldB = deferred();
  const source = { async loadChildren(parent) {
    if (parent === null) return page([folder("a"), folder("b")]);
    return parent === "folder:a" ? heldA.promise : heldB.promise;
  } };
  const first = createTreeState({ source }); const second = createTreeState({ source });
  await first.initialize(); await second.initialize();
  const a = first.expand("folder:a"); const b = first.expand("folder:b");
  heldB.resolve(page([leaf("b/item", "folder:b")])); await b;
  heldA.resolve(page([leaf("a/item", "folder:a")])); await a;
  assert.equal(first.nodes("folder:a").length, 1); assert.equal(first.nodes("folder:b").length, 1);
  assert.equal(second.open.size, 0); assert.equal(second.nodes("folder:a").length, 0);
  first.dispose(); assert.equal(second.nodes().length, 2);
});

test("context change and dispose refuse held foreign responses", async () => {
  const held = deferred();
  const state = createTreeState({ source: { async loadChildren() { return held.promise; } }, context: "old" });
  const pending = state.initialize();
  await state.update({ source: { async loadChildren() { return page([folder("new")]); } }, context: "new" });
  held.resolve(page([folder("old")])); await pending;
  assert.deepEqual(state.nodes().map(n => n.path), ["new"]);
});

test("debounced query changes invalidate old answers before any replacing search starts", async () => {
  const held = deferred(); let calls = 0;
  const source = { async loadChildren() { return page([folder('root')]); },
    async search() { calls += 1; return held.promise; } };
  const state = createTreeState({source}); await state.initialize(); const old = state.setQuery('alpha');
  await state.setQuery('omega', true); held.resolve(page([folder('stale')])); await old;
  assert.equal(calls, 1); assert.equal(state.query, 'omega'); assert.equal(state.search.nodes.size, 0);
});

test("view changes invalidate complete descendant caches and offer a fresh root retry", async () => {
  let view = 'one';
  const source = { async loadChildren(parent) { return {...page([folder(parent ? 'root/deep' : 'root')]), context:{workspace:'ws',viewToken:view}}; } };
  const state = createTreeState({source,context:{workspace:'ws'}}); await state.initialize();
  await state.expand('folder:root'); view='two'; await state.refresh();
  assert.equal(state.open.size,0); assert.equal(state.levels.size,1); assert.equal(state.status().status,'error');
  assert.equal(state.nodes().length,0); await state.retry(); assert.equal(state.status().complete,true);
});

// #465 item 1 — a refresh fetches into a staging level and swaps on completion: the visible
// rows never blank to "Loading…", and every visible open level reloads after its parent.
test("refresh keeps the visible level until the refetch completes, then reloads open levels without toggling", async () => {
  let generation = 0; const held = deferred(); const seen = [];
  const source = { async loadChildren(parent) {
    seen.push(parent);
    if (generation === 1 && parent === null) return held.promise;
    if (parent === null) return page([folder("a"), folder("gone")]);
    if (parent === "folder:a") return page([leaf(generation ? "a/renamed" : "a/old", "folder:a")]);
    return page([leaf("gone/x", "folder:gone")]);
  } };
  const statuses = [];
  const state = createTreeState({ source }, () => statuses.push(state.status().status));
  await state.initialize(); await state.expand("folder:a"); await state.expand("folder:gone");
  generation = 1; statuses.length = 0;
  const pending = state.refresh();
  assert.deepEqual(state.nodes().map(n => n.path), ["a", "gone"], "rows stay while the refetch is held");
  assert.equal(state.status().status, "complete");
  assert.equal(statuses.includes("loading"), false, "no loading state is published for a staged refresh");
  held.resolve(page([folder("a")])); await pending;
  assert.deepEqual(state.nodes().map(n => n.path), ["a"]);
  assert.deepEqual(state.nodes("folder:a").map(n => n.path), ["a/renamed"], "the open child level reloaded without a toggle");
  assert.equal(state.open.has("folder:a"), true);
  assert.equal(state.open.has("folder:gone"), false, "a folder the refetch no longer lists leaves the open set");
  assert.equal(state.levels.has("folder:gone"), false, "and its cached level is dropped");
});

test("refresh invalidates closed cached levels so the next expand refetches them", async () => {
  let renamed = false; const calls = [];
  const source = { async loadChildren(parent) {
    calls.push(parent);
    if (parent === null) return page([folder("a")]);
    if (parent === "folder:a") return page([folder("a/b", "folder:a"), leaf(renamed ? "a/new" : "a/old", "folder:a")]);
    return page([leaf("a/b/x", "folder:a/b")]);
  } };
  const state = createTreeState({ source });
  await state.initialize(); await state.expand("folder:a"); await state.expand("folder:a/b"); state.collapse("folder:a");
  renamed = true; calls.length = 0; await state.refresh();
  assert.deepEqual(calls, [null], "a closed level is not fetched while it is invisible");
  assert.equal(state.open.has("folder:a/b"), false, "descendants of an invalidated level are closed");
  await state.expand("folder:a");
  assert.deepEqual(state.nodes("folder:a").map(n => n.path), ["a/b", "a/new"]);
});

test("a failed staged refresh keeps the visible rows and reports the failure", async () => {
  let fail = false;
  const source = { async loadChildren() { if (fail) throw new Error("Network"); return page([folder("a")]); } };
  const state = createTreeState({ source }); await state.initialize();
  fail = true; await state.refresh();
  assert.deepEqual(state.nodes().map(n => n.path), ["a"]);
  assert.equal(state.status().complete, true);
  assert.equal(state.status().refreshError?.message, "Network");
  fail = false; await state.refresh();
  assert.equal(state.status().refreshError, null);
});

test("a newer refresh supersedes a held staged refresh", async () => {
  const first = deferred(); let calls = 0;
  const source = { async loadChildren() { calls += 1; if (calls === 2) return first.promise; return page([folder(calls === 1 ? "one" : "three")]); } };
  const state = createTreeState({ source }); await state.initialize();
  const stale = state.refresh(); await state.refresh();
  first.resolve(page([folder("two")])); await stale;
  assert.deepEqual(state.nodes().map(n => n.path), ["three"]);
});

// #465 item 6 — a level pages to a documented ceiling per load, then stops and says so; the
// reader's "Load more" resumes from the kept cursor with a fresh budget.
test("a level stops at its page ceiling with a resumable cursor", async () => {
  const cursors = [];
  const source = { async loadChildren(parent, cursor) {
    cursors.push(cursor); const n = cursors.length;
    return page([leaf(`item${n}`)], n < 50 ? `c${n}` : null); // finite, so an unbounded loader fails instead of hanging
  } };
  const state = createTreeState({ source, pageCeiling: 3 }); await state.initialize();
  assert.equal(cursors.length, 3);
  assert.equal(state.status().status, "more");
  assert.equal(state.status().complete, false);
  assert.equal(state.nodes().length, 3);
  await state.retry(); assert.equal(cursors.length, 3, "retry does not resume a level stopped at its ceiling");
  await state.more(); assert.equal(cursors.length, 6);
  assert.deepEqual(cursors.slice(3), ["c3", "c4", "c5"]);
  assert.equal(state.nodes().length, 6);
});

test("the default ceiling is ten pages per load", async () => {
  let calls = 0;
  const state = createTreeState({ source: { async loadChildren() { calls += 1; return page([leaf(`x${calls}`)], calls < 50 ? `c${calls}` : null); } } });
  await state.initialize();
  assert.equal(PAGE_CEILING, 10); assert.equal(calls, PAGE_CEILING); assert.equal(state.status().status, "more");
});

test("a refresh cancelled by collapsing its level does not go on to refetch the levels below it", async () => {
  const held = deferred(); let refreshing = false; const calls = [];
  const source = { async loadChildren(parent) {
    calls.push(parent);
    if (refreshing && parent === "folder:a") return held.promise;
    if (parent === null) return page([folder("a")]);
    if (parent === "folder:a") return page([folder("a/b", "folder:a")]);
    return page([leaf("a/b/x", "folder:a/b")]);
  } };
  const state = createTreeState({ source });
  await state.initialize(); await state.expand("folder:a"); await state.expand("folder:a/b");
  refreshing = true; calls.length = 0;
  const pending = state.refresh("folder:a"); state.collapse("folder:a");
  held.resolve(page([folder("a/b", "folder:a")])); await pending;
  assert.deepEqual(calls, ["folder:a"]);
});
