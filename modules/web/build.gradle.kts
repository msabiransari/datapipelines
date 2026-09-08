// module-structure.md §5.9 — the aggregation layer. Lists every module it touches
// EXPLICITLY (§4.2): it could reach most of them transitively through mcp-server,
// and declaring them is what makes the table checkable.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
    implementation(project(":modules:pipeline-contract"))
    implementation(project(":modules:templates"))
    implementation(project(":modules:datasources"))
    implementation(project(":modules:staging"))
    implementation(project(":modules:dag"))
    implementation(project(":modules:auth"))
    implementation(project(":modules:application"))
    implementation(project(":modules:mcp-server"))

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

tasks.register("editorJsTest") {
    group = "verification"
    description = "Runs the pipeline-editor JS unit tests (sse parser, coercion, paging) on node --test."
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
        logger.lifecycle("editorJsTest: {} on {}", node.absolutePath, nodeVersion(node))
        val argv =
            buildList {
                add(node.absolutePath)
                add("--test")
                addAll(testFiles.map { it.absolutePath })
            }
        val proc =
            ProcessBuilder(argv)
                .redirectOutput(ProcessBuilder.Redirect.INHERIT)
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start()
        if (proc.waitFor() != 0) throw GradleException("editorJsTest FAILED (node --test exited ${proc.exitValue()})")
    }
}

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
