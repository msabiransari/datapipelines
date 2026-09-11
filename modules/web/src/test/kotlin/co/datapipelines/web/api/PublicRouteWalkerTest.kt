package co.datapipelines.web.api

import co.datapipelines.auth.PublicPaths
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.config.BeanDefinition
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.http.server.PathContainer
import org.springframework.mock.web.MockServletContext
import org.springframework.stereotype.Controller
import org.springframework.web.context.support.StaticWebApplicationContext
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser

/**
 * The runtime half of 096 §A (review finding F1): **which handlers does the §8.3 allowlist
 * actually make public?**
 *
 * `PublicPathsTest` (in `auth`) freezes the allowlist's CONTENTS. It cannot answer the
 * question that matters operationally, because the mappings live here: a new controller
 * mapped under one of the globs — the `site`, `docs`, `compare`, `login` or `oauth2`
 * patterns — is anonymous the moment it lands, with every existing test green. Until 096
 * nothing walked the two against each other in either module.
 *
 * So this test walks Spring's own `RequestMappingHandlerMapping`, decides public/private
 * for every registered path pattern with the same `PathPatternParser` the chain matches
 * with, and freezes the PUBLIC side by handler name. A new public route fails here naming
 * itself; making an existing one private fails here too, which is the direction that
 * would otherwise ship as a 302-to-login nobody noticed.
 *
 * ## Why a hand-built context rather than `@SpringBootTest`
 * This module has no `@SpringBootApplication` — its Spring context tests live in `app`,
 * behind Postgres and Redis containers. The mapping does not need one: Spring detects
 * handler methods from the bean *type* (`getType(beanName)`), never from an instance, so
 * lazy bean definitions over the classpath-scanned controllers produce the same
 * `RequestMappingInfo`s the running application registers, in milliseconds and with no
 * container. What this deliberately does NOT cover is the two handlers that live outside
 * `web`: `HealthController` (in `app`, serving the three probes) and Boot's
 * `BasicErrorController` (`/error`). Their patterns are in the allowlist and asserted by
 * `PublicPathsTest`; `ApplicationSmokeTest` boots the real chain over them.
 *
 * ## The pattern-against-pattern approximation
 * A handler's mapping is a PATTERN (`/docs/{slug}`), not a path, and it is matched here as
 * if it were a path. That resolves the way the allowlist intends — the `docs` glob matches
 * `/docs/{slug}` exactly as it matches `/docs/auth` — and it errs toward calling a route
 * public, which is the direction that makes this test fail rather than the direction that
 * lets a route through.
 */
class PublicRouteWalkerTest {
    private val parser = PathPatternParser()

    private val publicPatterns: List<PathPattern> = PublicPaths.PATTERNS.map { parser.parse(it) }

    /** `Class#method` for every handler whose every mapped pattern is matched by the allowlist. */
    private val publicHandlers: List<String> =
        walkMappings()
            .filter { (patterns, _) -> patterns.isNotEmpty() && patterns.all(::isPublic) }
            .map { (_, handler) -> handler }
            .distinct()
            .sorted()

    @Test
    fun `exactly these handlers are reachable without a credential`() {
        publicHandlers shouldContainExactly
            listOf(
                // The login surface — the pages a caller reaches BEFORE it has a credential.
                "LocalLoginController#login",
                "UiController#login",
                // The marketing site (033) and its intent-cluster pages (073, 089).
                "SiteController#home",
                "SitePagesController#addToClaudeCode",
                "SitePagesController#aiDataPipeline",
                "SitePagesController#compareAirflow",
                "SitePagesController#compareDbt",
                "SitePagesController#demoData",
                "SitePagesController#dpLake",
                "SitePagesController#engine",
                "SitePagesController#faq",
                "SitePagesController#federatedQuery",
                "SitePagesController#howItWorks",
                "SitePagesController#mcpTools",
                "SitePagesController#pillar",
                "SitePagesController#pricing",
                "SitePagesController#publishedApi",
                "SitePagesController#roadmap",
                "SitePagesController#security",
                "SitePagesController#tableau",
                "SitePagesController#tableauGovernedDataset",
                "SitePagesController#textToSqlAgent",
                // Site v2 batch 2 (111): the seven intent pages got their own controller
                // (detekt TooManyFunctions), same shape and same reasoning.
                "SiteV2Batch2Controller#compareFivetranAirbyte",
                "SiteV2Batch2Controller#comparePostgresOnly",
                "SiteV2Batch2Controller#forAgencies",
                "SiteV2Batch2Controller#forAnalysts",
                "SiteV2Batch2Controller#forSaasTeams",
                "SiteV2Batch2Controller#tableauPrep",
                "SiteV2Batch2Controller#tableauRoadmap",
                // Crawler infrastructure (073) — robots.txt is a static file, not a handler.
                "SitemapController#sitemap",
                // The packaged spec set (073) and the agent skill (095): jar-packaged
                // Markdown, no principal, no workspace, no datastore.
                "DocsController#doc",
                "DocsController#index",
                "SkillController#reference",
                "SkillController#skill",
            ).sorted()
    }

