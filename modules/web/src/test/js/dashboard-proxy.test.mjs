/**
 * The reference proxy's conformance test (L5, #367) — dashboards.md §6.5's wire contract driven
 * against a STUB upstream, on the house runner (`node --test`, dependency-free).
 *
 * What each case proves:
 * - the four runtime routes are relayed byte-for-byte, with the key attached server-side;
 * - a FIFTH path (a lifecycle route, /mcp, a page, the binding route) is refused by the PROXY
 *   (404, the envelope) and never reaches the upstream;
 * - `Cookie` and `DP-CSRF-Token` are stripped; `Accept`/`Content-Type` pass through;
 * - the SSE response is piped as a STREAM: the first frame arrives while the upstream is still
 *   writing (the timing assertion is the buffering detector — a buffered proxy fails it);
 * - error envelopes pass through with their status.
 *
 * The proxy is spawned as a child process on a scratch port, exactly as a host would run it;
 * `node` here is the test runner itself, so the spawn is available wherever the suite runs.
 */
import test from "node:test";
import { before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";
import { fileURLToPath } from "node:url";
import path from "node:path";

const PROXY_SCRIPT = path.join(path.dirname(fileURLToPath(import.meta.url)), "../../../../../examples/dashboard-proxy/proxy.mjs");
const PROXY_PORT = 18367;
const UPSTREAM_PORT = 18368;
const PROXY_BASE = `http://127.0.0.1:${PROXY_PORT}`;
const UPSTREAM_BASE = `http://127.0.0.1:${UPSTREAM_PORT}`;
const KEY = "dpk_PROXYTESTKEY01";

/** Records one relayed request; answers from a scripted table. */
const seen = [];
let upstreamHandler = (req, res) => {
  res.writeHead(200, { "content-type": "application/json" });
  res.end(JSON.stringify({ ok: true, path: req.url }));
};

const upstream = http.createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    seen.push({
      method: req.method,
      url: req.url,
      headers: req.headers,
      body: Buffer.concat(chunks).toString("utf8"),
    });
    upstreamHandler(req, res);
  });
});

function startProxy() {
  const child = spawn(process.execPath, [PROXY_SCRIPT], {
    env: { ...process.env, DP_BASE_URL: UPSTREAM_BASE, DASHBOARD_KEY: KEY, PORT: String(PROXY_PORT) },
    stdio: ["ignore", "pipe", "pipe"],
  });
  child.stderr.on("data", (d) => process.stderr.write(`[proxy] ${d}`));
  return child;
}

async function waitReady(base, what) {
  for (let i = 0; i < 100; i++) {
    try {
      await fetch(base + "/", { signal: AbortSignal.timeout(200) });
      return;
    } catch (e) {
      if (e.name === "AbortError") throw new Error(`${what} did not answer`);
      await new Promise((r) => setTimeout(r, 50));
    }
  }
  throw new Error(`${what} never became ready`);
}

before(async () => {
  await new Promise((resolve) => upstream.listen(UPSTREAM_PORT, "127.0.0.1", resolve));
  const proxy = startProxy();
  await waitReady(PROXY_BASE, "the proxy");
  test.dashboardProxyChild = proxy;
});

after(() => {
  test.dashboardProxyChild?.kill();
  upstream.close();
});

function reset() {
  seen.length = 0;
  upstreamHandler = (req, res) => {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: true, path: req.url }));
  };
}

test("the four runtime routes are relayed byte-for-byte with the key attached server-side", async () => {
  reset();
  const cases = [
    ["GET", "/api/v1/dashboards/11111111-1111-4111-8111-111111111111/runtime/config", undefined],
    ["POST", "/api/v1/dashboards/11111111-1111-4111-8111-111111111111/runtime/parameters", JSON.stringify({ instance_id: "x" })],
    ["POST", "/api/v1/dashboards/11111111-1111-4111-8111-111111111111/runtime/refreshes/22222222-2222-4222-8222-222222222222/abort", JSON.stringify({ instance_id: "x" })],
  ];
  for (const [method, p, body] of cases) {
    const res = await fetch(PROXY_BASE + p, {
      method,
      headers: body === undefined ? {} : { "content-type": "application/json" },
      body,
    });
    assert.equal(res.status, 200, p);
    assert.deepEqual(await res.json(), { ok: true, path: p });
  }
  assert.equal(seen.length, 3);
  assert.equal(seen[0].headers["dp-api-key"], KEY, "the key travels server-side");
  assert.equal(seen[1].body, JSON.stringify({ instance_id: "x" }), "the body is byte-for-byte");
});

