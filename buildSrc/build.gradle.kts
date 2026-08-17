import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.jvm.toolchain.JavaLanguageVersion
import org.gradle.jvm.toolchain.JavaToolchainService

plugins {
    `kotlin-dsl`
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
    systemProperty("repo.catalog", rootProject.file("../gradle/libs.versions.toml").absolutePath)
    // The probes apply the same JDK 21 toolchain the modules get; hand the
    // tests a RESOLVED installation (detection finds the daemon JDK /
    // ~/.gradle/jdks / any auto-provisioned install) so the probe builds need
    // no toolchain repositories of their own — the tests stay network-free.
    // The test JVM itself may be any JDK the daemon picked (26 here), so
    // java.home is NOT a valid substitute.
    systemProperty("probe.jdk21", probeJdk21.get())
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
