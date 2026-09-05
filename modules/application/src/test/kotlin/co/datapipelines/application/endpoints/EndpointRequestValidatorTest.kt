package co.datapipelines.application.endpoints

import co.datapipelines.pipeline.Parameter
import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.LogicalType
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * The §5.3 request validator.
 *
 * The headline property — **every defect in one response** — gets its own test with three
 * simultaneous, DIFFERENT defects, because the failure mode this class exists to prevent is the
 * ordinary one: a validator that returns on its first problem, forcing a client's developer
 * through one round trip per typo.
 *
 * The rest of the suite is organised around the two judgment calls that could each be made
 * wrongly in a way no happy-path test would notice: strictness about unknown parameters (the
 * execute body deliberately tolerates them; this surface must not), and lifting URL text into
 * each declared type's wire form (a `TextNode` for an `INTEGER` would be refused with a message
 * about JSON, which is useless to someone holding a URL).
 */
class EndpointRequestValidatorTest {
    @Test
    fun `path variables and query parameters bind together`() {
        val outcome =
            validator().validate(
                request(pathVariables = mapOf("borough" to "Manhattan"), query = mapOf("start_date" to listOf("2024-01-01"))),
            )

        val valid = outcome.shouldBeInstanceOf<EndpointRequestValidator.Outcome.Valid>()
        assertAll(
            { valid.parameters["borough"]?.asText() shouldBe "Manhattan" },
            { valid.parameters["start_date"]?.asText() shouldBe "2024-01-01" },
        )
    }

    @Test
    fun `a request with three defects yields exactly three entries`() {
        // The falsification the design asks for. Three DIFFERENT defect classes at once: an
        // unknown parameter, a missing required one, and a bad type. A fail-fast validator
        // reports one of these; this must report all three.
        val outcome =
            validator().validate(
                request(
                    pathVariables = emptyMap(),
                    query = mapOf("nope" to listOf("1"), "limit" to listOf("abc")),
                ),
            )

        val invalid = outcome.shouldBeInstanceOf<EndpointRequestValidator.Outcome.Invalid>()
        withClue("defects: ${invalid.defects}") {
            invalid.defects.size shouldBe 3
        }
        invalid.defects.map { it.parameter to it.code } shouldContainExactlyInAnyOrder
            listOf(
                "nope" to PipelineErrorCodes.EndpointRequest.PARAMETER_UNKNOWN,
                "limit" to PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE,
                "borough" to PipelineErrorCodes.Execution.PARAMETER_REQUIRED,
            )
    }

    @Test
    fun `an unknown query parameter is refused, and the message says what is accepted`() {
        // Strictness on purpose: ParameterBinder IGNORES undeclared inputs, which is right for
        // the execute body and wrong here — `?start_dt=...` would silently run the default and
        // return plausible, wrong rows.
        val outcome = validator().validate(request(pathVariables = mapOf("borough" to "Q"), query = mapOf("start_dt" to listOf("x"))))

        val invalid = outcome.shouldBeInstanceOf<EndpointRequestValidator.Outcome.Invalid>()
        assertAll(
            { invalid.defects.single().code shouldBe PipelineErrorCodes.EndpointRequest.PARAMETER_UNKNOWN },
            { invalid.defects.single().message shouldContain "start_date" },
        )
    }

    @Test
    fun `a repeated query key is refused`() {
        val outcome =
            validator().validate(
                request(pathVariables = mapOf("borough" to "Q"), query = mapOf("start_date" to listOf("2024-01-01", "2024-02-01"))),
            )

        outcome
            .shouldBeInstanceOf<EndpointRequestValidator.Outcome.Invalid>()
            .defects
            .single()
            .code shouldBe
            PipelineErrorCodes.EndpointRequest.PARAMETER_REPEATED
    }

