// value-text-probes.js : the text of a number outside an axis (brief 1088: tooltip, value label, legend end, stack
// total) as ONE script both runtimes evaluate: Node in value-texts.test.mjs and the server's sandbox in
// JsRenderedValueTextsTest. The brief's table, both start points from both sides, the ends of the number range, where a
// number's own text changes its form (1e21, 1e-7), exact values delivered as text, and the values that are not finite.
// No picture of the fixtures reaches most of these cells. Each run compares the JSON text it gets with
// value-text-probes.json, Node's output, byte for byte: the same script, one expected text, two runtimes. A classic
// script (no export, nothing of R-BAN): the sandbox evaluates it in the engine's own context.
function valueTextProbes(R) {
  var T = R.text, out = [], i, j;
  var FORMATS = [null, "compact", "percent", "integer", "decimal(2)", "currency(USD)"];
  var VALUES = [
    // the brief's table
    ["1e18", 1e18], ["1.23456e18", 1234560000000000000], ["exact 25000000000000000", "25000000000000000"],
    ["1234.5", 1234.5], ["0.051", 0.051], ["0.05", 0.05], ["0.5", 0.5], ["0.0149", 0.0149], ["0.004", 0.004],
    ["0.0046", 0.0046], ["0.00001", 0.00001], ["0.0000099", 0.0000099], ["2.5 billionths", 2.5e-9],
    ["minus 2.5e17", -250000000000000000], ["zero", 0], ["999999999999999", 999999999999999], ["1e15", 1e15],
    // the other sign, and the start points from both sides
    ["minus 0.004", -0.004], ["minus 2.5 billionths", -2.5e-9], ["minus 1e15", -1e15], ["9.995e14", 999500000000000],
    ["1e13", 1e13], ["9.99e12", 9990000000000], ["0.0000001", 0.0000001], ["0.000000099", 0.000000099],
    // the ends of the number range, and where a number's own text changes its form
    ["the smallest double", 5e-324], ["the largest double", 1.7976931348623157e308],
    ["minus the largest double", -1.7976931348623157e308], ["1e21", 1e21], ["1e22", 1e22], ["1.5e300", 1.5e300],
    ["9.995e17", 9.995e17], ["9.994999 millionths", 0.000009994999], ["123456789012345680000", 123456789012345680000],
    ["0.1 + 0.2", 0.1 + 0.2], ["2 to the 53", 9007199254740992],
    // exact values, delivered as text
    ["exact 12345678901234567", "12345678901234567"], ["exact 1234567890123456789", "1234567890123456789"],
    ["exact 30 digits", "123456789012345678901234567891"], ["exact minus 1234567890123456789", "-1234567890123456789"],
    ["exact 12345678901234567890.1234", "12345678901234567890.1234"],
    ["exact 999999999999999.999", "999999999999999.999"],
    ["exact 0.0000001234567890123456789", "0.0000001234567890123456789"], ["exact 0.004999", "0.004999"],
    // the values that are not finite: the empty text (D6)
    ["infinity", Infinity], ["minus infinity", -Infinity], ["not a number", NaN]
  ];
  for (i = 0; i < VALUES.length; i++) {
    for (j = 0; j < FORMATS.length; j++) {
      out.push({ name: VALUES[i][0] + " as " + (FORMATS[j] === null ? "no format" : FORMATS[j]), text: T.value(VALUES[i][1], FORMATS[j]) });
    }
  }
  return JSON.stringify(out);
}

// The control of the comparison, the same in both runs: the probes over a copy of the namespace in which ONE text is
// written with a hyphen for its true minus. Its JSON differs from value-text-probes.json in that one character; each
// run asserts it, so a comparison that could not go red on a single text is red itself.
function valueTextProbesPlanted(R) {
  var P = {}, X = {}, k;
  for (k in R) P[k] = R[k];
  for (k in R.text) X[k] = R.text[k];
  X.value = function (v, format) {
    var t = R.text.value(v, format);
    return v === 2.5e-9 && format === null ? t.split("−").join("-") : t;
  };
  P.text = X;
  return valueTextProbes(P);
}
