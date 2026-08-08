// module-structure.md §5.4 — allowed internal deps: typesystem.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem")) // IngressTypeMapper per dialect

    implementation(libs.hikaricp)
    implementation(libs.spring.boot.starter.jdbc) // DatasourceRepository (§8.1)
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
}
