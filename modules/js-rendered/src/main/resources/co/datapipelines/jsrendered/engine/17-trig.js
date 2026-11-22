// 17-trig.js : the engine's own sine and cosine and the arc helper (record §2 O-16, §9 G1; brief 1049 D4). Math.sin and
// Math.cos differ in their last bit between Node, a browser and GraalJS (lane 977's trap table), and the identity test
// compares bytes, so a polar type draws with these instead. Written with the four basic operations, comparisons,
// Math.sqrt and the remainder `%`, whose result is the exact remainder of two doubles (a double itself, so it rounds
// nowhere and agrees to the bit like the four operations).
//
// Angles are degrees, 0 at 12 o'clock, growing clockwise on the screen: the record's rule for the radar (§4.5) and the
// way the path primitive's `A` turns (08-prim.js: "the shorter way, clockwise"). point(cx, cy, r, deg) is
// (cx + r sin, cy − r cos). The method: the angle's absolute value reduced into [0, 360), folded into [0, 90] by the
// half-turn and quarter-turn symmetries, written as 30k + d with |d| ≤ 15, and sin(30k + d) = S_k cos d + C_k sin d over
// the four exact pairs (S_k, C_k) of 0°, 30°, 60° and 90°, with sin d and cos d from their series (|d| ≤ 15° in
// radians is below 0.27, so eight terms leave an error far below the last bit). So sin 0, sin 180, sin 360, cos 90 and
// cos 270 are exactly 0 (every zero is +0); sin 90 and cos 0 exactly 1; sin 30 and cos 60 exactly 0.5; sin(−a) =
// −sin(a) and sin(180 − a) = sin(a) to the bit wherever 180 − a is exact (a zero's sign aside: both are +0).
(function (R) {
  "use strict";
  var r2 = R.scales.r2;
  var RAD = 3.141592653589793 / 180;
  var HALF_ROOT3 = Math.sqrt(3) / 2;
  /** sin and cos of 0°, 30°, 60°, 90°: exact but for √3/2. */
  var S = [0, 0.5, HALF_ROOT3, 1];
  var C = [1, HALF_ROOT3, 0.5, 0];

  function finite(deg, what) {
    if (typeof deg !== "number" || !(deg - deg === 0)) throw new Error("trig: " + what + " takes a finite angle in degrees, not " + deg);
  }

  /** sin t for |t| ≤ 0.27 radians: t (1 − t²/3! (1 − t²/(4·5) (1 − …))), odd in t to the bit. */
  function sinSeries(t) {
    var u = t * t;
    return t * (1 - (u / 6) * (1 - (u / 20) * (1 - (u / 42) * (1 - (u / 72) * (1 - (u / 110) * (1 - (u / 156) * (1 - u / 210)))))));
  }

  /** cos t for |t| ≤ 0.27 radians, even in t. */
  function cosSeries(t) {
    var u = t * t;
    return 1 - (u / 2) * (1 - (u / 12) * (1 - (u / 30) * (1 - (u / 56) * (1 - (u / 90) * (1 - (u / 132) * (1 - u / 182))))));
  }

  /** sin of a in [0, 90]. */
  function quarter(a) {
    var k = a < 15 ? 0 : a < 45 ? 1 : a < 75 ? 2 : 3;
    var d = a - 30 * k;
    if (d === 0) return S[k];
    var t = d * RAD;
    return S[k] * cosSeries(t) + C[k] * sinSeries(t);
  }

  /** sin of a in [0, 360): +0, never −0, at 0 and 180. */
  function turn(a) {
    if (a >= 180) {
      var v = half(a - 180);
      return v === 0 ? 0 : -v;
    }
    return half(a);
  }

  /** sin of a in [0, 180). */
  function half(a) {
    return a > 90 ? quarter(180 - a) : quarter(a);
  }

  /** Every zero these return is +0 (never −0), so "exactly 0" holds under any comparison and prints as 0. */
  function sin(deg) {
    finite(deg, "sin");
    var v = turn((deg < 0 ? -deg : deg) % 360);
    return deg < 0 && v !== 0 ? -v : v;
  }

  function cos(deg) {
    finite(deg, "cos");
    var a = (deg < 0 ? -deg : deg) % 360;
    return turn((a + 90) % 360);
  }

  /** The point at angle deg on the circle (cx, cy, r), each coordinate rounded as the engine rounds one. */
  function point(cx, cy, r, deg) {
    return { x: r2(cx + r * sin(deg)), y: r2(cy - r * cos(deg)) };
  }

  /**
   * The path commands of the clockwise arc on the circle (cx, cy, r) from the point at `from` to the point at `to`, in
   * the path primitive's form ("A", r, x, y per piece; the caller writes the move to point(from) first). from ≤ to ≤
   * from + 360. One piece below 180°; two equal pieces from 180° to 360°, the full circle included, because `A` draws
   * the shorter way: no piece spans more than 180°, and only a full circle's two halves span exactly 180°, where the
   * writers' fixed clockwise sweep decides the side. to = from → no piece.
   */
  function arc(cx, cy, r, from, to) {
    finite(from, "arc");
    finite(to, "arc");
    if (!(to >= from && to - from <= 360)) throw new Error("trig: arc takes from ≤ to ≤ from + 360, not " + from + " to " + to);
    var span = to - from;
    if (span === 0) return [];
    var end = point(cx, cy, r, to);
    if (span < 180) return ["A", r, end.x, end.y];
    var mid = point(cx, cy, r, from + span / 2);
    return ["A", r, mid.x, mid.y, "A", r, end.x, end.y];
  }

  R.trig = { sin: sin, cos: cos, point: point, arc: arc };
})(JsRendered);
