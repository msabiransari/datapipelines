package co.datapipelines.application.datasources

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.typesystem.DatapipelinesException

/**
 * The dp-lake registry's input grammar (metadata-db §4.15, the 2026-09-07 lake-datasource
 * design record §2, round 089 §A) — the ONE validation boundary every lake-table write crosses,
 * shared by REST and MCP so the two surfaces cannot drift (049's rule).
 *
 * ## Names and namespaces — the 077 segment grammar
 *
 * `namespace` segments and `name` are segments of the pipeline/template §4.1 grammar, checked
 * through [PipelineNameGrammar.matchesSegment] — the SAME production, read from the validator's
 * own regex, never a re-typed copy. One narrowing on top: a segment may not contain `.`. The
 * grammar admits one, but a lake table's namespace crosses the wire as the dot-joined shorthand
 * (`nyc.mobility`, the `Namespaces` form 087 froze), and a dotted segment would not round-trip
 * through it; a dotted NAME would be a qualifier separator to the engine, not a character in an
 * identifier. Refusing the dot at registration is what keeps the shorthand total.
 *
 * A namespace is **one to nine** segments — never empty. The tree UI's root level is a folder
 * list by construction, and the unregister path addresses a table as `{ns}/{table}` with `ns`
 * dot-joined; an empty namespace is unaddressable on both.
 *
 * ## Locations — a scheme allowlist and a TOTAL injection refusal
 *
 * A location is later interpolated into `CREATE VIEW … AS SELECT * FROM read_parquet('<location>')`
 * / `iceberg_scan('<location>')` — a SQL string-literal boundary. The refusal is therefore
 * total rather than an escaping rule: no single or double quote, no backslash, no control
 * character and no whitespace anywhere in the value. A legitimate S3 key needs none of those,
 * and a value that carries one is an injection attempt, not a name to escape.
 *
 * Schemes: `s3://bucket/prefix[/glob]` (Iceberg: the table root holding `metadata/`) and
 * `file://` for an on-prem volume (087's `catalog.kind`-less lake). **No other schemes** — an
 * `https://` location would let author SQL read arbitrary URLs through the engine's httpfs,
 * which is exactly the reach the datasource's own `dialect.endpoint` boundary exists to bound.
 */
object LakeTableValidator {
    /** The longest a location may be — generous for a bucket path, useless for an attack string. */
    const val MAX_LOCATION_CHARS = 2048

    /** A namespace is 1–9 segments (the §4.1 prefix bound, minus the zero case — see the KDoc). */
    const val MAX_NAMESPACE_SEGMENTS = 9

    private val S3_BUCKET = Regex("[a-z0-9][a-z0-9.-]{0,62}")

    private val COLUMN_IDENTIFIER = Regex("[a-zA-Z_][a-zA-Z0-9_]{0,62}")

    /**
     * The injection refusal's predicate, shared with [LakeManifestUrl] (whose URLs face the
     * same interpolation-and-echo boundary): a quote, a backslash, whitespace or a control
     * character (DEL included) anywhere in the value. TOTAL — there is no escaping rule,
     * because a value that would need one is an attack string, not a name.
     */
    fun containsRefusedChar(value: String): Boolean =
        value.any { ch ->
            ch in "'\"\\" || ch <= ' ' || ch == '\u007F'
        }

    /**
     * Validates a namespace — the segments, or a [DatapipelinesException] with
     * `datasource.validation.lake_namespace_invalid` naming the rule.
     */
    fun namespaceOf(namespace: List<String>): List<String> {
        if (namespace.isEmpty() || namespace.size > MAX_NAMESPACE_SEGMENTS) {
            throw invalid(
                PipelineErrorCodes.Datasource.LAKE_NAMESPACE_INVALID,
                "namespace",
                "a lake table's namespace is 1 to $MAX_NAMESPACE_SEGMENTS segments; got ${namespace.size}",
            )
        }
        namespace.forEach { segment ->
            if (!isSegment(segment)) {
                throw invalid(
                    PipelineErrorCodes.Datasource.LAKE_NAMESPACE_INVALID,
                    "namespace",
                    "namespace segment '${segment.echo()}' is not a legal segment: " +
                        "[a-z0-9][a-z0-9_.-]{0,63} with no '.', the pipeline/template §4.1 segment grammar",
                )
            }
        }
        return namespace
    }

