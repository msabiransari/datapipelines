// types/area.js : the area type (record §4.5 row area; look contract §2 area; brief 989 D6). A plain function template
// (R17, O-6): the line family's shared model and marks (16-lines.js) with the area's options (curve linear | smooth |
// step; y.stack zero | normalize | null); it never writes an axis, a legend or a title. The baseline is always zero; an
// area fades from --chart-area-top under its line to --chart-area-bottom at the baseline; unstacked areas overlap; a
// null splits the area; mark_count counts the non-null points.
(function (R) {
  "use strict";
  var L = R.lines;

  /** Area's channel descriptor (record §4.2): line's (record §4.5 row area, "as line"); R-REG compares it with Kotlin's. */
  var CHANNELS = [
    { key: "x", required: true, node: { node: "oneOf", of: [{ shape: "band", types: ["nominal", "ordinal"] }, { shape: "position", types: ["temporal", "quantitative"] }] } },
    { key: "y", required: true, node: { node: "shape", name: "value" } },
    { key: "color", required: false, node: { node: "shape", name: "series" } },
    { key: "tooltip", required: false, node: { node: "list", min: 0, max: 6, of: { node: "shape", name: "detail" } } },
  ];

  function model(doc, columns, types) {
    return L.model(doc, columns, types, { area: true });
  }

  R.type("area", { channels: CHANNELS, model: model, marks: L.marks, axes: "cartesian" });
})(JsRendered);
