package co.datapipelines.parameters

import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.pipeline.release.ReleaseMutationGuard
import io.kotest.matchers.shouldBe
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit

/** Two real connections: the template disappears AFTER validation and BEFORE the guarded set write. */
class ParameterPinGuardIntegrationTest {
    @Suppress("LongMethod", "NestedBlockDepth") // the ordered two-connection witness, including unconditional latch cleanup
    @ParameterizedTest
    @CsvSource("create,purge", "create,discard", "write,purge", "write,discard", "import,purge", "import,discard")
    fun `the loser refuses an ineligible pin without writing`(
        verb: String,
        mutation: String,
    ) {
        ParametersTestDb.reset()
        val validated = CountDownLatch(1)
        val resume = CountDownLatch(1)
        var armed = false
        val guard =
            ReleaseMutationGuard { workspace, _ ->
                if (armed) {
                    validated.countDown()
                    check(resume.await(10, TimeUnit.SECONDS)) { "template mutation did not finish" }
                }
                ParametersTestDb.jdbc.queryForList(
                    "SELECT id FROM workspaces WHERE id = :id FOR NO KEY UPDATE",
                    mapOf("id" to workspace),
                )
            }
        val h = ParametersHarness(guard = guard)
        val pin =
            h.template(
                "acme/sales/states_of_country.sql",
                "SELECT :country AS value, :country AS display_value",
                CreateLifecycle.DRAFT,
            )
        val document =
            h.document(
                ParameterSetFixtures.setJson(
                    ParameterSetFixtures.countryJson(),
                    ParameterSetFixtures.stateJson().replace("\"version\": 3", "\"version\": ${pin.version}"),
                ),
            )
        val existing = if (verb == "write") h.create(h.document(ParameterSetFixtures.setJson(ParameterSetFixtures.countryJson()))) else null
        val canonical = if (verb == "import") h.service.validateForImport(ParametersTestDb.WORKSPACE, document) else document
        val before = h.jdbc.queryForList("SELECT body_json::text FROM parameter_set_versions", emptyMap<String, Any>())
        armed = true
        val answer =
            CompletableFuture.supplyAsync {
                when (verb) {
                    "create" -> {
                        h.create(document)
                    }

                    "write" -> {
                        h.service.write(
                            ParametersTestDb.WORKSPACE,
                            checkNotNull(existing).record.id,
                            document,
                            existing.detail.bodyHash,
                            ParametersTestDb.AUTHOR,
                            WriteSurface.SESSION,
                        )
                    }

                    "import" -> {
                        h.service.importValidated(
                            ParametersTestDb.WORKSPACE,
                            ParameterSetExport(
                                UUID.randomUUID(),
                                document.name,
                                1,
                                h.repository.computeBodyHash(canonical.body),
                                null,
                                canonical.body,
                            ),
                            ParametersTestDb.AUTHOR,
                        )
                    }

                    else -> {
                        error("unknown verb")
                    }
                }
            }
        try {
            check(validated.await(10, TimeUnit.SECONDS)) { "write did not finish outside validation" }
            ParametersTestDb.rawConnection().use { connection ->
                connection.autoCommit = false
                connection.prepareStatement("SELECT id FROM workspaces WHERE id = ? FOR NO KEY UPDATE").use { statement ->
                    statement.setObject(1, ParametersTestDb.WORKSPACE)
                    statement.executeQuery().close()
                }
                connection.createStatement().use { statement ->
                    if (mutation == "purge") {
                        statement.executeUpdate("DELETE FROM template_versions")
                        statement.executeUpdate("DELETE FROM templates")
                    } else {
                        statement.executeUpdate(
                            "UPDATE template_versions SET status = 'DISCARDED', discarded_at = NOW(), " +
                                "discarded_by = '${ParametersTestDb.AUTHOR}'",
                        )
                    }
                }
                connection.commit()
            }
        } finally {
            resume.countDown()
        }
        val error =
            try {
                answer.get(15, TimeUnit.SECONDS)
                error("the set saved a pin to a $mutation template")
            } catch (error: ExecutionException) {
                error.cause as ParameterSetValidationException
            }
        error.result.failures.map { it.code } shouldBe listOf(ParameterErrorCodes.TEMPLATE_VERSION_NOT_FOUND)
        h.jdbc.queryForList("SELECT body_json::text FROM parameter_set_versions", emptyMap<String, Any>()) shouldBe before
    }
}
