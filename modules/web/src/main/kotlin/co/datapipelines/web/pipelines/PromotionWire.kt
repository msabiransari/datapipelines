package co.datapipelines.web.pipelines

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.JsonNode

/**
 * The promotion pair's wire shapes (rest-api.md §18, versioning §10).
 *
 * One file, because the SENDER writes exactly what the RECEIVER reads and a shape defined
 * twice drifts. Both halves live in this deployment's own jar — a sender and a receiver are
 * the same build in two postures — so this is a shared type, not a duplicated contract.
 *
 * Wire keys are snake_case and pinned with explicit `@JsonProperty` on all three use-site
 * targets, for the reason `Template`'s KDoc states at length: the Java-Beans
 * second-character rule, Kotlin's `is`-prefix getters and whatever naming strategy the
 * outbound mapper carries would each rewrite these keys silently.
 */
object PromotionWire {
    /**
     * §10.2's answer from the receiver: what it already holds, and what it can accept.
     *
     * `(name, current_version, body_hash)` per pipeline and per template is the whole delta
     * input — version for humans, hash for machines. [datasources] rides along so §10.5's
     * pre-validation costs no second round trip, and [authoringEnabled] so a sender can refuse
     * a misconfigured target before building a batch rather than after pushing one.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Inventory(
        @field:JsonProperty("deployment") @get:JsonProperty("deployment") @param:JsonProperty("deployment")
        val deployment: String,
        @field:JsonProperty("authoring_enabled") @get:JsonProperty("authoring_enabled") @param:JsonProperty("authoring_enabled")
        val authoringEnabled: Boolean,
        @field:JsonProperty("workspace") @get:JsonProperty("workspace") @param:JsonProperty("workspace")
        val workspace: String,
        @field:JsonProperty("pipelines") @get:JsonProperty("pipelines") @param:JsonProperty("pipelines")
        val pipelines: List<Entry> = emptyList(),
        @field:JsonProperty("templates") @get:JsonProperty("templates") @param:JsonProperty("templates")
        val templates: List<Entry> = emptyList(),
        /** Every datasource name visible in the target workspace — §10.5's pre-validation input. */
        @field:JsonProperty("datasources") @get:JsonProperty("datasources") @param:JsonProperty("datasources")
        val datasources: List<String> = emptyList(),
    ) {
        /** Pipelines by name; a pipeline the target does not have counts as version 0 (§10.2). */
        fun pipelineByName(): Map<String, Entry> = pipelines.associateBy { it.name }

        /** Templates by id — a template's id IS its name (templates.md §3.1). */
        fun templateById(): Map<String, Entry> = templates.associateBy { it.name }
    }

    /** One inventory row: the identity, the version the target serves, and its content hash. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Entry(
        @field:JsonProperty("name") @get:JsonProperty("name") @param:JsonProperty("name")
        val name: String,
        @field:JsonProperty("current_version") @get:JsonProperty("current_version") @param:JsonProperty("current_version")
        val currentVersion: Int,
        @field:JsonProperty("body_hash") @get:JsonProperty("body_hash") @param:JsonProperty("body_hash")
        val bodyHash: String,
    )

    /**
     * §10.4's batch: one unit, applied by the receiver in one transaction or not at all.
     *
     * [templates] and [pipelines] are already in PUSH ORDER — template versions first, then
     * child pipelines, then their parents. The receiver applies them in the order given and
     * does not re-derive it: the sender owns the closure (§10.4), and a receiver that sorted
     * for itself would be a second implementation of the same rule.
     *
     * [sourceEnv] is the sender's `deployment.name` and [keyFingerprint] a truncated digest of
     * the key it presented (never the key). Both are recorded by the receiver against the
     * import, so a promoted row's provenance survives in the audit trail (R7).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Batch(
        @field:JsonProperty("source_env") @get:JsonProperty("source_env") @param:JsonProperty("source_env")
        val sourceEnv: String,
        @field:JsonProperty("key_fingerprint") @get:JsonProperty("key_fingerprint") @param:JsonProperty("key_fingerprint")
        val keyFingerprint: String,
        @field:JsonProperty("workspace") @get:JsonProperty("workspace") @param:JsonProperty("workspace")
        val workspace: String,
        /** Full template version JSON, in `imports_json` closure order (§10.4 step 1). */
        @field:JsonProperty("templates") @get:JsonProperty("templates") @param:JsonProperty("templates")
        val templates: List<JsonNode> = emptyList(),
        /** Full pipeline JSON, children before parents (§10.4 steps 2–3). */
        @field:JsonProperty("pipelines") @get:JsonProperty("pipelines") @param:JsonProperty("pipelines")
        val pipelines: List<JsonNode> = emptyList(),
    )

    /** What the receiver reports back: what it stored, per kind. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    data class Applied(
        @field:JsonProperty("workspace") @get:JsonProperty("workspace") @param:JsonProperty("workspace")
        val workspace: String,
        @field:JsonProperty("source_env") @get:JsonProperty("source_env") @param:JsonProperty("source_env")
        val sourceEnv: String,
        @field:JsonProperty("templates") @get:JsonProperty("templates") @param:JsonProperty("templates")
        val templates: Int,
        @field:JsonProperty("pipelines") @get:JsonProperty("pipelines") @param:JsonProperty("pipelines")
        val pipelines: Int,
    )
}
