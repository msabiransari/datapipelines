// module-structure.md §5.12 — allowed internal deps: app (full context, end-to-end).
// Its `test` runs in `build`/`check` like every module's (the root `browserTest` runs it alone);
// only `siteShots` below stays outside build/check (#297).
plugins { id("datapipelines.common-conventions") }

dependencies {
    testImplementation(project(":modules:app"))

    // Seeding the local admin needs a real Argon2id hash of the known password —
    // same declared-exception rationale as tests/integration-tests (§5.11's note).
    testImplementation(libs.argon2.jvm)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.playwright)
    // #8 lane 8b: the JavaScript authoring walk uses the native isolate.
    testRuntimeOnly(libs.graalvm.js.isolate.community)
}

// `siteShots` — the marketing site's screenshots, produced by a script (070 §C). Wired the
// way `websiteExport` is (modules/web/build.gradle.kts): a JavaExec over this module's TEST
// runtime classpath, because the driver shares the browser suite's Playwright dependency and
// nothing in production depends on it.
//
// Deliberately NOT part of build/check/browserTest: it drives a RUNNING demo deployment
// (./app.sh --start --demo nyc) and overwrites files under modules/web/.../static/site/img.
// It is invoked explicitly, after a UI round, to make the page catch up with the product.
//
//   ./gradlew siteShots -PshotsUrl=http://localhost:8080 \
//                       -PshotsEmail=you@example.com -PshotsPassword=… \
//                       [-PshotsSet=app|home] [-PshotsTheme=dark] [-PshotsFailingPipeline=<name>] [-PshotsOut=<dir>] \
//                       [-PshotsHeroOut=<dir>] [-PshotsPipeline=<name>] [-PshotsHeroPipeline=<name>] [-PshotsInspectNode=<id>] \
//                       [-PshotsOnly=<name[,name…]>]
tasks.register<JavaExec>("siteShots") {
    group = "documentation"
    description = "Captures the marketing site's screenshots from a running demo deployment (070 §C)."
    dependsOn("testClasses")
    mainClass.set("co.datapipelines.browser.SiteShotsMain")
    classpath = sourceSets["test"].runtimeClasspath
    // The default output directory is repo-relative; a JavaExec's working directory is the
    // SUBPROJECT, so without this the shots land in tests/browser-tests/modules/web/... and
    // the page keeps serving the old ones (measured, 2026-09-04).
    workingDir = rootProject.projectDir
    // Project properties are resolved at configuration time; the driver reads system
    // properties so the same values work from an IDE run configuration.
    doFirst {
        mapOf(
            "dp.shots.url" to "shotsUrl",
            "dp.shots.email" to "shotsEmail",
            "dp.shots.password" to "shotsPassword",
            "dp.shots.out" to "shotsOut",
            "dp.shots.set" to "shotsSet",
            "dp.shots.failingPipeline" to "shotsFailingPipeline",
            // #166: photograph a richer pipeline than the seeded defaults (the graph/inspector
            // shots and the hero respectively); absent = the defaults in SiteShotsMain.
            "dp.shots.pipeline" to "shotsPipeline",
            "dp.shots.heroPipeline" to "shotsHeroPipeline",
            "dp.shots.inspectNode" to "shotsInspectNode",
            // 093 §B: where the hero's poster (2400 wide, device scale 2) and OG (1200x630)
            // copies land. Absent = they are not produced at all — they do not ship with the
            // app and must never be written into static/site/img.
            "dp.shots.heroOut" to "shotsHeroOut",
            // 169: run only the named captures of the `site` set (see the driver's KDoc) —
            // a two-shot round must not overwrite the other PNGs from a different deployment.
            "dp.shots.only" to "shotsOnly",
            // 175: the `home` set's theme — dark|light, default light. Driven through the
            // settings select and restored afterwards; the `site` set is untouched by it.
            "dp.shots.theme" to "shotsTheme",
            // 175: the review captures' directory was documented (-PshotsReview) but never
            // wired — the driver's prop("shots.review") always fell back to build/site-review.
            "dp.shots.review" to "shotsReview",
        ).forEach { (systemProperty, projectProperty) ->
            (project.findProperty(projectProperty) as String?)?.let { systemProperty(systemProperty, it) }
        }
    }
}

// The browser suite is the tail of every full gate (680 s serial on 2026-09-12: 25 classes,
// one JVM). Forks split the classes across JVMs; each fork boots its own containers, app and
// Chromium (SharedBrowserE2e is per JVM), so the count is dp.test.forks.e2e — the RAM-priced
// knob — not the ordinary dp.test.forks. DEVELOPMENT.md §9.5.
tasks.named<Test>("test") {
    maxParallelForks =
        project.providers.gradleProperty("dp.test.forks.e2e").orNull?.toIntOrNull()?.coerceAtLeast(1) ?: 1
    // #139: ONE class order for CI and the box — each fork boots its own containers, app and
    // Chromium, so which suites cohabit a JVM must not depend on the filesystem's scan order.
    // Pinned to alphabetical UNLESS the property is passed: a passed
    // -Pjunit.jupiter.testclass.order.default is forwarded by the conventions plugin, and this
    // default then stays out of its way (the round-060 shuffle override keeps winning).
    if (project.providers.gradleProperty("junit.jupiter.testclass.order.default").orNull == null) {
        systemProperty("junit.jupiter.testclass.order.default", "org.junit.jupiter.api.ClassOrderer\$ClassName")
    }
    // #438 (owner ruling 2026-10-03): the gates run the browser suite with CI's per-action
    // patience. Only a project property reaches here — `-D` and ORG_GRADLE_PROJECT_* env vars
    // lose to the repo's gradle.properties (measured 2026-09-19) — so scripts/gate.sh and
    // scripts/pregate.sh pass `-Pdp.browser.ciPatience=true` and this forwards it to the test
    // JVM BrowserSuite reads. Absent = 30 s, where a plain local run stays impatient.
    project.providers.gradleProperty("dp.browser.ciPatience").orNull?.let {
        systemProperty("dp.browser.ciPatience", it)
    }
}

