package co.datapipelines.datasources

import co.datapipelines.typesystem.DatapipelinesException
import java.time.Instant
import java.util.UUID

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
 * Which branch of §8A.3's **rule 3** ran for one bootstrap entry (061/T84).
 *
 * The values are the whole decision, and the decision is made by CONNECTING — never by
 * preferring the file over the row or the row over the file. That is what lets rule 3 fix the
 * credential desync without weakening rule 1 ("a restart never reverts an operator's edit").
 */
enum class CredentialResync {
    /**
     * No live row to reconcile — never registered, or **soft-deleted**. A soft-deleted
     * bootstrap datasource is left exactly as it is: rule 1 promises a datasource an operator
     * deleted never resurrects itself, and a credential write would be a resurrection in
     * everything but the flag.
     */
    NO_LIVE_ROW,

    /**
     * The file's credential and the stored one are the SAME — the ordinary case on every
     * boot. **Nothing is probed.** Rule 3 fires on a DIFFERENCE, and making a healthy restart
     * open a connection per bootstrap datasource would be a new cost paid on every boot to
     * learn something the equality already said.
     */
    CREDENTIAL_MATCHES,

    /** The STORED credential authenticates. The row is left byte-untouched (rule 1). */
    STORED_WORKS,

    /**
     * #818 — the row is the managed lake datasource. Configuration owns its credential (the
     * reader or writer pair, re-synced at every boot), so a bootstrap entry naming it is never
     * probed and its credential is never written. The row is left byte-untouched.
     */
    MANAGED_ROW,

    /**
     * The stored credential does not authenticate and the FILE's does — `credential_encrypted`
     * is replaced and nothing else is. The desync the 2026-09-02 incident was made of.
     */
    RESYNCED,

    /**
     * Neither credential authenticates. The row is left alone and the failure is logged at
     * ERROR: there is nothing to resync TO, and replacing one broken credential with another
     * would destroy the operator's value for no gain.
     */
    BOTH_FAILED,

    /**
     * The stored ciphertext could not be DECRYPTED at all — a wrong master key, a restored
     * row from another deployment, or corruption. The row is left alone and the failure is
     * logged at ERROR naming the encryption key.
     *
     * Deliberately not folded into [BOTH_FAILED]: the remedy is different (fix
     * `datapipelines.db.encryption-key`, not the database role's password), and overwriting
     * an unreadable credential with the file's would quietly paper over a key problem that
     * every OTHER datasource in the deployment also has. Rule 1 is absolute here — a resync
     * gets to fix a credential that FAILED a login, not one it could not read.
     */
    STORED_UNREADABLE,

    /**
     * This registry cannot answer — it holds no ciphertext and runs no probe.
     *
     * The default return of [DatasourceRegistry.resyncBootstrapCredential], for the same
     * reason [DatasourceRegistry.retirePool] defaults to a no-op rather than being abstract
     * (and unlike the live reads, which are abstract by design, 020 F6): a silent default
     * here cannot re-open a hole, because bootstrap registration only ever runs against the
     * production registry — the in-memory fakes exist to answer read questions in modules
     * that never boot one. Making the method abstract would instead cost an edit in every
     * unrelated module's test double.
     */
    NOT_APPLICABLE,
}

/**
 * The stored outcome of the last connection test (V9; datasources.md §8.1B, 061/T84) — the
 * persisted, list-visible twin of [TestResult].
 *
 * [TestResult] is what ONE probe returned; this is what the ROW remembers, so the datasources
 * screen can answer "is this credential still working?" without anyone running an execution.
 * It carries the three columns and nothing else: the full [TestResult] holds a latency and an
 * exception class that belong to the moment of the probe, not to the datasource.
 *
 * [message] is the driver's message on failure and the server version on success — both
 * already redaction-scrubbed by the probe, so this value is safe to render and to serialize.
 */
data class DatasourceTestOutcome(
    val testedAt: Instant,
    val ok: Boolean,
    val message: String? = null,
) {
    companion object {
        /** The row-shaped projection of a live probe (§8.1) — the one place the two types meet. */
        fun of(result: TestResult): DatasourceTestOutcome =
            DatasourceTestOutcome(
                testedAt = result.testedAt,
                ok = result.connected,
                message = if (result.connected) result.serverVersion else result.error,
            )
    }
}

