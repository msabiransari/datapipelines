package co.datapipelines.application.pipelines

import co.datapipelines.application.pipelines.PipelineInputResolver.Refusal
import co.datapipelines.application.pipelines.PipelineInputResolver.Result
import co.datapipelines.executor.ExecutionReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.ZoneId

/**
 * The pipeline input resolver's decisions (design revision §5.2, §9.5 [3]): the record's New York
 * example (the frozen reference, not the actual start), the DST fall-back day, a zone east of UTC,
 * the literal-STRING TODAY that is never a keyword, and every refusal reason including the
 * conflict. `ParameterBinder` runs AFTER this on the resolved map — its refusals are the execute
 * path's own, already covered there.
 */
class PipelineInputResolverTest {
    private val resolver = PipelineInputResolver()
    private val mapper = ObjectMapper()

    private val declaredDates = mapOf("as_of_date" to "DATE", "previous_date" to "DATE")

    private fun bindings(json: String): Map<String, com.fasterxml.jackson.databind.JsonNode> =
        mapper.readTree(json).properties().associate { it.key to it.value }

    // ------------------------------------------------------------------ the clock

    @Test
    fun `the record New York example - due 2355 on the 22nd, started 0005 on the 23rd, TODAY is the 22nd`() {
        val reference = ExecutionReference(Instant.parse("2026-09-23T03:55:00Z"), ZoneId.of("America/New_York"))

        val resolved =
            resolver.resolve(
                declaredDates,
                emptyMap(),
                bindings("""{"as_of_date":{"source":"keyword","name":"TODAY"},"previous_date":{"source":"keyword","name":"YESTERDAY"}}"""),
                reference,
            )

        (resolved as Result.Resolved).parameters shouldBe
            mapOf(
                "as_of_date" to mapper.readTree("\"2026-09-22\""),
                "previous_date" to mapper.readTree("\"2026-09-21\""),
            )
    }

    @Test
    fun `the DST fall-back day - YESTERDAY is the calendar day before, across the 25-hour day`() {
        // 2026-11-01 01:30 EDT (the hour that happens once) — the day before is 25 hours earlier
        // on the clock, and still exactly one calendar day earlier.
        val reference = ExecutionReference(Instant.parse("2026-11-01T05:30:00Z"), ZoneId.of("America/New_York"))

        val resolved =
            resolver.resolve(
                declaredDates,
                emptyMap(),
                bindings("""{"previous_date":{"source":"keyword","name":"YESTERDAY"}}"""),
                reference,
            )

        (resolved as Result.Resolved).parameters["previous_date"] shouldBe mapper.readTree("\"2026-10-31\"")
    }

    @Test
    fun `a zone east of UTC - the zone's date, never the UTC date`() {
        // 2026-09-22T20:00Z is 01:30 on the 23rd in Kolkata (UTC+05:30): TODAY is the 23rd.
        val reference = ExecutionReference(Instant.parse("2026-09-22T20:00:00Z"), ZoneId.of("Asia/Kolkata"))

        val resolved =
            resolver.resolve(
                declaredDates,
                emptyMap(),
                bindings("""{"as_of_date":{"source":"keyword","name":"TODAY"}}"""),
                reference,
            )

        (resolved as Result.Resolved).parameters["as_of_date"] shouldBe mapper.readTree("\"2026-09-23\"")
    }

    // ------------------------------------------------------------------ literals stay literals

    @Test
    fun `a literal STRING TODAY in parameters reaches the map as the string, and a literal binding passes through`() {
        val reference = ExecutionReference(Instant.parse("2026-09-23T03:55:00Z"), ZoneId.of("America/New_York"))
        val literals = mapOf("label" to mapper.readTree("\"TODAY\""))

        val resolved =
            resolver.resolve(
                declaredDates + ("label" to "STRING"),
                literals,
                bindings("""{"as_of_date":{"source":"literal","value":"TODAY"}}"""),
                reference,
            )

        (resolved as Result.Resolved).parameters shouldBe
            mapOf(
                "label" to mapper.readTree("\"TODAY\""),
                "as_of_date" to mapper.readTree("\"TODAY\""),
            )
    }

    // ------------------------------------------------------------------ the refusals

    @Test
    fun `a bound name the version does not declare is unknown_parameter`() {
        val refused =
            resolver.resolve(declaredDates, emptyMap(), bindings("""{"nope":{"source":"keyword","name":"TODAY"}}"""), reference())

        (refused as Result.Refused).refusal shouldBe
            Refusal.Invalid(Refusal.Reason.UNKNOWN_PARAMETER, "nope", (refused.refusal as Refusal.Invalid).message)
    }

    @Test
    fun `a keyword on a non-DATE parameter is type_mismatch`() {
        val refused =
            resolver.resolve(
                declaredDates + ("label" to "STRING"),
                emptyMap(),
                bindings("""{"label":{"source":"keyword","name":"TODAY"}}"""),
                reference(),
            )

        (refused as Result.Refused).refusal shouldBe
            Refusal.Invalid(Refusal.Reason.TYPE_MISMATCH, "label", (refused.refusal as Refusal.Invalid).message)
    }