// #669: CI runs this suite as BrowserShards.BROWSER_SHARDS parallel jobs, one JVM each, every
// leg passing -Pdp.browser.shard=<i>/<n> (buildSrc/src/main/kotlin/BrowserShards.kt has the rule:
// sorted round-robin over the *Test.kt files read from the tree here, at configuration). Absent
// = today's unfiltered task, which is what build/check and every local run use. A shard = a JVM
// = its own containers, app and Chromium (SharedBrowserE2e, BrowserSuite), so a shard boundary
// changes nothing a case observes. A filtered run is red on verifyTestsExecuted by design, so
// the CI leg excludes that guard by name and verifyBrowserShardExecuted replaces it.
val browserTestSources = layout.projectDirectory.dir("src/test/kotlin").asFile
val browserShard = providers.gradleProperty(BrowserShards.PROPERTY).orNull?.let { BrowserShards.parseShard(it) }
if (browserShard != null) {
    val shardClasses =
        BrowserShards.partition(BrowserShards.testClassNames(browserTestSources), browserShard.index, browserShard.count)
    // An empty include list is NO filter — the whole suite would run in this leg.
    require(shardClasses.isNotEmpty()) {
        "-P${BrowserShards.PROPERTY}=${browserShard.index}/${browserShard.count} selects no class from $browserTestSources"
    }
    val shardLabel = "${browserShard.index}/${browserShard.count}"
    tasks.named<Test>("test") {
        filter { shardClasses.forEach { includeTestsMatching(it) } }
        doFirst {
            logger.lifecycle("browser shard $shardLabel: ${shardClasses.size} classes\n  " + shardClasses.joinToString("\n  "))
        }
    }
    // The leg's own zero-loss guard: EXACTLY its classes left a result file — none missing (a class
    // silently dropped), none extra (a class run outside its shard). Finalizes `test` like
    // verifyTestsExecuted does, so it judges a red test task too.
    val shardResults = layout.buildDirectory.dir("test-results/test").get().asFile
    val shardGuard = tasks.register("verifyBrowserShardExecuted") {
        group = "verification"
        description = "Fails unless this browser shard wrote a result file for exactly its classes (#669)."
        outputs.cacheIf { false }
        doLast {
            val expected = shardClasses.map { "TEST-$it.xml" }.toSet()
            val present = shardResults.listFiles { f -> f.isFile && f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
                ?.map { it.name }?.toSet().orEmpty()
            val missing = (expected - present).sorted()
            val extra = (present - expected).sorted()
            if (missing.isNotEmpty() || extra.isNotEmpty()) {
                throw GradleException(
                    "browser shard $shardLabel: ${expected.size} classes expected in $shardResults; " +
                        "missing ${missing.size} $missing, outside the shard ${extra.size} $extra",
                )
            }
            logger.lifecycle("browser shard $shardLabel: ${present.size} result files = its ${expected.size} classes")
        }
    }
    tasks.named("test") { finalizedBy(shardGuard) }
}

// #669: the partition's CI-covered guard, in `check` (so the gate job's build, Gate A and a local
// `build` run it; no browser, no JVM fork). Every *Test.kt is in exactly one shard of
// BROWSER_SHARDS, no shard is empty, no source would be mis-sharded (a `class …Test` outside a
// file of its own name runs unsharded and in NO shard), and the list is not vacuous.
val verifyBrowserShardPartition = tasks.register("verifyBrowserShardPartition") {
    group = "verification"
    description = "Fails unless every browser test class is in exactly one of the CI shards (#669)."
    outputs.cacheIf { false }
    doLast {
        val names = BrowserShards.testClassNames(browserTestSources)
        val problems = BrowserShards.unshardableSources(browserTestSources).toMutableList()
        if (names.size < BrowserShards.MIN_CLASSES) {
            problems += "only ${names.size} *Test.kt classes under $browserTestSources (floor ${BrowserShards.MIN_CLASSES})"
        }
        val shards = (1..BrowserShards.BROWSER_SHARDS).map { BrowserShards.partition(names, it, BrowserShards.BROWSER_SHARDS) }
        shards.forEachIndexed { i, shard -> if (shard.isEmpty()) problems += "shard ${i + 1} is empty" }
        val placed = shards.flatten()
        (placed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys).forEach { problems += "$it is in two shards" }
        (names.toSet() - placed.toSet()).forEach { problems += "$it is in no shard" }
        if (problems.isNotEmpty()) {
            throw GradleException("browser shard partition refused:\n  " + problems.joinToString("\n  "))
        }
        logger.lifecycle(
            "browser shard partition: ${names.size} classes in ${shards.size} shards of ${shards.map { it.size }}",
        )
    }
}
tasks.named("check") { dependsOn(verifyBrowserShardPartition) }
