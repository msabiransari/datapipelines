// The colour check of the look contract's LA14 (brief 1053 D5), for tests only: Node, never the engine, never a jar.
// The contract's numbers were measured with the first study's validator, a third party's file kept in the private
// store; the product's suite cannot read it and carries no copy of it. This file computes the same quantities from
// their published definitions, in code written here:
//  - a colour's channels (whole numbers 0 to 255) → linear red, green and blue by the sRGB transfer curve (IEC 61966-2-1);
//  - a colour-blind reader: the matrix of Machado, Oliveira and Fernandes (2009, "A Physiologically-based Model for
//    Simulation of Color Vision Deficiency", severity 1.0) for protanopia and for deuteranopia, applied to the linear
//    values, each result clamped to 0…1;
//  - OKLab (Björn Ottosson, 2020): the linear values through his first matrix, the cube root of each, then his second;
//  - a distance: the Euclidean distance of two colours in OKLab, times 100; a pair's colour-blind distance is the smaller
//    of its protan and its deutan distance; a set's number is the lowest over ALL its pairs.
// The brief's evidence script compares these numbers with the validator's, to 0.05, outside the product.

/** The contract's thresholds (LA14): a set passes with a colour-blind number of at least 8 AND a normal one of at least 15. */
export const CVD_TARGET = 8;
export const NORMAL_FLOOR = 15;

/** Machado, Oliveira and Fernandes (2009), Table 1 at severity 1.0, rows of a 3 × 3 matrix on linear RGB. */
const SIMULATION = {
  protan: [
    [0.152286, 1.052583, -0.204868],
    [0.114503, 0.786281, 0.099216],
    [-0.003882, -0.048116, 1.051998],
  ],
  deutan: [
    [0.367322, 0.860646, -0.227968],
    [0.280085, 0.672501, 0.047413],
    [-0.01182, 0.04294, 0.968881],
  ],
};

/** Ottosson's OKLab: linear sRGB → cone response (M1), and the cube-rooted response → L, a, b (M2). */
const TO_LMS = [
  [0.4122214708, 0.5363325363, 0.0514459929],
  [0.2119034982, 0.6806995451, 0.1073969566],
  [0.0883024619, 0.2817188376, 0.6299787005],
];
const TO_LAB = [
  [0.2104542553, 0.793617785, -0.0040720468],
  [1.9779984951, -2.428592205, 0.4505937099],
  [0.0259040371, 0.7827717662, -0.808675766],
];

const times = (M, v) => M.map((row) => row[0] * v[0] + row[1] * v[1] + row[2] * v[2]);
const clamp01 = (x) => (x < 0 ? 0 : x > 1 ? 1 : x);

/** [r, g, b] (0 to 255) or "#rrggbb" → linear red, green and blue (0 to 1). */
export function linear(colour) {
  const rgb = typeof colour === "string" ? [1, 3, 5].map((i) => parseInt(colour.slice(i, i + 2), 16)) : colour;
  if (rgb.length < 3 || rgb.slice(0, 3).some((c) => !Number.isInteger(c) || c < 0 || c > 255)) throw new Error("palette: not a colour " + colour);
  return rgb.slice(0, 3).map((c) => {
    const s = c / 255;
    return s <= 0.04045 ? s / 12.92 : Math.pow((s + 0.055) / 1.055, 2.4);
  });
}

/** OKLab [L, a, b] of linear values. */
function lab(lin) {
  return times(TO_LAB, times(TO_LMS, lin).map(Math.cbrt));
}

/** OKLab of a colour as a reader sees it: `vision` null (normal), "protan" or "deutan". */
export function oklab(colour, vision = null) {
  const lin = linear(colour);
  return lab(vision === null ? lin : times(SIMULATION[vision], lin).map(clamp01));
}

/** A colour's OKLab lightness. */
export function lightness(colour) {
  return oklab(colour)[0];
}

const distance = (p, q) => 100 * Math.hypot(p[0] - q[0], p[1] - q[1], p[2] - q[2]);

/** A pair's two distances: {normal, cvd}, cvd the smaller of the protan and the deutan distance (with both). */
export function distances(a, b) {
  const protan = distance(oklab(a, "protan"), oklab(b, "protan")), deutan = distance(oklab(a, "deutan"), oklab(b, "deutan"));
  return { normal: distance(oklab(a), oklab(b)), cvd: Math.min(protan, deutan), protan, deutan };
}

/**
 * A set's numbers over ALL its pairs: {cvd, normal, cvdPair, normalPair, pass}; a pair is [i, j] into `colours`.
 * pass is the contract's: cvd ≥ CVD_TARGET and normal ≥ NORMAL_FLOOR (a cvd from 6 to 8, the validator's `floor`, fails).
 */
export function check(colours) {
  if (colours.length < 2) throw new Error("palette: a set needs two colours");
  let cvd = Infinity, normal = Infinity, cvdPair = null, normalPair = null;
  for (let i = 0; i < colours.length; i++) {
    for (let j = i + 1; j < colours.length; j++) {
      const d = distances(colours[i], colours[j]);
      if (d.cvd < cvd) [cvd, cvdPair] = [d.cvd, [i, j]];
      if (d.normal < normal) [normal, normalPair] = [d.normal, [i, j]];
    }
  }
  return { cvd, normal, cvdPair, normalPair, pass: cvd >= CVD_TARGET && normal >= NORMAL_FLOOR };
}
