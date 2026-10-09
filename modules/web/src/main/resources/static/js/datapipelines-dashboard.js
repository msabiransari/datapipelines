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
 *
 * ## Fixture mode (the visualization test preview, #353)
 * `server: { fixtures: { config, results } }` swaps the TRANSPORT and nothing else: the configuration
 * read answers `fixtures.config`, a refresh's stream answers one frame sequence per target from
 * `fixtures.results[name]` (stamped with the client's own refresh id, so the freshness gate judges it
 * exactly as a live refresh), and an abort answers at once. No `fetch` is issued in this mode — no
 * cookie, no csrf header, no `/api/v1` path — and init refuses `fixtures` beside a `baseUrl` or
 * `credentials`. The instance lifecycle, the adapters, the renderers and the layout are unchanged.
 */
(function () {
  "use strict";

  var SUCCESS_SETTLE_MS = 1200;
  // #473 — how long a card keeps its loud error/abort accent before falling back to the quiet
  // issue border. The failure MESSAGE inside the card persists; only the loud border settles.
  var ERROR_ACCENT_MS = 3000;
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

  /** #374 — the value-origin words the parameter form shows (record P26's `state.origin` wire values). */
  var ORIGIN_LABELS = {
    client: "your selection",
    default: "default",
    first: "first option",
    source: "from source",
    none: "no value",
  };

  /**
   * #473 — the card's failure sentences: fixed words for the wire's refresh-reason codes
   * (the runtime's reason catalogue), falling back to the code itself. A reason's raw
   * `message` is never rendered — the same rule the chip's text has always followed.
   */
  var STATUS_CODE_TEXT = {
    timeout: "The refresh took too long",
    budget_exceeded: "The refresh exceeded its data budget",
    source_unavailable: "A data source was unavailable",
    cancelled: "The refresh was cancelled",
    render_failed: "The visualization failed to render",
    aborted: "The refresh was stopped",
  };

  /**
   * #473 — a card's heading, first non-empty wins: the occurrence's authored
   * `presentation.title`, else the pinned visualization version's `display_name`,
   * else a renderer-level label from its stored config (the KPI's `label`), else
   * the occurrence name. The fallback exists so an unnamed card is never headless.
   */
  function cardHeading(occurrence) {
    var presentationTitle = occurrence && occurrence.presentation ? occurrence.presentation.title : null;
    if (presentationTitle && String(presentationTitle).trim()) return String(presentationTitle).trim();
    if (occurrence.display_name && String(occurrence.display_name).trim()) return String(occurrence.display_name).trim();
    var configLabel = occurrence && occurrence.config ? occurrence.config.label : null;
    if (configLabel && String(configLabel).trim()) return String(configLabel).trim();
    return occurrence.name;
  }

  /** #473 — a completion frame's per-target outcomes, reduced to the outcome WORDS (`ok`/`no-data`/`error`/`abort`). */
  function targetOutcomeSummary(targets) {
    var out = {};
    for (var name in targets) {
      if (Object.prototype.hasOwnProperty.call(targets, name)) {
        out[name] = targets[name] && targets[name].outcome ? String(targets[name].outcome) : "unknown";
      }
    }
    return out;
  }

  function isPlainObject(value) {
    return !!value && typeof value === "object" && !Array.isArray(value);
  }

  /** #369 — `"released"`, or the positive integer a draft preview names. Nothing else inits. */
  function isValidVersion(value) {
    if (value === "released") return true;
    return typeof value === "number" && isFinite(value) && value > 0 && value % 1 === 0;
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

  /** The canonical key of a wire value: option identity is the TYPED JSON value, never a DOM string. */
  function wireKey(value) {
    return JSON.stringify(value === undefined ? null : value);
  }

  /**
   * A free (INPUT) control's typed read: the DOM string is parsed toward the parameter's wire type
   * and an unparsable text travels AS TEXT — the server's validator (P28) is the authority and its
   * error lands on the row. BIGINTEGER/BIGDECIMAL travel as strings by wire contract. A BOOLEAN
   * INPUT never reaches this reader: its tri-state select is read in its own branch (null stays
   * null — a two-state read would conflate the unresolved value with false).
   */
  function typedInputRead(type, control) {
    return function () {
      var text = typeof control.value === "string" ? control.value : "";
      if (text === "") return null;
      if (type === "INTEGER" || type === "DECIMAL") {
        var parsed = Number(text);
        return text.trim() !== "" && isFinite(parsed) ? parsed : text;
      }
      return text; // STRING, DATE, TIME, TIMESTAMP, BINARY, BIGINTEGER, BIGDECIMAL
    };
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
    this._fixtures = initOptions.server.fixtures || null;
    this._options = initOptions.options || {};
    this._config = null;
    this._parameters = null;
    this._baseline = null;
    this._lock = null;
    // #374 — the parameters-only entry (initParameters): a parameter SET is evaluated by id and version, with no
    // dashboard configuration behind it. `_generation` numbers every attempt so a superseded one is nameable.
    this._parametersOnly = false;
    this._generation = 0;
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

  // #369 — the draft preview's named version: `"released"` (or nothing) rides no query; a
  // positive integer rides `?version=N` on EVERY runtime path this instance builds.
  DashboardInstance.prototype._versionQuery = function () {
    var version = this._init.dashboard.version;
    return typeof version === "number" && isFinite(version) && version > 0 && version % 1 === 0
      ? "?version=" + version
      : "";
  };

  /** The controller's ONE runtime segment: /api/v1/dashboards/{id}/runtime/<segment> (+ ?version=N). */
  DashboardInstance.prototype._runtimePath = function (segment) {
    return "/api/v1/dashboards/" + encodeURIComponent(this._init.dashboard.id) + "/runtime/" + segment + this._versionQuery();
  };

  DashboardInstance.prototype._configPath = function () {
    return this._runtimePath("config");
  };

  DashboardInstance.prototype._parametersPath = function () {
    return this._runtimePath("parameters");
  };

  /** #383 — the parameters-only instance's stream route: the OBSERVED evaluation (rest-api §21.5). */
  DashboardInstance.prototype._parameterSetEvaluationsPath = function () {
    return "/api/v1/parameter-sets/" + encodeURIComponent(this._init.parameterSet.id) + "/evaluations";
  };

  /**
   * The stream route. The board refreshes ride the dashboard runtime's visualization stream; a parameters-only
   * instance streams the OBSERVED evaluation instead — the same decision `_parametersPath` makes, in the same
   * place, so the route choice lives with the other route choices and `_openStream`'s one caller is untouched.
   */
  DashboardInstance.prototype._streamPath = function () {
    if (this._parametersOnly) return this._parameterSetEvaluationsPath();
    return this._runtimePath("visualizations");
  };

  DashboardInstance.prototype._abortPath = function (refreshId) {
    return this._runtimePath("refreshes/" + encodeURIComponent(refreshId) + "/abort");
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
    if (this._fixtures) return this._fixtureCall(path);
    var self = this;
    var url = this._baseUrl() + path;
    var headers = { Accept: accept || "application/json" };
    var init = { method: body === undefined ? "GET" : "POST", headers: headers };
    if (this._proxyMode()) {
      init.credentials = "omit"; // the proxy holds the key: no cookie, no csrf header
    } else {
      init.credentials = "same-origin";
      if (body !== undefined) headers["DP-CSRF-Token"] = readCookie("dp_csrf");
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
    if (this._fixtures) return this._fixtureStream(body, handlers);
    var self = this;
    var url = this._baseUrl() + this._streamPath();
    var headers = { "Content-Type": "application/json", Accept: "text/event-stream" };
    var init = { method: "POST", headers: headers };
    if (this._proxyMode()) {
      init.credentials = "omit"; // the proxy holds the key: no cookie, no csrf header
    } else {
      init.credentials = "same-origin";
      headers["DP-CSRF-Token"] = readCookie("dp_csrf");
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

  // ------------------------------------------------------------------------------ fixture transport

  /** Fixture mode's answer to the three calls (§ Fixture mode above): config, abort — anything else is refused. */
  DashboardInstance.prototype._fixtureCall = function (path) {
    if (path === this._configPath()) return Promise.resolve(JSON.parse(JSON.stringify(this._fixtures.config)));
    if (/\/abort$/.test(path)) return Promise.resolve(null);
    return Promise.reject(this._fail("fixtures.unsupported", "the fixture transport answers no " + path));
  };

  /**
   * Fixture mode's stream: the frames a live refresh would send for the same targets — `refresh_started`, then per
   * target its data (`{bindings, rows}`), its empty state (no rows) or its refusal (`{error}`), then
   * `refresh_completed` — each carrying THIS refresh's id, delivered asynchronously like a read.
   */
  DashboardInstance.prototype._fixtureStream = function (body, handlers) {
    var results = this._fixtures.results || {};
    var names = body.scope === "targets" ? body.targets : (this._config.visualizations || []).map(function (v) {
      return v.name;
    });
    var id = body.refresh_id;
    var closed = false;
    var frames = [["refresh_started", { refresh_id: id, targets: names, sources: [], deadline_at: null }]];
    var outcomes = {};
    names.forEach(function (name) {
      var result = results[name] || { rows: 0 };
      if (result.error) {
        frames.push(["visualization_status", { refresh_id: id, name: name, type: "visualization", state: "error", stage: "transform", reason: result.error }]);
        outcomes[name] = { outcome: "error", stage: "transform", reason: result.error };
      } else if (result.rows > 0) {
        frames.push(["visualization_data", { refresh_id: id, name: name, type: "visualization", bindings: result.bindings || {}, rows: result.rows, bytes: 0 }]);
        outcomes[name] = { outcome: "ok" };
      } else {
        frames.push(["visualization_status", { refresh_id: id, name: name, type: "visualization", state: "no-data" }]);
        outcomes[name] = { outcome: "no-data" };
      }
    });
    frames.push(["refresh_completed", { refresh_id: id, status: "COMPLETED", targets: outcomes }]);
    var settled = Promise.resolve().then(function () {
      frames.forEach(function (frame) {
        if (!closed) handlers.onFrame(frame[0], frame[1]);
      });
      if (!closed) handlers.onEnd();
    });
    return {
      close: function () {
        closed = true;
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

  /**
   * #374 — the parameters-only barrier: the same ordered steps minus the dashboard's (no configuration read, no
   * renderer judgement, no occurrences, no actions). The layout is mounted (the composite builds `container` there), then
   * the FIRST evaluation — selections `{}` — is awaited and rendered. Resolves `ready`; a failure is published
   * VERBATIM (the server's code and message) and disposes the instance, exactly as the board's bootstrap does.
   */
  DashboardInstance.prototype._bootstrapParameters = function () {
    var self = this;
    var steps = [];
    this._readySteps = steps;
    // The lock window a parameter attempt is given: the option, else the dashboard runtime's own default.
    var lockSeconds = this._options.parameterLockSeconds;
    this._config = {
      timeouts: { parameter_lock_seconds: typeof lockSeconds === "number" && lockSeconds > 0 ? lockSeconds : 30 },
      parameter_set: { name: this._init.parameterSet.id },
      layout: {},
      visualizations: [],
      actions: [],
    };
    this._ready = (async function () {
      steps.push("adapter_validated");
      await withDeadline(Promise.resolve(self._adapter.mountLayout({})), self._renderTimeoutMs(), function () {
        return self._fail("render.layout_timeout", "the host did not mount the layout in time");
      });
      steps.push("layout_mounted");
      var evaluated = await self._evaluateParameters("bootstrap");
      steps.push("parameters_rendered");
      self._parameters = evaluated;
      self._baseline = { selections: self._snapshotSelections(evaluated), revision: evaluated.parameter_revision };
      self._bootstrapped = true;
      return undefined;
    })();
    this._ready.catch(function (error) {
      self._publishNotification({
        scope: "bootstrap",
        severity: "error",
        code: isDashboardError(error) ? error.code : "bootstrap.failed",
        message: isDashboardError(error) && error.message ? error.message : "the parameter set could not be evaluated",
        retryable: !!(isDashboardError(error) && error.retryable),
        recover: isDashboardError(error) && error.retryable ? "retry" : null,
      });
      self._dispose("bootstrap_failed");
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

  /**
   * The evaluate response's shape is the WRITER's (EvaluateResponseJson): every parameter is a flat
   * object — the stored definition's fields (`name`, `label`, `type`, `presentation`, …) beside
   * `dependents` and `state` — and the value lives at `state.value` in its WIRE type (a JSON number
   * for INTEGER, a string for BIGDECIMAL, an array for a MULTI, null unresolved). `values` at the
   * response's top level is the same payload keyed by name.
   */
  DashboardInstance.prototype._snapshotSelections = function (evaluated) {
    var out = {};
    var parameters = (evaluated && evaluated.parameters) || [];
    for (var i = 0; i < parameters.length; i++) {
      var parameter = parameters[i];
      if (parameter && parameter.name) {
        var value = parameter.state ? parameter.state.value : undefined;
        out[parameter.name] = value === undefined ? null : value;
      }
    }
    return out;
  };

  /**
   * One parameter attempt (§8.2): the FULL selections — the server's current state with the host's
   * committed values — under `intent`. The lock is acquired before the call and released only by the
   * accepted response (applied and rendered), its deadline, or disposal — never by a late timer.
   * The deadline is the ABSOLUTE clock: both admission points re-read it, so an overdue response
   * or render is refused even when the timer's callback has not run yet.
   */
  DashboardInstance.prototype._evaluateParameters = function (intent) {
    if (this._lock) {
      // #374 — a parameter SET's form SUPERSEDES: the person's newest selection is the one worth answering, and a
      // refused change would be a lost one. The pending attempt is finished here, so its response (n) arriving after
      // this one (n+1) was minted passes neither admission point and changes nothing. A dashboard keeps the refusal.
      if (!this._parametersOnly) {
        return Promise.reject(this._fail("parameters.locked", "a parameter evaluation is already pending"));
      }
      var prior = this._lock;
      prior.finished = true;
      this._clearLockTimer(prior);
      // #383 — the supersede also CLOSES the prior attempt's stream (the AbortController fires; the server's
      // disconnect grace then aborts the evaluation — the owner's §11.7 ruling over the spec's "never cancels").
      this._closeParameterStream(prior, "superseded");
      this._lock = null;
    }
    var self = this;
    var startedAt = this._env.now();
    var lockSeconds =
      this._config && this._config.timeouts && this._config.timeouts.parameter_lock_seconds
        ? this._config.timeouts.parameter_lock_seconds
        : 30;
    var attempt = {
      intent: intent,
      generation: ++this._generation,
      startedAt: startedAt,
      deadline: startedAt + lockSeconds * 1000,
      finished: false,
      timer: null,
      // #383 — the observed evaluation's identity, minted per attempt and never stored: every frame carries it and
      // a frame of ANY other id is dropped before it can touch state or DOM.
      evaluationId: null,
      stream: null,
      streamClosed: false,
      resolve: null,
      reject: null,
    };
    this._lock = attempt;
    attempt.timer = this._env.setTimeout(function () {
      self._lockExpired(attempt);
    }, attempt.deadline - startedAt);

    if (this._parametersOnly) {
      // The OBSERVED evaluation's body (rest-api §21.5): the version ALWAYS present, the WHOLE selection set
      // (P27), a fresh v4 evaluation_id per attempt and the instance id for diagnostics. The first render submits `{}`.
      attempt.evaluationId = this._env.uuid();
      return this._streamEvaluateParameters(attempt, {
        version: this._init.parameterSet.version,
        selections: this._committedSelections(),
        evaluation_id: attempt.evaluationId,
        instance_id: this._instanceId,
      });
    }
    var request = {
      configuration_id: this._config.configuration_id,
      instance_id: this._instanceId,
      selections: this._committedSelections(),
      intent: intent,
    };
    return this._call(this._parametersPath(), request).then(function (evaluated) {
      // A response arriving after its attempt expired (or was superseded) changes nothing (§5.6).
      if (attempt.finished) {
        throw self._fail("parameters.superseded", "a late parameter response after its deadline changes nothing");
      }
      // The ABSOLUTE clock is the authority, not the timer's callback: a continuation that runs
      // after the deadline but BEFORE the delayed timer fires expires the attempt here (release,
      // notification, exactly once), and the overdue response never reaches the adapter.
      if (self._env.now() > attempt.deadline) {
        self._lockExpired(attempt);
        throw self._fail("parameters.superseded", "a late parameter response after its deadline changes nothing");
      }
      return self._acceptParameterResponse(attempt, evaluated);
    });
  };

  /**
   * #383 — a parameters-only attempt as an OBSERVED evaluation (rest-api §21.5): the stream POST opens with the
   * attempt's id and the frames drive it; `evaluation_completed`'s response resolves the attempt through the SAME
   * admission path the plain evaluate used (`_acceptParameterResponse` — byte-identical semantics, the E2E proves
   * the server's side, this path proves the page applies it). The attempt owns its stream; the supersede, the
   * deadline and disposal close it, and the server's disconnect grace then aborts the evaluation.
   */
  DashboardInstance.prototype._streamEvaluateParameters = function (attempt, body) {
    var self = this;
    return new Promise(function (resolve, reject) {
      attempt.resolve = resolve;
      attempt.reject = reject;
      attempt.stream = self._openStream(body, {
        onFrame: function (event, payload) {
          self._onParameterFrame(attempt, event, payload);
        },
        onRevoked: function () {
          self._onParameterStreamRevoked(attempt);
        },
        onEnd: function () {
          self._onParameterStreamEnd(attempt);
        },
        onFailure: function (error) {
          self._onParameterStreamFailure(attempt, error);
        },
      });
    });
  };

  /**
   * The host's optional witness (`options.onStreamEvent`): every frame and stream fact, once,
   * isolated — the handler cannot block or throw into the runtime. Two vocabularies share the
   * hook (#473): `initParameters`'s parameter-attempt frames (`evaluation_id`, `event`,
   * `name`, `applied`, …) and the board's `scope: "dashboard"` summaries — raw refresh frames
   * (codes/states/outcome words only, never rows, bindings or raw error payloads), the card's
   * rendered/stale client facts, abort intents, and the deduped notifications. A frame of an
   * unknown or already-retired refresh is recorded with `applied: false`.
   */
  DashboardInstance.prototype._streamEvent = function (info) {
    var host = this._options.onStreamEvent;
    if (typeof host !== "function") return;
    try {
      host(info);
    } catch (e) {
      /* the host's handler failing is the host's business */
    }
  };

  /** Closes the attempt's stream once, announcing the close (the AbortController firing is a fact the page logs). */
  DashboardInstance.prototype._closeParameterStream = function (attempt, reason) {
    if (!attempt.stream || attempt.streamClosed) return;
    attempt.streamClosed = true;
    this._streamEvent({ evaluation_id: attempt.evaluationId, event: "stream_closed", reason: reason, applied: false });
    attempt.stream.close();
  };

  /**
   * One stream frame of a parameters-only attempt. The SUPERSEDE rule (§6.2, the plant-7 defence): a frame whose
   * `evaluation_id` is not the CURRENT attempt's is dropped before any state or DOM change — the current attempt
   * is the lock's, so a frame of an attempt the deadline released (its stream not yet closed) or of a superseded
   * generation changes nothing. The page's graph states are the page's business: this path ends the attempt on
   * the terminal frames and hands every applied frame to the host's witness.
   */
  DashboardInstance.prototype._onParameterFrame = function (attempt, event, payload) {
    if (this._disposed) return;
    var lock = this._lock;
    var currentId = lock && lock.evaluationId ? lock.evaluationId : null;
    var applied = currentId !== null && !!payload && payload.evaluation_id === currentId;
    this._streamEvent({
      evaluation_id: payload ? payload.evaluation_id : null,
      event: event,
      name: payload ? payload.parameter : undefined,
      code: payload && payload.code ? payload.code : undefined,
      detail: payload && payload.detail ? payload.detail : undefined,
      response: event === "evaluation_completed" && payload ? payload.response : undefined,
      applied: applied,
    });
    if (!applied) return; // dropped BEFORE any state or DOM change
    if (event === "evaluation_completed") {
      this._completeParameterStream(attempt, payload.response);
      return;
    }
    if (event === "evaluation_failed") {
      this._failParameterStream(attempt, payload.code);
    }
  };

  /**
   * The terminal frame on success: close the stream (nothing follows a terminal frame), then the plain path's
   * admission — the absolute clock first (an overdue completion expires the attempt and changes nothing), else
   * the response resolves through `_acceptParameterResponse` exactly as the plain evaluate's did.
   */
  DashboardInstance.prototype._completeParameterStream = function (attempt, response) {
    var self = this;
    this._closeParameterStream(attempt, "terminal");
    if (attempt.finished) return;
    if (this._env.now() > attempt.deadline) {
      this._lockExpired(attempt);
      if (attempt.reject) {
        attempt.reject(self._fail("parameters.superseded", "a late parameter response after its deadline changes nothing"));
      }
      return;
    }
    this._acceptParameterResponse(attempt, response).then(attempt.resolve, attempt.reject);
  };

  /**
   * The terminal frame on failure: the attempt ends with the catalogued code — the plain path's refusal shape
   * (the host shows the code verbatim). The applied state is unchanged and stays shown; the lock holds until its
   * deadline (or the next attempt's supersede), so a half-known state is never submitted silently.
   */
  DashboardInstance.prototype._failParameterStream = function (attempt, code) {
    this._closeParameterStream(attempt, "terminal");
    if (attempt.reject) {
      attempt.reject(this._fail(code || "parameters.failed", code || "the parameter evaluation failed"));
    }
  };

  /** The stream ended WITHOUT a terminal frame — the dashboards §6.6 transport-failure path: content is retained, the lock is released by the deadline, never earlier. */
  DashboardInstance.prototype._onParameterStreamEnd = function (attempt) {
    if (this._disposed || attempt.finished || this._lock !== attempt) return;
    this._closeParameterStream(attempt, "ended");
    this._publishNotification({
      scope: "parameters",
      severity: "error",
      code: "transport.disconnected",
      message: "the connection to the evaluation was lost",
      retryable: true,
      recover: "retry",
    });
  };

  /** The authority guard cut the READ (`: revoked`): the runtime's revoked notice — content kept, retry offered. */
  DashboardInstance.prototype._onParameterStreamRevoked = function (attempt) {
    if (this._disposed || attempt.finished || this._lock !== attempt) return;
    this._closeParameterStream(attempt, "revoked");
    this._publishNotification({
      scope: "parameters",
      severity: "error",
      code: "stream.revoked",
      message: "the session lost its authority to read this evaluation",
      retryable: true,
      recover: "retry",
    });
  };

  /** The stream failed at the transport (or the POST was refused): the attempt rejects; the host's notification path shows it. */
  DashboardInstance.prototype._onParameterStreamFailure = function (attempt, error) {
    if (this._disposed || attempt.finished || this._lock !== attempt) return;
    this._closeParameterStream(attempt, "failed");
    if (attempt.reject) attempt.reject(error);
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

  /**
   * The accepted response (§5.6's order, corrected 2026-09-30): the gate releases only AFTER the
   * host has applied the state — the lock, its deadline and the attempt's timer stay LIVE through
   * the asynchronous `renderParameters`. Before any runtime state changes, the attempt is
   * re-validated: liveness, its finished flag (the deadline may have fired mid-render), LOCK
   * OWNERSHIP (a newer attempt may hold it) and the ABSOLUTE clock (a render that resolved after
   * the deadline commits nothing — L3a-c). A late render — after timeout, replacement or
   * disposal — changes nothing and releases nothing it does not own. A synchronous throw and a
   * rejected render promise follow the same bounded path: the attempt terminates exactly once, the
   * gate releases, and a recoverable host-render failure is published.
   */
  DashboardInstance.prototype._acceptParameterResponse = function (attempt, evaluated) {
    var self = this;
    var render;
    try {
      render = Promise.resolve(this._adapter.renderParameters(evaluated));
    } catch (error) {
      render = Promise.reject(error);
    }
    return render.then(
      function () {
        if (self._disposed || attempt.finished || self._lock !== attempt) {
          throw self._fail("parameters.superseded", "a late parameter render changes nothing");
        }
        // The absolute deadline again, at the COMMIT: a render that resolved after the deadline
        // installs nothing and clears no newer lock — the attempt expires here (release,
        // notification, exactly once) and the timer becomes a no-op. An arrival exactly AT the
        // deadline is still admitted; strictly after it, the attempt is superseded.
        if (self._env.now() > attempt.deadline) {
          self._lockExpired(attempt);
          throw self._fail("parameters.superseded", "a late parameter render changes nothing");
        }
        attempt.finished = true;
        self._clearLockTimer(attempt);
        self._lock = null;
        self._parameters = evaluated;
        self._baseline = { selections: self._snapshotSelections(evaluated), revision: evaluated.parameter_revision };
        // #374 — the form's failure notice is a STATE, not an event: an applied response retires it, so the same
        // failure twice in a row (with a success between) is shown twice rather than deduplicated into silence.
        if (self._parametersOnly) self._notifications = {};
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
      },
      function (error) {
        if (!attempt.finished && self._lock === attempt) {
          attempt.finished = true;
          self._clearLockTimer(attempt);
          self._lock = null;
          self._publishNotification({
            scope: "parameters",
            severity: "error",
            code: "parameters.render_failed",
            message: "the host failed to render the parameter state",
            retryable: true,
            recover: "retry",
          });
        }
        throw isDashboardError(error)
          ? error
          : self._fail("parameters.render_failed", "the host failed to render the parameter state");
      },
    );
  };

  /**
   * The deadline expired — by the timer's callback OR an admission check's absolute-clock reading,
   * whichever notices first: release, publish, exactly once (a second caller is a no-op) — never
   * awaiting it (§5.6).
   */
  DashboardInstance.prototype._lockExpired = function (attempt) {
    if (attempt.finished) return;
    attempt.finished = true;
    if (this._lock === attempt) this._lock = null;
    // #383 — the expired attempt's stream closes with it: the deadline releases the lock and stops the frames
    // (the evaluation itself runs on server-side; the grace governs its abort, as for every disconnect).
    this._closeParameterStream(attempt, "expired");
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

  /**
   * The one gate (§5.6): refused while a parameter attempt is pending — never queued — and refused
   * while the applied state is INVALID (the writer answers `valid: false` when any parameter
   * carries an error): submission stays blocked until the state is valid again, which a commit's
   * re-evaluation restores.
   */
  DashboardInstance.prototype._requireActionGate = function () {
    if (this._disposed) throw this._fail("dashboard.disposed", "the instance is disposed");
    if (this._lock) throw this._fail("actions.locked", "an action is refused while a parameter evaluation is pending");
    if (!this._bootstrapped) throw this._fail("actions.not_ready", "the instance is not ready");
    if (this._parameters && this._parameters.valid === false) {
      throw this._fail("parameters.invalid", "the parameter state is invalid; submission stays blocked until it is valid again");
    }
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
    this._refreshes[refreshId] = { id: refreshId, targets: names, ended: false, abortAcked: false, abortRequested: false };
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
    // #473 — the witness's render outcomes: the two card facts NO wire frame carries. A rendered
    // visualization is confirmed only here (the render is asynchronous); staleness is the client's
    // freshness gate. Everything else a card shows arrives as a frame the _onFrame witness already
    // recorded — this must not double-report it.
    if (status.state === "success") {
      this._streamEvent({ scope: "dashboard", event: "visualization_rendered", name: name, refresh_id: status.refreshId || null, applied: true });
    } else if (status.state === "ready" && status.stale === true) {
      this._streamEvent({ scope: "dashboard", event: "visualization_stale", name: name, refresh_id: status.refreshId || null, applied: true });
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
    // #473 — the witness sees EVERY frame once, before any ownership gate: a frame of an unknown
    // refresh (another instance's) or of a run this instance already retired is recorded with
    // applied=false and can never alter state. The summary carries codes, states, stages, names and
    // per-target outcome WORDS only — never rows, bindings, SQL, or a raw error payload.
    if (event === "visualization_data") {
      this._streamEvent({
        scope: "dashboard",
        event: event,
        name: payload ? payload.name : undefined,
        refresh_id: refreshId || null,
        applied: !!(refresh && !refresh.ended),
      });
    } else {
      this._streamEvent({
        scope: "dashboard",
        event: event,
        name: payload ? payload.name : undefined,
        state: payload ? payload.state : undefined,
        code: payload && payload.error && payload.error.code ? payload.error.code : undefined,
        stage: payload ? payload.stage : undefined,
        status: event === "refresh_completed" && payload ? payload.status : undefined,
        outcomes: event === "refresh_completed" && payload && payload.targets ? targetOutcomeSummary(payload.targets) : undefined,
        refresh_id: refreshId || null,
        applied: !!(refresh && !refresh.ended),
      });
    }
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
      // The REAL wire's target outcomes (RefreshJob's TargetOutcome): "ok" | "no-data" | "error" |
      // "abort". "ok" — the server delivered the data — behaves like a data frame already applied:
      // completion must not clobber the state the data frame set (or is about to set, the render
      // being asynchronous). A delivered "rendered" is tolerated for older fixtures, never sent.
      if (outcome.outcome === "ok" || outcome.outcome === "rendered") {
        // A target the server reports ok was never server-aborted (an aborted target's outcome is
        // "abort"): an abort chip still standing at completion is this client's own optimistic render
        // from an abort whose confirmation never decided it (#356) — the terminal frame's truth
        // restores the delivered state.
        var occurrence = this._occurrences[name];
        if (occurrence && occurrence.status === "abort") {
          this._renderOccurrenceStatus(name, { state: "success", stale: false, reason: null, refreshId: refresh.id });
          this._scheduleSuccessSettle(name, refresh.id);
        }
        continue;
      }
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
      // An "abort" chip is a target still waiting for the server's word (#356: the click no longer ends the
      // refresh), so a dropped stream lists it as pending with the rest — never a chip stuck on "abort".
      if (occurrence.status === "in-progress" || occurrence.status === "success" || occurrence.status === "abort") {
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
   * §8.4: an authenticated intent keyed by the refresh id. The POST is asynchronous and its answer is
   * the honesty boundary (#356): a 202 means explicit cancellation was recorded (`abortAcked`), a 404
   * means it was NOT — the chips say so ("abort not confirmed") and the terminal frame, or the
   * stream's end, decides what really happened. The stream stays open either way: the server's own
   * terminal frame is the truth this runtime renders, never its own click.
   */
  DashboardInstance.prototype.abort = function (refreshId) {
    var refresh = this._refreshes[refreshId];
    if (!refresh || refresh.ended || refresh.abortRequested) return Promise.resolve({ abort_requested: false });
    refresh.abortRequested = true;
    // #473 — the click is a client fact the wire never sends: the dock records the intent, and the
    // acked / not-acked outcome follows (acked here; not-acked as the `abort.not_acked` notification).
    this._streamEvent({ scope: "dashboard", event: "abort_requested", refresh_id: refreshId, applied: true });
    for (var i = 0; i < refresh.targets.length; i++) {
      var name = refresh.targets[i];
      if (this._owns(name, refreshId)) {
        this._renderOccurrenceStatus(name, {
          state: "abort",
          stale: false,
          reason: { code: "abort.requested" },
          refreshId: refreshId,
        });
      }
    }
    var self = this;
    return this._call(this._abortPath(refreshId), { instance_id: this._instanceId }).then(
      function () {
        refresh.abortAcked = true;
        self._streamEvent({ scope: "dashboard", event: "abort_acked", refresh_id: refreshId, applied: true });
        return { abort_requested: true };
      },
      function (error) {
        // Not recorded: the chips carry the honest reason and the intent may be asked again. A 404
        // (another person's or a finished refresh) publishes nothing — the finished-refresh idempotence;
        // any other failure is reported as the unconfirmed abort it is.
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
        if (!refresh.ended) {
          refresh.abortRequested = false;
          for (var j = 0; j < refresh.targets.length; j++) {
            var target = refresh.targets[j];
            if (self._owns(target, refreshId)) {
              self._renderOccurrenceStatus(target, {
                state: "abort",
                stale: false,
                reason: { code: "abort.not_confirmed" },
                refreshId: refreshId,
              });
            }
          }
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
    // An invalid state is also a reason to re-evaluate: the commit's fresh selections are the way
    // back to a valid state (§5.6 — submission stays blocked until then).
    var invalid = this._parameters.valid === false;
    // #374 — a parameter SET re-evaluates on EVERY commit: the whole selection set goes to the server whatever
    // changed (P27), and an INPUT's own validation answer arrives the same way a parent's re-resolution does.
    if (this._parametersOnly) {
      this._safeEvaluate(null, "parent_change");
      return;
    }
    if (parents.indexOf(name) !== -1 || bound || invalid) {
      this._safeEvaluate(bound && !invalid ? bound.action : null, invalid ? "retry" : "parent_change");
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
  DashboardInstance.prototype._safeEvaluate = function (actionName, intent) {
    var self = this;
    (async function () {
      try {
        await self._evaluateParameters(intent || "parent_change");
      } catch (error) {
        if (isDashboardError(error) && (error.code === "parameters.superseded" || error.code === "parameters.locked")) return;
        self._publishNotification({
          scope: "parameters",
          severity: "error",
          code: error && error.code ? error.code : "parameters.failed",
          // #374 — the form shows the SERVER's code and message as received; the dashboard's generic sentence stays.
          message: self._parametersOnly && error && error.message ? error.message : "the parameter evaluation failed",
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
    // #473 — the witness rides the SAME deduped, enriched record the other sinks get: failures,
    // connection losses, abort outcomes and recovery offers reach the dock exactly once. The
    // message is the runtime's own fixed sentence — never a raw error body.
    this._streamEvent({
      scope: "dashboard",
      event: "notification",
      severity: enriched.severity,
      code: enriched.code,
      message: enriched.message,
      recover: enriched.recover,
      pending: enriched.pending,
      refresh_id: enriched.refreshId,
      applied: true,
    });
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
    var stateAtStart = this._parameters;
    var render;
    try {
      render = Promise.resolve(this._adapter.renderParameters(restored));
    } catch (error) {
      render = Promise.reject(error);
    }
    return render.then(function () {
      // Ownership: if an evaluation was accepted while the reset's render ran, the NEWER applied
      // state owns the board — the baseline's values must not clobber its revision.
      if (self._disposed || self._parameters !== stateAtStart) return undefined;
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
      var name = parameter && parameter.name;
      if (name && Object.prototype.hasOwnProperty.call(baseline, name) && parameter.state) {
        parameter.state.value = baseline[name];
      }
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
      // #383 — a parameters-only attempt's stream closes with the instance (the page left; the grace decides).
      this._closeParameterStream(this._lock, "disposed");
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
    var fixtures = options.server.fixtures;
    if (fixtures !== undefined) {
      if (!isPlainObject(fixtures) || !isPlainObject(fixtures.config)) {
        throw DashboardError("init.invalid", "fixtures require a config object");
      }
      if (options.server.baseUrl !== undefined || options.server.credentials !== undefined) {
        throw DashboardError("init.invalid", "fixture mode names no server: no baseUrl, no credentials");
      }
    } else if (!isValidVersion(options.dashboard.version)) {
      // "released" serves the current release; since #369 a positive INTEGER names the version
      // the draft preview is looking at (the server refuses a version the caller may not see).
      // Anything else is refused with a clear error naming what this runtime serves.
      throw DashboardError("init.version_unsupported", 'this runtime serves version "released" or a positive integer version number', {
        requested: String(options.dashboard.version),
      });
    }
    var credentials = options.server.credentials;
    if (fixtures === undefined && credentials !== "session" && !isPlainObject(credentials)) {
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

  /**
   * #374 — `initParameters` (the parameter-set workspace's entry, workspace spec §6.3): the SAME instance machinery
   * (adapter contract, attempt generation, lock, absolute deadline, supersede-by-newest) pointed at a parameter SET
   * instead of a dashboard, so there is one renderer and one copy of the attempt logic. Differences from `init`,
   * all of them narrowings: `parameterSet: { id, version }` replaces `dashboard` (the version is a positive integer,
   * ALWAYS — a form never relies on the server's served-version default), and there is no configuration, no
   * visualization and no action. #383 — the one call is the OBSERVED evaluation `POST
   * /api/v1/parameter-sets/{id}/evaluations` (rest-api §21.5) with `{ version, selections, evaluation_id,
   * instance_id }`: the cascade arrives as frames (`evaluation_started` … `evaluation_completed`), a frame whose
   * `evaluation_id` is not the current attempt's is dropped before it touches anything, `evaluation_completed`'s
   * response resolves the attempt exactly as the plain evaluate's did, a superseded attempt's stream is closed
   * (the server's disconnect grace then aborts it), and a stream that ends without a terminal frame is the
   * dashboards §6.6 transport-failure path. Credentials are the session's, exactly as `init` carries them (the
   * double-submit CSRF cookie read by `_openStream`); a proxy or fixture transport is refused — this entry has
   * neither. `options.onStreamEvent(info)` is the host's optional frame witness (every frame and stream fact,
   * `{evaluation_id, event, name?, applied, …}`) for the page's frame log. Re-initialising an OWNED container
   * is refused (`DashboardAlreadyMounted`).
   */
  function initParameters(options) {
    if (!isPlainObject(options)) throw DashboardError("init.invalid", "initParameters requires an options object");
    if (!isPlainObject(options.server)) throw DashboardError("init.invalid", "initParameters requires server");
    if (!isPlainObject(options.parameterSet) || typeof options.parameterSet.id !== "string" || options.parameterSet.id === "") {
      throw DashboardError("init.invalid", "initParameters requires parameterSet.id");
    }
    var version = options.parameterSet.version;
    if (typeof version !== "number" || !isFinite(version) || version < 1 || version % 1 !== 0) {
      throw DashboardError("init.version_unsupported", "initParameters requires a positive integer parameterSet.version", {
        requested: String(version),
      });
    }
    if (!options.container || typeof options.container.setAttribute !== "function") {
      throw DashboardError("init.invalid", "initParameters requires a container element");
    }
    if (options.container.getAttribute(MOUNTED_ATTRIBUTE)) {
      var mounted = new Error("the container already mounts a dashboard instance");
      mounted.name = "DashboardAlreadyMounted";
      throw mounted;
    }
    if (options.server.credentials !== "session" || options.server.fixtures !== undefined) {
      throw DashboardError("init.invalid", 'initParameters takes credentials: "session" and no fixtures');
    }
    validateAdapter(options.adapter);
    var env = {
      uuid: randomUuid,
      now: nowMillis,
      fetchImpl: typeof fetch === "function" ? function (url, init) { return fetch(url, init); } : null,
      setTimeout: function (fn, ms) { return setTimeout(fn, ms); },
      clearTimeout: function (id) { clearTimeout(id); },
      renderers: REGISTERED_RENDERERS,
      declaredPlotlyBundles: declaredPlotlyBundles,
    };
    var instance = new DashboardInstance(options, env);
    instance._parametersOnly = true;
    options.container.setAttribute(MOUNTED_ATTRIBUTE, instance._instanceId);
    instance._wrapCallbacks();
    instance._bootstrapParameters();
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

  // ------------------------------------------------------------- the composite's host-width rule (#412)

  /** The spec's §3.2: below `layout.breakpoint_px` every item spans the full width in grid order. */
  var DEFAULT_BREAKPOINT_PX = 640;

  /** The server's range (DashboardRules: 1..MAX_BREAKPOINT_PX); a value outside it never reaches a query. */
  var MIN_BREAKPOINT_PX = 1;
  var MAX_BREAKPOINT_PX = 10000;

  /** The grid's gap: the design system's spacing step, inline (CSSOM is CSP-clean); `1rem` where no tokens load. */
  var GRID_GAP = "var(--space-4, 1rem)";

  /**
   * The breakpoint as a NUMBER, clamped to the server's range: absent (the wire omits a null) or not
   * a finite number is the default. Only this number is ever written into a media query.
   */
  function layoutBreakpointPx(layout) {
    var value = layout ? layout.breakpoint_px : undefined;
    if (typeof value !== "number" || !isFinite(value)) return DEFAULT_BREAKPOINT_PX;
    return Math.min(MAX_BREAKPOINT_PX, Math.max(MIN_BREAKPOINT_PX, Math.round(value)));
  }

  /**
   * Defers ResizeObserver work to a frame: changing the observed host's layout inside the callback
   * can cause another observation. Hosts without requestAnimationFrame use a task instead.
   */
  function scheduleResize(callback) {
    if (typeof window !== "undefined" && window && typeof window.requestAnimationFrame === "function") {
      window.requestAnimationFrame(callback);
      return;
    }
    setTimeout(callback, 0);
  }

  /**
   * Each slot's placement. Stored: the item's own column and row span. Collapsed: every item spans all
   * `columns`, stacked in GRID ORDER — by row (`y`), then column (`x`), the configuration's order
   * breaking a tie — each keeping its own row span `h`, so the row unit and every slot's height stand.
   */
  function placeGridSlots(items, slots, defaultSlot, columns, collapsed) {
    // The default slot (occurrences with no grid entry) auto-places after the items; collapsed, it
    // spans the full width like them. Empty, dashboards.css hides it either way.
    defaultSlot.style.gridColumnEnd = collapsed ? "span " + Number(columns) : "";
    if (!collapsed) {
      for (var i = 0; i < items.length; i++) {
        var item = items[i];
        var style = slots[i].style;
        style.gridColumnStart = typeof item.x === "number" ? String(item.x + 1) : "";
        style.gridColumnEnd = typeof item.w === "number" ? "span " + Number(item.w) : "";
        style.gridRowStart = typeof item.y === "number" ? String(item.y + 1) : "";
        style.gridRowEnd = typeof item.h === "number" ? "span " + Number(item.h) : "";
      }
      return;
    }
    var order = [];
    for (var j = 0; j < items.length; j++) order.push(j);
    order.sort(function (a, b) {
      var ya = Number(items[a].y) || 0;
      var yb = Number(items[b].y) || 0;
      if (ya !== yb) return ya - yb;
      var xa = Number(items[a].x) || 0;
      var xb = Number(items[b].x) || 0;
      return xa !== xb ? xa - xb : a - b;
    });
    var row = 1;
    for (var k = 0; k < order.length; k++) {
      var stacked = items[order[k]];
      var rows = typeof stacked.h === "number" && stacked.h > 0 ? Number(stacked.h) : 1;
      var placed = slots[order[k]].style;
      placed.gridColumnStart = "1";
      placed.gridColumnEnd = "span " + Number(columns);
      placed.gridRowStart = String(row);
      placed.gridRowEnd = "span " + rows;
      row += rows;
    }
  }

  /**
   * The first-party composite adapter (§10.3): the object a host passes to `init` when it wants the
   * shipped renderers. `adapters(container)` takes the SAME element init will mount — the composite
   * builds its grid into it. `adapters(container, { parametersContainer })` (#473) additionally
   * names the element that hosts the parameters pane, so a page can keep the controls in their own
   * panel outside the grid container; absent, the pane mounts into `container` as before. The
   * contract functions are the composite's; `mountVisualization` and `renderData` dispatch by the
   * occurrence's renderer kind to the registered implementations; the layout, parameters, card
   * presentation, callbacks and notifications are the composite's own (a grid host for the layout,
   * text-only controls for the parameters, `textContent` everywhere).
   */
  function adapters(container, adapterOptions) {
    // #374 — `provenance: true` (the parameter-set workspace's form) adds each row's value ORIGIN and RESET marks;
    // absent, the board's rows render exactly as before.
    var provenance = !!(adapterOptions && adapterOptions.provenance === true);
    // #473 — `parametersContainer` (optional) hosts the parameters pane OUTSIDE the layout container:
    // the board page keeps the filters in their own panel, so the grid container's measurements and
    // the parameters pane's position are independent. Absent, the pane mounts into `container` —
    // exactly the pre-#473 behaviour (first child, before the grid).
    var parametersContainer =
      adapterOptions && adapterOptions.parametersContainer ? adapterOptions.parametersContainer : null;
    if (typeof document === "undefined") {
      throw DashboardError("adapter.no_dom", "the first-party adapter needs a DOM");
    }
    if (!container || typeof container.setAttribute !== "function") {
      throw DashboardError("adapter.no_container", "adapters(container) needs the element init will mount");
    }
    if (parametersContainer && typeof parametersContainer.setAttribute !== "function") {
      throw DashboardError(
        "adapter.no_container",
        "adapters({parametersContainer}) needs the element that hosts the parameters pane",
      );
    }
    var implemented = {};
    var listeners = { edit: null, commit: null, action: null };
    var notifications = [];
    // Radio groups are named PER ADAPTER: two composites in one document (two boards, a preview
    // beside a board) must never share a native radio group — one instance's pick would clear the
    // other's. The token is stable for the adapter's lifetime, so re-renders regroup correctly.
    // It also namespaces the cards' aria ids (#473): two boards never collide.
    var radioGroupToken = randomUuid();
    // #473 — per-occurrence card DOM ({ card, body, chip, message, timer, generation, heading }),
    // keyed by occurrence name; the cards map is the ONLY lookup renderStatus needs (no selector
    // walk), and the generation counter retires a card's pending accent timer on every new status.
    var cards = {};

    /**
     * #473 — the card's status treatment. The chip keeps its element, `role=status`, text and
     * `data-dp-state` exactly as before (tests and assistive tech read them); what changes is the
     * PRESENTATION around it: a state class on the card (border colour/animation), a persistent
     * in-card message for failure/no-data/stale, and a LOUD accent border that settles after
     * ERROR_ACCENT_MS while the message stays. `ready` is visually quiet (the chip is hidden by
     * CSS), never colour-only: the message text and the chip's `data-dp-state` carry the fact.
     */
    function applyCardStatus(card, status) {
      // Any new status retires the pending accent timer of the previous one.
      card.generation += 1;
      if (card.timer) {
        clearTimeout(card.timer);
        card.timer = null;
      }
      var state = status.state;
      var stale = status.stale === true;
      var classes = "dp-dashboard-card";
      var message = "";
      var loud = null;
      if (state === "in-progress") {
        classes += " dp-dashboard-card--busy";
        card.card.setAttribute("aria-busy", "true");
        // The retained result stays visible while the new one loads — say so, rather than
        // leaving the viewer to guess whether the chart is old or new.
        message = card.body && card.body.firstChild ? "Loading — showing the previous result." : "Loading…";
      } else {
        card.card.removeAttribute("aria-busy");
        if (state === "success") {
          classes += " dp-dashboard-card--success";
          // The runtime settles success → ready after SUCCESS_SETTLE_MS; the accent rides along.
        } else if (state === "error") {
          classes += " dp-dashboard-card--error";
          loud = "dp-dashboard-card--issue";
          message = cardStatusText(status.reason, "could not load this visualization");
        } else if (state === "abort") {
          classes += " dp-dashboard-card--aborted";
          loud = "dp-dashboard-card--issue";
          message = "Refresh cancelled.";
        } else if (state === "no-data") {
          classes += " dp-dashboard-card--empty";
          message = "No data for this selection.";
        } else if (state === "ready") {
          if (stale) {
            classes += " dp-dashboard-card--stale";
            message = "Showing out-of-date data.";
          }
        }
      }
      card.card.className = classes;
      card.message.textContent = message;
      if (loud) {
        // Loud now, quiet later — but the message (and the quiet issue border) persist.
        var gen = card.generation;
        card.timer = setTimeout(function () {
          if (card.generation !== gen) return; // a newer status owns the card now
          card.timer = null;
          card.card.className = "dp-dashboard-card dp-dashboard-card--has-issue" + (stale ? " dp-dashboard-card--stale" : "");
        }, ERROR_ACCENT_MS);
      }
    }

    /** The safe failure sentence: words from a fixed dictionary plus the wire's CODE — never a raw message. */
    function cardStatusText(reason, fallback) {
      if (!reason || !reason.code) return fallback;
      var text = STATUS_CODE_TEXT[reason.code] || reason.code;
      if (reason.stage && reason.stage !== "refresh") text += " (" + reason.stage + ")";
      return text + ".";
    }

    function ensure(kind) {
      var implementation = REGISTERED_RENDERERS[kind];
      if (!implementation) throw DashboardError("renderer.unsupported", "no renderer is registered for '" + kind + "'");
      return implementation;
    }

    // The host observer mountLayout added (#412); disconnected by dispose or a subsequent mountLayout.
    var resizeObservation = null;

    function stopResizeObservation() {
      if (!resizeObservation) return;
      resizeObservation.active = false;
      resizeObservation.observer.disconnect();
      resizeObservation = null;
    }

    /** Every mounted handle re-measures its host, each isolated — one renderer's throw stops no other. */
    function resizeMounted() {
      for (var name in implemented) {
        if (!Object.prototype.hasOwnProperty.call(implemented, name)) continue;
        var handle = implemented[name];
        if (handle && typeof handle.resize === "function") {
          try {
            handle.resize();
          } catch (e) {
            /* isolated */
          }
        }
      }
    }

    return {
      /**
       * The layout: a CSS grid container from the system layout (columns, breakpoint §3.2). Below
       * `breakpoint_px` (640 when absent) every item spans the full width in grid order (#412); the
       * composite host's width decides, and a crossing re-places the slots and resizes mounted renderers.
       * A zero-width host keeps its stored grid until it is laid out. Without ResizeObserver the stored
       * grid always holds; the observer is disconnected by `dispose`.
       */
      mountLayout: function (layout) {
        this.root = document.createElement("div");
        this.root.className = "dp-dashboard";
        var columns = layout && layout.columns ? layout.columns : 12;
        this.root.style.display = "grid";
        this.root.style.gridTemplateColumns = "repeat(" + Number(columns) + ", minmax(0, 1fr))";
        this.root.style.gap = GRID_GAP;
        this.grid = {};
        var items = (layout && layout.grid) || [];
        var slots = [];
        for (var i = 0; i < items.length; i++) {
          var item = items[i];
          var slot = document.createElement("div");
          slot.className = "dp-dashboard-slot";
          slot.setAttribute("data-dp-slot", item.name);
          this.root.appendChild(slot);
          this.grid[item.name] = slot;
          slots.push(slot);
        }
        // Occurrences and controls without a grid entry flow into the default slot order.
        var defaultSlot = document.createElement("div");
        defaultSlot.className = "dp-dashboard-slot";
        this.root.appendChild(defaultSlot);
        this.defaultSlot = defaultSlot;
        stopResizeObservation();
        this.container = container;
        container.appendChild(this.root);
        var ResizeObserverType = typeof window !== "undefined" && window ? window.ResizeObserver : null;
        var breakpointPx = layoutBreakpointPx(layout);
        var initialWidth = Number(container.clientWidth) || 0;
        var collapsed = typeof ResizeObserverType === "function" && initialWidth > 0 && initialWidth < breakpointPx;
        placeGridSlots(items, slots, defaultSlot, columns, collapsed);
        if (typeof ResizeObserverType === "function") {
          var observation = {
            active: true,
            observer: null,
            frameQueued: false,
            pendingWidth: initialWidth,
            laidOut: initialWidth > 0,
            collapsed: collapsed,
          };
          observation.observer = new ResizeObserverType(function (entries) {
            if (!observation.active) return;
            for (var i = 0; i < entries.length; i++) {
              if (entries[i].target === container) observation.pendingWidth = entries[i].contentRect.width;
            }
            if (observation.frameQueued) return;
            observation.frameQueued = true;
            scheduleResize(function () {
              observation.frameQueued = false;
              if (!observation.active) return;
              var width = Number(observation.pendingWidth) || 0;
              if (width <= 0) return;
              var nextCollapsed = width < breakpointPx;
              if (!observation.laidOut) {
                observation.laidOut = true;
                observation.collapsed = nextCollapsed;
                if (nextCollapsed) placeGridSlots(items, slots, defaultSlot, columns, true);
                return;
              }
              if (nextCollapsed === observation.collapsed) return;
              observation.collapsed = nextCollapsed;
              placeGridSlots(items, slots, defaultSlot, columns, nextCollapsed);
              // Only a narrow/wide crossing changes slot widths. Plotly does not re-fit on host resize.
              resizeMounted();
            });
          });
          observation.observer.observe(container);
          resizeObservation = observation;
        }
        return Promise.resolve(undefined);
      },
      mountVisualization: function (occurrence, renderer) {
        try {
          var slot = this.grid && this.grid[occurrence.name] ? this.grid[occurrence.name] : this.defaultSlot;
          // #473 — every visualization renders INSIDE a card: a bordered shell with a heading (the
          // authored title, else the pinned visualization's display name, else a renderer label such
          // as the KPI's, else the occurrence name), an optional description, the renderer's own
          // host body, and a status foot. The `data-dp-viz` marker and the status chip keep their
          // DOM relationship (`[data-dp-viz="name"] .dp-dashboard-status`) — the marker moved from
          // the renderer body to the card that now wraps it.
          var cardEl = document.createElement("article");
          cardEl.className = "dp-dashboard-card";
          cardEl.setAttribute("data-dp-viz", occurrence.name);
          var titleId = "dp-card-" + radioGroupToken + "-" + occurrence.name;
          cardEl.setAttribute("aria-labelledby", titleId);
          var head = document.createElement("div");
          head.className = "dp-dashboard-card-head";
          var heading = document.createElement("h3");
          heading.className = "dp-dashboard-card-title";
          heading.id = titleId;
          heading.textContent = cardHeading(occurrence);
          var description = document.createElement("p");
          description.className = "dp-dashboard-card-description";
          description.textContent = (occurrence.description || "").trim();
          head.appendChild(heading);
          head.appendChild(description);
          var body = document.createElement("div");
          body.className = "dp-dashboard-viz";
          var foot = document.createElement("div");
          foot.className = "dp-dashboard-card-foot";
          var chip = document.createElement("div");
          chip.className = "dp-dashboard-status";
          chip.setAttribute("role", "status");
          var message = document.createElement("div");
          message.className = "dp-dashboard-card-message";
          foot.appendChild(chip);
          foot.appendChild(message);
          cardEl.appendChild(head);
          cardEl.appendChild(body);
          cardEl.appendChild(foot);
          slot.appendChild(cardEl);
          cards[occurrence.name] = {
            card: cardEl,
            body: body,
            chip: chip,
            message: message,
            heading: heading,
            description: description,
            timer: null,
            generation: 0,
          };
          var implementation = ensure(renderer.kind);
          var context = {
            instanceId: null,
            host: body,
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
      /**
       * The parameters pane from the WRITER's response (EvaluateResponseJson): one row per
       * parameter — label, control, error text — REBUILT WHOLE on every call, so repeated
       * evaluations and resets replace the owned rows instead of appending duplicates (and no
       * stale listener ever survives a render). The definition is FLAT (`name`, `label`, `type`,
       * `kind`, `cardinality`, `presentation.control`); value/options/hidden/disabled live in
       * `state`; the dashboard's `overrides_applied` wins over the engine's hidden/disabled.
       * Option identity is the TYPED wire value — nothing is stringified through `select.value`:
       * a <select>'s option value is an INDEX into the control's wire values (the typed value
       * never passes through a string form), a checkbox group collects its checked TYPED values,
       * a MULTI reads an array, an unresolved null stays null, and hidden and disabled values are
       * read exactly like any other (D23).
       */
      renderParameters: function (state) {
        var root = this.parametersRoot;
        if (!root) {
          root = this.parametersRoot = document.createElement("div");
          root.className = "dp-dashboard-parameters";
        }
        // #473 — the pane mounts where the HOST pointed (its own filters panel when
        // `parametersContainer` was given), else into the layout container as before.
        var parent = parametersContainer || this.container || this.defaultSlot;
        if (parent) {
          if (root.parentNode !== parent) {
            if (root.parentNode && typeof root.parentNode.removeChild === "function") root.parentNode.removeChild(root);
            if (typeof parent.insertBefore === "function") parent.insertBefore(root, parent.firstChild);
          }
        }
        while (root.firstChild) root.removeChild(root.firstChild);
        this._selectionInputs = {};
        var parameters = (state && state.parameters) || [];
        var overrides = (state && state.overrides_applied) || {};
        var self2 = this;
        for (let i = 0; i < parameters.length; i++) {
          let parameter = parameters[i];
          if (!parameter || !parameter.name) continue;
          let definitionState = parameter.state || {};
          let override = overrides[parameter.name] || {};
          let hidden = override.visible === false || (override.visible === undefined && definitionState.hidden === true);
          let enabled = override.enabled === true || (override.enabled === undefined && definitionState.disabled !== true);
          let controlType =
            parameter.presentation && parameter.presentation.control ? parameter.presentation.control : null;
          let isMulti = parameter.cardinality === "MULTI";
          // #473 — a MULTI/checkbox/radio group is a FIELDSET: its caption is a legend (a <label>
          // may name only ONE control), and every option's text is a <label for> over its own
          // input, so clicking the visible text selects it. Single-control rows keep the label[for]
          // caption with a stable per-instance id (`dp-param-<token>-<name>`).
          let isGroup = isMulti || controlType === "checkboxes" || controlType === "radio";
          let controlId = "dp-param-" + radioGroupToken + "-" + parameter.name;
          let row = document.createElement(isGroup ? "fieldset" : "div");
          row.className = "dp-dashboard-parameter";
          row.setAttribute("data-dp-parameter", parameter.name);
          if (isGroup) {
            let legend = document.createElement("legend");
            legend.className = "dp-dashboard-parameter-label";
            legend.textContent = parameter.label || parameter.name || "";
            row.appendChild(legend);
          } else {
            let label = document.createElement("label");
            label.className = "dp-dashboard-parameter-label";
            label.setAttribute("for", controlId);
            label.textContent = parameter.label || parameter.name || "";
            row.appendChild(label);
          }
          let read = null;
          let interactives = [];
          if (parameter.kind === "SELECT") {
            let options = (definitionState.options || []).slice();
            let typedValues = [];
            let byKey = {};
            for (let o = 0; o < options.length; o++) {
              typedValues.push(options[o] && options[o].value !== undefined ? options[o].value : null);
              byKey[wireKey(typedValues[o])] = o;
            }
            let indexOfValue = function (value) {
              return byKey[wireKey(value === undefined ? null : value)];
            };
            let displayText = function (option) {
              return option && option.display_value !== undefined ? option.display_value : String(option ? option.value : "");
            };
            if (isMulti || controlType === "checkboxes") {
              // A MULTI renders a checkbox group; the checked boxes' TYPED values are collected.
              let boxes = [];
              let current = definitionState.value;
              let pickedKeys = {};
              if (Object.prototype.toString.call(current) === "[object Array]") {
                for (let c = 0; c < current.length; c++) pickedKeys[wireKey(current[c])] = true;
              }
              for (let b = 0; b < options.length; b++) {
                let box = document.createElement("input");
                box.setAttribute("type", "checkbox");
                box.checked = pickedKeys[wireKey(typedValues[b])] === true;
                if (!enabled) box.disabled = true;
                let boxText = document.createElement("label");
                boxText.className = "dp-dashboard-parameter-option-label";
                boxText.setAttribute("for", controlId + "-" + b);
                boxText.textContent = displayText(options[b]);
                box.id = controlId + "-" + b;
                row.appendChild(box);
                row.appendChild(boxText);
                boxes.push({ element: box, value: typedValues[b] });
                interactives.push(box);
              }
              read = function () {
                let picked = [];
                for (let p = 0; p < boxes.length; p++) if (boxes[p].element.checked) picked.push(boxes[p].value);
                return picked;
              };
            } else if (controlType === "radio") {
              let radios = [];
              let selected = indexOfValue(definitionState.value);
              for (let r = 0; r < options.length; r++) {
                let input = document.createElement("input");
                input.setAttribute("type", "radio");
                input.setAttribute("name", "dp-param-" + radioGroupToken + "-" + parameter.name);
                input.checked = selected === r;
                if (!enabled) input.disabled = true;
                let radioText = document.createElement("label");
                radioText.className = "dp-dashboard-parameter-option-label";
                radioText.setAttribute("for", controlId + "-" + r);
                radioText.textContent = displayText(options[r]);
                input.id = controlId + "-" + r;
                row.appendChild(input);
                row.appendChild(radioText);
                radios.push({ element: input, value: typedValues[r] });
                interactives.push(input);
              }
              read = function () {
                for (let q = 0; q < radios.length; q++) if (radios[q].element.checked) return radios[q].value;
                return null;
              };
            } else {
              // dropdown/list (the derived default included): one <select>; its option value is
              // the INDEX into the wire values.
              let selectControl = document.createElement("select");
              // #473 — the themed field appearance (the design system's control), never a bare UA select.
              selectControl.className = "ds-input";
              selectControl.id = controlId;
              let placeholder = document.createElement("option");
              placeholder.setAttribute("value", "");
              selectControl.appendChild(placeholder);
              for (let s = 0; s < options.length; s++) {
                let option = document.createElement("option");
                option.setAttribute("value", String(s));
                option.textContent = displayText(options[s]);
                selectControl.appendChild(option);
              }
              let selectedIndex = indexOfValue(definitionState.value);
              selectControl.value = selectedIndex === undefined ? "" : String(selectedIndex);
              if (!enabled) selectControl.disabled = true;
              interactives.push(selectControl);
              row.appendChild(selectControl);
              read = function () {
                return selectControl.value === "" ? null : typedValues[Number(selectControl.value)];
              };
            }
          } else if (parameter.type === "BOOLEAN") {
            // The house BOOLEAN control (the schedules form's mould): a tri-state select —
            // "— not given —" / true / false. A two-state control cannot display the unresolved
            // null, and reading one would turn it into false; here all three wire values survive
            // the round trip untouched. The `toggle`/`checkbox` hints are deliberately not
            // honoured yet (P23 lets a renderer ignore a hint): neither shows the third state.
            let booleanSelect = document.createElement("select");
            booleanSelect.className = "ds-input";
            booleanSelect.id = controlId;
            let unset = document.createElement("option");
            unset.setAttribute("value", "");
            unset.textContent = "— not given —";
            booleanSelect.appendChild(unset);
            let yes = document.createElement("option");
            yes.setAttribute("value", "true");
            yes.textContent = "true";
            booleanSelect.appendChild(yes);
            let no = document.createElement("option");
            no.setAttribute("value", "false");
            no.textContent = "false";
            booleanSelect.appendChild(no);
            booleanSelect.value =
              definitionState.value === true ? "true" : definitionState.value === false ? "false" : "";
            if (!enabled) booleanSelect.disabled = true;
            interactives.push(booleanSelect);
            row.appendChild(booleanSelect);
            read = function () {
              return booleanSelect.value === "true" ? true : booleanSelect.value === "false" ? false : null;
            };
          } else {
            // INPUT: one free control; the read parses toward the wire type (typedInputRead).
            let free = document.createElement("input");
            free.setAttribute("type", "text");
            free.className = "ds-input";
            free.id = controlId;
            let raw = definitionState.value;
            free.value = raw === null || raw === undefined ? "" : String(raw);
            if (!enabled) free.disabled = true;
            interactives.push(free);
            read = typedInputRead(parameter.type, free);
            row.appendChild(free);
          }
          if (hidden) row.style.display = "none";
          if (provenance) {
            // #374 — where the shown value came from (P26) and whether the walk dropped the person's own value.
            // Both are the response's facts, rendered as text and a data attribute — never invented client-side.
            if (definitionState.origin) {
              let origin = document.createElement("span");
              origin.className = "dp-dashboard-parameter-origin";
              origin.setAttribute("data-dp-origin", String(definitionState.origin));
              origin.textContent = ORIGIN_LABELS[definitionState.origin] || String(definitionState.origin);
              row.appendChild(origin);
            }
            if (definitionState.reset === true) {
              let reset = document.createElement("span");
              reset.className = "dp-dashboard-parameter-reset";
              reset.setAttribute("data-dp-reset", "true");
              reset.setAttribute("role", "status");
              reset.textContent = "reset — the previous selection is no longer valid";
              row.appendChild(reset);
            }
          }
          if (definitionState.errors && definitionState.errors.length) {
            let problem = document.createElement("div");
            problem.className = "dp-dashboard-parameter-error";
            problem.setAttribute("role", "alert");
            let text = "";
            for (let e = 0; e < definitionState.errors.length; e++) {
              let one = definitionState.errors[e] || {};
              text += (one.code || "parameter.error") + (one.message ? ": " + one.message : "") + " ";
            }
            problem.textContent = text.trim();
            row.appendChild(problem);
          }
          // One edit + one commit per change gesture, on every interactive element of the row.
          for (let g = 0; g < interactives.length; g++) {
            interactives[g].addEventListener("change", (function (name) {
              return function () {
                if (listeners.edit && self2._gesture !== name) {
                  listeners.edit({ name: name, type: "parameter" });
                  self2._gesture = name;
                }
                if (listeners.commit) listeners.commit({ name: name, type: "parameter" });
                self2._gesture = null;
              };
            })(parameter.name));
          }
          this._selectionInputs[parameter.name] = read;
          root.appendChild(row);
        }
        return Promise.resolve(undefined);
      },
      /** Every parameter's current value — hidden and disabled included (D23) — in its WIRE type. */
      readSelections: function () {
        var out = {};
        var reads = this._selectionInputs || {};
        for (var name in reads) {
          if (Object.prototype.hasOwnProperty.call(reads, name)) {
            var read = reads[name];
            out[name] = typeof read === "function" ? read() : read;
          }
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
        // The composite's own status chip: accessible, not colour-only — and (#473) the card's
        // border/message treatment, driven by the same status. The chip keeps its element, text
        // and `data-dp-state` exactly as before.
        var card = cards[occurrence.name];
        if (!card) return;
        var text = status.state;
        if (status.stale) text += " (stale)";
        if (status.reason && status.reason.code) text += ": " + status.reason.code;
        card.chip.textContent = text;
        card.chip.setAttribute("data-dp-state", status.state);
        applyCardStatus(card, status);
      },
      notify: function (notification) {
        notifications.push(notification);
      },
      // The instance's resize walks the renderers itself; the host observer owns breakpoint placement.
      resize: function () {},
      dispose: function () {
        stopResizeObservation();
        for (var name in implemented) {
          if (Object.prototype.hasOwnProperty.call(implemented, name)) {
            var handle = implemented[name];
            if (handle && typeof handle.dispose === "function") handle.dispose();
          }
        }
        implemented = {};
        // #473 — no card's accent timer may outlive the composite: a disposed card must never
        // reach back into the DOM after its instance is gone.
        var cardNames = Object.keys(cards);
        for (var c = 0; c < cardNames.length; c++) {
          if (cards[cardNames[c]].timer) clearTimeout(cards[cardNames[c]].timer);
        }
        cards = {};
        // The composite removes everything IT mounted — the grid and the parameters pane — so a
        // dispose/re-init cycle leaves exactly one layout/control set. Host-owned DOM (the
        // container itself, a neighbouring instance's subtree) stands untouched.
        if (this.parametersRoot && this.parametersRoot.parentNode && this.parametersRoot.parentNode.removeChild) {
          this.parametersRoot.parentNode.removeChild(this.parametersRoot);
        }
        if (this.root && this.root.parentNode && this.root.parentNode.removeChild) {
          this.root.parentNode.removeChild(this.root);
        }
      },
    };
  }

  var api = {
    init: init,
    initParameters: initParameters,
    registerRenderer: registerRenderer,
    adapters: adapters,
    DashboardError: DashboardError,
    isDashboardError: isDashboardError,
    /** Internal, for the node tests: the required-function list and a fresh renderer table. */
    _internal: {
      REQUIRED_ADAPTER_FUNCTIONS: REQUIRED_ADAPTER_FUNCTIONS,
      /** #473 — how long a card's loud error/abort accent lasts before the quiet issue border. */
      ERROR_ACCENT_MS: ERROR_ACCENT_MS,
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
      /** #383 — the one parser, exported for the unit tests only; the page never copies it. */
      SseParser: SseParser,
    },
  };

  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") window.DatapipelinesDashboard = api;
})();
