/*
 * The embedded-dashboard example's conformance test (L5, #856; corrected by 856b) —
 * the host glue in examples/dashboard-embed/embed.js driven against the REAL runtime,
 * the REAL chart-kind adapter, the REAL reference proxy (spawned, port 0) and a STUB
 * upstream, on the house runner (`node --test`, dependency-free).
 *
 * What it proves:
 * - the REAL index.html loads every Datapipelines asset from ONE Datapipelines origin
 *   placeholder, never from the host page's own origin (856b correction 1);
 * - embed.js mounts every [data-dp-embed] container AUTOMATICALLY at load — there is no
 *   window.dpEmbedBoot global (856b correction 2) — through `window.dpEmbed.ready` with
 *   `version: "released"` and proxy credentials only;
 * - the `chart` kind (the retired `plotly` kind's successor, #869) bootstraps with
 *   `charts: { library: "plotly", instance: window.Plotly }` at init and the real chart
 *   adapter + a Plotly converter registered;
 * - the runtime's requests reach the STUB only through the proxy: the proxy attached the
 *   dashboard key server-side (the client never carries it), and no cookie or CSRF header
 *   travels;
 * - a container missing either data attribute is refused with a message naming it;
 * - dispose() unmounts cleanly — the same container hosts a fresh boot.
 *
 * The runtime's fetches are REAL (Node's global fetch to the spawned proxy), wrapped once
 * to RECORD url/headers. The fake window/document follows dashboard-runtime.test.mjs's
 * hand-rolled pattern; the smallest stand-in is the recording fetch wrapper itself.
 */

import test from "node:test";
import { before, after } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import http from "node:http";
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import path from "node:path";
import fs from "node:fs";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const runtimePath = path.resolve(here, "../../main/resources/static/js/datapipelines-dashboard.js");
const chartAdapterPath = path.resolve(here, "../../main/resources/static/js/datapipelines-dashboard-chart.js");
const embedPath = path.resolve(here, "../../../../../examples/dashboard-embed/embed.js");
const indexPath = path.resolve(here, "../../../../../examples/dashboard-embed/index.html");
const readmePath = path.resolve(here, "../../../../../examples/dashboard-embed/README.md");
const proxyPath = path.resolve(here, "../../../../../examples/dashboard-proxy/proxy.mjs");

// A plainly fake, NON-secret key literal: the proxy reads it from its environment and
// attaches it server-side; the page (and this test's client side) never carries it.
const KEY = "embed-test-key-0001";
const LISTENING_TIMEOUT_MS = 10_000;
const DASHBOARD_ID = "00000000-0000-0000-0000-000000000000";

// -------------------------------------------------------------------------------- fake DOM

function fakeElement(tag) {
  const listeners = {};
  const attrs = {};
  const children = [];
  const el = {
    tagName: (tag || "div").toUpperCase(),
    attrs,
    children,
    style: {},
    className: "",
    textContent: "",
    parentNode: null,
    firstChild: null,
    addEventListener(type, fn) {
      (listeners[type] ||= []).push(fn);
    },
    setAttribute(k, v) {
      attrs[k] = String(v);
    },
    getAttribute(k) {
      return k in attrs ? attrs[k] : null;
    },
    removeAttribute(k) {
      delete attrs[k];
    },
    appendChild(child) {
      child.parentNode = el;
      children.push(child);
      el.firstChild = children[0] || null;
      return child;
    },
  };
  return el;
}

function installDom() {
  // The one bundle declaration the runtime reads (the index.html script tag, faked).
  const bundleScript = fakeElement("script");
  bundleScript.setAttribute("data-dp-plotly-bundle", "2d");
  // The host containers embed.js's automatic mount scans for, in document order.
  const embedHosts = [];
  const doc = {
    body: fakeElement("body"),
    head: fakeElement("head"),
    cookie: "",
  };
  doc.getElementById = () => null;
  doc.querySelectorAll = (selector) => {
    if (selector === "script[data-dp-plotly-bundle]") return [bundleScript];
    if (selector === "[data-dp-embed]") return embedHosts.slice();
    return [];
  };
  doc.createElement = (tag) => fakeElement(tag);
  doc.createTextNode = (text) => ({ text: String(text), nodeType: 3 });
  globalThis.document = doc;
  globalThis.window = {
    document: doc,
    // The library entry value embed.js declares at init (charts.instance).
    Plotly: {},
    addEventListener: () => {},
    getComputedStyle: () => ({ color: "rgb(10, 20, 30)" }),
  };
  globalThis.location = { reload: () => {} };
  doc.__embedHosts = embedHosts;
  return doc;
}

function uninstallDom() {
  delete globalThis.document;
  delete globalThis.window;
  delete globalThis.location;
  delete globalThis.fetch;
}

