import io.gitlab.arturbosch.detekt.Detekt
import io.gitlab.arturbosch.detekt.extensions.DetektExtension
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.file.FileCollection
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.testing.Test
import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.dependencies
import org.gradle.kotlin.dsl.dependencyLocking
import org.gradle.kotlin.dsl.getByType
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import java.io.File
import java.util.zip.ZipFile

/**
 * Build conventions applied to every module (module-structure.md §7).
 *
 * Configures: the JDK 21 toolchain (§7.2), strict Kotlin compilation, the
 * Spring Boot BOM as a dependency platform, the JUnit 5 + MockK + Kotest test
 * stack (§7.4), ktlint + detekt (§7.3), and STRICT dependency locking (§7.6).
 *
 * ## Rule: ONE JUnit Platform TestEngine per Test task
 *
 * A `Test` task must have exactly one engine on its runtime classpath — Jupiter.
 * Kotest is present for its **assertions** (`kotest-assertions-core`), which are
 * engine-independent; `kotest-runner-junit5` is deliberately absent because it
 * registers a second engine via `META-INF/services`.
 *
 * This is not stylistic. Measured on 2026-08-08, same machine, back-to-back,
 * 8 × (`gradlew clean` + `gradlew build`) per arm:
 *
 * | Engines on the test classpath | Failures |
 * |---|---|
 * | Jupiter only                  | **0 / 8** |
 * | Jupiter + Kotest              | **2 / 8** |
 *
 * Both failures were corrupted Gradle 9.6 test-result state, not test failures:
 * one `java.io.EOFException` from a truncated `results-generic.bin`, and one run
 * where the test task silently produced no results at all and the build would
 * have reported success having executed ZERO tests.
 *
 * If a module genuinely needs Kotest spec styles (`StringSpec`, `FunSpec`, …), it
 * adds `kotest-runner-junit5` to its **own** build file and gives those specs
 * their **own** `Test` task, so the two engines never share one task run. Do not
 * add the runner back here.
 *
 * Deviations from the §7.1 sketch, both deliberate and both reported at P0:
 *  - `-Xcontext-receivers` is NOT set. The flag was removed from the Kotlin
 *    compiler in 2.2; on the pinned 2.4.10 it is a hard error, and with
 *    `allWarningsAsErrors` there is no soft-landing. Context parameters
 *    (`-Xcontext-parameters`) are the successor, and nothing in v1 needs them
 *    yet, so no replacement flag is set.
 *  - The Spring Boot BOM is imported as a Gradle `platform()` rather than via
 *    the `io.spring.dependency-management` plugin. Same effect (BOM-managed
 *    versions, §6 gate G2 step 3) without the legacy plugin, and a non-enforced
 *    platform lets the Kotlin plugin's newer stdlib win over the BOM's 1.9.25.
 */
class CommonConventionsPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        project.plugins.apply("org.jetbrains.kotlin.jvm")
        // kotlin-spring (allopen): opens @Component/@Configuration/@Transactional
        // classes for CGLIB proxying so no `open` keyword is needed (§8.4).
        project.plugins.apply("org.jetbrains.kotlin.plugin.spring")
        project.plugins.apply("java-library")
        project.plugins.apply("org.jlleitschuh.gradle.ktlint")
        project.plugins.apply("io.gitlab.arturbosch.detekt")

        val libs = project.extensions.getByType<VersionCatalogsExtension>().named("libs")
        fun lib(alias: String) = libs.findLibrary(alias).orElseThrow {
            IllegalStateException("Version catalog alias '$alias' is missing from gradle/libs.versions.toml")
        }

        project.extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }

        project.extensions.configure<KotlinJvmProjectExtension> {
            jvmToolchain(JDK_VERSION)
            compilerOptions {
                jvmTarget.set(JvmTarget.JVM_21)
                freeCompilerArgs.add("-Xjsr305=strict")
                allWarningsAsErrors.set(true)
            }
        }

        project.dependencies {
            // BOM: every catalog entry declared without a version resolves here.
            add("implementation", platform(lib("spring-boot-dependencies")))
            add("testImplementation", platform(lib("spring-boot-dependencies")))

            // SECURITY OVERRIDE (2026-08-07, GHSA-5gvw-p9qm-jgwh / GHSA-5jmj-h7xm-6q6v /
            // GHSA-mhm7-754m-9p8w): jackson-bom 2.21.5 applied AFTER the Spring Boot BOM.
            // Both are non-enforced platforms, so Gradle's conflict resolution picks the
            // higher version and every Jackson artifact — including the transitively
            // pulled jackson-databind the advisories name — moves to 2.21.5. Retirement
            // condition is documented on the `jackson` entry in libs.versions.toml.
            add("implementation", platform(lib("jackson-bom")))
            add("testImplementation", platform(lib("jackson-bom")))

            add("testImplementation", lib("junit-jupiter"))
            add("testImplementation", lib("mockk"))
            // Kotest ASSERTIONS only — deliberately NOT kotest-runner-junit5.
            //
            // module-structure.md §7.4 is explicit about the split: "JUnit 5 as the
            // platform" and "Kotest for assertion library". `kotest-assertions-core`
            // supplies the matchers (`shouldBe`, `shouldContainExactly`); the runner
            // supplies a JUnit Platform TestEngine, which is needed only to execute
            // Kotest *spec* classes (StringSpec/FunSpec/…). Nothing in this repo uses
            // one. (§7.1's older code sketch lists `libs.kotest.runner`; §7.4's prose
            // is the convention, and adding the runner here was my error at P0.)
            //
            // It is not merely unused — it is actively harmful. kotest-runner-junit5
            // registers io.kotest.runner.junit.platform.KotestJunitPlatformTestEngine
            // through META-INF/services, so EVERY test JVM loaded a SECOND TestEngine
            // that discovers zero tests, alongside the Jupiter engine. That engine has
            // open incompatibility reports against Gradle 9 (kotest#5013: "could not
            // be instantiated"; plus reports of it discovering tests without reporting
            // them back to Gradle), and Gradle 9's test-result store surfaced exactly
            // that class of fault here as an intermittent
            // `EOFException` / truncated `results-generic.bin` in
            // SerializableTestResultStore.hasResults.
            //
            // A module that genuinely wants Kotest spec styles adds the runner to its
            // OWN build file and owns the Gradle 9 compatibility question there —
            // it does not come back into the shared conventions.
            add("testImplementation", lib("kotest-assertions-core"))
            add("testRuntimeOnly", lib("junit-platform-launcher"))
        }

        project.extensions.configure<KtlintExtension> {
            version.set(KTLINT_VERSION)
            // ktlint's own reporting; detekt covers static analysis.
            filter {
                exclude { it.file.path.contains("${project.layout.buildDirectory.get()}") }
            }
        }

        project.extensions.configure<DetektExtension> {
            buildUponDefaultConfig = true
            allRules = false
            config.setFrom(project.rootProject.file("config/detekt/detekt.yml"))
            baseline = project.rootProject.file("config/detekt/baseline.xml").takeIf { it.exists() }
        }

        project.tasks.withType<Detekt>().configureEach {
            jvmTarget = "21"
            reports {
                html.required.set(true)
                xml.required.set(false)
                txt.required.set(false)
                sarif.required.set(false)
                md.required.set(false)
            }
        }

        project.tasks.withType<Test>().configureEach {
            useJUnitPlatform()
            // If the platform discovers no tests at all, that is a broken test setup,
            // not a pass. Guards the "engine found nothing" half of the false-green.
            failOnNoDiscoveredTests.set(true)
            // The ONE-TestEngine rule (see this class's KDoc) enforced instead of merely
            // written down. `classpath` is this task's own input, so reading it here is
            // configuration-cache safe.
            doFirst { assertSingleTestEngine(classpath, path) }
            testLogging {
                events("passed", "skipped", "failed")
                showExceptions = true
                showCauses = true
                exceptionFormat = TestExceptionFormat.FULL
            }
        }

        // Dependency locking — STRICT, every configuration (module-structure.md §7.6).
        // Resolution is validated against the committed gradle.lockfile: any drift from
        // the locked versions FAILS the build. Regenerate after a deliberate dependency
        // change with `./gradlew resolveAndLockAll --write-locks` (DEVELOPMENT.md §6.2).
        // STRICT (not DEFAULT) so that a NEW configuration with no recorded lock state
        // is also a failure — silence is exactly what locking exists to prevent.
        project.dependencyLocking {
            lockAllConfigurations()
            lockMode.set(LockMode.STRICT)
        }

        // The documented "lock all configurations in a single build execution" pattern
        // (docs.gradle.org/current/userguide/dependency_locking.html): the built-in
        // `dependencies` task only covers ONE project, so each module registers this
        // and `./gradlew resolveAndLockAll --write-locks` fans out across all of them.
        project.tasks.register("resolveAndLockAll") {
            group = "verification"
            description = "Resolves every resolvable configuration; run with --write-locks to (re)generate gradle.lockfile."
            notCompatibleWithConfigurationCache("Resolves configurations eagerly at execution time")
            doFirst {
                require(project.gradle.startParameter.isWriteDependencyLocks) {
                    "$path must be run with --write-locks; its only purpose is regenerating gradle.lockfile"
                }
            }
            doLast {
                project.configurations
                    .filter { it.isCanBeResolved }
                    .forEach { it.resolve() }
            }
        }

        registerZeroTestGuard(project)
    }

    /**
     * Fails the build when a module produced fewer test result files than it has test
     * source files.
     *
     * This exists because of an observed false green: `:modules:app:test` reported
     * `NO-SOURCE` — Gradle decided there were no test classes even though
     * `compileTestKotlin` had just run — and the build exited 0 having executed ZERO
     * tests. `failOnNoDiscoveredTests` cannot catch that case: the task is skipped
     * before it ever starts, so nothing is there to fail. It has since caught a real
     * false green in the wild during the 2026-08-08 engine A/B.
     *
     * The check is `results < sources`, deliberately **not** `results != sources`:
     * `@Nested` classes and parameterised suites legitimately emit MORE result files
     * than there are `*Test.kt` files. Fewer results than sources means a class was
     * silently dropped — partial loss, which a plain zero-check would wave through.
     *
     * A green build that silently ran no tests is worse than a red one, and reviewing
     * console output is not a control. This is the mechanical control.
     */
    private fun registerZeroTestGuard(project: Project) {
        // Values are resolved at configuration time: the task action must not reach
        // back into the project (configuration-cache safe).
        val testSourceDir = project.layout.projectDirectory.dir("src/test/kotlin").asFile
        val resultsDir = project.layout.buildDirectory.dir("test-results/test").get().asFile
        val modulePath = project.path

        val guard = project.tasks.register("verifyTestsExecuted") {
            group = "verification"
            description = "Fails if this module produced fewer test result files than it has test sources."
            dependsOn(project.tasks.named("test"))
            doLast {
                val sourceCount = testSourceDir.walkTopDown()
                    .count { it.isFile && it.name.endsWith("Test.kt") }
                if (sourceCount == 0) return@doLast

                val resultCount = resultsDir
                    .listFiles { f -> f.isFile && f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
                    ?.size ?: 0

                // `<` not `!=`: @Nested / parameterised suites legitimately emit MORE
                // result files than source files. Fewer means a class silently vanished.
                if (resultCount < sourceCount) {
                    throw GradleException(
                        "$modulePath produced $resultCount test result file(s) for $sourceCount test " +
                            "source file(s) in $resultsDir. The build would otherwise have reported " +
                            "success while silently skipping tests. Re-run with --rerun-tasks; if it " +
                            "persists, the test task is being skipped as NO-SOURCE, or a test class " +
                            "died without reporting.",
                    )
                }
            }
        }

        project.tasks.named("check").configure { dependsOn(guard) }
        // Also finalize `test` itself, not just `check`. Running `gradlew :m:test`
        // directly — which is what everyone does while iterating — otherwise skips the
        // guard entirely, so the one command most likely to be trusted mid-change was
        // the one command the control did not cover. `finalizedBy` runs the guard even
        // when the test task fails, which is correct: a task that died without reporting
        // is exactly the case it exists to catch.
        project.tasks.named("test").configure { finalizedBy(guard) }
    }

    private companion object {
        const val JDK_VERSION = 21
        const val KTLINT_VERSION = "1.8.0"
    }
}