    @Test
    fun `an unknown keyword, an unknown source and a literal without a value are refused with their reasons`() {
        val unknownKeyword =
            resolver.resolve(
                declaredDates,
                emptyMap(),
                bindings("""{"as_of_date":{"source":"keyword","name":"TOMORROW"}}"""),
                reference(),
            )
        (unknownKeyword as Result.Refused).refusal shouldBe
            Refusal.Invalid(Refusal.Reason.UNKNOWN_KEYWORD, "as_of_date", (unknownKeyword.refusal as Refusal.Invalid).message)

        val unknownSource = resolver.resolve(declaredDates, emptyMap(), bindings("""{"as_of_date":{"source":"calculator"}}"""), reference())
        (unknownSource as Result.Refused).refusal shouldBe
            Refusal.Invalid(Refusal.Reason.UNKNOWN_SOURCE, "as_of_date", (unknownSource.refusal as Refusal.Invalid).message)

        val literalInvalid = resolver.resolve(declaredDates, emptyMap(), bindings("""{"as_of_date":{"source":"literal"}}"""), reference())
        (literalInvalid as Result.Refused).refusal shouldBe
            Refusal.Invalid(Refusal.Reason.LITERAL_INVALID, "as_of_date", (literalInvalid.refusal as Refusal.Invalid).message)
    }

    @Test
    fun `a keyword with no reference is no_reference - save time has a structure but no clock`() {
        val refused =
            resolver.resolve(declaredDates, emptyMap(), bindings("""{"as_of_date":{"source":"keyword","name":"TODAY"}}"""), null)

        (refused as Result.Refused).refusal shouldBe
            Refusal.Invalid(Refusal.Reason.NO_REFERENCE, "as_of_date", (refused.refusal as Refusal.Invalid).message)
    }

    @Test
    fun `the same name in parameters and parameter_bindings is the conflict - no invented precedence`() {
        val refused =
            resolver.resolve(
                declaredDates,
                mapOf("as_of_date" to mapper.readTree("\"2026-01-01\"")),
                bindings("""{"as_of_date":{"source":"keyword","name":"TODAY"}}"""),
                reference(),
            )

        (refused as Result.Refused).refusal shouldBe
            Refusal.Conflict("as_of_date", (refused.refusal as Refusal.Conflict).message)
    }

    @Test
    fun `names are judged in sorted order - the first refusal is deterministic`() {
        val refused =
            resolver.resolve(
                declaredDates,
                emptyMap(),
                bindings(
                    """{"previous_date":{"source":"keyword","name":"TODAY"},"as_of_date":{"source":"keyword","name":"TODAY"}}""",
                ),
                null,
            )

        (refused as Result.Refused).refusal.parameter shouldBe "as_of_date"
    }

    @Test
    fun `no bindings resolves to the literals unchanged`() {
        val literals = mapOf("as_of_date" to mapper.readTree("\"2026-01-01\""))

        val resolved = resolver.resolve(declaredDates, literals, null, reference())

        (resolved as Result.Resolved).parameters shouldBe literals
    }

    @Test
    fun `every echoed client string is clipped and control-safe (#269)`() {
        val long = "b".repeat(200)
        val clipped = "b".repeat(64) + "…"

        // unknown_parameter: the binding NAME, in both the message and the details' parameter.
        val unknown =
            resolver.resolve(
                declaredDates,
                emptyMap(),
                bindings("""{"$long":{"source":"keyword","name":"TODAY"}}"""),
                reference(),
            ) as Result.Refused
        unknown.refusal.parameter shouldBe clipped
        unknown.refusal.message.contains(long) shouldBe false

        // unknown_keyword: the echoed KEYWORD — with a control character, U+FFFD, never a newline.
        // (Built as a node: a raw CR is not legal JSON text, which is the point of the sanitiser.)
        val evilKeyword = "TO" + '\r' + "DAY" + "x".repeat(100)
        val keyword =
            resolver.resolve(
                declaredDates,
                emptyMap(),
                mapOf(
                    "as_of_date" to
                        mapper
                            .createObjectNode()
                            .put("source", "keyword")
                            .put("name", evilKeyword),
                ),
                reference(),
            ) as Result.Refused
        (keyword.refusal as Refusal.Invalid).message.contains('\r') shouldBe false
        keyword.refusal.message.contains("x".repeat(100)) shouldBe false

        // unknown_source: the echoed SOURCE.
        val evilSource = "s".repeat(200)
        val source =
            resolver.resolve(
                declaredDates,
                emptyMap(),
                bindings("""{"as_of_date":{"source":"$evilSource"}}"""),
                reference(),
            ) as Result.Refused
        source.refusal.message.contains(evilSource) shouldBe false
        source.refusal.message.contains("s".repeat(64)) shouldBe true
    }

    private fun reference() = ExecutionReference(Instant.parse("2026-09-23T03:55:00Z"), ZoneId.of("America/New_York"))
}
