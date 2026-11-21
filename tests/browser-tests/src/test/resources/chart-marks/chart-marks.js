// chart-marks.js : the page script of ChartMarkLookupBrowserTest (brief 1052 D11). It draws ONE emitted input of the
// test type test_marks, as the test names it, with that input's own theme and `measure` (so a card's lines are decided
// as the Node tests decide them), writes it with JsRendered.writeDom and attaches the interaction layer, or hands the
// layer the new draw. Nothing else: no markup string, no style attribute, no request but the input it is told to read.
(function () {
  "use strict";
  var R = window.JsRendered;
  var L = window.DatapipelinesChartInteractions;
  var svg = document.getElementById("chart");
  var layer = null;

  /** draw(file, frame): the emitted input emitted/<file>, drawn at its own frame or at `frame` ({w, h}) when given. */
  function draw(file, frame) {
    return fetch("emitted/" + file)
      .then(function (response) {
        if (!response.ok) throw new Error("chart-marks: emitted/" + file + " answered " + response.status);
        return response.json();
      })
      .then(function (input) {
        if (frame) input.frame = { w: frame.w, h: frame.h };
        var out = R.render(input);
        var drawn = out.state === "rendered" || out.state === "no-data";
        R.writeDom(drawn ? out.primitives : [], svg, input.theme, drawn ? R.write.describe(out, input.document) : undefined);
        var handed = { output: out, theme: input.theme, measure: input.measure, frame: input.frame };
        // The legend's switch is lane G6's and no test here presses it: the toggle draws nothing.
        if (layer === null) layer = L.attach(svg, handed, { toggle: function () {} });
        else layer.update(handed);
        return { state: out.state, frame: input.frame, theme: input.theme, index: out.index, summary: out.summary, primitives: out.primitives };
      });
  }

  window.ChartMarks = { draw: draw };
})();
