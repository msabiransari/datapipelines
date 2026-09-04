package co.datapipelines.application.datasources

import co.datapipelines.pipeline.PipelineErrorCodes
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.json.JsonMapper
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll

/**
 * [DatasourcePayloadBinder] — the ONE reader of the datasources.md §3.1 body, for REST §9.1/§9.4
 * and the `datasources_create` MCP tool alike.
 *
 * Every rejection here is a catalogued §13 code carried on a plain [DatapipelinesException]: this
 * layer sits below both surfaces, so it raises no `ApiException` and no MCP wire type, and REST's
 * handler maps the base type by CODE to exactly the status the inlined controller produced.
 *
 * The create-path happy cases live with the service that owns them
 * ([DatasourceCreateServiceTest]); what is here is the rejection surface and the UPDATE flavour,
 * which no other suite exercises directly.
 */
class DatasourcePayloadBinderTest {
    private val mapper = JsonMapper.builder().build()

    private fun json(raw: String): JsonNode = mapper.readTree(raw)

    private val complete =
        """{"name":"pg_prod","dialect":"POSTGRES","jdbc_url":"jdbc:postgresql://db:5432/app",
           "username":"readonly","password":"s3cret"}"""

    @Test
    fun `update binds without a password and takes the name from the path, not the body`() {
        // §11.1 — the name is immutable, so the path wins and a body `name` cannot rename a row.
        val bound =
            DatasourcePayloadBinder.bind(
                json("""{"name":"ignored","dialect":"H2","jdbc_url":"jdbc:h2:mem:x","username":"sa"}"""),
                requirePassword = false,
                pathName = "pg_prod",
            )

        assertAll(
            { bound.name shouldBe "pg_prod" },
            { bound.dialect shouldBe Dialect.H2 },
            // Absent password on update = "keep the stored one", so it binds to null, not "".
            { bound.password.shouldBeNull() },
        )
    }

    @Test
    fun `a blank password on create is password_missing, exactly like an absent one`() {
        val thrown =
            shouldThrow<DatapipelinesException> {
                DatasourcePayloadBinder.bind(
                    json("""{"name":"n","dialect":"H2","jdbc_url":"jdbc:h2:mem:x","username":"sa","password":""}"""),
                    requirePassword = true,
                )
            }

        thrown.code shouldBe PipelineErrorCodes.Datasource.PASSWORD_MISSING
    }

    @Test
    fun `each missing required field names itself in the 400`() {
        val cases =
            mapOf(
                "name" to """{"dialect":"H2","jdbc_url":"jdbc:h2:mem:x","username":"sa","password":"p"}""",
                "dialect" to """{"name":"n","jdbc_url":"jdbc:h2:mem:x","username":"sa","password":"p"}""",
                "jdbc_url" to """{"name":"n","dialect":"H2","username":"sa","password":"p"}""",
                "username" to """{"name":"n","dialect":"H2","jdbc_url":"jdbc:h2:mem:x","password":"p"}""",
            )

        assertAll(
            cases.map { (field, raw) ->
                {
                    val thrown =
                        shouldThrow<DatapipelinesException> {
                            DatasourcePayloadBinder.bind(json(raw), requirePassword = true)
                        }
                    thrown.code shouldBe PipelineErrorCodes.Datasource.PROPERTIES_INVALID
                    thrown.details["field"] shouldBe field
                }
            },
        )
    }

