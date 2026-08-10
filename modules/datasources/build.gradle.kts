// module-structure.md §5.4 — allowed internal deps: typesystem.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem")) // IngressTypeMapper per dialect

    implementation(libs.hikaricp)
    implementation(libs.spring.boot.starter.jdbc) // DatasourceRepository (§8.1)
    implementation(libs.jackson.module.kotlin) // properties_json (de)serialization (§4.10)
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
    testRuntimeOnly(libs.mysql.connector.j)
}
