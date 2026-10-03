package co.datapipelines.parameters

import java.util.UUID

/**
 * Who asked for an evaluation — the durable history's caller discriminator (the parameter-set workspace spec R2,
 * §2.1 `parameter_evaluations.caller`; enums.md §39). All five values exist now (the owner's §11.10 ruling: widening
 * the CHECK later would be a migration), and [PIPELINE] is DORMANT: no consumer binds a set to a pipeline yet (the
 * engine record's §13), so the recorder refuses it at its entry until that binding exists. [PAGE] is produced only
 * by the Parameter Sets page's observed route.
 */
enum class EvaluationCaller {
    PAGE,
    DASHBOARD,
    PIPELINE,
    REST,
    MCP,
}

/**
 * One evaluation attempt, named by its caller — the REQUIRED argument of [ParameterEvaluator.evaluate] and
 * [ParameterEvaluator.evaluateBlocking] (#376, R1 of its brief): a new call site cannot compile without saying who
 * it is, so no evaluation can record under the wrong discriminator by default.
 *
 * [evaluationId] is the record's primary key — the observed route's client-minted id (the owner's §11.3 ruling),
 * else minted here by [of]; never re-issued. The principal is a person XOR a key (`chk_parameter_evaluations_principal`,
 * V43's shape): a session names its user, an API key its key id. [correlationId] is the caller's own correlation
 * identity when one exists — the dashboard runtime's refresh id; null for the page, REST and MCP, whose evaluation
 * id already is the identity.
 */
data class EvaluationAttempt(
    val evaluationId: UUID,
    val caller: EvaluationCaller,
    val principalUserId: UUID?,
    val principalKeyId: String?,
    val correlationId: String?,
) {
    init {
        require((principalUserId == null) != (principalKeyId == null)) {
            "an evaluation attempt names exactly one principal — a user or a key, never both or neither"
        }
    }

    companion object {
        /**
         * The attempt of a principal acting as [caller]: a key credential ([keyId] non-null) records the key, any other
         * the user [userId] — the dashboard refreshes' rule (`DashboardRuntime.recordOf`). [evaluationId] defaults to a
         * fresh server-minted id; the observed route passes its client-minted one.
         */
        fun of(
            caller: EvaluationCaller,
            userId: UUID,
            keyId: String?,
            correlationId: String? = null,
            evaluationId: UUID = UUID.randomUUID(),
        ): EvaluationAttempt =
            EvaluationAttempt(
                evaluationId = evaluationId,
                caller = caller,
                principalUserId = userId.takeIf { keyId == null },
                principalKeyId = keyId,
                correlationId = correlationId,
            )
    }
}