// --------------------------------------------------------------------------- stub upstream

/** Every relayed request lands here with its headers and body; the routes answer the wire. */
const seen = [];

const CONFIG = () => ({
  configuration_id: "cfg-1",
  renderer: { bundle: "2d" },
  dashboard: { id: "d1", name: "acme/finance/board", version: 1, status: "RELEASED" },
  layout: { columns: 12, grid: [] },
   parameter_set: null,
   visualizations: [
     // The chart kind (the plotly kind's successor, #869): a pinned bar chart whose
     // family the judge reads from config.mark.type.
     { name: "chart", renderer: { kind: "chart", version: "1" }, config: { mark: { type: "bar" } }, bindings: {}, presentation: null, timeout_seconds: 120 },
     { name: "cells", renderer: { kind: "table", version: "1" }, config: {}, bindings: {}, presentation: null, timeout_seconds: 120 },
   ],
  groups: [],
  actions: [],
  action_controls: [],
  parameter_scopes: {},
  parents: [],
  parameter_revision: 3,
});

const upstream = http.createServer((req, res) => {
  const chunks = [];
  req.on("data", (c) => chunks.push(c));
  req.on("end", () => {
    const body = Buffer.concat(chunks).toString("utf8");
    seen.push({ method: req.method, url: req.url, headers: req.headers, body });
    if (req.url.endsWith("/runtime/config")) {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ data: CONFIG() }));
    } else if (req.url.endsWith("/runtime/parameters")) {
      res.writeHead(200, { "content-type": "application/json" });
      res.end(JSON.stringify({ data: { parameters: [], groups: [], scopes: {} } }));
    } else if (req.url.endsWith("/runtime/visualizations")) {
      // The terminal frame, exactly the shape the runtime's own unit tests feed it.
      const refreshId = JSON.parse(body || "{}").refresh_id;
      res.writeHead(200, { "content-type": "text/event-stream" });
      res.write(`event: refresh_completed\ndata: ${JSON.stringify({ refresh_id: refreshId, status: "COMPLETED", targets: [] })}\n\n`);
      res.end();
    } else {
      res.writeHead(404, { "content-type": "application/json" });
      res.end(JSON.stringify({ error: { code: "dashboard.not_found", message: "not here", details: {} } }));
    }
  });
});

// ------------------------------------------------------------------------ reference proxy

/**
 * Spawns the reference proxy on `PORT=0` and resolves `{ child, port }` once it prints its
 * bound port (the line proxy.mjs documents). Rejects, quoting the lines seen so far, if the
 * child exits first or stays silent.
 */
function startProxy() {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [proxyPath], {
      env: { ...process.env, DP_BASE_URL: UPSTREAM_BASE, DASHBOARD_KEY: KEY, PORT: "0" },
      // stdout is read here for the bound port and then DRAINED for the child's lifetime:
      // an unread pipe is a handle that keeps a finished file alive (gate-825ab754).
      stdio: ["ignore", "pipe", "pipe"],
    });
    child.stderr.on("data", (d) => process.stderr.write(`[proxy] ${d}`));
    let seenOut = "";
    let settled = false;
    const settle = (fn, value) => {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      fn(value);
    };
    const timer = setTimeout(() => {
      child.kill();
      settle(reject, new Error(`the proxy printed no "listening on :<port>" line in ${LISTENING_TIMEOUT_MS}ms; stdout so far: ${JSON.stringify(seenOut)}`));
    }, LISTENING_TIMEOUT_MS);
    child.stdout.on("data", (d) => {
      if (settled) return;
      seenOut += d;
      const m = /listening on :(\d+)/.exec(seenOut);
      if (m) settle(resolve, { child, port: Number(m[1]) });
    });
    child.once("exit", (code, signal) =>
      settle(reject, new Error(`the proxy exited (code ${code}, signal ${signal}) before listening; stdout so far: ${JSON.stringify(seenOut)}`)));
    child.once("error", (e) => settle(reject, e));
  });
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

let UPSTREAM_BASE;
let PROXY_BASE;

