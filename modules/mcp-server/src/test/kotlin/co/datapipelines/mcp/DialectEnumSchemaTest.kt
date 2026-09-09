package co.datapipelines.mcp

import co.datapipelines.typesystem.Dialect
import com.fasterxml.jackson.databind.ObjectMapper
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import org.junit.jupiter.api.Test

/**
 * The `dialect` enum the MCP schema ADVERTISES equals the enum the server ACCEPTS.
 *
 * The client validates arguments against the schema before the server sees them, so a stale
 * advertised enum is a refusal the server never logs. `LAKE` was accepted by the parser
 * (`Dialect.entries`) and refused by the schema (a seven-value literal) for two rounds; the
 * agent that hit it blamed its own typing (T199). This test reads the schema JSON as a client
 * would and compares it with the type system — the generated artifact, not the source that
 * feeds it (MISTAKES.md, "Reflection-Fed Frameworks Fail SILENTLY").
 */
class DialectEnumSchemaTest {
    private val json = ObjectMapper()

    @Test
    fun `the advertised dialect enum is exactly the supported dialects, LAKE included`() {
        val advertised = json.readTree(DIALECT_ENUM_JSON).map { it.asText() }
        advertised shouldContainExactly Dialect.entries.map { it.wire }
        advertised shouldContain "LAKE"
    }
}
