// 15-thin.js : thinning (record §4.9 "Thinning"; lane 972's correction "Thinning keeps four points per pixel column";
// brief 989 D8). A line-family series with more points than its drawing cap is thinned, never refused: per pixel
// column of the plot the first, the lowest, the highest and the last point are kept, in data order. A null ends a run,
// and columns are formed inside each run, so thinning never joins across a gap and never removes one. It works on the
// data points, before a step is expanded and before the curve is fitted. In a stack every series keeps the SAME x
// positions: the union of what each series would keep. Nothing here changes what is counted (mark_count counts the
// data) or what the index holds (every point).
(function (R) {
  "use strict";

  /**
   * The indices of one series to keep. xs = the points' x in px (ascending), ys = their values (null = a gap), both
   * of one length. Per run between nulls and per pixel column (Math.floor of x) inside it: the first, the lowest, the
   * highest and the last point, each once, in data order. keep = an array of booleans the kept indices are set in.
   */
  function series(xs, ys, keep) {
    var i = 0, n = xs.length;
    while (i < n) {
      if (ys[i] === null) {
        i++;
        continue;
      }
      // One column inside one run: from i while the column is the same and no null comes.
      var col = Math.floor(xs[i]), first = i, low = i, high = i, last = i, j = i + 1;
      while (j < n && ys[j] !== null && Math.floor(xs[j]) === col) {
        if (ys[j] < ys[low]) low = j;
        if (ys[j] > ys[high]) high = j;
        last = j;
        j++;
      }
      keep[first] = true;
      keep[low] = true;
      keep[high] = true;
      keep[last] = true;
      i = j;
    }
    return keep;
  }

  /**
   * The kept indices, ascending, of a set of series sharing their x positions: the union of each series' own kept
   * points (a stack's boundaries then still meet). lists = [ys of each series]; returns [index].
   */
  function union(xs, lists) {
    var keep = [];
    for (var s = 0; s < lists.length; s++) series(xs, lists[s], keep);
    var out = [];
    for (var i = 0; i < xs.length; i++) if (keep[i] === true) out.push(i);
    return out;
  }

  R.thin = { series: series, union: union };
})(JsRendered);
