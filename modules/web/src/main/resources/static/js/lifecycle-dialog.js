/*
 * 102 — the lifecycle dialogs' client half (ui-screens §4.3d): the ONE per-screen container
 * (#px-dialog / #tx-dialog / #pe-dialog / #te-dialog), its close paths, focus, and the typed
 * confirm. Shared by four screens; it owns no verb, no fetch and no toast — the dialogs are
 * htmx partials whose POSTs answer Shape A / HX-Redirect / Shape C, and toast.js owns the
 * toasts. Everything here is chrome, in the ds-dialog lineage (094 §A/§B).
 *
 * FIVE small jobs, no framework:
 *
 * 1. CLOSE. Emptying the container is the ONLY close path (no display toggling, so a stale
 *    form can never hide in it): the dialog's own [data-lifecycle-close] buttons, and Escape.
 * 2. FOCUS. When a dialog lands, focus moves to its first control — a keyboard user opens a
 *    dialog with Enter and must not be left on the opener.
 * 3. TYPED CONFIRM. The irreversible verbs (purge, purge-entity) ship a
 *    [data-typed-confirm] button that stays disabled until [data-confirm-input] carries
 *    exactly the expected text (§5.1's convention; the server re-checks the field and
 *    answers 400 *.confirm_mismatch, so this is convenience, not the guard). 140's sibling
 *    sits beside it: the release override's [data-min-chars-input] arms its
 *    [data-min-chars-submit] at ≥ N trimmed characters, and because that footer lands by
 *    out-of-band swap AFTER the dialog opened, both arms re-run on any swap INSIDE an open
 *    dialog (a `lcArmed` marker keeps re-arms from stacking listeners).
 * 4. CLOSE ON SUCCESS. A Shape A response swaps the DETAIL pane, not the dialog; the hidden
 *    [data-lifecycle-applied] marker riding with it is what closes the dialog — a refusal
 *    (Shape C, retargeted at #toast) never carries the marker, so an error cannot close
 *    over the dialog.
 * 5. MENUS. The version rows' ⋯ overflow menus close on Escape and when a dialog opens —
 *    and, open, they are PLACED: the list is a `popover="manual"` living in the top layer
 *    (the tab panel it sits in scrolls and used to clip it), so this script puts it under
 *    (or, out of room, above) its own ⋯ from the ⋯'s measured rect, and RE-places it on
 *    every scroll and resize so it follows its ⋯; it closes only when the ⋯ itself has
 *    scrolled out of sight. Closing on scroll instead was a defect: the browser delivers
 *    `scroll` asynchronously, so the scroll-into-view that precedes a click on the ⋯ fired
 *    AFTER the click had opened the menu and shut it again (every Playwright click on a ⋯
 *    below the fold, LifecycleDialogBrowserTest, 2026-09-12). The placement rule is pure
 *    and exported (`menuPlacement`) for `node --test`.
 */
