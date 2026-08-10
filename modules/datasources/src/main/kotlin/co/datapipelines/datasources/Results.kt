package co.datapipelines.datasources

import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant

/**
 * Outcome of save-time validation (datasources.md §5.4, §9). Also the return type of
 * [DialectAdapter.validateJdbcUrl].
 *
 * [errors] is **complete, not first-failure** (§6.1): a save runs every §9 rule and returns
 * all failures so the UI renders one form pass. Every [ValidationError.message] is
 * redaction-safe — it never carries a password or the credential portion of a JDBC URL
 * (observability.md §9.2).
 */
data class ValidationResult(
    val valid: Boolean,
    val errors: List<ValidationError> = emptyList(),
) {
    /**
     * @param code a §9 code, e.g. [DatasourceErrorCodes.PROPERTIES_INVALID].
     * @param field JSON-pointer-ish path, e.g. `properties.hikari.maximumPoolSize`, or null.
     * @param message human-readable and safe to surface.
     */
    data class ValidationError(
        val code: String,
        val field: String?,
        val message: String,
    )

    /**
     * Throws [DatasourceValidationException] when invalid; returns nothing otherwise. The
     * exception's `code` is the **first** failure's code (a unified error response carries one),
     * and the full list travels in `details["errors"]` so the REST layer renders every failure.
     */
    fun orThrow() {
        if (!valid) throw DatasourceValidationException(this)
    }

    companion object {
        /** The passing result. */
        fun ok() = ValidationResult(true)

        /** A failing result carrying every collected error. */
        fun of(errors: List<ValidationError>) = ValidationResult(errors.isEmpty(), errors)
    }
}

/**
 * Thrown when an invalid datasource reaches a save boundary. Carries the first failure's code
 * (per the unified error response) with every failure in `details["errors"]`. Extends the
 * shared [DatapipelinesException] base (module-structure §4.3).
 */
class DatasourceValidationException(
    val result: ValidationResult,
) : DatapipelinesException(
        code = result.errors.first().code,
        message = result.errors.first().message,
        details =
            mapOf(
                "errors" to
                    result.errors.map {
                        mapOf("code" to it.code, "field" to it.field, "message" to it.message)
                    },
            ),
    )

/**
 * Outcome of a live connectivity probe (§8.1). Failure is **data, not an exception**: the
 * caller asked "can I connect?" and gets an honest answer, so `POST .../test` is HTTP 200
 * even when [connected] is false.
 *
 * [error]/[errorClass] are redaction-scrubbed — never a password or a credential-bearing URL.
 */
data class TestResult(
    val connected: Boolean,
    val testedAt: Instant,
    val latencyMs: Long? = null,
    val serverVersion: String? = null,
    val error: String? = null,
    val errorClass: String? = null,
)

/**
 * Outcome of a soft delete (§6.2). Never throws for the in-use case — the caller needs the
 * list of blocking pipelines to clean up.
 */
data class DeleteResult(
    val deleted: Boolean,
    val name: String,
    val errorCode: String? = null,
    val referencingPipelines: List<String> = emptyList(),
)
