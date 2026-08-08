package co.datapipelines.pipeline

import co.datapipelines.pipeline.PipelineErrorCodes.Validation

/**
 * pipeline-contract §12.8 — `settings.tempdb` validation.
 *
 * `tempdb_engine_unsupported` is raised by [PipelineDeserializer]'s pre-scan, for the same
 * reason as the other wire-value codes: [StagingEngine] has exactly one constant in v1, so a
 * payload declaring `DUCKDB` cannot be bound at all and the verdict has to be reached on the
 * JSON tree. enums.md is explicit that reserved values "MUST NOT appear in generated code or
 * be accepted by validators in v1" — hence one constant, not a rejected-at-validation second
 * one.
 *
 * What remains for the typed model is the engine-specific `config` map.
 */
internal object SettingsRules {
    fun check(
        pipeline: Pipeline,
        into: FailureCollector,
    ) {
        val tempdb = pipeline.settings.tempdb
        val allowed = TempdbSettings.ALLOWED_CONFIG_KEYS[tempdb.engine].orEmpty()
        tempdb.config.forEach { (key, value) ->
            if (key !in allowed) {
                into.add(
                    Validation.TEMPDB_CONFIG_INVALID,
                    "settings.tempdb.config.$key",
                    "'${key.truncateForError()}' is not a valid config key for engine ${tempdb.engine.wire}; " +
                        "allowed: $allowed.",
                    mapOf("key" to key.truncateForError(), "engine" to tempdb.engine.wire, "allowed" to allowed),
                )
                return@forEach
            }
            if (key == TempdbSettings.MAX_MEMORY_MB_KEY && !(value.isIntegralNumber && value.asInt() > 0)) {
                into.add(
                    Validation.TEMPDB_CONFIG_INVALID,
                    "settings.tempdb.config.$key",
                    "'$key' must be a positive integer number of megabytes.",
                    mapOf("key" to key, "engine" to tempdb.engine.wire),
                )
            }
        }
    }
}
