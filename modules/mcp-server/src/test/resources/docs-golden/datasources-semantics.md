
# Learned semantics — the workspace's shared memory

Facts are how one session's probes become the next session's head start. Every fact carries
its `trust`: a fact you record with `evidence_sql` is `observed`; without it, only `asserted`
— except a `definition`, `exclusion` or `preference`, which is a choice and lands `asserted`
until a person confirms it.

## Recording

**Record what introspection could not tell you** — a unit, a time zone, a sample rate, a
grain, what a coded value means, a join that holds — with the probe that showed it. Never
record what introspection already returns (types, keys, comments). `semantics_record` takes
the fact text, its kind and scope, and `evidence_sql`; `datasources_get` is the refreshed view
after you record.

- **`refs` names every table the `fact` text names, spelled as the catalog spells it** — a
  catalog table the text names exactly and `refs` omit is ADDED for you (`refs_added` in the
  response); a near-miss is refused with the nearest name (`semantics.ref_mismatch`).
- **A DATASOURCE-scope fact is table-wide and shared**: visible to every workspace the
  datasource is granted to. "in Q4 only A and B carry rows" is a pipeline description's job,
  not a fact's. A `definition`, `exclusion` or `preference` (WORKSPACE scope) stays in your
  workspace. A `definition` that spans datasources carries no `refs` — record it once,
  against the datasource the question is mostly about.
- **A rule the question leaves to you** — what counts as rainy, active, late — is a
  `definition`; the pipelines guide's window rule says when to record one and when to reuse
  one.

## Reading

`datasources_list` carries the datasource-wide facts (its time window, whether it is a
sample); `_get_tables` each table's (grain, caveats); `_get_columns` each column's (units,
time zones, what a coded value means, joins) — each with its `trust` and evidence.
`observed` or `verified` with evidence saves you the probe; `stale` or `needs_review` is a
warning, not a truth — re-verify it. The `definitions` on the listing are rules earlier
pipelines chose, each with `implemented_by`; find the transform that implements a rule before
writing your own (`transforms-contracts` § Implements).

## Correcting and retiring

Two facts of one kind on the same column are both served, flagged `conflict` — a reader
decides, the store never picks. To correct one, record the replacement with `supersedes` (the
old one retires as `superseded`); `semantics_retire` alone is for a fact that is simply wrong.
Keep a fact about the PLATFORM (a tool's behavior, a timeout you met) out of the datasource's
facts — it describes the server, not the data — and keep a diagnosis to what you observed: one
failure is one observation, not a universal explanation.
