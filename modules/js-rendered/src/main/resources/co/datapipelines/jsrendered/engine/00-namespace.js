// 00-namespace.js : the ONE global of the js-rendered engine (design record 2026-11-14 §4.3). Every engine file is a
// classic script that adds to it, loaded in the order of LOAD-ORDER.txt: the same files in the browser, in Node and in
// the GraalJS sandbox (D3). Nothing here keeps state between two pictures: the registries are filled once, at load,
// and only read after.
var JsRendered = {
  /** Carried in every output, so a page's picture and a server's picture can be matched (record §4.11). */
  version: "1.0.0",
  /** Advance-width tables by name (02-widths-*.js): data for measuring text where no font exists. */
  widthTables: {},
};