    @Test
    fun `an unknown dialect is dialect_invalid and the echoed value is length-capped`() {
        val longToken = "X".repeat(200)

        val thrown =
            shouldThrow<DatapipelinesException> {
                DatasourcePayloadBinder.bind(
                    json("""{"name":"n","dialect":"$longToken","jdbc_url":"jdbc:h2:mem:x","username":"sa","password":"p"}"""),
                    requirePassword = true,
                )
            }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Datasource.DIALECT_INVALID },
            // Bounded echo: an operator-supplied value never reproduces itself whole into a log.
            { (thrown.details["dialect"] as String).length shouldBe 32 },
        )
    }

    @Test
    fun `a dialect token is matched case-insensitively and trimmed`() {
        DatasourcePayloadBinder
            .bind(
                json("""{"name":"n","dialect":" postgres ","jdbc_url":"jdbc:postgresql://d/x","username":"sa","password":"p"}"""),
                requirePassword = true,
            ).dialect shouldBe Dialect.POSTGRES
    }

    @Test
    fun `a non-boolean flag is a payload-shape 400 naming the field`() {
        val thrown = shouldThrow<DatapipelinesException> { DatasourcePayloadBinder.booleanFlag(json("""{"global":"yes"}"""), "global") }

        assertAll(
            { thrown.code shouldBe PipelineErrorCodes.Datasource.PROPERTIES_INVALID },
            { thrown.details["field"] shouldBe "global" },
        )
    }

    @Test
    fun `an absent flag is null, and a present one is its value`() {
        assertAll(
            { DatasourcePayloadBinder.booleanFlag(json("""{}"""), "readonly").shouldBeNull() },
            { DatasourcePayloadBinder.booleanFlag(json("""{"readonly":true}"""), "readonly") shouldBe true },
            { DatasourcePayloadBinder.booleanFlag(json("""{"readonly":false}"""), "readonly") shouldBe false },
        )
    }

    @Test
    fun `workspaceNameOf trims, and ignores a non-textual or absent value`() {
        assertAll(
            { DatasourcePayloadBinder.workspaceNameOf(json("""{"workspace":"  team-etl  "}""")) shouldBe "team-etl" },
            { DatasourcePayloadBinder.workspaceNameOf(json("""{"workspace":7}""")).shouldBeNull() },
            { DatasourcePayloadBinder.workspaceNameOf(json("""{}""")).shouldBeNull() },
        )
    }

    @Test
    fun `include-schemas must be an array of strings`() {
        val notArray =
            shouldThrow<DatapipelinesException> {
                DatasourcePayloadBinder.bind(json(complete.dropLast(1) + ""","introspection_include_schemas":"apex"}"""), true)
            }
        val notStrings =
            shouldThrow<DatapipelinesException> {
                DatasourcePayloadBinder.bind(json(complete.dropLast(1) + ""","introspection_include_schemas":[42]}"""), true)
            }

        assertAll(
            { notArray.code shouldBe PipelineErrorCodes.Datasource.PROPERTIES_INVALID },
            { notArray.details["field"] shouldBe "introspection_include_schemas" },
            { notStrings.code shouldBe PipelineErrorCodes.Datasource.PROPERTIES_INVALID },
        )
    }

    @Test
    fun `a non-object properties value and a non-int timeout are ignored, not fatal`() {
        // The §3.1 defaults: absent or ill-shaped optional structures fall back rather than
        // failing a registration over a field the caller did not mean to set.
        val bound =
            DatasourcePayloadBinder.bind(
                json(complete.dropLast(1) + ""","properties":"nope","query_timeout_seconds":"soon"}"""),
                requirePassword = true,
            )

        assertAll(
            { bound.properties.hikari.isEmpty() shouldBe true },
            { bound.properties.jdbc.isEmpty() shouldBe true },
            { bound.queryTimeoutSeconds.shouldBeNull() },
        )
    }

    @Test
    fun `no rejection message or details map echoes the password`() {
        val raw =
            """{"dialect":"NOPE","jdbc_url":"jdbc:h2:mem:x","username":"sa","password":"s3cret-do-not-echo",
               "introspection_include_schemas":[1]}"""

        val thrown = shouldThrow<DatapipelinesException> { DatasourcePayloadBinder.bind(json(raw), requirePassword = true) }

        assertAll(
            { (thrown.message ?: "") shouldNotContain "s3cret-do-not-echo" },
            { thrown.details.values.joinToString() shouldNotContain "s3cret-do-not-echo" },
        )
    }
}
