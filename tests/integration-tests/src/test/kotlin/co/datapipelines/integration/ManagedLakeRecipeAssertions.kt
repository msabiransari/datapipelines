package co.datapipelines.integration

import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.springframework.jdbc.core.JdbcTemplate

/** A real completed execution's immutable recipe must contain its two actually resolved SQL artifacts. */
internal object ManagedLakeRecipeAssertions {
    fun complete(
        jdbc: JdbcTemplate,
        delivery: String,
        bucket: String,
    ) {
        val key =
            requireNotNull(
                jdbc.queryForObject(
                    "SELECT a.recipe_key FROM lake_delivery_attempts a JOIN lake_deliveries d ON d.id = a.delivery_id " +
                        "WHERE d.delivery_id = ? AND a.outcome = 'PUBLISHED'",
                    String::class.java,
                    delivery,
                ),
            )
        SharedE2e.s3Client().use { client ->
            val bytes = client.getObjectAsBytes { it.bucket(bucket).key(key) }.asByteArray()
            val recipe = ObjectMapper().readTree(bytes)
            recipe.path("unresolved_nodes").size() shouldBe 0
            recipe.path("exact_replay_possible").asBoolean() shouldBe true
            recipe.path("templates").size() shouldBe 2
        }
    }
}
