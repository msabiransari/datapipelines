package co.datapipelines.lake.profiles

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * Reads `retention.keep` (record call 42, brief 956 D2): a list of `{ "period": …, "keep_for": { "n": …, "unit": … } }`,
 * at most one rule per period, each within [KeepRuleLimits]. Every refusal names the key `retention.keep` as
 * `details.path` and the rule's position as `details.index`.
 */
internal object KeepRulesReader {
    private val RULE_KEYS = setOf("period", "keep_for")
    private val DURATION_KEYS = setOf("n", "unit")

    fun read(
        node: JsonNode,
        key: String,
    ): List<KeepRule> {
        if (node !is ArrayNode) throw LakeProfileErrors.invalid(key, LakeProfileDocument.TYPE, "must be a list of keep rules")
        val rules = node.mapIndexed { index, element -> rule(element, key, index) }
        val seen = HashSet<KeepPeriod>()
        rules.forEachIndexed { index, rule ->
            if (!seen.add(rule.period)) {
                throw LakeProfileErrors.invalid(key, "duplicate_period", "at most one rule per period; ${rule.period.wire} repeats", index)
            }
        }
        return rules
    }

    @Suppress("ThrowsCount") // period, unit and each limit breach are distinct catalogued reasons
    private fun rule(
        element: JsonNode,
        key: String,
        index: Int,
    ): KeepRule {
        val obj = objectWith(element, RULE_KEYS, key, index, "a keep rule")
        val periodNode = obj.path("period")
        val period =
            periodNode.takeIf { it.isTextual }?.let { KeepPeriod.fromWire(it.textValue()) }
                ?: throw LakeProfileErrors.invalid(key, "period", "period must be one of ${KeepPeriod.entries.map { it.wire }}", index)
        val duration = objectWith(obj.path("keep_for"), DURATION_KEYS, key, index, "keep_for")
        val unit =
            duration.path("unit").takeIf { it.isTextual }?.let { KeepUnit.fromWire(it.textValue()) }
                ?: throw LakeProfileErrors.invalid(key, "unit", "keep_for.unit must be one of ${KeepUnit.entries.map { it.wire }}", index)
        val n = LakeProfileDocument.whole(duration.path("n"), key, 1, unit.maxN.toLong(), index).toInt()
        val rule = KeepRule(period, KeepDuration(n, unit))
        when (KeepRuleLimits.breach(rule)) {
            KeepRuleLimits.Breach.SHORTER_THAN_PERIOD -> {
                throw LakeProfileErrors.invalid(
                    key,
                    KeepRuleLimits.Breach.SHORTER_THAN_PERIOD.reason,
                    "keep_for ($n ${unit.wire}) must be at least one ${period.wire} long for every ${period.wire}",
                    index,
                )
            }

            KeepRuleLimits.Breach.TOO_MANY_KEEPS -> {
                throw LakeProfileErrors.invalid(
                    key,
                    KeepRuleLimits.Breach.TOO_MANY_KEEPS.reason,
                    "a ${period.wire} rule kept for $n ${unit.wire} asks for ${KeepRuleLimits.liveKeeps(rule)} live keeps; " +
                        "at most ${KeepRuleLimits.MAX_LIVE_KEEPS}",
                    index,
                )
            }

            null -> {}
        }
        return rule
    }

    @Suppress("ThrowsCount") // type, unknown key and missing key are distinct catalogued reasons
    private fun objectWith(
        node: JsonNode,
        keys: Set<String>,
        key: String,
        index: Int,
        what: String,
    ): ObjectNode {
        if (node !is ObjectNode) throw LakeProfileErrors.invalid(key, LakeProfileDocument.TYPE, "$what must be an object", index)
        node.fieldNames().forEach { name ->
            if (name !in keys) throw LakeProfileErrors.invalid(key, LakeProfileDocument.UNKNOWN_KEY, "$what has no key $name", index)
        }
        keys.forEach { name ->
            if (!node.has(name)) throw LakeProfileErrors.invalid(key, LakeProfileDocument.REQUIRED, "$what needs $name", index)
        }
        return node
    }
}
