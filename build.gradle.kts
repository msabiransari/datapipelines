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
//
// #214: the map is also compared against the §4.2 TABLE ITSELF (parsed from
// docs/module-structure.md) in both directions — a hand copy compared to
// nothing drifts silently, and did (the §4.1 diagram omitted calculators and
// scripting for two rounds). And every module the table names must appear in
// §4.1's diagram block; edge fidelity there stays a human read.
// ---------------------------------------------------------------------------
val allowedInternalDependencies: Map<String, Set<String>> = mapOf(
    ":modules:typesystem" to emptySet(),
    // 072 calculators §0.4/C12 — layer 0 beside typesystem. The empty-but-for-typesystem row IS
    // the purity guarantee: a kind that could reach a database or an HTTP client would stop being
    // a pure function of its inputs, and the executor's freedom to evaluate one anywhere depends
    // on that. Adding a second entry here is the change a reviewer must refuse.
    ":modules:calculators" to setOf(":modules:typesystem"),
    // 7a transform engine — layer 0 beside typesystem. The seam evaluates untrusted script
    // bodies as pure functions of their JSON input (transform-nodes design §4.1); its only
    // internal edge is typesystem, for the canonical LogicalType/ColumnSchema the type gate
    // (§5.3) and the egress encoding rule over. A second entry here would give a script
    // engine a route to I/O that the purity test and the breach suite do not fence.
    ":modules:scripting" to setOf(":modules:typesystem"),
    ":modules:pipeline-contract" to setOf(":modules:typesystem", ":modules:calculators"),
    ":modules:templates" to setOf(":modules:typesystem", ":modules:pipeline-contract", ":modules:scripting"),
    ":modules:datasources" to setOf(":modules:typesystem"),
    ":modules:staging" to setOf(":modules:typesystem"),
    ":modules:auth" to setOf(":modules:typesystem"),
    ":modules:dag" to setOf(
        ":modules:typesystem",
        ":modules:calculators",
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
        ":modules:calculators",
        ":modules:pipeline-contract",
        ":modules:templates",
        ":modules:datasources",
        ":modules:dag",
        ":modules:auth",
        ":modules:application",
    ),
    ":modules:web" to setOf(
        ":modules:typesystem",
        ":modules:calculators",
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

// #214 — the §4.2 table and the §4.1 diagram, parsed out of the doc so the
// normative text and this map cannot drift apart. Map keys are Gradle paths
// (`:modules:templates`); the table names modules plainly (`templates`,
// `tests/integration-tests`).
fun String.toLayeringTableName(): String =
    removePrefix(":").replace(":", "/").removePrefix("modules/")

fun parseLayeringTable(docText: String): Map<String, Set<String>> {
    require(docText.contains("### 4.2 The dependency rule")) {
        "docs/module-structure.md has no '### 4.2 The dependency rule' section"
    }
    val section = docText
        .substringAfter("### 4.2 The dependency rule")
        .substringBefore("### 4.3")
    val rowPattern = Regex("""^\| `([^`]+)` \| (.+) \|$""")
    val table = section.lineSequence()
        .mapNotNull { rowPattern.find(it.trim())?.destructured }
        .associate { (module, depsCell) ->
            module to Regex("""`([^`]+)`""").findAll(depsCell)
                .map { it.groupValues[1] }
                .toSet()
        }
    require(table.isNotEmpty()) {
        "docs/module-structure.md §4.2 yielded no table rows — the parse is broken, not the table"
    }
    return table
}

fun parseLayeringDiagram(docText: String): String {
    require(docText.contains("### 4.1 Layered dependency graph")) {
        "docs/module-structure.md has no '### 4.1 Layered dependency graph' section"
    }
    return docText
        .substringAfter("### 4.1 Layered dependency graph")
        .substringBefore("### 4.2")
        .substringAfter("```")
        .substringBefore("```")
}

val verifyModuleDependencies = tasks.register("verifyModuleDependencies") {
    group = "verification"
    description = "Fails if any module declares a project dependency outside its module-structure.md §4.2 row, if the §4.2 table and this build's map disagree, or if a §4.2 module is absent from the §4.1 diagram."

    val moduleStructureDoc = layout.projectDirectory.file("docs/module-structure.md")
    inputs.file(moduleStructureDoc)

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

        // #214, part one: the §4.2 table (normative) vs the map above (enforcement).
        val docText = moduleStructureDoc.asFile.readText()
        val table = parseLayeringTable(docText)
        val mapAsTable = allowed.mapKeys { (path, _) -> path.toLayeringTableName() }
            .mapValues { (_, deps) -> deps.map { it.toLayeringTableName() }.toSet() }
        val drift = mutableListOf<String>()
        (table.keys + mapAsTable.keys).toSortedSet().forEach { module ->
            val inTable = table[module]
            val inMap = mapAsTable[module]
            when {
                inTable == null -> drift += "$module is in build.gradle.kts's allowedInternalDependencies but NOT in the §4.2 table"
                inMap == null -> drift += "$module is in the §4.2 table but NOT in build.gradle.kts's allowedInternalDependencies"
                inTable != inMap -> drift += "$module's §4.2 row $inTable disagrees with its map entry $inMap"
            }
        }
        if (drift.isNotEmpty()) {
            throw GradleException(
                "module-structure.md §4.2 table and build.gradle.kts's allowedInternalDependencies disagree " +
                    "(the table is normative; edit it first, then the map):\n" +
                    drift.joinToString("\n") { "  - $it" },
            )
        }

        // #214, part two: every module the table names has its own box in §4.1's
        // diagram. The name must appear between box borders — an occurrence in
        // another module's `←` edge list is an edge, not presence. Edge fidelity
        // stays a human read — ASCII arrows cannot be parsed honestly; module
        // presence can.
        val diagram = parseLayeringDiagram(docText)
        val absent = table.keys.filter { name ->
            !diagram.contains(Regex("""│\s*${Regex.escape(name)}\s*│"""))
        }
        if (absent.isNotEmpty()) {
            throw GradleException(
                "module-structure.md §4.1's diagram does not name these §4.2 modules " +
                    "(the diagram is a rendering of the table; redraw it):\n" +
                    absent.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("§4.2 table ↔ map ↔ §4.1 diagram: ${table.size} modules consistent.")
    }
}

// #214 — DEVELOPMENT.md §6.3 owns the rationale for hand-verified
// verification-metadata components, because `--write-verification-metadata`
// rewrites the XML and drops hand comments from it. This check fails when the
// XML contains a comment at all (its home is §6.3) and when a component the
// §6.3 table lists is absent from the XML.
val verifyVerificationMetadataDocs = tasks.register("verifyVerificationMetadataDocs") {
    group = "verification"
    description = "Fails when verification-metadata.xml carries a hand comment, or a DEVELOPMENT.md §6.3 hand-verified component is absent from it."

    val metadata = layout.projectDirectory.file("gradle/verification-metadata.xml")
    val developmentDoc = layout.projectDirectory.file("DEVELOPMENT.md")
    inputs.file(metadata)
    inputs.file(developmentDoc)

    doLast {
        val xml = metadata.asFile.readText()
        if (xml.contains("<!--")) {
            throw GradleException(
                "gradle/verification-metadata.xml contains a hand-written XML comment; " +
                    "the checksum regeneration drops comments. The rationale's home is " +
                    "DEVELOPMENT.md §6.3 (Hand-verified entries) — move it there.",
            )
        }
        val docText = developmentDoc.asFile.readText()
        require(docText.contains("#### Hand-verified entries")) {
            "DEVELOPMENT.md §6.3 has no 'Hand-verified entries' table — the home of every hand-verified checksum's rationale"
        }
        val section = docText
            .substringAfter("#### Hand-verified entries")
            .substringBefore("\n---")
        val componentPattern = Regex("""^\| `([^`]+:[^`]+:[^`]+)` \|""")
        val components = section.lineSequence()
            .mapNotNull { componentPattern.find(it.trim())?.groupValues?.get(1) }
            .toList()
        val missing = components.filter { coordinate ->
            val (group, name, version) = coordinate.split(":")
            !xml.contains("""<component group="$group" name="$name" version="$version"""")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "DEVELOPMENT.md §6.3 lists hand-verified components absent from gradle/verification-metadata.xml:\n" +
                    missing.joinToString("\n") { "  - $it" },
            )
        }
        logger.lifecycle("verification-metadata.xml: comment-free; ${components.size} hand-verified component(s) present.")
    }
}

// T119 (075): the compose env-contract audit runs on every `build`, not when someone
// remembers. It was born in 051 as a hand-run script, and 074 then shipped three
// application.yml keys with no compose pass-through — the exact class it detects — and
// nothing said so for a round. Since 081 it also proves the ONE-AUTHORITY rule over the
// two tracked env files: every variable the app binds is declared in exactly one of
// deploy/env/defaults.env and deploy/secrets.env.example (and the posture-dependent pair
// in neither, because their default is the profile's).
//
// Inputs are declared so an unchanged tree skips it; the gate's `--rerun-tasks` forces
// it regardless, and `> Task :composeEnvAudit UP-TO-DATE` in a log is the tell that a
// run you are citing did not actually execute (MISTAKES.md, the lint variant).
val composeEnvAudit = tasks.register<Exec>("composeEnvAudit") {
    group = "verification"
    description = "Fails if compose.yml, defaults.env or secrets.env.example drift from application.yml's env contract."
    val script = layout.projectDirectory.file("scripts/compose-env-audit.sh")
    inputs.file(script)
    inputs.file(layout.projectDirectory.file("modules/app/src/main/resources/application.yml"))
    inputs.file(layout.projectDirectory.file("deploy/compose.yml"))
    inputs.file(layout.projectDirectory.file("deploy/env/defaults.env"))
    inputs.file(layout.projectDirectory.file("deploy/secrets.env.example"))
    outputs.file(layout.buildDirectory.file("compose-env-audit.ok"))
    val stamp = layout.buildDirectory.file("compose-env-audit.ok")
    commandLine("bash", script.asFile.absolutePath)
    doLast {
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

// #196: no secret's VALUE is interpolated into any service's command, entrypoint or
// healthcheck across deploy/compose*.yml — a `${VAR}` there lands on the container's argv,
// which `docker inspect` and `ps` show; `$$VAR` (a name for the shell) and the environment
// are the two allowed homes (deployment.md §4.2.1, §9). Born red on the pre-#196 tree.
val composeArgvSecretsAudit = tasks.register<Exec>("composeArgvSecretsAudit") {
    group = "verification"
    description = "Fails if a compose service's command, entrypoint or healthcheck interpolates a secret's value."
    val script = layout.projectDirectory.file("scripts/compose-argv-secrets-audit.sh")
    inputs.file(script)
    inputs.files(layout.projectDirectory.dir("deploy").asFileTree.matching { include("compose*.yml") })
    outputs.file(layout.buildDirectory.file("compose-argv-secrets-audit.ok"))
    val stamp = layout.buildDirectory.file("compose-argv-secrets-audit.ok")
    commandLine("bash", script.asFile.absolutePath)
    doLast {
        stamp.get().asFile.apply { parentFile.mkdirs() }.writeText("ok\n")
    }
}

// Every `build` runs the layering check and the two compose audits.
subprojects {
    plugins.withId("java") {
        tasks.named("check").configure {
            dependsOn(verifyModuleDependencies, verifyVerificationMetadataDocs, composeEnvAudit, composeArgvSecretsAudit)
        }
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
// The screenshot driver is a sibling of the browser suite, registered in the module itself
// (tests/browser-tests/build.gradle.kts) and reachable from the root as :tests:browser-tests:siteShots.
tasks.register("siteShots") {
    group = "documentation"
    description = "Captures the marketing site screenshots from a running demo deployment (070 §C)."
    dependsOn(":tests:browser-tests:siteShots")
}

tasks.register("browserTest") {
    group = "verification"
    description = "Runs the Playwright browser suite of the UI golden paths (tests/browser-tests)."
    dependsOn(":tests:browser-tests:test")
}

tasks.register("verify") {
    group = "verification"
    description = "lint + test + build — the pre-push gate (DEVELOPMENT.md §13)."
    dependsOn(
        tasks.named("build"),
        verifyModuleDependencies,
        verifyVerificationMetadataDocs,
        composeEnvAudit,
        composeArgvSecretsAudit,
    )
}
