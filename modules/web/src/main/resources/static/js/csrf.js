/*
 * The CSRF double-submit token, read in ONE place (097 §D).
 *
 * Cookie-authenticated state-changing requests carry the `dp_csrf` cookie's value in the
 * `DP-CSRF-Token` header (auth.md §8.4). htmx does it for every hx-* request through the
 * layout's inherited `hx-headers`; a raw `fetch` has to do it itself, and this is what it
 * calls. The template editor's inline script had grown a THIRD way of finding the token
 * (parsing the layout's `hx-headers` attribute out of the DOM) beside the cookie read
 * `pipeline-editor/draft.js` does — three readers of one value, one of them looking at an
 * attribute that exists for a different mechanism entirely.
 *
 * The value is URL-decoded: the cookie is written encoded and the header must carry the
 * raw token. An absent cookie yields "" rather than throwing — the server's 403
 * `auth.csrf.invalid` is the authority on a missing token, not the browser.
 */
(function () {
  "use strict";

  function readCookie(name, cookieString) {
    var source = typeof cookieString === "string" ? cookieString : typeof document !== "undefined" ? document.cookie : "";
    var match = source.match(new RegExp("(?:^|;\\s*)" + name + "=([^;]*)"));
    if (!match) return "";
    try {
      return decodeURIComponent(match[1]);
    } catch (e) {
      // A cookie value that is not valid percent-encoding is not our token; sending it
      // raw is better than throwing inside a click handler.
      return match[1];
    }
  }

  function token(cookieString) {
    return readCookie("dp_csrf", cookieString);
  }

  var api = { readCookie: readCookie, token: token };
  if (typeof module !== "undefined" && module.exports) module.exports = api; // node --test
  if (typeof window !== "undefined") window.DpCsrf = api;
})();