    @Test
    fun `a query parameter cannot override a path variable`() {
        // The URL says which borough. Letting the query string win would let a caller address a
        // different row than the path they requested.
        val outcome =
            validator().validate(
                request(pathVariables = mapOf("borough" to "Manhattan"), query = mapOf("borough" to listOf("Queens"))),
            )

        outcome
            .shouldBeInstanceOf<EndpointRequestValidator.Outcome.Invalid>()
            .defects
            .single()
            .code shouldBe
            PipelineErrorCodes.EndpointRequest.PARAMETER_REPEATED
    }

    @Test
    fun `a required parameter with no value is refused with the existing code`() {
        val outcome = validator().validate(request(pathVariables = emptyMap(), query = emptyMap()))

        outcome.shouldBeInstanceOf<EndpointRequestValidator.Outcome.Invalid>().defects.map { it.code } shouldContainExactly
            listOf(PipelineErrorCodes.Execution.PARAMETER_REQUIRED)
    }

    @Test
    fun `an optional parameter with a default may simply be absent`() {
        validator()
            .validate(request(pathVariables = mapOf("borough" to "Q"), query = emptyMap()))
            .shouldBeInstanceOf<EndpointRequestValidator.Outcome.Valid>()
    }

    @Test
    fun `URL text is lifted into each declared type's wire form`() {
        // INTEGER, DECIMAL and BOOLEAN are JSON numbers/booleans on the wire; everything else is
        // a JSON string already. Without the lift, `?limit=10` would be refused as "INTEGER takes
        // a JSON number" — true, and useless to someone holding a URL.
        val outcome =
            typed(
                "n" to Parameter(LogicalType.INTEGER),
                "d" to Parameter(LogicalType.DECIMAL),
                "b" to Parameter(LogicalType.BOOLEAN),
                "s" to Parameter(LogicalType.STRING),
                "day" to Parameter(LogicalType.DATE),
            ).validate(
                request(
                    pathVariables = emptyMap(),
                    query =
                        mapOf(
                            "n" to listOf("10"),
                            "d" to listOf("1.5"),
                            "b" to listOf("true"),
                            "s" to listOf("hello"),
                            "day" to listOf("2024-01-01"),
                        ),
                ),
            )

        val valid = outcome.shouldBeInstanceOf<EndpointRequestValidator.Outcome.Valid>()
        assertAll(
            { valid.parameters["n"]?.isNumber shouldBe true },
            { valid.parameters["d"]?.isNumber shouldBe true },
            { valid.parameters["b"]?.isBoolean shouldBe true },
            { valid.parameters["s"]?.isTextual shouldBe true },
            { valid.parameters["day"]?.isTextual shouldBe true },
        )
    }

    @Test
    fun `a boolean takes only true or false — never a truthy-looking string`() {
        // Kotlin's `toBoolean` maps every other string to FALSE, so a `?dry_run=yes` would run
        // for real while the caller believed it had asked for a dry run.
        val outcome =
            typed("b" to Parameter(LogicalType.BOOLEAN))
                .validate(request(pathVariables = emptyMap(), query = mapOf("b" to listOf("yes"))))

        outcome
            .shouldBeInstanceOf<EndpointRequestValidator.Outcome.Invalid>()
            .defects
            .single()
            .code shouldBe
            PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE
    }

    @Test
    fun `a malformed date keeps the existing coercion message`() {
        // DATE stays textual, so ParameterCoercion judges it and its tested message survives —
        // the lift deliberately does not re-implement date parsing.
        val outcome =
            validator().validate(
                request(pathVariables = mapOf("borough" to "Q"), query = mapOf("start_date" to listOf("bogus"))),
            )

        val invalid = outcome.shouldBeInstanceOf<EndpointRequestValidator.Outcome.Invalid>()
        assertAll(
            { invalid.defects.size shouldBe 1 },
            { invalid.defects.single().code shouldBe PipelineErrorCodes.Execution.INVALID_PARAMETER_TYPE },
            { invalid.defects.single().message shouldContain "YYYY-MM-DD" },
        )
    }

    @Test
    fun `a value over 4 KB is refused`() {
        val outcome =
            validator().validate(
                request(pathVariables = mapOf("borough" to "x".repeat(4 * 1024 + 1)), query = emptyMap()),
            )

        outcome
            .shouldBeInstanceOf<EndpointRequestValidator.Outcome.Invalid>()
            .defects
            .single()
            .code shouldBe
            PipelineErrorCodes.EndpointRequest.VALUE_TOO_LARGE
    }

