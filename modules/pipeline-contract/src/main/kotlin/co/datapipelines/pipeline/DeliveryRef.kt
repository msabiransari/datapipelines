package co.datapipelines.pipeline

import com.fasterxml.jackson.annotation.JsonProperty

/** Sender identity of an immutable landing folder; resolved and validated before storage access. */
data class DeliveryRef(
    @field:JsonProperty("feed") @get:JsonProperty("feed") @param:JsonProperty("feed")
    val feed: String = "",
    @field:JsonProperty("id") @get:JsonProperty("id") @param:JsonProperty("id")
    val id: String = "",
)
