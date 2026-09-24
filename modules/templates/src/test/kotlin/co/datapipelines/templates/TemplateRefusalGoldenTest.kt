package co.datapipelines.templates

import co.datapipelines.pipeline.TemplateType
import co.datapipelines.typesystem.Dialect
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * The refusal golden (7b §A.2): every `template.*` refusal the save-time surface can produce —
 * the deserializer's wire-value verdicts, the validator's §7 checks and [TemplateTypeRule]'s
 * immutability refusal — rendered to one canonical line each and compared against the committed
 * golden at `src/test/resources/golden/template-refusals.txt`.
 *
 * The golden exists so the `TemplateTypeBehaviour` refactor (the record's §2.4: one behaviour
 * per type, every `when (type)` moved behind it) can prove **byte-identical** refusals for the
 * pre-existing `sql`/`html` world: the golden is committed from the pre-refactor code and must
 * not change when the branches move. A message edited deliberately is a golden edit reviewed
 * like a behaviour change; a message changed accidentally is a red test.
 *
 * Regenerate ONLY deliberately: `REWRITE_GOLDEN=1 ./gradlew :modules:templates:test --tests '*TemplateRefusalGoldenTest*'`.
 * (An env var, not a `-P` property: the conventions plugin forwards only the JUnit ordering
 * properties to the test JVM.)
 */
class TemplateRefusalGoldenTest {
    private val workspaceId = java.util.UUID.randomUUID()

    /** One refusal as its canonical line: `code | message | k=v,k=v` (details sorted by key). */
    private fun lineOf(failure: TemplateValidationFailure): String {
        val details =
            failure.details
                .toSortedMap()
                .entries
                .joinToString(",") { (k, v) -> "$k=${render(v)}" }
        return "${failure.code} | ${failure.message} | $details"
    }

    private fun render(value: Any?): String =
        when (value) {
            null -> "null"
            is List<*> -> value.joinToString(",", "[", "]") { render(it) }
            else -> value.toString()
        }

    /** Runs [block], collects every failure it produces, one line each. */
    private fun linesOf(block: () -> List<TemplateValidationFailure>): List<String> = block().map { lineOf(it) }

    private fun validator(
        vararg registered: TemplateVersion,
        maxBodyChars: Int = TemplateValidator.DEFAULT_MAX_BODY_CHARS,
    ): TemplateValidator {
        val registry = InMemoryTemplateRegistry(registered.toList())
        return TemplateValidator(LibraryResolver { _ -> registry }, maxBodyChars = maxBodyChars)
    }

