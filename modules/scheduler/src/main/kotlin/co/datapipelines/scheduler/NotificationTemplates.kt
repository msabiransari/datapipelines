package co.datapipelines.scheduler

import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.DateTimeException
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/**
 * What one notice is about: the run's frozen target and reference timezone, recorded times and
 * opaque execution reference, and the event's outcome. Only the schedule name stays live: a
 * rename is still the same schedule identity. Names, codes and times — never the run's
 * payload, parameters, rows or SQL, so no template can print them.
 */
data class NotificationNotice(
    val scheduleId: UUID,
    val scheduleName: String,
    val timezone: String,
    val targetRef: String?,
    val runId: UUID,
    val outcome: NotificationOutcome,
    val runState: RunState,
    val reason: String?,
    val occurredAt: Instant,
    val scheduledAt: Instant?,
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val executionId: UUID? = null,
)

/** A rendered notice. [toString] names the subject and sizes only. */
data class RenderedNotice(
    val subject: String,
    val text: String,
    val html: String,
) {
    override fun toString(): String = "RenderedNotice(subject=$subject, text=${text.length} chars, html=${html.length} chars)"
}

/**
 * The scheduler's two notice families under `scheduler-mail-templates/` (#442b): `run-event` for a
 * run's outcome and `schedule-blocked` for the schedule-level block. The engine setup is auth's
 * `MailTemplates` duplicated, not shared — the scheduler cannot see `auth` — and for the same
 * reasons: an engine of this module's own (HTML for `.html`, TEXT for `.txt`), deliberately NOT
 * under `templates/`, where `web`'s audits would apply the app's rules to mail markup.
 *
 * Every value reaches a template pre-formatted. The html part escapes through `th:text`; the text
 * part inlines verbatim — it is plain text, nothing interprets it. The subject is the schedule name
 * with every control character removed (a CR/LF cannot start a header) and the policy's fixed
 * subject; the link is the configured base URL plus a path, never a request's host.
 */
class NotificationTemplates {
    private val html = engine(TemplateMode.HTML, ".html")
    private val text = engine(TemplateMode.TEXT, ".txt")

    fun render(
        notice: NotificationNotice,
        baseUrl: String,
    ): RenderedNotice {
        val zone = zoneOf(notice.timezone)
        val subject = subjectOf(notice.scheduleName, notice.outcome)
        val variables =
            mapOf(
                "subject" to subject,
                "headline" to notice.outcome.subject,
                "explanation" to EXPLANATIONS.getValue(notice.outcome),
                "scheduleName" to notice.scheduleName,
                "target" to notice.targetRef,
                "state" to notice.runState.wire,
                "reason" to notice.reason,
                "scheduledFor" to notice.scheduledAt?.let { format(it, zone) },
                "started" to notice.startedAt?.let { format(it, zone) },
                "ended" to notice.finishedAt?.let { format(it, zone) },
                "execution" to notice.executionId?.toString(),
                "when" to format(notice.occurredAt, zone),
                "link" to linkOf(baseUrl, notice.scheduleId, notice.runId),
            )
        val family = if (notice.outcome == NotificationOutcome.BLOCKED) BLOCKED_FAMILY else RUN_FAMILY
        val context = Context(Locale.ENGLISH, variables)
        return RenderedNotice(subject, text.process(family, context), html.process(family, context))
    }

    private fun engine(
        mode: TemplateMode,
        suffix: String,
    ): SpringTemplateEngine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = TEMPLATE_PREFIX
                    this.suffix = suffix
                    templateMode = mode
                    characterEncoding = Charsets.UTF_8.name()
                    isCacheable = true
                },
            )
        }

    companion object {
        private const val TEMPLATE_PREFIX = "scheduler-mail-templates/"
        private const val RUN_FAMILY = "run-event"
        private const val BLOCKED_FAMILY = "schedule-blocked"
        private val CONTROL = Regex("\\p{Cntrl}")
        private val LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.ROOT)

        /** `<schedule name> — <the policy's subject>`; the name loses every control character, CR and LF included. */
        fun subjectOf(
            scheduleName: String,
            outcome: NotificationOutcome,
        ): String = "${scheduleName.replace(CONTROL, "")} — ${outcome.subject}"

        /** The run's page under the configured base URL — the deep link the schedules page reads (`?id=…&run=…`). */
        fun linkOf(
            baseUrl: String,
            scheduleId: UUID,
            runId: UUID,
        ): String = "${baseUrl.trimEnd('/')}/schedules?id=$scheduleId&run=$runId"

        /** `2026-10-24 23:14:05 Europe/Berlin (21:14:05 UTC)` — the run's reference clock first, UTC beside it. */
        private fun format(
            at: Instant,
            zone: ZoneId,
        ): String {
            val local = LOCAL.format(at.atZone(zone))
            return if (zone == ZoneOffset.UTC) {
                "$local UTC"
            } else {
                "$local $zone (${LOCAL.format(at.atZone(ZoneOffset.UTC)).substringAfter(' ')} UTC)"
            }
        }

        /** The run's IANA reference zone; UTC if the stored id no longer parses (a notice is not where that is fixed). */
        private fun zoneOf(timezone: String): ZoneId =
            try {
                ZoneId.of(timezone)
            } catch (_: DateTimeException) {
                ZoneOffset.UTC
            }

        /** One sentence per outcome — what happened, in the policy's words (scheduler.md §2.3, §5). */
        private val EXPLANATIONS: Map<NotificationOutcome, String> =
            mapOf(
                NotificationOutcome.STARTED to "The run's execution is confirmed running.",
                NotificationOutcome.COMPLETED to "The run finished successfully.",
                NotificationOutcome.FAILED to "The run's execution failed.",
                NotificationOutcome.COULD_NOT_START to "The run could not start; nothing ran. The reason says why.",
                NotificationOutcome.CANCELLED to "The run was cancelled.",
                NotificationOutcome.ABORTED to "The instance running it shut down, and the run was stopped.",
                NotificationOutcome.UNKNOWN to
                    "Nobody can say whether this run's work happened. The schedule is blocked until a person checks and unblocks it.",
                NotificationOutcome.UNKNOWN_UPDATE to
                    "A run whose outcome was unknown has reported its real outcome. The schedule stays blocked until a person unblocks it.",
                NotificationOutcome.BLOCKED to "This schedule fires nothing until a person unblocks it.",
            )
    }
}
