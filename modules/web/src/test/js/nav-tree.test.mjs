// #350 — the sidebar trees' controller (static/js/nav-tree.js): its pure halves. The DOM half
// (open/close, the swaps, the width) is driven in a real browser by PipelineSidebarTree*
// browser tests; what is pinned here is every DECISION those paths take — what may be stored,
// what a stored entry can become, which swap is admitted, which folders a reveal opens, and
// the width arithmetic — so a regression in a policy is a unit failure, not a flaky pixel.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const nav = require(path.resolve(here, "../../main/resources/static/js/nav-tree.js"));

test("state keys are per family AND per workspace — another workspace's entry is a different key", () => {
  assert.equal(nav.stateKey("pipelines", "acme"), "dp-nav:pipelines:acme");
  assert.notEqual(nav.stateKey("pipelines", "acme"), nav.stateKey("pipelines", "beta"));
  assert.notEqual(nav.stateKey("pipelines", "acme"), nav.stateKey("dashboards", "acme"));
  assert.equal(nav.railKey("acme"), "dp-nav-rail:acme");
});

test("a stored state round-trips paths and offsets — and nothing else", () => {
  const raw = nav.serializeState({ open: true, folders: ["nyc", "nyc/mobility"], top: 120.6, left: 33.2, rows: ["leak"] });
  assert.deepEqual(JSON.parse(raw), { open: true, folders: ["nyc", "nyc/mobility"], top: 121, left: 33 });
  assert.deepEqual(nav.parseState(raw), { open: true, folders: ["nyc", "nyc/mobility"], top: 121, left: 33 });
});

test("storage is input: a corrupt, hostile or oversized entry parses to the closed shape", () => {
  const empty = { open: false, folders: [], top: 0, left: 0 };
  assert.deepEqual(nav.parseState(null), empty);
  assert.deepEqual(nav.parseState(""), empty);
  assert.deepEqual(nav.parseState("{not json"), empty);
  assert.deepEqual(nav.parseState("42"), empty);
  // open must be literally true; non-string / empty / over-long folder entries are dropped;
  // negative or non-numeric offsets become 0.
  const hostile = JSON.stringify({
    open: "yes",
    folders: ["ok", 7, "", "x".repeat(600), { a: 1 }, "<img src=x onerror=alert(1)>"],
    top: -5,
    left: "9",
  });
  assert.deepEqual(nav.parseState(hostile), {
    open: false,
    folders: ["ok", "<img src=x onerror=alert(1)>"], // kept as an opaque string: it is only ever
    top: 0, //                                            compared with a title, never rendered
    left: 0,
  });
  const many = JSON.stringify({ open: true, folders: Array.from({ length: 500 }, (_, i) => "f" + i) });
  assert.equal(nav.parseState(many).folders.length, nav.MAX_FOLDERS);
});

test("the rail entry pre-paint reads is a flag and a bounded pixel count", () => {
  assert.deepEqual(nav.parseRail(JSON.stringify({ open: true, fit: 351.4 })), { open: true, fit: 351 });
  assert.deepEqual(nav.parseRail(JSON.stringify({ open: true, fit: 99999 })), { open: true, fit: 0 });
  assert.deepEqual(nav.parseRail("garbage"), { open: false, fit: 0 });
});

test("a reveal opens exactly the folders above the leaf, outermost first", () => {
  assert.deepEqual(nav.ancestorsOf("nyc/mobility/revenue_by_borough"), ["nyc", "nyc/mobility"]);
  assert.deepEqual(nav.ancestorsOf("solo"), []);
  assert.deepEqual(nav.ancestorsOf(""), []);
  assert.deepEqual(nav.ancestorsOf(null), []);
});

test("closing a folder forgets it and everything beneath it, never a sibling that shares a prefix", () => {
  const open = ["nyc", "nyc/mobility", "nyc/mobility/taxi", "nyc-archive", "trade"];
  assert.deepEqual(nav.forgetFolder(open, "nyc"), ["nyc-archive", "trade"]);
  assert.deepEqual(nav.forgetFolder(open, "nyc/mobility"), ["nyc", "nyc-archive", "trade"]);
  assert.deepEqual(nav.rememberFolder(["a"], "a"), ["a"]);
  assert.deepEqual(nav.rememberFolder(["a"], "b"), ["a", "b"]);
  const full = Array.from({ length: nav.MAX_FOLDERS }, (_, i) => "f" + i);
  const next = nav.rememberFolder(full, "new");
  assert.equal(next.length, nav.MAX_FOLDERS);
  assert.equal(next[next.length - 1], "new");
});

test("the stamp is <workspace>|<lens>; anything else is no stamp", () => {
  assert.deepEqual(nav.parseStamp("acme|all"), { workspace: "acme", lens: "all" });
  assert.deepEqual(nav.parseStamp("acme|lens"), { workspace: "acme", lens: "lens" });
  assert.equal(nav.parseStamp(null), null);
  assert.equal(nav.parseStamp("acme"), null);
});

test("ADMISSION — the live generation, the tree's workspace and the tree's lens must all match", () => {
  const stamp = { workspace: "acme", lens: "all" };
  assert.equal(nav.admit(3, 3, stamp, "acme", "all"), "admit");
  assert.equal(nav.admit(3, 3, stamp, "acme", null), "admit", "the first root answer adopts its lens");
  // a search typed/cleared or a reset since the request left: the answer is the past
  assert.equal(nav.admit(2, 3, stamp, "acme", "all"), "stale");
  // the generation is checked FIRST: a stale answer from another workspace is merely stale
  assert.equal(nav.admit(2, 3, { workspace: "beta", lens: "all" }, "acme", "all"), "stale");
  assert.equal(nav.admit(3, 3, { workspace: "beta", lens: "all" }, "acme", "all"), "foreign-workspace");
  assert.equal(nav.admit(3, 3, { workspace: "acme", lens: "lens" }, "acme", "all"), "lens-changed");
  // a family whose route carries no stamp (dashboards) is still guarded by the generation
  assert.equal(nav.admit(3, 3, null, "acme", null), "admit");
  assert.equal(nav.admit(1, 3, null, "acme", null), "stale");
});

test("the fit is the content plus the rail's own chrome; the bounds are CSS's", () => {
  // rail 320 wide, region 290 wide -> 30px of chrome; 410px of rows needs a 440px rail (CSS
  // then clamps to --app-rail-tree-max and the region scrolls).
  assert.equal(nav.fitWidth(410, 320, 290), 440);
  assert.equal(nav.fitWidth(150.2, 320, 290), 181);
  assert.equal(nav.fitWidth(0, 320, 290), 30);
  assert.equal(nav.fitWidth(5000, 320, 290), 0, "an absurd measurement writes nothing");
  assert.equal(nav.fitWidth(100, 200, 260), 100, "negative chrome is no chrome");
});

test("the current page's leaf id comes from the item's own path prefix only", () => {
  const id = "3f1c2d4e-0000-4000-8000-000000000001";
  assert.equal(nav.currentIdFor("/pipelines", "/pipelines/" + id), id);
  assert.equal(nav.currentIdFor("/pipelines", "/pipelines/" + id + "/editor"), id);
  assert.equal(nav.currentIdFor("/pipelines", "/pipelines"), null);
  assert.equal(nav.currentIdFor("/pipelines", "/pipelinesx/" + id), null);
  assert.equal(nav.currentIdFor("/dashboards", "/dashboards/" + id), id);
  assert.equal(nav.currentIdFor(null, "/pipelines/" + id), null);
  assert.equal(nav.currentIdFor("/pipelines", "/pipelines/%E0"), null); // malformed escape: no leaf, no URIError
});
