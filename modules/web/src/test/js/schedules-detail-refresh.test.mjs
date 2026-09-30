// #336-b — a deferred runs refresh acts only on the selection and page it started for.
//
// The defect (orchestrator review F1): refreshRuns's catch had no live-page/current-schedule
// check, so A's late failure marked whatever pane was current when the rejection arrived —
// B turned stale on A's outage and A's toast fired over B — and leaving the page threw on
// the null pane. The shared module-global `staleToastShown` also survived selection and
// reload, so B's first real failure stayed silent after A's.
//
// Now both completion arms are guarded by the page generation (`detailSeq`, bumped by every
// show) AND the originating schedule id; the outage flag lives on the selection's own state,
// so it starts fresh per selection and after a successful reload. Red on the delivered
// f470b30f by construction: the switch-to-B and page-exit rejections marked B / threw, the
// A→B→A old success cleared the re-selected A's live stale mark, and B's first failure was
// silenced by A's toast.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const detailPath = path.resolve(here, "../../main/resources/static/js/schedules/detail.js");

const ACTIVE_RUN = { id: "r1", state: "RUNNING" };

/** A DOM node that absorbs everything runRow and the slots touch. */
const el = () => ({
  hidden: true,
  textContent: "",
  className: "",
  setAttribute() {},
  removeAttribute() {},
  appendChild() {},
  querySelector: () => el(),
  querySelectorAll: () => [],
  getAttribute: () => null,
});

/**
 * Loads detail.js fresh against a minimal page: `note` is the runs-stale slot the assertions
 * read, `timers` records every setTimeout (a re-armed poll carries the 4 s cadence and never
 * fires), `toasts` every error toast, `calls` one entry per API runs read in order (settle
 * them with rejectCall/resolveCall). `present=false` is the page left (live false, no pane).
 * switchTo models what a successful show leaves behind: a bumped detailSeq, a fresh current
 * and a re-rendered pane (the stale note back in its hidden template state).
 */
function loadDetail() {
  const env = { present: true, seq: 1, toasts: [], timers: [], calls: [] };
  const note = { hidden: true };
  const root = {
    querySelector: (sel) => (sel === "[data-slot=runs-stale]" ? note : el()),
    querySelectorAll: () => [],
  };
  const paneEl = { querySelector: () => root };
  const S = {
    state: {
      current: { schedule: { id: "A" }, runs: [ACTIVE_RUN] },
      detailSeq: env.seq,
      durations: {},
      // no execution reads: fillDurations exits before its per-run work
      root: { isConnected: true, getAttribute: () => "false" },
    },
    live: () => env.present,
    slot: () => el(),
    clone: () => el(),
    clear: () => {},
    show: (e, on) => {
      if (e) e.hidden = !on;
    },
    text: () => el(),
    time: () => el(),
    toast: () => {},
    toastError: (err, title) => env.toasts.push({ err, title }),
  };
  globalThis.window = {
    DpSchedules: S,
    DpSchedulesApi: {
      runs: (id) =>
        new Promise((resolve, reject) => {
          env.calls.push({ id, resolve, reject });
        }),
    },
    DpSchedulesModel: {
      isActive: (run) => run.state === "RUNNING",
      runChip: () => "app-chip-neutral",
      runStateText: () => "running",
      originText: () => "scheduled",
      shortText: () => "",
    },
  };
  globalThis.document = {
    getElementById: () => (env.present ? paneEl : null),
    createElement: () => el(),
    hidden: false,
  };
  const realSetTimeout = globalThis.setTimeout;
  globalThis.setTimeout = (fn, ms) => {
    env.timers.push(ms);
    const t = realSetTimeout(() => {}, 10_000_000); // never fires: the poll's effect is out of scope
    if (t.unref) t.unref();
    return t;
  };
  delete require.cache[require.resolve(detailPath)];
  require(detailPath);
  env.detail = S.detail;
  env.note = note;
  env.restore = () => {
    globalThis.setTimeout = realSetTimeout;
  };
  return env;
}

/** Selects another schedule the way a successful show leaves the page. */
function switchTo(env, id) {
  env.seq += 1;
  env.note.hidden = true; // the fresh render carries the note hidden
  globalThis.window.DpSchedules.state = {
    ...globalThis.window.DpSchedules.state,
    detailSeq: env.seq,
    current: { schedule: { id }, runs: [ACTIVE_RUN], staleToastShown: false },
  };
}

