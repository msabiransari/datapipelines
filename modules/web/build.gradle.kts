import java.util.concurrent.TimeUnit

// module-structure.md §5.9 — the aggregation layer. Lists every module it touches
// EXPLICITLY (§4.2): it could reach most of them transitively through mcp-server,
// and declaring them is what makes the table checkable.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    implementation(project(":modules:calculators"))
    implementation(project(":modules:pipeline-contract"))
    implementation(project(":modules:templates"))
    // 7b: the script-evaluation pool and the §8.1 test-runner bean (transform-nodes §4.3/§8.1).
    implementation(project(":modules:scripting"))
    implementation(project(":modules:datasources"))
    implementation(project(":modules:staging"))
    implementation(project(":modules:dag"))
    implementation(project(":modules:auth"))
    implementation(project(":modules:application"))
    implementation(project(":modules:mcp-server"))
    // #194 lane D: the parameter-set REST surface and the engine's wiring (record §2.4 — the
    // edge was allowed ahead; now that `web` compiles against the engine it is declared).
    implementation(project(":modules:parameters"))
    // #9: the schedules REST surface, the pipeline executor adapter and the scheduler's ports'
    // implementations (web/config is the composition root — scheduler design revision §6.2, A3).
    implementation(project(":modules:scheduler"))
    // #266: the execution-event record's and the replay log's batching writers, their recorder and
    // their metrics (module-structure §5.19).
    implementation(project(":modules:persistence"))
    // #10 L1b: the visualization/dashboard REST routes and the module's wiring (web/config/VisualizationConfiguration)
    // — the edge the root map allowed ahead, declared now that `web` compiles against it.
    implementation(project(":modules:visualization"))

    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.actuator)
    implementation(libs.spring.boot.starter.thymeleaf)
    implementation(libs.spring.boot.starter.validation)
    // spring-jdbc: EngineConfiguration/DomainConfiguration declare the dag/datasources/
    // pipeline-contract repositories as @Bean methods (015), so NamedParameterJdbcTemplate
    // is a COMPILE-time type here, not merely a runtime one.
    implementation(libs.spring.boot.starter.jdbc)
    // Redis: post-completion SSE event log (1h) + per-user rate-limit counters.
    // The durable 7-day record is dag's ExecutionEventRepository (D9).
    implementation(libs.spring.boot.starter.data.redis)
    implementation(libs.micrometer.core)
    implementation(libs.jackson.module.kotlin)
    // 033: renders the packaged spec set (classpath:docs/*.md) at /docs. Extension
    // selection justified at the version pin in libs.versions.toml.
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)

    // DEVIATION from §5.9's external-dep list, following the precedent `auth` and
    // `mcp-server` set, and reported to the orchestrator. All three are existing catalog
    // aliases — no new artifact enters the build:
    //  - oauth2-client: web reads the principal `auth`'s filters put in
    //    `SecurityContextHolder` and registers its own servlet filters ahead of the
    //    Spring Security chain, so `spring-security-core`/`-web` are COMPILE-time types
    //    here, not merely runtime ones. `auth` declares this starter `implementation`,
    //    which puts it on web's runtime classpath but not its compile classpath.
    //  - coroutines: `PipelineExecutor.execute` and `EventEmitter.emit` are `suspend`;
    //    the SSE surface drives them from a servlet thread.
    //  - slf4j: request/SSE diagnostics (observability §3.4 — every module logs via the API).
    implementation(libs.spring.boot.starter.oauth2.client)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.slf4j.api)

    // --- Tests -------------------------------------------------------------------
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.kotlinx.coroutines.test)
    // Module-local integration tests: a Postgres container running app's real V1
    // migration (executions + execution_events) and a Redis container for the SSE event
    // log, the result cursor and the rate-limit counters. Same aliases `dag` and `auth`
    // already use; `testcontainers` (core) is the GenericContainer Redis rides on.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.postgresql)
    // Real forward-only cursors for the result-store round trip (the fixture dag's own
    // RedisResultStoreIntegrationTest uses); the alias already exists in the catalog.
    testImplementation(libs.h2)
    // The integration test drives dag's repository classes against a Postgres container
    // directly (spring-jdbc is a main dependency — see above).
    // Konsist architecture guard for the web layer (module-structure.md §7.8).
    testImplementation(libs.konsist)
    // jsoup: the script-block XSS render test (185) parses the rendered editor page to
    // decide whether the JSON blobs stayed inside their script elements — a parse-level
    // question a regex over the raw output cannot answer (a script element's content is
    // raw text, so string content and injected markup are indistinguishable without a
    // parser). Test scope only; version pinned in libs.versions.toml.
    testImplementation(libs.jsoup)
}

