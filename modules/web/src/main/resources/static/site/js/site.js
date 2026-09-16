/* datapipelines.co — the public site's one script (145): the worked example's
   tabs, the phone menu's dismissal, copy-to-clipboard, the late mono sheet,
   the header hairline and the one-time entrances. The site is fully readable
   and navigable without this file: the tablist ships `hidden` and the three
   panels stack in order, the menu is a <details>, copy buttons ship `hidden`,
   and every element rests visible — motion only decorates a first entry.

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
     The phone menu — a <details>: open/close, a focusable summary
     and Enter/Space come from the element. This adds Escape (focus
     back on the summary) and a click outside. Scoped to the header;
     the FAQ <details> keep their own behaviour.
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
     The header's hairline appears once the page has scrolled,
     so the top of every page is clean.
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
})();
