// buildSrc reuses the root version catalog so plugin versions are pinned in
// exactly one place (module-structure.md §6, principle 4).
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "buildSrc"
