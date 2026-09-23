package co.datapipelines.scripting

import co.datapipelines.typesystem.DatapipelinesException

/**
 * Base for every refusal this module raises (module-structure.md §4.3: a module's
 * exceptions extend the shared base).
 *
 * The `code` values are the pipeline-contract §13.18 Transform rows the record already
 * names — this module does not invent codes; it carries the mapping the design record
 * fixes so callers (7b/7c) can rely on it. If the catalog amends a row, the constant
 * here moves in the same commit.
 */
open class ScriptingException(
    code: String,
    message: String,
    details: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : DatapipelinesException(code, message, details, cause)
