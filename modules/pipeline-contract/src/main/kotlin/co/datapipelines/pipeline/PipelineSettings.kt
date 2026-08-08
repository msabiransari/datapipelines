package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

/**
 * Pipeline-level execution settings (pipeline-contract §5).
 *
 * Settings travel with the pipeline across environments (§5.2): the staging *engine choice*
 * is a property of the pipeline, while the connections it reaches are per-environment
 * config. Everything under §5.3 (parallelism, timeouts, cache sizes, default output format)
 * is deliberately out of scope for v1 and is not modelled here — an unknown settings key is
 * ignored on read (`ignoreUnknown`) so a v1.1 addition is not a breaking change.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PipelineSettings(
    @field:JsonProperty("tempdb") @get:JsonProperty("tempdb") @param:JsonProperty("tempdb")
    val tempdb: TempdbSettings = TempdbSettings(),
)

/**
 * Staging-engine configuration (§5.1).
 *
 * Omitting `settings.tempdb` entirely defaults to H2 with default config — which is why
 * both fields carry defaults rather than being nullable: "absent" and "explicitly H2 with
 * no config" are the same pipeline, and modelling them differently would give the executor
 * two states to handle where the spec defines one.
 *
 * `config` keys are engine-specific; for H2 the only valid key is [MAX_MEMORY_MB_KEY]
 * (§12.8 `tempdb_config_invalid`). Values stay as [JsonNode] so the validator can check the
 * declared JSON type, not a Jackson-coerced approximation of it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TempdbSettings(
    @field:JsonProperty("engine") @get:JsonProperty("engine") @param:JsonProperty("engine")
    val engine: StagingEngine = StagingEngine.H2,
    @field:JsonProperty("config") @get:JsonProperty("config") @param:JsonProperty("config")
    val config: Map<String, JsonNode> = emptyMap(),
) {
    /**
     * The declared per-execution memory ceiling in MB, or null when the pipeline does not
     * override it.
     *
     * Per D6, a present value takes precedence over the global
     * `datapipelines.staging.h2.max-memory-mb`; absent means "use the global".
     */
    @get:JsonIgnore
    val maxMemoryMb: Int?
        get() = config[MAX_MEMORY_MB_KEY]?.takeIf { it.isIntegralNumber }?.asInt()

    companion object {
        /** The only `config` key H2 accepts in v1 (§5.1). */
        const val MAX_MEMORY_MB_KEY = "max_memory_mb"

        /** Valid `config` keys per engine — the §12.8 `tempdb_config_invalid` check reads this. */
        val ALLOWED_CONFIG_KEYS: Map<StagingEngine, Set<String>> =
            mapOf(StagingEngine.H2 to setOf(MAX_MEMORY_MB_KEY))
    }
}
