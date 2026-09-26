package co.datapipelines.web.ui

import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.mock.web.MockServletContext
import org.thymeleaf.context.WebContext
import org.thymeleaf.spring6.SpringTemplateEngine
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver
import org.thymeleaf.web.servlet.JakartaServletWebApplication

/**
 * #9 slice 2 — the Schedules page (`schedules/index`) rendered the way `SchedulesUiController`
 * renders it, for the per-piece render tests (shell, detail, form, run). The roles default to the
 * fullest (`withRoles()`); the four role shapes the page distinguishes are named here once.
 */
internal object SchedulesRender {
    private val engine =
        SpringTemplateEngine().apply {
            setTemplateResolver(
                ClassLoaderTemplateResolver().apply {
                    prefix = "templates/"
                    suffix = ".html"
                    characterEncoding = "UTF-8"
                },
            )
        }

    fun page(roles: WebContext.() -> Unit = { withRoles() }): String {
        val context =
            WebContext(
                JakartaServletWebApplication
                    .buildApplication(MockServletContext())
                    .buildExchange(MockHttpServletRequest(), MockHttpServletResponse()),
            )
        context.roles()
        context.setVariable("activeTheme", "saas")
        context.setVariable("authenticated", true)
        context.setVariable("currentPath", "/schedules")
        context.setVariable("workspaceOptions", emptyList<Any>())
        context.setVariable("timezoneGroups", ScheduleTimezones.GROUPS)
        return engine.process("schedules/index", context)
    }

    val AUTHOR: WebContext.() -> Unit = {
        withRoles(isSuperAdmin = false, canAdminWorkspace = false, canPromote = false, roleLabel = "author")
    }

    val VIEWER: WebContext.() -> Unit = {
        withRoles(canAuthor = false, canPromote = false, canAdminWorkspace = false, isSuperAdmin = false, roleLabel = "viewer")
    }

    val PROMOTER: WebContext.() -> Unit = {
        withRoles(
            canExecute = false,
            canAuthor = false,
            canPromote = true,
            canAdminWorkspace = false,
            isSuperAdmin = false,
            roleLabel = "promoter",
        )
    }

    /** The text of one `<template id="…">` skeleton, or "" when the role left it out. */
    fun skeleton(
        html: String,
        id: String,
    ): String = if (!html.contains("id=\"$id\"")) "" else html.substringAfter("id=\"$id\"").substringBefore("</template>")
}
