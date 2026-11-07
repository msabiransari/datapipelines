package co.datapipelines.lake.delivery

import co.datapipelines.lake.LakeTestDb
import co.datapipelines.lake.LeaseKind
import co.datapipelines.lake.definitions.LakeDefinitionRepository
import co.datapipelines.persistence.FailureShape
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/** V55 on real Postgres: immutable identities, preserved attempts, conditional transitions and atomic stamps. */
class DeliveryLedgerIntegrationTest {
    private val jdbc = LakeTestDb.jdbc
    private val bounded = JdbcTemplate(LakeTestDb.dataSource).apply { queryTimeout = 1 }
    private val repository = DeliveryLedgerRepository(NamedParameterJdbcTemplate(bounded))
    private val ledger = DeliveryLedger(repository, TransactionTemplate(DataSourceTransactionManager(LakeTestDb.dataSource)))
    private val tableId = UUID.randomUUID()
    private val actor = UUID.fromString("00000000-0000-0000-0000-000000000799")

    @BeforeEach
    fun clean() {
        LakeTestDb.reset()
        jdbc.execute("TRUNCATE lake_delivery_attempts, lake_deliveries, lake_tables_managed, lake_namespaces CASCADE")
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (?, ?, 'Lane 799', 'google', ?) " +
                "ON CONFLICT (id) DO NOTHING",
            actor,
            "lane799@example.com",
            "sub-799",
        )
        val definitions = LakeDefinitionRepository(NamedParameterJdbcTemplate(bounded))
        val namespace = definitions.namespaceId("delivery", actor)
        definitions.insertTable(tableId, namespace, "orders", "{}", actor) shouldBe true
    }

    @Test
    fun `receive is idempotent for its operation and a retry preserves both attempt rows`() {
        val request = request()
        val first = ledger.receive(request).shouldBeInstanceOf<DeliveryReceipt.Accepted>().delivery
        ledger.receive(request).delivery.id shouldBe first.id
        attempts(first.id) shouldBe 1
        val second =
            ledger
                .receive(request.copy(operationId = UUID.randomUUID(), executionId = UUID.randomUUID()))
                .shouldBeInstanceOf<DeliveryReceipt.Accepted>()
                .delivery
        second.id shouldBe first.id
        second.state shouldBe LakeDeliveryState.RECEIVED
        attempts(first.id) shouldBe 2
        outcome(first.operationId) shouldBe "FAILED"
        outcome(second.operationId) shouldBe null
        ledger.beginPublishing(first) shouldBe false
    }

    @Test
    fun `fingerprint is immutable in every state and published same-input admission skips`() {
        val request = request()
        val received = ledger.receive(request).delivery
        assertChanged(request, received)
        ledger.beginPublishing(received) shouldBe true
        val publishing = requireNotNull(repository.find(received.id))
        assertChanged(request, publishing)
        ledger.conclude(publishing, DeliveryConclusion.Published("s3://ledger/metadata/one.json")) shouldBe true
        val published = requireNotNull(repository.find(received.id))
        assertChanged(request, published)
        ledger.receive(request.copy(operationId = UUID.randomUUID())).shouldBeInstanceOf<DeliveryReceipt.AlreadyPublished>()
        attempts(received.id) shouldBe 1
        outcome(received.operationId) shouldBe "PUBLISHED"
    }

    @Test
    fun `publishing and unknown cannot be restarted without settlement`() {
        val request = request()
        val received = ledger.receive(request).delivery
        ledger.beginPublishing(received) shouldBe true
        ledger.receive(request.copy(operationId = UUID.randomUUID())).shouldBeInstanceOf<DeliveryReceipt.NeedsSettlement>()
        val publishing = requireNotNull(repository.find(received.id))
        ledger.conclude(publishing, DeliveryConclusion.Unknown(null)) shouldBe true
        ledger.receive(request.copy(operationId = UUID.randomUUID())).shouldBeInstanceOf<DeliveryReceipt.NeedsSettlement>()
        val unknown = requireNotNull(repository.find(received.id))
        assertChanged(request, unknown)
        ledger.conclude(unknown, DeliveryConclusion.Failed(LakeDeliveryFailurePhase.PUBLISH, null)) shouldBe true
        outcome(received.operationId) shouldBe "FAILED"
        assertChanged(request, requireNotNull(repository.find(received.id)))
        ledger.receive(request.copy(operationId = UUID.randomUUID())).shouldBeInstanceOf<DeliveryReceipt.Accepted>()
        attempts(received.id) shouldBe 2
    }

    @Test
    fun `a stale attempt cannot fail a replacement or stamp its own attempt as completed`() {
        val request = request()
        val old = ledger.receive(request).delivery
        val current = ledger.receive(request.copy(operationId = UUID.randomUUID())).delivery
        ledger.conclude(old, DeliveryConclusion.Failed(LakeDeliveryFailurePhase.VALIDATION, null)) shouldBe false
        repository.find(old.id) shouldBe current
        outcome(current.operationId) shouldBe null
        outcome(old.operationId) shouldBe "FAILED"
        ledger.conclude(current, DeliveryConclusion.Failed(LakeDeliveryFailurePhase.STAGING, null)) shouldBe true
        shouldThrow<IllegalStateException> { ledger.receive(request.copy(operationId = current.operationId)) }
    }

    @Test
    fun `a stale state cannot overwrite a published verdict even with the same operation`() {
        val row = ledger.receive(request()).delivery
        ledger.beginPublishing(row) shouldBe true
        val publishing = requireNotNull(repository.find(row.id))
        ledger.conclude(publishing, DeliveryConclusion.Published("s3://ledger/metadata/two.json")) shouldBe true
        ledger.conclude(publishing, DeliveryConclusion.Unknown(null)) shouldBe false
        repository.find(row.id)?.state shouldBe LakeDeliveryState.PUBLISHED
        outcome(row.operationId) shouldBe "PUBLISHED"
        shouldThrow<IllegalArgumentException> { ledger.conclude(row, DeliveryConclusion.Published("bad")) }
    }

    @Test
    fun `attempt stamp failure rolls the delivery stamp back in the same transaction`() {
        val received = ledger.receive(request()).delivery
        ledger.beginPublishing(received) shouldBe true
        val publishing = requireNotNull(repository.find(received.id))
        jdbc.update("UPDATE lake_delivery_attempts SET outcome = 'FAILED' WHERE operation_id = ?", received.operationId)
        shouldThrow<IllegalStateException> {
            ledger.conclude(publishing, DeliveryConclusion.Published("s3://ledger/metadata/three.json"))
        }
        repository.find(received.id)?.state shouldBe LakeDeliveryState.PUBLISHING
    }

    @Test
    fun `recipe provenance is immutable and cannot be attached by a stale operation`() {
        val old = ledger.receive(request()).delivery
        val current = ledger.receive(request()).delivery
        ledger.beginPublishing(current) shouldBe true
        repository.attachRecipe(old, "old.json", "a".repeat(64)) shouldBe 0
        repository.attachRecipe(current, "current.json", "b".repeat(64)) shouldBe 1
        repository.attachRecipe(current, "overwrite.json", "c".repeat(64)) shouldBe 0
        jdbc.queryForObject(
            "SELECT recipe_key FROM lake_delivery_attempts WHERE operation_id = ?",
            String::class.java,
            current.operationId,
        ) shouldBe "current.json"
    }

    @Test
    fun `held ledger row times out without changing the delivery or attempt`() {
        val row = ledger.receive(request()).delivery
        LakeTestDb.connect().use { holder ->
            holder.autoCommit = false
            holder.prepareStatement("SELECT id FROM lake_deliveries WHERE id = ? FOR UPDATE").use { statement ->
                statement.setObject(1, row.id)
                statement.executeQuery().close()
            }
            val failure =
                shouldThrow<DataAccessException> {
                    ledger.conclude(row, DeliveryConclusion.Failed(LakeDeliveryFailurePhase.VALIDATION, null))
                }
            FailureShape.sqlState(failure) shouldBe "57014"
            holder.rollback()
        }
        repository.find(row.id)?.state shouldBe LakeDeliveryState.RECEIVED
        outcome(row.operationId) shouldBe null
    }

    @Test
    fun `unbounded JDBC is refused at construction`() {
        shouldThrow<IllegalArgumentException> { DeliveryLedgerRepository(NamedParameterJdbcTemplate(LakeTestDb.dataSource)) }
    }

    @Test
    fun `commit evidence comes from the trigger and joins the delivery's own table and attempt`() {
        val row = ledger.receive(request()).delivery
        repository.committedEvidence(row.id) shouldBe null
        val lease = LakeTestDb.lease(LeaseKind.TABLE, "delivery.orders", operationId = row.operationId)
        try {
            LakeTestDb.connect().use { connection ->
                connection
                    .prepareStatement(
                        "SELECT set_config('dp.lease_token', ?, false), set_config('dp.operation_id', ?, false)",
                    ).use { statement ->
                        statement.setString(1, lease.token.toString())
                        statement.setString(2, lease.operationId.toString())
                        statement.executeQuery().close()
                    }
                connection
                    .prepareStatement(
                        "INSERT INTO iceberg_tables (catalog_name, table_namespace, table_name, metadata_location, iceberg_type) " +
                            "VALUES ('lake', 'delivery', 'orders', ?, 'TABLE')",
                    ).use { statement ->
                        statement.setString(1, "s3://ledger/metadata/evidence.json")
                        statement.executeUpdate() shouldBe 1
                    }
            }
            val evidence = requireNotNull(repository.committedEvidence(row.id))
            evidence.operationId shouldBe row.operationId
            evidence.metadataLocation shouldBe "s3://ledger/metadata/evidence.json"
            evidence.revisionSequence shouldBe jdbc.queryForObject("SELECT max(seq) FROM lake_table_revisions", Long::class.java)
            val other = ledger.receive(request().let { it.copy(manifest = it.manifest.copy(key = DeliveryKey("orders", "other"))) })
            repository.committedEvidence(other.delivery.id) shouldBe null
        } finally {
            LakeTestDb.leases.release(lease.operationId)
        }
    }

    private fun assertChanged(
        request: ReceiveDelivery,
        row: LakeDelivery,
    ) {
        val changed = request.copy(manifest = request.manifest.copy(fingerprint = "b".repeat(64)), operationId = UUID.randomUUID())
        ledger.receive(changed).shouldBeInstanceOf<DeliveryReceipt.Changed>()
        repository.find(row.id) shouldBe row
    }

    private fun attempts(id: UUID): Int =
        requireNotNull(
            jdbc.queryForObject(
                "SELECT count(*) FROM lake_delivery_attempts WHERE delivery_id = ?",
                Int::class.java,
                id,
            ),
        )

    private fun outcome(operationId: UUID): String? =
        jdbc.queryForObject(
            "SELECT outcome FROM lake_delivery_attempts WHERE operation_id = ?",
            String::class.java,
            operationId,
        )

    private fun request(): ReceiveDelivery =
        ReceiveDelivery(
            tableId = tableId,
            manifest =
                DeliveryManifest(
                    key = DeliveryKey("orders", "delivery1"),
                    objects = emptyList(),
                    files = emptyList(),
                    format = DeliveryFormat.Csv,
                    fingerprint = "a".repeat(64),
                    totalBytes = 1,
                ),
            operationId = UUID.randomUUID(),
            executionId = UUID.randomUUID(),
            pipelineId = UUID.randomUUID(),
        )
}
