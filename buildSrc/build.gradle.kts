import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask

plugins {
    `kotlin-dsl`
    // buildSrc is linted like every module (#724): the same plugin versions, from the
    // catalog, on buildSrc's OWN build-script classpath. The `implementation` entries below
    // put the same jars on the plugin's compile classpath, so it can apply them to modules;
    // that never lints buildSrc itself.
    alias(libs.plugins.ktlint)
    alias(libs.plugins.detekt)
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("org.jetbrains.kotlin:kotlin-allopen:${libs.versions.kotlin.get()}")
    implementation("org.jlleitschuh.gradle:ktlint-gradle:${libs.versions.ktlint.plugin.get()}")
    implementation("io.gitlab.arturbosch.detekt:detekt-gradle-plugin:${libs.versions.detekt.get()}")
    implementation("org.jetbrains.kotlinx:kover-gradle-plugin:${libs.versions.kover.get()}")
    constraints {
        // GHSA-27j2-h3m2-8237 / CVE-2026-84939 (#223): the Kover report tooling drags
        // freemarker 2.3.32 onto the build classpath; build-time only, never packaged,
        // constrained to the catalog's patched version so the lockfile scan stays honest.
        implementation("org.freemarker:freemarker:${libs.versions.freemarker.get()}") {
            because("GHSA-27j2-h3m2-8237: path traversal fixed in 2.3.35; see gradle/libs.versions.toml")
        }
    }

    // Guard tests (012/F6) — the COVERAGE_FLOORS / -Pkover.off configuration-time
    // behaviour is proven with Gradle TestKit (see
    // src/test/kotlin/CommonConventionsPluginTest.kt). junit-bom is pinned
    // directly to the same line the Spring Boot 3.5.16 BOM manages for the
    // modules (5.12.2): buildSrc cannot inherit that platform without dragging
    // all of it, and the version catalog carries no junit version entry to reuse.
    testImplementation(platform("org.junit:junit-bom:5.12.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // Gradle 9 needs the launcher on the test runtime classpath — same rule
    // CommonConventionsPlugin applies to every module.
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(gradleTestKit())
}

// Resolved JDK 21 installation for the TestKit probes (see tasks.test below).
val probeJdk21 = extensions.getByType(JavaToolchainService::class.java)
    .launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
    .map { it.metadata.installationPath.asFile.absolutePath }

tasks.test {
    useJUnitPlatform()
    // The probe projects the tests drive must see the repo's real version
    // catalog — the plugin resolves catalog aliases at apply time. buildSrc's
    // project dir is <repo>/buildSrc, so ../gradle is the main build's.
    //
    // The catalog's CONTENT is a declared input (013/F4): the tests read the
    // file through the repo.catalog system property, whose value is only a
    // PATH — so before this, editing gradle/libs.versions.toml left :test
    // UP-TO-DATE and the guards unexecuted while still reporting PASS.
    // withPathSensitivity(NONE): only content changes invalidate, not the
    // checkout's absolute location.
    val repoCatalog = rootProject.file("../gradle/libs.versions.toml")
    inputs.file(repoCatalog).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("repo.catalog", repoCatalog.absolutePath)
    // The other repo files these tests open beside the catalog (#718 — the convention's
    // repo-tree input reaches the modules' test tasks, never this one): the Dockerfile
    // (ThirdPartyNoticesTest's GraalJS pin) and .gitignore (RepoTestInputsTest's mirror).
    inputs.files(rootProject.file("../Dockerfile"), rootProject.file("../.gitignore"))
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
        .withPropertyName("repoFilesReadByBuildSrcTests")
    // The module and integration test sources RepoTestInputsTest scans for absent-path assertions
    // (#728): a guard added there must re-run that witness, which sees them only as text.
    inputs.files(
        fileTree(rootProject.file("..")) {
            include("modules/*/src/test/kotlin/**/*.kt", "tests/*/src/test/kotlin/**/*.kt")
        },
    )
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("testSourcesScannedByRepoTestInputsTest")
    // The probes apply the same JDK 21 toolchain the modules get; hand the
    // tests a RESOLVED installation (detection finds the daemon JDK /
    // ~/.gradle/jdks / any auto-provisioned install) so the probe builds need
    // no toolchain repositories of their own and no toolchain DOWNLOAD.
    // (Dependency resolution is a separate matter: the probes resolve their
    // compile/test set from Maven Central — see the test KDoc. 013/F6.)
    // The test JVM itself may be any JDK the daemon picked (26 here), so
    // java.home is NOT a valid substitute.
    systemProperty("probe.jdk21", probeJdk21.get())
}

// The lint CommonConventionsPlugin applies to every module, mirrored for buildSrc (#724),
// which cannot apply its own plugin. The engine version is read from the plugin's
// KTLINT_VERSION constant, so the modules and buildSrc share one source; a missing
// constant fails configuration instead of falling back to the plugin's default engine.
val conventionPluginSource = layout.projectDirectory.file("src/main/kotlin/CommonConventionsPlugin.kt")
val houseKtlintVersion = providers.fileContents(conventionPluginSource).asText.map { text ->
    Regex("""const val KTLINT_VERSION = "([^"]+)"""").find(text)?.groupValues?.get(1)
        ?: throw GradleException("${conventionPluginSource.asFile} declares no KTLINT_VERSION; buildSrc's ktlint reads it (#724)")
}

ktlint {
    version.set(houseKtlintVersion)
    // kotlin-dsl generates accessors under build/; only checked-in sources are linted.
    filter {
        exclude { it.file.path.contains("${layout.buildDirectory.get()}") }
    }
}

// The module-side #689 / #703 boundary, for the same reasons: the pregate's stage 1 runs
// this build's ktlint with --build-cache, and a worker's stored findings are its last
// incremental execution's, not a function of its inputs. The source inventory makes a
// removed file re-lint the whole source set.
tasks.withType<BaseKtLintCheckTask>().configureEach {
    outputs.cacheIf("ktlint worker outputs stay out of the build cache; stored findings reflect incremental history, not only the inputs (#689)") {
        false
    }
    val projectDirectory = layout.projectDirectory.asFile
    inputs.property(
        "ktlintSourceInventory",
        provider { source.files.map { it.relativeTo(projectDirectory).invariantSeparatorsPath }.sorted() },
    )
}

// The repo's detekt config, as the modules get it. No baseline, deliberately: buildSrc's
// findings are fixed, not baselined (#724), so a future config/detekt/baseline.xml does
// not reach this build.
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(rootProject.file("../config/detekt/detekt.yml"))
}

tasks.withType<Detekt>().configureEach {
    jvmTarget = "21"
    reports {
        html.required.set(true)
        xml.required.set(false)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

gradlePlugin {
    plugins {
        register("common-conventions") {
            id = "datapipelines.common-conventions"
            implementationClass = "CommonConventionsPlugin"
        }
    }
}

// Dependency locking for buildSrc itself (module-structure.md §7.6): the
// convention plugin's own toolchain (kotlin-gradle-plugin, ktlint-gradle,
// detekt-gradle-plugin) is part of the build's supply chain, so it is locked
// with the same STRICT mode the plugin applies to every module. buildSrc is a
// separate included build — `lockAllConfigurations()` in the main build does
// NOT reach it, and a lockfile here is written only when buildSrc's own
// configurations resolve under --write-locks (i.e. via resolveAndLockAll below,
// or any main-build invocation with --write-locks, which builds buildSrc first).
dependencyLocking {
    lockAllConfigurations()
    lockMode = LockMode.STRICT
}

// Same documented "lock all configurations in a single build execution" pattern
// the convention plugin registers for modules — see CommonConventionsPlugin.
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
