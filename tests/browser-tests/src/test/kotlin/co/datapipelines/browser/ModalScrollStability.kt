package co.datapipelines.browser

import com.microsoft.playwright.Page
import com.microsoft.playwright.TimeoutError

/**
 * 454 (#454) — waits until the first element [selector] matches sits inside the viewport with
 * its top and bottom unchanged across two animation frames: the scroll has SETTLED (301's
 * event-based replacement for a fixed sleep, shared by ModalBackBrowserTest's version-menu walk
 * and ModalScrollStabilityBrowserTest).
 *
 * Each observation is a COMPLETED `page.evaluate`: Playwright awaits the two-frame Promise and
 * hands back its Boolean, and `page.waitForCondition` decides from that Boolean, observing again
 * on false within the page's default patience (BrowserSuite's per-action timeout — 30 s locally,
 * CI's 90 s under the gates). It replaced a `page.waitForFunction` whose predicate RETURNED that
 * Promise: the installed driver stops polling as soon as the predicate's value is truthy, which a
 * Promise always is, so the first observation ended the wait even when it resolved to false.
 */
internal fun awaitSettledInViewport(
    page: Page,
    selector: String,
) {
    try {
        page.waitForCondition { page.evaluate(SETTLED_OBSERVATION, selector) == true }
    } catch (e: TimeoutError) {
        throw AssertionError("$selector never sat inside the viewport unchanged across two animation frames — url=${page.url()}", e)
    }
}

/** One observation: present, inside the viewport, and the same top and bottom two animation frames later. */
private const val SETTLED_OBSERVATION = """(sel) => {
  const el = document.querySelector(sel);
  if (!el) return false;
  const rect = el.getBoundingClientRect();
  if (rect.top < 0 || rect.bottom > innerHeight) return false;
  return new Promise(done => requestAnimationFrame(() => requestAnimationFrame(() => {
    const after = el.getBoundingClientRect();
    done(after.top === rect.top && after.bottom === rect.bottom);
  })));
}"""
