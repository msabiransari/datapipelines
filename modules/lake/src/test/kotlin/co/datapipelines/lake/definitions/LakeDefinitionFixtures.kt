package co.datapipelines.lake.definitions

import com.fasterxml.jackson.databind.json.JsonMapper

/** Builds definition bodies as JSON text, the form the service receives them in. */
internal object LakeDefinitionFixtures {
    private val mapper = JsonMapper.builder().build()

    fun field(
        id: Int,
        name: String,
        type: String = "STRING",
        vararg extra: Pair<String, Any?>,
    ): Map<String, Any?> = linkedMapOf<String, Any?>("id" to id, "name" to name, "type" to type).apply { putAll(extra) }

    fun partition(
        fieldId: Int,
        transform: String,
    ): Map<String, Any?> = linkedMapOf("field_id" to fieldId, "transform" to transform)

    fun body(
        fields: List<Map<String, Any?>>,
        partition: List<Map<String, Any?>>? = null,
        identifiers: List<Int>? = null,
        extra: Map<String, Any?> = emptyMap(),
    ): String {
        val root = linkedMapOf<String, Any?>("fields" to fields)
        partition?.let { root["partition"] = it }
        identifiers?.let { root["identifier_field_ids"] = it }
        root.putAll(extra)
        return mapper.writeValueAsString(root)
    }

    /** `orders` v1: id (required, identifier), customer, amount DECIMAL(12,2), ordered_at TIMESTAMP partitioned by day. */
    val ORDERS_V1: String =
        body(
            listOf(
                field(1, "id", "BIGINTEGER", "required" to true, "doc" to "order id"),
                field(2, "customer", "STRING", "tags" to listOf("pii")),
                field(3, "amount", "DECIMAL", "precision" to 12, "scale" to 2),
                field(4, "ordered_at", "TIMESTAMP", "required" to true),
            ),
            partition = listOf(partition(4, "day")),
            identifiers = listOf(1),
        )

    /** `orders` v2: v1 + an optional `channel` column (id 5) — a COMPATIBLE change. */
    val ORDERS_V2: String =
        body(
            listOf(
                field(1, "id", "BIGINTEGER", "required" to true, "doc" to "order id"),
                field(2, "customer", "STRING", "tags" to listOf("pii")),
                field(3, "amount", "DECIMAL", "precision" to 12, "scale" to 2),
                field(4, "ordered_at", "TIMESTAMP", "required" to true),
                field(5, "channel", "STRING", "doc" to "sales channel"),
            ),
            partition = listOf(partition(4, "day")),
            identifiers = listOf(1),
        )

    /** `orders` v3: v2 + an optional `note` column (id 6). */
    val ORDERS_V3: String =
        body(
            listOf(
                field(1, "id", "BIGINTEGER", "required" to true, "doc" to "order id"),
                field(2, "customer", "STRING", "tags" to listOf("pii")),
                field(3, "amount", "DECIMAL", "precision" to 12, "scale" to 2),
                field(4, "ordered_at", "TIMESTAMP", "required" to true),
                field(5, "channel", "STRING", "doc" to "sales channel"),
                field(6, "note", "STRING"),
            ),
            partition = listOf(partition(4, "day")),
            identifiers = listOf(1),
        )
}
