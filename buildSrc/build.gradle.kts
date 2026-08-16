import org.gradle.api.artifacts.dsl.LockMode

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
