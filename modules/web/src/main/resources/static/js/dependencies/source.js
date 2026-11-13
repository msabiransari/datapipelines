(function () {
  "use strict";

  /*
   * dependencies/source.js (#876 G6) — the one client of G4's eight session routes
   * `GET /api/v1/dependencies/<family>` (record §11.1, erratum (8)): the request built from a pane's selector and
   * nothing else, ONE same-origin fetch per load (tree/rest-source.mjs's shape: credentials same-origin, no-store,
   * Accept JSON, content-type checked), and the §11.1 error table mapped onto the explicit states of §13:
   *
   *   200 + data.schema_version 1 → "ready"        any other schema_version → "server-failure" (A47 consumer half)
   *   404 (the family's own code)  → "not-available" — one state for a missing and a denied root (§12, no oracle)
   *   503 dependencies.metadata.unavailable | navigation.view.unavailable → "try-again" (Retry-After honoured)
   *   503 dependencies.response.too_large, any other 5xx → "server-failure"
   *   400 → "server-failure" + a console error: the page built a bad request (a client defect, never shown as such)
   *
   * A26: createLoader() gives every load its own AbortController and a GENERATION; a response whose generation is no
   * longer current resolves null and is never rendered (tree/state.mjs's live() guard). The selector table (brief
   * §A.6): artifact families send `name` + `version`, a schedule sends `id` only, a datasource `name` only; an
   * artifact selector without a version is the choose-a-version state and builds NO request.
   */

  var ARTIFACT = ["pipeline", "template", "parameter_set", "visualization", "dashboard", "api_endpoint"];
  var RETRYABLE = ["dependencies.metadata.unavailable", "navigation.view.unavailable"];

  /** The route URL for a pane selector `{ family, name, version, id }`, or null when no request may be sent. */
  function requestUrl(selector) {
    if (!selector || typeof selector.family !== "string") return null;
    var family = selector.family;
    var params = new URLSearchParams();
    if (ARTIFACT.indexOf(family) !== -1) {
      if (!selector.name || !/^[1-9][0-9]{0,9}$/.test(String(selector.version || ""))) return null;
      params.set("name", selector.name);
      params.set("version", String(selector.version));
    } else if (family === "schedule") {
      if (!selector.id) return null;
      params.set("id", selector.id);
    } else if (family === "datasource") {
      if (!selector.name) return null;
      params.set("name", selector.name);
    } else {
      return null;
    }
    return "/api/v1/dependencies/" + family + "?" + params.toString();
  }

  function retryAfterOf(response) {
    var raw = response.headers && response.headers.get ? response.headers.get("retry-after") : null;
    var n = raw !== null && /^\d+$/.test(String(raw).trim()) ? parseInt(raw, 10) : null;
    return n !== null && n > 0 ? n : 5;
  }

  async function errorCodeOf(response) {
    try {
      var body = await response.json();
      return body && body.error && typeof body.error.code === "string" ? body.error.code : null;
    } catch (_) {
      return null;
    }
  }

  /** One load: resolves `{ state, data?, retryAfter? }`; an abort rejects with the AbortError. */
  async function load(selector, signal, fetchImpl) {
    var url = requestUrl(selector);
    if (url === null) return { state: "no-version" };
    var doFetch = fetchImpl || (typeof fetch === "function" ? fetch : null);
    var response;
    try {
      response = await doFetch(url, {
        signal: signal,
        credentials: "same-origin",
        cache: "no-store",
        headers: { Accept: "application/json" },
      });
    } catch (error) {
      if (error && error.name === "AbortError") throw error;
      return { state: "server-failure" };
    }
    var json = (response.headers && response.headers.get && response.headers.get("content-type")) || "";
    if (response.ok) {
      if (json.indexOf("application/json") === -1) return { state: "server-failure" };
      var envelope;
      try {
        envelope = await response.json();
      } catch (_) {
        return { state: "server-failure" };
      }
      var data = envelope ? envelope.data : null;
      if (!envelope || envelope.schema_version !== 1 || !data || data.schema_version !== 1) return { state: "server-failure" };
      return { state: "ready", data: data };
    }
    if (response.status === 404) return { state: "not-available" };
    if (response.status === 400) {
      if (typeof console !== "undefined" && console.error) {
        console.error("Dependencies request refused as invalid (a client defect): " + url.split("?")[0]);
      }
      return { state: "server-failure" };
    }
    if (response.status === 503) {
      var code = await errorCodeOf(response);
      if (RETRYABLE.indexOf(code) !== -1) return { state: "try-again", retryAfter: retryAfterOf(response) };
    }
    return { state: "server-failure" };
  }

  /**
   * A per-pane loader: `request(selector)` aborts the pending load and starts a new one; it resolves the result, or
   * null when the load was superseded or aborted (the caller renders nothing for null). `abort()` drops everything.
   */
  function createLoader(fetchImpl) {
    var generation = 0;
    var controller = null;
    function abort() {
      generation++;
      if (controller) controller.abort();
      controller = null;
    }
    async function request(selector) {
      abort();
      var mine = generation;
      var own = typeof AbortController === "function" ? new AbortController() : null;
      controller = own;
      var live = function () {
        return mine === generation;
      };
      try {
        var result = await load(selector, own ? own.signal : undefined, fetchImpl);
        return live() ? result : null;
      } catch (error) {
        if (error && error.name === "AbortError") return null;
        return live() ? { state: "server-failure" } : null;
      }
    }
    return { request: request, abort: abort };
  }

  var api = { requestUrl: requestUrl, load: load, createLoader: createLoader };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  if (typeof window !== "undefined") window.DependencySource = api;
})();
