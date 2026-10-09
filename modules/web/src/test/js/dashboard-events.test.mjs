// #473 — the activity dock's pure module (events-dock.js): the capped log, the severity/kind
// buckets, the fixed per-event sentences and the filters. Node's built-in runner; DOM-free,
// like the module under test.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const dock = require(path.resolve(here, "../../main/resources/static/js/dashboards/events-dock.js"));

test("the log is a capped ring: the newest survive, the eviction count is exact", () => {
  const log = dock.createDashboardEventLog({ limit: 3 });
  for (let i = 1; i <= 5; i++) log.add({ event: "refresh_started", n: i });
  assert.deepEqual(
    log.all().map((e) => e.n),
    [3, 4, 5],
    "the newest 3 remain, in arrival order",
  );
  assert.equal(log.size(), 3);
  assert.equal(log.droppedTotal(), 2, "exactly the evicted count");
  log.add(null);
  log.add("junk");
  assert.equal(log.size(), 3, "a non-event adds nothing");
  log.clear();
  assert.equal(log.size(), 0);
  assert.equal(log.droppedTotal(), 0, "clear is a view action over the whole buffer");
  assert.equal(dock.createDashboardEventLog().limit, dock.DEFAULT_LIMIT, "the default cap is the module's");
});

test("severity: a notification carries its own, failures are errors, stale is a warning, the rest are info", () => {
  assert.equal(dock.severityOf({ event: "notification", severity: "warning" }), "warning");
  assert.equal(dock.severityOf({ event: "notification", severity: "error" }), "error");
  assert.equal(dock.severityOf({ event: "source_failed" }), "error");
  assert.equal(dock.severityOf({ event: "refresh_completed", status: "failed" }), "error");
  assert.equal(dock.severityOf({ event: "abort_requested" }), "error");
  assert.equal(dock.severityOf({ event: "abort_acked" }), "error");
  assert.equal(dock.severityOf({ event: "visualization_stale" }), "warning");
  assert.equal(dock.severityOf({ event: "refresh_completed", status: "completed" }), "info");
  assert.equal(dock.severityOf({ event: "visualization_rendered" }), "info");
  assert.equal(dock.severityOf({ event: "notification" }), "info", "a notification without a severity is info");
});

test("kind buckets match the Kind select's words", () => {
  assert.equal(dock.kindOf({ event: "source_started" }), "source");
  assert.equal(dock.kindOf({ event: "source_completed" }), "source");
  assert.equal(dock.kindOf({ event: "source_failed" }), "source");
  assert.equal(dock.kindOf({ event: "visualization_data" }), "visualization");
  assert.equal(dock.kindOf({ event: "visualization_rendered" }), "visualization");
  assert.equal(dock.kindOf({ event: "visualization_stale" }), "visualization");
  assert.equal(dock.kindOf({ event: "refresh_started" }), "refresh");
  assert.equal(dock.kindOf({ event: "refresh_completed" }), "refresh");
  assert.equal(dock.kindOf({ event: "abort_requested" }), "abort");
  assert.equal(dock.kindOf({ event: "connection_lost" }), "connection");
  assert.equal(dock.kindOf({ event: "notification" }), "notification");
});

test("every event formats as one fixed sentence — names and outcome words only, never a raw body", () => {
  assert.equal(dock.formatEvent({ event: "refresh_started" }), "Refresh started.");
  assert.equal(dock.formatEvent({ event: "source_started", name: "orders" }), "orders loading…");
  assert.equal(dock.formatEvent({ event: "source_started" }), "Source loading…");
  assert.equal(dock.formatEvent({ event: "source_completed", name: "orders" }), "orders loaded.");
  assert.equal(dock.formatEvent({ event: "source_failed", name: "orders" }), "orders failed.");
  assert.equal(dock.formatEvent({ event: "source_failed", name: "orders", stage: "render" }), "orders failed (render).");
  assert.equal(dock.formatEvent({ event: "visualization_data", name: "revenue" }), "revenue received new data.");
  assert.equal(dock.formatEvent({ event: "visualization_rendered", name: "revenue" }), "revenue rendered.");
  assert.equal(
    dock.formatEvent({ event: "visualization_stale", name: "revenue" }),
    "revenue is showing out-of-date data.",
  );
  assert.equal(dock.formatEvent({ event: "abort_requested" }), "Abort requested.");
  assert.equal(dock.formatEvent({ event: "abort_acked" }), "Abort acknowledged — the refresh stopped.");
  assert.equal(
    dock.formatEvent({ event: "refresh_completed", status: "completed", outcomes: { a: "loaded", b: "rendered" } }),
    "Refresh completed — loaded · rendered.",
  );
  assert.equal(dock.formatEvent({ event: "refresh_completed", status: "failed" }), "Refresh failed.");
  assert.equal(
    dock.formatEvent({ event: "notification", message: "the connection to the refresh was lost", code: "transport.disconnected" }),
    "the connection to the refresh was lost.",
    "the runtime's own fixed sentence",
  );
  assert.equal(
    dock.formatEvent({ event: "notification", code: "transport.disconnected" }),
    "transport.disconnected.",
    "a code, never an invented story",
  );
});

test("filters: the Errors view is severity error; the kind select narrows both views", () => {
  const events = [
    { event: "source_failed" },
    { event: "visualization_rendered" },
    { event: "notification", severity: "error" },
    { event: "visualization_stale" },
  ];
  const errorsOnly = events.filter((e) => dock.matchesFilters(e, { severity: "error" }));
  assert.deepEqual(
    errorsOnly.map((e) => e.event),
    ["source_failed", "notification"],
  );
  const viz = events.filter((e) => dock.matchesFilters(e, { kind: "visualization" }));
  assert.deepEqual(
    viz.map((e) => e.event),
    ["visualization_rendered", "visualization_stale"],
  );
  const both = events.filter((e) => dock.matchesFilters(e, { severity: "error", kind: "source" }));
  assert.equal(both.length, 1);
  assert.equal(events.filter((e) => dock.matchesFilters(e, {})).length, 4, "no filters pass everything");
});

test("shortId: the timeline's 8-character shape, shorter ids untouched", () => {
  assert.equal(dock.shortId("7c1e93ab-1234-5678-9abc-def012345678"), "7c1e93ab");
  assert.equal(dock.shortId("abc"), "abc");
  assert.equal(dock.shortId(null), "");
  assert.equal(dock.shortId(undefined), "");
});
