(function () {
  "use strict";

  function ResultPanel(editor) {
    this.editor = editor;
    this.visible = false;
    this.data = null;
    this.columns = [];
    this.rows = [];
    this.page = 1;
    this.totalPages = 1;
    this.pageSize = 0;
    this.hasPrev = false;
    this.hasNext = false;
    this.cursorEndpoint = null;
    this.ttlSeconds = 0;
    this.ttlInterval = null;
    this.expired = false;
  }

  // The wire contract (rest-api.md §6.4.7 data_ready, §7.3 cursor) carries
  // schema: [{name, type, ...}] and POSITIONAL rows [[...]]; the editor's table
  // renders named columns against keyed row objects (:key="row.__idx"). The
  // panel previously read payload.columns/object-rows — a shape the API never
  // sent — so the table never rendered and Alpine's keyed x-for crashed on the
  // undefined keys (027). Normalize once, at this choke point.
  function normalizePage(payload) {
    var columns =
      payload.columns ||
      (payload.schema || []).map(function (s) {
        return s && s.name;
      });
    var keyed = (payload.rows || []).map(function (r, i) {
      if (r && typeof r === "object" && !Array.isArray(r)) {
        r.__idx = i;
        return r;
      }
      var o = { __idx: i };
      columns.forEach(function (c, ci) {
        o[c] = r === null || r === undefined ? r : r[ci];
      });
      return o;
    });
    // `limit` is the page size the SERVER says it applied (§7.3). rows.length is
    // only ever a lower bound — the last page is short by definition, and
    // treating it as the page size corrupted every subsequent offset (027b C).
    return { columns: columns, rows: keyed, limit: payload.limit };
  }

  /** The page denominator from the field both payloads carry (§6.4.7, §7.3). */
  function totalPagesFrom(totalRows, pageSize) {
    if (totalRows === undefined || totalRows === null) return 1;
    return Math.max(1, Math.ceil(totalRows / (pageSize || 1)));
  }

  ResultPanel.prototype.showData = function (payload) {
    var self = this;
    self.visible = true;
    self.cursorEndpoint = payload.cursor_endpoint || payload.result_url || null;

    var page = normalizePage(payload);
    self.columns = page.columns;
    self.rows = page.rows;
    // data_ready carries no `limit` (§6.4.7). The inline page IS exactly one page:
    // a full page when has_more, the whole result when not — either way its row
    // count is a correct provisional size. Cursor pages below prefer the
    // server-reported `limit` and never shrink to a short last page's row count.
    self.pageSize = page.limit || page.rows.length;
    // The template gates the table on `resultPanel.data` — a value nothing ever
    // set, so the panel rendered its chrome (header, TTL, pagination) over an
    // eternally hidden table (027). Materialize it from the loaded page.
    self.data = { columns: self.columns, rows: self.rows };
    // total_rows is in both payloads — a real denominator, recomputed on every
    // page load (027b D). The API sends neither total_pages nor page (§6.4.7,
    // §7.3); the old has_more guess froze at 2 and rendered "Page 3 / 2".
    self.totalPages = totalPagesFrom(payload.total_rows, self.pageSize);
    self.page = payload.page || 1;
    self.hasPrev = self.page > 1;
    self.hasNext = payload.has_more !== undefined ? payload.has_more : self.page < self.totalPages;
    if (self.expired) {
      self.expired = false;
    }
    self.startTtl(payload.ttl_seconds || self.ttlFromExpires(payload.expires_at));

    self.syncToEditor();
  };

  ResultPanel.prototype.ttlFromExpires = function (expiresAt) {
    if (!expiresAt) return 0;
    var ms = Date.parse(expiresAt) - Date.now();
    return ms > 0 ? Math.floor(ms / 1000) : 0;
  };

  ResultPanel.prototype.startTtl = function (seconds) {
    var self = this;
    if (self.ttlInterval) clearInterval(self.ttlInterval);
    self.ttlSeconds = seconds;

    if (seconds > 0) {
      self.ttlInterval = setInterval(function () {
        self.ttlSeconds--;
        self.syncToEditor();
        if (self.ttlSeconds <= 0) {
          clearInterval(self.ttlInterval);
          self.ttlInterval = null;
          self.expired = true;
          self.syncToEditor();
        }
      }, 1000);
    }
  };

  ResultPanel.prototype.loadPage = function (page) {
    var self = this;
    if (!self.cursorEndpoint) return;

    // The cursor paginates by offset/limit (rest-api.md §7.3), not by a page
    // number — the panel's old "?page=" param was ignored and every Prev/Next
    // click silently re-fetched the first page (027).
    var size = self.pageSize || 100;
    var offset = (page - 1) * size;
    fetch(self.cursorEndpoint + "?offset=" + offset + "&limit=" + size)
      .then(function (res) {
        if (!res.ok) throw new Error("Failed to load page");
        return res.json();
      })
      .then(function (data) {
        var pl = data.data || data;
        var normalized = normalizePage(pl);
        self.columns = normalized.columns;
        self.rows = normalized.rows;
        // The cursor reports the limit IT applied — the only page-size authority.
        // Never rows.length: the short last page must not rescale every other
        // page's offsets (027b C). total_rows keeps the denominator current on
        // every page (027b D).
        if (normalized.limit) self.pageSize = normalized.limit;
        self.page = page;
        self.totalPages = totalPagesFrom(pl.total_rows, self.pageSize);
        self.hasPrev = self.page > 1;
        self.hasNext = pl.has_more !== undefined ? pl.has_more : false;
        self.syncToEditor();
      })
      .catch(function (err) {
        console.error("Result page load failed:", err);
      });
  };

  ResultPanel.prototype.syncToEditor = function () {
    var rp = this.editor.resultPanel;
    rp.visible = this.visible;
    rp.data = this.data;
    rp.columns = this.columns;
    rp.rows = this.rows;
    rp.page = this.page;
    rp.totalPages = this.totalPages;
    rp.hasPrev = this.hasPrev;
    rp.hasNext = this.hasNext;
    rp.ttlSeconds = this.ttlSeconds;
    rp.expired = this.expired;
    rp.cursorEndpoint = this.cursorEndpoint;
    if (rp.ttlInterval) clearInterval(rp.ttlInterval);
    rp.ttlInterval = this.ttlInterval;
  };

  ResultPanel.prototype.hide = function () {
    this.visible = false;
    if (this.ttlInterval) clearInterval(this.ttlInterval);
    this.ttlInterval = null;
    this.syncToEditor();
  };

  window.ResultPanel = ResultPanel;
})();
