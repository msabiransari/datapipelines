package co.datapipelines.visualization

import co.datapipelines.pipeline.PipelineNameGrammar
import co.datapipelines.pipeline.ValidationResult

/** A validator's answer: the canonical document, or every refusal together (§17.2). */
sealed interface ArtifactValidation<out D> {
    data class Valid<D>(
        val document: D,
    ) : ArtifactValidation<D>

    data class Invalid(
        val result: ValidationResult,
    ) : ArtifactValidation<Nothing>
}

/** The document's [ArtifactValidation.Valid] document, or [ArtifactValidationException] with every refusal. */
fun <D> ArtifactValidation<D>.orThrow(fallbackCode: String): D =
    when (this) {
        is ArtifactValidation.Valid -> document
        is ArtifactValidation.Invalid -> throw ArtifactValidationException(result, fallbackCode)
    }

/** The rules both documents share for their identity and their display text (the parameter-set §3.2 limits). */
internal object DocumentRules {
    /** A display name is 1–120 characters and not blank; a description at most 2000. */
    const val MAX_DISPLAY_NAME_CHARS = 120
    const val MAX_DESCRIPTION_CHARS = 2_000

    /** The object/input grammar of the spec's §3.2 — lower-case, a letter first, 64 characters. */
    val OBJECT_NAME = Regex("^[a-z][a-z0-9_]{0,63}$")

    /** The folder-path name (spec §4): `name_invalid` with the grammar's own reason. */
    fun name(
        name: String,
        code: String,
        failures: ArtifactFailures,
    ) {
        if (!PipelineNameGrammar.matches(name)) {
            failures.add(
                code,
                "name",
                "Name '${name.safeEcho()}' is invalid. ${PipelineNameGrammar.DESCRIPTION}",
                mapOf("name" to name.safeEcho(), "reason" to PipelineNameGrammar.refusalReason(name)),
            )
        }
    }

    /** `display_name` and `description` — `body_invalid` / `blank` or `too_long`. */
    fun texts(
        displayName: String,
        description: String?,
        bodyInvalid: String,
        failures: ArtifactFailures,
    ) {
        when {
            displayName.isBlank() -> failures.add(bodyInvalid, "display_name", "display_name is blank.", mapOf("reason" to "blank"))
            displayName.length > MAX_DISPLAY_NAME_CHARS -> tooLong("display_name", MAX_DISPLAY_NAME_CHARS, bodyInvalid, failures)
        }
        if ((description?.length ?: 0) > MAX_DESCRIPTION_CHARS) tooLong("description", MAX_DESCRIPTION_CHARS, bodyInvalid, failures)
    }

    fun tooLong(
        path: String,
        max: Int,
        code: String,
        failures: ArtifactFailures,
    ) = failures.add(code, path, "'$path' is longer than $max characters.", mapOf("reason" to "too_long", "max" to max))
}
