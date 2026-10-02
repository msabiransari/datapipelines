#!/usr/bin/env node
/**
 * The dashboard reference proxy (L5, #367) — the wire contract of dashboards.md §6.5, in one
 * dependency-free Node script (`http` + `fetch`, Node >= 18). It is an EXAMPLE, not a product:
 * a host application's backend copies it, sets its own env, and fronts it with its own auth.
 *
 * ## What it does (the whole contract)
 *
 * - EXACTLY FOUR paths are relayed, byte-for-byte, under the same shapes:
 *     GET  {DP}/api/v1/dashboards/{id}/runtime/config
 *     POST {DP}/api/v1/dashboards/{id}/runtime/parameters
 *     POST {DP}/api/v1/dashboards/{id}/runtime/visualizations   (SSE, piped as a STREAM)
 *     POST {DP}/api/v1/dashboards/{id}/runtime/refreshes/{rid}/abort
 * - Every OTHER path — the lifecycle REST routes, the pages, /partials, /mcp, a published
 *   endpoint, /api/v1/dashboards/bindings, the refreshes reads — is refused BY THE PROXY (404,
 *   the §4 envelope). A fifth path must never reach Datapipelines.
 * - The key never reaches the browser: `DASHBOARD_KEY` is read from the proxy's ENV and sent as
 *   `DP-API-Key` server-side. `Cookie` and `DP-CSRF-Token` are STRIPPED from the request (the
 *   browser offers the proxy nothing, and a cookie must not choose a second identity here);
 *   only `Accept`, `Content-Type` and `Last-Event-ID` are forwarded.
 * - Error envelopes pass through VERBATIM with their status, so the client runtime's states
 *   work unchanged in proxy mode (`credentials: "omit"`, no CSRF header).
 * - One key = one budget (auth.md §7.7): the SSE stream cap and the rate limit are keyed on
 *   the key's identity, shared by every end user behind this proxy. Size
 *   `datapipelines.sse.max-streams-per-user` for the host's concurrency.
 *
 * ## Config (env)
 *   DP_BASE_URL     the Datapipelines deployment to serve from (e.g. https://dp.example.co)
 *   DASHBOARD_KEY   a `dashboard`-kind API key minted on the Keys page (`dpk_…`)
 *   PORT            optional, default 8080
 */
import http from "node:http";

const DP_BASE_URL = process.env.DP_BASE_URL || "";
const DASHBOARD_KEY = process.env.DASHBOARD_KEY || "";
const PORT = Number(process.env.PORT || 8080);

if (!DP_BASE_URL || !DASHBOARD_KEY) {
  console.error("dashboard-proxy: DP_BASE_URL and DASHBOARD_KEY are both required (see the README)");
  process.exit(2);
}

/** The relayed routes: method + regex. Byte-for-byte bodies, nothing rewritten. */
const RUNTIME_ROUTES = [
  { method: "GET", pattern: /^\/api\/v1\/dashboards\/([^/]+)\/runtime\/config$/ },
  { method: "POST", pattern: /^\/api\/v1\/dashboards\/([^/]+)\/runtime\/parameters$/ },
  { method: "POST", pattern: /^\/api\/v1\/dashboards\/([^/]+)\/runtime\/visualizations$/ },
  { method: "POST", pattern: /^\/api\/v1\/dashboards\/([^/]+)\/runtime\/refreshes\/([^/]+)\/abort$/ },
];

/** Headers the browser MAY send through; everything else (cookies above all) is dropped. */
const FORWARDED_HEADERS = ["accept", "content-type", "last-event-id"];

const server = http.createServer(async (req, res) => {
  const path = (req.url || "").split("?")[0];
  const route = RUNTIME_ROUTES.find((r) => r.method === req.method && r.pattern.test(path));
  if (!route) {
    // A fifth path is refused HERE — the proxy is the fence, not the app.
    respond(res, 404, {
      error: {
        code: "proxy.not_found",
        message: "The dashboard proxy relays the four runtime routes only.",
        details: { reason: "not_relayed", path },
      },
    });
    return;
  }

  const headers = {};
  for (const name of FORWARDED_HEADERS) {
    const value = req.headers[name];
    if (value !== undefined) headers[name] = value;
  }
  headers["dp-api-key"] = DASHBOARD_KEY;

  const upstream = await fetch(DP_BASE_URL + req.url, {
    method: req.method,
    headers,
    body: req.method === "GET" ? undefined : req,
    duplex: "half",
  });

  // The SSE response is piped as a STREAM — `res.write` per chunk, never buffered: the first
  // event must reach the browser before the refresh ends (the conformance test's timing
  // assertion exists because buffering is the natural way to get this wrong).
  res.writeHead(upstream.status, {
    "content-type": upstream.headers.get("content-type") || "application/json",
    "cache-control": "no-store",
  });
  if (!upstream.body) {
    res.end();
    return;
  }
  const reader = upstream.body.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    res.write(Buffer.from(value));
  }
  res.end();
});

/** The §4 envelope shape, so a refusal is indistinguishable from the app's own refusals. */
function respond(
  res,
  status,
  body,
) {
  res.writeHead(status, { "content-type": "application/json", "cache-control": "no-store" });
  res.end(JSON.stringify(body));
}

server.listen(PORT, () => {
  // The port actually bound: with PORT=0 the OS picks a free one, and this line is where a caller reads it.
  console.log(`dashboard-proxy listening on :${server.address().port} -> ${DP_BASE_URL} (four runtime routes, key in env)`);
});