    @Test
    fun `a path variable the version does not declare is its own code`() {
        // Distinct from an unknown QUERY parameter: this one means the endpoint's released
        // version changed underneath a published path, which is an operator's problem, not the
        // caller's.
        val outcome = validator().validate(request(pathVariables = mapOf("ghost" to "1"), query = emptyMap()))

        outcome.shouldBeInstanceOf<EndpointRequestValidator.Outcome.Invalid>().defects.map { it.code } shouldContainExactlyInAnyOrder
            listOf(PipelineErrorCodes.Endpoint.PATH_VARIABLE_UNKNOWN, PipelineErrorCodes.Execution.PARAMETER_REQUIRED)
    }

    @Test
    fun `an unsatisfiable Accept is a 406, not one more entry in the 400`() {
        // A negotiation failure about the RESPONSE, not a defect in the request's data — so it
        // does not join the collected list, and it does not suppress it either.
        validator()
            .validate(request(pathVariables = mapOf("borough" to "Q"), query = emptyMap(), accept = "text/csv"))
            .shouldBeInstanceOf<EndpointRequestValidator.Outcome.Unacceptable>()
    }

    @Test
    fun `wildcard, absent and json Accept are all served`() {
        listOf(null, "", "*/*", "application/json", "application/json;q=0.9", "text/html,*/*;q=0.8").forEach { accept ->
            withClue("Accept: $accept") {
                validator()
                    .validate(request(pathVariables = mapOf("borough" to "Q"), query = emptyMap(), accept = accept))
                    .shouldBeInstanceOf<EndpointRequestValidator.Outcome.Valid>()
            }
        }
    }

    @Test
    fun `the TTL and page-rows headers are clamped, and garbage reads as absent`() {
        val valid = { ttl: String?, rows: String? ->
            validator()
                .validate(request(pathVariables = mapOf("borough" to "Q"), query = emptyMap(), ttl = ttl, pageRows = rows))
                .shouldBeInstanceOf<EndpointRequestValidator.Outcome.Valid>()
        }

        assertAll(
            { valid(null, null).ttlSeconds shouldBe 300L },
            { valid(null, null).pageRows shouldBe 1000 },
            { valid("10", null).ttlSeconds shouldBe 60L },
            { valid("99999", null).ttlSeconds shouldBe 3600L },
            { valid(null, "0").pageRows shouldBe 1 },
            { valid(null, "999999").pageRows shouldBe 100_000 },
            // Unparseable is treated as absent: the server clamps anyway, so the worst outcome of
            // ignoring garbage is the documented default.
            { valid("abc", "abc").ttlSeconds shouldBe 300L },
            { valid("abc", "abc").pageRows shouldBe 1000 },
        )
    }

    private fun validator() =
        typed(
            "borough" to Parameter(LogicalType.STRING, required = true),
            "start_date" to Parameter(LogicalType.DATE, default = MAPPER.readTree("\"2024-01-01\"")),
            "limit" to Parameter(LogicalType.INTEGER, default = MAPPER.readTree("100")),
        )

    private fun typed(vararg declared: Pair<String, Parameter>) = EndpointRequestValidator(declared.toMap(), LIMITS)

    private fun request(
        pathVariables: Map<String, String>,
        query: Map<String, List<String>>,
        accept: String? = null,
        ttl: String? = null,
        pageRows: String? = null,
    ) = EndpointRequestValidator.Request(pathVariables, query, accept, ttl, pageRows)

    private companion object {
        val MAPPER: JsonMapper = JsonMapper.builder().build()

        val LIMITS =
            EndpointRequestValidator.Limits(
                ttlMinSeconds = 60,
                ttlMaxSeconds = 3600,
                ttlDefaultSeconds = 300,
                pageMaxRows = 100_000,
                pageDefaultRows = 1000,
            )
    }
}
