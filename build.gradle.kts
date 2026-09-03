import org.gradle.api.artifacts.dsl.LockMode

// Root build — module-structure.md §7.
// Conventions are applied per-module via the `datapipelines.common-conventions`
// plugin from buildSrc; this file carries only project coordinates and the
// cross-module verification tasks.

plugins {
    base
    // Aggregated coverage report across all modules (module-structure.md §7.7).
    // The plugin is applied per-module by CommonConventionsPlugin; here it only
    // merges every module's measurements into the root `koverHtmlReport` /
    // `koverXmlReport` tasks via the `kover` dependency configuration below.
    // No version: the plugin jar is already on the build-script classpath via
    // buildSrc, so a versioned alias would be rejected as a duplicate.
    id("org.jetbrains.kotlinx.kover")
}

allprojects {
    group = "co.datapipelines"
    version = providers.gradleProperty("datapipelines.version").getOrElse("1.0.0-SNAPSHOT")
}

dependencies {
    // Root Kover aggregate, derived reactively (009/F9 → 012/F3): wire every
    // subproject that ACTUALLY applies the Kover plugin, via
    // pluginManager.withPlugin. The previous `buildFile.exists()` filter
    // dragged :tests:integration-tests (Testcontainers) into the report's
    // task graph — the root koverHtmlReport then needed a Docker daemon, and
    // the aggregate absorbed integration coverage, making its numbers
    // incomparable with the unit-only 2026-08-15 baseline the COVERAGE_FLOORS
    // derive from. :tests:integration-tests is therefore excluded from the
    // ROOT aggregate DELIBERATELY, for baseline comparability; its own
    // module-level report still exists. A future subproject without the
    // Kover plugin is simply not wired — no obscure resolution break.
    // (The callbacks fire as each subproject applies the plugin, which is
    // after this block runs; the root `kover` configuration is not resolved
    // until the report tasks execute, so late additions are safe.)
    subprojects.forEach { sub ->
        sub.pluginManager.withPlugin("org.jetbrains.kotlinx.kover") {
            if (sub.path != ":tests:integration-tests") {
                kover(project(sub.path))
            }
        }
    }
}

// The root project resolves the `kover` aggregation configuration, so it locks
// like every module does (module-structure.md §7.6) — same STRICT mode, same
// regenerate-with-resolveAndLockAll flow (DEVELOPMENT.md §6.2).
dependencyLocking {
    lockAllConfigurations()
    lockMode.set(LockMode.STRICT)
}

// Same "lock all configurations in a single build execution" pattern the
// convention plugin registers per module — the root has no convention plugin,
// so it registers its own copy.
tasks.register("resolveAndLockAll") {
    group = "verification"
    description = "Resolves every resolvable configuration; run with --write-locks to (re)generate gradle.lockfile."
    notCompatibleWithConfigurationCache("Resolves configurations eagerly at execution time")
    doFirst {
        require(gradle.startParameter.isWriteDependencyLocks) {
            "$path must be run with --write-locks; its only purpose is regenerating gradle.lockfile"
        }
    }
    doLast {
        configurations
            .filter { it.isCanBeResolved }
            .forEach { it.resolve() }
    }
}

// ---------------------------------------------------------------------------
// Cold dependency-verification surface (025b/C2 — the CI `cold-verify` job).
//
// Gradle checks gradle/verification-metadata.xml ONLY for artifacts it
// downloads in the current run, so a metadata gap is visible only to a cold
// GRADLE_USER_HOME resolving the configuration that carries the artifact.
// `bootJar` alone resolves just runtime graphs; the T30 gaps were mostly
// test-only and tool-only artifacts. These tasks resolve EVERY resolvable
// configuration of EVERY project — runtimeClasspath, testRuntimeClasspath,
// the compile classpaths, the detekt/ktlint tool classpaths, Kover plumbing —
// so a cold run verifies the whole dependency surface. Same
// isCanBeResolved-guarded pattern as resolveAndLockAll above; a configuration
// a project does not have simply never exists, so there is nothing to fail on.
// ---------------------------------------------------------------------------
val resolveDependencyVerificationSurface = tasks.register("resolveDependencyVerificationSurface") {
    group = "verification"
    description = "Resolves every resolvable configuration in every project; run against a cold GRADLE_USER_HOME to exercise verification-metadata.xml."
}

allprojects {
    val resolveOwnDependencyVerificationSurface = tasks.register("resolveOwnDependencyVerificationSurface") {
        group = "verification"
        description = "Resolves every resolvable configuration of this project (the per-project half of :resolveDependencyVerificationSurface)."
        notCompatibleWithConfigurationCache("Resolves configurations eagerly at execution time")
        doLast {
            configurations
                .filter { it.isCanBeResolved }
                .forEach { it.resolve() }
        }
    }
    resolveDependencyVerificationSurface.configure { dependsOn(resolveOwnDependencyVerificationSurface) }
}

