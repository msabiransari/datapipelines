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
    kover(project(":modules:typesystem"))
    kover(project(":modules:pipeline-contract"))
    kover(project(":modules:templates"))
    kover(project(":modules:datasources"))
    kover(project(":modules:staging"))
    kover(project(":modules:auth"))
    kover(project(":modules:dag"))
    kover(project(":modules:mcp-server"))
    kover(project(":modules:web"))
    kover(project(":modules:app"))
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
    ":modules:mcp-server" to setOf(
        ":modules:typesystem",
        ":modules:pipeline-contract",
        ":modules:templates",
        ":modules:datasources",
        ":modules:dag",
        ":modules:auth",
    ),
    ":modules:web" to setOf(
        ":modules:typesystem",
        ":modules:pipeline-contract",
        ":modules:templates",
        ":modules:datasources",
        ":modules:staging",
        ":modules:dag",
        ":modules:auth",
        ":modules:mcp-server",
    ),
    ":modules:app" to setOf(":modules:web"),
    ":tests:integration-tests" to setOf(":modules:app"),
)

val verifyModuleDependencies = tasks.register("verifyModuleDependencies") {
    group = "verification"
    description = "Fails if any module declares a project dependency outside its module-structure.md §4.2 row."

    // Snapshot at configuration time: Gradle 9 forbids cross-project state access from task actions.
    val declared: Map<String, Set<String>> = subprojects
        .filter { it.buildFile.exists() }
        .associate { sub ->
            sub.path to sub.configurations
                // Kover wires a self-edge per module (its kover/koverExternalArtifacts
                // bucket configurations) — plugin plumbing, not a §4.2 declaration.
                // Filter at the CONFIGURATION level (009/F7): the previous
                // `.filter { it != sub.path }` dropped self-edges from ALL
                // configurations, so a genuinely declared
                // `implementation(project(":modules:x"))` inside x itself
                // passed silently.
                .filterNot { it.name.startsWith("kover") }
                .flatMap { cfg -> cfg.dependencies.withType(ProjectDependency::class.java) }
                .map { it.path }
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

tasks.register("verify") {
    group = "verification"
    description = "lint + test + build — the pre-push gate (DEVELOPMENT.md §13)."
    dependsOn(tasks.named("build"), verifyModuleDependencies)
}
