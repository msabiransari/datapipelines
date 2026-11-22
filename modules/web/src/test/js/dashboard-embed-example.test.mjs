/*
 * The embedded-dashboard example's conformance test (L5, #856; corrected by 856b) —
 * the host glue in examples/dashboard-embed/embed.js driven against the REAL runtime,
 * the REAL chart-kind adapter, the REAL reference proxy (spawned, port 0) and a STUB
 * upstream, on the house runner (`node --test`, dependency-free).
 *
 * What it proves:
 * - the REAL index.html loads every Datapipelines asset from ONE Datapipelines origin
 *   placeholder, never from the host page's own origin (856b correction 1), and those assets
 *   are exactly the client library's ONE stylesheet and ONE script (#1064, record §8 A12);
 * - embed.js mounts every [data-dp-embed] container AUTOMATICALLY at load — there is no
 *   window.dpEmbedBoot global (856b correction 2) — through `window.dpEmbed.ready` with
 *   `version: "released"` and proxy credentials only;
 * - the `chart` kind (#869) bootstraps with no chart library declared at init, the real
 *   chart adapter registered over the js-rendered engine — all of it evaluated as ONE script,
 *   the client library's text built here from the same two lists the application joins (the
 *   engine's load order, then the vendor manifest's runtime block; #1064 A.4);
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
import vm from "node:vm";
import { ENGINE_DIR, STATIC_JS, engineFiles } from "./support/js-rendered.mjs";

const require = createRequire(import.meta.url);
const here = path.dirname(fileURLToPath(import.meta.url));
const manifestPath = path.resolve(here, "../../main/resources/static/vendor/design-system/vendor-manifest.json");
const embedPath = path.resolve(here, "../../../../../examples/dashboard-embed/embed.js");
const indexPath = path.resolve(here, "../../../../../examples/dashboard-embed/index.html");
const readmePath = path.resolve(here, "../../../../../examples/dashboard-embed/README.md");
const proxyPath = path.resolve(here, "../../../../../examples/dashboard-proxy/proxy.mjs");

// A plainly fake, NON-secret key literal: the proxy reads it from its environment and
// attaches it server-side; the page (and this test's client side) never carries it.
const KEY = "embed-test-key-0001";
const LISTENING_TIMEOUT_MS = 10_000;
const DASHBOARD_ID = "00000000-0000-0000-0000-000000000000";

// ------------------------------------------------------------------------- client library

/** The runtime's five files after the engine: the vendor manifest's runtime block, paths under static/ (#1064 D2). */
function runtimeFiles() {
  const files = JSON.parse(fs.readFileSync(manifestPath, "utf8"))["datapipelines-dashboard-runtime"].files;
  assert.ok(Array.isArray(files) && files.length > 0, "the manifest's runtime block lists its files");
  return files;
}

/**
 * The client library's script as /js/datapipelines.js answers it: every engine file in load order, then the
 * runtime's five, each followed by one newline (#1064 D2) — built here from the two lists, never from the server.
 */
function libraryText() {
  const engine = engineFiles().map((n) => fs.readFileSync(path.join(ENGINE_DIR, n), "utf8") + "\n");
  const runtime = runtimeFiles().map((f) => fs.readFileSync(path.join(STATIC_JS, "..", f), "utf8") + "\n");
  return engine.join("") + runtime.join("");
}

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
    removeChild(child) {
      const i = children.indexOf(child);
      if (i !== -1) children.splice(i, 1);
      child.parentNode = null;
      el.firstChild = children[0] || null;
      return child;
    },
  };
  return el;
}

