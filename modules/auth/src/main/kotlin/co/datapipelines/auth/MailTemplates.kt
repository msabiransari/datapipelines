package co.datapipelines.auth

import org.thymeleaf.context.Context
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templatemode.TemplateMode
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import java.time.Instant
import java.util.Locale

/**
 * A rendered notice: the subject and both bodies — the plain part and the html part. The
 * bodies may carry a one-time password, so [toString] names the subject and the sizes only.
 */
data class RenderedMail(
    val subject: String,
    val text: String,
    val html: String,
) {
    override fun toString(): String = "RenderedMail(subject=$subject, text=${text.length} chars, html=${html.length} chars)"
}

/** What the sys-ops "New user" notice says (auth.md §5A.8). Never a password. */
data class NewUserNotice(
    val email: String,
    /** The row's display name when it is more than the email's local part; null = omitted. */
    val displayName: String?,
    /** `local`, or the OIDC registration id. */
    val provider: String,
    /** The acting admin's email for a local account; `self-service via <provider>` for a social login. */
    val createdBy: String,
    /** The workspace the account landed in (or the admin asked for); null = none. */
    val workspace: String?,
    val at: Instant,
    /** The admin users screen, absolute — built from `auth.base-url`. */
    val adminUsersUrl: String,
)

/**
 * The two notice families under `mail-templates/` (auth.md §5A.8), rendered with a
 * [SpringTemplateEngine] of this module's own — one for the `.html` parts, one for the `.txt`
 * parts in Thymeleaf's TEXT mode. Not `web`'s MVC engine and not a bean of that type: a
 * domain module renders mail, and an engine that resolved these from the site's resolver
 * chain would be the coupling this module's fence exists to keep out. Deliberately NOT under
 * `templates/`: `web`'s template audits sweep every html under the templates classpath root and would apply the
 * app's typography and htmx rules to mail markup that has its own inline style by design.
 *
 * Every value reaches a template pre-formatted (an `Instant` as its ISO string), so the
 * templates carry no dialect beyond the standard one; the html part escapes through
 * `th:text`, the text part inlines verbatim (`[(…)]`) — pinned by `MailTemplatesTest`.
 *
 * The password appears in exactly one place: the message body the transport is handed. It is
 * a template VARIABLE here and never a log line, an audit detail or a claim-row column.
 */
class MailTemplates {
    private val html = engine(TemplateMode.HTML, ".html")
    private val text = engine(TemplateMode.TEXT, ".txt")

    /** The welcome family — [MailKind.WELCOME] at creation, [MailKind.PASSWORD_RESET] at a reset. */
    fun welcome(
        kind: MailKind,
        email: String,
        loginUrl: String,
        oneTimePassword: String,
        replyTo: String,
    ): RenderedMail {
        require(kind != MailKind.NEW_USER) { "the new-user notice is its own family (newUser)" }
        val reset = kind == MailKind.PASSWORD_RESET
        val variables =
            mapOf(
                "reset" to reset,
                "email" to email,
                "loginUrl" to loginUrl,
                "oneTimePassword" to oneTimePassword,
                "replyTo" to replyTo,
            )
        return RenderedMail(
            subject = if (reset) "Your datapipelines password was reset" else "Your datapipelines account",
            text = text.process("welcome", context(variables)),
            html = html.process("welcome", context(variables)),
        )
    }

    /** The sys-ops notice — [MailKind.NEW_USER]. */
    fun newUser(notice: NewUserNotice): RenderedMail {
        val variables =
            mapOf(
                "email" to notice.email,
                "displayName" to notice.displayName,
                "provider" to notice.provider,
                "createdBy" to notice.createdBy,
                "workspace" to notice.workspace,
                "at" to notice.at.toString(),
                "adminUsersUrl" to notice.adminUsersUrl,
            )
        return RenderedMail(
            subject = "New user: ${notice.email} (${notice.provider})",
            text = text.process("new-user", context(variables)),
            html = html.process("new-user", context(variables)),
        )
    }

    private fun context(variables: Map<String, Any?>): Context = Context(Locale.ENGLISH, variables)

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

    private companion object {
        const val TEMPLATE_PREFIX = "mail-templates/"
    }
}