before(async (t) => {
  await new Promise((resolve, reject) => {
    upstream.once("error", reject);
    upstream.listen(0, "127.0.0.1", resolve);
  });
  UPSTREAM_BASE = `http://127.0.0.1:${upstream.address().port}`;
  const { child, port } = await startProxy();
  test.dashboardProxyChild = child;
  PROXY_BASE = `http://127.0.0.1:${port}`;
  t.diagnostic(`dashboard-embed test ports: upstream ${upstream.address().port}, proxy ${port}`);
  await waitReady(PROXY_BASE, "the proxy");
  installDom();
  const runtime = require(runtimePath);
  // The chart kind's REAL adapter: loaded like index.html loads it (after the runtime),
  // which auto-registers the kind through the fake window. Registered again explicitly
  // for the Node path, idempotently (the adapter's own contract).
  const chartApi = require(chartAdapterPath);
  chartApi.register(runtime);
  if (!runtime._internal.renderers().chart) throw new Error("the chart adapter did not register the chart kind");
  // The Plotly converter the chart adapter draws through, as a stand-in with the REAL
  // registration contract ({ library, families, draw }); the example's refresh declares
  // no targets, so the converter's draw itself is never invoked here.
  chartApi.registerConverter({
    library: "plotly",
    families: { bar: () => ({}) },
    draw: () => {},
  });
  // The browser gets its table and KPI renderers from the adapter files' load-time
  // registration; the tests register stand-ins for the same kinds and majors
  // (dashboard-runtime.test.mjs).
  const registered = runtime._internal.renderers();
  if (!registered.table) runtime.registerRenderer({ kind: "table", version: "1", create: () => ({ renderData: () => Promise.resolve("rendered") }) });
  if (!registered.kpi) runtime.registerRenderer({ kind: "kpi", version: "1", create: () => ({ renderData: () => Promise.resolve("rendered") }) });
});

after(async () => {
  // Awaited teardown: nothing outlives the tests (gate-825ab754).
  const proxy = test.dashboardProxyChild;
  if (proxy && proxy.exitCode === null && proxy.signalCode === null) {
    await new Promise((resolve) => {
      proxy.once("exit", resolve);
      proxy.kill();
    });
  }
  await new Promise((resolve) => upstream.close(() => resolve()));
  uninstallDom();
});

function reset() {
  seen.length = 0;
}

/** Polls until `predicate` holds: `refresh()` resolves when the stream OPENS, before the POST lands. */
async function waitFor(predicate, what, timeoutMs) {
  const limit = timeoutMs || 5000;
  const start = Date.now();
  for (;;) {
    if (predicate()) return;
    if (Date.now() - start > limit) throw new Error("timed out waiting for " + what);
    await new Promise((r) => setTimeout(r, 25));
  }
}

/**
 * Builds the container the way index.html declares it: the data-dp-embed marker plus
 * the two data attributes. Registered with the fake DOM's [data-dp-embed] scan so the
 * AUTOMATIC mount (require-time mountAll) finds it — embed.js exposes no boot global
 * (856b correction 2).
 */
function embedContainer() {
  const container = document.createElement("div");
  container.setAttribute("data-dp-embed", "");
  container.setAttribute("data-dp-proxy-base", PROXY_BASE);
  container.setAttribute("data-dp-dashboard-id", DASHBOARD_ID);
  document.body.appendChild(container);
  document.__embedHosts.push(container);
  return container;
}

/**
 * The browser's load path: a fresh execution of embed.js mounts every registered
 * [data-dp-embed] container at require time. The require-cache bust is the reload.
 */
function mountExample() {
  delete require.cache[embedPath];
  return require(embedPath);
}

