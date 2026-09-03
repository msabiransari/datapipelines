import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

// module-structure.md §5.10 — allowed internal deps: web only (§4.2).
// Contains main(), configuration, logback config and the Flyway migrations.
// No domain code.
plugins {
    id("datapipelines.common-conventions")
    alias(libs.plugins.spring.boot)
}

dependencies {
    implementation(project(":modules:web"))

    implementation(libs.spring.boot.starter)
    implementation(libs.spring.boot.starter.actuator)
    // Authorized by module-structure.md §5.10: serves the root-level /health,
    // /ready and /info probes (rest-api.md §11, observability.md §6).
    implementation(libs.spring.boot.starter.web)
    // spring-jdbc + spring-tx: `app` owns the metadata `DataSource` (§3.1 rule 4) and, since
    // 056, declares the ONE transaction manager over it (`TransactionConfiguration`), so
    // `DataSourceTransactionManager` and `@EnableTransactionManagement` are COMPILE-time types
    // here. Both already arrived transitively through `web`; declaring the starter is §4.2's
    // "everything used at compile time is listed", and no new artifact enters the build.
    implementation(libs.spring.boot.starter.jdbc)

    // Flyway: this module is the ONLY one that may depend on it (§3.1 rule 2).
    // Nothing compiles against it — Spring Boot autoconfigures the migration on
    // startup — so both artifacts are runtimeOnly. Since Flyway 10 the Postgres
    // support is a separate artifact; flyway-core alone cannot migrate Postgres.
    runtimeOnly(libs.flyway.core)
    runtimeOnly(libs.flyway.database.postgresql)

    // Metadata-DB driver at runtime.
    runtimeOnly(libs.postgresql)

    testImplementation(libs.spring.boot.starter.test)
    // The @SpringBootTest smoke test brings its own Postgres and Redis so it is
    // self-contained — it must not depend on deploy/docker-compose.dev.yml being up.
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.postgresql)
    // jackson-module-kotlin is NOT pulled by spring-boot-starter-json; the
    // serialization test registers it explicitly to assert the app's real behaviour.
    testImplementation(libs.jackson.module.kotlin)
}

// Generates META-INF/build-info.properties → the BuildProperties bean that backs
// the `version` field of /health (rest-api.md §11.1) and all of /info
// (observability.md §6.3: version, commit hash, build timestamp).
springBoot {
    buildInfo {
        properties {
            // Commit hash is OPT-IN via a Gradle property, never read from git by
            // the build itself: shelling out to `git rev-parse` would break builds
            // from a source tarball (no .git), fight the configuration cache, and
            // make every build non-reproducible. CI supplies it explicitly:
            //     ./gradlew -Pdatapipelines.commit=$GITHUB_SHA :modules:app:bootJar
            // When absent, /info omits the field rather than reporting a fake one.
            val commit = providers.gradleProperty("datapipelines.commit").orNull
            if (commit != null) {
                additional.put("commit", commit)
            }
        }
    }
}

// §5.4.1 — `lib/` driver drop-in with no rebuild.
tasks.named<BootJar>("bootJar") {
    archiveFileName.set("datapipelines-app.jar")
    // PropertiesLauncher is the only launcher that honours loader.path.
    // At runtime: java -Dloader.path=lib -jar datapipelines-app.jar
    //         or: LOADER_PATH=lib (the deployment image sets this).
    manifest {
        attributes("Main-Class" to "org.springframework.boot.loader.launch.PropertiesLauncher")
    }
}

tasks.named<BootRun>("bootRun") {
    // Dev parity: same drop-in directory, no packaging.
    classpath += files("lib")
}

// -Pmysql / -Poracle add flag-gated drivers to the runtime classpath (datasources
// §4.1/§10.2), and the ONE committed gradle.lockfile must validate both flag states —
// so the drivers are excluded from lock state exactly as modules/datasources already
// does (its comment is the authority). Without this block, `-Pmysql :modules:app:bootJar`
// failed STRICT lock validation: the ignore is per-project and this module resolves the
// driver transitively. Found by 023's demo build (T-mysql-lock), fixed 2026-08-29.
dependencyLocking {
    ignoredDependencies.add("com.oracle.database.jdbc:ojdbc11")
    ignoredDependencies.add("com.mysql:mysql-connector-j")
}
