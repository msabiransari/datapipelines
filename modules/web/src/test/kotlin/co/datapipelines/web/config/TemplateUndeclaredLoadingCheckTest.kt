package co.datapipelines.web.config

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import co.datapipelines.pipeline.CreateLifecycle
import co.datapipelines.pipeline.TemplateType
import co.datapipelines.pipeline.WriteSurface
import co.datapipelines.templates.TemplateDraft
import co.datapipelines.templates.TemplateRepository
import co.datapipelines.typesystem.Dialect
import co.datapipelines.web.SharedPostgres
import co.datapipelines.web.capturingLogEvents
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.util.UUID

/**
 * #787 A3: the boot count over stored bodies. Non-vacuity floor — one seeded matching row makes
 * the check WARN with `count=1`; clean bodies stay at INFO `count=0`; the count covers every
 * matching row in BOTH spellings and the line carries no id and no body text.
 *
 * Seeds through the real [TemplateRepository] over [SharedPostgres] — production-shaped rows,
 * no validator in between (the bodies below are exactly what the NEW save rule refuses, which
 * is the point of the check).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TemplateUndeclaredLoadingCheckTest {
    private lateinit var jdbc: NamedParameterJdbcTemplate
    private lateinit var templates: TemplateRepository

    private val workspace = UUID.randomUUID()
    private val actor = UUID.randomUUID()

    private val offendingSnake = "test/boot_check/snake"
    private val offendingCamel = "test/boot_check/camel"
    private val clean = "test/boot_check/clean"

    @BeforeAll
    fun connect() {
        jdbc = NamedParameterJdbcTemplate(SharedPostgres.dataSource())
        templates = TemplateRepository(jdbc)
    }

    @BeforeEach
    fun setUp() {
        // The CASCADE reaches workspaces (workspace_id) and templates (created_by), so every case starts bare.
        jdbc.jdbcTemplate.execute("TRUNCATE templates, users, workspaces CASCADE")
        jdbc.jdbcTemplate.execute(
            "INSERT INTO workspaces (id, name, display_name) VALUES ('$workspace', 'boot-check', 'boot-check') ON CONFLICT DO NOTHING",
        )
        jdbc.update(
            "INSERT INTO users (id, email, display_name, provider, provider_subject) VALUES (:id, :email, 'T', 'google', :sub)",
            mapOf("id" to actor, "email" to "u$actor@example.com", "sub" to "sub-$actor"),
        )
    }

    @Test
    fun `one stored body with the construct is a WARN with count=1`() {
        seed(offendingSnake, body = "<#assign t = .get_optional_template('/test/lib@1')>SELECT 1")

        val events = boot()

        events.filter { it.level == Level.INFO }.shouldBeEmpty()
        events.single { it.level == Level.WARN }.formattedMessage shouldBe
            "event=templates.undeclared_loading_construct count=1"
    }

    @Test
    fun `clean bodies are the quiet default - INFO count=0, no warn`() {
        seed(clean, body = "SELECT 1")

        val events = boot()

        events.filter { it.level == Level.WARN }.shouldBeEmpty()
        events.single { it.level == Level.INFO }.formattedMessage shouldBe
            "event=templates.undeclared_loading_construct count=0"
    }

    @Test
    fun `the count counts every matching row in both spellings, and echoes no id and no body`() {
        seed(offendingSnake, body = "<#assign t = .get_optional_template('/test/lib@1')>SELECT 'snake-row-marker'")
        seed(offendingCamel, body = "SELECT 1 <#assign y = .getOptionalTemplate('/test/other@1')> -- camel-row-marker")
        seed(clean, body = "SELECT 1")

        val warn = boot().single { it.level == Level.WARN }.formattedMessage

        warn shouldBe "event=templates.undeclared_loading_construct count=2"
        withClue("the line must echo no template id") {
            warn shouldNotContain offendingSnake
            warn shouldNotContain offendingCamel
        }
        withClue("the line must echo no body text") {
            warn shouldNotContain "get_optional_template"
            warn shouldNotContain "getOptionalTemplate"
            warn shouldNotContain "SELECT"
            warn shouldNotContain "row-marker"
        }
    }

    private fun boot(): List<ILoggingEvent> = capturingLogEvents(logger()) { check().check() }

    private fun check(): TemplateUndeclaredLoadingCheck = TemplateUndeclaredLoadingCheck(jdbc)

    /** One draft version-1 row through the production persistence verb — no validator in between. */
    private fun seed(
        id: String,
        body: String,
    ) {
        templates.create(
            workspace,
            TemplateDraft(
                id = id,
                type = TemplateType.SQL,
                dialect = Dialect.POSTGRES,
                displayName = "Boot check",
                description = "",
                body = body,
            ),
            actor,
            CreateLifecycle.DRAFT,
            WriteSurface.SESSION,
        )
    }

    private fun logger(): Logger = LoggerFactory.getLogger(TemplateUndeclaredLoadingCheck::class.java) as Logger
}
