/*
 * The Datapipelines dashboard client runtime (#10, the implementation spec's §10.1–§10.3; the design
 * record's §5.3–§5.6). One IIFE, ES2019, no dependencies: script evaluation registers
 * `window.DatapipelinesDashboard` and nothing else — the host calls `init` when its container and
 * adapter are ready.
 *
 * ## What the runtime owns
 * The four runtime routes' client half and NOTHING else: it never renders a chart (the adapters do),
 * never evaluates a parameter (the server does), never trusts a refresh id it did not mint, and never
 * lets a stale event touch the DOM. Host components make no dashboard network calls of their own
 * (record §5.5) — everything goes through here.
 *
 * ## Bootstrap (R22's four-step barrier)
 * validate the adapter and the renderer/bundle pair → fetch the configuration → mount the layout and
 * await it → fetch the parameter state, render it and await it (an explicit no-op without a parameter
 * set) → mount the visualizations → invoke the initial actions. A failure at ANY step fails `ready`
 * before anything executes.
 *
 * ## Freshness (§5.3)
 * The latest refresh owns its targets PER INSTANCE. An event touches an occurrence only when its
 * `refresh_id` is that occurrence's owner; a finished old run cannot overwrite a newer view, and a
 * dashboard-level completion cannot clear an occurrence a newer run owns. Render acknowledgments and
 * completion frames pass through the same gate.
 *
 * ## The parameter lock (§5.6)
 * One gate per instance: while a parameter evaluation is pending, every action — button, programmatic,
 * initial — is refused, never queued. The lock carries an absolute deadline from
 * `timeouts.parameter_lock_seconds` that no response or host callback can extend; expiry publishes a
 * timeout outcome and releases the gate; a late response changes nothing and cannot unlock a newer
 * attempt.
 *
 * ## Transport
 * `fetch` POST + a streamed SSE read (native EventSource cannot send a JSON body), one stream per
 * refresh. Session mode sends cookies and the `DP-CSRF-Token` double-submit header on every POST;
 * proxy mode (`credentials: { proxyBaseUrl }`) sends the same paths under the proxy's base with
 * `credentials: "omit"` and NO csrf header — the proxy holds the key (the wire contract is documented
 * in dashboards.md and is what L5's reference proxy implements).
 */