// 033 — the spec set ships IN THE JAR: docs served in-product always describe the version
// they run on (the round's structural argument). 096 §G (review finding F4) INVERTED the
// selection: it was `include("*.md")` minus five excludes, which is a DENYLIST — a new
// `docs/*.md` was published to the world the moment it landed, and the only thing standing
// between a contributor note and the public site was somebody remembering to add an exclude.
// That is the exact inversion `rules/04-configuration.md`'s PUBLIC_ENDPOINTS principle
// forbids (and auth.md §8.3 now applies to routes): the default is private, the exceptions
// are enumerated, and each one is a decision somebody took. The list below IS that decision
// record — a new doc is private until its filename is added here, which is a visible diff on
// a line whose whole job is to be read.
//
// The exclusion is also what makes link rewriting mandatory (033 §A): packaged docs link to
// non-packaged targets, and those links must not ship dead. DocsRenderer rewrites them to
// canonical GitHub URLs; DocsRenderingTest asserts every relative link resolves one way or
// the other. DocsCatalog still fails fast at startup on a packaged doc with no index group,
// so adding a filename here without grouping it is a boot failure, not a silent publish.
// scripts/docs-audit.sh keeps guarding the source set — this copies, never edits.
//
// NOT packaged, and why: semantic-layer-research.md, SPEC-REVIEW-2026-08.md,
// ARCH-AUDIT-2026-08.md and TEST-GAP-2026-09.md are contributor material (research notes,
// review records, findings pending review) — an operator running this deployment is not the
// audience, and shipping "pending review" findings in the product jar would read as product
// documentation. template-hierarchy-design.md proposes schema and contract that does not
// exist yet, so shipping it would describe a version nobody runs.
tasks.named<ProcessResources>("processResources") {
    from(rootProject.layout.projectDirectory.dir("docs")) {
        into("docs")
        include(
            "auth.md",
            "calculators.md",
            "configuration.md",
            "dag-executor.md",
            // #10 L1a — the two dashboard documents and their lifecycle (the implementation spec §15).
            "dashboards.md",
            "datasources.md",
            "deployment.md",
            "enums.md",
            "environments.md",
            "key-providers.md",
            "mcp-server.md",
            "metadata-db.md",
            "module-structure.md",
            "observability.md",
            "pipeline-contract.md",
            "pipeline-editor.md",
            "README.md",
            "rest-api.md",
            "ROADMAP.md",
            // #699 — the scheduler's operating contract, under the Operations manual.
            "scheduler.md",
            "staging.md",
            "templates.md",
            "type-system.md",
            "ui-screens.md",
            "versioning.md",
        )
    }
}

// 027b A/B/C+D — automated coverage for the pipeline-editor JS (the SSE frame
// parser, the execute-path wire coercion, the result-panel paging). No JS test
// harness existed in the repo and 027's six execute-path fixes shipped with
// zero automated coverage; these run on Node's BUILT-IN test runner
// (`node --test`), so they add NO package, NO lockfile, NO runner dependency —
// the deliberate dependency decision, named in the 027b handback. Node >= 18
// (the runner's floor); the editor JS is browser IIFE code published on
// `window`, which the tests shim and require directly.
//
// Node-less machines SKIP with a lifecycle banner rather than fail the build:
// the Gradle toolchain pins JDK, not node, and every other module builds
// node-free. Where node exists (dev boxes, this guard's audience), a red
// parser/paging test fails `check` like any other test.
val editorJsTests =
    fileTree("src/test/js") {
        include("*.test.mjs")
    }