/** The JUnit Platform's engine service descriptor, one per providing artifact. */
private const val TEST_ENGINE_SERVICE = "META-INF/services/org.junit.platform.engine.TestEngine"

/**
 * Fails a [Test] task whose runtime classpath registers more than one JUnit Platform
 * `TestEngine`.
 *
 * This is the mechanism behind the ONE-TestEngine rule documented on
 * [CommonConventionsPlugin]. That rule previously existed only as a comment plus the
 * absence of one catalog entry — and a comment does not survive someone adding
 * `kotest-runner-junit5` (or `junit-vintage-engine`, or a transitive dependency that
 * drags one in) to a module build file. The A/B that produced the rule measured 2 failed
 * builds in 8 with two engines present, both of them corrupted Gradle test-result state
 * rather than test failures — including one run that reported success having executed
 * ZERO tests. A false green is exactly the failure mode a comment cannot prevent.
 *
 * The check reads the service descriptors actually on the classpath rather than the
 * declared dependency coordinates, so it also catches an engine arriving transitively,
 * which is the case nobody reviews.
 */
private fun assertSingleTestEngine(
    classpath: FileCollection,
    taskPath: String,
) {
    val engines = declaredTestEngines(classpath)
    if (engines.size > 1) {
        throw GradleException(
            buildString {
                append("$taskPath has ${engines.size} JUnit Platform TestEngines on its runtime classpath; ")
                append("exactly one (Jupiter) is allowed.\n")
                engines.forEach { (engine, source) -> append("  - $engine  (from $source)\n") }
                append(
                    "A second engine participates in result reporting while discovering nothing, and has " +
                        "produced truncated Gradle test-result stores here before — a build that reports " +
                        "success having run zero tests. See the CommonConventionsPlugin KDoc. A module that " +
                        "deliberately wants Kotest spec styles gives them their OWN Test task so the two " +
                        "engines never share one task run.",
                )
            },
        )
    }
}

/** Engine implementation class → the classpath entry that registers it. */
private fun declaredTestEngines(classpath: FileCollection): Map<String, String> {
    val found = linkedMapOf<String, String>()

    fun record(
        lines: List<String>,
        source: String,
    ) = lines
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .forEach { found.putIfAbsent(it, source) }

    classpath.files.forEach { entry ->
        when {
            entry.isDirectory ->
                File(entry, TEST_ENGINE_SERVICE)
                    .takeIf { it.isFile }
                    ?.let { record(it.readLines(), entry.name) }

            entry.isFile && entry.name.endsWith(".jar") ->
                ZipFile(entry).use { jar ->
                    jar.getEntry(TEST_ENGINE_SERVICE)?.let { service ->
                        record(jar.getInputStream(service).bufferedReader().readLines(), entry.name)
                    }
                }
        }
    }
    return found
}
