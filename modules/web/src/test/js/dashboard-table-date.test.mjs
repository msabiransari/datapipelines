// The table renderer's `date` format (#1081): a date-only text — what a DATE cell carries,
// `uuuu-MM-dd` — is shown as its own calendar day in every time zone, never the day before west of
// UTC; every other value under `date`, and every value under `datetime`, keeps the bytes the base's
// expression writes. A file of its own: it changes `process.env.TZ`, and the web module's
// `editorJsTest` runs each `*.test.mjs` in a Node process of its own, so no other file sees a zone
// set here.

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const table = require(path.resolve(here, "../../main/resources/static/js/datapipelines-dashboard-table.js"));
const format = table._internal.format;

const ZONES = ["UTC", "America/New_York", "America/Sao_Paulo", "Asia/Karachi", "Pacific/Kiritimati"];
const WEST = new Set(["America/New_York", "America/Sao_Paulo"]);
const DAY_MS = 24 * 60 * 60 * 1000;

// Runs `fn` with the process's zone set to `zone`, restoring the zone it found.
function inZone(zone, fn) {
  const had = Object.prototype.hasOwnProperty.call(process.env, "TZ");
  const before = process.env.TZ;
  process.env.TZ = zone;
  try {
    return fn();
  } finally {
    if (had) process.env.TZ = before;
    else delete process.env.TZ;
  }
}

// The expected text, built without the rule's own steps: the instant of midnight UTC that day,
// written with the zone fixed to UTC and no locale named (the script names none).
const utcDayText = (ms) => new Date(ms).toLocaleDateString(undefined, { timeZone: "UTC" });
const expectedDay = (value) => utcDayText(Date.parse(value + "T00:00:00Z"));
const dayBefore = (value) => utcDayText(Date.parse(value + "T00:00:00Z") - DAY_MS);

// The base's expressions, byte for byte (the code before #1081).
const baseDate = (value) => new Date(value).toLocaleDateString();
const baseDateTime = (value) => new Date(value).toLocaleString();

test("the zone change takes effect: one instant's local day differs between New York and Karachi", () => {
  const instant = "2026-09-01T02:30:00Z";
  const ny = inZone("America/New_York", () => baseDate(instant));
  const khi = inZone("Asia/Karachi", () => baseDate(instant));
  assert.notEqual(ny, khi, "TZ did not change the local day — every zone case below would be vacuous");
  assert.equal(ny, utcDayText(Date.UTC(2026, 7, 31)));
  assert.equal(khi, utcDayText(Date.UTC(2026, 8, 1)));
});

const DATE_ONLY = ["2026-09-01", "2026-01-01", "2026-12-31", "2024-02-29", "0001-01-01", "0099-12-31"];

for (const value of DATE_ONLY) {
  test(`a date-only text ${value} is its own day in every zone, never the day before`, () => {
    const expected = expectedDay(value);
    const before = dayBefore(value);
    assert.notEqual(expected, before, "the two candidate texts must differ for the case to mean anything");
    const seen = {};
    for (const zone of ZONES) {
      seen[zone] = inZone(zone, () => format(value, "date"));
      assert.equal(seen[zone], expected, `${zone}: ${value} under date`);
      assert.notEqual(seen[zone], before, `${zone}: ${value} reads the day before`);
    }
    assert.equal(new Set(Object.values(seen)).size, 1, `one text in every zone: ${JSON.stringify(seen)}`);
  });
}

test("the base's expression loses the day west of UTC for every date-only value (what the rule fixes)", () => {
  for (const value of DATE_ONLY) {
    for (const zone of WEST) {
      assert.equal(inZone(zone, () => baseDate(value)), dayBefore(value), `${zone}: base on ${value}`);
    }
  }
});

// An instant is not a day: it keeps the viewer's local day of it, as the base writes it — the
// server's own six-digit form, the short form, and the instant of midnight UTC alike (D2, D4).
const INSTANTS = [
  "2026-09-01T02:30:00.000000Z",
  "2026-09-01T02:30:00Z",
  "2026-09-01T00:00:00.000000Z",
  "2026-09-01T00:00:00Z",
];

for (const value of INSTANTS) {
  test(`an instant ${value} under date keeps the base's bytes: the local day, the 31st of August west of UTC`, () => {
    for (const zone of ZONES) {
      inZone(zone, () => {
        const got = format(value, "date");
        assert.equal(got, baseDate(value), `${zone}: ${value} keeps the base's text`);
        const day = WEST.has(zone) ? Date.UTC(2026, 7, 31) : Date.UTC(2026, 8, 1);
        assert.equal(got, utcDayText(day), `${zone}: ${value} is the viewer's local day`);
      });
    }
  });
}

// Values outside the exact shape, or naming no real day, take the base's path byte for byte.
const OTHERS = ["2026-02-31", " 2026-09-01", "2026-09-01 ", "2026-9-1", "+002026-09-01", "20260-09-01", "not a date", 1788220800000];

for (const value of OTHERS) {
  test(`${JSON.stringify(value)} under date keeps the base's bytes in every zone`, () => {
    for (const zone of ZONES) {
      inZone(zone, () => assert.equal(format(value, "date"), baseDate(value), `${zone}: ${JSON.stringify(value)}`));
    }
  });
}

test("null, undefined and the empty text under date are the empty cell, as before", () => {
  for (const zone of ZONES) {
    inZone(zone, () => {
      assert.equal(format(null, "date"), "");
      assert.equal(format(undefined, "date"), "");
      assert.equal(format("", "date"), "");
    });
  }
});

test("datetime keeps the base's bytes for a date-only text, an instant and a number (D3)", () => {
  for (const zone of ZONES) {
    inZone(zone, () => {
      for (const value of ["2026-09-01", "2026-09-01T02:30:00.000000Z", "2026-09-01T00:00:00Z", 1788220800000]) {
        assert.equal(format(value, "datetime"), baseDateTime(value), `${zone}: ${JSON.stringify(value)} under datetime`);
      }
    });
  }
});
