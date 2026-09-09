/*
 * 106 — the explorers' detail pane: its tabs and the tree drawer the narrow layout needs.
 * Shared by BOTH explorers, exactly as template-explorer.js is: the panes and cards are found
 * by markers, never by a screen-specific id.
 *
 * 102 removed the fetch-and-confirm verb path 106 shipped against the REST routes (the verbs
 * are §4.3d dialog partials now — their POSTs answer Shape A with a `lifecycle-changed`
 * payload) and added the tree badge refresh that payload drives: the leaf's version badge
 * follows the POST's OWN facts (working version, draft flag), never a client-side guess, and
 * a dialog landing closes any open ⋯ menu.
 *
 * THREE small jobs, and deliberately no framework.
 *
 * 1. TABS. The acting card's Versions / Runs / Usage panels. htmx loads the two lazy tabs
 *    (`hx-trigger="click once"`); this only decides which panel is VISIBLE, and it does so
 *    with `hidden` rather than a display rule, so the swap target is in the DOM before its
 *    fragment lands and htmx has something to swap into.
 *
 * 2. THE TREE BADGE REFRESH. `lifecycle-changed` arrives with the Shape A response of every
 *    lifecycle POST, carrying {leafId|leafName, workingVersion, hasDraft} read from the row
 *    the POST just wrote. The leaf's badges are rewritten from that payload; when the working
 *    version is null (no live version remains) the version badge goes away, which is the
 *    state the empty detail will confirm on the next selection.
 *
 * 3. THE DRAWER. Below 1100px the tree is an overlay over the detail rather than a column
 *    beside it. Opening is one class on `.tplx-body`; every rule that positions it lives in
 *    template-tree.css, so this file decides no width, no breakpoint and no colour. It closes
 *    on Escape, on the backdrop, and when a selection lands — selecting a leaf is the reason
 *    the drawer was opened, so staying open would cover the answer.
 */
(function () {
  'use strict';

  // ------------------------------------------------------------------ 1. tabs

  function selectTab(button) {
    var card = button.closest('[data-explorer-tabs]');
    if (!card) return;
    Array.prototype.forEach.call(card.querySelectorAll('.tplx-tab'), function (tab) {
      var on = tab === button;
      tab.classList.toggle('is-on', on);
      tab.setAttribute('aria-selected', on ? 'true' : 'false');
      var panel = document.getElementById(tab.getAttribute('data-tab-panel'));
      if (panel) panel.hidden = !on;
    });
  }

  // -------------------------------------------------- 2. the tree badge refresh

  /**
   * The badge rewrite, driven ONLY by the payload's server facts. Exported for
   * `node --test` with the leaf row and badges passed in as plain elements would tie the
   * test to the DOM; instead the decision is one function of facts, and the DOM half stays
   * thin: applyBadge(leaf, {workingVersion, hasDraft}).
   */
  function badgeFacts(leaf, payload) {
    return {
      version: payload && typeof payload.workingVersion === 'number' ? 'v' + payload.workingVersion : null,
      hasDraft: !!(payload && payload.hasDraft),
    };
  }

  function applyBadge(leaf, payload) {
    if (!leaf) return;
    var facts = badgeFacts(leaf, payload);
    var version = leaf.querySelector('.tpl-leaf-version');
    if (facts.version === null) {
      if (version) version.remove();
    } else if (version) {
      version.textContent = facts.version;
    } else {
      // A leaf with no badge yet (never released, then restored/imported): add one beside
      // the label, matching the tree's own markup shape.
      var label = leaf.querySelector('.tpl-label');
      if (label) {
        var span = document.createElement('span');
        span.className = 'ds-badge ds-badge-default tpl-leaf-version';
        span.textContent = facts.version;
        label.after(span);
      }
    }
    var draft = leaf.querySelector('.tpl-leaf-draft');
    if (!facts.hasDraft && draft) draft.remove();
  }

  function leafFor(detail) {
    if (!detail) return null;
    if (detail.leafId) {
      return document.querySelector('[data-leaf-id="' + detail.leafId + '"] .tpl-leaf-version, [data-leaf-id="' + detail.leafId + '"]');
    }
    if (detail.leafName) {
      return document.querySelector('[data-leaf-name="' + detail.leafName.replace(/"/g, '\\"') + '"]');
    }
    return null;
  }

  function onLifecycleChanged(event) {
    var payload = event.detail && event.detail.value;
    if (!payload) return;
    applyBadge(leafFor(payload), payload);
  }

  // --------------------------------------------------------------- 3. drawer

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

  // ------------------------------------------------------------------ wiring

  document.addEventListener('click', function (event) {
    if (!event.target.closest) return;
    var tab = event.target.closest('.tplx-tab');
    if (tab) {
      selectTab(tab);
      return;
    }
    // "Open full source" in the reading column jumps to the acting column's Source tab —
    // one body, one place, and the excerpt stays an excerpt.
    var jump = event.target.closest('[data-tab-jump]');
    if (jump) {
      var wanted = document.querySelector('[data-tab-panel="' + jump.getAttribute('data-tab-jump') + '"]');
      if (wanted) selectTab(wanted);
      return;
    }
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

  // A selection landing in the detail pane is what the drawer was opened FOR.
  if (document.body) {
    document.body.addEventListener('htmx:afterSwap', function (event) {
      var target = event.detail && event.detail.target;
      if (target && (target.id === 'pipeline-detail' || target.id === 'template-detail')) setDrawer(false);
    });

    // 102: every lifecycle POST's Shape A response fires this with its own facts.
    document.body.addEventListener('lifecycle-changed', onLifecycleChanged);
  }

  // The pure halves, for `node --test` (the shell.js / template-explorer.js convention). The
  // DOM adapters above are the browser suite's to prove; these are the decisions they delegate.
  var api = { selectTab: selectTab, setDrawer: setDrawer, badgeFacts: badgeFacts, applyBadge: applyBadge };
  if (typeof window !== 'undefined') window.explorerDetail = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})();
