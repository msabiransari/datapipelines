/*
 * #9 slice 2 — the Schedules page's ONE way to talk to the server: rest-api §20 (and the
 * two reads beside it the page needs — §5's pipeline list and version for the form's picker
 * and parameters, §10.3A's durable execution events for a run's Messages).
 *
 * Every request carries the session cookie (`credentials: same-origin`) and the
 * `DP-CSRF-Token` header read by csrf.js's one reader — the same shape the pipeline editor
 * uses for /api/v1 (the record §9.5). There is no other path: no htmx partial, no form post
 * (the record §6). What comes back is unwrapped here once:
 *
 *   ok   → { status, etag, data }   (data = the §4.1 envelope's `data`; null for a 204)
 *   fail → rejects with an ApiError  { status, code, message, userMessage, details,
 *                                      correlationId, network }
 *
 * A 401 is not an error to render: the session is gone, and the page goes to the login screen
 * exactly as an htmx partial's HX-Redirect would send it (ui-screens §5.1).
 */
(function () {
  "use strict";

  function csrfToken() {
    if (typeof window !== "undefined" && window.DpCsrf) return window.DpCsrf.token();
    return "";
  }

  function ApiError(fields) {
    this.name = "ApiError";
    this.status = fields.status || 0;
    this.code = fields.code || "";
    this.message = fields.message || "";
    this.userMessage = fields.userMessage || fields.message || "";
    this.details = fields.details || {};
    this.correlationId = fields.correlationId || "";
    this.network = !!fields.network;
  }

  /** The §4.2 envelope as an ApiError; a body that is not one still yields a readable error. */
  function errorFrom(status, body) {
    var e = body && body.error;
    if (e) {
      return new ApiError({
        status: status,
        code: e.code,
        message: e.message,
        userMessage: e.user_message,
        details: e.details,
        correlationId: body.correlation_id,
      });
    }
    return new ApiError({ status: status, code: "http." + status, message: "The server answered " + status + "." });
  }

  function networkError(cause) {
    return new ApiError({
      status: 0,
      code: "network",
      message: String((cause && cause.message) || cause || "network failure"),
      userMessage: "The server could not be reached. Check your connection and try again.",
      network: true,
    });
  }

  /**
   * One request. `options`: {body (object → JSON), ifMatch, idempotencyKey}. Resolves
   * {status, etag, data}; rejects ApiError.
   */
  function request(method, path, options) {
    var o = options || {};
    var headers = { Accept: "application/json", "DP-CSRF-Token": csrfToken() };
    var init = { method: method, credentials: "same-origin", headers: headers };
    if (o.body !== undefined) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(o.body);
    }
    if (o.ifMatch) headers["If-Match"] = o.ifMatch;
    if (o.idempotencyKey) headers["Idempotency-Key"] = o.idempotencyKey;
    return fetch(path, init).then(
      function (res) {
        if (res.status === 401) {
          window.location.assign("/login?expired=true");
          return new Promise(function () {}); // the page is leaving; nothing downstream runs
        }
        var etag = res.headers.get("ETag");
        if (res.status === 204) return { status: 204, etag: etag, data: null };
        return res.text().then(function (text) {
          var body = null;
          try { body = text ? JSON.parse(text) : null; } catch (e) { body = null; }
          if (!res.ok) throw errorFrom(res.status, body);
          return { status: res.status, etag: etag, data: body ? body.data : null };
        });
      },
      function (cause) { throw networkError(cause); },
    );
  }

  function q(params) {
    var parts = [];
    Object.keys(params).forEach(function (k) {
      var v = params[k];
      if (v === undefined || v === null || v === "") return;
      parts.push(encodeURIComponent(k) + "=" + encodeURIComponent(v));
    });
    return parts.length ? "?" + parts.join("&") : "";
  }

  var BASE = "/api/v1/schedules";

  function id(value) {
    return encodeURIComponent(String(value));
  }

  /**
   * §20.1, every page: the list reads `has_more` until it is false (a subtree is bounded by the
   * per-workspace cap, scheduler.md §3 — 100 by default, so one page at the 200 maximum is the
   * usual answer).
   */
  function listAll(prefix) {
    var items = [];
    function page(offset) {
      return request("GET", BASE + q({ prefix: prefix, offset: offset, limit: 200 })).then(function (r) {
        var data = r.data || {};
        items = items.concat(data.items || []);
        var more = data.pagination && data.pagination.has_more;
        return more ? page(offset + (data.items || []).length) : items;
      });
    }
    return page(0);
  }

  var api = {
    ApiError: ApiError,
    errorFrom: errorFrom,
    request: request,
    listAll: listAll,
    get: function (scheduleId) { return request("GET", BASE + "/" + id(scheduleId)); },
    upcoming: function (scheduleId, count) { return request("GET", BASE + "/" + id(scheduleId) + "/upcoming" + q({ count: count || 5 })); },
    preview: function (cron, timezone, count) { return request("GET", BASE + "/preview" + q({ cron: cron, timezone: timezone, count: count || 5 })); },
    runs: function (scheduleId, offset, limit) { return request("GET", BASE + "/" + id(scheduleId) + "/runs" + q({ offset: offset || 0, limit: limit || 20 })); },
    run: function (scheduleId, runId) { return request("GET", BASE + "/" + id(scheduleId) + "/runs/" + id(runId)); },
    create: function (body, key) { return request("POST", BASE, { body: body, idempotencyKey: key }); },
    update: function (scheduleId, body, etag) { return request("PUT", BASE + "/" + id(scheduleId), { body: body, ifMatch: etag }); },
    remove: function (scheduleId, etag) { return request("DELETE", BASE + "/" + id(scheduleId), { ifMatch: etag }); },
    pause: function (scheduleId) { return request("POST", BASE + "/" + id(scheduleId) + "/pause"); },
    resume: function (scheduleId) { return request("POST", BASE + "/" + id(scheduleId) + "/resume"); },
    unblock: function (scheduleId) { return request("POST", BASE + "/" + id(scheduleId) + "/unblock"); },
    runNow: function (scheduleId, key) { return request("POST", BASE + "/" + id(scheduleId) + "/run", { idempotencyKey: key }); },
    // §5.7 — the picker's search, the caller's own view (the promoter lens applies server-side).
    findPipelines: function (text) { return request("GET", "/api/v1/pipelines" + q({ q: text, limit: 20 })); },
    pipeline: function (pipelineId) { return request("GET", "/api/v1/pipelines/" + id(pipelineId)); },
    pipelineVersion: function (pipelineId, version) { return request("GET", "/api/v1/pipelines/" + id(pipelineId) + "/versions/" + id(version)); },
    // §10.2 — one execution's metadata (its own duration_ms, status); execution.read, R3.
    execution: function (executionId) { return request("GET", "/api/v1/executions/" + id(executionId)); },
    // §10.3A — the durable record, oldest first, paged by event id.
    executionEvents: function (executionId, after) {
      return request("GET", "/api/v1/executions/" + id(executionId) + "/events" + q({ format: "json", after: after || 0, limit: 500 }));
    },
  };
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") window.DpSchedulesApi = api;
})();
