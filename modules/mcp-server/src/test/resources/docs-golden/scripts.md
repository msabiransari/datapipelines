
# Scripts — a pure function with a contract and tests

**SQL when SQL can; a script when the shape is nested, the logic is per-row, or SQL cannot
say it.** A **script template** is a pure function over staged data with a declared
contract and a test suite — write the empty and wrong-data cases before the right-data one.

## Concepts

A template's `type` is `jsonata` or `javascript` (the script types) beside `sql`/`html`. A
script sets `engine: "none"` (evaluated, never rendered) and takes **no** `dialect`, no
`imports`, no `is_library`; a Freemarker construct in its body is refused with
`template.validation.freemarker_forbidden`. JavaScript saves when the isolate is installed;
otherwise save refuses with `script.js.unavailable`.

A JSONata body is one expression. A JavaScript body is the **statements of `main(input)`**:
`input` is in scope, `return` the result, and define helpers as nested functions; never write
`function main` or its outer braces yourself — the engine supplies that immutable frame.
Both are pure: no modules, `require` or `export`; `Date.now()`/`$now()` read the pinned run time;
`Math.random()`, `$random()` and `$shuffle()` are refused.

Numbers cross by logical type: BIGINTEGER and BIGDECIMAL use their normal decimal-string
wire form; other integers beyond ±2^53 and DECIMALs whose double would lose digits arrive as
decimal strings and are written back as strings. Use
`BigInt(input.rows[0].id)` for large integer arithmetic and `String(x)` to return it;
`Number(x)` can lose digits, so for exact money use integer minor units.

Return a synchronous JSON value: no `NaN`/`Infinity`, functions, promises, `Map`/`Set`/`Date`
(call `toISOString()` first); an `undefined` object property is dropped, but an `undefined`
array element or top-level return is refused. `console.*` lines are returned by evaluate,
logged by pipeline runs and shown inside each case result, capped at 200 lines × 2000 chars.
The mandatory empty-input case runs through the real engine on the pool at save, so a body
that returns nothing does not save.

Every script version carries three bound blocks — `contract`, `invariants`, `tests` —
inside its `body_hash` (`scripts-contracts` holds their shape). A workspace rule someone
recorded as a learned fact and the script that computes it are one decision: cite the rule
with `implements` (`scripts-contracts` § Implements).

A **SCRIPT node** pins a script template, conforms to the pinned contract at save, and
is tempdb-only — no `source` (`scripts-evaluation` holds the node and its run behaviour).

## The authoring loop

1. `templates_create` with the type, the body, and the three blocks — **save runs the suite**:
   every case on the bounded evaluation pool, output gated, invariants checked. The first
   failure is `template.test_failed` naming the case (the diff is bounded; a wrong refusal
   code is a failure too).
2. Iterate with `templates_evaluate` per case — same engine, same pool, same input check —
   and fix what it surfaces. `templates_get` returns the blocks back.
3. A human releases from the UI (the core's draft rule); **release re-runs the suite** on the
   version being released.

## Common mistakes

Declaring a contract the inputs do not match (the key set must equal the contract's input
names); writing the right-data case and skipping the empty one (a suite with no empty case is
refused); citing another workspace's fact in `implements`; re-writing a rule a recorded
definition already implements instead of pinning the existing script.

## References — open when

- **`scripts-contracts`** — declaring the three blocks, the types, `implements`, and the
  refusal codes a save can answer.
- **`scripts-evaluation`** — `templates_evaluate`, the run-time pool and bounds, the
  SCRIPT node, and the `pipeline.script.*` family.
- **`scripts-tools`** — the area's tools, generated from their shipped descriptions.
- **`pipelines-numbers`** — the numbers the script ships still obey the measurement rules.
