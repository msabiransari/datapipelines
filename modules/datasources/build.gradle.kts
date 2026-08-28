// module-structure.md §5.4 — allowed internal deps: typesystem.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem")) // IngressTypeMapper per dialect

    implementation(libs.hikaricp)
    implementation(libs.spring.boot.starter.jdbc) // DatasourceRepository (§8.1)
    implementation(libs.jackson.module.kotlin) // properties_json (de)serialization (§4.10)
    // Bootstrap datasources file (§8A). Same Jackson stack, YAML backend — already on the
    // runtime classpath via spring-boot-starter; declared here because this module now
    // COMPILES against YAMLFactory (module-structure §4.2: no reliance on transitive leakage).
    implementation(libs.jackson.dataformat.yaml)
    // No BouncyCastle (removed 2026-08-07, security review MEDIUM-6 — see §5.4).
    // CredentialEncryptor uses the JDK's SunJCE `AES/GCM/NoPadding` directly.

    // Bundled drivers. Nothing compiles against them — JdbcDrivers resolves them
    // reflectively by class name (datasources.md §10.3).
    runtimeOnly(libs.postgresql)
    runtimeOnly(libs.mssql.jdbc)
    runtimeOnly(libs.h2)
    runtimeOnly(libs.duckdb.jdbc)
    runtimeOnly(libs.sqlite.jdbc)

    // §5.4.1 — gated on Gradle project PROPERTIES (Gradle has no profiles).
    // Presence is the switch, not value: `-Poracle=false` still enables it.
    // The flag must be passed to every task that builds the artifact:
    //   ./gradlew -Poracle bootJar   (not `-Poracle build` then a bare bootJar)
    if (project.hasProperty("oracle")) {
        runtimeOnly(libs.ojdbc11)          // OTN-licensed; operator accepts the licence
    }
    if (project.hasProperty("mysql")) {
        runtimeOnly(libs.mysql.connector.j) // GPL + FOSS exception
    }

    // Integration tests via Testcontainers (§13.2): real PG / MySQL / MSSQL containers exercise
    // each dialect's adapter, encryption round-trip and type mapping. The bundled drivers
    // (postgresql, mssql-jdbc, h2, …) are already on the test runtime via `runtimeOnly`; the
    // MySQL driver is not bundled by default, so it is added for the test runtime explicitly.
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testcontainers.mysql)
    testImplementation(libs.testcontainers.mssqlserver)
    testImplementation(libs.postgresql)
    // The SQLite connection-loss tests construct org.sqlite.SQLiteException by type (the
    // production classifier is name-based precisely because main never compiles against a
    // driver); the test runtime already carries the jar via `runtimeOnly` above.
    testImplementation(libs.sqlite.jdbc)
    // Same rule for h2: the R5 F1 tests construct JdbcSQLNonTransientException by type to
    // reproduce the exact closed-connection shape (state 90007) the classifier matches
    // name-based; main never compiles against the driver.
    testImplementation(libs.h2)
    testRuntimeOnly(libs.mysql.connector.j)
}

// Dependency-locking exclusion for the §5.4.1 flag-gated drivers above
// (docs.gradle.org dependency_locking, "Ignoring specific dependencies from the
// lock state"). These artifacts exist on the runtime classpath only under
// -Poracle / -Pmysql, but ONE committed gradle.lockfile must validate BOTH flag
// states: with the driver locked, the default build fails on an unmatched lock
// entry; without it, the flagged build fails on an unlocked extra module. No
// single lockfile can satisfy both, so these two are the build's only ignored
// dependencies. Side effect: mysql-connector-j on the always-present TEST
// runtime classpath is also unvalidated — accepted; its version is still
// BOM-pinned, and every other artifact in every configuration stays locked.
dependencyLocking {
    ignoredDependencies.add("com.oracle.database.jdbc:ojdbc11")
    ignoredDependencies.add("com.mysql:mysql-connector-j")
}
