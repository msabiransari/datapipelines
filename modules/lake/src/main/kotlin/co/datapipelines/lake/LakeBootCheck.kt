package co.datapipelines.lake

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.HeadObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.model.S3Exception

/**
 * The managed lake's after-Flyway boot check (#785 A6; configuration.md §7). Run once, by `web`'s
 * `LakeCatalogStartupCheck`, only when `datapipelines.lake.enabled=true`. Each failure refuses boot with an
 * [IllegalStateException] whose message starts with its catalogued id ([Refusal]); none is a warning, because
 * every one of them is a lake that would accept writes without the guarantees §8a rests on.
 *
 * What it checks, in order — all reads but the one marker PUT; it opens NO Iceberg catalog, so it cannot issue
 * catalog DDL (the catalog itself runs with `jdbc.init-catalog-tables=false`):
 * 1. the two catalog tables have exactly V53's V1 column list ([Refusal.CATALOG_SHAPE]);
 * 2. the fence trigger on both tables and the two revision triggers exist, are ENABLED, and call their own
 *    functions ([Refusal.TRIGGER_MISSING]) — a disabled or re-pointed trigger is an unfenced catalog;
 * 3. `lake_control` holds exactly its one row ([Refusal.CONTROL_ROW]);
 * 4. a HEAD on the bucket answers 200, then a HEAD on the storage prefix (skipped when the prefix is empty)
 *    answers 200 or 404 — the store is reachable and the writer may read there ([Refusal.STORAGE_UNREACHABLE]).
 *    The bucket goes first because a key HEAD answers 404 for a missing bucket as well. AWS answers 403 instead
 *    of 404 to a caller without `s3:ListBucket`; the writer needs that permission for the orphan sweep anyway
 *    (P6), so a 403 refuses;
 * 5. the store honours `If-None-Match: *`: two conditional PUTs of `<prefix>dp/conditional-put-probe` — the
 *    first may create it or find it (412), the second MUST answer 412 ([Refusal.CONDITIONAL_PUT]). The marker
 *    is never deleted, so every later boot sends two PUTs that both answer 412. Conditional writes are what the
 *    landing protocol's exactly-once markers (P4) rely on.
 *
 * Nothing else leaves the process: no other key is read, listed or written.
 */
