// #9 slice 2 — the Schedules page's decisions (static/js/schedules/model.js), DOM-free.
//
// What the page shows is decided here from REST data: which folders and leaves a tree level
// has, what a search matches, which preset a cron pattern is, how a run's two logs merge (the
// record's R10), which form field a §20 refusal belongs to, how a typed parameter becomes its
// wire value, and when an idempotency key may be replayed. The DOM halves are the browser
// suite's to prove (SchedulesBrowserTest); these are the rules they delegate to.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const M = require(path.join(here, "../../main/resources/static/js/schedules/model.js"));

const s = (name, extra = {}) => ({ id: name, name, condition: "enabled", cron: "0 6 * * *", timezone: "UTC", payload: { pipeline: "p/x", version: "current" }, ...extra });

test("a level lists its direct folders with subtree counts, and its direct leaves", () => {
  const all = [s("finance/daily/revenue"), s("finance/daily/costs"), s("finance/weekly/summary"), s("ops/nightly"), s("finance/top")];
  const root = M.buildLevel(all, "");
  assert.deepEqual(root.folders.map((f) => [f.path, f.segment, f.count]), [["finance", "finance", 4], ["ops", "ops", 1]]);
  assert.deepEqual(root.leaves, []);
  const finance = M.buildLevel(all, "finance");
  assert.deepEqual(finance.folders.map((f) => [f.path, f.count]), [["finance/daily", 2], ["finance/weekly", 1]]);
  assert.deepEqual(finance.leaves.map((l) => l.name), ["finance/top"]);
  // A prefix never matches a sibling that merely starts with the same letters.
  assert.deepEqual(M.buildLevel([s("fin/x"), s("finance/y")], "fin").leaves.map((l) => l.name), ["fin/x"]);
});

test("folder suggestions are every proper prefix, with the trailing slash", () => {
  assert.deepEqual(M.folderSuggestions([s("a/b/c"), s("a/d")]), ["a/", "a/b/"]);
});

test("search matches every column the list renders, case-insensitively, sorted by name", () => {
  const all = [
    s("z/one", { payload: { pipeline: "nyc/mobility/revenue" } }),
    s("a/two", { condition: "paused" }),
    s("m/three", { cron: "15 * * * *" }),
    s("b/four", { timezone: "America/New_York" }),
  ];
  assert.deepEqual(M.search(all, "MOBILITY").map((x) => x.name), ["z/one"]);
  assert.deepEqual(M.search(all, "paused").map((x) => x.name), ["a/two"]);
  assert.deepEqual(M.search(all, "every hour").map((x) => x.name), ["m/three"]); // the preset wording
  assert.deepEqual(M.search(all, "new_york").map((x) => x.name), ["b/four"]);
  assert.deepEqual(M.search(all, "  "), []);
});

test("describeCron recognises exactly the shapes the presets build", () => {
  assert.deepEqual(M.describeCron("15 * * * *"), { preset: "hourly", minute: 15, label: "Every hour at :15" });
  assert.equal(M.describeCron("30 6 * * *").label, "Every day at 06:30");
  assert.equal(M.describeCron("0 9 * * 1").label, "Every Monday at 09:00");
  assert.equal(M.describeCron("0 3 1 * *").label, "Every month on day 1 at 03:00");
  for (const custom of ["*/15 * * * *", "0 6 * * 1-5", "0 6,18 * * *", "0 6 1 1 *", "0 6 * * 7", "60 * * * *", "0 6 * *", "0 0 0 6 * * *"]) {
    assert.equal(M.describeCron(custom).preset, "custom", custom);
  }
});

test("buildCron and describeCron round-trip every preset", () => {
  const cases = [
    ["hourly", { minute: "7" }, "7 * * * *"],
    ["daily", { time: "06:30" }, "30 6 * * *"],
    ["weekly", { time: "23:05", dow: "0" }, "5 23 * * 0"],
    ["monthly", { time: "00:00", dom: "31" }, "0 0 31 * *"],
  ];
  for (const [preset, parts, cron] of cases) {
    assert.equal(M.buildCron(preset, parts), cron);
    assert.equal(M.describeCron(cron).preset, preset);
  }
  assert.equal(M.buildCron("custom", { cron: "  */5 * * * * " }), "*/5 * * * *");
  // Out-of-range parts clamp rather than write a pattern the server would refuse.
  assert.equal(M.buildCron("hourly", { minute: "99" }), "59 * * * *");
});

