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
    var pageSize = payload.rows ? payload.rows.length : 0;
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
    return { columns: columns, rows: keyed, pageSize: pageSize, limit: payload.limit };
  }

  ResultPanel.prototype.showData = function (payload) {
    var self = this;
    self.visible = true;
    self.cursorEndpoint = payload.cursor_endpoint || payload.result_url || null;

    var page = normalizePage(payload);
    self.columns = page.columns;
    self.rows = page.rows;
    self.pageSize = page.pageSize || page.limit || self.pageSize;
    // The template gates the table on `resultPanel.data` — a value nothing ever
    // set, so the panel rendered its chrome (header, TTL, pagination) over an
    // eternally hidden table (027). Materialize it from the loaded page.
    self.data = { columns: self.columns, rows: self.rows };
    if (payload.total_pages !== undefined) self.totalPages = payload.total_pages;
    else self.totalPages = payload.has_more === false ? self.page : 2; // "1 or more": Next probes the next offset
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
    var size = self.pageSize || self.rows.length || 100;
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
        self.pageSize = normalized.pageSize || normalized.limit || self.pageSize;
        self.page = page;
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
