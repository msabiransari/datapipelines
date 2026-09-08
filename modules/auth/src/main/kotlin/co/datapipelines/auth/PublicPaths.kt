package co.datapipelines.auth

/**
 * One row of the `permitAll` allowlist: the path pattern, the sentence that says why it
 * is public, and the round that added it.
 *
 * The [reason] is not decoration. It is the field that makes an allowlist reviewable —
 * "why is this public?" is the only question worth asking about an entry, and an entry
 * that cannot answer it in a sentence is an entry nobody decided on. `docs/auth.md` §8.3
 * is generated from these three fields and drift-tested against them
 * (`PublicPathsTest`), so the doc cannot fall behind the code the way it had by 096
 * (9 documented patterns against 22 in code).
 */
data class PublicPath(
    val pattern: String,
    val reason: String,
    val since: String,
)

/**
 * The `permitAll` allowlist (auth.md §8.3) as data, so it can be TESTED — the 096 §A fix
 * for review finding F1.
 *
 * Until 096 this list was an inline `requestMatchers(...)` argument inside
 * [SecurityConfig]. Nothing asserted its contents, nothing asserted that a reason
 * existed, and nothing walked the application's request mappings against it — so a
 * controller mapped under one of the globs (the `site`, `docs`, `compare`, `login` and
 * `oauth2` patterns — written without their leading slash because a slash-star sequence
 * opens a NESTED block comment in Kotlin) would have shipped **public** with every test
 * green. The house rule this restores is `rules/04-configuration.md`'s PUBLIC_ENDPOINTS principle:
 * every endpoint is authenticated by default, the exceptions live in ONE named
 * allowlist, and each exception carries a comment explaining why.
 *
 * Three guards hang off this object, and each fails on a different mistake:
 *  1. `PublicPathsTest` freezes [ENTRIES]'s patterns as a written-out list, so ADDING a
 *     pattern is a visible diff in a test rather than one more line in a 30-line argument.
 *  2. The same test parses auth.md §8.3 and demands pattern-for-pattern, reason-for-reason
 *     equality — the doc is generated from here, and drift fails the build either way.
 *  3. `PublicRouteWalkerTest` (in `web`) walks every request mapping the module registers
 *     against these patterns and freezes the resulting PUBLIC handler set by name. A new
 *     controller that lands under a public glob fails that test, naming itself.
 *
 * The dispatcher-type permit (ASYNC/ERROR) is NOT here: it is not a path allowlist but a
 * statement about re-dispatches of an already-authorized request, and it keeps its own
 * KDoc at the call site in [SecurityConfig].
 */