// #508 — one node --test process per file, each bounded and captured. The old single run over
// all files failed in two ways. With --test-force-exit the parent's forced exit dropped tests
// whose stdout was still queued: shell.test.mjs reported 20-45 of its 54 defined tests across
// the issue's five runs (re-measured 2026-10-11: 45/32/54/54/32), exit 0 every time — silently
// green. And when the run wedged instead, the INHERITed stdout went to the Gradle daemon's
// /dev/null, so no log carried WHICH file stalled (68 of 76 files "Interrupted", twice to the
// 10-minute bound). Now: per file, `node --test --test-reporter=tap <file>` — no force-exit;
// its reason died with the dashboard-proxy teardown fix 15bdbb90, re-verified 2026-10-11
// (exits on its own, 3/3). stdout+stderr captured to build/editor-js-test/<file>.tap, one
// lifecycle line per file into the build log, a per-file kill bound, and a static floor of
// `test(` registrations per file, so ANY short count — a dropped pipe, a crashed child, a
// re-added flag — is red naming the file, never a green that counted less.
tasks.register("editorJsTest") {
    group = "verification"
    description = "Runs the pipeline-editor JS unit tests (sse parser, coercion, paging) on node --test, one process per file."
    doLast {
        val node =
            System.getenv("PATH")
                ?.split(File.pathSeparator)
                ?.asSequence()
                ?.map { File(it, "node") }
                ?.firstOrNull { it.canExecute() }
        if (node == null) {
            logger.lifecycle("editorJsTest SKIPPED — node not on PATH (install Node >= 18 to run the editor JS guard)")
            return@doLast
        }
        val testFiles = editorJsTests.files.sortedBy { it.name }
        if (testFiles.isEmpty()) throw GradleException("editorJsTest found no *.test.mjs under src/test/js — the guard ran vacuously")
        val version = nodeVersion(node)
        logger.lifecycle("editorJsTest: {} on {}", node.absolutePath, version)

        // Wipe first: accumulating would let a PREVIOUS run's TAP files read as this run's
        // trace (the same rule websiteExportAssets applies to its export directory).
        val tapDir = layout.buildDirectory.dir("editor-js-test").get().asFile
        tapDir.deleteRecursively()
        tapDir.mkdirs()

        // Serial, deliberately: all 77 files measured 23.5 s end to end serially (node
        // v24.21.0, 2026-10-11), far under a pool's payoff, and serial never forks the
        // ~95-at-once child storm the old all-files run forked — the #508 hang's leading
        // (unproven) hypothesis. The whole-task bound below stays as the outer fence.
        val deadlineMs = System.currentTimeMillis() + TimeUnit.MINUTES.toMillis(EDITOR_JS_TEST_BOUND_MINUTES)
        val fileBoundMs = TimeUnit.SECONDS.toMillis(EDITOR_JS_FILE_BOUND_SECONDS)
        val results = mutableListOf<EditorJsFileResult>()
        for (file in testFiles) {
            if (System.currentTimeMillis() >= deadlineMs) {
                results.add(EditorJsFileResult(file.name, floor = 0, notRun = true))
                continue
            }
            // The static floor: every `test(` call site in the file must be REPORTED by the
            // run. Files registering inside a loop report MORE than the floor, so it is a
            // lower bound, never an equality — but any count under it means defined tests
            // were never counted, whatever dropped them.
            val floor = Regex("^\\s*test\\(", RegexOption.MULTILINE).findAll(file.readText()).count()
            val tapFile = File(tapDir, file.name + ".tap")
            val proc =
                ProcessBuilder(node.absolutePath, "--test", "--test-reporter=tap", file.absolutePath)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.to(tapFile))
                    .start()
            // The per-file fence, independent of node: --test-timeout bounds one test, not a
            // file stuck at load, so the wait is ours. Kill the TREE (a file may have forked,
            // e.g. dashboard-proxy's proxy child) and keep going — a hung file is named at
            // the end, never waited on past the bound, never the other files' loss.
            val waitMs = minOf(fileBoundMs, deadlineMs - System.currentTimeMillis()).coerceAtLeast(0L)
            if (!proc.waitFor(waitMs, TimeUnit.MILLISECONDS)) {
                proc.descendants().forEach { it.destroyForcibly() }
                proc.destroyForcibly()
                results.add(
                    EditorJsFileResult(file.name, floor, hungAfterSeconds = TimeUnit.MILLISECONDS.toSeconds(waitMs)),
                )
                continue
            }
            val tap = tapFile.readText()
            results.add(
                EditorJsFileResult(
                    file.name,
                    floor,
                    exit = proc.exitValue(),
                    tests = tapCount(tap, "tests"),
                    pass = tapCount(tap, "pass"),
                    fail = tapCount(tap, "fail"),
                    cancelled = tapCount(tap, "cancelled"),
                ),
            )
        }

        // The trace the swallowed INHERIT never let into a log: one line per file, then the
        // total, in the BUILD LOG where the gate's grep and the human both read.
        for (result in results) logger.lifecycle("editorJsTest: {}", result.line())
        logger.lifecycle(
            "editorJsTest: total: tests {} pass {} fail {} cancelled {} across {} file(s), {} hung, {} not run",
            results.sumOf { it.tests ?: 0 },
            results.sumOf { it.pass ?: 0 },
            results.sumOf { it.fail ?: 0 },
            results.sumOf { it.cancelled ?: 0 },
            results.size,
            results.count { it.hungAfterSeconds != null },
            results.count { it.notRun },
        )
        val offenders = results.mapNotNull { it.offence() }
        if (offenders.isNotEmpty()) {
            throw GradleException(
                "editorJsTest FAILED — ${offenders.size} of ${results.size} file(s) offended (per-file TAP under " +
                    "build/editor-js-test/):\n  " + offenders.joinToString("\n  "),
            )
        }
    }
}

