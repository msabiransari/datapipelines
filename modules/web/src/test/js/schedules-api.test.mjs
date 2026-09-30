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
