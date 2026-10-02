# The dashboard reference proxy (L5)

`proxy.mjs` implements `docs/dashboards.md` §6.5's wire contract, dependency-free (Node >= 18,
`http` + `fetch` — the same toolchain the client runtime and the house Node test runner use; a
second language would add a toolchain the product does not carry).

```
DP_BASE_URL=https://dp.example.co DASHBOARD_KEY=dpk_… PORT=8080 node proxy.mjs
```

- Relays EXACTLY the four runtime routes byte-for-byte (`config`, `parameters`,
  `visualizations` as a STREAM, `refreshes/{id}/abort`); every other path answers the proxy's
  own 404 envelope — the fence is the proxy, not the app.
- Forwards `Accept`, `Content-Type` and `Last-Event-ID` only; strips `Cookie` and
  `DP-CSRF-Token`; attaches the key from `DASHBOARD_KEY` server-side, so the key never
  reaches a browser.
- Passes error envelopes through verbatim with their status.
- One key = one budget: the SSE stream cap (`datapipelines.sse.max-streams-per-user`) and the
  rate limit are keyed on the key's identity, shared by every end user behind this proxy —
  size the cap for the host's concurrency.

Conformance: `modules/web/src/test/js/dashboard-proxy.test.mjs` (a stub upstream proving
streaming, header stripping and the four-route fence) and
`tests/integration-tests/.../DashboardProxyE2eTest.kt` (the script against the running app
with a real key). This is an EXAMPLE — a host copies it behind its own authentication.
