package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeMinio
import co.datapipelines.lake.LakeProperties
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import software.amazon.awssdk.core.sync.RequestBody
import java.net.URI
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.sql.SQLTimeoutException
import java.time.Instant
import java.util.Properties
import java.util.UUID

/** Real bound-list reads against MinIO, followed by the same author configuration lock as staging. */
class DeliveryMaterializerIntegrationTest {
    @ParameterizedTest
    @ValueSource(
        strings = ["ordinary.csv", "quote'--.csv", "space name.csv", "plus+.csv", "hash#.csv", "query?.csv", "star*.csv", "bracket[1].csv"],
    )
    @Suppress("NestedBlockDepth") // Keep the real S3 client, connection and both cursors' ownership visible.
    fun `only listed objects become author tables and manifest fields remain exact`(filename: String) {
        val storage = LakeMinio.storage(BUCKET)
        val key = DeliveryKey("material799", UUID.randomUUID().toString())
        val dataKey = key.prefix(storage.landingPrefix) + filename
        LakeMinio.client().use { client ->
            client.putObject({ it.bucket(BUCKET).key(dataKey) }, RequestBody.fromString("id,label\n1,listed\n2,listed\n"))
            listOf("starX.csv", "bracket1.csv", "queryX.csv").forEachIndexed { index, name ->
                client.putObject(
                    { it.bucket(BUCKET).key(key.prefix(storage.landingPrefix) + name) },
                    RequestBody.fromString("id,label\n${91 + index},also_listed\n"),
                )
            }
            client.putObject({ it.bucket(BUCKET).key(key.prefix(storage.landingPrefix) + "_done") }, RequestBody.empty())
            client.putObject({ it.bucket(BUCKET).key("decoy799/unlisted.csv") }, RequestBody.fromString("id,label\n99,decoy\n"))
            val manifest = TrustedDeliveryListing(client, storage, MAX_BYTES).list(key, deadline())
            open().use { connection ->
                configureStorage(connection, storage)
                DeliveryMaterializer(BUCKET).materialize(connection, manifest, deadline())
                lock(connection)
                connection.createStatement().use { statement ->
                    statement.executeQuery("SELECT sum(id), count(*) FROM delivery").use { rows ->
                        rows.next() shouldBe true
                        rows.getLong(1) shouldBe 279
                        rows.getLong(2) shouldBe 5
                    }
                    statement.executeQuery("SELECT key, size, etag FROM delivery_files").use { rows ->
                        val actual =
                            buildList {
                                while (rows.next()) add(DeliveryObject(rows.getString(1), rows.getLong(2), rows.getString(3)))
                            }
                        actual.sortedBy { it.key } shouldBe manifest.files
                    }
                    shouldThrow<SQLException> { statement.executeQuery("SELECT * FROM read_csv('s3://$BUCKET/decoy799/unlisted.csv')") }
                    shouldThrow<SQLException> { statement.execute("SET enable_external_access = true") }
                }
            }
        }
    }

    @Test
    fun `parquet is materialized before the author connection is locked`() {
        val storage = LakeMinio.storage(BUCKET)
        val key = DeliveryKey("material799", UUID.randomUUID().toString())
        val dataKey = key.prefix(storage.landingPrefix) + "input.parquet"
        open().use { connection ->
            configureStorage(connection, storage)
            connection.createStatement().use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                // Fixture path is built only from the validated key and constant bucket/suffix.
                statement.execute("COPY (SELECT 42::BIGINT AS id, 'parquet' AS label) TO 's3://$BUCKET/$dataKey' (FORMAT PARQUET)")
            }
            LakeMinio.client().use { client ->
                client.putObject({ it.bucket(BUCKET).key(key.prefix(storage.landingPrefix) + "_done") }, RequestBody.empty())
                val manifest = TrustedDeliveryListing(client, storage, MAX_BYTES).list(key, deadline())
                DeliveryMaterializer(BUCKET).materialize(connection, manifest, deadline())
            }
            lock(connection)
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT id, label FROM delivery").use { rows ->
                    rows.next() shouldBe true
                    rows.getLong(1) shouldBe 42
                    rows.getString(2) shouldBe "parquet"
                    rows.next() shouldBe false
                }
            }
        }
    }

    @Test
    fun `a passed deadline creates no delivery table`() {
        open().use { connection ->
            val manifest =
                DeliveryManifest(
                    DeliveryKey("material799", "expired"),
                    emptyList(),
                    listOf(DeliveryObject("unused.csv", 1, "etag")),
                    DeliveryFormat.Csv,
                    "unused",
                    1,
                )
            shouldThrow<SQLTimeoutException> { DeliveryMaterializer(BUCKET).materialize(connection, manifest, Instant.EPOCH) }
            connection.createStatement().use { statement ->
                statement.executeQuery("SELECT count(*) FROM information_schema.tables WHERE table_name = 'delivery'").use { rows ->
                    rows.next() shouldBe true
                    rows.getLong(1) shouldBe 0
                }
            }
        }
    }

    private fun open(): Connection =
        DriverManager
            .getConnection(
                "jdbc:duckdb:",
                Properties().apply { setProperty("allow_persistent_secrets", "false") },
            ).also { connection ->
                connection.createStatement().use { statement ->
                    statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                    // CSV readers and the S3 fixture uploader reserve native buffers even for tiny objects.
                    statement.execute("SET memory_limit = '256MB'")
                    statement.execute("SET threads = 1")
                    statement.execute("SET autoinstall_known_extensions = false")
                    statement.execute("SET autoload_known_extensions = false")
                }
            }

    @Suppress("SwallowedException") // The original driver exception can contain the ephemeral test secret.
    private fun configureStorage(
        connection: Connection,
        storage: LakeProperties.Storage,
    ) {
        try {
            connection.createStatement().use { statement ->
                statement.queryTimeout = QUERY_TIMEOUT_SECONDS
                statement.execute("INSTALL httpfs FROM core")
                statement.execute("LOAD httpfs")
                statement.execute(
                    "CREATE SECRET material799 (TYPE s3, KEY_ID '${storage.accessKey}', SECRET '${storage.secretKey}', " +
                        "REGION 'us-east-1', ENDPOINT '${URI.create(storage.endpoint).authority}', URL_STYLE 'path', USE_SSL false)",
                )
            }
        } catch (failure: SQLException) {
            // A driver setup failure can echo the secret SQL. Never retain its message/cause in test output.
            throw AssertionError("Could not initialize test S3 storage; SQLSTATE=${failure.sqlState}")
        }
    }

    private fun lock(connection: Connection) {
        connection.createStatement().use { statement ->
            statement.execute("SET enable_external_access = false")
            statement.execute("SET lock_configuration = true")
        }
    }

    private fun deadline(): Instant = Instant.now().plusSeconds(QUERY_TIMEOUT_SECONDS.toLong())

    private companion object {
        const val BUCKET = "dp-lake-material799"
        const val MAX_BYTES = 1024L
        const val QUERY_TIMEOUT_SECONDS = 30
    }
}