class LakeBootCheck(
    private val jdbc: JdbcTemplate,
    private val s3: S3Client,
    private val storage: LakeProperties.Storage,
) {
    private val log = LoggerFactory.getLogger(LakeBootCheck::class.java)

    fun verify() {
        verifyCatalogShape()
        verifyTriggers()
        verifyControlRow()
        verifyStorageReachable()
        verifyConditionalPut()
        log.info("event=lake.boot.ok bucket={} prefix={}", storage.bucket, storage.normalisedPrefix)
    }

    private fun verifyCatalogShape() {
        CATALOG_COLUMNS.forEach { (table, expected) ->
            val actual =
                jdbc.queryForList(
                    "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema()" +
                        " AND table_name = ? ORDER BY column_name",
                    String::class.java,
                    table,
                )
            if (actual != expected.sorted()) {
                refuse(Refusal.CATALOG_SHAPE, "$table has columns $actual; V53's V1 shape is ${expected.sorted()}")
            }
        }
    }

    private fun verifyTriggers() {
        val present =
            jdbc
                .query(
                    "SELECT c.relname, t.tgname, p.proname FROM pg_trigger t JOIN pg_class c ON c.oid = t.tgrelid" +
                        " JOIN pg_proc p ON p.oid = t.tgfoid JOIN pg_namespace n ON n.oid = c.relnamespace" +
                        " WHERE n.nspname = current_schema() AND NOT t.tgisinternal AND t.tgenabled IN ('O', 'A')" +
                        " AND c.relname IN ('iceberg_tables', 'iceberg_namespace_properties')",
                ) { rs, _ -> Triple(rs.getString("relname"), rs.getString("tgname"), rs.getString("proname")) }
                .toSet()
        val missing = REQUIRED_TRIGGERS - present
        if (missing.isNotEmpty()) {
            refuse(
                Refusal.TRIGGER_MISSING,
                "missing, disabled or re-pointed: " + missing.joinToString { (table, trigger, _) -> "$table.$trigger" },
            )
        }
    }

    private fun verifyControlRow() {
        val rows = jdbc.queryForObject("SELECT count(*) FROM lake_control", Long::class.java) ?: 0L
        if (rows != 1L) refuse(Refusal.CONTROL_ROW, "lake_control holds $rows rows; V53 inserts exactly one")
    }

    private fun verifyStorageReachable() {
        // The bucket first: a HEAD on a key answers 404 for a missing BUCKET too, and that must not read as "fine".
        val bucket = head { s3.headBucket(HeadBucketRequest.builder().bucket(storage.bucket).build()) }
        if (bucket != HTTP_OK) refuse(Refusal.STORAGE_UNREACHABLE, "HEAD on the bucket answered HTTP $bucket")
        if (storage.normalisedPrefix.isEmpty()) return
        val prefix =
            head {
                s3.headObject(
                    HeadObjectRequest
                        .builder()
                        .bucket(storage.bucket)
                        .key(storage.normalisedPrefix)
                        .build(),
                )
            }
        if (prefix != HTTP_OK && prefix != HTTP_NOT_FOUND) {
            refuse(Refusal.STORAGE_UNREACHABLE, "HEAD on the prefix answered HTTP $prefix")
        }
    }

    private fun head(call: () -> Unit): Int =
        try {
            call()
            HTTP_OK
        } catch (e: S3Exception) {
            e.statusCode()
        } catch (e: SdkException) {
            refuse(Refusal.STORAGE_UNREACHABLE, "no answer from the store (${e.javaClass.simpleName})")
        }

    private fun verifyConditionalPut() {
        val first = conditionalPut()
        val second = conditionalPut()
        if (first !in setOf(HTTP_OK, HTTP_PRECONDITION_FAILED) || second != HTTP_PRECONDITION_FAILED) {
            refuse(
                Refusal.CONDITIONAL_PUT,
                "two PUTs with If-None-Match: * answered HTTP $first then $second; the second must be 412",
            )
        }
    }

    private fun conditionalPut(): Int =
        try {
            s3.putObject(
                PutObjectRequest
                    .builder()
                    .bucket(storage.bucket)
                    .key(probeKey(storage))
                    .ifNoneMatch("*")
                    .build(),
                RequestBody.fromString(PROBE_BODY),
            )
            HTTP_OK
        } catch (e: S3Exception) {
            e.statusCode()
        } catch (e: SdkException) {
            refuse(Refusal.STORAGE_UNREACHABLE, "no answer from the store (${e.javaClass.simpleName})")
        }

    private fun refuse(
        refusal: Refusal,
        detail: String,
    ): Nothing = error("${refusal.id}: ${refusal.summary} — $detail")

    /** The catalogued boot refusals (configuration.md §7). The id leads the message. */
    enum class Refusal(
        val id: String,
        val summary: String,
    ) {
        CATALOG_SHAPE("lake.boot.catalog_shape", "the Iceberg catalog tables are not V53's shape"),
        TRIGGER_MISSING("lake.boot.trigger_missing", "a lake catalog trigger is missing or disabled; the catalog is unfenced"),
        CONTROL_ROW("lake.boot.control_row", "lake_control does not hold its one row"),
        STORAGE_UNREACHABLE("lake.boot.storage_unreachable", "the lake's object store is unreachable or refuses the writer"),
        CONDITIONAL_PUT("lake.boot.conditional_put", "the lake's object store does not honour If-None-Match: *"),

        /** #818 (record §6): raised by the web layer's managed-datasource registrar, which runs after [verify]. */
        DATASOURCE_NAME_TAKEN("lake.boot.datasource_name_taken", "a datasource that is not the managed lake holds the lake's name"),

        /**
         * #845 (D4): raised by the web layer's managed-datasource registrar before it writes the row, when
         * `reads.egress-proxy` is set: the proxy is unreachable, does not declare the configured
         * `reads.egress-max-age-seconds` at `/dp-egress`, or cannot reach the object store.
         */
        EGRESS_PROXY(
            "lake.boot.egress_proxy",
            "the managed reads' egress proxy is unreachable, does not declare the configured max-age, or cannot reach the store",
        ),
    }

    companion object {
        private const val HTTP_OK = 200
        private const val HTTP_NOT_FOUND = 404
        private const val HTTP_PRECONDITION_FAILED = 412

        /** The marker body: the key's own purpose, so an operator listing the bucket can tell what it is. */
        private const val PROBE_BODY = "datapipelines lake boot check: conditional PUT probe (never deleted)"

        /** `<prefix>dp/conditional-put-probe` — the one key the boot check writes. */
        fun probeKey(storage: LakeProperties.Storage): String = "${storage.normalisedPrefix}dp/conditional-put-probe"

        /** V53's V1 catalog shape (Iceberg 1.12.0 `JdbcUtil` V0 DDL + `iceberg_type`; A0). */
        val CATALOG_COLUMNS: Map<String, List<String>> =
            mapOf(
                "iceberg_tables" to
                    listOf(
                        "catalog_name",
                        "table_namespace",
                        "table_name",
                        "metadata_location",
                        "previous_metadata_location",
                        "iceberg_type",
                    ),
                "iceberg_namespace_properties" to listOf("catalog_name", "namespace", "property_key", "property_value"),
            )

        /** (table, trigger, function) — V53's four triggers. */
        val REQUIRED_TRIGGERS: Set<Triple<String, String, String>> =
            setOf(
                Triple("iceberg_tables", "dp_fence", "dp_fence"),
                Triple("iceberg_namespace_properties", "dp_fence", "dp_fence"),
                Triple("iceberg_tables", "dp_revlog", "dp_revlog"),
                Triple("iceberg_tables", "dp_revlog_drop", "dp_revlog_drop"),
            )
    }
}
