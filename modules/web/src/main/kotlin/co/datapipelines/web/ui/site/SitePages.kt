package co.datapipelines.web.ui.site

/**
 * The public origin every canonical URL points at (073).
 *
 * A constant, not configuration, and deliberately so: `rel="canonical"` names the ONE
 * address search engines should index, and that address is a property of the published
 * site, not of the deployment rendering it. A self-hosted copy behind some other host
 * emitting its own canonicals would ask Google to index a private instance — the opposite
 * of what the tag is for. Nothing else here depends on the host: every in-page link is
 * root-relative and resolves against whatever origin served it.
 */
const val SITE_ORIGIN: String = "https://datapipelines.co"

/**
 * One indexable public page: the route it is served on, the `<title>` and meta description
 * the searcher reads in the result, and the Thymeleaf view that renders it.
 *
 * [title] and [description] live HERE rather than in the templates because three consumers
 * need the same strings — the page's own `<head>`, `/sitemap.xml`'s URL set, and the SEO
 * guard that asserts the display limits — and a fourth copy in the template is how the three
 * drift. The templates read them off the model; nothing hardcodes a title.
 */
data class SitePage(
    val path: String,
    val title: String,
    val description: String,
    val view: String,
) {
    /** The absolute address this page asks to be indexed under. */
    val canonical: String get() = SITE_ORIGIN + path
}

/**
 * One engine's page facts (073 §B): the six `/mcp-server/{engine}` routes are ONE template
 * over this row, because everything that differs between them is data — the dialect
 * constant, the JDBC driver and its license, whether that driver ships in the published
 * image, a URL example, and which demo pipeline (if any) reads that engine.
 *
 * Every field is transcribed from the dialect catalog in `docs/datasources.md` §4.1 and
 * the driver matrix in `docs/deployment.md` §3.5; the template carries the `claim:` comment
 * that cites both, and `SiteEngineFactsGuardTest` reads those two tables and asserts every
 * dialect, driver coordinate, license and bundled flag against them — so the page cannot
 * drift from the specs it quotes.
 */
data class EngineFacts(
    val slug: String,
    val displayName: String,
    val dialect: String,
    val driver: String,
    val license: String,
    val bundled: Boolean,
    val otherwise: String,
    val jdbcUrlExample: String,
    val demo: String?,
    /** Where [demo] is shown, or null when no seeded pipeline reads this engine. */
    val demoHref: String?,
)

/**
 * The site's page registry (073) — the single list of what is public, indexable and
 * therefore in the sitemap.
 *
 * The searcher's vocabulary drives every title here, measured rather than guessed
 * (`notes/2026-09-04-seo-keywords.md` addendum, DataForSEO 2026-09-04): the pattern that
 * carries the volume is *"{engine} mcp server"*, so that phrasing leads the six engine
 * titles and *"SQL MCP server"* leads the pillar. Phrasings with no measured volume
 * ("connect claude code to postgres") appear in H2s and body copy, never in a title.
 *
 * Adding a page means adding a row here AND a handler in [SitePagesController]; the
 * sitemap, the SEO limit guard and the anonymous 200-sweep then cover it with nothing to
 * remember — which is the point of the list existing at all.
 */
object SitePages {
    /** `GET /` — the homepage. Its `<h1>` stays the poster's line; the TITLE speaks the search. */
    val HOME =
        SitePage(
            path = "/",
            title = "MCP server for SQL databases — governed pipelines | datapipelines.co",
            description =
                "One MCP server for Postgres, MySQL, SQL Server, Oracle, DuckDB and SQLite: " +
                    "read-only by default, scoped keys per agent, every call audited. Self-hosted.",
            view = "site/index",
        )

    /** The cluster-1 pillar: "SQL MCP server" (590/mo) + "database MCP server" (110/mo). */
    val PILLAR =
        SitePage(
            path = "/mcp-server-for-sql-databases",
            title = "SQL MCP server — one database MCP server, six engines",
            description =
                "A database MCP server your agent uses instead of your production credentials: " +
                    "six SQL engines, read-only by default, scoped keys, every call audited.",
            view = "site/pillar",
        )

    /** "add mcp server to claude code" (660/mo) + "claude code mcp server" (880) + "cursor mcp server" (720). */
    val ADD_TO_CLAUDE_CODE =
        SitePage(
            path = "/add-mcp-server-to-claude-code",
            title = "Add an MCP server to Claude Code, Cursor or Copilot",
            description =
                "How to add the datapipelines.co MCP server to Claude Code, Cursor and GitHub Copilot: " +
                    "mint a scoped API key, paste one config block, check the tool list.",
            view = "site/add-mcp-server",
        )

    /** "ai data pipeline" — 210/mo at a $54.62 CPC, the highest-value term measured. */
    val AI_DATA_PIPELINE =
        SitePage(
            path = "/ai-data-pipeline",
            title = "AI data pipeline — authored by an agent, run under review",
            description =
                "What an AI data pipeline is here: an agent reads your real schemas, writes the SQL, " +
                    "and saves it as a versioned pipeline that runs governed and audited.",
            view = "site/ai-data-pipeline",
        )

    /** "text to sql" (590) + "text to sql agent" (110) — the after-state, not the generation. */
    val TEXT_TO_SQL_AGENT =
        SitePage(
            path = "/text-to-sql-agent",
            title = "Text-to-SQL agent — from a question to a pipeline you rerun",
            description =
                "Text-to-SQL answers once. Give the agent real schemas, a versioned pipeline and " +
                    "readable failures, and the answer becomes something your team can rerun.",
            view = "site/text-to-sql-agent",
        )