test("an occurrence's local time is the SERVER's wall clock, never re-derived in the browser", () => {
  // 01:30 on the fall-back day happens twice in New York; §20.3 names which one — shown as given.
  const inThisYear = M.occurrenceLocalText("2026-11-01T01:30", "en-GB", Date.parse("2026-06-01T00:00:00Z"));
  assert.match(inThisYear, /01:30/);
  assert.match(inThisYear, /Sun/);
  assert.doesNotMatch(inThisYear, /2026/); // the year is written only when it is not this one
  assert.match(M.occurrenceLocalText("2027-01-01T03:00", "en-GB", Date.parse("2026-06-01T00:00:00Z")), /2027/);
  // The compact instant form: in the named zone, the year only when it differs.
  assert.equal(M.shortText("2026-09-27T10:30:00Z", "UTC", Date.parse("2026-01-01T00:00:00Z"), "en-US"), "Sep 27, 10:30");
  assert.equal(M.shortText("2026-09-27T10:30:00Z", "America/New_York", Date.parse("2026-06-01T00:00:00Z"), "en-US"), "Sep 27, 06:30");
  assert.match(M.shortText("2027-01-02T00:00:00Z", "UTC", Date.parse("2026-01-01T00:00:00Z"), "en-US"), /2027/);
  assert.equal(M.offsetText("-04:00"), "UTC−04:00");
  assert.equal(M.offsetText("Z"), "UTC");
  assert.equal(M.offsetText("+05:30"), "UTC+05:30");
});

test("durations and relative times", () => {
  assert.equal(M.durationText("2026-01-01T00:00:00Z", "2026-01-01T00:00:00.250Z"), "250 ms");
  assert.equal(M.durationText("2026-01-01T00:00:00Z", "2026-01-01T00:00:12.340Z"), "12.3 s");
  assert.equal(M.durationText("2026-01-01T00:00:00Z", "2026-01-01T00:03:05Z"), "3 min 5 s");
  assert.equal(M.durationText("2026-01-01T00:00:00Z", null), "");
  assert.equal(M.msText(17), "17 ms");
  assert.equal(M.msText(3723000), "1 h 2 min");
  assert.equal(M.msText(-1), "");
  const now = Date.parse("2026-01-01T12:00:00Z");
  assert.equal(M.relativeText("2026-01-01T15:00:00Z", now), "in 3 h");
  assert.equal(M.relativeText("2026-01-01T11:55:00Z", now), "5 min ago");
});

test("run chips, origins and activity", () => {
  assert.equal(M.runChip("succeeded"), "app-chip-ok");
  assert.equal(M.runChip("unknown"), "app-chip-bad");
  assert.equal(M.runChip("queued"), "app-chip-run");
  assert.equal(M.runChip("not_started"), "app-chip-warn");
  assert.equal(M.runStateText("not_started"), "not started");
  // Not "Run now": a badge worded like the verb beside it reads as a second button.
  assert.equal(M.originText("manual"), "manual");
  assert.equal(M.originText("catch_up"), "catch-up");
  assert.equal(M.isActive({ state: "starting" }), true);
  assert.equal(M.isActive({ state: "skipped" }), false);
  // An unknown block reason still says SOMETHING (the code is shown beside it).
  assert.match(M.blockedText("run_unknown"), /unknown/);
  assert.match(M.blockedText("something_new"), /code below/);
});

test("the Messages pane merges the two logs in time order, each line labelled by its source", () => {
  const trail = [
    { seq: 1, kind: "recorded", reason: null, at: "2026-09-26T10:00:00.000Z", details: {} },
    { seq: 2, kind: "claimed", reason: null, at: "2026-09-26T10:00:01.000Z", details: {} },
    { seq: 3, kind: "execution_started", reason: null, at: "2026-09-26T10:00:02.000Z", details: { execution_id: "e1" } },
    { seq: 4, kind: "finished", reason: null, at: "2026-09-26T10:00:09.000Z", details: { state: "succeeded" } },
  ];
  const events = [
    { event_id: 1, event: "execution_started", timestamp: "2026-09-26T10:00:02.000Z", data: { pipeline_version: 3 } },
    { event_id: 2, event: "node_completed", timestamp: "2026-09-26T10:00:05.000Z", data: { node_id: "rows", rows_out: 12, duration_ms: 40 } },
    { event_id: 3, event: "pipeline_completed", timestamp: "2026-09-26T10:00:08.000Z", data: { duration_ms: 6000 } },
  ];
  const lines = M.mergeMessages(trail, events);
  assert.deepEqual(
    lines.map((l) => l.source + ":" + l.what),
    [
      "scheduler:recorded",
      "scheduler:claimed",
      // Equal instants: the scheduler's hand-over line first, then the pipeline's own.
      "scheduler:execution_started",
      "pipeline:execution_started",
      "pipeline:node_completed",
      "pipeline:pipeline_completed",
      "scheduler:finished",
    ],
  );
  assert.equal(lines[2].detail, 'execution_id e1');
  assert.equal(lines[3].detail, "v3");
  assert.equal(lines[4].detail, "rows · 12 rows · 40 ms");
  // A run that never started: the scheduler's lines alone.
  assert.deepEqual(M.mergeMessages(trail.slice(0, 2), []).map((l) => l.source), ["scheduler", "scheduler"]);
});