/**
 * How long editorJsTest waits for ONE file's node --test before killing its tree: the slowest
 * file measured 6.9 s serially (toast.test.mjs, node v24.21.0, 2026-10-11; it sleeps by design),
 * x10 is ~70 s, rounded up to 90 s for gate-load headroom. A kill is a red verdict NAMING the
 * file — the bounded, self-naming shape #508 asked for.
 */
val EDITOR_JS_FILE_BOUND_SECONDS = 90L

/** How long the whole editorJsTest task runs before every remaining file is reported NOT RUN. */
val EDITOR_JS_TEST_BOUND_MINUTES = 10L

/** One test file's outcome; the counts are null when the file left them unknown (hung, no summary). */
private data class EditorJsFileResult(
    val file: String,
    val floor: Int,
    val exit: Int? = null,
    val tests: Int? = null,
    val pass: Int? = null,
    val fail: Int? = null,
    val cancelled: Int? = null,
    val hungAfterSeconds: Long? = null,
    val notRun: Boolean = false,
)

/** The per-file lifecycle line for the build log: counts, plus which bound fired when one did. */
private fun EditorJsFileResult.line(): String {
    val counts = "tests ${tests ?: "?"} pass ${pass ?: "?"} fail ${fail ?: "?"} cancelled ${cancelled ?: "?"}"
    return when {
        notRun -> "$file: NOT RUN — the whole-task bound expired"
        hungAfterSeconds != null -> "$file: $counts — HUNG after $hungAfterSeconds s, killed"
        else -> "$file: $counts"
    }
}

