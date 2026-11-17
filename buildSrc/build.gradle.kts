import io.gitlab.arturbosch.detekt.Detekt
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService
import org.jlleitschuh.gradle.ktlint.tasks.BaseKtLintCheckTask
import com.sun.security.auth.module.UnixSystem
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributes
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest

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
    // #1004: a lane check runs `:buildSrc:cleanTest :buildSrc:test` with --build-cache, and a second
    // check on an unchanged tree was served FROM-CACHE: the classes reported a result without
    // executing. A guard's verdict is its execution, so this task's results never come from the cache.
    outputs.cacheIf("buildSrc's tests are guards: a lane check must execute them, never restore their results (#1004)") {
        false
    }
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
    // #742: every TestKit probe root gets a copy of the repo's dependency checksums (ProbeVerification.kt),
    // so a checksum change re-runs the probes that verify against them.
    val repoVerification = rootProject.file("../gradle/verification-metadata.xml")
    inputs.file(repoVerification).withPathSensitivity(PathSensitivity.NONE)
    systemProperty("repo.verification", repoVerification.absolutePath)
    // The other repo files these tests open beside the catalog (#718 — the convention's
    // repo-tree input reaches the modules' test tasks, never this one): the Dockerfile
    // (ThirdPartyNoticesTest's GraalJS pin) and .gitignore (RepoTestInputsTest's mirror).
    inputs.files(
        rootProject.file("../Dockerfile"),
        rootProject.file("../.gitignore"),
        rootProject.file("../deploy/duckdb-extensions.sha256"),
        // #747: the extension closure files the notices test parses.
        rootProject.file("../deploy/duckdb-extension-ports"),
    )
        .withPathSensitivity(PathSensitivity.NAME_ONLY)
        .withPropertyName("repoFilesReadByBuildSrcTests")
    // The module and integration test sources RepoTestInputsTest scans for absent-path assertions
    // (#728): a guard added there must re-run that witness, which sees them only as text. The same
    // tree feeds RawListAppenderGuardTest, which since #756 also scans the shared log-capture module's
    // MAIN sources: without that include a raw appender added there would leave the guard UP-TO-DATE.
    // Its duplicate-copy check walks every src tree: matching names there must also invalidate this task.
    inputs.files(
        fileTree(rootProject.file("..")) {
            include(
                "modules/*/src/test/kotlin/**/*.kt",
                "tests/*/src/test/kotlin/**/*.kt",
                "modules/log-capture/src/main/kotlin/**/*.kt",
                "modules/*/src/**/SnapshotListAppender.kt",
                "modules/*/src/**/SnapshotListAppenderTest.kt",
                "tests/*/src/**/SnapshotListAppender.kt",
                "tests/*/src/**/SnapshotListAppenderTest.kt",
            )
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
    // #983 (DEVELOPMENT.md §9.7): buildSrc's own test JVM gets its java.io.tmpdir under this
    // checkout's test temp root, by the rule TestTempRoot (CommonConventionsPlugin.kt) states and
    // scripts/lib/temp-hygiene.sh follows; repeated here because buildSrc cannot apply its own
    // plugin. CommonConventionsPluginTest pins this copy to the plugin's. The TestKit probes are
    // handed the checkout they belong to (DP_TEST_TMP_OWNER_CHECKOUT, TestTempRoot.OWNER_ENV), so a
    // probe's test JVMs land under this root too and never under one derived from the probe's
    // own directory. Strings only, resolved at execution: no task input, as for the modules.
    val testTempCheckout = rootProject.projectDir.parentFile.toPath().toRealPath().toString()
    val testTempTmpdir = providers.environmentVariable("TMPDIR")
    environment("DP_TEST_TMP_OWNER_CHECKOUT", testTempCheckout)
    val testTempDirectory =
        object : CommandLineArgumentProvider {
            fun base(): Path {
                val named = testTempTmpdir.orNull?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                val chosen =
                    if (named != null && named.isAbsolute && Files.isDirectory(named)) named else Path.of("/tmp")
                return chosen.toRealPath()
            }

            fun uid(): Long = UnixSystem().uid

            fun parent(): Path = base().resolve("dp-test-tmp-" + uid())

            fun key(): String =
                MessageDigest.getInstance("SHA-256")
                    .digest(testTempCheckout.toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                    .take(16)

            fun directory(): Path = parent().resolve(key()).resolve("buildSrc").resolve("test")

            // The plugin's Security check: created 0700, a real directory owned by this uid,
            // not writable by group or others — else the task fails; never the shared /tmp.
            fun ensurePrivate(directory: Path) {
                val private = PosixFilePermissions.asFileAttribute(
                    PosixFilePermissions.fromString("rwx------"),
                )
                try {
                    Files.createDirectory(directory, private)
                } catch (_: FileAlreadyExistsException) {
                    // verified below
                }
                val nofollow = LinkOption.NOFOLLOW_LINKS
                val attributes = Files.readAttributes(
                    directory,
                    PosixFileAttributes::class.java,
                    nofollow,
                )
                val owner = (Files.getAttribute(directory, "unix:uid", nofollow) as Number).toLong()
                val shared = attributes.permissions().any {
                    it == PosixFilePermission.GROUP_WRITE ||
                        it == PosixFilePermission.OTHERS_WRITE
                }
                if (attributes.isSymbolicLink || !attributes.isDirectory || owner != uid() || shared) {
                    throw GradleException(
                        "Refusing the test temp directory $directory: it must be a real directory owned by uid ${uid()} " +
                            "and not writable by group or others (#983, DEVELOPMENT.md §9.7); remove or repair it by hand.",
                    )
                }
            }

            fun prepare() {
                ensurePrivate(parent())
                ensurePrivate(parent().resolve(key()))
                val record = parent().resolve(key() + ".checkout")
                val content = testTempCheckout + "\n"
                val nofollow = LinkOption.NOFOLLOW_LINKS
                if (!Files.isRegularFile(record, nofollow) || Files.readString(record) != content) {
                    val staged = Files.createTempFile(parent(), key() + ".checkout.", ".tmp")
                    try {
                        Files.writeString(staged, content)
                        Files.move(
                            staged,
                            record,
                            StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING,
                        )
                    } finally {
                        Files.deleteIfExists(staged)
                    }
                }
                Files.createDirectories(directory())
            }

            override fun asArguments(): Iterable<String> = listOf("-Djava.io.tmpdir=" + directory())
        }
    jvmArgumentProviders.add(testTempDirectory)
    doFirst { testTempDirectory.prepare() }
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
