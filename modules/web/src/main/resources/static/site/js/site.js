/* datapipelines.co — theme toggle, header menus + copy-to-clipboard only.
   The site is fully readable without this file: auto.css
   handles theming via prefers-color-scheme, and the header's
   menus are <details>, which open and close on their own. */
(function () {
  'use strict';

  /* ------------------------------------------------------
     Theme toggle
     Swaps the dp-theme stylesheet between auto / light /
     dark. All theme files open on :root (027: dark.css was
     re-vendored to match its siblings), so the swap alone
     activates the theme; the data-theme attribute is kept
     as harmless belt-and-braces for dark.
     ------------------------------------------------------ */
  var STORAGE_KEY = 'dp-site-theme';
  var THEME_DIR = '/vendor/design-system/themes/';
  var MODES = ['auto', 'light', 'dark'];
  var themeLink = document.getElementById('dp-theme');
  var toggle = document.querySelector('[data-theme-toggle]');

  function label(mode) {
    return 'Theme: ' + mode.charAt(0).toUpperCase() + mode.slice(1);
  }

  function applyTheme(mode) {
    if (!themeLink) {
      return;
    }
    /* 111 §C: auto's values ride the site-chrome bundle, so auto DISABLES the swap
       sheet (no fetch, no render-blocking request) instead of re-pointing it. */
    if (mode === 'auto') {
      themeLink.disabled = true;
    } else {
      themeLink.disabled = false;
      themeLink.setAttribute('href', THEME_DIR + mode + '.css');
    }
    if (mode === 'dark') {
      document.documentElement.setAttribute('data-theme', 'dark');
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
    if (toggle) {
      toggle.textContent = label(mode);
    }
  }

  var current = 'auto';
  try {
    var saved = window.localStorage.getItem(STORAGE_KEY);
    if (MODES.indexOf(saved) !== -1) {
      current = saved;
    }
  } catch (err) {
    /* localStorage unavailable — stay on auto */
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
     Late stylesheets (111 §C)
     The mono faces ship in a media="print" sheet so their
     bytes stay off the render-blocking chain; this flips it
     to "all". Runs before first paint on a normal parse,
     because this script sits at the end of the body.
     ------------------------------------------------------ */
  Array.prototype.forEach.call(
    document.querySelectorAll('link[data-late-style]'),
    function (link) {
      link.media = 'all';
    }
  );

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
     Copy-to-clipboard on code blocks
     Buttons ship with the `hidden` attribute; they appear
     only when this script runs.
     ------------------------------------------------------ */
  var blocks = document.querySelectorAll('.code-block');
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
