// module-structure.md §5.12 — allowed internal deps: app (full context, end-to-end).
// Separately invoked via the root `browserTest` task; NOT part of build/check.
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
}
