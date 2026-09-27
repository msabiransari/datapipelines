package co.datapipelines.pipeline

/**
 * The parameter-name rule of pipeline-contract §6.1 / §12.7 — `[a-z_][a-z0-9_]*`, 1–63 characters —
 * PUBLISHED so a caller outside this module asks the question without owning a second copy of the
 * answer (the [PipelineNameGrammar] precedent). `ParameterRules` judges a pipeline parameter by it,
 * and the parameter engine (#194) judges a parameter set's parameter by the same constant (the
 * engine record's §3.2: "the same constant").
 *
 * Anchored against a leading digit (`${1st_date}` is not a Freemarker identifier) and bounded at 63
 * — which is also a security control: the name is reflected into messages, paths and logs.
 */
object ParameterNameGrammar {
    private val PATTERN = Regex("^[a-z_][a-z0-9_]{0,62}$")

    /** The pattern's source, for a message or a hint. */
    val pattern: String get() = PATTERN.pattern

    /** True when [name] is a legal parameter name. */
    fun matches(name: String): Boolean = PATTERN.matches(name)
}
