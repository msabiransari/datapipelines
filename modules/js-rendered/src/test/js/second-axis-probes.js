// second-axis-probes.js : the second value axis (brief 1051 D5) on the inputs of the round-3 corrections, R1 to R3 (the
// landing verdict specs/js-rendered-types/landings/1051-r2.md), and of round 4's, R4 and the boundary bank's own-code
// fixes (landings/1051-r3.md), as ONE script both runtimes evaluate: Node in second-bounds.test.mjs and the server's
// sandbox in JsRenderedTestTypesTest. Each compares the JSON text it gets with second-axis-probes.json, Node's output,
// byte for byte, so the two runtimes agree on these paths to the bit. A picture cannot carry R2 to R4: their inputs
// (ends near ±1e308, subnormal ends) write tick texts hundreds of characters wide, which leave any frame (#1059); a
// fixture of test_two_axes carries R1 (signed-two-ticks). A classic script: one function of the engine's namespace, no
// module syntax, nothing of R-BAN.
function secondAxisProbes(R) {
  "use strict";
  var linear = function (domain) {
    return { domain: domain, format: null, log: false };
  };
  var L2 = { lo: 0, hi: 1, ticks: [0, 1], log: false };
  var L3 = { lo: 0, hi: 1, ticks: [0, 0.5, 1], log: false };
  var L5 = { lo: 0, hi: 1, ticks: [0, 0.25, 0.5, 0.75, 1], log: false };
  var OFFSET = { lo: 0, hi: 1, ticks: [0.2, 0.4, 0.6, 0.8], log: false };
  var LOG125 = { lo: 1, hi: 10, ticks: [1, 2, 5, 10], log: true };
  var HUGE = { lo: -1e308, hi: 1e308, ticks: [-1e308, 0, 1e308], log: false };
  var HUGE_ASYM = { lo: -1.5e308, hi: 1e308, ticks: [-1e308, 0, 1e308], log: false };
  var log = function (domain) {
    return { domain: domain, format: null, log: true };
  };
  var MAX = Number.MAX_VALUE, MIN = Number.MIN_VALUE;
  var own = R.axes.valueAxis("full", [-1e308, 1e308], null, 200, null, 0);
  var HUGE_ENGINE = { lo: own.lo, hi: own.hi, ticks: own.ticks, log: false };
  var probes = [
    ["r1 two edge ticks, extent -1..1", linear(null), [-1, 1], L2],
    ["r1 two edge ticks, extent -0.001..0.003", linear(null), [-0.001, 0.003], L2],
    ["r1 two edge ticks, extent -1e6..1", linear(null), [-1e6, 1], L2],
    ["r1 three ticks, extent -1..1", linear(null), [-1, 1], L3],
    ["r1 inset ticks, extent -3..0.2", linear(null), [-3, 0.2], OFFSET],
    ["r2 left -1e308..1e308, right domain 0..10", linear([0, 10]), [0, 10], HUGE],
    ["r2 left -1e308..1e308, right domain -10..0", linear([-10, 0]), [-10, 0], HUGE],
    ["r2 left -1.5e308..1e308, right domain 0..10", linear([0, 10]), [0, 10], HUGE_ASYM],
    ["r2 left chosen over -1e308..1e308, right extent 0..10", linear(null), [0, 10], HUGE_ENGINE],
    ["r2 right domain -1e308..1e308, three left ticks", linear([-1e308, 1e308]), [-1e308, 1e308], L3],
    ["r2 right domain -1e308..1e308, five left ticks", linear([-1e308, 1e308]), [-1e308, 1e308], L5],
    ["r2 right domain -MAX..MAX, log left 1, 2, 5, 10", linear([-MAX, MAX]), [-MAX, MAX], LOG125],
    ["r2 right extent -1e308..1e308 chosen by the engine", linear(null), [-1e308, 1e308], L3],
    ["r3 right domain 1e-322..1e-321, log left 1, 2, 5, 10", linear([1e-322, 1e-321]), [1e-322, 1e-321], LOG125],
    ["r3 right domain 1e-322..1e-321, three left ticks", linear([1e-322, 1e-321]), [1e-322, 1e-321], L3],
    ["r3 right domain -1e-321..-1e-322, log left 1, 2, 5, 10", linear([-1e-321, -1e-322]), [-1e-321, -1e-322], LOG125],
    ["r3 right domain 1e-306..9e-306, log left 1, 2, 5, 10", linear([1e-306, 9e-306]), [1e-306, 9e-306], LOG125],
    ["r3 right domain 5e-324..1e-322, five left ticks", linear([5e-324, 1e-322]), [5e-324, 1e-322], L5],
    ["r4 log right domain 1e308..MAX, three left ticks", log([1e308, MAX]), [1e308, MAX], L3],
    ["r4 log right domain 1e308..MAX, log left 1, 2, 5, 10", log([1e308, MAX]), [1e308, MAX], LOG125],
    ["r4 log right domain MIN..1e-322, three left ticks", log([MIN, 1e-322]), [MIN, 1e-322], L3],
    ["r4 log right domain MIN..1e-322, inset ticks", log([MIN, 1e-322]), [MIN, 1e-322], OFFSET],
    ["r4 log right domain MIN..MAX, log left 1, 2, 5, 10", log([MIN, MAX]), [MIN, MAX], LOG125],
    ["r4 right domain -MAX..-1, three left ticks", linear([-MAX, -1]), [-MAX, -1], L3],
    ["r4 right extent -2.2250738585072014e-308..2.2250738585072014e-308 chosen by the engine", linear(null), [-2.2250738585072014e-308, 2.2250738585072014e-308], L3],
    ["r4 right extent -1e-322..-5e-324 chosen by the engine, two edge ticks", linear(null), [-1e-322, -5e-324], L2],
  ];
  var out = [];
  for (var i = 0; i < probes.length; i++) {
    var p = probes[i], ax = R.axes.secondAxis("full", p[1], p[2], p[3], 200);
    out.push({ name: p[0], lo: ax.lo, hi: ax.hi, ticks: ax.ticks, texts: ax.texts, rule: ax.rule });
  }
  return JSON.stringify(out);
}
