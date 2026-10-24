import { test } from "node:test";
import assert from "node:assert/strict";
import { createTreeState, PAGE_CEILING } from "../../main/resources/static/js/tree/state.mjs";
import { createTreeRenderer } from "../../main/resources/static/js/tree/render.mjs";

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

// A minimal DOM stand-in for the renderer: elements with attributes, ordered children, the
// `hidden` property, text nodes and the selector shapes render.mjs and these tests use
// (`[name]`, `[name="v"]`, `.class` — #604's region assertions read `.dp-tree-title`/`.dp-tree-meta`).
function fakeDocument() {
  const document = { activeElement: null };
  const detach = node => {
    if (!node.parentNode) return;
    const siblings = node.parentNode.childNodes; siblings.splice(siblings.indexOf(node), 1); node.parentNode = null;
  };
  const matches = (element, selector) => {
    const classShape = selector.match(/^\.([\w-]+)$/);
    if (classShape) return element.className.split(" ").includes(classShape[1]);
    const [, name, value] = selector.match(/^\[([\w-]+)(?:="([^"]*)")?\]$/) || [];
    if (!name) throw new Error(`unsupported selector ${selector}`);
    if (name === "hidden") return element.hidden === true;
    return value === undefined ? element.hasAttribute(name) : element.getAttribute(name) === value;
  };
  function make(tagName, namespaceURI = "http://www.w3.org/1999/xhtml") {
    const attributes = new Map();
    const element = {
      tagName: tagName.toUpperCase(), namespaceURI, ownerDocument: document, parentNode: null, childNodes: [],
      className: "", title: "", hidden: false, tabIndex: 0, type: "", dataset: {}, ownText: "",
      classList: { add(...names) { element.className = [element.className, ...names].filter(Boolean).join(" "); } },
      get children() { return element.childNodes.filter(child => child.tagName); },
      get lastChild() { return element.childNodes.at(-1) ?? null; },
      get isConnected() { let node = element; while (node.parentNode) node = node.parentNode; return node === document.body; },
      get textContent() { return element.ownText + element.childNodes.map(child => child.textContent).join(""); },
      set textContent(value) { element.replaceChildren(); element.ownText = String(value); },
      setAttribute(name, value) { attributes.set(name, String(value)); },
      getAttribute(name) { return attributes.has(name) ? attributes.get(name) : null; },
      hasAttribute(name) { return attributes.has(name); },
      removeAttribute(name) { attributes.delete(name); },
      append(...nodes) { nodes.forEach(node => element.insertBefore(node, null)); },
      insertBefore(node, reference) {
        detach(node);
        const at = reference ? element.childNodes.indexOf(reference) : -1;
        if (at < 0) element.childNodes.push(node); else element.childNodes.splice(at, 0, node);
        node.parentNode = element; return node;
      },
      replaceChildren() { [...element.childNodes].forEach(detach); element.ownText = ""; },
      remove() { detach(element); },
      contains(node) { for (let at = node; at; at = at.parentNode) if (at === element) return true; return false; },
      closest(selector) { for (let at = element; at && at.tagName; at = at.parentNode) if (matches(at, selector)) return at; return null; },
      querySelectorAll(selector) {
        const found = [];
        const walk = node => node.children.forEach(child => { if (matches(child, selector)) found.push(child); walk(child); });
        walk(element); return found;
      },
      addEventListener() {}, removeEventListener() {},
      focus() { document.activeElement = element; },
    };
    return element;
  }
  document.createElement = tag => make(tag);
  document.createElementNS = (namespace, tag) => make(tag, namespace);
  document.createTextNode = text => ({ textContent: String(text), parentNode: null, remove() { detach(this); } });
  document.body = make("body");
  return document;
}

// #533 — the sidebar's leaf carries the catalog row's needs-review marker (partials/template-search.html):
// the same classes, attribute, text and title, after the draft badge, on exactly the flagged leaves, and
// removed/re-appended as the flag changes, the way the draft badge is.
test("a leaf whose listed version needs review carries the catalog's marker, and only while flagged", async () => {
  const document = fakeDocument(); const host = document.createElement("div"); document.body.append(host);
  let drifted = true; let drafting = true;
  const source = { async loadChildren(parent) {
    if (parent === null) return page([folder("a")]);
    return page([
      { ...leaf("a/active_orders.jsonata", "folder:a"), version: 2, needsReview: drifted },
      { ...leaf("a/orders.sql", "folder:a"), version: 1, needsReview: false },
      { ...leaf("a/legacy.sql", "folder:a"), version: 1 },
      { ...leaf("a/next.jsonata", "folder:a"), version: 1, draftVersion: drafting ? 2 : null, needsReview: true },
    ]);
  } };
  const state = createTreeState({ source });
  const renderer = createTreeRenderer(host, state, { label: "Templates", getHref: node => `/templates/${node.path}` });
  const control = key => host.querySelectorAll(`[data-tree-key="${key}"]`)[0].children[0].children[0];
  // #604: the badges live in the row's trailing metadata region, in the same order as before.
  const meta = key => control(key).querySelectorAll(".dp-tree-meta")[0];
  const badges = key => meta(key).children.filter(child => child.className.startsWith("ds-badge")).map(child => child.className.split(" ").at(-1));
  const markers = () => host.querySelectorAll("[data-needs-review]");
  await state.initialize(); renderer.render(); await state.expand("folder:a"); renderer.render();

  const marker = meta("artifact:a/active_orders.jsonata").lastChild;
  assert.equal(marker.className, "ds-badge ds-badge-warning dp-tree-review");
  assert.equal(marker.getAttribute("data-needs-review"), "");
  assert.equal(marker.textContent, "needs review");
  assert.equal(marker.title, "A fact this template cites was retired or superseded — re-verify before you lean on it.");
  assert.equal(marker.getAttribute("aria-hidden"), null, "the badge text is its accessible text");
  assert.deepEqual(badges("artifact:a/active_orders.jsonata"), ["dp-tree-version", "dp-tree-review"]);
  assert.deepEqual(badges("artifact:a/orders.sql"), ["dp-tree-version"], "false reads clean");
  assert.deepEqual(badges("artifact:a/legacy.sql"), ["dp-tree-version"], "an absent flag reads clean");
  assert.deepEqual(badges("artifact:a/next.jsonata"), ["dp-tree-version", "dp-tree-draft", "dp-tree-review"], "after the draft badge");
  assert.equal(control("folder:a").children.some(child => child.className.includes("dp-tree-review")), false, "never on a folder");
  assert.equal(markers().length, 2);

  drifted = false; drafting = false; await state.refresh(); renderer.render();
  assert.deepEqual(badges("artifact:a/active_orders.jsonata"), ["dp-tree-version"], "a cleared flag removes the marker");
  assert.deepEqual(badges("artifact:a/next.jsonata"), ["dp-tree-version", "dp-tree-review"]);

  drifted = true; drafting = true; await state.refresh(); renderer.render();
  assert.deepEqual(badges("artifact:a/active_orders.jsonata"), ["dp-tree-version", "dp-tree-review"], "and a raised one re-appends it");
  assert.deepEqual(badges("artifact:a/next.jsonata"), ["dp-tree-version", "dp-tree-draft", "dp-tree-review"], "the returning draft stays before it");
  assert.equal(markers().length, 2);
  renderer.dispose();
});

// #604 — the row's regions (record §5/§7): the label is its own title span, the badges
// live in a trailing metadata region, and a folder carries the same structure with
// nothing visible in its region. The control stays the line's first child and every
// aria attribute is untouched — the browser tree tests are that contract's witness.
test("a row is an icon, a title span and a trailing metadata region; a folder's region shows no badge", async () => {
  const document = fakeDocument(); const host = document.createElement("div"); document.body.append(host);
  const source = { async loadChildren(parent) {
    if (parent === null) return page([folder("region"), { ...leaf("region/row.jsonata"), version: 3, draftVersion: 4, needsReview: true }]);
    return page([]);
  } };
  const state = createTreeState({ source });
  const renderer = createTreeRenderer(host, state, { label: "Templates", getHref: node => `/templates/${node.path}` });
  await state.initialize(); renderer.render();
  const controlOf = key => host.querySelectorAll(`[data-tree-key="${key}"]`)[0].children[0].children[0];

  const row = controlOf("artifact:region/row.jsonata");
  const title = row.children[1];
  assert.equal(title.className, "dp-tree-title", "the label is the title region");
  assert.equal(title.textContent, "region/row.jsonata");
  const meta = row.children.at(-1);
  assert.equal(meta.className, "dp-tree-meta", "the metadata region is the control's last child");
  assert.deepEqual(meta.children.map(child => child.className.split(" ").at(-1)),
    ["dp-tree-version", "dp-tree-draft", "dp-tree-review"], "the three badges, in order, inside the region");
  assert.equal(meta.children.find(child => child.className.includes("dp-tree-version")).textContent, "v4");

  const folderRow = controlOf("folder:region");
  assert.equal(folderRow.children[1].className, "dp-tree-title", "a folder carries the same title region");
  const folderMeta = folderRow.children.at(-1);
  assert.equal(folderMeta.className, "dp-tree-meta");
  assert.equal(folderMeta.children.filter(child => !child.hidden).length, 0, "a folder's region shows no badge");
  renderer.dispose();
});

// #701 — the owner ruling of 2026-10-24: a folder's glyph follows its expansion in the
// same indent slot (folder closed, folder-open expanded), a leaf keeps the file glyph,
// and the full canonical path stays in the control's and the label's title.
test("a folder's glyph follows its expansion between folder and folder-open; a leaf keeps file", async () => {
  const document = fakeDocument(); const host = document.createElement("div"); document.body.append(host);
  const source = { async loadChildren(parent) {
    if (parent === null) return page([folder("glyph")]);
    return page([leaf("glyph/row.jsonata", "folder:glyph")]);
  } };
  const state = createTreeState({ source });
  const renderer = createTreeRenderer(host, state, { label: "Templates", getHref: node => `/templates/${node.path}` });
  await state.initialize(); renderer.render();
  const controlOf = key => host.querySelectorAll(`[data-tree-key="${key}"]`)[0].children[0].children[0];
  const glyphHref = control => control.children[0].children[0].getAttribute("href");

  assert.equal(glyphHref(controlOf("folder:glyph")).endsWith("#folder"), true, "a collapsed folder shows the closed folder glyph");
  await state.expand("folder:glyph"); renderer.render();
  assert.equal(glyphHref(controlOf("folder:glyph")).endsWith("#folder-open"), true, "an expanded folder shows the open folder glyph");
  assert.equal(glyphHref(controlOf("artifact:glyph/row.jsonata")).endsWith("#file"), true, "a leaf keeps the file glyph");
  assert.equal(controlOf("folder:glyph").title, "glyph", "the control's title carries the full path");
  assert.equal(controlOf("folder:glyph").children[1].title, "glyph", "the label's title carries the full path");
  await state.collapse("folder:glyph"); renderer.render();
  assert.equal(glyphHref(controlOf("folder:glyph")).endsWith("#folder"), true, "collapsing returns the closed folder glyph");
  renderer.dispose();
});
