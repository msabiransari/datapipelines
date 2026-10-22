# modules/scripting

The script engines (GitHub #7 and #8, design record
`docs/superpowers/specs/2026-09-09-transform-nodes-design.md` §4/§5, lanes 7a and 8a): a pure,
layer-0 library that compiles and evaluates untrusted script bodies as pure functions of
their JSON input — JSONata in-process, JavaScript in a GraalJS polyglot isolate. No route,
tool, page or config ships from this module — 7b/7c wire the JSONata callers, 8b the
JavaScript ones (until 8b, a javascript template is refused with `script.js.unavailable`).

## Public API (the frozen contract)

- `ScriptEngine` / `JsonataEngine` / `JavaScriptEngine` — compile once, evaluate many;
  JSON-shaped values (`Map`/`List`/`String`/`Number`/`Boolean`/null) in and out.
  `ScriptLanguage` names the engine (`JSONATA`, `JAVASCRIPT` — deliberately NOT the template
  model's `TemplateType`, which sits a layer above; 7b maps the two).
- `EvaluationLimits` — the declared bounds for one evaluation (`wallClock`, `maxDepth`,
  `maxHeapBytes`/`maxStatements` (JavaScript only), `maxInputBytes`/`maxResultBytes`
  (JavaScript only), `console` (a `ConsoleSink`; not a bound), and `now`: the pinned instant
  `$now()`/`$millis()` and JavaScript's `Date.now()`/`new Date()` return; production callers
  always pin it to the execution's `current_timestamp`, which is what makes a script
  reproducible — the clock is an input, not an ambient read).
- `EngineCapabilities` — which limits an engine can actually enforce, measured by the breach
  suite, never intended: JSONata bounds wall clock **between steps** and depth **at every
  step**; it cannot bound heap or statements, and it is not interruptible. JavaScript bounds
  all five (heap softly, with the isolate's hard cap behind it) and is interruptible: a
  cancellation closes its Context.
- `JavaScriptRuntime` (+ `JavaScriptRuntimeOptions`, `Availability`, `EngineScope`) — the ONE
  factory of every polyglot Engine and Context (`SandboxPolicy.UNTRUSTED`,
  `spawnIsolate(true)`, `HostAccess.UNTRUSTED`, no IO/host/polyglot/native/process/env access,
  `polyglot.*` system properties ignored). One Engine per JVM (`EngineScope.PerJvm`), a fresh
  Context — its own realm — per compile and per evaluation; `availability()` says whether the
  isolate starts in this process; `close()` closes the Engine. The options are the isolate's
  sizing and the ceilings `EvaluationLimits` may lower but never raise; defaults: isolate
  512 MiB, heap 256 MiB, statements 100 000 000, AST depth 1 000, stack frames 1 000, console
  1 MiB, input and result 64 MiB each, ECMAScript 2025. `EngineScope.PerEvaluation` is
  documented and NOT built: a closed isolate whose guest code was JIT-compiled is never
  released (the KDoc carries the measurement).
- `ConsoleSink` / `ConsoleLine` / `ConsoleLevel` — where a JavaScript body's
  `console.log/info/debug/warn/error` lines go: at most 200 lines of 2 000 characters, in
  order, after the evaluation returns or fails.
- `JavaScriptResourcesMain` — the image build's step that pre-installs the isolate's native
  resources into a read-only cache directory (the Dockerfile runs it before `USER`).
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
  `ScriptTimeoutException`, `ScriptResourceLimitException` (DEPTH | HEAP | STATEMENTS |
  OUTPUT | INPUT_BYTES | RESULT_BYTES),
  `ScriptPoolExhaustedException` — each carrying its `pipeline-contract` §13.18 code from
  the design record's §7 mapping.

## Purity (a build rule and a source rule, the calculators pattern)

The §4.2 row admits exactly one internal dependency (`typesystem`); the root build's
`allowedInternalDependencies` map is the same closed set; `ScriptingPurityTest` refuses
I/O, file, network, JDBC and Spring imports in `src/main` and counts the module's ambient
clock reads (exactly one: `ScriptClock.SYSTEM`). No Java function is registered on any
engine frame, so a body has no host entry point; the conformance suite asserts the builtin
catalogue contains nothing from a written deny-list. The JavaScript engine opens no file:
`JavaScriptSandboxGuardTest` finds every `Engine`/`Context` builder call in `JavaScriptRuntime`
alone and refuses any permissive policy or access constant anywhere in `src/main`.

## Dependencies

| Artifact | Version | Licence | Why |
|---|---|---|---|
| `com.dashjoin:jsonata` | 0.9.10 (pinned; Maven Central `<release>`, verified 2026-09-23) | Apache-2.0 | the round-one script engine. Pure Java, zero transitive dependencies. **Bus-factor note for the next dependency review:** small upstream project — 99 stars, 17 open issues, last push 2026-07-08 at dispatch. Not a blocker: the seam (`ScriptEngine`) exists precisely so the engine stays replaceable, and the breach suite re-measures whatever replaces it. |

| `org.graalvm.polyglot:polyglot`, `org.graalvm.sdk:nativebridge`, `org.graalvm.truffle:truffle-api` | 25.4.4.1.1 (pinned; Maven Central `<release>`, verified 2026-10-21) | UPL 1.0 | the JavaScript engine's API and the isolate's two declared bridge dependencies. They bring `org.graalvm.sdk:collections`, `nativeimage`, `word` and `jniutils` (UPL 1.0), all in the boot jar. |
| `org.graalvm.polyglot:js-isolate-community` | 25.4.4.1.1, **test runtime only** | MIT + UPL 1.0 | the GraalJS isolate. The aggregator pulls the four platform binaries (~245 MB, once, into the Gradle cache) so the lockfile is the same on every machine. **No binary ever enters the jar**: the image fetches its platform's `js-isolate-linux-<arch>-community` jar into `/opt/graaljs`, SHA-256-pinned (`GRAALJS_*` ARGs, guarded by `DockerfileGraalJsPinTest`), and puts it on the loader path (`LOADER_PATH`). A jar run without it reports JavaScript unavailable. |

Jackson (`jackson-databind`, BOM-managed) for `CanonicalJson` and the JavaScript engine's
input/result encoding; SLF4J API (BOM-managed) for the pool's abandonment log.

## Commands

```bash
# Dependency change? Regenerate locks + verification metadata (DEVELOPMENT.md §6.2/§6.3)
# — the diff must be exactly this module's lockfile + the jsonata verification entries:
./gradlew --write-verification-metadata sha256 --write-locks resolveAndLockAll

# The breach suite: measures every bomb under one budget and writes the report whose
# table dag-executor.md §5.3 publishes. It runs ALONE in its own task and its own 512m
# test JVM (#289 — the build file; `check`, so every `build` runs it too) so the heap
# cases are honest and a heap event can never take the module's other tests with it:
# The JavaScript corpus and the engine's costs (first Engine, p50/p95, parse, rows per
# batch, RSS over 1 000 evaluations, globals) land beside it:
./gradlew :modules:scripting:breachSuite && cat modules/scripting/build/reports/jsonata-breach.md \
  modules/scripting/build/reports/javascript-breach.md modules/scripting/build/reports/javascript-measurements.md

# What the image build runs (Dockerfile, as root, before USER; LOADER_PATH=/app/lib,/opt/graaljs
# puts the fetched isolate jar on the class path): pre-install the isolate's resources into the
# cache the read-only container reads through -Dpolyglot.engine.userResourceCache:
java -Dloader.main=co.datapipelines.scripting.JavaScriptResourcesMain -cp /app/datapipelines.jar \
  org.springframework.boot.loader.launch.PropertiesLauncher /opt/graaljs/resources
```

## Tests

`ScriptEngineConformanceTest` (parameterised over every engine — the JS round added an
instance, not cases), `JsonataBreachTest` and `JavaScriptBreachTest` (the measured corpora,
in `src/test/breach` and the `breachSuite` task, on the shared `BreachHarness`; a prediction
mismatch fails the build), `JavaScriptMeasurementsTest` (the costs report; children via
`JavaScriptChildMain`), `JsonataEngineParallelDeterminismTest` (32 threads × 1000 evaluations
against pre-computed single-threaded results), `JsonataEngineBoundsTest`,
`JsonataEngineEvaluateTest`, `JsonataEngineCompileTest`, `JsonataEngineNoHostAccessTest`,
the `JavaScript*Test` classes (engine compile/evaluate/bounds, runtime, sandbox guard, console,
determinism, number rule, result shape, errors), `DockerfileGraalJsPinTest`,
`ScriptEvaluationPoolTest`, `ScriptEvaluationPoolCancellationTest`, `TypeGateTest` (the §5.3
table), `CanonicalJsonTest`, `ScriptingPurityTest`.
