/* datapipelines.co — the public site's one script (145; 148 added the Product
   disclosure): the worked example's tabs, the header menus' dismissal,
   copy-to-clipboard, the late mono sheet, the header hairline and the
   one-time entrances. The site is fully readable and navigable without this
   file: the tablist ships `hidden` and the three panels stack in order, both
   header menus are <details>, copy buttons ship `hidden`, and every element
   rests visible — motion only decorates a first entry.

   No style injection anywhere here (SitePublicPresentationGuardTest sweeps
   this file): state is attributes and classes, appearance is site.css. */
(function () {
  'use strict';

  var motionPreference = window.matchMedia('(prefers-reduced-motion: reduce)');

  /* ------------------------------------------------------
     Tabs (the worked example). ARIA tabs: arrows, Home and End
     move the selection, the selected tab is the only one in the
     tab order, and each panel is hidden or shown by its `hidden`
     attribute. Without this script the tablist stays hidden and
     the panels stack — every reader gets the content.
     ------------------------------------------------------ */
  var tablist = document.querySelector('[role="tablist"]');
  var tabs = tablist ? Array.prototype.slice.call(tablist.querySelectorAll('[role="tab"]')) : [];

  function activateTab(tab, focus) {
    tabs.forEach(function (candidate) {
      var selected = candidate === tab;
      candidate.setAttribute('aria-selected', String(selected));
      candidate.tabIndex = selected ? 0 : -1;
      var panel = document.getElementById(candidate.getAttribute('aria-controls'));
      if (panel) {
        panel.hidden = !selected;
        panel.classList.toggle('is-entering', selected && !motionPreference.matches);
      }
    });
    if (focus) {
      tab.focus();
    }
  }

  if (tabs.length) {
    tablist.hidden = false;
    tabs.forEach(function (tab) {
      tab.addEventListener('click', function () {
        activateTab(tab, false);
      });
      tab.addEventListener('keydown', function (event) {
        var keys = ['ArrowLeft', 'ArrowRight', 'Home', 'End'];
        if (keys.indexOf(event.key) === -1) {
          return;
        }
        event.preventDefault();
        var index = tabs.indexOf(tab);
        if (event.key === 'Home') {
          index = 0;
        } else if (event.key === 'End') {
          index = tabs.length - 1;
        } else {
          index = (index + (event.key === 'ArrowRight' ? 1 : -1) + tabs.length) % tabs.length;
        }
        activateTab(tabs[index], true);
      });
    });
    var initial = tabs.filter(function (tab) {
      return tab.getAttribute('aria-selected') === 'true';
    })[0] || tabs[0];
    activateTab(initial, false);
  }

  /* ------------------------------------------------------
     Late stylesheets (111 §C, re-measured 133): the mono faces ship
     in a media="print" sheet so they stay off the render-blocking
     chain; flipped to "all" on load, so first paint never waits.
     ------------------------------------------------------ */
  function flipLateStyles() {
    Array.prototype.forEach.call(document.querySelectorAll('link[data-late-style]'), function (link) {
      link.media = 'all';
    });
  }

  if (document.readyState === 'complete') {
    flipLateStyles();
  } else {
    window.addEventListener('load', flipLateStyles);
  }

  /* ------------------------------------------------------
     The header's menus — the Product disclosure (148) and the phone
     menu, both <details>: open/close, a focusable summary and
     Enter/Space come from the element. This adds Escape (focus back
     on the summary), a click outside, focus leaving the menu (Tab
     past its last link closes it, so nothing is left open behind the
     reader), one open at a time, and a close when the viewport
     crosses the header's breakpoint (a panel opened in one layout is
     not found open in the other). Scoped to the header; the FAQ
     <details> keep their own behaviour.
     ------------------------------------------------------ */
  var headerMenus = Array.prototype.slice.call(document.querySelectorAll('.site-header details'));

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

  function closeMenusExcept(kept) {
    headerMenus.forEach(function (menu) {
      if (menu !== kept) {
        closeMenu(menu, false);
      }
    });
  }

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
    headerMenus.forEach(function (menu) {
      menu.addEventListener('toggle', function () {
        if (menu.open) {
          closeMenusExcept(menu);
        }
      });
      menu.addEventListener('focusout', function (event) {
        if (event.relatedTarget && !menu.contains(event.relatedTarget)) {
          closeMenu(menu, false);
        }
      });
    });

    /* The breakpoint is the sheet's token (site.css declares it once and
       repeats it in the media query; the guard test holds the two equal). */
    var wideMin = window.getComputedStyle(document.documentElement).getPropertyValue('--site-nav-wide-min').trim();
    if (wideMin) {
      var wideQuery = window.matchMedia('(min-width: ' + wideMin + ')');
      if (wideQuery.addEventListener) {
        wideQuery.addEventListener('change', function () {
          closeMenusExcept(null);
        });
      }
    }
  }

  /* ------------------------------------------------------
     Copy-to-clipboard on code blocks, the install chip and the
     quickstart command. Buttons ship `hidden`; they appear only
     when this script runs. The fallback selects the text so a
     reader without clipboard access can still copy it.
     ------------------------------------------------------ */
  var blocks = document.querySelectorAll('.code-block, .install, .quick-code');
  Array.prototype.forEach.call(blocks, function (block) {
    var btn = block.querySelector('.copy-btn');
    var code = block.querySelector('code, .code');
    if (!btn || !code) {
      return;
    }
    btn.hidden = false;
    btn.addEventListener('click', function () {
      var text = code.textContent.trim();

      function confirm() {
        btn.textContent = 'Copied';
        window.setTimeout(function () {
          btn.textContent = 'Copy';
        }, 1600);
      }

      function fallback() {
        var selection = window.getSelection();
        var range = document.createRange();
        range.selectNodeContents(code);
        if (selection) {
          selection.removeAllRanges();
          selection.addRange(range);
        }
        btn.textContent = 'Select and copy';
      }

      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(text).then(confirm, fallback);
      } else {
        fallback();
      }
    });
  });

  /* ------------------------------------------------------
     Entrances: content is visible by default; motion decorates an
     element's FIRST entry into view and never repeats. Reduced
     motion disables it at load and, if the preference changes while
     the page is open, removes the class from everything.
     ------------------------------------------------------ */
  var motionTargets = Array.prototype.slice.call(document.querySelectorAll(
    '.section-title, .card, .knowledge .grid-2, .dark-grid, .status-box, .walkthrough, ' +
    '.use-card, .price-card, .media-frame, .final-cta, .mini-flow, .resource-grid, .quickstart'
  ));
  var revealed = [];
  var revealObserver = null;

  function configureMotion() {
    if (revealObserver) {
      revealObserver.disconnect();
      revealObserver = null;
    }
    if (motionPreference.matches) {
      Array.prototype.forEach.call(document.querySelectorAll('.is-entering'), function (element) {
        element.classList.remove('is-entering');
      });
      return;
    }
    if (!('IntersectionObserver' in window)) {
      return;
    }
    revealObserver = new IntersectionObserver(function (entries) {
      entries.forEach(function (entry) {
        if (!entry.isIntersecting || revealed.indexOf(entry.target) !== -1) {
          return;
        }
        entry.target.classList.add('is-entering');
        revealed.push(entry.target);
        revealObserver.unobserve(entry.target);
      });
    }, { threshold: 0.12 });
    motionTargets.forEach(function (target) {
      if (revealed.indexOf(target) === -1) {
        revealObserver.observe(target);
      }
    });
  }

  if (motionPreference.addEventListener) {
    motionPreference.addEventListener('change', configureMotion);
  }
  configureMotion();

  /* ------------------------------------------------------
     Docs anchors (713). The docs pages skip-render the body's
     off-screen parts (content-visibility in docs.css). The
     browser computes its jump-to-fragment destination from the
     first layout — where the parts above the target are still
     placeholders — and never re-runs it, so a deep link rests
     short of its heading. And the site chrome scrolls SMOOTH
     everywhere (scroll-behavior in site.css), so a plain
     re-scroll only starts an animation across tens of
     thousands of pixels: correct and instant here means
     behavior 'instant', not the sheet's smooth. CSS alone
     cannot express "the parts before the one containing
     :target" (:has() may not nest inside :has()), so first
     mark every part above the target rendered (the class
     turns its content-visibility off — class state, like the
     tabs above, never inline styles), then scroll again —
     bounded, one frame apart — until the heading holds its
     position in the viewport: each scrollIntoView re-reads
     the live layout, so the walk ends on true heights no
     matter which of the page's scroll writers ran last. Runs
     after the stylesheet (deferred scripts wait for the
     CSSOM), on load and on hashchange — the docs' in-page
     cross-references go through the same walk. No hash, a
     missing target, or a page without doc parts: untouched.
     ------------------------------------------------------ */
  function clearRenderedParts() {
    Array.prototype.forEach.call(
      document.querySelectorAll('.doc-part.is-rendered'),
      function (part) { part.classList.remove('is-rendered'); }
    );
  }

  function scrollToFragmentTarget() {
    var hash = window.location.hash;
    if (!hash || hash === '#') {
      return;
    }
    var id;
    try {
      id = decodeURIComponent(hash.slice(1));
    } catch (e) {
      id = hash.slice(1);
    }
    var target = document.getElementById(id);
    var part = target && target.closest ? target.closest('.doc-part') : null;
    if (!part) {
      return;
    }
    clearRenderedParts();
    var cursor = part.previousElementSibling;
    while (cursor) {
      if (cursor.classList && cursor.classList.contains('doc-part')) {
        cursor.classList.add('is-rendered');
      }
      cursor = cursor.previousElementSibling;
    }
    var attempts = 0;
    (function correct() {
      target.scrollIntoView({ behavior: 'instant', block: 'start' });
      var box = target.getBoundingClientRect();
      if (attempts++ < 10 && (box.top < -2 || box.top > window.innerHeight * 0.5)) {
        window.requestAnimationFrame(correct);
      }
    })();
  }
  scrollToFragmentTarget();
  window.addEventListener('hashchange', scrollToFragmentTarget);
})();
