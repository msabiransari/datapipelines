// #876 G6 — static/js/dependencies/source.js, the one client of G4's eight routes: the request built from the pane's
// selector (brief §A.6: artifact families name + version, a schedule id only, a datasource name only, no request
// without a version), one same-origin no-store fetch per load, the §11.1 error table mapped over G4's own served
// error goldens, the schema_version gate on both the envelope and data, and A26's generation guard (a superseded
// response is never rendered).

import test from "node:test";
import assert from "node:assert/strict";
import fs from "node:fs";
import path from "node:path";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const source = require(path.resolve(here, "../../main/resources/static/js/dependencies/source.js"));
const GOLDENS = path.resolve(here, "../resources/dependencies/goldens");
const read = name => JSON.parse(fs.readFileSync(path.join(GOLDENS, name), "utf8"));
const goldenNames = () => fs.readdirSync(GOLDENS).filter(name => name.endsWith(".json"));

function response(status, body, headers = {}) {
  const lower = Object.fromEntries(Object.entries({ "content-type": "application/json", ...headers }).map(([k, v]) => [k.toLowerCase(), v]));
  return {
    ok: status >= 200 && status < 300,
    status,
    headers: { get: name => (name.toLowerCase() in lower ? lower[name.toLowerCase()] : null) },
    json: async () => (typeof body === "string" ? JSON.parse(body) : body),
  };
}
const answering = (...args) => {
  const calls = [];
  const fetchImpl = async (url, init) => {
    calls.push({ url, init });
    return response(...args);
  };
  return { calls, fetchImpl };
};

test("the selector table: artifact families send name + version, a schedule id only, a datasource name only", () => {
  for (const family of ["pipeline", "template", "parameter_set", "visualization", "dashboard", "api_endpoint"]) {
    assert.equal(source.requestUrl({ family, name: "acme/a b", version: "3", id: "ignored" }), `/api/v1/dependencies/${family}?name=acme%2Fa+b&version=3`, family);
    assert.equal(source.requestUrl({ family, name: "acme/x", version: "" }), null, `${family}: no version, no request`);
    assert.equal(source.requestUrl({ family, name: "acme/x" }), null, family);
    assert.equal(source.requestUrl({ family, name: "", version: "1" }), null, family);
    for (const bad of ["0", "01", "-1", "1.5", "abc", "12345678901"]) assert.equal(source.requestUrl({ family, name: "acme/x", version: bad }), null, `${family} ${bad}`);
  }
  assert.equal(source.requestUrl({ family: "schedule", id: "00000000-0000-4000-8000-000000000001", name: "n", version: "2" }), "/api/v1/dependencies/schedule?id=00000000-0000-4000-8000-000000000001");
  assert.equal(source.requestUrl({ family: "schedule", name: "n" }), null);
  assert.equal(source.requestUrl({ family: "datasource", name: "warehouse", version: "4", id: "x" }), "/api/v1/dependencies/datasource?name=warehouse");
  assert.equal(source.requestUrl({ family: "datasource" }), null);
  assert.equal(source.requestUrl({ family: "folder", name: "x", version: "1" }), null, "an unknown family builds nothing");
  assert.equal(source.requestUrl(null), null);
});

test("a pane with no version sends zero requests and is the choose-a-version state", async () => {
  const { calls, fetchImpl } = answering(200, read("tree-pipeline.json"));
  assert.deepEqual(await source.load({ family: "pipeline", name: "acme/x", version: "" }, undefined, fetchImpl), { state: "no-version" });
  assert.equal(calls.length, 0);
});

test("one same-origin, no-store, JSON fetch; a served tree golden is ready with its data", async () => {
  const { calls, fetchImpl } = answering(200, read("tree-pipeline.json"));
  const result = await source.load({ family: "pipeline", name: "test/tree875/golden/source", version: "1" }, undefined, fetchImpl);
  assert.equal(result.state, "ready");
  assert.equal(result.data.nodes.length, 3);
  assert.equal(calls.length, 1);
  assert.equal(calls[0].init.credentials, "same-origin");
  assert.equal(calls[0].init.cache, "no-store");
  assert.equal(calls[0].init.headers.Accept, "application/json");
});

test("schema_version other than 1 — on the envelope or on data — is a server failure (A47 consumer half)", async () => {
  const envelope = read("tree-pipeline.json");
  const selector = { family: "pipeline", name: "test/tree875/golden/source", version: "1" };
  const outer = { ...envelope, schema_version: 2 };
  assert.equal((await source.load(selector, undefined, answering(200, outer).fetchImpl)).state, "server-failure");
  const inner = { ...envelope, data: { ...envelope.data, schema_version: 2 } };
  assert.equal((await source.load(selector, undefined, answering(200, inner).fetchImpl)).state, "server-failure");
  assert.equal((await source.load(selector, undefined, answering(200, envelope, { "content-type": "text/html" }).fetchImpl)).state, "server-failure");
});

