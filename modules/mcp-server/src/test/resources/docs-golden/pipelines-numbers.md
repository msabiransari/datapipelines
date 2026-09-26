
# Get the numbers right — the contract before the arithmetic

Every number the pipeline emits is the end of a chain: what is counted, over which rows, in
which unit, weighted how, computed at what precision, ranked by what rule. Decide the chain in
this order, write each decision into the description (the pipelines guide's step 4), and
verify the DECISIONS before you verify the arithmetic — a formula that reproduces its own
output proves consistency, not that the right quantity was computed.

1. **State the measurement contract before you write a formula.** For every quantity: the
   population (which rows are eligible, and which are excluded — a distance or price filter
   is an eligibility rule, not a cleanup), the time window, the unit, the sampling basis
   (sample or census, at what stated rate), and the output grain. Where a material ambiguity
   remains — two readings of the question, two candidate denominators, a filter that moves
   the answer — resolve it with the person, not by choosing quietly. Reuse a recorded
   `definition` only when it answers THIS question; a rule chosen for another measure is not
   automatically yours. Counting and measuring may need different eligibility: a row can
   count as an event and still be unfit for a per-mile or per-minute metric — separate the
   two populations when they differ, and say so.
2. **Normalize before you combine.** Two quantities enter one total, one share, one
   comparison or one ranking only when they are the same kind of thing. A sampled count and a
   census count are not: apply the stated sampling weight to every sampled count BEFORE the
   sum, the share and the rank. A constant multiplier preserves order only when it
   multiplies the whole score; applied to one summand it reorders — so "scaling this
   component cannot change the ranking" is a claim to disprove, never to assume. Keep three
   quantities distinct in the output and its labels: observed sample support (rows you saw),
   the estimated population count (support × weight) and a census count. When no rate is
   stated, a sample can be compared only with itself, and using a within-sample ratio to
   estimate a population ratio requires a justified sampling assumption. Independent sampling
   does not make a finite sample's ratio exactly equal to the population's or make every
   ratio estimator unbiased; state the assumption and uncertainty.
3. **Reuse a helper by its formula, not its name.** Before importing a shared macro or
   metric, read what it computes: its numerator and denominator, its precision, and what it
   does with a missing value. A helper that ROUNDS is a presentation helper — reused inside a
   difference, a rank or a threshold it changes the answer (rule 4). Match the numerator's
   population to the denominator's: a component recorded only for a subset is a ratio over
   that subset (a fee some rows carry, over the rows that carry it), and a sum of per-row
   ratios is a different measure from a ratio of sums — pick one, name it, and do not label
   the other "avg". When two sources carry different components of a cost or a price (one
   all-in, one a base amount), they are not comparable until a decision makes them so: agree
   the basis, or keep them as two labelled columns; a caveat in the description does not make
   them one measure. Do not clip, cap or impute a monetary or physical value by default — a
   long duration or a large amount is a source fact until a rule you wrote and justified says
   otherwise.
4. **Carry precision to the output boundary.** Differences, growth, shares of shares, rank
   scores, thresholds and top-N cutoffs are computed at full precision and rounded once, for
   display, at the end — and a displayed difference is the ROUNDED TRUE DIFFERENCE, never the
   difference of two rounded values. Ranking a rounded score manufactures ties and moves the
   cutoff. Ties are a decision: `ROW_NUMBER()` with a stated, deterministic tie-break (a
   second key, then a stable id), or `RANK()` with the expansion written into the description
   — never a window with no secondary key. Verify the membership of the top N and the rows on
   either side of the cutoff, not only the winner.
5. **Support and missingness are part of the answer.** A floor on a combined or estimated
   volume does not give every contributing group its own support: inspect the observed rows
   per source, per mode, per requested subgroup, and show that support beside the estimate.
   No sampled row for a group is not evidence of no population events — it is unknown
   support: distinguish an absent group from a true zero, in the missing-data policy and in
   the output (a cell that is absent, null or flagged, never a silent zero or a point
   estimate of 100 % / 0 % from one side). A numerical gap at the cutoff, or the floor you
   chose, is not statistical stability; a sampled estimate carries uncertainty, and the reply
   says so instead of calling the ranking stable. A threshold the person approved is theirs:
   keep it, show the leaders' support under it, and recommend a different one with the
   evidence — never raise it silently to make a ranking look defensible. Then, per measure,
   choose the policy for what is absent — report as unknown, exclude with the exclusion
   written down, or impute with the justification written down — and apply the SAME policy on
   both sides of every ratio: a share whose denominator counts periods the numerator treats
   as missing is two answers glued together. Prove coverage — groups and periods present
   against those expected — beside the measure, and exercise it (`pipelines-verification`): a
   run over a period the population does not cover, and one with an alternate parameter set.
6. **Cast what you ship across engines.** `SUM`, `AVG` and arithmetic over a `NUMERIC(p,s)`
   lose their declared scale on the wire (Postgres reports "unknown"); make the type
   explicit: `SUM(x)::NUMERIC(14,2)`, `CAST(AVG(x) AS DECIMAL(14,4))`. Before you divide,
   look at MIN/MAX of every column you divide by (`pipelines-dag`). H2 specifics that bite:
   no `:bind` inside a `GROUP BY` expression; cast DECIMAL ratio operands to DOUBLE; `VALUES`
   rows are named `C1, C2…` — or alias them (`pipelines-engine-quirks`).

Then verify the QUESTION (`pipelines-verification`): derive the check from the question and
the source facts — population, weights, denominators and all — never from the SQL you just
wrote.

## Preserve meaning across aggregation steps

Before reducing a source or combining summaries, identify what the later joins, filters and
groupings need. A small intermediate is useful only if it can still answer the question.

- **Keep the required keys and grain.** Retain downstream join/group/filter keys, including
  dates needed for effective-dated lookups. Aggregating away a required key loses
  information; joining a coarser summary later cannot recover its allocation without an
  explicit rule.
- **Carry the components of an average.** For a later average of `x`, ship `SUM(x)` and
  `COUNT(x)` over the same eligible rows; `COUNT(*)` matches only when every eligible `x` is
  non-null. Combine as `SUM(x_sum) / NULLIF(SUM(x_count), 0)` with the destination engine's
  appropriate numeric casts, preserving precision until display. Averaging subgroup averages
  gives each subgroup equal weight, which is a different measure unless the counts are equal
  or that weighting is intended. For weighted averages and ratios, retain the corresponding
  weighted numerator and denominator. State the empty-input policy.
- **Distinct counts need distinct entities.** Add partial distinct counts only when the
  counted entity sets are provably disjoint. Disjoint date partitions do not prove this: an
  entity can occur on several dates. Otherwise deduplicate retained keys at the final grain,
  or use a supported mergeable approximation only when its error is acceptable and stated.
- **Check join multiplicity and coverage.** A lookup used to enrich a summary must match at
  most one row per summary row under the complete join predicate (including effective dates).
  Multiple matches multiply measures; an inner join can also lose unmatched rows. Verify
  match counts, unmatched keys and relevant totals before/after the join. An intended
  one-to-many allocation needs explicit weights or a different grain; `DISTINCT` or an
  arbitrary lookup row is not a repair.
- **Snapshots are not flows.** An `as_of_date` balance can often sum across distinct entities
  at one instant, but summing it across dates counts the same holdings repeatedly. Choose
  as-of/closing balance or a defined time average, with a policy for missing dates and
  irregular observations. Do not infer additivity from a numeric column's type or name.

Use these invariants in the Verification recipe (`pipelines-verification`) where the pipeline
relies on them, including unequal group sizes, overlapping entities, duplicate lookup keys or
multiple snapshot dates as applicable. Source checks and successful execution alone do not
reconcile the final output; compare it independently at the requested grain.
