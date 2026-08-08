package co.datapipelines.typesystem

import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory

/**
 * Every key in every dialect's lookup table must be **reachable through dispatch**.
 *
 * The hand-written per-dialect suites cannot catch this class of defect, because a
 * mistyped or shadowed key appears identically in the table and in the test that was
 * written from it — `"varhcar" to AS_STRING` passes a test asserting `map(…, "varhcar")`
 * is `STRING`. What it does not do is map a real `varchar` column, which silently takes
 * the §8.2 fallback instead.
 *
 * So the cases are **generated from the tables themselves**, one per key, and assert two
 * properties a typo or a mis-ordered branch breaks:
 *
 *  1. the key is in [DialectTypeMapper.normalizeTypeName] form — a key carrying stray
 *     case or a `(p,s)` suffix can never be produced by the normalizer, so it is dead;
 *  2. dispatching the key (or code) through the public `map` actually returns that
 *     entry — an earlier branch shadowing it would otherwise go unnoticed.
 *
 * `@TestFactory` rather than `@ParameterizedTest`: the case list is derived at runtime
 * from production data structures, and each key gets its own named, individually
 * failing test.
 */
class MappingTableReachabilityTest {
    @TestFactory
    fun `every name-table key is reachable through dispatch`(): List<DynamicTest> =
        Dialect.entries
            .flatMap { dialect ->
                val mapper = TypeMappers.forDialect(dialect) as DialectTypeMapper
                mapper.recognizedTypeNames.map { (name, expected) ->
                    DynamicTest.dynamicTest("$dialect: \"$name\"") {
                        withClue("key is not in normalized form, so dispatch can never produce it") {
                            DialectTypeMapper.normalizeTypeName(name) shouldBe name
                        }
                        // An unrecognized code, so only the name can be answering.
                        withClue("name lookup shadowed by an earlier branch") {
                            mapper.map(UNRECOGNIZED_CODE, 0, 0, name) shouldBe expected
                        }
                    }
                }
            }.also { check(it.isNotEmpty()) { "no name tables found — the hooks stopped being overridden" } }

    @TestFactory
    fun `every code-table key is reachable through dispatch`(): List<DynamicTest> =
        Dialect.entries
            .flatMap { dialect ->
                val mapper = TypeMappers.forDialect(dialect) as DialectTypeMapper
                mapper.recognizedTypeCodes.map { (code, expected) ->
                    DynamicTest.dynamicTest("$dialect: JDBC $code") {
                        // No type name, so only the code can be answering.
                        mapper.map(code, 0, 0, "") shouldBe expected
                    }
                }
            }.also { check(it.isNotEmpty()) { "no code tables found — the hooks stopped being overridden" } }

    @TestFactory
    fun `every table entry survives mapColumn identically`(): List<DynamicTest> =
        Dialect.entries.flatMap { dialect ->
            val mapper = TypeMappers.forDialect(dialect) as DialectTypeMapper
            mapper.recognizedTypeNames.map { (name, expected) ->
                DynamicTest.dynamicTest("$dialect: \"$name\" via mapColumn") {
                    mapper.mapColumn("c", UNRECOGNIZED_CODE, 0, 0, name).column shouldBe
                        expected.toColumnSchema("c")
                }
            } +
                mapper.recognizedTypeCodes.map { (code, expected) ->
                    DynamicTest.dynamicTest("$dialect: JDBC $code via mapColumn") {
                        mapper.mapColumn("c", code, 0, 0, "").column shouldBe expected.toColumnSchema("c")
                    }
                }
        }

    private companion object {
        /** A code no dialect table lists, so name dispatch is the only possible answer. */
        const val UNRECOGNIZED_CODE = 9999
    }
}
