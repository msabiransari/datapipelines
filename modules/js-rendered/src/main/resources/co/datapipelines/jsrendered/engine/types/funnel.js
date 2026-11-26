// types/funnel.js — centred stage bars (§4.5; #1074). The none frame owns chrome.
(function (R) {
  "use strict";
  R.type("funnel", {
    channels: [
      { key: "stage", required: true, node: { node: "shape", name: "band" } },
      { key: "value", required: true, node: { node: "shape", name: "value" } },
    ],
    model: function () { return { series: [], markCount: 0, warnings: [] }; },
    marks: function () { return { primitives: [], lookup: [], count: 0 }; },
    axes: "none",
  });
})(JsRendered);
