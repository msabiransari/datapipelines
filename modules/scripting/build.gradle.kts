// module-structure.md §5.15 — layer 0 beside `typesystem`: the ONLY internal dependency is
// `typesystem`, for the canonical [LogicalType]/[ColumnSchema] the type gate checks against
// and the egress encoding rule the DATE/TIME/TIMESTAMP normalisation reuses.
//
// The engine evaluates UNTRUSTED script bodies as pure functions of their JSON input
// (transform-nodes design §4.1, D-T4). Two facts make that safe rather than hoped-for:
//
// 1. The layering row (§4.2 + the root build's allowed-dependency map) — no route to a
//    database, HTTP client or Spring context is even compilable, and `ScriptingPurityTest`
//    refuses an I/O, network or JDBC import in `src/main` the `CalculatorPurityTest` way.
// 2. The resource bounds are measured, not believed: `JsonataBreachTest` and
//    `JavaScriptBreachTest` run their bomb corpora in the `breachSuite` task below and write
//    `build/reports/jsonata-breach.md` and `javascript-breach.md`, whose tables are what
//    dag-executor.md publishes; `JavaScriptMeasurementsTest` writes the JavaScript engine's
//    costs beside them. (`ScriptEngine.capabilities` reports which limits each engine can
//    actually enforce; the heap is NOT one of them in-process — only the JavaScript isolate's.)
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    implementation(libs.jsonata)
    // Canonical JSON (§5.5): one ObjectMapper, configured here, private to the module.
    // BOM-managed (the conventions apply the Spring Boot BOM and the jackson-bom override).
    implementation("com.fasterxml.jackson.core:jackson-databind")
    // The pool's abandonment log (§4.3): SLF4J API only; the registry lives in `app`.
    implementation(libs.slf4j.api)
    // The JavaScript engine (transform-nodes design §4.4, D-T16; #8 lane 8a): the polyglot API and
    // the isolate's two declared dependencies, explicit so the boot jar carries everything the
    // isolate needs EXCEPT the native binary itself.
    implementation(libs.graalvm.polyglot)
    implementation(libs.graalvm.nativebridge)
    implementation(libs.graalvm.truffle.api)
    // The binary is TEST runtime only: the aggregator pom pulls all four platform isolates (~245 MB,
    // once, in the Gradle cache) so the lockfile is the same on every machine and polyglot picks the
    // host's at runtime. The jar never carries one — the image fetches its platform's (Dockerfile,
    // D-T16) onto the loader path (`LOADER_PATH`); a jar run without it reports JavaScript unavailable.
    testRuntimeOnly(libs.graalvm.js.isolate.community)
}

// ---- the breach suite: its own source set, its own task, its own 512m JVM (#289) ----------
//
// The suite must be able to EXHAUST a JVM to prove the heap is unbounded (transform-nodes
// design §4.5): a bomb that OOMs a 1g test JVM says nothing about a 512m production child,
// and a larger heap would let the big cases pass silently instead of recording their honest
// outcome. Until #289 that 512m was the whole module's `test` heap, so a heap event that
// escaped the suite (CI run 36365034218: the Gradle worker died of it) took the verdict of
// the module's 62 other tests with it. Now the suite runs ALONE:
//
//  - `src/test/breach/kotlin` is its own source set (`breachTest`), so `test` never sees the
//    class and the conventions' zero-test guard (which counts `src/test/kotlin`) stays exact for
//    `test`; `verifyBreachSuiteExecuted` below is the same guard for this task. The directory
//    sits under `src/test/` on purpose: detekt applies its test regime by PATH (`**/test/**` —
//    its defaults and config/detekt/detekt.yml), and a test moved out of `test` keeps it;
//  - `breachSuite` runs it in a fresh 512m JVM and hangs off `check`, so `build` — every
//    gate stage and the CI build step — runs it; it is never an opt-in. `test` keeps the
//    conventions' heap and forks (`dp.test.heap`, `dp.test.forks`, DEVELOPMENT.md §9.5).
//  - Kover instruments the task (the suite's coverage counts, as it did inside `test`) but
//    reports never include the suite's own classes (only `test` is excluded by default).
val breachTest: SourceSet =
    sourceSets.create("breachTest") {
        java.setSrcDirs(emptyList<Any>())
        resources.setSrcDirs(emptyList<Any>())
        compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
        runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
    }
configurations[breachTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[breachTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())
kotlin.sourceSets.named(breachTest.name) { kotlin.setSrcDirs(listOf("src/test/breach/kotlin")) }

kover {
    currentProject {
        sources { excludedSourceSets.add(breachTest.name) }
    }
}

// detekt 1.23's plain `detekt` task reads src/{main,test}/kotlin only; ktlint's plugin adds a
// check per source set on its own (`ktlintBreachTestSourceSetCheck`).
detekt { source.from("src/test/breach/kotlin") }

val breachSuite =
    tasks.register<Test>("breachSuite") {
        description =
            "Runs the JSONata and JavaScript breach suites and the JavaScript measurements alone in their own 512m JVM " +
            "and writes build/reports/jsonata-breach.md, javascript-breach.md and javascript-measurements.md."
        group = "verification"
        testClassesDirs = breachTest.output.classesDirs
        classpath = breachTest.runtimeClasspath
        shouldRunAfter(tasks.named("test"))
        outputs.file(layout.buildDirectory.file("reports/jsonata-breach.md"))
        outputs.file(layout.buildDirectory.file("reports/javascript-breach.md"))
        outputs.file(layout.buildDirectory.file("reports/javascript-measurements.md"))
    }
// A configure action of its own, so it lands AFTER the conventions' `withType<Test>()` defaults
// (`dp.test.heap`, `dp.test.forks`) whatever the order Gradle runs a register block in.
breachSuite.configure {
    maxHeapSize = "512m"
    maxParallelForks = 1
}

// The conventions' zero-test guard (CommonConventionsPlugin.registerZeroTestGuard), for this
// task: fewer result files than `*Test.kt` sources in src/test/breach/kotlin means the suite was
// skipped (NO-SOURCE) or its JVM died without reporting — a false green isolation must not buy.
val breachSources = layout.projectDirectory.dir("src/test/breach/kotlin").asFile
val breachResults = layout.buildDirectory.dir("test-results/breachSuite").get().asFile
val verifyBreachSuiteExecuted =
    tasks.register("verifyBreachSuiteExecuted") {
        group = "verification"
        description = "Fails if breachSuite produced fewer test result files than src/test/breach has test sources."
        dependsOn(breachSuite)
        doLast {
            val sources = breachSources.walkTopDown().count { it.isFile && it.name.endsWith("Test.kt") }
            val results =
                breachResults.listFiles { f -> f.isFile && f.name.startsWith("TEST-") && f.name.endsWith(".xml") }?.size ?: 0
            if (results < sources) {
                throw GradleException(
                    ":modules:scripting:breachSuite produced $results test result file(s) for $sources test source " +
                        "file(s) in $breachResults — the breach suite did not run or its JVM died without reporting.",
                )
            }
        }
    }
breachSuite.configure { finalizedBy(verifyBreachSuiteExecuted) }
tasks.named("check") { dependsOn(breachSuite, verifyBreachSuiteExecuted) }
