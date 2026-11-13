package co.datapipelines.lake.definitions

import co.datapipelines.lake.delivery.DeliverySettlementHarness
import co.datapipelines.lake.delivery.ScriptedFileIo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.UUID

/**
 * #822 section B: the settlement sweep keeps its per-table catch (`LakeSettlementSweep.recover`). `verifyApplying` still
 * throws (the apply path no longer does), so a table whose verification throws must not keep the NEXT table of the
 * same tick from being settled. The failing table is the first of the tick's page (`ORDER BY id`), so the case can
 * only pass when the sweep went on past it.
 */
class LakeSweepPerTableCatchIntegrationTest {
    private val h = DeliverySettlementHarness()

    @BeforeEach
    fun clean() {
        dropTrigger()
        h.reset()
    }

    @AfterEach
    fun close() {
        dropTrigger()
        h.close()
    }

    @Test
    fun `a table whose verification throws does not keep the next table from being settled in the same tick`() {
        val other = h.addTable("returns")
        val names = mapOf(h.tableId to "orders", other to "returns")
        val (first, second) = h.jdbc.queryForList("SELECT id FROM lake_tables_managed ORDER BY id", UUID::class.java)
        names.forEach { (id, name) -> h.applyingWithEvidence(id, name) }
        // The first table's applied stamp is refused by the database, inside verifyApplying, on every visit.
        h.jdbc.execute(
            "CREATE OR REPLACE FUNCTION $TRIGGER() RETURNS trigger LANGUAGE plpgsql AS " +
                "\$\$ BEGIN RAISE EXCEPTION 'injected 822 sweep fault'; END \$\$",
        )
        h.jdbc.execute(
            "CREATE TRIGGER $TRIGGER BEFORE UPDATE ON lake_tables_managed FOR EACH ROW " +
                "WHEN (NEW.id = '$first'::uuid AND NEW.apply_state = 'applied') EXECUTE FUNCTION $TRIGGER()",
        )

        h.stallableSweep(ScriptedFileIo(h.metadataJson())).runOnce()

        println("(822 sweep) first ${names[first]} = ${h.applyState(first)}, second ${names[second]} = ${h.applyState(second)}")
        h.applyState(first) shouldBe LakeApplyState.APPLYING
        h.applyState(second) shouldBe LakeApplyState.APPLIED
    }

    private fun dropTrigger() = h.jdbc.execute("DROP TRIGGER IF EXISTS $TRIGGER ON lake_tables_managed")

    private companion object {
        const val TRIGGER = "lane822_sweep_fault"
    }
}
