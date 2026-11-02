package co.datapipelines.lake

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.datasource.SingleConnectionDataSource

/**
 * The boot check (#785 A6) against the migrated database and MinIO. Green twice (the second boot finds the
 * marker and both PUTs answer 412); each refusal staged for real: a dropped, disabled or re-pointed trigger, the
 * five-column catalog shape, a missing control row (each inside a transaction on ONE connection that the check
 * reads through, rolled back after — the shared database is never left weakened), a missing bucket, a refused
 * credential and an unreachable endpoint. No refusal carries the secret.
 */
class LakeBootCheckIntegrationTest {
    @Test
    fun `a healthy lake passes twice and leaves exactly the one marker`() {
        val storage = LakeMinio.storage(bucket = BUCKET)
        LakeS3Clients.build(storage).use { s3 ->
            LakeBootCheck(LakeTestDb.jdbc, s3, storage).verify()
            LakeBootCheck(LakeTestDb.jdbc, s3, storage).verify()
            val keys = s3.listObjectsV2 { it.bucket(BUCKET) }.contents().map { it.key() }
            keys shouldBe listOf(LakeBootCheck.probeKey(storage))
            keys.single() shouldBe "lake/dp/conditional-put-probe"
        }
    }

    @Test
    fun `a dropped, disabled or re-pointed trigger refuses boot`() {
        refusalUnder("DROP TRIGGER dp_fence ON iceberg_tables") shouldStartWith "lake.boot.trigger_missing"
        refusalUnder("ALTER TABLE iceberg_namespace_properties DISABLE TRIGGER dp_fence") shouldContain
            "iceberg_namespace_properties.dp_fence"
        refusalUnder("DROP TRIGGER dp_revlog_drop ON iceberg_tables") shouldContain "iceberg_tables.dp_revlog_drop"
        refusalUnder(
            "CREATE OR REPLACE TRIGGER dp_fence BEFORE INSERT OR UPDATE OR DELETE ON iceberg_tables" +
                " FOR EACH ROW EXECUTE FUNCTION dp_revlog()",
        ) shouldContain "iceberg_tables.dp_fence"
    }

    @Test
    fun `the five-column catalog shape and a missing control row refuse boot`() {
        refusalUnder("ALTER TABLE iceberg_tables DROP COLUMN iceberg_type") shouldStartWith "lake.boot.catalog_shape"
        refusalUnder("DELETE FROM lake_control") shouldStartWith "lake.boot.control_row"
    }

    @Test
    fun `a missing bucket, a refused credential and an unreachable endpoint refuse boot without the secret`() {
        val missing = LakeMinio.storage(bucket = "dp-lake-missing", create = false)
        refusal(missing) shouldStartWith "lake.boot.storage_unreachable"
        refusal(missing) shouldContain "HTTP 404"

        LakeMinio.storage(bucket = BUCKET)
        val wrongSecret = "wrong-${LakeMinio.secret.reversed()}"
        val refused = refusal(LakeMinio.storage(bucket = BUCKET, create = false, secretKey = wrongSecret))
        refused shouldStartWith "lake.boot.storage_unreachable"
        refused shouldContain "HTTP 403"
        refused shouldNotContain wrongSecret
        refused shouldNotContain LakeMinio.secret

        val unreachable =
            LakeMinio.storage(bucket = BUCKET, create = false).let {
                LakeProperties.Storage(
                    bucket = it.bucket,
                    region = it.region,
                    endpoint = "http://127.0.0.1:1",
                    pathStyle = true,
                    accessKey = it.accessKey,
                    secretKey = it.secretKey,
                )
            }
        refusal(unreachable) shouldStartWith "lake.boot.storage_unreachable"
    }

    private fun refusal(
        storage: LakeProperties.Storage,
        jdbc: JdbcTemplate = LakeTestDb.jdbc,
    ): String =
        LakeS3Clients.build(storage).use { s3 ->
            shouldThrow<IllegalStateException> { LakeBootCheck(jdbc, s3, storage).verify() }.message.orEmpty()
        }

    /** The refusal the check raises while [ddl] is in force on its connection; rolled back afterwards. */
    private fun refusalUnder(ddl: String): String {
        val storage = LakeMinio.storage(bucket = BUCKET)
        LakeTestDb.connect().use { connection ->
            connection.autoCommit = false
            try {
                connection.createStatement().use { it.execute(ddl) }
                return refusal(storage, JdbcTemplate(SingleConnectionDataSource(connection, true)))
            } finally {
                connection.rollback()
            }
        }
    }

    private companion object {
        const val BUCKET = "dp-lake-boot"
    }
}