function rejectCall(env, n, err) {
  env.calls[n].reject(err);
  return env.calls[n];
}

function resolveCall(env, n, data) {
  env.calls[n].resolve({ data, pagination: { has_more: false } });
  return env.calls[n];
}

const RUNS_ANSWER = { items: [ACTIVE_RUN] };

test("A's late failure after switching to B leaves B fresh, toasts nothing and arms no poll", async () => {
  const env = loadDetail();
  const pending = env.detail.refreshRuns(); // call 0: A
  switchTo(env, "B");
  rejectCall(env, 0, new Error("A failed after navigation"));
  await pending; // resolves — no unhandled rejection
  env.restore();

  assert.equal(env.note.hidden, true); // B's runs stay fresh
  assert.deepEqual(env.toasts, []); // no A toast over B
  assert.deepEqual(env.timers, []); // A's failure arms no poll for B
});

test("A's late failure after the page is left throws nothing, touches no DOM, arms no poll", async () => {
  const env = loadDetail();
  const pending = env.detail.refreshRuns();
  env.present = false; // live() false and the pane is gone
  rejectCall(env, 0, new Error("A failed after exit"));
  await pending;
  env.restore();

  assert.deepEqual(env.toasts, []);
  assert.deepEqual(env.timers, []);
});

test("an A→B→A sequence: the first A's old failure does not touch the re-selected A", async () => {
  const env = loadDetail();
  const stale = env.detail.refreshRuns(); // call 0: A, generation 1
  switchTo(env, "B");
  switchTo(env, "A"); // back on A, generation 3
  rejectCall(env, 0, new Error("the first A's late failure"));
  await stale;
  env.restore();

  assert.equal(env.note.hidden, true); // the new A is untouched
  assert.deepEqual(env.toasts, []);
});

test("an A→B→A sequence: the first A's old success does not clear the re-selected A's stale mark", async () => {
  const env = loadDetail();
  const oldA = env.detail.refreshRuns(); // call 0: A, generation 1
  switchTo(env, "B");
  switchTo(env, "A"); // generation 3: a NEW selection of A
  const newA = env.detail.refreshRuns(); // call 1: the new A's own refresh
  rejectCall(env, 1, new Error("the new A's outage")); // the new selection goes stale, toasts once
  await newA;
  assert.equal(env.toasts.length, 1);
  assert.equal(env.note.hidden, false);

  resolveCall(env, 0, RUNS_ANSWER); // the FIRST A's success finally lands
  await oldA;
  env.restore();

  assert.equal(env.note.hidden, false); // the stale mark survives the old success
  assert.equal(env.toasts.length, 1); // and the old success toasts nothing
});

test("the first failure on B toasts even though A failed before it (the outage is per selection)", async () => {
  const env = loadDetail();
  const a = env.detail.refreshRuns();
  rejectCall(env, 0, new Error("A's outage"));
  await a;
  assert.equal(env.toasts.length, 1); // A's own outage toasts

  switchTo(env, "B");
  const b = env.detail.refreshRuns();
  rejectCall(env, 1, new Error("B's first failure"));
  await b;
  env.restore();

  assert.equal(env.toasts.length, 2); // B's first failure is NOT silenced by A's toast
  assert.equal(env.note.hidden, false);
  assert.equal(env.timers.at(-1), 4000); // the poll keeps its cadence through the outage
});

test("repeated failures in one outage toast once; recovery then a new failure toasts again", async () => {
  const env = loadDetail();
  const first = env.detail.refreshRuns();
  rejectCall(env, 0, new Error("first failure"));
  await first;
  assert.equal(env.toasts.length, 1);

  const second = env.detail.refreshRuns();
  rejectCall(env, 1, new Error("second failure"));
  await second;
  assert.equal(env.toasts.length, 1); // the same outage: silent, still stale

  const healed = env.detail.refreshRuns();
  resolveCall(env, 2, RUNS_ANSWER); // the same runs: no reload, the marker clears
  await healed;
  assert.equal(env.note.hidden, true); // recovered

  const again = env.detail.refreshRuns();
  rejectCall(env, 3, new Error("a new outage"));
  await again;
  env.restore();

  assert.equal(env.toasts.length, 2); // the new outage toasts again
  assert.equal(env.note.hidden, false);
});
