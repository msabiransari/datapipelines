// 05-theme.js : the look's tokens (record §4.10, the look contract's §3). Every token the engine receives has a
// built-in default under the theme names `light` and `dark`: the contract's two columns, name by name. `--chart-pad`
// and `--chart-tip-shadow` are the stylesheet's alone: not members of `theme`, no default here (contract §3.7).
// A caller passes `theme` as a name ("light", "dark") or as {base, tokens}: tokens resolved by the page's adapter,
// colours as whole-number channels [r, g, b] or [r, g, b, a], numbers inside the contract's §3.7 ranges. A value the
// grammar does not admit is the adapter's bug and throws, naming the token.
// Also here: the ink of a text on a fill (contract §4.6, L6, D6a), over the sRGB table of 04-srgb.js.
(function (R) {
  "use strict";
  function hex(s) {
    return [parseInt(s.slice(1, 3), 16), parseInt(s.slice(3, 5), 16), parseInt(s.slice(5, 7), 16)];
  }
  var TRANSPARENT = [0, 0, 0, 0];

  /** name → [kind, light, dark]; kind: colour (alpha allowed), opaque, length, share, duration, weight, font. */
  var TOKENS = {
    "--chart-canvas": ["colour", hex("#ffffff"), hex("#171b21")],
    "--chart-plot": ["colour", TRANSPARENT, TRANSPARENT],
    "--chart-grid": ["colour", hex("#e6e9ee"), hex("#262c35")],
    "--chart-axis": ["colour", hex("#a7acb4"), hex("#616772")],
    "--chart-ink": ["colour", hex("#0f172a"), hex("#e9ecf2")],
    "--chart-ink-2": ["colour", hex("#334155"), hex("#a6afbf")],
    "--chart-muted": ["colour", hex("#64748b"), hex("#8993a5")],
    "--chart-on-fill-light": ["opaque", hex("#ffffff"), hex("#ffffff")],
    "--chart-on-fill-dark": ["opaque", hex("#111418"), hex("#111418")],
    "--font-sans": ["font", "Noto Sans", "Noto Sans"],
    "--chart-series-1": ["opaque", hex("#3b5bdb"), hex("#5c7cfa")],
    "--chart-series-2": ["opaque", hex("#e8590c"), hex("#e8590c")],
    "--chart-series-3": ["opaque", hex("#0ca678"), hex("#0fa678")],
    "--chart-series-4": ["opaque", hex("#c98500"), hex("#b88a00")],
    "--chart-series-5": ["opaque", hex("#d6336c"), hex("#e64980")],
    "--chart-series-6": ["opaque", hex("#1098ad"), hex("#1299ac")],
    "--chart-series-7": ["opaque", hex("#7048e8"), hex("#9775fa")],
    "--chart-series-8": ["opaque", hex("#5c940d"), hex("#66a80f")],
    "--chart-seq-1": ["opaque", hex("#ebeffb"), hex("#222b44")],
    "--chart-seq-2": ["opaque", hex("#cbd2ea"), hex("#323e63")],
    "--chart-seq-3": ["opaque", hex("#aab4d9"), hex("#425081")],
    "--chart-seq-4": ["opaque", hex("#8a97c8"), hex("#5363a0")],
    "--chart-seq-5": ["opaque", hex("#6979b7"), hex("#6376be")],
    "--chart-seq-6": ["opaque", hex("#495ca6"), hex("#7388dd")],
    "--chart-seq-7": ["opaque", hex("#283e95"), hex("#839bfb")],
    "--chart-positive": ["opaque", hex("#1971c2"), hex("#4dabf7")],
    "--chart-negative": ["opaque", hex("#d9480f"), hex("#ff922b")],
    "--chart-total": ["opaque", hex("#343a40"), hex("#dee2e6")],
    "--chart-good": ["opaque", hex("#00755a"), hex("#42ae8e")],
    "--chart-warn": ["opaque", hex("#ce7c04"), hex("#fab72a")],
    "--chart-bad": ["opaque", hex("#cd171e"), hex("#d42325")],
    "--chart-div-1": ["opaque", hex("#283e95"), hex("#839bfb")],
    "--chart-div-2": ["opaque", hex("#6279b6"), hex("#6375b5")],
    "--chart-div-3": ["opaque", hex("#a5b3d5"), hex("#465174")],
    "--chart-div-4": ["opaque", hex("#eceef2"), hex("#2a2f38")],
    "--chart-div-5": ["opaque", hex("#d7a699"), hex("#674c3b")],
    "--chart-div-6": ["opaque", hex("#b95d42"), hex("#a86734")],
    "--chart-div-7": ["opaque", hex("#7f2400"), hex("#ee820b")],
    "--chart-text": ["length", 12, 12, 8, 32],
    "--chart-text-small": ["length", 11, 11, 8, 32],
    "--chart-title": ["length", 14, 14, 8, 48],
    "--chart-title-weight": ["weight", 600, 600, 100, 900],
    "--chart-bar-radius": ["length", 4, 4, 0, 32],
    "--chart-cell-radius": ["length", 2.5, 2.5, 0, 32],
    "--chart-tip-radius": ["length", 10, 10, 0, 32],
    "--chart-gap": ["length", 2, 2, 0, 8],
    "--chart-line": ["length", 2, 2, 0, 8],
    "--chart-stroke": ["length", 1, 1, 0, 8],
    "--chart-point": ["length", 4, 4, 1, 16],
    "--chart-area-top": ["share", 0.38, 0.38, 0, 1],
    "--chart-area-bottom": ["share", 0.02, 0.02, 0, 1],
    "--chart-dim": ["share", 0.25, 0.25, 0, 1],
    "--chart-ease": ["duration", 120, 120, 0, 1000],
    "--chart-tip-surface": ["colour", hex("#ffffff"), hex("#232831")],
    "--chart-tip-ink": ["colour", hex("#0f172a"), hex("#e9ecf2")],
    "--chart-tip-border": ["colour", hex("#e3e6ea"), hex("#2b313a")],
  };

  function two(n) {
    var s = n.toString(16);
    return s.length < 2 ? "0" + s : s;
  }

  /** A colour's text in a primitive: #rrggbb when opaque, rgba(r,g,b,a) otherwise. */
  function css(c) {
    if (c.length === 3 || c[3] === 1) return "#" + two(c[0]) + two(c[1]) + two(c[2]);
    return "rgba(" + c[0] + "," + c[1] + "," + c[2] + "," + String(c[3]) + ")";
  }

  function checkColour(name, v, opaque) {
    var ok = Array.isArray(v) && (v.length === 3 || (v.length === 4 && !opaque));
    for (var i = 0; ok && i < 3; i++) ok = typeof v[i] === "number" && v[i] >= 0 && v[i] <= 255 && Math.floor(v[i]) === v[i];
    if (ok && v.length === 4) ok = typeof v[3] === "number" && v[3] >= 0 && v[3] <= 1;
    if (!ok) throw new Error("theme: " + name + " is not " + (opaque ? "an opaque colour [r, g, b]" : "a colour [r, g, b] or [r, g, b, a]"));
    return v.slice();
  }

  function check(name, kind, v) {
    var spec = TOKENS[name];
    if (kind === "colour" || kind === "opaque") return checkColour(name, v, kind === "opaque");
    if (kind === "font") {
      if (typeof v !== "string" || v === "") throw new Error("theme: " + name + " is not a font family");
      return v;
    }
    if (typeof v !== "number" || !(v >= spec[3] && v <= spec[4])) throw new Error("theme: " + name + " is outside " + spec[3] + " to " + spec[4]);
    return v;
  }

  /** The resolved theme: {name, colour(name) → [r,g,b(,a)], css(name) → text, n(name) → number, font}. */
  function resolve(theme) {
    var base = "light", given = null;
    if (theme === null || theme === undefined) base = "light";
    else if (typeof theme === "string") base = theme;
    else if (typeof theme === "object" && !Array.isArray(theme)) {
      for (var k in theme) if (Object.prototype.hasOwnProperty.call(theme, k) && k !== "base" && k !== "tokens") throw new Error("theme: unknown member " + k);
      base = theme.base === undefined ? "light" : theme.base;
      given = theme.tokens === undefined ? {} : theme.tokens;
      if (given === null || typeof given !== "object" || Array.isArray(given)) throw new Error("theme: tokens is not an object");
    } else throw new Error("theme is neither a name nor {base, tokens}");
    if (base !== "light" && base !== "dark") throw new Error("theme: unknown theme name " + base);
    var col = base === "light" ? 1 : 2, values = {};
    for (var name in TOKENS) if (Object.prototype.hasOwnProperty.call(TOKENS, name)) values[name] = TOKENS[name][col];
    if (given !== null) {
      for (var g in given) {
        if (!Object.prototype.hasOwnProperty.call(given, g)) continue;
        if (!Object.prototype.hasOwnProperty.call(TOKENS, g)) throw new Error("theme: unknown token " + g);
        values[g] = check(g, TOKENS[g][0], given[g]);
      }
    }
    return {
      name: base,
      colour: function (n) {
        return values[n];
      },
      css: function (n) {
        return css(values[n]);
      },
      n: function (n) {
        return values[n];
      },
      font: values["--font-sans"],
    };
  }

  /** The defaults of one theme as {token: value}, for the tests (LA1) and the documentation. */
  function defaults(base) {
    var col = base === "light" ? 1 : base === "dark" ? 2 : 0, out = {};
    if (col === 0) throw new Error("unknown theme name " + base);
    for (var name in TOKENS) if (Object.prototype.hasOwnProperty.call(TOKENS, name)) out[name] = TOKENS[name][col];
    return out;
  }

  // ---- text on a fill (contract §4.6, L6): the WCAG contrast over the 256-entry sRGB table ----

  function luminance(c) {
    var L = R.srgbLinear;
    return 0.2126 * L[c[0]] + 0.7152 * L[c[1]] + 0.0722 * L[c[2]];
  }

  function contrast(a, b) {
    var x = luminance(a), y = luminance(b);
    return x > y ? (x + 0.05) / (y + 0.05) : (y + 0.05) / (x + 0.05);
  }

  /**
   * Which on-fill ink a text inside `fill` takes: "--chart-on-fill-light" or "--chart-on-fill-dark", the one whose WCAG
   * contrast against the fill is greater, the light one when they are equal. All three colours opaque, whole channels.
   */
  function onFill(fill, light, dark) {
    checkColour("fill", fill, true);
    checkColour("--chart-on-fill-light", light, true);
    checkColour("--chart-on-fill-dark", dark, true);
    return contrast(fill, light) >= contrast(fill, dark) ? "--chart-on-fill-light" : "--chart-on-fill-dark";
  }

  /** The token names of the series colours, in slot order. */
  var SERIES = ["--chart-series-1", "--chart-series-2", "--chart-series-3", "--chart-series-4", "--chart-series-5", "--chart-series-6", "--chart-series-7", "--chart-series-8"];

  R.theme = { TOKENS: TOKENS, SERIES: SERIES, resolve: resolve, defaults: defaults, css: css, luminance: luminance, contrast: contrast, onFill: onFill };
})(JsRendered);