    /** "airflow alternative" + "apache airflow alternative" + "alternative to airflow" — 360/mo combined. */
    val COMPARE_AIRFLOW =
        SitePage(
            path = "/compare/airflow",
            title = "datapipelines.co vs Apache Airflow — when to use each",
            description =
                "An honest comparison: Airflow orchestrates scheduled workflows across a whole platform. " +
                    "We run agent-authored SQL across several operational databases.",
            view = "site/compare-airflow",
        )

    /** "dbt alternative" + "alternative to dbt" — 210/mo, $17.61 CPC. */
    val COMPARE_DBT =
        SitePage(
            path = "/compare/dbt",
            title = "datapipelines.co vs dbt — when to use each",
            description =
                "An honest comparison: dbt transforms inside one warehouse and owns the modelling layer. " +
                    "We join across operational databases without landing anything.",
            view = "site/compare-dbt",
        )

    /** "federated query" (170) + "data virtualization tool" (170, $48 CPC). */
    val FEDERATED_QUERY =
        SitePage(
            path = "/federated-query",
            title = "Federated query without a warehouse — cross-database joins",
            description =
                "Join Postgres to MySQL to SQLite in one pipeline. Each source is read in place, " +
                    "the join runs in an in-memory staging database, and nothing is landed.",
            view = "site/federated-query",
        )

    /** The route prefix the six engine pages share. */
    const val ENGINE_PREFIX: String = "/mcp-server/"

    /**
     * The six engines with their own page, in the order the measured volume ranks them
     * (postgres 720 · sql-server 640 · mysql 320 · oracle 260 · sqlite 140 · duckdb 90).
     *
     * H2 is deliberately absent: it is the staging engine, not a database anyone runs their
     * business on, and a page targeting "h2 mcp server" would target nothing.
     */
    val ENGINES: List<EngineFacts> =
        listOf(
            EngineFacts(
                slug = "postgres",
                displayName = "Postgres",
                dialect = "POSTGRES",
                driver = "org.postgresql:postgresql",
                license = "BSD-2-Clause",
                bundled = true,
                otherwise = "",
                jdbcUrlExample = "jdbc:postgresql://db.internal:5432/analytics",
                demo = "revenue_by_borough",
                demoHref = "/federated-query",
            ),
            EngineFacts(
                slug = "sql-server",
                displayName = "SQL Server",
                dialect = "MSSQL",
                driver = "com.microsoft.sqlserver:mssql-jdbc",
                license = "MIT",
                bundled = true,
                otherwise = "",
                jdbcUrlExample = "jdbc:sqlserver://db.internal:1433;databaseName=analytics",
                demo = null,
                demoHref = null,
            ),
            EngineFacts(
                slug = "mysql",
                displayName = "MySQL",
                dialect = "MYSQL",
                driver = "com.mysql:mysql-connector-j",
                license = "GPL-2.0 with FOSS exception",
                bundled = false,
                otherwise = "Rebuild with ./gradlew -Pmysql bootJar, or drop mysql-connector-j.jar into lib/",
                jdbcUrlExample = "jdbc:mysql://db.internal:3306/analytics",
                demo = "rainy_vs_dry_ridership",
                demoHref = "/#demo",
            ),
            EngineFacts(
                slug = "oracle",
                displayName = "Oracle",
                dialect = "ORACLE",
                driver = "com.oracle.database.jdbc:ojdbc11",
                license = "OTN",
                bundled = false,
                otherwise = "Rebuild with ./gradlew -Poracle bootJar, or drop ojdbc11.jar into lib/",
                jdbcUrlExample = "jdbc:oracle:thin:@db.internal:1521/ORCLPDB1",
                demo = null,
                demoHref = null,
            ),
            EngineFacts(
                slug = "sqlite",
                displayName = "SQLite",
                dialect = "SQLITE",
                driver = "org.xerial:sqlite-jdbc",
                license = "Apache-2.0",
                bundled = true,
                otherwise = "",
                jdbcUrlExample = "jdbc:sqlite:/data/reference.db",
                demo = "revenue_by_borough",
                demoHref = "/federated-query",
            ),
            EngineFacts(
                slug = "duckdb",
                displayName = "DuckDB",
                dialect = "DUCKDB",
                driver = "org.duckdb:duckdb_jdbc",
                license = "MIT",
                bundled = true,
                otherwise = "",
                jdbcUrlExample = "jdbc:duckdb:/data/warehouse.duckdb",
                demo = null,
                demoHref = null,
            ),
        )

    /** The engine page for [slug], or null — the controller's 404 branch. */
    fun engine(slug: String): EngineFacts? = ENGINES.firstOrNull { it.slug == slug }

    /** The `SitePage` view of an engine row: same registry contract, so the sitemap needs no special case. */
    fun enginePage(facts: EngineFacts): SitePage =
        SitePage(
            path = ENGINE_PREFIX + facts.slug,
            title = "${facts.displayName} MCP server — governed, read-only, self-hosted",
            description =
                "Connect Claude Code, Cursor or any MCP client to ${facts.displayName} — read-only by " +
                    "default, one scoped key per agent, every call audited. Self-hosted, open source.",
            view = "site/engine",
        )

    /** Every page in the registry, homepage first — the sitemap's source and the guards' sweep set. */
    val ALL: List<SitePage> =
        listOf(HOME, PILLAR) +
            ENGINES.map(::enginePage) +
            listOf(
                ADD_TO_CLAUDE_CODE,
                AI_DATA_PIPELINE,
                TEXT_TO_SQL_AGENT,
                COMPARE_AIRFLOW,
                COMPARE_DBT,
                FEDERATED_QUERY,
            )

    /** The cluster pages the homepage links, in nav order (the homepage links to itself nowhere). */
    val NAV: List<SitePage> = ALL.filter { it.path != HOME.path }
}
