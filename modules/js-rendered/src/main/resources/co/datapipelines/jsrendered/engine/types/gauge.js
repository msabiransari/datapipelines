// Gauge (§4.5; #1075): one aggregate, on the polar frame.
(function (R) {
  "use strict";
  function model() { return { series: [], markCount: 0, warnings: [] }; }
  function marks() { return { primitives: [], lookup: [], count: 0 }; }
  R.type("gauge", { channels: [{ key: "value", required: true, node: { node: "shape", name: "value" } }], model: model, marks: marks, axes: "polar" });
})(JsRendered);