function installDom() {
  // The host containers embed.js's automatic mount scans for, in document order.
  const embedHosts = [];
  const doc = {
    body: fakeElement("body"),
    head: fakeElement("head"),
    cookie: "",
  };
  doc.getElementById = () => null;
  doc.querySelectorAll = (selector) => {
    if (selector === "[data-dp-embed]") return embedHosts.slice();
    return [];
  };
  // The chart adapter reaches its document through the host (host.ownerDocument) and
  // creates its svg there; the example's refresh declares no targets, so nothing is drawn.
  doc.createElement = (tag) => Object.assign(fakeElement(tag), { ownerDocument: doc });
  doc.createElementNS = (ns, tag) => Object.assign(fakeElement(tag), { ownerDocument: doc, namespaceURI: ns });
  doc.createTextNode = (text) => ({ text: String(text), nodeType: 3 });
  globalThis.document = doc;
  globalThis.window = {
    document: doc,
    addEventListener: () => {},
    getComputedStyle: () => ({ color: "rgb(10, 20, 30)" }),
  };
  doc.defaultView = globalThis.window;
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
  dashboard: { id: "d1", name: "acme/finance/board", version: 1, status: "RELEASED" },
  layout: { columns: 12, grid: [] },
   parameter_set: null,
   visualizations: [
     // The chart kind (#869): a pinned bar chart whose family the engine reads from
     // config.mark.type.
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
  // The client library, evaluated as ONE classic script the way index.html's one tag runs it:
  // the engine, the runtime (publishing window.DatapipelinesDashboard), the interaction layer
  // and the three adapters, each registering its kind through the fake window at load.
  vm.runInThisContext(libraryText(), { filename: "datapipelines.js" });
  const runtime = window.DatapipelinesDashboard;
  if (!runtime) throw new Error("the client library did not publish window.DatapipelinesDashboard");
  const registered = runtime._internal.renderers();
  for (const kind of ["chart", "table", "kpi"]) {
    if (!registered[kind]) throw new Error(`the client library did not register the ${kind} kind at load`);
  }
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

test("index.html loads ONE stylesheet and ONE script from the one Datapipelines origin, then embed.js", () => {
  const HOST_ORIGIN = "https://host.example/page.html"; // where the host page itself lives
  const DP_ORIGIN = "https://dp.example.co"; // the ONE placeholder the example writes
  const html = fs.readFileSync(indexPath, "utf8");
  const urls = [...html.matchAll(/(?:href|src)="([^"]+)"/g)].map((m) => m[1]);
  // Resolve every href/src exactly as a browser on the HOST origin would: a root-relative
  // Datapipelines asset resolves to the host and never loads.
  const dpAssets = urls
    .map((u) => new URL(u, HOST_ORIGIN))
    .filter((u) => /^\/(vendor|css|js)\//.test(u.pathname));
  for (const asset of dpAssets) {
    assert.equal(
      asset.origin,
      DP_ORIGIN,
      `Datapipelines asset ${asset.pathname} loads from ${asset.origin}, not the Datapipelines origin — a root-relative URL resolves to the HOST page's origin`,
    );
  }
  // The client library (#1064 D8, record §8 A12): the Datapipelines stylesheet links are
  // exactly the one file, and the page's scripts are exactly the library, then the host's glue.
  const sheets = [...html.matchAll(/<link\b[^>]*\brel="stylesheet"[^>]*>/g)].map((m) => /href="([^"]+)"/.exec(m[0])[1]);
  assert.deepEqual(
    sheets.map((u) => new URL(u, HOST_ORIGIN)).filter((u) => u.origin === DP_ORIGIN).map((u) => u.pathname),
    ["/css/datapipelines.css"],
    `index.html's Datapipelines stylesheets are exactly the library's one: ${JSON.stringify(sheets)}`,
  );
  assert.equal(sheets.length, 1, `index.html links exactly one stylesheet: ${JSON.stringify(sheets)}`);
  const scripts = [...html.matchAll(/<script\b[^>]*\bsrc="([^"]+)"/g)].map((m) => new URL(m[1], HOST_ORIGIN));
  assert.deepEqual(
    scripts.map((u) => (u.origin === DP_ORIGIN ? u.href : u.pathname)),
    [DP_ORIGIN + "/js/datapipelines.js", "/embed.js"],
    `index.html's scripts are exactly the library, then embed.js: ${JSON.stringify(scripts.map((u) => u.href))}`,
  );
  // ONE origin placeholder value across the whole file, and nothing else of the Datapipelines origin.
  assert.deepEqual(dpAssets.map((a) => a.pathname), ["/css/datapipelines.css", "/js/datapipelines.js"]);
  assert.equal(/\bcharts\s*:/.test(fs.readFileSync(embedPath, "utf8")), false, "embed.js declares no charts option");
  // The README tells the reader to replace the origin — stated once — and names no source file
  // the library joins: neither the engine's directory nor a runtime file.
  const readme = fs.readFileSync(readmePath, "utf8");
  assert.equal(readme.split(DP_ORIGIN).length - 1, 1, "the README names the origin placeholder exactly once");
  assert.equal(readme.includes("js-rendered/"), false, "the README names no engine file path");
  for (const file of runtimeFiles()) {
    const name = path.basename(file);
    assert.equal(readme.includes(name), false, `the README names the runtime source file ${name}`);
  }
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
