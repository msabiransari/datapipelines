package co.datapipelines.datasources

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import co.datapipelines.persistence.FailureShape
import co.datapipelines.typesystem.DatapipelinesException
import co.datapipelines.typesystem.Dialect
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

/** The real registry fixture: recorder failure must neither expose data nor replace the refusal. */
class DatasourceRefusalLogTest {
    @ParameterizedTest
    @ValueSource(booleans = [true, false])
    fun `registry recorder failure logs only its shape and still refuses the pool`(sqlFailure: Boolean) {
        val (jdbc, owner) = metadataFixture()
        val marker = "synthetic_refused_row_329a"
        val failure =
            IllegalStateException(
                "$marker wrapper",
                if (sqlFailure) SQLException("$marker nested", "23514") else IllegalArgumentException("$marker nested"),
            )
        val recorded = mutableListOf<String>()
        val registry =
            DefaultDatasourceRegistry(
                DatasourceRepository(jdbc),
                testEncryptor(),
                lakeTables =
                    LakeTableCatalog {
                        listOf(LakeRegisteredTable(listOf("a", "b", "c"), "unmappable", "parquet", "s3://b/t.parquet"))
                    },
                lakeViewRecorder =
                    LakeViewOutcomeRecorder { name, namespace, table, error ->
                        recorded += (listOf(name) + namespace + table).joinToString(".")
                        requireNotNull(error)
                        throw failure
                    },
            )
        val datasource =
            registry.save(
                Datasource(
                    name = "lake_refusal_log",
                    displayName = "Lake",
                    dialect = Dialect.LAKE,
                    jdbcUrl = "jdbc:duckdb::memory:",
                    credentialKind = CredentialKind.NONE,
                ),
                owner,
            )
        val events =
            capturingFailureLogs {
                val refused = shouldThrow<DatapipelinesException> { registry.poolFor(datasource) }
                refused.code shouldBe DatasourceErrorCodes.LAKE_NO_HEALTHY_TABLES
                requireNotNull(refused.message) shouldContain "a.b.c.unmappable"
            }
        recorded shouldBe listOf("lake_refusal_log.a.b.c.unmappable")
        val event = events.single { it.formattedMessage.startsWith("event=lake.view_outcome_record_failed ") }
        event.level shouldBe Level.WARN
        event.formattedMessage shouldNotContain marker
        event.throwableProxy shouldBe null
        event.formattedMessage shouldContain "datasource=lake_refusal_log"
        event.formattedMessage shouldContain "table=a.b.c.unmappable"
        event.formattedMessage shouldContain "error=${FailureShape.cause(failure)}"
        event.formattedMessage shouldContain "sql_state=${FailureShape.sqlState(failure)}"
        FailureShape.sqlState(failure) shouldBe if (sqlFailure) "23514" else "none"
        event.formattedMessage shouldContain "the pool build is refused regardless"
    }

    private fun metadataFixture(): Pair<NamedParameterJdbcTemplate, UUID> {
        val jdbc = NamedParameterJdbcTemplate(SharedPostgres.pooledDataSource())
        jdbc.jdbcTemplate.execute("TRUNCATE datasources, users CASCADE")
        val owner =
            requireNotNull(
                jdbc.queryForObject(
                    """
                    INSERT INTO users (email, display_name, provider, provider_subject)
                    VALUES ('owner@example.com', 'Owner', 'google', 'sub-1')
                    RETURNING id
                    """.trimIndent(),
                    emptyMap<String, Any>(),
                    UUID::class.java,
                ),
            )
        return jdbc to owner
    }

    private fun capturingFailureLogs(block: () -> Unit): List<ILoggingEvent> {
        val logger = LoggerFactory.getLogger(DefaultDatasourceRegistry::class.java) as ch.qos.logback.classic.Logger
        val previousLevel = logger.level
        val appender =
            ListAppender<ILoggingEvent>().apply {
                list = CopyOnWriteArrayList()
                start()
            }
        logger.level = Level.WARN
        logger.addAppender(appender)
        return try {
            block()
            appender.list.toList()
        } finally {
            logger.detachAppender(appender)
            logger.level = previousLevel
            appender.stop()
        }
    }
}
