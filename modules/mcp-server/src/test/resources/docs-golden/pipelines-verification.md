
# Finish like a professional

- **Stop at the draft.** Your update is not live until a human releases it (the core's
  draft-and-release rule) — never call a release endpoint, never say "released" or "live"
  about your own work. Execute the draft to prove it, then hand back.
- **Describe what a reader needs, in sections.** The description's labels and their order are
  the pipelines guide's step 4; the **Verification** section is the numbered recipe a human
  re-runs before releasing: steps separated by blank lines, each carrying its purpose, the
  datasource and dialect it runs on, the parameter values used, the SQL itself on its own
  lines (rerunnable as-is), the observed result, the comparison it supports, and its scope or
  limitation — plus the check-id when the query also lives in `checks[]`. One recipe may span
  several queries and engines; `checks[]` holds the checks, the recipe holds the COMPLETE
  reproduction path for every verification claim you report — reconciliation queries beyond
  `checks[]` included, exploratory dead ends excluded. It carries queries and observed
  numbers — evidence, never the word "validated": a reader re-runs the recipe and reaches
  their own verdict. The SQL and the observed values live with the pipeline, not in a
  transcript.
- **Read the whole result, not the part your client showed you.** A client can truncate a
  large tool result: if the execute reply reports more rows than you can see, or your client
  shows a truncation notice, page the rows with `executions_get_result` and reason only over
  the rows the server actually returned.
- **Verify the question, not the SQL you wrote.** Independent means derived again from the
  question and the source facts — the population, the weights, the denominators, the window —
  never by re-running the pipeline's own formula or reading its staged tables. Re-running
  your formula checks arithmetic; only a second derivation can challenge the interpretation,
  and the interpretation is where the answer goes wrong (`pipelines-numbers`). Then cover
  what the question asked for: every requested dimension (each period, each mode, each
  subgroup — a combined total that agrees says nothing about how it splits), and the
  vulnerable rows — the rank cutoff and the rows on both sides of it, the sparsest groups,
  the groups that are absent, the window's boundaries. Where the result is small, reconcile
  all of it; where it is not, one winner's total is the weakest possible sample — a winner
  rarely moves, the cutoff does. Compare like with like at the declared precision, and report
  exactly what was compared: which rows, which quantity, what matched, what did not.
- **Report what you did not verify.** "Row counts match the previous version" is not "the
  numbers are right". Name the independent check you ran — or that you ran none. A proxy is
  reported as what it proves: a non-empty count for one period and one source shows that
  period and that source have rows — not that the combined, multi-period eligibility pool is
  right; a source-only check never validates an output ranking.
- **Verify three ways — two are checks, and the server is their judge.** A `checks[]` entry
  is ONE datasource, ONE read-only statement, ONE expectation (`value`, `range` or `rows` — a
  `rows` expectation counts the statement's rows, so an assertion need not return a single
  numeric cell), read from the RAW source — never from the pipeline's own output tables —
  with `expected` supplied by you and `observed` produced only by the server's own run
  (`pipelines_run_checks`; there is no tool that records an observed value from a caller).
  Two to five per pipeline is the right band. A human releasing from the UI sees every
  check's expected and observed and cannot release past a failing one without giving a
  reason. The first two strategies below are `checks[]` entries; the third is not and cannot
  be — pretending otherwise is the miss this section exists to prevent.

  - **Fixed-baseline drift check.** Literals for one baseline window; `expected` is the value
    your independent query measured there. It says "the baseline still holds" and nothing
    else — name the baseline in the check's `name` ("… (2024 baseline)"), because the static
    expectation means nothing beside any other window. When the baseline stops being the
    interesting window, the check is re-measured and re-based, not silently failed.
  - **Parameterized invariant check.** The SQL binds the pipeline's parameters AND the
    expectation holds for EVERY input the pipeline accepts: a violation count is zero, a
    reconciliation difference is empty. The tell that you have this shape: the expectation
    needs no window in its name. **Never bind a changing parameter while keeping an
    unrelated fixed expected total** — that is a baseline check wearing a bind: green on the
    baseline, a lie everywhere else.
  - **Independent output reconciliation — the recipe, not a check.** A source check cannot
    prove the OUTPUT right — it never sees it, and no `checks[]` entry reads the pipeline's
    result. Recompute the output groups the bullet above names independently from the source
    — joins, filters, weights and denominator included — and compare them against the
    pipeline's actual result rows for the same groups. That comparison is usually more than
    one query, sometimes more than one engine, plus an explicit side-by-side: it lives in
    the numbered Verification recipe, never in `checks[]`, with the SQL, the parameter
    values, the observed numbers, the comparison and its limits. A source assertion that
    DOES fit the one-datasource, one-statement shape (a raw total the group's number must
    equal) is a check of one of the two kinds above — link it in the recipe by check-id. A
    shared literal list copied into several templates is verified the same way: the run
    compares the copies (`pipelines-dag`), and the recipe shows the comparison — a check
    that counts one copy proves nothing about the others.

  Two honest limits, by design. Expectations are STATIC — the server compares the observed
  value against the value, range or row count you declared; there is no dynamic expectation.
  And the release gate binds the declared DEFAULTS (it supplies no parameters): a
  parameterized check proves the default window, not every combination a caller can pass; a
  fixed-literal baseline check proves its own named baseline, whatever the defaults are.
  What the defaults cannot prove stays in the Verification recipe as measured observations;
  the server remains the only source of a check run's observed values. The field shapes are
  `pipelines-schema`'s.
