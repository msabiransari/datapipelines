# modules/scripting

The transform script engine (GitHub #7, design record
`docs/superpowers/specs/2026-09-09-transform-nodes-design.md` §4/§5, lane 7a): a pure,
layer-0 library that compiles and evaluates untrusted script bodies as pure functions of
their JSON input. No route, tool, page or config ships from this module — 7b/7c wire the
callers.

## Public API (the frozen contract)

- `ScriptEngine` / `JsonataEngine` — compile once, evaluate many; JSON-shaped values
  (`Map`/`List`/`String`/`Number`/`Boolean`/null) in and out. `ScriptLanguage` names the
  engine (`JSONATA` today, `JAVASCRIPT` reserved for round two — deliberately NOT the
  template model's `TemplateType`, which sits a layer above; 7b maps the two).
- `EvaluationLimits` — the declared bounds for one evaluation (`wallClock`, `maxDepth`,
  reserved `maxHeapBytes`/`maxStatements`, and `now`: the pinned instant `$now()`/`$millis()`
  return; production callers always pin it to the execution's `current_timestamp`, which is
  what makes a transform reproducible — the clock is an input, not an ambient read).
- `EngineCapabilities` — which limits an engine can actually enforce, measured by the breach
  suite, never intended: JSONata bounds wall clock **between steps** and depth **at every
  step**; it cannot bound heap or statements, and it is not interruptible.
- `CompiledScript` — opaque, engine-produced, safe for concurrent evaluation.
- `ScriptEvaluationPool` — every production evaluate call runs here: bounded admission,
  one daemon thread (`script-eval-N`) per evaluation, abandonment past budget + grace
  (`Future.cancel(true)` reaches nothing in the engine — documented), an `abandoned`
  `LongAdder` to scrape into a meter, ERROR log per abandonment. `ScriptClock` is its
  injected time source; `ScriptClock.SYSTEM` is the module's single deliberate ambient read
  (the purity test counts it).
- `TypeGate` + `GateRefusal`/`GateResult` — the contract's declared columns against every
  returned value: exact key set, wire-form fit, R1's numerics (DECIMAL rounds half-even to
  its declared scale; BIGDECIMAL is exact and never rounded), DATE/TIME/TIMESTAMP
  normalised through the type system's canonical encoder. Nullability here is the
  CONTRACT's (absent = not nullable) — deliberately not `ColumnSchema.nullable`'s
  tri-state, whose "absent = unknown, never false" rule governs INGRESS schemas; the KDoc
  says why the two coexist.
- `CanonicalJson` — the §5.5 canonical form (keys sorted recursively, integers as
  integers, shortest round-trip doubles, plain-decimal `BigDecimal`s); `equal` ignores key
  order and decimal spelling, per the record's §10.3.
- Typed refusals extending `DatapipelinesException`: `ScriptSyntaxException` (1-based
  line/column), `ScriptEvaluationException` (message bounded to 2000 chars),
  `ScriptTimeoutException`, `ScriptResourceLimitException` (DEPTH | HEAP | STATEMENTS),
  `ScriptPoolExhaustedException` — each carrying its `pipeline-contract` §13.18 code from
  the design record's §7 mapping.

## Purity (a build rule and a source rule, the calculators pattern)

The §4.2 row admits exactly one internal dependency (`typesystem`); the root build's
`allowedInternalDependencies` map is the same closed set; `ScriptingPurityTest` refuses
I/O, file, network, JDBC and Spring imports in `src/main` and counts the module's ambient
clock reads (exactly one: `ScriptClock.SYSTEM`). No Java function is registered on any
engine frame, so a body has no host entry point; the conformance suite asserts the builtin
catalogue contains nothing from a written deny-list.

## Dependencies

| Artifact | Version | Licence | Why |
|---|---|---|---|
| `com.dashjoin:jsonata` | 0.9.10 (pinned; Maven Central `<release>`, verified 2026-09-23) | Apache-2.0 | the round-one transform engine. Pure Java, zero transitive dependencies. **Bus-factor note for the next dependency review:** small upstream project — 99 stars, 17 open issues, last push 2026-07-08 at dispatch. Not a blocker: the seam (`ScriptEngine`) exists precisely so the engine stays replaceable, and the breach suite re-measures whatever replaces it. |

Jackson (`jackson-databind`, BOM-managed) for `CanonicalJson`; SLF4J API (BOM-managed) for
the pool's abandonment log.

## Commands

```bash
# Dependency change? Regenerate locks + verification metadata (DEVELOPMENT.md §6.2/§6.3)
# — the diff must be exactly this module's lockfile + the jsonata verification entries:
./gradlew --write-verification-metadata sha256 --write-locks resolveAndLockAll

# The breach suite: measures every bomb under one budget and writes the report whose
# table dag-executor.md §5.3 publishes. The test JVM runs at 512m on purpose (the build
# file) so the heap cases are honest:
./gradlew :modules:scripting:test && cat modules/scripting/build/reports/jsonata-breach.md
```

## Tests

`ScriptEngineConformanceTest` (parameterised over every engine — the JS round adds an
instance, not cases), `JsonataBreachTest` (the measured corpus; a prediction mismatch
fails the build), `JsonataEngineParallelDeterminismTest` (32 threads × 1000 evaluations
against pre-computed single-threaded results), `JsonataEngineBoundsTest`,
`JsonataEngineEvaluateTest`, `JsonataEngineCompileTest`, `JsonataEngineNoHostAccessTest`,
`ScriptEvaluationPoolTest`, `TypeGateTest` (the §5.3 table, 42 cases), `CanonicalJsonTest`,
`ScriptingPurityTest`.