/** Why this file fails the task, most specific reason first; null when it ran clean and complete. */
private fun EditorJsFileResult.offence(): String? =
    when {
        notRun -> "$file NOT RUN — the $EDITOR_JS_TEST_BOUND_MINUTES-minute whole-task bound expired"
        hungAfterSeconds != null ->
            "$file HUNG after $hungAfterSeconds s (per-file bound $EDITOR_JS_FILE_BOUND_SECONDS s) — killed"
        (fail ?: 0) > 0 -> "$file reported $fail failing test(s)"
        (cancelled ?: 0) > 0 -> "$file reported $cancelled cancelled test(s)"
        tests == null -> "$file exited $exit but wrote no TAP summary — its counts cannot be verified"
        tests < floor -> "$file reported $tests tests, below the static floor of $floor test( registrations"
        (exit ?: 1) != 0 -> "$file exited $exit with every reported test passing"
        else -> null
    }

/** The LAST top-level TAP summary line for a label; node indents subtest summaries, this anchors at 0. */
private fun tapCount(tap: String, label: String): Int? =
    Regex("^# $label (\\d+)$", RegexOption.MULTILINE).findAll(tap).lastOrNull()?.groupValues?.get(1)?.toIntOrNull()

fun nodeVersion(node: File): String =
    ProcessBuilder(node.absolutePath, "--version")
        .start()
        .inputStream
        .bufferedReader()
        .readText()
        .trim()

tasks.named("check") {
    dependsOn("editorJsTest")
}

// 033 Decision 3 — S3 cold fallback for the marketing site. `websiteExport` renders the
// public site through the SAME templates AND the same controllers the app serves, and copies
// the assets those pages reference, into build/website-export/ — ready for an emergency
// `aws s3 sync` (procedure in docs/deployment.md). The renderer lives in the test source set
// (SiteExportMain, delegating to SitePageRenderer) because the offline Thymeleaf render needs
// spring-test's mock web context; drop this block if the fallback rots unused.
//
// 073 widened it from one page to the whole public surface: the homepage, every intent-cluster
// page, the public docs index and every packaged doc, each written as <path>/index.html, plus
// robots.txt and a sitemap.xml. The exported sitemap carries no lastmod — the build timestamp
// comes from a Spring bean this process does not have, and an invented date is worse than an
// absent optional field.
val websiteExportAssets =
    tasks.register<Copy>("websiteExportAssets") {
        group = "distribution"
        description = "Copies the marketing site's assets into build/website-export (033 fallback)."
        // Wipe first. A Copy adds; it never removes, so an export directory left over from a
        // previous run makes the whole export un-reproducible — and, worse, makes the
        // completeness check in SiteExportMain pass on a file THIS run did not produce
        // (measured, 2026-09-04: removing the css copy still exported "successfully").
        val exportDir = layout.buildDirectory.dir("website-export")
        doFirst { exportDir.get().asFile.deleteRecursively() }
        from("src/main/resources/static/site") { into("site") }
        from("src/main/resources/static/vendor/design-system") { into("vendor/design-system") }
        // Site v2: site.css declares its own faces (Manrope, JetBrains Mono) by relative url();
        // the export's completeness check reads href/src, not CSS url(), so this copy is the
        // only thing standing between the static site and system-font fallback.
        from("src/main/resources/static/vendor/fonts") { into("vendor/fonts") }
        // 073: the public docs pages reference /css/docs.css through the layout's extraCss
        // slot. Without this the exported doc pages render as unstyled full-width prose —
        // which is exactly what the first export produced, and what a 404 on a stylesheet
        // looks like when nothing checks for it.
        from("src/main/resources/static/css") { into("css") }
        from("src/main/resources/static/favicon.ico")
        into(layout.buildDirectory.dir("website-export"))
    }

tasks.register<JavaExec>("websiteExport") {
    group = "distribution"
    description = "Renders the public site (pages, docs, robots, sitemap) + assets under build/website-export (033 fallback)."
    dependsOn("testClasses", websiteExportAssets)
    mainClass.set("co.datapipelines.web.ui.SiteExportMainKt")
    classpath = sourceSets["test"].runtimeClasspath
    // Resolved in doFirst: a Provider passed through vararg `args` stringifies instead of unwrapping.
    val outDir = layout.buildDirectory.dir("website-export")
    doFirst { args(outDir.get().asFile.absolutePath) }
}
