/* datapipelines.co — theme toggle + copy-to-clipboard only.
   The site is fully readable without this file: auto.css
   handles theming via prefers-color-scheme. */
(function () {
  'use strict';

  /* ------------------------------------------------------
     Theme toggle
     Swaps the dp-theme stylesheet between auto / light /
     dark. dark.css keys off [data-theme="dark"], so the
     attribute is set only while dark.css is active.
     ------------------------------------------------------ */
  var STORAGE_KEY = 'dp-site-theme';
  var THEME_DIR = 'assets/vendor/design-system/themes/';
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
    themeLink.setAttribute('href', THEME_DIR + mode + '.css');
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