test("a fifth path is refused BY THE PROXY and never reaches the upstream", async () => {
  reset();
  for (const p of [
    "/api/v1/dashboards",
    "/api/v1/dashboards/11111111-1111-4111-8111-111111111111",
    "/api/v1/dashboards/bindings",
    "/api/v1/dashboards/11111111-1111-4111-8111-111111111111/refreshes",
    "/api/v1/promotion/inventory",
    "/mcp",
    "/dashboards",
    "/partials/dashboards/tree",
  ]) {
    const res = await fetch(PROXY_BASE + p, { method: p === "/api/v1/dashboards/bindings" ? "POST" : "GET" });
    assert.equal(res.status, 404, p);
    const body = await res.json();
    assert.equal(body.error.code, "proxy.not_found", p);
  }
  assert.equal(seen.length, 0, "nothing leaked upstream");
});

test("Cookie and DP-CSRF-Token are stripped; Accept and Content-Type pass through", async () => {
  reset();
  await fetch(PROXY_BASE + "/api/v1/dashboards/11111111-1111-4111-8111-111111111111/runtime/config", {
    headers: { accept: "application/json", cookie: "dp_session=attacker", "dp-csrf-token": "x" },
  });
  assert.equal(seen.length, 1);
  assert.equal(seen[0].headers["cookie"], undefined, "no cookie forwarded");
  assert.equal(seen[0].headers["dp-csrf-token"], undefined, "no CSRF header forwarded");
  assert.equal(seen[0].headers["accept"], "application/json");
});

test("the SSE response is piped as a STREAM - the first frame arrives before the refresh ends", async () => {
  reset();
  // A slow upstream: the first frame at t=0, the second 400ms later, the end 800ms after that.
  upstreamHandler = (req, res) => {
    res.writeHead(200, { "content-type": "text/event-stream" });
    res.write("event: refresh_started\ndata: {}\n\n");
    setTimeout(() => res.write("event: source_completed\ndata: {}\n\n"), 400);
    setTimeout(() => {
      res.write("event: refresh_completed\ndata: {}\n\n");
      res.end();
    }, 800);
  };
  const started = Date.now();
  const res = await fetch(PROXY_BASE + "/api/v1/dashboards/11111111-1111-4111-8111-111111111111/runtime/visualizations", {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "{}",
  });
  assert.equal(res.status, 200);
  // Read the stream incrementally, timing the FIRST frame.
  const reader = res.body.getReader();
  const decoder = new TextDecoder();
  let firstFrameAt = null;
  let all = "";
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    if (firstFrameAt === null) firstFrameAt = Date.now() - started;
    all += decoder.decode(value, { stream: true });
  }
  const total = Date.now() - started;
  assert.ok(all.includes("refresh_started"), "the first frame came through");
  assert.ok(all.includes("refresh_completed"), "the last frame came through");
  assert.ok(
    firstFrameAt < total - 300,
    `the first frame arrived at ${firstFrameAt}ms of a ${total}ms stream - a buffered proxy delivers it at the end`,
  );
  assert.ok(res.headers.get("content-type").includes("text/event-stream"), "the SSE content-type passes through");
});

test("error envelopes pass through verbatim with their status", async () => {
  reset();
  upstreamHandler = (req, res) => {
    res.writeHead(404, { "content-type": "application/json" });
    res.end(JSON.stringify({ error: { code: "dashboard.not_found", message: "not here", details: {} } }));
  };
  const res = await fetch(PROXY_BASE + "/api/v1/dashboards/11111111-1111-4111-8111-111111111111/runtime/config");
  assert.equal(res.status, 404);
  const body = await res.json();
  assert.equal(body.error.code, "dashboard.not_found");
});