// ---------------------------------------------------------------------------
// §4.2 enforcement — the allowed-dependency table, machine-checked.
//
// "A module's dependencies block MUST list a subset of its row below, and every
//  module it uses at compile time MUST be listed explicitly."
//
// Adding an edge means editing module-structure.md §4.2 FIRST, then this map.
// That ordering is the review gate; this task is what makes it non-optional.
// ---------------------------------------------------------------------------
val allowedInternalDependencies: Map<String, Set<String>> = mapOf(
    ":modules:typesystem" to emptySet(),
    ":modules:pipeline-contract" to setOf(":modules:typesystem"),
    ":modules:templates" to setOf(":modules:typesystem", ":modules:pipeline-contract"),
    ":modules:datasources" to setOf(":modules:typesystem"),
    ":modules:staging" to setOf(":modules:typesystem"),
    ":modules:auth" to setOf(":modules:typesystem"),
    ":modules:dag" to setOf(
        ":modules:typesystem",
        ":modules:pipeline-contract",
        ":modules:templates",
        ":modules:datasources",
        ":modules:staging",
    ),
    // The cross-aggregate use-case layer (056/R6): below `web` and `mcp-server`, above the
    // domain modules. `templates` and `datasources` are allowed here for slices B/C's moves
    // (the import services, promotion) and are not declared in the module's build file until
    // something compiles against them.
    ":modules:application" to setOf(
        ":modules:typesystem",
        ":modules:pipeline-contract",
        ":modules:templates",
        ":modules:datasources",
        ":modules:dag",
        ":modules:auth",
    ),
    ":modules:mcp-server" to setOf(
        ":modules:typesystem",
        ":modules:pipeline-contract",
        ":modules:templates",
        ":modules:datasources",
        ":modules:dag",
        ":modules:auth",
        ":modules:application",
    ),
    ":modules:web" to setOf(
        ":modules:typesystem",
        ":modules:pipeline-contract",
        ":modules:templates",
        ":modules:datasources",
        ":modules:staging",
        ":modules:dag",
        ":modules:auth",
        ":modules:application",
        ":modules:mcp-server",
    ),
    ":modules:app" to setOf(":modules:web"),
    ":tests:integration-tests" to setOf(":modules:app"),
    ":tests:browser-tests" to setOf(":modules:app"),
)

// Kover wires a per-module SELF-edge through its `kover` aggregation bucket —
// plugin plumbing, not a §4.2 declaration. The names below are the exact
// plumbing configurations of the PINNED plugin, probed 2026-08-16
// (org.jetbrains.kotlinx.kover 0.9.9: every module's `kover` configuration
// holds exactly one ProjectDependency — its own self-edge;
// `koverExternalArtifacts` holds none). 009/F7 exempted ANY configuration
// named kover* wholesale; 012/F4 narrows that to these exact names AND to
// SELF-edges only, so that a module declaring `kover(project(":modules:x"))`
// (a documented Kover API the root build itself uses) still fails the guard,
// as does a self-edge declared through a non-plumbing configuration such as
// `implementation(project(":modules:x"))` inside x itself.
val koverPlumbingConfigurations = setOf("kover", "koverExternalArtifacts")

val verifyModuleDependencies = tasks.register("verifyModuleDependencies") {
    group = "verification"
    description = "Fails if any module declares a project dependency outside its module-structure.md §4.2 row."

    // Snapshot at configuration time: Gradle 9 forbids cross-project state access from task actions.
    // (The registration action itself runs at task realization — after all projects are
    // evaluated — so the subproject configurations are fully populated here.)
    val declared: Map<String, Set<String>> = subprojects
        .filter { it.buildFile.exists() }
        .associate { sub ->
            sub.path to sub.configurations
                .flatMap { cfg ->
                    val isKoverPlumbing = cfg.name in koverPlumbingConfigurations
                    cfg.dependencies.withType(ProjectDependency::class.java)
                        // Only the plugin's OWN self-edges are exempt (012/F4) —
                        // see koverPlumbingConfigurations above for why the
                        // 009/F7 name-prefix blocklist was too wide.
                        .filterNot { isKoverPlumbing && it.path == sub.path }
                        .map { it.path }
                }
                .toSet()
        }
    val allowed = allowedInternalDependencies

    doLast {
        val violations = mutableListOf<String>()
        declared.forEach { (modulePath, deps) ->
            val row = allowed[modulePath]
            if (row == null) {
                violations += "$modulePath is not listed in the §4.2 table (add it to the table AND to build.gradle.kts)"
                return@forEach
            }
            (deps - row).sorted().forEach { extra ->
                violations += "$modulePath declares $extra, which is NOT in its §4.2 allowed set $row"
            }
        }
        if (violations.isNotEmpty()) {
            throw GradleException(
                "module-structure.md §4.2 dependency table violated:\n" +
                    violations.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("§4.2 dependency table: ${declared.size} modules checked, 0 violations.")
    }
}

// Every `build` runs the layering check.
subprojects {
    plugins.withId("java") {
        tasks.named("check").configure { dependsOn(verifyModuleDependencies) }
    }
}

// Lifecycle aggregates documented in DEVELOPMENT.md §9 and §13.
tasks.register("integrationTest") {
    group = "verification"
    description = "Runs cross-module integration tests (tests/integration-tests)."
    dependsOn(":tests:integration-tests:test")
}

// The browser suite's separate invocation (module-structure §5.12): deliberately NOT
// part of build/check/verify — it downloads browser binaries on first use and launches
// chromium, so it is invoked deliberately before a release, never on every build.
tasks.register("browserTest") {
    group = "verification"
    description = "Runs the Playwright browser suite of the UI golden paths (tests/browser-tests)."
    dependsOn(":tests:browser-tests:test")
}

tasks.register("verify") {
    group = "verification"
    description = "lint + test + build — the pre-push gate (DEVELOPMENT.md §13)."
    dependsOn(tasks.named("build"), verifyModuleDependencies)
}
