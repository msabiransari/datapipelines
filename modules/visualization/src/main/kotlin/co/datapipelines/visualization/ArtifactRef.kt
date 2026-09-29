package co.datapipelines.visualization

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * A reference to one exact version of one named artifact — `{ "name", "version" }` (the spec's §4; the design
 * record §4.2): the FQN in a saved definition, resolved within the workspace and the kind the containing field
 * names (`transform.template`, `parameter_set`, `sources[].pipeline`, `visualizations[].visualization`).
 * Authors never maintain both a UUID and a name; the server resolves.
 */
data class ArtifactRef(
    @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
    val name: String,
    @field:JsonProperty("version") @get:JsonProperty("version") @param:JsonProperty("version")
    val version: Int,
) {
    /** `name@version` — the form a refusal and a log line name a pin by. */
    override fun toString(): String = "$name@$version"
}