- **Exercise the door, then say what it accepts.** Before you hand back, run the draft with a
  valid input other than the defaults, and with an input the data does not cover or the
  pipeline should refuse — an empty answer with the policy stated is the pipeline working; a
  quietly plausible number is not. A period the question names is exactly that period in
  every node: a two-period comparison reads each period as asked, never one period as
  "everything up to" the other (which folds intervening periods into one side) and never the
  same period twice; when two sources read the window differently, make the formulas agree or
  document and restrict the combinations the door supports. Write the supported inputs into
  the description.
- **Leave what you learned where the next session looks.** A rule the person approved — a
  threshold, an eligibility filter, a price basis — is a `definition` recorded through
  `semantics_record` (`datasources-semantics`), with the evidence probe where one shows it; a
  note in your own client's memory or in the reply is invisible to every other session and
  every other client.
- **The person's constraints are part of the task.** If the person said no delegation, no
  sub-agents, read-only, or MCP-only, that holds for the whole task, including anything you
  would have handed to a helper. The server sees only what you call, never how you work, so
  nothing in this manual or on the server enforces it — you do.
- **Report the index analysis** (the `pipelines-dag` rule): for every source node, one line —
  supported by `<index>` / *not supported — suggest `CREATE INDEX … ON table (cols)`* / *lake:
  filters on the partition column, `partitions_scanned`/`partitions_total` from `sql_probe`'s
  plan*. The operator reads the handback; that line is how a slow pipeline becomes a fast one
  without a rewrite.
- **A performance rewrite proves two things, both measured.** When a node is the bottleneck
  and you reshape it (a materialisation like `pipelines-engine-quirks` § 6.5's, a pushed-down
  filter, a rollup table), the rewrite is done when (1) a meaningful reconciliation says the
  new shape answers the same question — the same rows, or the same aggregates over the same
  population — and (2) the timings say it is faster: `node_stats` durations before and after,
  or `sql_probe`'s `wall_ms` on both shapes. "Should be faster" is not a measurement, and a
  faster query over a different population is a different answer. The materialisation rule is
  about the shape it names — an aggregate or ranking CTE JOINED to another table in H2 — not
  a licence to stage every CTE, and H2's inlining is not every engine's behavior.
- **Report from the output you read, not the story you remember.** Every quantitative claim
  in your reply — a direction ("share rose"), a unit, the period, a sample size — is
  reconciled against the result rows before you write it: read the sign off the numbers, the
  period off the window you bound, the base off the denominator. An association is not a
  mechanism: "the two move together" is what the rows say; "one caused the other" names a
  cause the rows do not show. When the reply and the output disagree, the output is right —
  fix the reply.