/**
 * Outcome of a soft delete (§6.2). Never throws for the in-use case — the caller needs the
 * list of blocking pipelines to clean up.
 *
 * [references] is the ANY-VERSION scan (061/T79, mirroring 040's template guard): one entry
 * per referencing NODE, carrying the pipeline version whose body holds the reference — a
 * released pipeline whose OLDER version pins the datasource is a real reference, because
 * pipeline versions are immutable and executable by explicit version.
 *
 * The two lists are the LOCK's evidence: every workspace's references, so a datasource granted to another workspace
 * stays protected. They are never rendered as they stand — a surface names them only through [usagesVisibleTo], which
 * is why the distinct names live on [DatasourceUsages], not here (#873 §F.3 e: the delete refusal's details are scoped
 * to the caller's workspaces).
 */
data class DeleteResult(
    val deleted: Boolean,
    val name: String,
    val errorCode: String? = null,
    val references: List<DatasourceReference> = emptyList(),
    /** #873 §F.3 e — the parameter-set selectors that lock the name, one per selector occurrence, in scan order. */
    val parameterSetReferences: List<ParameterSetDatasourceReference> = emptyList(),
) {
    /** The refusal's evidence as [visibility] may see it; [DatasourceUsages.inUse] still counts every workspace. */
    fun usagesVisibleTo(visibility: ReferenceVisibility): DatasourceUsages =
        DatasourceUsages.of(references, parameterSetReferences, visibility)
}

/**
 * Whose stored references a datasource-delete refusal may NAME (#873 §F.3 e; record §12 "missing versus inaccessible
 * facts remain indistinguishable"). The surface resolves it from the authenticated caller — never from a request value:
 * a super admin reads every workspace ([Everything]); anyone else reads the workspace the request is authorized in
 * ([Workspace]).
 */
sealed interface ReferenceVisibility {
    /** True when a reference held in [workspaceId] may be named to this caller. */
    fun admits(workspaceId: UUID): Boolean

    /** A super admin: every workspace's references are named. */
    data object Everything : ReferenceVisibility {
        override fun admits(workspaceId: UUID): Boolean = true
    }

    /** A workspace-scoped caller: only references held in [id] are named. */
    data class Workspace(
        val id: UUID,
    ) : ReferenceVisibility {
        override fun admits(workspaceId: UUID): Boolean = workspaceId == id
    }
}

/**
 * The in-use evidence one caller may see (#873 §F.3 e). [inUse] is the LOCK — true when ANY workspace's stored pipeline
 * or parameter-set version references the name, so the refusal fires either way; [references] and
 * [parameterSetReferences] keep only what [ReferenceVisibility.admits], in scan order. A reference outside the caller's
 * scope contributes no name, id, link, count or workspace: two different outside populations yield equal instances.
 */
data class DatasourceUsages(
    val inUse: Boolean,
    val references: List<DatasourceReference>,
    val parameterSetReferences: List<ParameterSetDatasourceReference>,
) {
    /** The named blocking pipelines, distinct, in scan order — what a message counts and names. */
    val referencingPipelines: List<String> get() = references.map { it.pipelineName }.distinct()

    /** The named blocking parameter sets, distinct, in scan order. */
    val referencingParameterSets: List<String> get() = parameterSetReferences.map { it.parameterSetName }.distinct()

    /** True when the caller may be told which references block the delete — false when every one lies outside its scope. */
    val named: Boolean get() = references.isNotEmpty() || parameterSetReferences.isNotEmpty()

    companion object {
        /** Scopes the any-version scan's two lists to [visibility]; the lock bit is computed BEFORE the filter. */
        fun of(
            references: List<DatasourceReference>,
            parameterSetReferences: List<ParameterSetDatasourceReference>,
            visibility: ReferenceVisibility,
        ): DatasourceUsages =
            DatasourceUsages(
                inUse = references.isNotEmpty() || parameterSetReferences.isNotEmpty(),
                references = references.filter { visibility.admits(it.workspaceId) },
                parameterSetReferences = parameterSetReferences.filter { visibility.admits(it.workspaceId) },
            )
    }
}
