package co.datapipelines.scheduler

import com.fasterxml.jackson.databind.JsonNode
import java.util.Locale

/** Optional members keep defaults on create and stored values on edit (#442a). */
data class NotificationSettingsRequest(
    val recipients: JsonNode? = null,
    val events: JsonNode? = null,
) {
    companion object {
        fun fromNode(node: JsonNode?): NotificationSettingsRequest? {
            if (node == null || node.isNull) return null
            if (!node.isObject) NotificationSettings.invalid("notifications", "not_an_object")
            return NotificationSettingsRequest(node.get("recipients"), node.get("events"))
        }
    }
}

/** Validated settings; ONE grammar owns every stored address. No refusal echoes personal data. */
data class NotificationSettings(
    val recipients: List<String>,
    val events: Set<NotificationEvent>,
) {
    companion object {
        private const val MAX_ADDRESS_CHARS = 254

        // WHATWG valid e-mail address production, also used by input type=email.
        private val ADDRESS =
            Regex(
                """^[a-zA-Z0-9.!#${'$'}%&'*+/=?^_`{|}~-]+@""" +
                    """[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?""" +
                    """(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*${'$'}""",
            )

        fun resolve(
            request: NotificationSettingsRequest?,
            current: Schedule?,
            limit: Int,
        ): NotificationSettings {
            val recipients = request?.recipients?.let { recipients(it, limit) } ?: current?.notificationRecipients.orEmpty()
            val events = request?.events?.let(::events) ?: current?.notificationEvents ?: NotificationEvent.DEFAULT
            return NotificationSettings(recipients, events)
        }

        private fun recipients(
            node: JsonNode,
            limit: Int,
        ): List<String> {
            if (!node.isArray) invalid("notifications.recipients", "not_a_list")
            val seen = mutableSetOf<String>()
            val normalized =
                node.mapIndexedNotNull { index, value ->
                    val field = "notifications.recipients[$index]"
                    if (!value.isTextual) invalid(field, "not_a_string")
                    val address = value.asText().trim()
                    if (address.length > MAX_ADDRESS_CHARS) invalid(field, "too_long")
                    if (!ADDRESS.matches(address)) invalid(field, "syntax")
                    val normalized = address.substringBefore('@') + "@" + address.substringAfter('@').lowercase(Locale.ROOT)
                    normalized.takeIf { seen.add(it.lowercase(Locale.ROOT)) }
                }
            if (normalized.size > limit) invalid("notifications.recipients", "too_many")
            return normalized
        }

        private fun events(node: JsonNode): Set<NotificationEvent> {
            if (!node.isArray) invalid("notifications.events", "not_a_list")
            return node
                .mapIndexed { index, value ->
                    val field = "notifications.events[$index]"
                    if (!value.isTextual) invalid(field, "not_a_string")
                    NotificationEvent.fromWire(value.asText()) ?: invalid(field, "unknown_event")
                }.toSet()
        }

        fun invalid(
            field: String,
            reason: String,
        ): Nothing =
            throw ScheduleException(
                ScheduleErrorCodes.NOTIFICATIONS_INVALID,
                "The schedule's notification settings are invalid ($reason).",
                mapOf("field" to field, "reason" to reason),
            )
    }
}

/** Slice 4 part b computes this from the enable flag and mail transport; until then nothing sends. */
object NotificationDelivery {
    val CURRENT: Map<String, String> = mapOf("state" to "off", "reason" to "disabled")
}