(function () {
  "use strict";

  var CSRF_COOKIE = "dp_csrf";
  var CSRF_HEADER = "DP-CSRF-Token";
  var SUCCESS_SETTLE_MS = 1200;
  var MOUNTED_ATTRIBUTE = "data-datapipelines-dashboard";
  var STYLE_ELEMENT_ID = "plotly.js-style-global";

  /** The adapter functions §10.3 requires — initialisation rejects an adapter missing any of them. */
  var REQUIRED_ADAPTER_FUNCTIONS = [
    "mountLayout",
    "mountVisualization",
    "renderParameters",
    "readSelections",
    "onEdit",
    "onCommit",
    "onAction",
    "renderData",
    "renderStatus",
    "notify",
    "resize",
    "dispose",
  ];

  function isPlainObject(value) {
    return !!value && typeof value === "object" && !Array.isArray(value);
  }

  function randomUuid() {
    var bytes = new Uint8Array(16);
    crypto.getRandomValues(bytes);
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;
    var hex = [];
    for (var i = 0; i < 256; i++) hex.push((i + 0x100).toString(16).substr(1));
    var out = "";
    for (var b = 0; b < 16; b++) {
      if (b === 4 || b === 6 || b === 8 || b === 10) out += "-";
      out += hex[bytes[b]];
    }
    return out;
  }

  function nowMillis() {
    return Date.now();
  }

  function readCookie(name, cookieString) {
    var source =
      typeof cookieString === "string" ? cookieString : typeof document !== "undefined" ? document.cookie : "";
    var match = source.match(new RegExp("(?:^|;\\s*)" + name + "=([^;]*)"));
    if (!match) return "";
    try {
      return decodeURIComponent(match[1]);
    } catch (e) {
      return match[1];
    }
  }

  /**
   * The runtime's one error type. `code` is a stable reason code (a `dashboard.*` wire code where one
   * exists); `retryable` says whether a Retry control makes sense; `details` carries paths — never
   * values (the 286/298 rule).
   */
  function DashboardError(code, message, details) {
    var error = new Error(message);
    error.name = "DashboardError";
    error.code = code;
    error.retryable = !!(details && details.retryable);
    error.details = details || {};
    return error;
  }

  function isDashboardError(value) {
    return !!value && value.name === "DashboardError";
  }

  /** Settles with `promise` inside `ms`, else rejects with `errorFactory()` — the deadline never extends. */
  function withDeadline(promise, ms, errorFactory) {
    return new Promise(function (resolve, reject) {
      var timer = setTimeout(function () {
        reject(errorFactory());
      }, ms);
      promise.then(
        function (value) {
          clearTimeout(timer);
          resolve(value);
        },
        function (reason) {
          clearTimeout(timer);
          reject(reason);
        },
      );
    });
  }

  /**
   * The SSE line protocol over a fetch body: `event:` + `id:` + `data:` frames separated by blank
   * lines, `:` comment lines carrying `heartbeat` and `revoked`. Partial chunks are buffered; a frame
   * is emitted only whole.
   */
  function SseParser(onFrame, onComment) {
    var buffer = "";
    function dispatch(raw) {
      var event = "message";
      var data = "";
      var lines = raw.split("\n");
      for (var i = 0; i < lines.length; i++) {
        var line = lines[i];
        if (line.charAt(0) === ":") {
          if (onComment) onComment(line.slice(1).replace(/^ /, ""));
          continue;
        }
        if (line.indexOf("event:") === 0) event = line.slice(6).replace(/^ /, "");
        else if (line.indexOf("data:") === 0) data += (data ? "\n" : "") + line.slice(5).replace(/^ /, "");
      }
      if (data !== "") {
        try {
          onFrame(event, JSON.parse(data));
        } catch (e) {
          // A frame that is not JSON is dropped whole — never half a frame; the stream's own end
          // and the deadlines surface the fault.
        }
      }
    }
    return {
      push: function (chunk) {
        buffer += chunk;
        var index;
        while ((index = buffer.indexOf("\n\n")) !== -1) {
          dispatch(buffer.slice(0, index));
          buffer = buffer.slice(index + 2);
        }
      },
    };
  }

  /**
   * One mounted dashboard. Everything below is instance-scoped: two instances share no state, no
   * ownership map and no DOM (record acceptance 1).
   */
  function DashboardInstance(initOptions, env) {
    this._init = initOptions;
    this._env = env;
    this._disposed = false;
    this._bootstrapped = false;
    this._instanceId = env.uuid();
    this._adapter = initOptions.adapter;
    this._server = initOptions.server;
    this._options = initOptions.options || {};
    this._config = null;
    this._parameters = null;
    this._baseline = null;
    this._lock = null;
    this._refreshes = {};
    this._streams = {};
    this._occurrences = {};
    this._notifications = {};
    this._callbacks = { edit: [], commit: [], action: [] };
  }

  DashboardInstance.prototype._fail = function (code, message, details) {
    return DashboardError(code, message, details);
  };

  /** §10.2: resolves after the four-step barrier; rejects with the failing step's error. */
  Object.defineProperty(DashboardInstance.prototype, "ready", {
    get: function () {
      return this._ready || Promise.reject(this._fail("dashboard.disposed", "the instance is disposed"));
    },
  });

  /** Proxy mode swaps the base and drops the credential machinery (dashboards.md, the wire contract). */
  DashboardInstance.prototype._proxyMode = function () {
    return isPlainObject(this._server.credentials);
  };

  DashboardInstance.prototype._baseUrl = function () {
    var base = this._proxyMode() ? this._server.credentials.proxyBaseUrl : this._server.baseUrl;
    return String(base || "").replace(/\/$/, "");
  };

  DashboardInstance.prototype._configPath = function () {
    return "/api/v1/dashboards/" + encodeURIComponent(this._init.dashboard.id) + "/runtime/config";
  };

  DashboardInstance.prototype._parametersPath = function () {
    return this._configPath().replace(/\/config$/, "/parameters");
  };

  DashboardInstance.prototype._streamPath = function () {
    return this._configPath().replace(/\/config$/, "/visualizations");
  };

  DashboardInstance.prototype._abortPath = function (refreshId) {
    return this._configPath().replace(/\/config$/, "/runtime/refreshes/" + encodeURIComponent(refreshId) + "/abort");
  };

  DashboardInstance.prototype._renderTimeoutMs = function () {
    var option = this._options.renderTimeoutMs;
    var fromServer = this._config && this._config.timeouts ? this._config.timeouts.render_seconds : null;
    if (typeof option === "number" && option > 0) return option;
    return fromServer ? fromServer * 1000 : 20000;
  };

  /** The one fetch the runtime issues (§5.1: fetch POST; GET for the config). */
  DashboardInstance.prototype._call = function (path, body, accept) {
    if (this._disposed) return Promise.reject(this._fail("dashboard.disposed", "the instance is disposed"));
    return this._callEvenDisposed(path, body, accept);
  };

  /** The dispose path's best-effort abort: the disposed check cannot veto it (it must not be awaited). */
  DashboardInstance.prototype._callEvenDisposed = function (path, body, accept) {
    var self = this;
    var url = this._baseUrl() + path;
    var headers = { Accept: accept || "application/json" };
    var init = { method: body === undefined ? "GET" : "POST", headers: headers };
    if (this._proxyMode()) {
      init.credentials = "omit"; // the proxy holds the key: no cookie, no csrf header
    } else {
      init.credentials = "same-origin";
      if (body !== undefined) headers[CSRF_HEADER] = readCookie(CSRF_COOKIE);
    }
    if (body !== undefined) {
      headers["Content-Type"] = "application/json";
      init.body = JSON.stringify(body);
    }
    var started = this._env.now();
    return Promise.resolve()
      .then(function () {
        return self._env.fetchImpl(url, init);
      })
      .then(
        function (response) {
          return self._consume(response, started);
        },
        function (reason) {
          throw self._fail("transport.network", "the server could not be reached", {
            retryable: true,
            cause: String(reason),
          });
        },
      );
  };

  /** Envelope handling: §4's shape on error, the payload on success; a 409 stale is marked. */
  DashboardInstance.prototype._consume = function (response, started) {
    var self = this;
    return Promise.resolve(response.text()).then(function (text) {
      var payload = null;
      if (text) {
        try {
          payload = JSON.parse(text);
        } catch (e) {
          payload = null;
        }
      }
      if (response.ok) return payload && payload.data !== undefined ? payload.data : payload;
      var code = payload && payload.error && payload.error.code ? payload.error.code : "transport.status_" + response.status;
      var details = { status: response.status, durationMs: self._env.now() - started };
      if (payload && payload.error && payload.error.details) details.server = payload.error.details;
      var error = self._fail(code, payload && payload.error && payload.error.message ? payload.error.message : code, details);
      error.retryable = response.status >= 500 || response.status === 429;
      if (response.status === 409 && code === "dashboard.runtime.configuration_stale") {
        error.configurationStale = true;
      }
      throw error;
    });
  };

  /**
   * The stream call: POST, then read the body as SSE. `onFrame` sees every whole frame; the returned
   * handle closes the READ (the refresh runs on server-side) and never throws.
   */
  DashboardInstance.prototype._openStream = function (body, handlers) {
    var self = this;
    var url = this._baseUrl() + this._streamPath();
    var headers = { "Content-Type": "application/json", Accept: "text/event-stream" };
    var init = { method: "POST", headers: headers };
    if (this._proxyMode()) {
      init.credentials = "omit";
    } else {
      init.credentials = "same-origin";
      headers[CSRF_HEADER] = readCookie(CSRF_COOKIE);
    }
    init.body = JSON.stringify(body);
    var closed = false;
    var controller = typeof AbortController === "function" ? new AbortController() : null;
    if (controller) init.signal = controller.signal;

    var parse = SseParser(
      function (event, payload) {
        if (!closed) handlers.onFrame(event, payload);
      },
      function (comment) {
        if (!closed && comment === "revoked") handlers.onRevoked();
      },
    );

    function fail(reason) {
      if (!closed) {
        handlers.onFailure(
          isDashboardError(reason) ? reason : self._fail("transport.network", "the stream failed", { retryable: true }),
        );
      }
    }

    var settled = Promise.resolve()
      .then(function () {
        return self._env.fetchImpl(url, init);
      })
      .then(function (response) {
        if (!response.ok) {
          return self._consume(response, self._env.now()).then(
            function () {
              throw self._fail("transport.stream", "the stream refused");
            },
            function (error) {
              throw error;
            },
          );
        }
        if (!response.body || typeof response.body.getReader !== "function") {
          throw self._fail("transport.stream", "streaming is unavailable in this browser", { retryable: true });
        }
        var reader = response.body.getReader();
        var decoder = new TextDecoder();
        function pump() {
          return reader.read().then(function (chunk) {
            if (closed) {
              try {
                reader.cancel();
              } catch (e) {
                /* already gone */
              }
              return undefined;
            }
            if (chunk.done) {
              handlers.onEnd();
              return undefined;
            }
            parse.push(decoder.decode(chunk.value, { stream: true }));
            return pump();
          });
        }
        return pump();
      })
      .catch(fail);

    return {
      close: function () {
        if (closed) return;
        closed = true;
        if (controller) {
          try {
            controller.abort();
          } catch (e) {
            /* closing twice is nothing */
          }
        }
      },
      settled: settled,
    };
  };

  // ---------------------------------------------------------------------------------------- bootstrap

  /**
   * R22's barrier, each step awaited and recorded. Resolves `ready`; rejects with the failing step's
   * error — before anything executes (the initial actions are the last step).
   */
  DashboardInstance.prototype._bootstrap = function () {
    var self = this;
    var steps = [];
    this._readySteps = steps;

    this._ready = (async function () {
      // Step 0 — the adapter was validated synchronously in init (§10.3).
      steps.push("adapter_validated");

      // Step 1 — the configuration; the renderer/bundle pair is judged BEFORE any execution.
      self._config = await self._call(self._configPath());
      steps.push("config_fetched");
      self._judgeRenderers();
      self._ensureStyleElement();

      // Step 2 — the layout, awaited.
      await withDeadline(
        Promise.resolve(self._adapter.mountLayout(self._config.layout || {})),
        self._renderTimeoutMs(),
        function () {
          return self._fail("render.layout_timeout", "the host did not mount the layout in time");
        },
      );
      steps.push("layout_mounted");

      // Step 3 — the parameter state (an explicit no-op without a parameter set), awaited.
      var parameterSet = self._config.parameter_set;
      if (parameterSet && parameterSet.name) {
        var evaluated = await self._evaluateParameters("bootstrap");
        steps.push("parameters_rendered");
        self._parameters = evaluated;
        self._baseline = { selections: self._snapshotSelections(evaluated), revision: evaluated.parameter_revision };
      } else {
        steps.push("parameters_rendered");
      }

      // The visualizations' placeholders — the adapter mounts each occurrence's renderer.
      await self._mountOccurrences();
      steps.push("visualizations_mounted");

      // Step 4 — the initial actions, once and only once everything above held.
      self._bootstrapped = true;
      var initial = (self._config.actions || []).filter(function (action) {
        return action && action.initial === true;
      });
      for (var i = 0; i < initial.length; i++) {
        self._invokeAction(initial[i].name, { via: "initial" });
      }
      steps.push("initial_actions_invoked");
      return undefined;
    })();

    this._ready.catch(function (error) {
      // The bootstrap's failure is PUBLISHED (record §5.4: a blocking error stays discoverable, with
      // its recovery) and then disposes the instance: no half-mounted board. The host also reads the
      // rejection from `ready`; a retry is a NEW init on a clean container.
      var stale = isDashboardError(error) && error.configurationStale === true;
      self._publishNotification({
        scope: "bootstrap",
        severity: "error",
        code: isDashboardError(error) ? error.code : "bootstrap.failed",
        message: isDashboardError(error) ? error.message : "the dashboard could not be mounted",
        retryable: !!(isDashboardError(error) && error.retryable),
        recover: stale ? "reload" : isDashboardError(error) && error.retryable ? "retry" : null,
        configurationStale: stale,
      });
      if (!stale) self._dispose("bootstrap_failed"); // the stale notification disposes on publish
    });
    return this._ready;
  };

  /** Adapter validation (§10.3): a missing function is refused at registration — synchronously, in init. */
  function validateAdapter(adapter) {
    if (!isPlainObject(adapter)) {
      throw DashboardError("adapter.invalid", "init requires an adapter object");
    }
    for (var i = 0; i < REQUIRED_ADAPTER_FUNCTIONS.length; i++) {
      var name = REQUIRED_ADAPTER_FUNCTIONS[i];
      if (typeof adapter[name] !== "function") {
        throw DashboardError("adapter.missing_function", "the adapter is missing the required function '" + name + "'", {
          missing: name,
        });
      }
    }
  }

  /**
   * The renderer/bundle judgement (§10.4): every occurrence's renderer must be a kind the adapter side
   * registered, at the major version it declared, and the ONE Plotly bundle on the page must carry
   * every trace the board needs. All of it fails here — before any execution.
   */
  DashboardInstance.prototype._judgeRenderers = function () {
    var renderers = this._env.renderers;
    var bundle = this._config.renderer && this._config.renderer.bundle ? this._config.renderer.bundle : "2d";
    var declaredBundles = this._env.declaredPlotlyBundles();
    if (declaredBundles.length > 1) {
      throw this._fail("renderer.two_bundles", "the page loads more than one Plotly bundle; the two are never on one page");
    }
    var visualizations = this._config.visualizations || [];
    for (var i = 0; i < visualizations.length; i++) {
      var occurrence = visualizations[i];
      var renderer = occurrence.renderer;
      if (!renderer || typeof renderer.kind !== "string") {
        throw this._fail("renderer.unsupported", "a visualization names no renderer", { name: occurrence.name });
      }
      var implementation = renderers[renderer.kind];
      if (!implementation) {
        throw this._fail("renderer.unsupported", "no adapter is registered for renderer kind '" + renderer.kind + "'", {
          kind: renderer.kind,
          name: occurrence.name,
        });
      }
      if (String(renderer.version) !== String(implementation.version)) {
        throw this._fail(
          "renderer.version_mismatch",
          "renderer '" + renderer.kind + "' needs major " + renderer.version + "; the adapter provides " + implementation.version,
          { kind: renderer.kind, name: occurrence.name },
        );
      }
      if (renderer.kind === "plotly" && bundle === "3d" && declaredBundles[0] === "2d") {
        throw this._fail("renderer.bundle_insufficient", "the dashboard needs the 3D bundle; the page loaded the 2D bundle", {
          name: occurrence.name,
        });
      }
    }
  };

  /**
   * The design-around (§10.4): pre-place the empty element Plotly checks before the bundle injects, so
   * `addRelatedStyleRule` returns early and Plotly's rules come from the vendored plotly.css under
   * `style-src 'self'`. Idempotent — the host may have placed it already; an element without the class
   * gains it.
   */
  DashboardInstance.prototype._ensureStyleElement = function () {
    if (typeof document === "undefined") return;
    var existing = document.getElementById(STYLE_ELEMENT_ID);
    if (existing) {
      if (!(" " + existing.className + " ").match(/ no-inline-styles /)) existing.className += " no-inline-styles";
      return;
    }
    var style = document.createElement("style");
    style.setAttribute("id", STYLE_ELEMENT_ID);
    style.className = "no-inline-styles";
    style.appendChild(document.createTextNode(""));
    document.head.appendChild(style);
  };

  // --------------------------------------------------------------------------------------- parameters

  DashboardInstance.prototype._snapshotSelections = function (evaluated) {
    var out = {};
    var parameters = (evaluated && evaluated.parameters) || [];
    for (var i = 0; i < parameters.length; i++) {
      var parameter = parameters[i];
      if (parameter && parameter.definition && parameter.definition.name) {
        out[parameter.definition.name] = parameter.value === undefined ? null : parameter.value;
      }
    }
    return out;
  };

  /**
   * One parameter attempt (§8.2): the FULL selections — the server's current state with the host's
   * committed values — under `intent`. The lock is acquired before the call and released only by the
   * accepted response (applied and rendered), its deadline, or disposal — never by a late timer.
   */
  DashboardInstance.prototype._evaluateParameters = function (intent) {
    if (this._lock) {
      return Promise.reject(this._fail("parameters.locked", "a parameter evaluation is already pending"));
    }
    var self = this;
    var startedAt = this._env.now();
    var lockSeconds =
      this._config && this._config.timeouts && this._config.timeouts.parameter_lock_seconds
        ? this._config.timeouts.parameter_lock_seconds
        : 30;
    var attempt = {
      intent: intent,
      startedAt: startedAt,
      deadline: startedAt + lockSeconds * 1000,
      finished: false,
      timer: null,
    };
    this._lock = attempt;
    attempt.timer = this._env.setTimeout(function () {
      self._lockExpired(attempt);
    }, attempt.deadline - startedAt);

    return this._call(this._parametersPath(), {
      configuration_id: this._config.configuration_id,
      instance_id: this._instanceId,
      selections: this._committedSelections(),
      intent: intent,
    }).then(function (evaluated) {
      // A response arriving after its attempt expired (or was superseded) changes nothing (§5.6).
      if (attempt.finished) {
        throw self._fail("parameters.superseded", "a late parameter response after its deadline changes nothing");
      }
      return self._acceptParameterResponse(attempt, evaluated);
    });
  };

  /** Every parameter's current value, hidden and disabled included (D23). */
  DashboardInstance.prototype._committedSelections = function () {
    var state = this._parameters ? this._snapshotSelections(this._parameters) : {};
    var committed = null;
    try {
      committed = this._adapter.readSelections();
    } catch (e) {
      committed = null;
    }
    if (committed && typeof committed === "object") {
      for (var name in committed) {
        if (Object.prototype.hasOwnProperty.call(committed, name) && Object.prototype.hasOwnProperty.call(state, name)) {
          state[name] = committed[name];
        }
      }
    }
    return state;
  };

  /** The accepted response: applied to the adapter BEFORE the gate releases (§5.6's order). */
  DashboardInstance.prototype._acceptParameterResponse = function (attempt, evaluated) {
    attempt.finished = true;
    this._clearLockTimer(attempt);
    if (this._lock === attempt) this._lock = null;
    var self = this;
    return Promise.resolve(this._adapter.renderParameters(evaluated)).then(function () {
      self._parameters = evaluated;
      self._baseline = { selections: self._snapshotSelections(evaluated), revision: evaluated.parameter_revision };
      self._publishNotification({
        scope: "parameters",
        severity: "info",
        code: "parameters.applied",
        message: "parameter state applied",
        retryable: false,
        recover: null,
        dedupe: "parameters.applied",
      });
      return evaluated;
    });
  };

  /** The deadline expired: release, publish, best-effort cancellation — never awaiting it (§5.6). */
  DashboardInstance.prototype._lockExpired = function (attempt) {
    if (attempt.finished) return;
    attempt.finished = true;
    if (this._lock === attempt) this._lock = null;
    this._publishNotification({
      scope: "parameters",
      severity: "error",
      code: "parameters.lock_timeout",
      message: "the parameter evaluation did not finish in time; submission stays blocked until the state is valid",
      retryable: true,
      recover: "retry",
    });
  };

  DashboardInstance.prototype._clearLockTimer = function (attempt) {
    if (attempt.timer) {
      this._env.clearTimeout(attempt.timer);
      attempt.timer = null;
    }
  };

  /** The one gate (§5.6): refused while a parameter attempt is pending — never queued. */
  DashboardInstance.prototype._requireActionGate = function () {
    if (this._disposed) throw this._fail("dashboard.disposed", "the instance is disposed");
    if (this._lock) throw this._fail("actions.locked", "an action is refused while a parameter evaluation is pending");
    if (!this._bootstrapped) throw this._fail("actions.not_ready", "the instance is not ready");
  };

  // ---------------------------------------------------------------------------------------- refreshes

  /**
   * The authorized programmatic action (§10.2) — gated exactly like a button. `scope: "all"` claims
   * every occurrence; `scope: "targets"` claims the named ones only. A fresh v4 refresh id is minted
   * here and nowhere else.
   */
  DashboardInstance.prototype.refresh = function (options) {
    var self = this;
    try {
      this._requireActionGate();
    } catch (error) {
      return Promise.reject(error);
    }
    var scope = options && options.scope ? options.scope : "all";
    var targets = options && options.targets ? options.targets.slice() : [];
    if (scope !== "all" && scope !== "targets") {
      return Promise.reject(this._fail("actions.invalid_scope", "scope is 'all' or 'targets'"));
    }
    if (scope === "targets" && targets.length === 0) {
      return Promise.reject(this._fail("dashboard.validation.empty_targets", "scope targets names no occurrence"));
    }
    var known = {};
    (this._config.visualizations || []).forEach(function (occurrence) {
      known[occurrence.name] = occurrence;
    });
    for (var i = 0; i < targets.length; i++) {
      if (!known[targets[i]]) {
        return Promise.reject(
          this._fail("dashboard.validation.target_not_visualization", "'" + targets[i] + "' is no visualization occurrence", {
            target: targets[i],
          }),
        );
      }
    }
    var names = scope === "all" ? Object.keys(known) : targets;
    var refreshId = this._env.uuid();
    this._refreshes[refreshId] = { id: refreshId, targets: names, ended: false, abortAcked: false };
    this._claimOccurrences(refreshId, names);

    var body = {
      configuration_id: this._config.configuration_id,
      instance_id: this._instanceId,
      refresh_id: refreshId,
      parameter_revision: this._parameters ? this._parameters.parameter_revision : 0,
      selections: this._committedSelections(),
      scope: scope,
      targets: scope === "targets" ? targets : [],
    };

    this._renderOccurrenceStatuses(names, { state: "in-progress", stale: false, reason: null, refreshId: refreshId });
    this._streams[refreshId] = this._openStream(body, {
      onFrame: function (event, payload) {
        self._onFrame(event, payload);
      },
      onRevoked: function () {
        self._onRevoked(refreshId);
      },
      onEnd: function () {
        self._onStreamEnd(refreshId);
      },
      onFailure: function (error) {
        self._onStreamFailure(refreshId, error);
      },
    });
    return Promise.resolve(refreshId);
  };

  /** Ownership: the NEWEST refresh for an occurrence wins; the old owner keeps only its other targets. */
  DashboardInstance.prototype._claimOccurrences = function (refreshId, names) {
    for (var i = 0; i < names.length; i++) {
      var occurrence = this._occurrences[names[i]];
      if (!occurrence) continue;
      occurrence.owner = refreshId;
      occurrence.stale = false;
    }
  };

  /** The freshness gate: an event touches an occurrence only through its CURRENT owner. */
  DashboardInstance.prototype._owns = function (name, refreshId) {
    var occurrence = this._occurrences[name];
    return !!occurrence && occurrence.owner === refreshId;
  };

  DashboardInstance.prototype._renderOccurrenceStatuses = function (names, status) {
    for (var i = 0; i < names.length; i++) this._renderOccurrenceStatus(names[i], status);
  };

  DashboardInstance.prototype._renderOccurrenceStatus = function (name, status) {
    var occurrence = this._occurrences[name];
    if (!occurrence) return;
    occurrence.status = status.state;
    occurrence.stale = status.stale === true;
    try {
      this._adapter.renderStatus({ name: name, type: "visualization" }, {
        state: status.state,
        stale: status.stale === true,
        reason: status.reason || null,
      });
    } catch (e) {
      // A throwing status hook is isolated (record §5.4) and reported on the runtime-owned path.
      this._publishNotification({
        scope: name,
        severity: "warning",
        code: "render.status_failed",
        message: "the host's status handler failed",
        retryable: false,
        recover: null,
      });
    }
  };

  /**
   * The frames (§8.3). Every payload carries `refresh_id`; data/status frames carry the occurrence
   * name and are judged against ownership; a frame for an unknown refresh is another instance's.
   */
  DashboardInstance.prototype._onFrame = function (event, payload) {
    if (this._disposed) return;
    var refreshId = payload && payload.refresh_id;
    var refresh = this._refreshes[refreshId];
    if (!refresh) return;
    if (event === "refresh_started") {
      refresh.deadline = payload.deadline_at || null;
      return;
    }
    if (event === "source_started" || event === "source_completed") return;
    if (event === "source_failed") {
      var reason = payload.error || {};
      this._publishNotification({
        scope: "sources",
        severity: "error",
        code: reason.code ? reason.code : "source.failed",
        message: "a source failed",
        retryable: true,
        recover: "retry",
        refreshId: refreshId,
      });
      return;
    }
    if (event === "visualization_status") {
      this._onVisualizationStatus(refreshId, payload);
      return;
    }
    if (event === "visualization_data") {
      this._onVisualizationData(refreshId, payload);
      return;
    }
    if (event === "refresh_completed") {
      this._onRefreshCompleted(refresh, payload);
    }
  };

  DashboardInstance.prototype._onVisualizationStatus = function (refreshId, payload) {
    var name = payload.name;
    if (!this._owns(name, refreshId)) return; // a newer run owns it
    var state = payload.state;
    if (state === "no-data") {
      this._renderOccurrenceStatus(name, { state: "no-data", stale: false, reason: null, refreshId: refreshId });
      return;
    }
    if (state === "error" || state === "abort") {
      this._renderOccurrenceStatus(name, {
        state: state,
        stale: false,
        reason: payload.reason || (payload.stage ? { stage: payload.stage } : null),
        refreshId: refreshId,
      });
      return;
    }
    this._renderOccurrenceStatus(name, { state: "in-progress", stale: false, reason: null, refreshId: refreshId });
  };

  DashboardInstance.prototype._onVisualizationData = function (refreshId, payload) {
    var self = this;
    var name = payload.name;
    if (!this._owns(name, refreshId)) return;
    var occurrence = this._occurrences[name];
    if (!occurrence || !occurrence.renderer) return;
    var renderer = occurrence.renderer;
    this._renderOccurrenceStatus(name, { state: "in-progress", stale: false, reason: null, refreshId: refreshId });
    var render = Promise.resolve(
      renderer.renderData({ name: name, type: "visualization", occurrence: occurrence.occurrence }, payload.rows, payload.bindings || {}),
    );
    withDeadline(render, this._renderTimeoutMs(), function () {
      return self._fail("render.timeout", "the renderer did not acknowledge in time");
    }).then(
      function (outcome) {
        if (self._disposed || !self._owns(name, refreshId)) return; // a late acknowledgment cannot publish (§5.3)
        if (outcome === "no-data") {
          self._renderOccurrenceStatus(name, { state: "no-data", stale: false, reason: null, refreshId: refreshId });
        } else {
          self._renderOccurrenceStatus(name, { state: "success", stale: false, reason: null, refreshId: refreshId });
          self._scheduleSuccessSettle(name, refreshId);
        }
      },
      function (error) {
        if (self._disposed || !self._owns(name, refreshId)) return;
        self._renderOccurrenceStatus(name, {
          state: "error",
          stale: false,
          reason: { code: isDashboardError(error) ? error.code : "render.failed" },
          refreshId: refreshId,
        });
        self._publishNotification({
          scope: name,
          severity: "error",
          code: "render.failed",
          message: "the visualization could not be rendered",
          retryable: false,
          recover: null,
          refreshId: refreshId,
        });
      },
    );
  };

  /** `success` is brief (§5.4): it settles back to `ready` without a new adapter status event. */
  DashboardInstance.prototype._scheduleSuccessSettle = function (name, refreshId) {
    var self = this;
    this._env.setTimeout(function () {
      var occurrence = self._occurrences[name];
      if (!occurrence || self._disposed) return;
      if (occurrence.status === "success" && occurrence.owner === refreshId) {
        occurrence.status = "ready";
        try {
          self._adapter.renderStatus({ name: name, type: "visualization" }, { state: "ready", stale: false, reason: null });
        } catch (e) {
          /* isolated */
        }
      }
    }, SUCCESS_SETTLE_MS);
  };

  DashboardInstance.prototype._onRefreshCompleted = function (refresh, payload) {
    refresh.ended = true;
    this._closeStream(refresh.id);
    var targets = payload.targets || {};
    for (var i = 0; i < refresh.targets.length; i++) {
      var name = refresh.targets[i];
      if (!this._owns(name, refresh.id)) continue; // a newer run owns it — completion cannot clear it
      var outcome = targets[name] || {};
      if (outcome.outcome === "rendered") continue; // the data frame already set the state
      if (outcome.outcome === "no-data") {
        this._renderOccurrenceStatus(name, { state: "no-data", stale: false, reason: null, refreshId: refresh.id });
      } else if (outcome.outcome === "abort") {
        this._renderOccurrenceStatus(name, { state: "abort", stale: false, reason: outcome.reason || null, refreshId: refresh.id });
      } else if (outcome.outcome) {
        this._renderOccurrenceStatus(name, {
          state: "error",
          stale: false,
          reason: outcome.reason || (outcome.stage ? { stage: outcome.stage } : null),
          refreshId: refresh.id,
        });
      }
    }
    if (payload.status && payload.status !== "COMPLETED") {
      this._publishNotification({
        scope: "refresh",
        severity: payload.status === "FAILED" ? "error" : "warning",
        code: "refresh." + String(payload.status).toLowerCase(),
        message: "the refresh ended " + payload.status,
        retryable: payload.status !== "ABORTED",
        recover: payload.status === "ABORTED" ? null : "retry",
        refreshId: refresh.id,
      });
    } else {
      this._publishNotification({
        scope: "refresh",
        severity: "info",
        code: "refresh.completed",
        message: "the refresh completed",
        retryable: false,
        recover: null,
        dedupe: "refresh.completed." + refresh.id,
        refreshId: refresh.id,
      });
    }
  };

  /** The authority guard cut the READ; the refresh runs on — content kept, nothing spins. */
  DashboardInstance.prototype._onRevoked = function (refreshId) {
    var refresh = this._refreshes[refreshId];
    if (!refresh || refresh.ended) return;
    this._interrupt(refreshId, "stream.revoked", "the session lost its authority to read this refresh");
  };

  /** The stream ended WITHOUT `refresh_completed` — a transport failure (§8.3, D45). */
  DashboardInstance.prototype._onStreamEnd = function (refreshId) {
    var refresh = this._refreshes[refreshId];
    if (!refresh || refresh.ended) return;
    this._interrupt(refreshId, "transport.disconnected", "the connection to the refresh was lost");
  };

  DashboardInstance.prototype._onStreamFailure = function (refreshId, error) {
    var refresh = this._refreshes[refreshId];
    if (!refresh || refresh.ended) return;
    this._interrupt(refreshId, error && error.code ? error.code : "transport.network", "the refresh stream failed");
  };

  /**
   * Connection loss (D45): retain content, report the interruption for the still-pending targets,
   * offer Retry. Completed targets stay completed; nothing restarts or replays automatically.
   */
  DashboardInstance.prototype._interrupt = function (refreshId, code, message) {
    var refresh = this._refreshes[refreshId];
    if (!refresh || refresh.ended) return;
    refresh.ended = true;
    this._closeStream(refreshId);
    var pending = [];
    for (var i = 0; i < refresh.targets.length; i++) {
      var name = refresh.targets[i];
      var occurrence = this._occurrences[name];
      if (!occurrence || occurrence.owner !== refreshId) continue;
      if (occurrence.status === "in-progress" || occurrence.status === "success") {
        occurrence.status = "ready";
        occurrence.stale = true;
        try {
          this._adapter.renderStatus({ name: name, type: "visualization" }, { state: "ready", stale: true, reason: null });
        } catch (e) {
          /* isolated */
        }
        pending.push(name);
      }
    }
    this._publishNotification({
      scope: "refresh",
      severity: "error",
      code: code,
      message: message,
      retryable: true,
      recover: "retry",
      refreshId: refreshId,
      pending: pending,
    });
  };

  // ------------------------------------------------------------------------------------------- abort

  /**
   * §8.4: an authenticated intent keyed by the refresh id. Local ownership is invalidated IMMEDIATELY;
   * the POST is asynchronous and its acknowledgment means cancellation was requested, not that work
   * stopped. A 404 (already finished) is idempotent — no state change.
   */
  DashboardInstance.prototype.abort = function (refreshId) {
    var refresh = this._refreshes[refreshId];
    if (!refresh || refresh.ended) return Promise.resolve({ abort_requested: false });
    refresh.ended = true;
    this._closeStream(refreshId);
    for (var i = 0; i < refresh.targets.length; i++) {
      var name = refresh.targets[i];
      if (this._owns(name, refreshId)) {
        this._renderOccurrenceStatus(name, { state: "abort", stale: false, reason: null, refreshId: refreshId });
      }
    }
    var self = this;
    return this._call(this._abortPath(refreshId), { instance_id: this._instanceId }).then(
      function () {
        refresh.abortAcked = true;
        return { abort_requested: true };
      },
      function (error) {
        // Transport failure is recorded separately; a 404 is the finished-refresh idempotence.
        if (!error || !error.details || error.details.status !== 404) {
          self._publishNotification({
            scope: "refresh",
            severity: "warning",
            code: "abort.not_acked",
            message: "the abort could not be confirmed",
            retryable: false,
            recover: null,
            refreshId: refreshId,
          });
        }
        return { abort_requested: false };
      },
    );
  };

  // -------------------------------------------------------------------------------------- interaction

  /** The runtime wraps the adapter's registration: every callback carries the identity quartet. */
  DashboardInstance.prototype._wrapCallbacks = function () {
    var self = this;
    ["edit", "commit", "action"].forEach(function (kind) {
      var capitalized = kind.charAt(0).toUpperCase() + kind.slice(1);
      var register = self._adapter["on" + capitalized];
      register.call(self._adapter, function (payload) {
        if (self._disposed) return; // disposal invalidates pending callbacks (record §5.3)
        if (kind === "action") {
          self._invokeAction(payload && payload.action, { via: "control" });
          return;
        }
        var enriched = {
          instanceId: self._instanceId,
          name: payload && payload.name,
          type: payload && payload.type,
          refreshId: (payload && payload.refreshId) || null,
        };
        var listeners = self._callbacks[kind].slice();
        for (var i = 0; i < listeners.length; i++) listeners[i](enriched, payload);
        if (kind === "commit") self._onCommitted(enriched);
      });
    });
  };

  /** The host's own listeners for edit/commit callbacks (the runtime's, not the adapter's). */
  DashboardInstance.prototype.on = function (kind, callback) {
    if (kind !== "edit" && kind !== "commit" && kind !== "action") {
      throw this._fail("listener.unknown_kind", "listeners are 'edit', 'commit' and 'action'");
    }
    this._callbacks[kind].push(callback);
    return this;
  };

  /**
   * A committed change (record §4.5): classify parent/leaf from the server metadata. A parent change
   * re-evaluates the set; a leaf change marks occurrences stale without a refresh. An action bound to
   * the control (D42) fires from the commit gesture only, through the gate.
   */
  DashboardInstance.prototype._onCommitted = function (enriched) {
    if (!this._parameters) return;
    var name = enriched.name;
    if (!name) return;
    var parents = this._parameters.parents || [];
    var bound = this._boundActionFor(name);
    if (parents.indexOf(name) !== -1 || bound) {
      this._safeEvaluate(bound ? bound.action : null);
      return;
    }
    this._markAllStale();
  };

  DashboardInstance.prototype._boundActionFor = function (parameterName) {
    var controls = (this._config.action_controls || []).filter(function (control) {
      return control && control.parameter === parameterName;
    });
    return controls[0] || null;
  };

  /** The parameter re-evaluation outside the caller's stack; errors reach the runtime-owned path. */
  DashboardInstance.prototype._safeEvaluate = function (actionName) {
    var self = this;
    (async function () {
      try {
        await self._evaluateParameters("parent_change");
      } catch (error) {
        if (isDashboardError(error) && (error.code === "parameters.superseded" || error.code === "parameters.locked")) return;
        self._publishNotification({
          scope: "parameters",
          severity: "error",
          code: error && error.code ? error.code : "parameters.failed",
          message: "the parameter evaluation failed",
          retryable: !!(error && error.retryable),
          recover: "retry",
          configurationStale: !!(error && error.configurationStale),
        });
        return;
      }
      if (actionName) self._invokeAction(actionName, { via: "parameter_binding" });
    })();
  };

  /** Actions: the dashboard's own refresh actions (§3.2), fired through the gate. */
  DashboardInstance.prototype._invokeAction = function (actionName, meta) {
    var self = this;
    var action = null;
    (this._config.actions || []).forEach(function (candidate) {
      if (candidate && candidate.name === actionName) action = candidate;
    });
    if (!action || action.type !== "refresh") {
      this._publishNotification({
        scope: "actions",
        severity: "warning",
        code: "actions.unknown",
        message: "no such action",
        retryable: false,
        recover: null,
      });
      return;
    }
    (async function () {
      try {
        var scope = action.scope === "targets" ? "targets" : "all";
        await self.refresh({ scope: scope, targets: scope === "targets" ? action.targets || [] : [] });
      } catch (error) {
        self._publishNotification({
          scope: "actions",
          severity: "error",
          code: error && error.code ? error.code : "actions.failed",
          message: "the action could not start a refresh",
          retryable: !!(error && error.retryable),
          recover: error && error.code === "actions.locked" ? null : "retry",
        });
      }
    })();
  };

  /** A parameter change invalidates affected occurrences even without a new refresh (§5.3). */
  DashboardInstance.prototype._markAllStale = function () {
    var names = Object.keys(this._occurrences);
    for (var i = 0; i < names.length; i++) {
      var occurrence = this._occurrences[names[i]];
      if (occurrence.owner && (occurrence.status === "in-progress" || occurrence.status === "success" || occurrence.status === "ready")) {
        occurrence.stale = true;
        try {
          this._adapter.renderStatus({ name: names[i], type: "visualization" }, {
            state: occurrence.status,
            stale: true,
            reason: null,
          });
        } catch (e) {
          /* isolated */
        }
      }
    }
  };

  // ----------------------------------------------------------------------------------- notifications

  /**
   * Notifications (record §5.4): structured, instance-scoped, deduplicated per outcome; old attempts
   * never replace a newer attempt's message. Delivered to BOTH the adapter and the init's
   * `onNotification`; recovery intents come back through `recover`.
   */
  DashboardInstance.prototype._publishNotification = function (notification) {
    if (this._disposed) return;
    var enriched = {
      instanceId: this._instanceId,
      scope: notification.scope,
      severity: notification.severity,
      code: notification.code,
      message: notification.message,
      retryable: notification.retryable === true,
      recover: notification.recover || null,
      refreshId: notification.refreshId || null,
      pending: notification.pending || null,
      configurationStale: notification.configurationStale === true,
      at: this._env.now(),
    };
    var key = notification.dedupe || notification.code + ":" + notification.scope;
    var previous = this._notifications[key];
    this._notifications[key] = enriched;
    if (
      previous &&
      previous.code === enriched.code &&
      previous.message === enriched.message &&
      previous.recover === enriched.recover
    ) {
      return; // deduplicated — the same outcome twice in a row is one message
    }
    try {
      this._adapter.notify(enriched);
    } catch (e) {
      // A throwing notify must not strand the lock or its siblings (record §5.4).
    }
    if (typeof this._options.onNotification === "function") {
      try {
        this._options.onNotification(enriched);
      } catch (e) {
        /* the host's handler failing is the host's business */
      }
    }
    if (enriched.configurationStale) {
      // 409: the client reloads — nothing further happens on this instance.
      this._dispose("configuration_stale");
    }
  };

  /** Recovery: the ONLY way a notification's Retry re-enters the runtime (§5.4). */
  DashboardInstance.prototype.recover = function (intent) {
    if (this._disposed) return Promise.reject(this._fail("dashboard.disposed", "the instance is disposed"));
    if (intent === "retry") return this.refresh({ scope: "all", targets: [] });
    if (intent === "reload") {
      if (typeof location !== "undefined" && location.reload) location.reload();
      return Promise.resolve(undefined);
    }
    return Promise.reject(this._fail("recover.unknown_intent", "recovery intents are 'retry' and 'reload'"));
  };

  // ---------------------------------------------------------------------------------- reset and resize

  /**
   * Reset (§5.8, round one's dashboard-wide scope): restore the parameter state behind the rendered
   * view — the last APPLIED server state — clearing the staleness uncommitted edits introduced,
   * without executing sources or emitting actions. Pre-existing stale/error states other than the
   * edit-staleness stand.
   */
  DashboardInstance.prototype.reset = function () {
    try {
      this._requireActionGate();
    } catch (error) {
      return Promise.reject(error);
    }
    if (!this._baseline) return Promise.resolve(undefined);
    var self = this;
    var restored = this._baselineRenderable();
    return Promise.resolve(this._adapter.renderParameters(restored)).then(function () {
      self._parameters = Object.assign({}, self._parameters, { parameters: restored.parameters });
      return undefined;
    });
  };

  /** The applied state with the baseline's values in place of any uncommitted edits. */
  DashboardInstance.prototype._baselineRenderable = function () {
    var evaluated = this._parameters;
    if (!evaluated) return null;
    var copy = JSON.parse(JSON.stringify(evaluated));
    var baseline = this._baseline.selections;
    for (var i = 0; i < copy.parameters.length; i++) {
      var parameter = copy.parameters[i];
      var name = parameter && parameter.definition && parameter.definition.name;
      if (name && Object.prototype.hasOwnProperty.call(baseline, name)) parameter.value = baseline[name];
    }
    return copy;
  };

  DashboardInstance.prototype.resize = function () {
    if (this._disposed) return;
    var names = Object.keys(this._occurrences);
    for (var i = 0; i < names.length; i++) {
      var renderer = this._occurrences[names[i]].renderer;
      if (renderer && typeof renderer.resize === "function") {
        try {
          renderer.resize();
        } catch (e) {
          /* isolated */
        }
      }
    }
    try {
      this._adapter.resize();
    } catch (e) {
      /* isolated */
    }
  };

  // ----------------------------------------------------------------------------------------- disposal

  /**
   * Disposal (record §5.4/§5.5): instance-owned streams closed, timers cleared, pending callbacks
   * invalidated, renderers released, the container unmarked. A late event, response or timer after
   * disposal changes NOTHING.
   */
  DashboardInstance.prototype.dispose = function () {
    this._dispose("host");
  };

  DashboardInstance.prototype._dispose = function (reason) {
    if (this._disposed) return;
    var refreshIds = Object.keys(this._refreshes);
    for (var i = 0; i < refreshIds.length; i++) {
      var refresh = this._refreshes[refreshIds[i]];
      if (!refresh.ended) {
        refresh.ended = true;
        // Best-effort server cancellation, never awaited (§5.6: cleanup never waits).
        this._callEvenDisposed(this._abortPath(refresh.id), { instance_id: this._instanceId }).catch(function () {});
      }
    }
    var streamIds = Object.keys(this._streams);
    for (var s = 0; s < streamIds.length; s++) this._streams[streamIds[s]].close();
    this._streams = {};
    if (this._lock) {
      this._clearLockTimer(this._lock);
      this._lock.finished = true;
      this._lock = null;
    }
    var names = Object.keys(this._occurrences);
    for (var n = 0; n < names.length; n++) {
      var renderer = this._occurrences[names[n]].renderer;
      if (renderer && typeof renderer.dispose === "function") {
        try {
          renderer.dispose();
        } catch (e) {
          /* isolated */
        }
      }
    }
    try {
      this._adapter.dispose();
    } catch (e) {
      /* isolated */
    }
    this._disposed = true;
    if (this._init.container && this._init.container.removeAttribute) {
      this._init.container.removeAttribute(MOUNTED_ATTRIBUTE);
    }
    this._occurrences = {};
    this._callbacks = { edit: [], commit: [], action: [] };
    if (!this._ready) this._ready = Promise.reject(this._fail("dashboard.disposed", "disposed during bootstrap"));
  };

  DashboardInstance.prototype._closeStream = function (refreshId) {
    var stream = this._streams[refreshId];
    if (stream) {
      stream.close();
      delete this._streams[refreshId];
    }
  };

  // ---------------------------------------------------------------------------------------- mounting

  /** The adapter mounts each occurrence's renderer (§10.3) and answers a per-occurrence handle. */
  DashboardInstance.prototype._mountOccurrences = function () {
    var self = this;
    var visualizations = (this._config && this._config.visualizations) || [];
    var mounts = visualizations.map(function (occurrence) {
      return Promise.resolve(
        self._adapter.mountVisualization(occurrence, occurrence.renderer),
      ).then(function (handle) {
        if (!handle || typeof handle.renderData !== "function") {
          throw self._fail("adapter.mount_incomplete", "mountVisualization returned no renderer for '" + occurrence.name + "'", {
            name: occurrence.name,
          });
        }
        self._occurrences[occurrence.name] = {
          occurrence: occurrence,
          renderer: handle,
          owner: null,
          status: "ready",
          stale: false,
        };
      });
    });
    return Promise.all(mounts).then(function () {
      return undefined;
    });
  };

  // ----------------------------------------------------------------------------------------- init

  /**
   * `init` (§10.2). Returns the instance immediately; `instance.ready` resolves after the barrier.
   * Re-initialising an OWNED container is refused (`DashboardAlreadyMounted`).
   */
  function init(options) {
    if (!isPlainObject(options)) throw DashboardError("init.invalid", "init requires an options object");
    if (!isPlainObject(options.server)) throw DashboardError("init.invalid", "init requires server");
    if (!isPlainObject(options.dashboard)) throw DashboardError("init.invalid", "init requires dashboard");
    if (!options.container || typeof options.container.setAttribute !== "function") {
      throw DashboardError("init.invalid", "init requires a container element");
    }
    if (options.container.getAttribute(MOUNTED_ATTRIBUTE)) {
      var mounted = new Error("the container already mounts a dashboard instance");
      mounted.name = "DashboardAlreadyMounted";
      throw mounted;
    }
    if (options.dashboard.version !== "released") {
      // The server serves the current release only (spec §18.12: no version route exists); a number
      // is refused with a clear error until the preview lane (L4) defines it.
      throw DashboardError("init.version_unsupported", 'this runtime serves version "released" only', {
        requested: String(options.dashboard.version),
      });
    }
    var credentials = options.server.credentials;
    if (credentials !== "session" && !isPlainObject(credentials)) {
      throw DashboardError("init.invalid", 'credentials is "session" or { proxyBaseUrl }');
    }
    if (isPlainObject(credentials) && typeof credentials.proxyBaseUrl !== "string") {
      throw DashboardError("init.invalid", "proxy credentials require proxyBaseUrl");
    }
    validateAdapter(options.adapter);

    var env = {
      uuid: randomUuid,
      now: nowMillis,
      // A wrapper, never an alias: calling a bare `fetch` reference throws Illegal invocation
      // in the browser (the method needs its window receiver).
      fetchImpl:
        typeof fetch === "function"
          ? function (url, init) {
              return fetch(url, init);
            }
          : null,
      setTimeout: function (fn, ms) {
        return setTimeout(fn, ms);
      },
      clearTimeout: function (id) {
        clearTimeout(id);
      },
      renderers: REGISTERED_RENDERERS,
      declaredPlotlyBundles: declaredPlotlyBundles,
    };

    var instance = new DashboardInstance(options, env);
    options.container.setAttribute(MOUNTED_ATTRIBUTE, instance._instanceId);
    instance._wrapCallbacks();
    instance._bootstrap();
    return instance;
  }

  /** The `<script data-dp-plotly-bundle="2d|3d">` declarations — the host names the bundle it loaded. */
  function declaredPlotlyBundles() {
    if (typeof document === "undefined" || !document.querySelectorAll) return [];
    var tags = document.querySelectorAll("script[data-dp-plotly-bundle]");
    var out = [];
    for (var i = 0; i < tags.length; i++) out.push(tags[i].getAttribute("data-dp-plotly-bundle"));
    return out;
  }

  // --------------------------------------------------------------------- renderer registration

  var REGISTERED_RENDERERS = {};

  /**
   * Renderer registration: the first-party adapters call this at load — `kind`, the major `version`
   * the wire's `renderer.version` is matched against, and `create(context) → {renderData, renderStatus?,
   * resize?, dispose?}`. A host registers its own kind the same way, before init.
   */
  function registerRenderer(spec) {
    if (!isPlainObject(spec) || typeof spec.kind !== "string" || typeof spec.create !== "function") {
      throw DashboardError("renderer.invalid_registration", "registerRenderer requires { kind, version, create }");
    }
    REGISTERED_RENDERERS[spec.kind] = {
      kind: spec.kind,
      version: spec.version === undefined ? "1" : String(spec.version),
      create: spec.create,
    };
    return api;
  }

  /**
   * The first-party composite adapter (§10.3): the object a host passes to `init` when it wants the
   * shipped renderers. `adapters(container)` takes the SAME element init will mount — the composite
   * builds its grid into it. The contract functions are the composite's; `mountVisualization` and
   * `renderData` dispatch by the occurrence's renderer kind to the registered implementations; the
   * layout, parameters, callbacks and notifications are the composite's own (a grid host for the
   * layout, text-only controls for the parameters, `textContent` everywhere).
   */
  function adapters(container) {
    if (typeof document === "undefined") {
      throw DashboardError("adapter.no_dom", "the first-party adapter needs a DOM");
    }
    if (!container || typeof container.setAttribute !== "function") {
      throw DashboardError("adapter.no_container", "adapters(container) needs the element init will mount");
    }
    var implemented = {};
    var listeners = { edit: null, commit: null, action: null };
    var notifications = [];

    function ensure(kind) {
      var implementation = REGISTERED_RENDERERS[kind];
      if (!implementation) throw DashboardError("renderer.unsupported", "no renderer is registered for '" + kind + "'");
      return implementation;
    }

    return {
      /** The layout: a CSS grid container from the system layout (columns, breakpoint §3.2). */
      mountLayout: function (layout) {
        this.root = document.createElement("div");
        this.root.className = "dp-dashboard";
        var columns = layout && layout.columns ? layout.columns : 12;
        this.root.style.display = "grid";
        this.root.style.gridTemplateColumns = "repeat(" + Number(columns) + ", minmax(0, 1fr))";
        this.root.style.gap = "16px";
        this.grid = {};
        var items = (layout && layout.grid) || [];
        for (var i = 0; i < items.length; i++) {
          var item = items[i];
          var slot = document.createElement("div");
          slot.className = "dp-dashboard-slot";
          slot.setAttribute("data-dp-slot", item.name);
          if (typeof item.x === "number") slot.style.gridColumnStart = String(item.x + 1);
          if (typeof item.w === "number") slot.style.gridColumnEnd = "span " + Number(item.w);
          if (typeof item.y === "number") slot.style.gridRowStart = String(item.y + 1);
          if (typeof item.h === "number") slot.style.gridRowEnd = "span " + Number(item.h);
          this.root.appendChild(slot);
          this.grid[item.name] = slot;
        }
        // Occurrences and controls without a grid entry flow into the default slot order.
        this.defaultSlot = document.createElement("div");
        this.defaultSlot.className = "dp-dashboard-slot";
        this.root.appendChild(this.defaultSlot);
        this.container = container;
        container.appendChild(this.root);
        return Promise.resolve(undefined);
      },
      mountVisualization: function (occurrence, renderer) {
        try {
          var slot = this.grid && this.grid[occurrence.name] ? this.grid[occurrence.name] : this.defaultSlot;
          var host = document.createElement("div");
          host.className = "dp-dashboard-viz";
          host.setAttribute("data-dp-viz", occurrence.name);
          slot.appendChild(host);
          var implementation = ensure(renderer.kind);
          var context = {
            instanceId: null,
            host: host,
            occurrence: occurrence,
            notify: this.notify,
            signal: function (name, payload) {
              if (listeners.commit && name === "commit") listeners.commit(payload);
              if (listeners.edit && name === "edit") listeners.edit(payload);
              if (listeners.action && name === "action") listeners.action(payload);
            },
          };
          var handle = implementation.create(context);
          implemented[occurrence.name] = handle;
          return Promise.resolve(handle);
        } catch (error) {
          return Promise.reject(error);
        }
      },
      renderParameters: function (state) {
        // The parameters pane: one row per parameter — label, control, (hidden/disabled respected).
        // Round one's controls are the engine's own (text/number/select via <select>); the composite
        // keeps them text-only and reports one commit per gesture.
        this.parametersRoot = this.parametersRoot || document.createElement("div");
        this.parametersRoot.className = "dp-dashboard-parameters";
        var parent = this.container || this.defaultSlot;
        if (this.parametersRoot.parentNode !== parent) parent.insertBefore(this.parametersRoot, parent.firstChild);
        var parameters = (state && state.parameters) || [];
        var self2 = this;
        this._selectionInputs = {};
        for (var i = 0; i < parameters.length; i++) {
          var parameter = parameters[i];
          var definition = parameter.definition || {};
          var row = document.createElement("div");
          row.className = "dp-dashboard-parameter";
          row.setAttribute("data-dp-parameter", definition.name || "");
          var label = document.createElement("label");
          label.textContent = definition.label || definition.name || "";
          row.appendChild(label);
          var control = document.createElement("select");
          var options = (parameter.options || []).slice();
          for (var o = 0; o < options.length; o++) {
            var option = document.createElement("option");
            option.value = String(options[o].value);
            option.textContent = String(options[o].display_value !== undefined ? options[o].display_value : options[o].value);
            control.appendChild(option);
          }
          if (parameter.value !== undefined && parameter.value !== null) control.value = String(parameter.value);
          if (parameter.state) {
            if (parameter.state.hidden === true) row.style.display = "none";
            if (parameter.state.disabled === true) control.disabled = true;
          }
          control.addEventListener("change", (function (name, input) {
            return function () {
              if (listeners.edit && self2._gesture !== name) {
                listeners.edit({ name: name, type: "parameter" });
                self2._gesture = name;
              }
              if (listeners.commit) listeners.commit({ name: name, type: "parameter" });
              self2._gesture = null;
            };
          })(definition.name, control));
          this._selectionInputs[definition.name] = control;
          row.appendChild(control);
          this.parametersRoot.appendChild(row);
        }
        return Promise.resolve(undefined);
      },
      readSelections: function () {
        var out = {};
        var inputs = this._selectionInputs || {};
        for (var name in inputs) {
          if (Object.prototype.hasOwnProperty.call(inputs, name)) out[name] = inputs[name].value;
        }
        return out;
      },
      onEdit: function (callback) {
        listeners.edit = callback;
      },
      onCommit: function (callback) {
        listeners.commit = callback;
      },
      onAction: function (callback) {
        listeners.action = callback;
      },
      renderData: function (occurrence, refreshId, rows, bindings) {
        var handle = implemented[occurrence.name];
        if (!handle) return Promise.resolve("no-data");
        return Promise.resolve(handle.renderData(occurrence, refreshId, rows, bindings));
      },
      renderStatus: function (occurrence, status) {
        var handle = implemented[occurrence.name];
        if (handle && typeof handle.renderStatus === "function") {
          handle.renderStatus(occurrence, status);
          return;
        }
        // The composite's own status chip: accessible, not colour-only.
        var slot = this.grid && this.grid[occurrence.name] ? this.grid[occurrence.name] : this.defaultSlot;
        var host = slot.querySelector('[data-dp-viz="' + occurrence.name + '"]');
        if (!host) return;
        var chip = host.querySelector(".dp-dashboard-status");
        if (!chip) {
          chip = document.createElement("div");
          chip.className = "dp-dashboard-status";
          chip.setAttribute("role", "status");
          host.insertBefore(chip, host.firstChild);
        }
        var text = status.state;
        if (status.stale) text += " (stale)";
        if (status.reason && status.reason.code) text += ": " + status.reason.code;
        chip.textContent = text;
        chip.setAttribute("data-dp-state", status.state);
      },
      notify: function (notification) {
        notifications.push(notification);
      },
      resize: function () {},
      dispose: function () {
        for (var name in implemented) {
          if (Object.prototype.hasOwnProperty.call(implemented, name)) {
            var handle = implemented[name];
            if (handle && typeof handle.dispose === "function") handle.dispose();
          }
        }
        implemented = {};
      },
    };
  }

  var api = {
    init: init,
    registerRenderer: registerRenderer,
    adapters: adapters,
    DashboardError: DashboardError,
    isDashboardError: isDashboardError,
    /** Internal, for the node tests: the required-function list and a fresh renderer table. */
    _internal: {
      REQUIRED_ADAPTER_FUNCTIONS: REQUIRED_ADAPTER_FUNCTIONS,
      resetRenderers: function () {
        var keys = Object.keys(REGISTERED_RENDERERS);
        for (var i = 0; i < keys.length; i++) delete REGISTERED_RENDERERS[keys[i]];
      },
      renderers: function () {
        return REGISTERED_RENDERERS;
      },
      /** Swap one registration wholesale — the host's instrumentation point (a counting wrapper). */
      replaceRenderer: function (kind, spec) {
        if (!REGISTERED_RENDERERS[kind]) {
          throw DashboardError("renderer.unknown_kind", "no renderer named '" + kind + "' is registered");
        }
        return registerRenderer(Object.assign({ kind: kind }, spec));
      },
      MOUNTED_ATTRIBUTE: MOUNTED_ATTRIBUTE,
    },
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") window.DatapipelinesDashboard = api;
})();
