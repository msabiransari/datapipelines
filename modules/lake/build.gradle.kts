// module-structure.md §5.21 — the managed lake (dp-lake design record §3a, call 12). Allowed internal
// deps (root build.gradle.kts): typesystem, persistence, datasources, staging, pipeline-contract,
// scheduler, auth. P2 (#785) compiles against typesystem, persistence and pipeline-contract; P3 (#798) adds
// auth for AuditEventSink. The rest are allowed ahead for P1/P4–P7 and stay undeclared until something
// compiles against them (§4.2's rule).
plugins { id("datapipelines.common-conventions") }

dependencies {
    // DatapipelinesException, the one exception base (§4.3).
    implementation(project(":modules:typesystem"))
    // FailureShape: the SQLState walk that classifies a catalog failure (design record §8b — by the
    // SQLSTATE of the cause chain, never by text) and names a store failure in a log line.
    implementation(project(":modules:persistence"))
    // PipelineErrorCodes.Lake: the catalogued fence and definition codes (pipeline-contract §13.25); AuthoringGuard,
    // WriteSurface and PipelineVersionStatus for the definition lifecycle (P3, #798).
    implementation(project(":modules:pipeline-contract"))
    // AuditEventSink: the definitions' lake.definition.* audit events, strict inside the applied transaction (P3, #798).
    implementation(project(":modules:auth"))

    // Jackson stays at the product's pin: Iceberg 1.12.0's POMs import jackson-bom 2.22.2, which wins
    // over the conventions' platform(jackson-bom 2.21.7) under default resolution (E1 #774 / E2).
    // enforcedPlatform, not eachDependency/useVersion: jackson-annotations has its own version line, so
    // forcing every com.fasterxml.jackson module to 2.21.7 does not resolve (E2, observed). THIS module only.
    implementation(enforcedPlatform(libs.jackson.bom))
    testImplementation(enforcedPlatform(libs.jackson.bom))

    // The lease protocol, the catalog-shape reads and the boot check run on DP's metadata DataSource.
    implementation(libs.spring.boot.starter.jdbc)
    implementation(libs.slf4j.api)

    // The catalog and the table format (design record §3, §5.1). JdbcCatalog ships in iceberg-core.
    implementation(libs.iceberg.api)
    implementation(libs.iceberg.core)
    implementation(libs.iceberg.aws)
    // javax.annotation-api (CDDL-1.1 / GPL-2.0 + CPE) arrives through parquet-format-structures for
    // thrift's @Generated, which has SOURCE retention: no class on this runtime classpath references it
    // (#785, scanned), and on the classpath it would switch on Spring's javax.annotation processing.
    implementation(libs.iceberg.parquet) { exclude(group = "javax.annotation", module = "javax.annotation-api") }
    // P4 uses GenericFileWriterFactory for the partitioned Parquet writer. Its format registry lives
    // in iceberg-data; the version is the existing Iceberg pin. Retain the annotation exclusion above.
    implementation(libs.iceberg.data) { exclude(group = "javax.annotation", module = "javax.annotation-api") }
    // Iceberg's catalog connections are opened by the fenced pool through DriverManager (§8a).
    implementation(libs.postgresql)
    // The S3 client: S3FileIO's own (iceberg-aws declares the SDK compileOnly) and the boot check's
    // HEAD + conditional PUT. url-connection-client keeps the Netty stack off the classpath.
    // The SDK's other HTTP stacks are excluded: their service modules declare netty-nio-client and
    // apache5-client at runtime, and with more than one sync client on the classpath the SDK's discovery
    // refuses to pick one. url-connection-client is the only one, so every client built here uses it.
    implementation(libs.awssdk.s3) { excludeOtherSdkHttpClients() }
    implementation(libs.awssdk.url.connection.client)
    // [upstream: apache/iceberg#18744 — TEMPORARY] S3FileIO.initialize needs kms + sts (E11 #777 F3).
    runtimeOnly(libs.awssdk.kms) { excludeOtherSdkHttpClients() }
    runtimeOnly(libs.awssdk.sts) { excludeOtherSdkHttpClients() }
    // REQUIRED at runtime: every Parquet write builds a Hadoop Configuration (E1 #774 F16).
    runtimeOnly(libs.hadoop.client.api)
    runtimeOnly(libs.hadoop.client.runtime)
    // GenericParquetWriter's signature names parquet's MessageType; runtime brings it via iceberg-parquet.
    compileOnly(libs.parquet.column)
    testCompileOnly(libs.parquet.column)
    // Inspect the actual file footer for zstd and row groups, at the existing runtime Parquet cohort.
    testCompileOnly(libs.parquet.format.structures) { exclude(group = "javax.annotation", module = "javax.annotation-api") }

    // The catalog suites run against a real Postgres with the shipped migrations applied through plain
    // JDBC (§7.4; Flyway stays in `app`), and a MinIO container for S3FileIO and the boot check.
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    // #756 — the shared snapshot log capture (module-structure §5.23); test-only, testImplementation alone.
    testImplementation(project(":modules:log-capture"))
    // Actual materialized input cursors exercise unsigned widths and microsecond temporal values.
    testRuntimeOnly(libs.duckdb.jdbc)
}

/** The AWS SDK HTTP clients this module does not use (see the awssdk-s3 dependency above). */
fun ModuleDependency.excludeOtherSdkHttpClients() {
    listOf("netty-nio-client", "apache-client", "apache5-client", "aws-crt-client").forEach {
        exclude(group = "software.amazon.awssdk", module = it)
    }
}