    /**
     * Non-vacuity. A walk that registered nothing — a scan filter that stopped matching, a
     * context that failed to detect handler methods — would report an EMPTY public set and
     * pass the assertion above by finding no violations at all.
     */
    @Test
    fun `the walk sees the module's whole request-mapping surface`() {
        val all = walkMappings()
        all.size shouldBeGreaterThanOrEqual MINIMUM_MAPPINGS
        withClue("every mapping resolves to at least one path pattern") {
            all.count { it.first.isEmpty() } shouldBe 0
        }
        // The REST surface, the partials and the UI pages are all in the walk — three
        // spellings, so a scan that lost one family is visible.
        withClue("the walk covers /api/v1, /partials and the UI pages") {
            all.any { m -> m.first.any { it.startsWith("/api/v1/") } } shouldBe true
            all.any { m -> m.first.any { it.startsWith("/partials") } } shouldBe true
            all.any { m -> m.first.any { it == "/dashboard" } } shouldBe true
        }
    }

    private fun isPublic(pattern: String): Boolean = publicPatterns.any { it.matches(PathContainer.parsePath(pattern)) }

    /** `(path patterns, "Class#method")` for every handler method Spring registers. */
    private fun walkMappings(): List<Pair<Set<String>, String>> {
        val context = StaticWebApplicationContext()
        context.servletContext = MockServletContext()
        scanControllers().forEach { type ->
            // Lazy: the mapping reads the bean TYPE, never an instance, so no controller's
            // constructor dependencies have to exist for the walk to be exact.
            context.registerBeanDefinition(type.name, RootBeanDefinition(type).apply { isLazyInit = true })
        }
        context.refresh()

        val mapping =
            RequestMappingHandlerMapping().apply {
                applicationContext = context
                afterPropertiesSet()
            }
        return mapping.handlerMethods.map { (info, handler) ->
            val patterns = info.pathPatternsCondition?.patternValues.orEmpty()
            patterns to "${handler.beanType.simpleName}#${handler.method.name}"
        }
    }

    /**
     * The module's production controllers. `@Controller` is the meta-annotation
     * `@RestController` carries, so one filter finds both families — the same scan
     * [RequiredScopeCoverageTest] uses, with the same main-only filter that keeps the test
     * runtime's probe controllers out.
     */
    private fun scanControllers(): List<Class<*>> =
        ClassPathScanningCandidateComponentProvider(false)
            .apply { addIncludeFilter(AnnotationTypeFilter(Controller::class.java)) }
            .findCandidateComponents(BASE_PACKAGE)
            .map(BeanDefinition::getBeanClassName)
            .filterNotNull()
            .map { Class.forName(it) }
            .filter {
                it.protectionDomain.codeSource.location.path
                    .contains("/main/")
            }.sortedBy { it.name }

    private companion object {
        const val BASE_PACKAGE = "co.datapipelines.web"

        /**
         * The endpoint inventory taken for the 2026-09-08 security review counted 133 routes
         * across the application; `web` owns all but the three probes. A floor of 100 is
         * comfortably under that and comfortably over "the scan broke".
         */
        const val MINIMUM_MAPPINGS = 100
    }
}
