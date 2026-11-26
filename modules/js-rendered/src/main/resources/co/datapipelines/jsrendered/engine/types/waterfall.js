// Waterfall registration (#1073); the implementation follows with its geometry tests.
(function (R) {
  "use strict";
  var CHANNELS = [
    { key: "x", required: true, node: { node: "shape", name: "band" } },
    { key: "y", required: true, node: { node: "shape", name: "value" } },
  ];
  function model() { return { series: [], markCount: 0 }; }
  function marks() { return []; }
  R.type("waterfall", { channels: CHANNELS, model: model, marks: marks, axes: "cartesian" });
})(JsRendered);