    /** The corpus: one entry per refusal path, in declaration order. */
    private fun corpus(): List<Pair<String, List<String>>> {
        val cases = mutableListOf<Pair<String, List<String>>>()
        val deserializer = TemplateDeserializer()

        fun rejectionsOf(payload: String): List<String> =
            when (val outcome = deserializer.read(payload)) {
                is TemplateDeserializationOutcome.Parsed -> emptyList()
                is TemplateDeserializationOutcome.Rejected -> outcome.result.failures.map { lineOf(it) }
            }

        cases +=
            "deserializer/type_invalid" to
            rejectionsOf("""{"type":"csv","dialect":"POSTGRES","display_name":"X","description":"Y","body":"SELECT 1"}""")
        cases +=
            "deserializer/dialect_not_allowed_html" to
            rejectionsOf("""{"type":"html","dialect":"POSTGRES","display_name":"X","description":"Y","body":"<p>Hi</p>"}""")
        cases +=
            "deserializer/dialect_invalid_unknown" to
            rejectionsOf("""{"type":"sql","dialect":"DB2","display_name":"X","description":"Y","body":"SELECT 1"}""")

        cases +=
            "validator/id_invalid" to
            linesOf { validator().validate(TemplateFixtures.draft(id = "Fetch Orders"), workspaceId).failures }
        cases +=
            "validator/engine_unsupported" to
            linesOf { validator().validate(TemplateFixtures.draft(engine = "pebble"), workspaceId).failures }
        cases +=
            "validator/schema_version_unsupported" to
            linesOf { validator().validate(TemplateFixtures.draft(schemaVersion = 2), workspaceId).failures }
        cases +=
            "validator/dialect_invalid_missing" to
            linesOf { validator().validate(TemplateFixtures.draft(dialect = null), workspaceId).failures }
        cases +=
            "validator/dialect_not_allowed" to
            linesOf {
                validator()
                    .validate(TemplateFixtures.draft(type = TemplateType.HTML, dialect = Dialect.POSTGRES), workspaceId)
                    .failures
            }
        cases +=
            "validator/html_entity" to
            linesOf {
                validator()
                    .validate(TemplateFixtures.draft(body = "SELECT * FROM t WHERE a &lt;= 5"), workspaceId)
                    .failures
            }
        cases +=
            "validator/syntax_error" to
            linesOf { validator().validate(TemplateFixtures.draft(body = "\${"), workspaceId).failures }
        cases +=
            "validator/dangerous_construct_builtin" to
            linesOf { validator().validate(TemplateFixtures.draft(body = "\${x?eval}"), workspaceId).failures }
        cases +=
            "validator/dangerous_construct_ftl_header" to
            linesOf {
                validator()
                    .validate(TemplateFixtures.draft(body = "<#ftl attributes={\"k\":\"1\"}>OK"), workspaceId)
                    .failures
            }
        cases +=
            "validator/dangerous_construct_square_bracket" to
            linesOf { validator().validate(TemplateFixtures.draft(body = "[#include \"y\"]"), workspaceId).failures }
        cases +=
            "validator/is_library_without_macros" to
            linesOf {
                validator()
                    .validate(TemplateFixtures.draft(isLibrary = true, body = "SELECT 1"), workspaceId)
                    .failures
            }
        cases +=
            "validator/body_over_cap" to
            linesOf {
                validator(maxBodyChars = 10)
                    .validate(TemplateFixtures.draft(body = "SELECT 1 FROM x"), workspaceId)
                    .failures
            }
        cases +=
            "validator/import_not_found" to
            linesOf {
                validator()
                    .validate(
                        TemplateFixtures.draft(imports = listOf(TemplateImport("acme/missing.lib.sql", 1, "m"))),
                        workspaceId,
                    ).failures
            }
        cases +=
            "validator/import_not_library" to
            linesOf {
                val plain = TemplateFixtures.version(id = "acme/plain.sql", isLibrary = false)
                validator(plain)
                    .validate(
                        TemplateFixtures.draft(imports = listOf(TemplateImport("acme/plain.sql", 1, "p"))),
                        workspaceId,
                    ).failures
            }
        cases +=
            "validator/duplicate_alias" to
            linesOf {
                val lib = TemplateFixtures.version(id = "acme/lib.sql", isLibrary = true, body = "<#macro m></#macro>")
                validator(lib)
                    .validate(
                        TemplateFixtures.draft(
                            imports =
                                listOf(
                                    TemplateImport("acme/lib.sql", 1, "x"),
                                    TemplateImport("acme/lib.sql", 1, "x"),
                                ),
                        ),
                        workspaceId,
                    ).failures
            }
        cases +=
            "validator/import_cycle" to
            linesOf {
                val a =
                    TemplateFixtures.version(
                        id = "acme/a.lib.sql",
                        isLibrary = true,
                        body = "<#macro m></#macro>",
                        imports = listOf(TemplateImport("acme/b.lib.sql", 1, "b")),
                    )
                val b =
                    TemplateFixtures.version(
                        id = "acme/b.lib.sql",
                        isLibrary = true,
                        body = "<#macro m></#macro>",
                        imports = listOf(TemplateImport("acme/a.lib.sql", 1, "a")),
                    )
                validator(a, b)
                    .validate(
                        TemplateFixtures.draft(imports = listOf(TemplateImport("acme/a.lib.sql", 1, "a"))),
                        workspaceId,
                    ).failures
            }
        cases +=
            "typerule/type_immutable" to
            linesOf {
                try {
                    TemplateTypeRule.forExisting(TemplateFixtures.draft(type = TemplateType.HTML), TemplateType.SQL)
                    emptyList()
                } catch (err: co.datapipelines.typesystem.DatapipelinesException) {
                    listOf(TemplateValidationFailure(err.code, err.message ?: err.code, err.details))
                }
            }
        return cases
    }

    @Test
    fun `every refusal matches the committed golden`() {
        val golden = File("src/test/resources/golden/template-refusals.txt")
        val actual =
            corpus()
                .flatMap { (name, lines) ->
                    lines.map { "$name :: $it" }
                }.sorted()
                .joinToString("\n") + "\n"
        if (System.getenv("REWRITE_GOLDEN") != null) {
            golden.parentFile.mkdirs()
            golden.writeText(actual)
            return
        }
        golden.isFile shouldBe true
        val expected = golden.readText()
        if (expected != actual) {
            val expectedLines = expected.lines().toSet()
            val actualLines = actual.lines().toSet()
            val added = (actualLines - expectedLines).sorted()
            val removed = (expectedLines - actualLines).sorted()
            throw AssertionError(
                "template-refusals golden drifted (regenerate deliberately with REWRITE_GOLDEN=1, " +
                    "and review the diff like a behaviour change):\n" +
                    "ADDED:\n${added.joinToString("\n")}\nREMOVED:\n${removed.joinToString("\n")}",
            )
        }
    }
}
