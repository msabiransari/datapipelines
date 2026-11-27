// chart-sparkline.js : the page script of JsRenderedSparklineBrowserTest (brief 1076 A.7). It draws ONE emitted sparkline
// input, as the test names it, at the frame the test names and with an empty hidden list, writes it with
// JsRendered.writeDom and R.write.describe, and attaches the interaction layer with a host that counts every switch it
// is asked for and draws the same input again with the new hidden list (as the page adapter's toggle does). Nothing
// else: no markup string, no style attribute, no request but the input it is told to read.
(function () {
  "use strict";
  var R = window.JsRendered;
  var L = window.DatapipelinesChartInteractions;
  var svg = document.getElementById("chart");
  var layer = null;
  var kept = null;
  var hidden = [];
  var asked = [];
  var last = null;

  /** One draw of the kept input with the current hidden list; the layer attached by the first, updated by the rest. */
  function paint() {
    var input = Object.assign({}, kept, { hidden: hidden.slice() });
    var out = R.render(input);
    var drawn = out.state === "rendered" || out.state === "no-data";
    R.writeDom(drawn ? out.primitives : [], svg, input.theme, drawn ? R.write.describe(out, input.document) : undefined);
    var handed = { output: out, theme: input.theme, measure: input.measure, frame: input.frame };
    if (layer === null) layer = L.attach(svg, handed, { toggle: toggle });
    else layer.update(handed);
    last = { state: out.state, frame: input.frame, theme: input.theme, index: out.index, summary: out.summary, primitives: out.primitives };
    return last;
  }

  /** The host's one callback: counted, the id in or out of the list, the picture drawn again at once. */
  function toggle(id) {
    asked.push(id);
    var at = hidden.indexOf(id);
    if (at >= 0) hidden.splice(at, 1);
    else hidden.push(id);
    paint();
  }

  /** draw(file, frame): the emitted input emitted/sparkline/<file>, drawn at `frame` ({w, h}) with an empty hidden list. */
  async function draw(file, frame) {
    var response = await fetch("emitted/sparkline/" + file);
    if (!response.ok) throw new Error("chart-sparkline: emitted/sparkline/" + file + " answered " + response.status);
    var input = await response.json();
    input.frame = { w: frame.w, h: frame.h };
    kept = input;
    hidden = [];
    asked = [];
    return paint();
  }

  window.ChartSparkline = {
    draw: draw,
    /** Draw a test variant without fetching or changing another chart type. */
    drawInput: function (input, frame) { kept = input; kept.frame = frame; hidden = []; return paint(); },
    /** The last draw the page wrote. */
    last: function () {
      return last;
    },
    /** A copy of the kept input (the test hands it to the engine itself, with a hidden list of its own). */
    input: function () {
      return JSON.parse(JSON.stringify(kept));
    },
    /** The ids the host was asked to switch, in order. */
    asked: function () {
      return asked.slice();
    },
  };
})();
