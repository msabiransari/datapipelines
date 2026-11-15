// types/line.js : the line type (record §4.5 row line; look contract §2 line, weight, points; §4.5 labels: last; brief
// 989 D6). A plain function template (R17, O-6): the line family's shared model and marks (16-lines.js) with the line's
// options (points none | end | markers, curve linear | smooth | step, labels none | last); it never writes an axis, a
// legend or a title. Rows sharing x and series are aggregated; a null is a gap in the path; a temporal x is a
// continuous UTC time axis; a nominal x is evenly spaced; mark_count counts the non-null points.
(function (R) {
  "use strict";
  var L = R.lines;

  /** Line's channel descriptor (record §4.2), the same data as ChartType.line's in Kotlin (R-REG compares them). */
  var CHANNELS = [
    { key: "x", required: true, node: { node: "oneOf", of: [{ shape: "band", types: ["nominal", "ordinal"] }, { shape: "position", types: ["temporal", "quantitative"] }] } },
    { key: "y", required: true, node: { node: "shape", name: "value" } },
    { key: "color", required: false, node: { node: "shape", name: "series" } },
    { key: "tooltip", required: false, node: { node: "list", min: 0, max: 6, of: { node: "shape", name: "detail" } } },
  ];

  function model(doc, columns, types) {
    return L.model(doc, columns, types, { area: false });
  }

  R.type("line", { channels: CHANNELS, model: model, marks: L.marks, axes: "cartesian" });
})(JsRendered);