test("the §11.1 error table over every served error golden", async () => {
  const selector = { family: "dashboard", name: "acme/board", version: "1" };
  const errors = goldenNames().filter(name => read(name).error);
  assert.ok(errors.length >= 8, `non-vacuity: ${errors.length} error goldens`);
  const statusOf = code => {
    if (code.endsWith(".not_found")) return 404;
    if (code === "dependencies.request.invalid") return 400;
    if (code === "auth.unauthenticated" || code.startsWith("auth.")) return 401;
    return 503;
  };
  const expected = code => {
    if (code.endsWith(".not_found")) return "not-available";
    if (code === "dependencies.metadata.unavailable" || code === "navigation.view.unavailable") return "try-again";
    return "server-failure";
  };
  const original = console.error;
  const logged = [];
  console.error = message => logged.push(message);
  try {
    for (const name of errors) {
      const body = read(name);
      const result = await source.load(selector, undefined, answering(statusOf(body.error.code), body, { "Retry-After": "5" }).fetchImpl);
      assert.equal(result.state, expected(body.error.code), `${name} (${body.error.code})`);
      if (result.state === "try-again") assert.equal(result.retryAfter, 5, name);
    }
  } finally {
    console.error = original;
  }
  const refusals = errors.filter(name => read(name).error.code === "dependencies.request.invalid").length;
  assert.ok(refusals >= 1 && logged.length === refusals, "each 400 logs one console error naming the route only");
  assert.ok(logged.every(message => !message.includes("acme")), "the logged line never carries the query values");
});

test("navigation.view.unavailable (the lens) is try-again too; Retry-After is honoured, defaulting to 5", async () => {
  const selector = { family: "pipeline", name: "acme/x", version: "1" };
  const lens = { error: { code: "navigation.view.unavailable", message: "m" } };
  assert.deepEqual(await source.load(selector, undefined, answering(503, lens, { "Retry-After": "12" }).fetchImpl), { state: "try-again", retryAfter: 12 });
  assert.deepEqual(await source.load(selector, undefined, answering(503, lens).fetchImpl), { state: "try-again", retryAfter: 5 });
  assert.equal((await source.load(selector, undefined, answering(500, "{}").fetchImpl)).state, "server-failure");
  const network = async () => { throw new TypeError("Failed to fetch"); };
  assert.equal((await source.load(selector, undefined, network)).state, "server-failure");
});

test("A26: a superseded load resolves null and is never rendered; the current one resolves", async () => {
  const pending = [];
  const fetchImpl = (url, init) => new Promise((resolve, reject) => {
    pending.push({ url, resolve, reject });
    init.signal?.addEventListener("abort", () => reject(Object.assign(new Error("aborted"), { name: "AbortError" })));
  });
  const loader = source.createLoader(fetchImpl);
  const first = loader.request({ family: "pipeline", name: "acme/x", version: "1" });
  const second = loader.request({ family: "pipeline", name: "acme/x", version: "2" });
  await Promise.resolve();
  pending[1].resolve(response(200, read("tree-pipeline.json")));
  pending[0].resolve(response(200, read("tree-pipeline-writer.json"))); // the late, stale answer
  assert.equal(await first, null, "the superseded v1 answer is dropped");
  const current = await second;
  assert.equal(current.state, "ready");
  assert.equal(current.data.nodes[0].name, "test/tree875/golden/source");
  assert.ok(pending[1].url.endsWith("version=2"));
});

test("A26 without an abort signal: the generation alone drops a late answer", async () => {
  const pending = [];
  const fetchImpl = url => new Promise(resolve => pending.push({ url, resolve }));
  const loader = source.createLoader(fetchImpl);
  const first = loader.request({ family: "pipeline", name: "acme/x", version: "1" });
  await Promise.resolve();
  const second = loader.request({ family: "pipeline", name: "acme/x", version: "2" });
  await Promise.resolve();
  pending[0].resolve(response(200, read("tree-pipeline-writer.json")));
  pending[1].resolve(response(200, read("tree-pipeline.json")));
  assert.equal(await first, null);
  assert.equal((await second).state, "ready");
  loader.abort();
  const third = loader.request({ family: "pipeline", name: "acme/x", version: "3" });
  await Promise.resolve();
  loader.abort();
  pending[2].resolve(response(200, read("tree-pipeline.json")));
  assert.equal(await third, null, "abort() drops the pending load");
});
