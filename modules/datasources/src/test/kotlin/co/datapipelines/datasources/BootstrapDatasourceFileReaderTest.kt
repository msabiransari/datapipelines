package co.datapipelines.datasources

import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * The bootstrap datasources file, parsed (datasources.md §8A; sample-data design §6).
 *
 * Pure — no database, no pool — because every rule here is a **parse-time** refusal and each one
 * exists to stop a specific silent failure: a placeholder that never resolved becoming a literal
 * password, a typo'd key becoming a null column, a file written for a later version being read as
 * global when it did not say so.
 */
class BootstrapDatasourceFileReaderTest {
    @TempDir
    lateinit var tempDir: Path

    private fun file(yaml: String): Path = tempDir.resolve("bootstrap-datasources.yml").also { it.writeText(yaml) }

    private fun reader(env: Map<String, String> = emptyMap()) = BootstrapDatasourceFileReader(environment = env::get)

    private val twoEntries =
        """
        datasources:
          - name: sample-trips
            display_name: "NYC Taxi Trips (sample)"
            description: Yellow-taxi trips
            dialect: POSTGRES
            jdbc_url: jdbc:postgresql://postgres:5432/dp_sample_trips
            username: dp_demo_ro
            password: ${'$'}{SAMPLE_PG_PASSWORD}
            query_timeout_seconds: 45
            properties:
              hikari:
                maximumPoolSize: 4
              jdbc:
                ssl: "true"
            readonly: true
            global: true
          - name: sample-reference
            dialect: SQLITE
            jdbc_url: jdbc:sqlite:/srv/sample/nyc_reference.db
            username: ""
            password: ""
            properties:
              jdbc:
                # Verified against the pinned xerial driver: open_mode=1 is SQLiteOpenMode.READONLY.
                open_mode: "1"
            readonly: true
            global: true
        """.trimIndent()

    @Test
    fun `the POST datasources vocabulary plus readonly and global maps onto the entity`() {
        val entries = reader(mapOf("SAMPLE_PG_PASSWORD" to "s3cr3t")).read(file(twoEntries))

        entries shouldHaveSize 2
        val trips = entries.first().datasource
        trips.name shouldBe "sample-trips"
        trips.displayName shouldBe "NYC Taxi Trips (sample)"
        trips.description shouldBe "Yellow-taxi trips"
        trips.dialect shouldBe Dialect.POSTGRES
        trips.jdbcUrl shouldBe "jdbc:postgresql://postgres:5432/dp_sample_trips"
        trips.username shouldBe "dp_demo_ro"
        trips.password shouldBe "s3cr3t"
        trips.queryTimeoutSeconds shouldBe 45
        trips.isReadonly shouldBe true
        trips.properties.hikari shouldBe mapOf("maximumPoolSize" to 4)
        trips.properties.jdbc shouldBe mapOf("ssl" to "true")
        trips.properties.unknownNamespaces shouldBe emptySet()

        val reference = entries[1].datasource
        reference.dialect shouldBe Dialect.SQLITE
        // display_name is optional and defaults to name — the same rule the REST bind uses.
        reference.displayName shouldBe "sample-reference"
        reference.properties.jdbc shouldBe mapOf("open_mode" to "1")
    }

    /**
     * 061/T84: the reader keeps the `${'$'}{VAR}` NAME each entry's password referenced, because
     * resolution destroys it and §8A.3 rule 3's ERROR line has to tell an operator which
     * variable to change. A literal password — the SQLite sample entry, which authenticates
     * against nothing — has no env key to name.
     */
    @Test
    fun `each entry keeps the env key its password referenced, positionally, and null for a literal`() {
        val yaml =
            """
            datasources:
              - name: from-env
                dialect: H2
                jdbc_url: jdbc:h2:mem:from_env
                username: sa
                password: ${'$'}{SAMPLE_PG_PASSWORD}
                global: true
              - name: literal
                dialect: SQLITE
                jdbc_url: jdbc:sqlite:/srv/sample/ref.db
                username: sqlite
                password: sqlite-file-datasource-has-no-authentication
                global: true
            """.trimIndent()

        val entries = reader(mapOf("SAMPLE_PG_PASSWORD" to "s3cr3t")).read(file(yaml))

        entries.map { it.datasource.name to it.passwordEnvKey } shouldBe
            listOf("from-env" to "SAMPLE_PG_PASSWORD", "literal" to null)
        // The entity still carries the RESOLVED value — keeping the key changes nothing else.
        entries.first().datasource.password shouldBe "s3cr3t"
    }