object PublicPaths {
    /**
     * Every publicly reachable path pattern, in the order [SecurityConfig] declares them
     * and auth.md §8.3 renders them: the marketing site, the SEO cluster, the packaged
     * docs and skill, the crawler files, the probes, the login/OIDC surface, and the
     * static assets.
     */
    val ENTRIES: List<PublicPath> =
        listOf(
            // 033: the marketing site owns `/` (owner decision 2026-08-31) — public by
            // design; the signed-in dashboard moved to /dashboard. Constant content only
            // (Decision 4): no DB access, defended by cache headers — NO rate limiter
            // (033/D1). The login limiter keys on the CLIENT address since R8/T46
            // ([ClientAddressResolver] + `datapipelines.auth.trusted-proxies`), so pointing
            // it at `/` is now possible; the no-limiter decision stands on its own grounds
            // (immutable content, shared-cache TTL).
            PublicPath(
                "/",
                "The marketing home page: constant content, no datastore, no principal — public by design (033/D4).",
                "033",
            ),
            PublicPath(
                "/site/**",
                "The marketing site's own css/js/img assets; the design system it references rides /vendor/** below.",
                "033",
            ),
            // 073: the site's intent-cluster pages — one route per thing a searcher types.
            // Same shape as `/` and public for the same reason: GET-only, constant content,
            // the only live fact is the compile-time MCP tool count, and no request on them
            // touches a datastore or a principal. Enumerated rather than globbed, so a
            // future route under one of these prefixes cannot become public by accident.
            PublicPath(
                "/mcp-server-for-sql-databases",
                "Intent-cluster page: GET-only constant content whose only live fact is the compile-time MCP tool count.",
                "073",
            ),
            PublicPath(
                "/mcp-server/*",
                "Per-engine intent-cluster pages, one segment deep and enumerated by the page registry, not globbed open.",
                "073",
            ),
            PublicPath(
                "/add-mcp-server-to-claude-code",
                "Intent-cluster page: GET-only constant content, no datastore and no principal on the request.",
                "073",
            ),
            PublicPath(
                "/ai-data-pipeline",
                "Intent-cluster page: GET-only constant content, no datastore and no principal on the request.",
                "073",
            ),
            PublicPath(
                "/text-to-sql-agent",
                "Intent-cluster page: GET-only constant content, no datastore and no principal on the request.",
                "073",
            ),
            PublicPath(
                "/compare/*",
                "The comparison pages (airflow, dbt): GET-only constant content, one segment deep, no datastore.",
                "073",
            ),
            PublicPath(
                "/federated-query",
                "Intent-cluster page: GET-only constant content, no datastore and no principal on the request.",
                "073",
            ),
            // 089: the dp-lake product page — same shape and same reasoning as the 073
            // cluster pages above.
            PublicPath(
                "/dp-lake",
                "The dp-lake product page: same shape and same reasoning as the 073 intent-cluster pages.",
                "089",
            ),
            // 073: the in-product spec set, public. The viewer renders the Markdown packaged
            // in the jar — DocsCatalog's only collaborator is a ClassLoader, the controller
            // reads no principal and no workspace, and no route here reaches a datastore.
            // The identical content is already public in the AGPL repository on GitHub, so
            // this exposes nothing new; it moves ~25 pages of long-tail documentation from
            // GitHub's index to ours. Anonymous requests get the public chrome, signed-in
            // ones keep the app chrome (DocsController).
            PublicPath(
                "/docs",
                "The in-product spec index: packaged Markdown, no principal, no datastore, already public in the AGPL repo.",
                "073",
            ),
            PublicPath(
                "/docs/*",
                "One packaged spec per slug, rendered from the jar; the same text is already public on GitHub.",
                "073",
            ),
            // 095: the agent skill, raw. `/skill.md` and `/skill/<reference>.md` serve the
            // Markdown packaged in the jar — SkillController reads no principal, resolves no
            // workspace and touches no datastore, and the identical text is public in the
            // AGPL repository on GitHub. It is the MANUAL: a key would mean an agent cannot
            // learn to use its key correctly until after it has one. This is the delivery
            // for clients that speak no MCP — one curl into .agents/skills/ and the agent has
            // the manual for the version this deployment actually runs.
            PublicPath(
                "/skill.md",
                "The agent skill's core, raw: it is the MANUAL, so requiring a key would gate learning how to use the key.",
                "095",
            ),
            PublicPath(
                "/skill/*",
                "The skill's reference files, packaged in the jar and identical to the public AGPL repository's text.",
                "095",
            ),
            // 073: crawler infrastructure. robots.txt is a static file; sitemap.xml is
            // generated from the page registry and the packaged doc slugs — both are, by
            // definition, documents that must be readable without a login.
            PublicPath(
                "/robots.txt",
                "Crawler infrastructure: a document that is meaningless unless it is readable without a login.",
                "073",
            ),
            PublicPath(
                "/sitemap.xml",
                "Generated from the page registry and packaged doc slugs; a sitemap behind auth indexes nothing.",
                "073",
            ),
            PublicPath(
                "/health",
                "Liveness probe for orchestrators, which present no credential; flattened UP/DOWN plus version only.",
                "P3d",
            ),
            PublicPath(
                "/ready",
                "Readiness probe for orchestrators, which present no credential; flattened UP/DOWN plus version only.",
                "P3d",
            ),
            PublicPath(
                "/info",
                "Build info (version, build time, commit) — the deployment's own identity, no principal data.",
                "P3d",
            ),
            PublicPath(
                "/login",
                "The login page and the local password POST: the surface a caller uses BEFORE it has a credential.",
                "P3d",
            ),
            PublicPath(
                "/login/**",
                "Login error redirects and the OIDC callback subtree, reached before any session exists.",
                "P3d",
            ),
            PublicPath(
                "/oauth2/**",
                "The OIDC authorization redirect and callback endpoints, by definition pre-authentication.",
                "P3d",
            ),
            PublicPath(
                "/vendor/**",
                "Vendored static assets (design system, fonts, icons, Alpine, Cytoscape, htmx) served to the login page too.",
                "P3d",
            ),
            PublicPath(
                "/css/**",
                "The application stylesheets, which the unauthenticated login and error pages already render.",
                "P3d",
            ),
            PublicPath(
                "/js/**",
                "The application scripts, which the unauthenticated login and error pages already render.",
                "P3d",
            ),
            PublicPath(
                "/favicon.ico",
                "The site icon, requested by the browser on the login page before any credential exists.",
                "P3d",
            ),
            // P8: Boot's BasicErrorController with the shipped defaults — no stack trace, no
            // exception message, so the body is {timestamp,status,error,path}. It must be
            // reachable anonymously or an unauthenticated error becomes a redirect loop
            // through /login. The dispatcherType ERROR permit above covers the container's
            // own error dispatch; this covers a direct GET.
            PublicPath(
                "/error",
                "Boot's default error page with no stack trace or message; an anonymous error must not loop through login.",
                "P8",
            ),
        )

    /** The patterns alone, in declaration order — what [SecurityConfig] feeds `requestMatchers`. */
    val PATTERNS: List<String> = ENTRIES.map { it.pattern }
}