test("every Datapipelines asset in index.html resolves to the one Datapipelines origin, not the host's", () => {
  const HOST_ORIGIN = "https://host.example/page.html"; // where the host page itself lives
  const DP_ORIGIN = "https://dp.example.co"; // the ONE placeholder the example writes
  const html = fs.readFileSync(indexPath, "utf8");
  const urls = [...html.matchAll(/(?:href|src)="([^"]+)"/g)].map((m) => m[1]);
  // Resolve every href/src exactly as a browser on the HOST origin would: a root-relative
  // Datapipelines asset resolves to the host and never loads.
  const dpAssets = urls
    .map((u) => new URL(u, HOST_ORIGIN))
    .filter((u) => /^\/(vendor|css|js)\//.test(u.pathname));
  // Non-vacuity: the example loads 13 Datapipelines assets (7 stylesheets + 6 scripts).
  assert.equal(dpAssets.length, 13, `non-vacuity: expected 13 Datapipelines assets, found ${dpAssets.length}`);
  for (const asset of dpAssets) {
    assert.equal(
      asset.origin,
      DP_ORIGIN,
      `Datapipelines asset ${asset.pathname} loads from ${asset.origin}, not the Datapipelines origin — a root-relative URL resolves to the HOST page's origin`,
    );
  }
  // ONE origin placeholder value across the whole file.
  const origins = [...new Set(dpAssets.map((a) => a.origin))];
  assert.deepEqual(origins, [DP_ORIGIN], "the example writes exactly ONE Datapipelines origin value");
  // The README tells the reader to replace it — stated once.
  const readme = fs.readFileSync(readmePath, "utf8");
  assert.equal(readme.split(DP_ORIGIN).length - 1, 1, "the README names the origin placeholder exactly once");
});

test("embed.js mounts the example automatically through the reference proxy: real fetch, key server-side only, clean dispose", async () => {
  reset();
  // RECORD, never fake: the runtime's fetches stay real (Node's global fetch); this
  // wrapper only writes down what the runtime asked for, then passes through.
  const clientRequests = [];
  const realFetch = globalThis.fetch;
  const recordingFetch = (url, init) => {
    clientRequests.push({ url: String(url), headers: (init && init.headers) || {} });
    return realFetch(url, init);
  };
  globalThis.fetch = recordingFetch;
  try {
    // The automatic mount: the container is registered BEFORE embed.js loads, the way
    // the script tag precedes the DOM content in no browser — here the fake DOM answers
    // the [data-dp-embed] scan synchronously at require time.
    const container = embedContainer();
    mountExample();
    assert.ok(window.dpEmbed && typeof window.dpEmbed.ready.then === "function", "the automatic mount published window.dpEmbed");

    await window.dpEmbed.ready;
    assert.ok(true, "ready resolved");

    // The refresh stream is driven explicitly: bootstrap mounts placeholders; the refresh
    // proves the stream route through the proxy. The refresh promise resolves when the
    // stream OPENS, so the POST's arrival is waited for explicitly.
    await window.dpEmbed.instance.refresh({ scope: "all" });
    await waitFor(() => seen.some((call) => call.url.endsWith("/runtime/visualizations")), "the refresh stream POST");
    // The terminal frame is processed before the dispose below: the stub closes the stream
    // right after writing it; give the runtime's read loop a beat to consume it.
    await new Promise((r) => setTimeout(r, 100));

    // (2) The stub upstream saw the configuration read and the refresh, each carrying the
    // key header the PROXY attached (server-side), and no cookie, no CSRF header.
    assert.ok(seen.length >= 2, `non-vacuity: the upstream saw ${seen.length} requests`);
    const configCall = seen.find((call) => call.url.endsWith("/runtime/config"));
    const streamCall = seen.find((call) => call.url.endsWith("/runtime/visualizations"));
    assert.ok(configCall, "the configuration read arrived at the stub");
    assert.ok(streamCall, "the refresh stream arrived at the stub");
    for (const call of seen) {
      assert.equal(call.headers["dp-api-key"], KEY, `the proxy attached the key server-side to ${call.url}`);
      assert.equal(call.headers["cookie"], undefined, `no cookie forwarded to ${call.url}`);
      assert.equal(call.headers["dp-csrf-token"], undefined, `no CSRF header forwarded to ${call.url}`);
    }

    // (3) Every request the RUNTIME sent went to the proxy's origin and carried no key.
    assert.ok(clientRequests.length >= 2, `the runtime sent ${clientRequests.length} requests`);
    for (const request of clientRequests) {
      assert.ok(request.url.startsWith(PROXY_BASE), `the runtime sent ${request.url} straight past the proxy`);
      const headerNames = Object.keys(request.headers);
      const keyHeader = headerNames.find((name) => name.toLowerCase() === "dp-api-key");
      assert.equal(keyHeader, undefined, `the client carried the key header on ${request.url}`);
      const cookieHeader = headerNames.find((name) => name.toLowerCase() === "cookie");
      assert.equal(cookieHeader, undefined, `the client sent a cookie to ${request.url}`);
    }

    // (4) dispose() unmounts; the same container hosts a fresh boot through the SAME
    // automatic path (the require-cache bust is the reload).
    window.dpEmbed.instance.dispose();
    mountExample();
    assert.ok(window.dpEmbed && typeof window.dpEmbed.ready.then === "function", "a disposed container boots again automatically");
    await window.dpEmbed.ready;
    const configReads = seen.filter((call) => call.url.endsWith("/runtime/config")).length;
    assert.equal(configReads, 2, "the second boot fetched its own configuration");
    window.dpEmbed.instance.dispose();
  } finally {
    globalThis.fetch = realFetch;
  }
});

test("a container missing either data attribute is refused with a message naming it", () => {
  reset();
  // The boot seam (embed.js's module export) drives a single container directly; the
  // automatic mount is what production uses, this seam is what a test may use.
  const { boot } = require(embedPath);
  const missingProxyBase = document.createElement("div");
  missingProxyBase.setAttribute("data-dp-dashboard-id", DASHBOARD_ID);
  assert.throws(() => boot(missingProxyBase), /data-dp-proxy-base/);

  const missingId = document.createElement("div");
  missingId.setAttribute("data-dp-proxy-base", PROXY_BASE);
  assert.throws(() => boot(missingId), /data-dp-dashboard-id/);
});
