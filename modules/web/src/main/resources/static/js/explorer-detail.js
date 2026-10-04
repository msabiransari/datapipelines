/*
 * 106 — the explorers' tree drawer, the narrow layout's one need. (The name is historical: this
 * file began as the explorers' detail pane — its tabs and the tree badge refresh. The
 * pipelines explorer's pane went with #401 and the templates explorer's with #398, so the
 * tabs and the badge refresh — whose `lifecycle-changed` Shape A payload no handler answers
 * any more — went with them. What remains is the one job the schedules page still has.)
 *
 * THE DRAWER. Below 1100px the tree is an overlay over the detail rather than a column
 * beside it. Opening is one class on `.tplx-body`; every rule that positions it lives in
 * template-tree.css, so this file decides no width, no breakpoint and no colour. It closes
 * on Escape and on the backdrop.
 */
(function () {
  'use strict';

  function explorerBody() {
    return document.querySelector('.tplx-body');
  }

  function setDrawer(open) {
    var pane = explorerBody();
    if (!pane) return;
    pane.classList.toggle('is-drawer-open', open);
    Array.prototype.forEach.call(document.querySelectorAll('[data-explorer-drawer-open]'), function (b) {
      b.setAttribute('aria-expanded', open ? 'true' : 'false');
    });
    var backdrop = document.querySelector('[data-explorer-drawer-backdrop]');
    if (backdrop) backdrop.hidden = !open;
    if (open) {
      var first = pane.querySelector('.tplx-tree input, .tplx-tree summary, .tplx-tree button');
      if (first) first.focus();
    }
  }

  document.addEventListener('click', function (event) {
    if (!event.target.closest) return;
    if (event.target.closest('[data-explorer-drawer-open]')) {
      var pane = explorerBody();
      setDrawer(!(pane && pane.classList.contains('is-drawer-open')));
      return;
    }
    if (event.target.closest('[data-explorer-drawer-backdrop]')) setDrawer(false);
  });

  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') setDrawer(false);
  });

  // The DOM adapter above is the browser suite's to prove; setDrawer is exported for `node --test`
  // (the shell.js / template-explorer.js convention).
  var api = { setDrawer: setDrawer };
  if (typeof window !== 'undefined') window.explorerDetail = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})();
