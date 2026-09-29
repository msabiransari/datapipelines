package co.datapipelines.visualization

import com.fasterxml.jackson.core.JsonProcessingException
import com.fasterxml.jackson.databind.JsonNode

/**
 * The bind step both readers end with, AFTER their pre-scan passed: the document's `name` is split off (it is
 * outside the body — `chk_*_versions_body`), the rest is bound by [ArtifactJson]'s strict mapper, and a bind
 * exception is still the family's `body_invalid` — a refusal the author can read, never a 500. The pre-scan's
 * key and type tables are the binding's own, so reaching the catch means they drifted apart.
 *
 * The copy is ONE level deep on purpose: the top-level members are shared, never deep-copied, so a raw value's
 * own depth costs this nothing (the parameter-set reader's rule — the host stack is never the bound).
 */
internal object DocumentBinding {
    fun <B : Any> bind(
        tree: JsonNode,
        bodyType: Class<B>,
        bodyInvalid: String,
    ): ReadOutcome<Pair<String, B>> {
        val stripped = ArtifactJson.mapper.createObjectNode()
        tree.properties().forEach { (key, value) -> if (key != NAME) stripped.set<JsonNode>(key, value) }
        return try {
            ReadOutcome.Read(tree.get(NAME).asText() to ArtifactJson.mapper.treeToValue(stripped, bodyType))
        } catch (e: JsonProcessingException) {
            val failures = ArtifactFailures(bodyInvalid)
            failures.add(
                bodyInvalid,
                "",
                "The document does not bind: ${e.originalMessage.safeEcho()}",
                mapOf("reason" to JsonScan.REASON_WRONG_TYPE),
            )
            ReadOutcome.Refused(failures.toResult())
        }
    }

    private const val NAME = "name"
}
