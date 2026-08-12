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
    this.hasPrev = false;
    this.hasNext = false;
    this.cursorEndpoint = null;
    this.ttlSeconds = 0;
    this.ttlInterval = null;
    this.expired = false;
  }

  ResultPanel.prototype.showData = function (payload) {
    var self = this;
    self.visible = true;
    self.cursorEndpoint = payload.cursor_endpoint || null;

    if (payload.columns) self.columns = payload.columns;
    if (payload.rows) self.rows = payload.rows;
    if (payload.total_pages !== undefined) self.totalPages = payload.total_pages;
    self.page = payload.page || 1;
    self.hasPrev = self.page > 1;
    self.hasNext = self.page < self.totalPages;
    if (self.expired) {
      self.expired = false;
    }
    self.startTtl(payload.ttl_seconds || 0);

    self.syncToEditor();
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

    fetch(self.cursorEndpoint + "?page=" + page)
      .then(function (res) {
        if (!res.ok) throw new Error("Failed to load page");
        return res.json();
      })
      .then(function (data) {
        var pl = data.data || data;
        if (pl.columns) self.columns = pl.columns;
        if (pl.rows) self.rows = pl.rows;
        self.page = pl.page || page;
        if (pl.total_pages !== undefined) self.totalPages = pl.total_pages;
        self.hasPrev = self.page > 1;
        self.hasNext = self.page < self.totalPages;
        self.syncToEditor();
      })
      .catch(function (err) {
        console.error("Result page load failed:", err);
      });
  };

  ResultPanel.prototype.syncToEditor = function () {
    var rp = this.editor.resultPanel;
    rp.visible = this.visible;
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