    /** Validates a table name — `datasource.validation.lake_name_invalid` on a refusal. */
    fun nameOf(name: String): String {
        if (!isSegment(name)) {
            throw invalid(
                PipelineErrorCodes.Datasource.LAKE_NAME_INVALID,
                "name",
                "table name '${name.echo()}' is not a legal segment: " +
                    "[a-z0-9][a-z0-9_.-]{0,63} with no '.', the pipeline/template §4.1 segment grammar",
            )
        }
        return name
    }

    /**
     * Validates a partition column — nullable; when present, an engine identifier. Refused
     * with `datasource.validation.lake_name_invalid`, the same code a bad name gets, with the
     * field named in `details`.
     */
    fun partitionColumnOf(partitionColumn: String?): String? {
        if (partitionColumn == null) return null
        if (!COLUMN_IDENTIFIER.matches(partitionColumn)) {
            throw invalid(
                PipelineErrorCodes.Datasource.LAKE_NAME_INVALID,
                "partition_column",
                "partition column '${partitionColumn.echo()}' is not a legal column identifier",
            )
        }
        return partitionColumn
    }

    /** Validates a format wire value — `datasource.validation.lake_format_invalid` on a refusal. */
    fun formatOf(format: String): LakeTableFormat =
        LakeTableFormat.fromWireOrNull(format.trim().lowercase())
            ?: throw invalid(
                PipelineErrorCodes.Datasource.LAKE_FORMAT_INVALID,
                "format",
                "format '${format.echo()}' is not one of ${LakeTableFormat.entries.map { it.wire }}",
            )

    /**
     * Validates a location — the scheme allowlist plus the total injection refusal (see the
     * class KDoc; this is the SQL-injection boundary phase B's `CREATE VIEW` interpolation
     * relies on). `datasource.validation.lake_location_invalid` on any refusal.
     */
    @Suppress("ThrowsCount") // a boundary maps each distinct failure to its own catalogued 4xx
    fun locationOf(location: String): String {
        if (location.isEmpty() || location.length > MAX_LOCATION_CHARS) {
            throw invalid(
                PipelineErrorCodes.Datasource.LAKE_LOCATION_INVALID,
                "location",
                "a location is 1 to $MAX_LOCATION_CHARS characters; got ${location.length}",
            )
        }
        if (containsRefusedChar(location)) {
            throw invalid(
                PipelineErrorCodes.Datasource.LAKE_LOCATION_INVALID,
                "location",
                "a location may not contain quotes, backslashes, whitespace or control characters — " +
                    "it is interpolated into a SQL string literal, and a value that needs escaping is refused",
            )
        }
        when {
            location.startsWith("s3://") -> {
                val bucket = location.removePrefix("s3://").substringBefore('/')
                if (!S3_BUCKET.matches(bucket)) {
                    throw invalid(
                        PipelineErrorCodes.Datasource.LAKE_LOCATION_INVALID,
                        "location",
                        "'${bucket.echo()}' is not a legal S3 bucket name in '${location.take(MAX_ECHOED_VALUE_CHARS)}'",
                    )
                }
            }

            location.startsWith("file://") -> {
                if (location.removePrefix("file://").isEmpty()) {
                    throw invalid(
                        PipelineErrorCodes.Datasource.LAKE_LOCATION_INVALID,
                        "location",
                        "a file:// location must name a path",
                    )
                }
            }

            else -> {
                throw invalid(
                    PipelineErrorCodes.Datasource.LAKE_LOCATION_INVALID,
                    "location",
                    "location '${location.take(MAX_ECHOED_VALUE_CHARS)}' has no allowed scheme — " +
                        "a lake table's location is s3:// or file://, nothing else",
                )
            }
        }
        return location
    }

    /** One legal segment per the class KDoc: the §4.1 grammar's segment, minus `.`. */
    private fun isSegment(value: String): Boolean = PipelineNameGrammar.matchesSegment(value) && !value.contains('.')

    private fun invalid(
        code: String,
        field: String,
        why: String,
    ): DatapipelinesException =
        DatapipelinesException(
            code,
            "Invalid lake table: $why.",
            mapOf("field" to field),
        )

    /** A bounded echo of a caller-supplied value in an error message (the binder's convention). */
    private fun String.echo(): String = take(MAX_ECHOED_VALUE_CHARS)

    private const val MAX_ECHOED_VALUE_CHARS = 32
}
