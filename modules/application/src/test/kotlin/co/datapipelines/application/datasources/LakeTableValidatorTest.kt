package co.datapipelines.application.datasources

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * [LakeTableValidator] — the dp-lake registry's input grammar (metadata-db §4.15, 089 §A).
 *
 * Two boundaries are pinned here, and both must be able to FAIL, not just pass:
 *
 * 1. **The segment grammar** — namespace segments and names are the pipeline/template §4.1
 *    segment (read from the shared `PipelineNameGrammar`, never a retyped copy), minus `.`.
 * 2. **The location grammar** — `s3://` / `file://` only, and the TOTAL injection refusal:
 *    locations are interpolated into `CREATE VIEW … read_parquet('<location>')` in a later
 *    phase, so a quote, backslash, whitespace or control character anywhere in the value is an
 *    attack string, not a name to escape. If this suite ever passes with one of those
 *    accepted, the SQL-injection boundary is open.
 */
class LakeTableValidatorTest {
    // ---------------------------------------------------------- namespace / name grammar

    @Test
    fun `legal namespaces and names are accepted`() {
        assertAll(
            { LakeTableValidator.namespaceOf(listOf("nyc")) shouldBe listOf("nyc") },
            { LakeTableValidator.namespaceOf(listOf("nyc", "mobility")) shouldBe listOf("nyc", "mobility") },
            { LakeTableValidator.namespaceOf(listOf("a1", "sales-2024", "q4_final")) shouldBe listOf("a1", "sales-2024", "q4_final") },
            { LakeTableValidator.nameOf("hvfhv_zone_day") shouldBe "hvfhv_zone_day" },
            { LakeTableValidator.nameOf("trips2024") shouldBe "trips2024" },
        )
    }

    @Test
    fun `grammar violations are refused with the catalogued codes`() {
        // (value, why it is illegal) — each must throw, with the right code, or the boundary
        // is not what the docs say it is.
        val badNamespaces =
            listOf(
                emptyList<String>() to "empty",
                List(10) { "s$it" } to "ten segments",
                listOf("NYC") to "uppercase",
                listOf("nyc", "") to "blank segment",
                listOf("_scratch") to "leading underscore",
                listOf("a.b") to "dot inside a segment — the dotted shorthand must round-trip",
                listOf("nyc mobility") to "whitespace",
                listOf("nyc/mobility") to "separator char inside a segment",
            )
        badNamespaces.forEach { (namespace, why) ->
            val e = shouldThrow<DatapipelinesException> { LakeTableValidator.namespaceOf(namespace) }
            withClue(why) { e.code shouldBe PipelineErrorCodes.Datasource.LAKE_NAMESPACE_INVALID }
        }

        val badNames = listOf("NYC", "_trips", "a.b", "trips table", "trips/x", "")
        badNames.forEach { name ->
            shouldThrow<DatapipelinesException> { LakeTableValidator.nameOf(name) }
                .code shouldBe PipelineErrorCodes.Datasource.LAKE_NAME_INVALID
        }
    }

    // ---------------------------------------------------------- format

    @Test
    fun `format is the closed set, case-normalized`() {
        assertAll(
            { LakeTableValidator.formatOf("parquet") shouldBe LakeTableFormat.PARQUET },
            { LakeTableValidator.formatOf("ICEBERG") shouldBe LakeTableFormat.ICEBERG },
            {
                shouldThrow<DatapipelinesException> { LakeTableValidator.formatOf("delta") }
                    .code shouldBe PipelineErrorCodes.Datasource.LAKE_FORMAT_INVALID
            },
        )
    }

    // ---------------------------------------------------------- location schemes

    @Test
    fun `s3 and file locations are accepted - directory, glob, iceberg root, deep key`() {
        val legal =
            listOf(
                "s3://datapipelines-co/sample-data/lake/v1/hvfhv_zone_day/part-0.parquet",
                "s3://datapipelines-co/sample-data/lake/v1/hvfhv_trips/pickup_date=*/part-*.parquet",
                "s3://datapipelines-co/sample-data/lake/v1/hvfhv_trips_iceberg",
                "s3://bucket/a/b/c/d-e_f.parquet",
                "file:///data/lake/trips",
                "file:///mnt/nfs/iceberg/trips_table",
            )
        legal.forEach { LakeTableValidator.locationOf(it) shouldBe it }
    }

    @Test
    fun `schemes other than s3 and file are refused`() {
        val refused =
            listOf(
                "https://bucket.s3.amazonaws.com/x.parquet", // httpfs reading arbitrary URLs is refused
                "http://minio:9000/bucket/x",
                "ftp://host/x",
                "gs://bucket/x",
                "abfss://container@account/x",
                "jdbc:postgresql://h/db",
                "bucket/path/file.parquet", // no scheme at all
                "s3://",
                "file://",
                "s3://UPPERCASE-BUCKET/x", // bucket grammar
            )
        refused.forEach { location ->
            shouldThrow<DatapipelinesException> { LakeTableValidator.locationOf(location) }
                .code shouldBe PipelineErrorCodes.Datasource.LAKE_LOCATION_INVALID
        }
    }

    // ---------------------------------------------------------- the injection refusal (total)

    @Test
    fun `injection-bearing locations are refused - quotes, backslash, control chars, whitespace`() {
        val attacks =
            listOf(
                "s3://bucket/x'); DROP TABLE lake_tables; --", // the canonical breakout
                "s3://bucket/x'; ATTACH 'evil", // quote + statement
                "s3://bucket/it\"s/here", // double quote
                "s3://bucket/back\\slash", // backslash — escape-sequence vector
                "s3://bucket/with space/file.parquet", // whitespace
                "s3://bucket/with\ttab", // tab
                "s3://bucket/with\nnewline', SELECT * FROM secrets; --", // newline + quote
                "s3://bucket/with\rcarriage",
                "s3://bucket/ctrlchar", // DEL
                "s3://bucket/''/x", // doubled quote
                "file:///data/x'; DELETE FROM lake_tables; --",
            )
        attacks.forEach { location ->
            shouldThrow<DatapipelinesException> { LakeTableValidator.locationOf(location) }
                .code shouldBe PipelineErrorCodes.Datasource.LAKE_LOCATION_INVALID
        }
    }

    // ---------------------------------------------------------- partition column

    @Test
    fun `partition column is nullable and identifier-shaped`() {
        assertAll(
            { LakeTableValidator.partitionColumnOf(null) shouldBe null },
            { LakeTableValidator.partitionColumnOf("pickup_date") shouldBe "pickup_date" },
            {
                shouldThrow<DatapipelinesException> { LakeTableValidator.partitionColumnOf("pickup date") }
                    .code shouldBe PipelineErrorCodes.Datasource.LAKE_NAME_INVALID
            },
            {
                shouldThrow<DatapipelinesException> { LakeTableValidator.partitionColumnOf("1day") }
                    .code shouldBe PipelineErrorCodes.Datasource.LAKE_NAME_INVALID
            },
        )
    }
}
