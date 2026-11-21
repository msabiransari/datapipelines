package co.datapipelines.web.templates

import co.datapipelines.templates.CitableFacts
import co.datapipelines.templates.FixtureInputs
import co.datapipelines.templates.ScriptBlocks
import co.datapipelines.templates.TemplateDeserializationOutcome
import co.datapipelines.templates.TemplateDeserializer
import co.datapipelines.templates.TemplateImplementsRepository
import co.datapipelines.templates.TemplateJson
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.templates.TemplateValidator
import co.datapipelines.web.SharedPostgres
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * A preserved-version template import binds each entry's body fixtures from the request TEXT (#1038,
 * review C2): the declared `body_hash` covers the fixture's exact text (member order, a repeated member
 * at any depth, the input named twice), so an import bound from a parsed tree would recompute a different
 * hash and be refused as transfer corruption. Driven through the shipped import verb on the real registry.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateImportBodyFixtureIntegrationTest {
    private val jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
    private val templates = TemplateRepository(jdbc)
    private val actor = UUID.randomUUID()
    private lateinit var imports: TemplateImportService

    @BeforeEach
    fun setUp() {
        jdbc.jdbcTemplate.execute("TRUNCATE templates, users CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name) VALUES ('$WORKSPACE', 'default', 'default') ON CONFLICT DO NOTHING",
        )
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to actor, "email" to "u$actor@example.com", "sub" to "sub-$actor"),
        )
        val validator = mockk<TemplateValidator>()
        every { validator.validateOrThrow(any(), any()) } answers { firstArg() }
        imports = TemplateImportService(templates, validator, CitableFacts.NONE, TemplateImplementsRepository(jdbc))
    }

    @Test
    fun `an entry whose body fixtures repeat members or depend on order imports under its declared hash`() {
        val entry = (TemplateDeserializer().read(ENTRY) as TemplateDeserializationOutcome.Parsed).draft
        val declared =
            templates.computeBodyHash(
                engine = entry.engine,
                dialect = entry.dialect?.wire,
                isLibrary = entry.isLibrary,
                importsJson = TemplateJson.writeImports(entry.imports),
                body = entry.body,
                contractJson = ScriptBlocks.writeContract(entry.contract),
                invariantsJson = ScriptBlocks.writeInvariants(entry.invariants),
                testsJson = ScriptBlocks.writeTests(entry.tests, entry.contract),
            )
        val body = """{"templates":[${ENTRY.removeSuffix("}")},"version":1,"body_hash":"$declared"}]}"""

        imports.import(body, WORKSPACE, actor)

        val stored = checkNotNull(templates.findVersion(WORKSPACE, ID, 1))
        stored.bodyHash shouldBe declared

        fun inputsOf(name: String) =
            stored.tests!!
                .single { it.name == name }
                .input.inputs
                .shouldBeInstanceOf<FixtureInputs>()
        inputsOf("order").exactText("order") shouldBe ORDER
        inputsOf("nested").exactText("order") shouldBe """{"a":1,"a":2}"""
        inputsOf("twice").occurrences("order") shouldBe listOf("""{"a":1}""", """{"a":2}""")
    }

    private companion object {
        val WORKSPACE: UUID = UUID.fromString("defa0000-0000-0000-0000-000000000001")
        const val ID = "test/import_body_fixture.jsonata"
        const val ORDER = """{"zeta":0.123456789012345678901234567890,"alpha":[3,2,1],"mid":{"z":1,"b":2.50}}"""
        val ENTRY =
            """
            {"id":"$ID","type":"jsonata","display_name":"X","description":"Y",
             "body":"${'$'}join(${'$'}keys(inputs.order), ',')",
             "contract":{"mode":"value","inputs":{"order":{"kind":"body"}},"output":{"kind":"value","type":"STRING"}},
             "invariants":[],
             "tests":[
               {"name":"order","input":{"inputs":{"order":$ORDER}},"expect":{"output":"zeta,alpha,mid"}},
               {"name":"nested","input":{"inputs":{"order":{"a":1,"a":2}}},
                "expect":{"refusal":"pipeline.script.input_contract_violation"}},
               {"name":"twice","input":{"inputs":{"order":{"a":1},"order":{"a":2}}},
                "expect":{"refusal":"pipeline.script.input_contract_violation"}}
             ]}
            """.trimIndent()
    }
}
