/*
 * 106 — the explorers' detail pane: its tabs, its lifecycle verbs, and the tree drawer the
 * narrow layout needs. Shared by BOTH explorers, exactly as template-explorer.js is: the
 * panes and cards are found by markers, never by a screen-specific id.
 *
 * FOUR small jobs, and deliberately no framework.
 *
 * 1. TABS. The acting card's Versions / Runs / Usage panels. htmx loads the two lazy tabs
 *    (`hx-trigger="click once"`); this only decides which panel is VISIBLE, and it does so
 *    with `hidden` rather than a display rule, so the swap target is in the DOM before its
 *    fragment lands and htmx has something to swap into.
 *
 * 2. THE LIFECYCLE VERBS, over `fetch` rather than htmx. 101's REST verbs are a JSON API:
 *    the template half takes a JSON REQUEST BODY (`{"name": …, "version": …}`) and htmx
 *    form-encodes its values, so driving them with `hx-post` would need the json-enc
 *    extension this app does not vendor. One `fetch` path drives both halves instead, which
 *    also keeps the `If-Match` precondition ("you release what you tested", §4.2) and the
 *    double-submit CSRF header in ONE place rather than spread over `hx-headers` attributes
 *    whose merge with <body>'s inherited token is a detail of htmx we should not depend on.
 *
 * 3. THE REFRESH AFTER A VERB. Nothing is patched into the DOM from a guess: a discard can
 *    move the sticky pointer (D60) and a sole-draft purge takes the entity with it. A
 *    success therefore RE-READS the detail fragment from the server — or reloads the page
 *    when the row itself is gone, because the TREE has to lose the leaf too and this pane may
 *    never touch the tree (the 067 render guard).
 *
 * 4. THE DRAWER. Below 1100px the tree is an overlay over the detail rather than a column
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

  // ---------------------------------------------------------- 2/3. the verbs

  function csrfToken() {
    var match = document.cookie.match(/(?:^|;\s*)dp_csrf=([^;]*)/);
    return match ? decodeURIComponent(match[1]) : '';
  }

  function detailPane() {
    return document.getElementById('pipeline-detail') || document.getElementById('template-detail');
  }

  function refreshDetail() {
    var source = document.querySelector('[data-detail-url]');
    var pane = detailPane();
    if (!source || !pane || !window.htmx) return;
    window.htmx.ajax('GET', source.getAttribute('data-detail-url'), { target: '#' + pane.id, swap: 'innerHTML' });
  }

  /**
   * The server's own words out of a refusal body.
   *
   * `{"error":{"code","message"}}` is the app's one error shape (rules/02); anything that is
   * not that — a proxy's HTML, an empty body — is passed through as text rather than replaced
   * by a house "something went wrong", because `pipeline.version.pinned` and the list it names
   * is the whole reason the user pressed the button.
   */
  function messageOf(text) {
    try {
      var parsed = JSON.parse(text);
      return (parsed && parsed.error && parsed.error.message) || text;
    } catch (ignored) {
      return text;
    }
  }

  function report(message) {
    if (window.DpToast) window.DpToast.show('error', 'The server refused that', message);
  }

  function runVerb(button) {
    var confirmation = button.getAttribute('data-confirm');
    if (confirmation && !window.confirm(confirmation)) return;
    var headers = { 'DP-CSRF-Token': csrfToken() };
    var hash = button.getAttribute('data-if-match');
    if (hash) headers['If-Match'] = hash;
    var body = button.getAttribute('data-verb-body');
    if (body) headers['Content-Type'] = 'application/json';
    button.disabled = true;
    window
      .fetch(button.getAttribute('data-verb-url'), {
        method: button.getAttribute('data-verb'),
        credentials: 'same-origin',
        headers: headers,
        body: body || undefined,
      })
      .then(function (response) {
        if (!response.ok) {
          return response.text().then(function (text) {
            button.disabled = false;
            report(messageOf(text));
          });
        }
        if (button.getAttribute('data-after') === 'reload') {
          window.location.reload();
          return null;
        }
        refreshDetail();
        return null;
      })
      .catch(function (error) {
        button.disabled = false;
        report(String(error));
      });
  }

  // --------------------------------------------------------------- 4. drawer

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
    var verb = event.target.closest('[data-verb-url]');
    if (verb) {
      event.preventDefault();
      runVerb(verb);
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
  }

  // The pure halves, for `node --test` (the shell.js / template-explorer.js convention). The
  // DOM adapters above are the browser suite's to prove; these are the decisions they delegate.
  var api = { messageOf: messageOf, csrfToken: csrfToken, selectTab: selectTab, setDrawer: setDrawer };
  if (typeof window !== 'undefined') window.explorerDetail = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})();
