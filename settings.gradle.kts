// Module list per module-structure.md §3.
// The foojay resolver lets Gradle auto-provision the JDK 21 toolchain (§7.2)
// on machines whose default JDK is something else.

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenCentral()
    }
}

rootProject.name = "datapipelines"

include(
    ":modules:typesystem",
    ":modules:pipeline-contract",
    ":modules:templates",
    ":modules:datasources",
    ":modules:staging",
    ":modules:dag",
    ":modules:auth",
    ":modules:mcp-server",
    ":modules:web",
    ":modules:app",
    ":tests:integration-tests",
    ":tests:browser-tests",
)
