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
// (./app.sh --start --demo-nyc) and overwrites files under modules/web/.../static/site/img.
// It is invoked explicitly, after a UI round, to make the page catch up with the product.
//
//   ./gradlew siteShots -PshotsUrl=http://localhost:8080 \
//                       -PshotsEmail=you@example.com -PshotsPassword=… \
//                       [-PshotsFailingPipeline=<name>] [-PshotsOut=<dir>]
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
            "dp.shots.failingPipeline" to "shotsFailingPipeline",
        ).forEach { (systemProperty, projectProperty) ->
            (project.findProperty(projectProperty) as String?)?.let { systemProperty(systemProperty, it) }
        }
    }
}
