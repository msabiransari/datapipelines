// tick-value-probes.js : the ranges, ticks and places an axis chooses at the ends of the number range (brief 1063: a
// tick as its decimal text, one value or two, the log floor and top, a log domain of one logarithm, the second axis
// below the power table and on one value) as ONE script both runtimes evaluate: Node in tick-values.test.mjs and the
// server's sandbox in JsRenderedTickValuesTest. Each compares the JSON text it gets with tick-value-probes.json, Node's output, byte for byte, so the
// two runtimes agree on these paths to the bit: a tick here is a decimal text read as a number, and both must read it
// to the same double. Numbers only: no tick text is in the output, so the lane that changes how a tick is written
// (#1059) does not change this file's snapshot. A classic script: one function of the engine's namespace, no module
// syntax, nothing of R-BAN.
function tickValueProbes(R) {
  "use strict";
  var S = R.scales, A = R.axes, MAX = Number.MAX_VALUE, MIN = Number.MIN_VALUE, out = [];
  var range = function (name, r) {
    out.push({ name: name, lo: r.lo, hi: r.hi, ticks: r.ticks });
  };
  // A linear range: a tick is its decimal value, at both ends of the doubles.
  var decimal = [[0, 1e30, 4], [0, 1e22, 5], [0, 3e25, 7], [-7e29, 1e30, 4], [0, 1.2e300, 4], [0, MAX, 4], [-MAX, 0, 3], [1e-322, 1e-321, 4], [MIN, 1e-322, 4], [-MIN, MIN, 4], [0, 2.2250738585072014e-308, 4], [3e-310, 7e-308, 5], [0, 1e-300, 4], [0, 1.5e308, 4], [-1e308, 5e307, 4]];
  // A span beyond the largest double: the two ends and their middle, at every count asked.
  var beyond = [[-MAX, MAX, 1], [-MAX, MAX, 4], [-1e308, 1e308, 1], [-1e308, MAX, 7]];
  // One value (the 16th digit is rounding) and two values (a difference of the 15th digit).
  var one = [[0.3, 0.30000000000000004, 4], [99.99999999999999, 100, 4], [100, 100.00000000000001, 4], [1.7e18, 1.700000000000001e18, 4], [-1, -0.9999999999999998, 4], [9999999999999998, 1e16, 4], [0.9999999999999999, 1, 4], [-5e98, -4.9999999999999986e98, 3], [1.7e308, 1.7000000000000001e308, 4]];
  var two = [[99.5, 99.7, 4], [1e9, 1000000001, 4], [999999999999999, 1e15, 4], [999999999999999, 1e15, 1], [-1e15, -999999999999999, 4], [0.999999999999999, 1, 4], [9.99999999999999e-6, 1e-5, 4], [9.99999999999999e-6, 1e-5, 1], [0.999999999999995, 1.00000000000002, 4], [0.999999999999995, 1.00000000000002, 2], [1, 1.0000000000001, 4], [9.99999999999999e199, 1e200, 4], [9.99999999999999e-201, 1e-200, 4]];
  var groups = [["decimal", decimal], ["beyond", beyond], ["one value", one], ["two values", two]];
  for (var g = 0; g < groups.length; g++) for (var i = 0; i < groups[g][1].length; i++) {
    var c = groups[g][1][i];
    range(groups[g][0] + ": nice " + c[0] + " .. " + c[1] + " in " + c[2], S.nice(c[0], c[1], c[2]));
  }
  // A log range: the floor below the power table, the top to the largest double.
  var logs = [[MIN, 1e-322, 4], [MIN, 1, 4], [MIN, MAX, 4], [3e-324, 2e-323, 2], [1e-300, 1e300, 4], [1, 1e300, 4], [1, 1e248, 4], [1e200, 1e305, 3], [1, 1.5e308, 4], [1e300, MAX, 4], [1e308, 1e308, 4], [9.9e307, 1e308, 4], [MAX, MAX, 4]];
  for (var l = 0; l < logs.length; l++) range("log: logNice " + logs[l][0] + " .. " + logs[l][1] + " in " + logs[l][2], S.logNice(logs[l][0], logs[l][1], logs[l][2], null));
  // The single axes as the charts ask for them, with the place of every tick and of both ends.
  var places = function (name, ax, ext) {
    var at = A.scaleOf(ax, 200, 0);
    out.push({ name: name, lo: ax.lo, hi: ax.hi, ticks: ax.ticks, at: ax.ticks.map(at), ends: [at(ext[0]), at(ext[1])] });
  };
  var values = [[0.3, 0.30000000000000004], [0, 1e30], [MIN, 1e-322], [-1, -0.9999999999999998], [999999999999999, 1e15], [-MAX, MAX]];
  for (var v = 0; v < values.length; v++) places("axis: valueAxis " + values[v][0] + " .. " + values[v][1], A.valueAxis("full", values[v], null, 200, null, 0), values[v]);
  var logAxes = [[MIN, 1e-322], [1, 1e300], [1e308, 1e308], [1, 1.5e308]];
  for (var x = 0; x < logAxes.length; x++) places("axis: logAxis " + logAxes[x][0] + " .. " + logAxes[x][1], A.logAxis("full", logAxes[x], null, 200, null), logAxes[x]);
  // A log domain whose two ends have one logarithm: placed by the linear scale.
  var same = [[8.488316745e130, 8.488316745000002e130], [7.215959968999993e39, 7.215959969e39], [1, 1000]];
  for (var d = 0; d < same.length; d++) {
    var d0 = same[d][0], d1 = same[d][1], f = S.log(d0, d1, 0, 300);
    out.push({ name: "place: log " + d0 + " .. " + d1, at: [f(d0), f(d0 / 2 + d1 / 2), f(d1)] });
    places("axis: logAxis over the domain " + d0 + " .. " + d1, A.logAxis("full", same[d], same[d], 200, null), same[d]);
  }
  // The second axis where the single axis's range starts below the power table, tops past it, or was one collapsed value; and on values the ticks
  // cannot tell apart, which are one value there too.
  var L2 = { lo: 0, hi: 1, ticks: [0, 1], log: false }, L3 = { lo: 0, hi: 1, ticks: [0, 0.5, 1], log: false };
  var LOG125 = { lo: 1, hi: 10, ticks: [1, 2, 5, 10], log: true };
  var OFFSET = { lo: 0, hi: 1, ticks: [0.2, 0.4, 0.6, 0.8], log: false };
  var second = [
    ["log extent MIN .. 1e-322 beside two edge ticks", true, [MIN, 1e-322], L2],
    ["log extent MIN .. 1e-322 beside three ticks", true, [MIN, 1e-322], L3],
    ["log extent MIN .. 1e-322 beside log 1, 2, 5, 10", true, [MIN, 1e-322], LOG125],
    ["log extent 1 .. 1e300 beside log 1, 2, 5, 10", true, [1, 1e300], LOG125],
    ["log extent 1 .. 1.5e308 beside two edge ticks", true, [1, 1.5e308], L2],
    ["linear extent -MIN .. MIN beside two edge ticks", false, [-MIN, MIN], L2],
    ["linear extent 0.3 .. 0.30000000000000004 beside two edge ticks", false, [0.3, 0.30000000000000004], L2],
    ["linear extent 0.3 .. 0.30000000000000004 beside three ticks", false, [0.3, 0.30000000000000004], L3],
    ["linear extent 0.3 .. 0.30000000000000004 beside inset ticks", false, [0.3, 0.30000000000000004], OFFSET],
    ["linear extent -1 .. -0.9999999999999998 beside three ticks", false, [-1, -0.9999999999999998], L3],
    ["linear extent 99.99999999999999 .. 100 beside three ticks", false, [99.99999999999999, 100], L3],
    ["linear extent 99.5 .. 99.7 beside three ticks", false, [99.5, 99.7], L3],
  ];
  for (var k = 0; k < second.length; k++) {
    var p = second[k], ax = A.secondAxis("full", { domain: null, format: null, log: p[1] }, p[2], p[3], 200);
    out.push({ name: "second: " + p[0], lo: ax.lo, hi: ax.hi, ticks: ax.ticks, rule: ax.rule });
  }
  return JSON.stringify(out);
}

// The control of the comparison, the same in both runs: the probes over a copy of the namespace in which ONE tick of
// ONE range is the product the engine wrote before lane 1063 (2.5e29 as 2.4999999999999997e29). Its text differs from
// tick-value-probes.json in that one number and nowhere else; each run asserts it, so a comparison that could not go
// red on a single tick is red itself.
function tickValueProbesPlanted(R) {
  var P = {}, S = {}, k;
  for (k in R) P[k] = R[k];
  for (k in R.scales) S[k] = R.scales[k];
  S.nice = function (a, b, n) {
    var r = R.scales.nice(a, b, n);
    if (!(a === 0 && b === 1e30)) return r;
    var t = r.ticks.slice();
    t[1] = 2.4999999999999997e29;
    return { lo: r.lo, hi: r.hi, step: r.step, ticks: t };
  };
  P.scales = S;
  return tickValueProbes(P);
}
