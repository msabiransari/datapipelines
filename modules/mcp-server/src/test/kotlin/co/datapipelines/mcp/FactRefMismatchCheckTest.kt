package co.datapipelines.mcp

import co.datapipelines.datasources.semantics.FactRef
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * 125 §B, kinded by 129 §B — the tokenizer half of the `semantics.ref_mismatch` check: what
 * counts as a table reference in free text and what stays prose, which table the tool ADDS on an
 * exact match, and which near-miss it refuses. The MCP-level arms (through the real tool, with a
 * catalog listing) are in [SemanticsToolsTest].
 */
class FactRefMismatchCheckTest {
    private val catalog = listOf("orders", "order_items", "customers")
    private val refs = listOf(FactRef(null, "orders", "amount"))

    @Test
    fun `prose with legitimate underscores does not fire - row_count, as_of, per_day`() {
        shouldNotThrowAny {
            FactRefMismatchCheck.check(
                "warehouse",
                listOf("amount is a row_count-weighted average; the window closes at the as_of date, aggregated per_day"),
                refs,
                catalog,
            )
        }
    }

    @Test
    fun `a listed table the refs carry does not fire - text and refs agree`() {
        shouldNotThrowAny {
            FactRefMismatchCheck.check("warehouse", listOf("orders.amount is in cents, never dollars"), refs, catalog)
        }
    }

    @Test
    fun `a listed table spelled exactly but missing from refs is returned as the ref to add - not refused`() {
        val missing =
            FactRefMismatchCheck.check(
                "warehouse",
                listOf("customers carry the credit limit that orders.amount eats"),
                refs,
                catalog,
            )
        missing shouldBe listOf("customers")
    }

    @Test
    fun `every exact-missing table is returned once, in first-seen order, in the catalog's spelling`() {
        val missing =
            FactRefMismatchCheck.check(
                "warehouse",
                listOf("CUSTOMERS and order_items, then customers again over the whole window"),
                refs,
                catalog,
            )
        missing shouldBe listOf("customers", "order_items")
    }

    @Test
    fun `a near-miss within edit distance two refuses naming the nearest listed table`() {
        val thrown =
            shouldThrow<DatapipelinesException> {
                // `order_itemz` is one edit from `order_items`; the refs are the CORRECT tables — the text is what lies.
                FactRefMismatchCheck.check(
                    "warehouse",
                    listOf("order_itemz joins to orders on order_id"),
                    listOf(FactRef(null, "order_items", null), FactRef(null, "orders", null)),
                    catalog,
                )
            }
        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Semantics.REF_MISMATCH },
            { thrown.message shouldContain "'order_itemz'" },
            { thrown.message shouldContain "did you mean 'order_items'" },
            { thrown.details["suggestion"] shouldBe "order_items" },
        )
    }

    @Test
    fun `an exact-missing token never rides a refused record - the near-miss arm wins`() {
        shouldThrow<DatapipelinesException> {
            // `customers` would be a legal add, but `order_itemz` is a lie — the record is refused whole.
            FactRefMismatchCheck.check(
                "warehouse",
                listOf("customers carry the limits order_itemz claims to join"),
                refs,
                catalog,
            )
        }.code shouldBe PipelineErrorCodes.Semantics.REF_MISMATCH
    }

    @Test
    fun `an underscore token further than two edits from everything is prose, not a reference`() {
        shouldNotThrowAny {
            // `daily_totals` is 5+ edits from every listed table — a description's word, not a table.
            FactRefMismatchCheck.check("warehouse", listOf("the daily_totals rollup never double-counts"), refs, catalog)
        }
    }

    @Test
    fun `a misspelled token the refs themselves carry is left to the recorder's ref_unresolved`() {
        shouldNotThrowAny {
            // Text and refs agree on `order_itemz`; whether that ref resolves is ref_unresolved's job.
            FactRefMismatchCheck.check(
                "warehouse",
                listOf("order_itemz joins on order_id"),
                listOf(FactRef(null, "order_itemz", null)),
                catalog,
            )
        }
    }

    @Test
    fun `the evidence_summary is scanned too`() {
        val missing =
            FactRefMismatchCheck.check(
                "warehouse",
                listOf("amount is in cents", "seen in customers over the whole window"),
                refs,
                catalog,
            )
        missing shouldBe listOf("customers")
    }

    @Test
    fun `no catalog listing means no check - an empty registry cannot disagree`() {
        shouldNotThrowAny {
            FactRefMismatchCheck.check("warehouse", listOf("order_itemz anything at all"), emptyList(), emptyList())
        }
    }
}