test("pipeline failure lines carry the failed node and the catalog's message", () => {
  const line = M.pipelineLine({
    event_id: 9, event: "node_failed", timestamp: "2026-09-26T10:00:05Z",
    data: { node_id: "fetch", error: { code: "pipeline.node.datasource_connection_failed", user_message: "We couldn't reach it." } },
  });
  assert.equal(line.detail, "fetch · pipeline.node.datasource_connection_failed: We couldn't reach it.");
});

test("a §20 refusal lands beside the field it names, or is a toast", () => {
  assert.deepEqual(M.fieldForError({ code: "schedule.validation.name_invalid" }), { field: "name" });
  assert.deepEqual(M.fieldForError({ code: "schedule.name_taken" }), { field: "name" });
  assert.deepEqual(M.fieldForError({ code: "schedule.validation.interval_too_short" }), { field: "cron" });
  assert.deepEqual(M.fieldForError({ code: "schedule.validation.timezone_invalid" }), { field: "timezone" });
  assert.deepEqual(M.fieldForError({ code: "schedule.validation.target_not_found" }), { field: "pipeline" });
  assert.deepEqual(M.fieldForError({ code: "schedule.validation.request_invalid", details: { field: "payload" } }), { field: "pipeline" });
  assert.equal(M.fieldForError({ code: "schedule.validation.request_invalid", details: { field: "If-Match" } }), null);
  assert.equal(M.fieldForError({ code: "schedule.limit.per_workspace" }), null);
  assert.equal(M.fieldForError({ code: "idempotency.key_reused_for_different_request" }), null);
  // The binder reports every failure at once (PipelineValidationException's details.failures).
  const binder = {
    code: "pipeline.execution.parameter_required",
    details: {
      failures: [
        { code: "pipeline.execution.parameter_required", path: "parameters.min_id", message: "min_id is required", details: { parameter: "min_id" } },
        { code: "pipeline.execution.invalid_parameter_type", path: "parameters.day", message: "day must be a DATE", details: {} },
      ],
    },
  };
  assert.deepEqual(M.fieldForError(binder), {
    field: "parameters",
    parameters: [
      { parameter: "min_id", message: "min_id is required" },
      { parameter: "day", message: "day must be a DATE" },
    ],
  });
});

test("parameters: typed text becomes its wire value, strictly", () => {
  assert.equal(M.coerceParameter("42", "INTEGER"), 42);
  assert.equal(M.coerceParameter("-3", "INTEGER"), -3);
  assert.equal(M.coerceParameter("12abc", "INTEGER"), "12abc"); // the binder refuses it, beside the field
  assert.equal(M.coerceParameter("1.5", "DECIMAL"), 1.5);
  assert.equal(M.coerceParameter("123456789012345678901234567890", "BIGINTEGER"), "123456789012345678901234567890");
  assert.equal(M.coerceParameter("true", "BOOLEAN"), true);
  assert.equal(M.coerceParameter("yes", "BOOLEAN"), "yes");
  assert.equal(M.coerceParameter("2026-01-31", "DATE"), "2026-01-31");
  const declared = { n: { type: "INTEGER" }, d: { type: "DATE" }, flag: { type: "BOOLEAN" } };
  // Blank is "not given" — the declared default applies.
  assert.deepEqual(M.collectParameters(declared, { n: "7", d: "", flag: "false", extra: "kept" }), { n: 7, flag: false, extra: "kept" });
  assert.equal(M.parameterText(7), "7");
  assert.equal(M.parameterText(false), "false");
  assert.equal(M.parameterText("x"), "x");
  assert.deepEqual(
    M.declaredParameters({ b: { type: "STRING" }, a: { type: "INTEGER" }, calc_key: { type: "ANY", derived: true } }).map((d) => d.name),
    ["a", "b"],
  );
});

test("an idempotency key is replayed only for the SAME request after an unanswered try", () => {
  let n = 0;
  const key = () => "k" + ++n;
  const first = M.nextAttempt(null, "body-A", key);
  assert.equal(first.key, "k1");
  // Answered (a validation refusal), then the same body again: a NEW attempt, a new key.
  assert.equal(M.nextAttempt(first, "body-A", key).key, "k2");
  // Unanswered (the network failed): the retry of the same body reuses the key…
  first.unanswered = true;
  assert.equal(M.nextAttempt(first, "body-A", key).key, "k1");
  // …but a changed body never does (the server would refuse the reuse).
  assert.equal(M.nextAttempt(first, "body-B", key).key, "k3");
});

test("uuidFrom writes an RFC 4122 v4 id", () => {
  const id = M.uuidFrom(new Uint8Array(16).fill(0xff));
  assert.match(id, /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
});