    @Test
    fun `placeholders resolve anywhere in the tree, including inside properties jdbc`() {
        val yaml =
            """
            datasources:
              - name: pg
                dialect: POSTGRES
                jdbc_url: jdbc:postgresql://${'$'}{PG_HOST}:5432/db
                username: ${'$'}{PG_USER}
                password: ${'$'}{PG_PASSWORD}
                properties:
                  jdbc:
                    sslpassword: ${'$'}{PG_SSL_PASSPHRASE}
                readonly: false
                global: true
            """.trimIndent()

        val entry =
            reader(
                mapOf(
                    "PG_HOST" to "pg.internal",
                    "PG_USER" to "app",
                    "PG_PASSWORD" to "hunter2",
                    "PG_SSL_PASSPHRASE" to "keypass",
                ),
            ).read(file(yaml)).single().datasource

        entry.jdbcUrl shouldBe "jdbc:postgresql://pg.internal:5432/db"
        entry.username shouldBe "app"
        entry.password shouldBe "hunter2"
        entry.properties.jdbc shouldBe mapOf("sslpassword" to "keypass")
        entry.isReadonly shouldBe false
    }

    @Test
    fun `an unresolved placeholder fails fast naming the variable - never a literal dollar-brace password`() {
        val yaml =
            """
            datasources:
              - name: pg
                dialect: POSTGRES
                jdbc_url: jdbc:postgresql://pg:5432/db
                username: app
                password: ${'$'}{SAMPLE_PG_PASSWORD}
                readonly: true
                global: true
            """.trimIndent()

        // The environment is EMPTY — the exact shape of a compose file that forgot the variable.
        val error = shouldThrow<BootstrapDatasourceFileException> { reader().read(file(yaml)) }

        error.message!!.shouldContain("SAMPLE_PG_PASSWORD")
        error.message!!.shouldContain("not set in this process's environment")
        // The failure is the whole point: nothing was returned, so nothing could be registered
        // with the literal placeholder as its credential.
        error.message!!.shouldNotContain("registered")
    }

    @Test
    fun `global false and a missing global are both refused, each saying why`() {
        fun yamlWith(globalLine: String) =
            """
            datasources:
              - name: pg
                dialect: POSTGRES
                jdbc_url: jdbc:postgresql://pg:5432/db
                username: app
                password: pw
                readonly: true
            $globalLine
            """.trimIndent()

        val explicitFalse = shouldThrow<BootstrapDatasourceFileException> { reader().read(file(yamlWith("    global: false"))) }
        explicitFalse.message!!.shouldContain("'global: false'")
        explicitFalse.message!!.shouldContain("not supported in v1")

        val missing = shouldThrow<BootstrapDatasourceFileException> { reader().read(file(yamlWith(""))) }
        missing.message!!.shouldContain("does not declare 'global'")
    }

    @Test
    fun `an unknown dialect names the supported set`() {
        val yaml =
            """
            datasources:
              - name: warehouse
                dialect: SNOWFLAKE
                jdbc_url: jdbc:snowflake://acme/db
                username: app
                password: pw
                global: true
            """.trimIndent()

        val error = shouldThrow<BootstrapDatasourceFileException> { reader().read(file(yaml)) }

        error.message!!.shouldContain("SNOWFLAKE")
        error.message!!.shouldContain("POSTGRES")
    }

    @Test
    fun `a mistyped key is refused rather than bound to null`() {
        // `jdbc_ur` is the failure this rule exists for: silently ignored, it would register a
        // datasource with no URL and fail at first query.
        val yaml =
            """
            datasources:
              - name: pg
                dialect: POSTGRES
                jdbc_ur: jdbc:postgresql://pg:5432/db
                jdbc_url: jdbc:postgresql://pg:5432/db
                username: app
                password: pw
                global: true
            """.trimIndent()

        val error = shouldThrow<BootstrapDatasourceFileException> { reader().read(file(yaml)) }

        error.message!!.shouldContain("malformed")
        error.message!!.shouldContain("jdbc_ur")
    }

    @Test
    fun `a required field left out is refused`() {
        val yaml =
            """
            datasources:
              - name: pg
                dialect: POSTGRES
                username: app
                password: pw
                global: true
            """.trimIndent()

        shouldThrow<BootstrapDatasourceFileException> { reader().read(file(yaml)) }
    }

    @Test
    fun `an unreadable file, unparseable YAML and an empty entry list each fail fast`() {
        val missing = shouldThrow<BootstrapDatasourceFileException> { reader().read(tempDir.resolve("nope.yml")) }
        missing.message!!.shouldContain("could not be read")

        val garbage = shouldThrow<BootstrapDatasourceFileException> { reader().read(file("datasources: [oops\n  - :::")) }
        garbage.message!!.shouldContain("not valid YAML")

        val none = shouldThrow<BootstrapDatasourceFileException> { reader().read(file("datasources: []")) }
        none.message!!.shouldContain("declares no datasources")
    }
}
