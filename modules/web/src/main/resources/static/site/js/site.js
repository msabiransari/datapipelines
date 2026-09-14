/* datapipelines.co — theme toggle, header menus + copy-to-clipboard only.
   The site is fully readable without this file: auto.css
   handles theming via prefers-color-scheme, the header's
   menus are <details>, which open and close on their own,
   and the saved-theme restore runs INLINE in the head (133)
   so this file loads `defer`red, off the critical chain. */
(function () {
  'use strict';

  /* ------------------------------------------------------
     Theme toggle
     Swaps the dp-theme stylesheet between auto / light /
     dark. All theme files open on :root (027: dark.css was
     re-vendored to match its siblings), so the swap alone
     activates the theme. 133: the SITE's palette is the
     `--site-*` three-state layer in site.css, driven by the
     data-theme attribute — 'light' sets it explicitly (the
     dark media query is guarded by :not([data-theme="light"])),
     'dark' sets it, 'auto' removes it. The swap sheet stays
     for the design-system `--_*` values. Default on a first
     visit is LIGHT (owner ruling 2026-09-14).
     ------------------------------------------------------ */
  var STORAGE_KEY = 'dp-site-theme';
  var THEME_DIR = '/vendor/design-system/themes/';
  var MODES = ['auto', 'light', 'dark'];
  var themeLink = document.getElementById('dp-theme');
  var toggle = document.querySelector('[data-theme-toggle]');

  function label(mode) {
    return mode.charAt(0).toUpperCase() + mode.slice(1);
  }

  function applyTheme(mode) {
    if (!themeLink) {
      return;
    }
    /* 111 §C: the swap sheet stays DISABLED unless it has something to say.
       auto's values ride the site-chrome bundle; light is the bundle's bare
       :root since 133 (the site palette's three states live in site.css, and
       the bridged semantic tokens mean no `--_*` read remains for the sheet
       to answer) — enabling light.css here cost the default first visit a
       render-blocking fetch for zero visible change (Lighthouse 133: / at 94).
       Only dark still points the sheet, for the design-system `--_*` values. */
    if (mode === 'dark') {
      themeLink.disabled = false;
      themeLink.setAttribute('href', THEME_DIR + mode + '.css');
    } else {
      themeLink.disabled = true;
    }
    if (mode === 'auto') {
      document.documentElement.removeAttribute('data-theme');
    } else {
      document.documentElement.setAttribute('data-theme', mode);
    }
    if (toggle) {
      toggle.querySelector('[data-theme-label]').textContent = label(mode);
    }
  }

  var current = 'light';
  try {
    var saved = window.localStorage.getItem(STORAGE_KEY);
    if (MODES.indexOf(saved) !== -1) {
      current = saved;
    }
  } catch (err) {
    /* localStorage unavailable — stay on the default */
  }
  applyTheme(current);

  if (toggle) {
    toggle.hidden = false;
    toggle.addEventListener('click', function () {
      current = MODES[(MODES.indexOf(current) + 1) % MODES.length];
      applyTheme(current);
      try {
        window.localStorage.setItem(STORAGE_KEY, current);
      } catch (err) {
        /* persistence is best-effort */
      }
    });
  }

  /* ------------------------------------------------------
     Late stylesheets (111 §C, re-measured 133)
     The mono faces ship in a media="print" sheet so their
     186 KB stays off the render-blocking chain; this flips it
     to "all" — but on `load`, not at parse time. A flip that
     lands before first paint makes the pending sheet render-
     relevant again and first paint waits ~1.05s for the two
     faces under the throttled harness (the bimodal FCP that
     kept /how-it-works under the Lighthouse floor). Flipping
     on load keeps first paint free of the faces in every
     connection class: fast ones still get the real face, slow
     ones keep the fallback — the trade font-display: optional
     already makes for late arrivals.
     ------------------------------------------------------ */
  function flipLateStyles() {
    Array.prototype.forEach.call(
      document.querySelectorAll('link[data-late-style]'),
      function (link) {
        link.media = 'all';
      }
    );
  }

  if (document.readyState === 'complete') {
    flipLateStyles();
  } else {
    window.addEventListener('load', flipLateStyles);
  }

  /* ------------------------------------------------------
     Header menus (130 §A.1)
     Both header menus — the Product disclosure and the
     phone menu — are <details>: open/close, a focusable
     summary and Enter/Space come from the element. This
     adds the three things it lacks: Escape closes and puts
     focus back on the summary, a click outside closes, and
     only one of them is open at a time. Scoped to the
     header — the FAQ <details> on the page keep their own
     behaviour.
     ------------------------------------------------------ */
  var headerMenus = Array.prototype.slice.call(
    document.querySelectorAll('.site-header details')
  );

  function closeMenu(menu, refocus) {
    if (!menu.open) {
      return;
    }
    menu.open = false;
    if (refocus) {
      var summary = menu.querySelector('summary');
      if (summary) {
        summary.focus();
      }
    }
  }

  headerMenus.forEach(function (menu) {
    menu.addEventListener('toggle', function () {
      if (!menu.open) {
        return;
      }
      headerMenus.forEach(function (other) {
        if (other !== menu) {
          closeMenu(other, false);
        }
      });
    });
  });

  if (headerMenus.length) {
    document.addEventListener('keydown', function (event) {
      if (event.key !== 'Escape') {
        return;
      }
      headerMenus.forEach(function (menu) {
        closeMenu(menu, true);
      });
    });
    document.addEventListener('click', function (event) {
      headerMenus.forEach(function (menu) {
        if (!menu.contains(event.target)) {
          closeMenu(menu, false);
        }
      });
    });
  }

  /* ------------------------------------------------------
     The header's hairline (133): it appears only once the
     page has scrolled, so the top of every page is clean.
     ------------------------------------------------------ */
  var siteHeader = document.querySelector('.site-header');
  if (siteHeader) {
    var onScroll = function () {
      siteHeader.classList.toggle('scrolled', window.scrollY > 8);
    };
    window.addEventListener('scroll', onScroll, { passive: true });
    onScroll();
  }

  /* ------------------------------------------------------
     Copy-to-clipboard on code blocks and the install chip
     Buttons ship with the `hidden` attribute; they appear
     only when this script runs.
     ------------------------------------------------------ */
  var blocks = document.querySelectorAll('.code-block, .install');
  Array.prototype.forEach.call(blocks, function (block) {
    var btn = block.querySelector('.copy-btn');
    var code = block.querySelector('code');
    if (!btn || !code) {
      return;
    }
    btn.hidden = false;
    btn.addEventListener('click', function () {
      var text = code.textContent;

      function confirm() {
        btn.textContent = 'Copied';
        window.setTimeout(function () {
          btn.textContent = 'Copy';
        }, 1600);
      }

      function fallback() {
        var ta = document.createElement('textarea');
        ta.value = text;
        ta.setAttribute('readonly', '');
        document.body.appendChild(ta);
        ta.select();
        try {
          document.execCommand('copy');
          confirm();
        } catch (err) {
          /* leave the button label unchanged */
        }
        document.body.removeChild(ta);
      }

      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(confirm, fallback);
      } else {
        fallback();
      }
    });
  });
})();
