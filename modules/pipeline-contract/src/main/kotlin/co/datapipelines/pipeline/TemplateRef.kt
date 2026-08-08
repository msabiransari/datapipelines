package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * An immutable reference to one version of one template (pipeline-contract §4.6).
 *
 * SQL/FTL never lives inline in a pipeline: templates are separately versioned entities and
 * a pipeline points at an exact `{id, version}` pair (§2 principle 3). The pair is portable
 * across environments (§11.1) and is resolved against the template registry at save time
 * (§12.6) and again at run time.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class TemplateRef(
    @field:JsonProperty("id") @get:JsonProperty("id") @param:JsonProperty("id")
    val id: String = "",
    @field:JsonProperty("version") @get:JsonProperty("version") @param:JsonProperty("version")
    val version: Int = 0,
) {
    /** The registry lookup key, `"{id}@{version}"` — the form `RegistryTemplateLoader` resolves. */
    val key: String get() = "$id@$version"
}
