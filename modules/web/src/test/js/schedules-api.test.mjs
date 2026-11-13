// #336 D8 — a 2xx whose body cannot be read is a failed response, never a null
// payload. The defect: `catch (e) { body = null; }` turned a malformed success
// body into `{ data: null }`, and every caller rendered an empty page that
// looked like "no schedules". Now the request rejects with a readable ApiError
// (fixed copy — the body carries nothing trustworthy).

import test from "node:test";
import assert from "node:assert/strict";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const apiPath = path.resolve(here, "../../main/resources/static/js/schedules/api.js");

delete require.cache[require.resolve(apiPath)];
const { path: _p } = { path };
require(_p.resolve(here, "../../main/resources/static/js/schedules/api.js"));
const api = require(apiPath);

test("a malformed 2xx body rejects with a readable error", async () => {
  globalThis.fetch = () =>
    Promise.resolve({
      ok: true,
      status: 200,
      headers: { get: () => null },
      text: async () => '{"data": not-json',
    });

  await assert.rejects(
    api.runs("11111111-1111-1111-1111-111111111111", 0, 5),
    (err) => err.name === "ApiError" && err.code === "malformed_response",
  );
});

test("an ok 2xx still resolves with the unwrapped data", async () => {
  globalThis.fetch = () =>
    Promise.resolve({
      ok: true,
      status: 200,
      headers: { get: () => null },
      text: async () => JSON.stringify({ data: { items: [1] } }),
    });

  const res = await api.runs("11111111-1111-1111-1111-111111111111", 0, 5);
  assert.deepEqual(res.data, { items: [1] });
});

// #442b — the resend (§20.13) is a POST to the delivery's own path under its run and schedule,
// carrying the CSRF header the shared request builds for every write.
test("resend posts to the delivery's path with the CSRF header", async () => {
  const seen = [];
  globalThis.window = { DpCsrf: { token: () => "csrf-token" } };
  globalThis.fetch = (url, init) => {
    seen.push({ url, init });
    return Promise.resolve({
      ok: true,
      status: 200,
      headers: { get: () => null },
      text: async () => JSON.stringify({ data: { id: "d", state: "pending", warning: "may_duplicate" } }),
    });
  };
  try {
    const res = await api.resend("s/1", "r", "d");
    assert.equal(seen.length, 1);
    assert.equal(seen[0].url, "/api/v1/schedules/s%2F1/runs/r/deliveries/d/resend");
    assert.equal(seen[0].init.method, "POST");
    assert.equal(seen[0].init.headers["DP-CSRF-Token"], "csrf-token");
    assert.equal(res.data.warning, "may_duplicate");
  } finally {
    delete globalThis.window;
  }
});

// #641 — the explorer's filtered read is §20.1 with `condition`, paged through has_more like
// every list; no condition is the unfiltered query it always was.
test("listAll pages §20.1 with the condition word when one is set, and without it otherwise", async () => {
  const seen = [];
  globalThis.fetch = (url) => {
    seen.push(url);
    const second = url.indexOf("offset=1") !== -1;
    return Promise.resolve({
      ok: true,
      status: 200,
      headers: { get: () => null },
      text: async () => JSON.stringify({ data: { items: [{ id: second ? "b" : "a" }], pagination: { has_more: !second } } }),
    });
  };
  const items = await api.listAll("", "blocked");
  assert.deepEqual(items.map((i) => i.id), ["a", "b"]);
  assert.deepEqual(seen, [
    "/api/v1/schedules?offset=0&limit=200&condition=blocked",
    "/api/v1/schedules?offset=1&limit=200&condition=blocked",
  ]);
  seen.length = 0;
  await api.listAll("ops");
  assert.equal(seen[0], "/api/v1/schedules?prefix=ops&offset=0&limit=200");
});

// #725 — the last-run filter rides §20.1 as `outcome`, beside `condition` when both are set.
test("listAll sends the outcome word, alone or with the condition, through every page", async () => {
  const seen = [];
  globalThis.fetch = (url) => {
    seen.push(url);
    const second = url.indexOf("offset=1") !== -1;
    return Promise.resolve({
      ok: true,
      status: 200,
      headers: { get: () => null },
      text: async () => JSON.stringify({ data: { items: [{ id: second ? "b" : "a" }], pagination: { has_more: !second } } }),
    });
  };
  await api.listAll("", "blocked", "failed");
  assert.deepEqual(seen, [
    "/api/v1/schedules?offset=0&limit=200&condition=blocked&outcome=failed",
    "/api/v1/schedules?offset=1&limit=200&condition=blocked&outcome=failed",
  ]);
  seen.length = 0;
  await api.listAll("", "", "never-ran");
  assert.equal(seen[0], "/api/v1/schedules?offset=0&limit=200&outcome=never-ran");
});