(function () {
  'use strict';

  var CONTAINERS = ['px-dialog', 'tx-dialog', 'pe-dialog', 'te-dialog'];

  function openContainer() {
    for (var i = 0; i < CONTAINERS.length; i++) {
      var el = document.getElementById(CONTAINERS[i]);
      if (el && el.children.length > 0) return el;
    }
    return null;
  }

  function closeDialog() {
    var container = openContainer();
    if (container) container.innerHTML = '';
  }

  function firstControl(container) {
    if (!container) return null;
    var controls = container.querySelectorAll('input, select, textarea, button:not([data-lifecycle-close])');
    return controls.length > 0 ? controls[0] : container.querySelector('[data-lifecycle-close]');
  }

  /**
   * The typed-confirm rule — the pure half, exported for `node --test` (the shell.js
   * convention): a value matches when it equals the expected text exactly, whitespace
   * trimmed so a trailing space cannot hold the button hostage.
   */
  function confirmMatches(value, expected) {
    return typeof value === 'string' && typeof expected === 'string' &&
      value.trim() === expected;
  }

  /**
   * 140 — the min-chars arm, the typed confirm's sibling for the release override: the
   * button stays disabled until the reason carries at least `min` characters, whitespace
   * trimmed (the server re-runs the whole gate on the POST; this is convenience, not the
   * guard — same division as the typed confirm).
   */
  function minCharsMet(value, min) {
    return typeof value === 'string' && value.trim().length >= min;
  }

  function armTypedConfirm(container) {
    var input = container.querySelector('[data-confirm-input]');
    var button = container.querySelector('[data-typed-confirm]');
    if (!input || !button || input.dataset.lcArmed) return;
    input.dataset.lcArmed = '1';
    var expected = input.getAttribute('data-confirm-expect') || '';
    var evaluate = function () {
      button.disabled = !confirmMatches(input.value, expected);
    };
    input.addEventListener('input', evaluate);
    // The initial state is disabled in the MARKUP (no-JS is safe); evaluate once so a
    // browser-autofilled field cannot leave the button stale either way.
    evaluate();
  }

  /**
   * 140 — the release override's pair: [data-min-chars-input] arms [data-min-chars-submit]
   * at data-min-chars (default 10) trimmed characters. The footer that carries these lands
   * by OUT-OF-BAND swap AFTER the dialog opened, so this runs on every swap inside an open
   * dialog (the `lcArmed` marker keeps a re-arm from stacking listeners on a survivor).
   */
  function armMinChars(container) {
    var input = container.querySelector('[data-min-chars-input]');
    var button = container.querySelector('[data-min-chars-submit]');
    if (!input || !button || input.dataset.lcArmed) return;
    input.dataset.lcArmed = '1';
    var min = parseInt(input.getAttribute('data-min-chars') || '10', 10);
    var evaluate = function () {
      button.disabled = !minCharsMet(input.value, min);
    };
    input.addEventListener('input', evaluate);
    evaluate();
  }

  function arm(container) {
    if (!container) return;
    armTypedConfirm(container);
    armMinChars(container);
    var focus = firstControl(container);
    if (focus) focus.focus();
  }

  /** A swap INSIDE the open dialog re-arms what it replaced; the focus move stays the landing's. */
  function rearm(container) {
    if (!container) return;
    armTypedConfirm(container);
    armMinChars(container);
  }

  // ------------------------------------------------------------------ wiring

  document.addEventListener('click', function (event) {
    if (!event.target.closest) return;
    if (event.target.closest('[data-lifecycle-close]')) {
      closeDialog();
      return;
    }
    // A click that is NOT inside the dialog card closes it (backdrop taps) — but a click
    // inside a dropdown menu of the dialog never does, and neither does a plain text
    // selection drag that ends outside.
    var container = openContainer();
    if (container && event.target === container) closeDialog();
  });

  document.addEventListener('keydown', function (event) {
    if (event.key !== 'Escape') return;
    var container = openContainer();
    if (container) {
      closeDialog();
      return;
    }
    closeMenus();
  });

  // A dialog landing in one of the containers arms it (htmx swaps are afterSettle-clean,
  // but afterSwap is the earliest the controls exist).
  document.body.addEventListener('htmx:afterSwap', function (event) {
    var target = event.detail && event.detail.target;
    if (!target || !target.id) return;
    // The dialog landed.
    if (CONTAINERS.indexOf(target.id) !== -1) {
      arm(target);
      closeMenus();
      return;
    }
    // A Shape A success landed in a detail pane: its marker closes the dialog.
    if ((target.id === 'pipeline-detail' || target.id === 'template-detail') &&
        target.querySelector('[data-lifecycle-applied]')) {
      closeDialog();
      return;
    }
    // 140 — a swap INSIDE the open dialog (the release checks' run re-rendering the list
    // and splicing the submit footer in out-of-band) re-arms the inputs it just replaced.
    var open = openContainer();
    if (open && open !== target && open.contains(target)) rearm(open);
  });

  // ------------------------------------------------------------------ 5. menus

  /** Close every open ⋯ overflow menu (the rows' `<details>` popovers). */
  function closeMenus() {
    var menus = document.querySelectorAll('details.tplx-vmenu[open]');
    Array.prototype.forEach.call(menus, function (m) { m.open = false; });
  }

  /**
   * Where an open ⋯ menu goes — the pure half. `anchor` is the ⋯'s rect, `size` the
   * list's, `viewport` {width, height}; all CSS px. Right-aligned to the anchor and
   * `gap` below it; ABOVE it when the viewport has no room below and does above; when
   * neither side fits, the side with more room, clamped inside the viewport. The left
   * edge never leaves the viewport either (a narrow pane with a wide menu).
   */
  function menuPlacement(anchor, size, viewport, gap) {
    var g = typeof gap === 'number' ? gap : 0;
    var belowTop = anchor.bottom + g;
    var aboveTop = anchor.top - g - size.height;
    var fitsBelow = belowTop + size.height <= viewport.height;
    var fitsAbove = aboveTop >= 0;
    var top;
    if (fitsBelow) top = belowTop;
    else if (fitsAbove) top = aboveTop;
    else {
      var roomBelow = viewport.height - belowTop;
      var roomAbove = anchor.top - g;
      top = roomBelow >= roomAbove ? Math.max(0, viewport.height - size.height) : 0;
    }
    var left = Math.min(anchor.right - size.width, viewport.width - size.width);
    return { top: top, left: Math.max(0, left), above: top < anchor.top };
  }

  /** Show or hide one menu's list as its <details> toggles, and place it when shown. */
  function placeMenu(details) {
    var list = details.querySelector('.tplx-vmenu-list');
    var summary = details.querySelector('summary');
    if (!list || !summary) return;
    var popover = typeof list.showPopover === 'function';
    if (!details.open) {
      if (popover && list.matches(':popover-open')) list.hidePopover();
      return;
    }
    if (popover && !list.matches(':popover-open')) list.showPopover();
    var anchor = summary.getBoundingClientRect();
    var size = list.getBoundingClientRect();
    var gap = parseFloat(getComputedStyle(list).getPropertyValue('--gap-xs')) || 4;
    var at = menuPlacement(anchor, { width: size.width, height: size.height },
      { width: window.innerWidth, height: window.innerHeight }, gap);
    list.style.setProperty('--vmenu-top', at.top + 'px');
    list.style.setProperty('--vmenu-left', at.left + 'px');
  }

  // `toggle` does not bubble: capture it at the document so menus that arrive by htmx swap
  // need no per-element wiring.
  document.addEventListener('toggle', function (event) {
    var details = event.target;
    if (!details || !details.matches || !details.matches('details.tplx-vmenu')) return;
    placeMenu(details);
  }, true);

  /**
   * A placed menu is pinned to the viewport, not to the row, so a scroll or a resize moves
   * the ⋯ out from under it: re-place every open menu against where its ⋯ is NOW, and close
   * the ones whose ⋯ is no longer on screen (scrolled out of the panel or the viewport —
   * the point at the ⋯'s centre no longer resolves to it or to its own menu).
   */
  function replaceOpenMenus() {
    var menus = document.querySelectorAll('details.tplx-vmenu[open]');
    Array.prototype.forEach.call(menus, function (m) {
      var summary = m.querySelector('summary');
      var r = summary ? summary.getBoundingClientRect() : null;
      var hit = r ? document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2) : null;
      if (hit && hit.closest && hit.closest('details.tplx-vmenu') === m) placeMenu(m);
      else m.open = false;
    });
  }

  document.addEventListener('scroll', function () {
    if (document.querySelector('details.tplx-vmenu[open]')) replaceOpenMenus();
  }, true);
  window.addEventListener('resize', replaceOpenMenus);

  document.addEventListener('click', function (event) {
    if (!event.target.closest) return;
    if (event.target.closest('details.tplx-vmenu')) return;
    closeMenus();
  });

  var api = {
    confirmMatches: confirmMatches, closeDialog: closeDialog, closeMenus: closeMenus,
    menuPlacement: menuPlacement, minCharsMet: minCharsMet,
  };
  if (typeof window !== 'undefined') window.lifecycleDialog = api;
  if (typeof module !== 'undefined' && module.exports) module.exports = api;
})();
