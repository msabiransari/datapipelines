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
 * The GitHub star count the nav's `★ GitHub` badge renders (119 §C.1).
 *
 * A BUILD-TIME constant, deliberately: the badge is baked by the site export and by every
 * server render, and no visitor's browser ever fetches api.github.com (the CSP forbids
 * third-party requests, and a counter that phones GitHub from every page view is exactly
 * what the site's own "no phone-home" strip cell denies). Refreshed BY HAND at each
 * release — the value the dispatch that added it measured was **0**
 * (api.github.com/repos/msabiransari/datapipelines, 2026-09-11).
 */
const val GITHUB_STARS: Int = 0

/**
 * The project's contact address (owner, 2026-09-11) — the ONE string the footer's
 * "Contact" line, /pricing's talk-to-me paragraph and /security's report-a-vulnerability
 * line all render from. Never typed in a template: a mailto that drifts from this
 * constant is a mailto nobody answers, and the render test sweeps every page for any
 * `mailto:` that is not this address.
 */
const val CONTACT_EMAIL: String = "datapipelines.co@gmail.com"

/**
 * The project's GitHub addresses (127 §B.3) — the ONE spelling of each, beside
 * [CONTACT_EMAIL] for the same reason: a support link typed into a template drifts, and
 * the drift guard for a URL is the constant every render reads. The beta's three report
 * paths (the bug form, Discussions, the private advisory flow) and the repo address the
 * README-equivalents on the site point at all render from these.
 */
const val REPO_URL: String = "https://github.com/msabiransari/datapipelines"

/** The issue tracker — questions and ideas go to [DISCUSSIONS_URL], vulnerabilities to [ADVISORY_URL]. */
const val ISSUES_URL: String = "$REPO_URL/issues"

/** The bug report ISSUE FORM — what "Report a problem" opens everywhere the app and the site offer it. */
const val REPORT_PROBLEM_URL: String = "$ISSUES_URL/new?template=bug_report.yml"

/** Questions and ideas (a GitHub repo switch the owner flips — the handback lists it). */
const val DISCUSSIONS_URL: String = "$REPO_URL/discussions"

/** The private vulnerability-reporting flow (likewise an owner-side repo switch). */
const val ADVISORY_URL: String = "$REPO_URL/security/advisories/new"

/**
 * The release stage, in the one sentence every page that says it renders (127 §B.1): the
 * homepage hero and the site footer. A string the templates read — never typed into a
 * page, so the day the beta ends the wording changes HERE and nowhere else.
 */
const val RELEASE_STAGE: String = "Public beta — self-hosted, AGPL, actively developed."

/** The call to action that follows [RELEASE_STAGE], linking to the support section on `/faq`. */
const val RELEASE_STAGE_CTA: String = "Report what breaks."

/** The host of [SITE_ORIGIN], without the scheme — what a request's server name is compared against. */
val SITE_ORIGIN_HOST: String = SITE_ORIGIN.removePrefix("https://")

/**
 * True when the request came in on the PUBLIC site's own origin (119 §C.1). On the public
 * site the nav's last item invites a visitor to **try the live demo**; on a customer's own
 * deployment the same `/login` route is how their people sign in, so it reads "Sign in".
 * The same decision the canonical tag makes — one origin is the published site — applied
 * to a label instead of a URL.
 */
fun isPublicOrigin(serverName: String?): Boolean = serverName != null && serverName.equals(SITE_ORIGIN_HOST, ignoreCase = true)

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
 * The facts a single engine page states (073 §B): the `/mcp-server/{engine}` routes are ONE
 * template over this row, because everything that differs between them is data — the dialect
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
) {
    /**
     * The indefinite article [displayName] takes in prose (160): "an Oracle connection",
     * "an H2 datasource", "a MySQL connection". H2 is pronounced aitch, so it takes "an"
     * despite the consonant letter; the rest follow their initial letter.
     */
    val article: String
        get() =
            when {
                displayName == "H2" -> "an"
                displayName.first().uppercase() in setOf("A", "E", "I", "O", "U") -> "an"
                else -> "a"
            }
}

/**
 * The site's page registry (073) — the single list of what is public, indexable and
 * therefore in the sitemap.
 *
 * The searcher's vocabulary drives every title here, measured rather than guessed
 * (`notes/2026-09-04-seo-keywords.md` addendum, DataForSEO 2026-09-04): the pattern that
 * carries the volume is *"{engine} mcp server"*, so that phrasing leads the engine
 * titles and *"SQL MCP server"* leads the pillar. Phrasings with no measured volume
 * ("connect claude code to postgres") appear in H2s and body copy, never in a title.
 *
 * Adding a page means adding a row here AND a handler in [SitePagesController]; the
 * sitemap, the SEO limit guard and the anonymous 200-sweep then cover it with nothing to
 * remember — which is the point of the list existing at all.
 */
object SitePages {
    /** The site's derived numbers — built from the code that owns each fact, never a literal (124 §A). */
    private val facts: SiteFacts = SiteFacts.current()

    /**
     * `GET /` — the homepage. 145 retargeted it from the customer-facing-data buyer to the
     * small or medium-sized organisation that struggles to understand its data: the title and
     * H1 speak the OUTCOME (clear answers from the data they already have) and the engineering
     * mechanism stays one hop away on /how-it-works. The old primary phrase ("no data team
     * required") was a 115 measurement; the 145 approved preview's title is the searcher's
     * plain question, and the pillar page keeps "SQL MCP server" as before.
     */
    val HOME =
        SitePage(
            path = "/",
            title = "Clear answers from your business data | datapipelines.co",
            // ≤ 155 (SiteSeoMetaTest): the preview's sentence, with the audience clause kept.
            description =
                "Turn database data into reviewed datasets, APIs and inputs for your BI tool. " +
                    "Open-source, self-hosted data pipelines for small and medium-sized teams.",
            view = "site/index",
        )

    /** The cluster-1 pillar: "SQL MCP server" (590/mo) + "database MCP server" (110/mo). */
    val PILLAR =
        SitePage(
            path = "/mcp-server-for-sql-databases",
            title = "SQL MCP server — one database MCP server, ${facts.engineCountWord} engines",
            description =
                "A database MCP server your agent uses instead of your production credentials: " +
                    "${facts.engineCountWord} SQL engines, read-only by default, scoped keys, every call audited.",
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
            title = "Apache Airflow alternative for SQL pipelines — when to use each",
            description =
                "An honest comparison: Airflow orchestrates scheduled workflows across a whole platform. " +
                    "We run agent-authored SQL across several operational databases.",
            view = "site/compare-airflow",
        )

    /** "dbt alternative" + "alternative to dbt" — 210/mo, $17.61 CPC. */
    val COMPARE_DBT =
        SitePage(
            path = "/compare/dbt",
            title = "dbt alternative without a warehouse — when to use each",
            description =
                "An honest comparison: dbt transforms inside one warehouse and owns the modelling layer. " +
                    "We join across operational databases without landing anything.",
            view = "site/compare-dbt",
        )

    /** "federated query" (170) + "data virtualization tool" (170, $48 CPC). */
    val FEDERATED_QUERY =
        SitePage(
            path = "/federated-query",
            title = "Federated query and data virtualization — cross-database joins",
            description =
                "Join Postgres to MySQL to SQLite in one pipeline. Each source is read in place, " +
                    "the join runs in an in-memory staging database, and nothing is landed.",
            view = "site/federated-query",
        )

    /**
     * dp-lake (089 §G) — the product page written from the thesis note
     * (`notes/2026-09-07-dp-lake-thesis.md`): the headline IS the note's marketing line.
     * It states exactly what ships — Parquet/Iceberg on S3 read in place, read-only, DuckDB
     * as the engine, our own catalog — and nothing more: no dashboards, no scheduler.
     */
    val DP_LAKE =
        SitePage(
            path = "/dp-lake",
            title = "dp-lake — query Parquet and Iceberg on S3 with SQL",
            description =
                "Your data is already in S3. Ask it a question: Parquet and Iceberg tables read in " +
                    "place, no warehouse, joined to your databases and served as an API.",
            view = "site/dp-lake",
        )

    // ---- Site v2 (2026-09-09): the intent pages. Titles ≤ 70, descriptions ≤ 155 (SiteSeoMetaTest).
    val FAQ =
        SitePage(
            path = "/faq",
            title = "FAQ — datapipelines.co, the MCP pipeline server, in questions",
            description =
                "What it is, what an agent can and cannot do, how the API works, where it runs, and what is on the " +
                    "roadmap — every answer cites the spec it rests on.",
            view = "site/faq",
        )
    val ROADMAP =
        SitePage(
            path = "/roadmap",
            title = "Roadmap — shipped, planned, and later | datapipelines.co",
            description =
                "Shipped: MCP server, ${facts.engineCountWord} engines, published APIs. Planned: scheduler, embedded dashboards, " +
                    "JSONata and JavaScript nodes. Later: lake extracts, alerts.",
            view = "site/roadmap",
        )
    val SECURITY =
        SitePage(
            path = "/security",
            title = "AI agent database access, made safe — scoped keys, read-only, audited",
            description =
                "How an AI agent reaches your databases without a password: scoped keys, read-only datasources, " +
                    "encrypted credentials, human release, a full audit log.",
            view = "site/security",
        )
    val PUBLISHED_API =
        SitePage(
            path = "/published-api",
            // "data api" is the measured head term (SEO addendum 4) — it leads the title and the H1.
            title = "A data API from a SQL query — published endpoints | datapipelines.co",
            description =
                "A released pipeline becomes a versioned GET endpoint with bound parameters and " +
                    "path-scoped keys. Data to API in a day, nothing to deploy.",
            view = "site/published-api",
        )
    val MCP_TOOLS =
        SitePage(
            path = "/mcp-tools",
            title = "The MCP tools — what an agent can do against your databases",
            description =
                "Every tool the MCP server exposes, generated from its own catalogue: schema reads, catalogue " +
                    "statistics, the SQL probe, drafts, execution, results.",
            view = "site/mcp-tools",
        )
    val TABLEAU =
        SitePage(
            path = "/tableau",
            title = "Using datapipelines with Tableau — what ships today, what is planned",
            description =
                "Feed Tableau a governed dataset from Postgres, MySQL, SQL Server and S3 without a warehouse, " +
                    "as an API or a table it reads. What ships now, what is next.",
            view = "site/tableau",
        )
    val TABLEAU_GOVERNED_DATASET =
        SitePage(
            path = "/tableau/governed-dataset",
            title = "Publish a governed dataset for Tableau from several databases",
            description =
                "Step by step: an agent authors the cross-database pipeline, a person releases it, and Tableau " +
                    "reads the result as a JSON endpoint or a live table.",
            view = "site/tableau-governed-dataset",
        )

    // ---- Site v2 batch 2 (111): seven more intent pages, same rules as the batch above.

    /** "fivetran alternative" + "airbyte vs" + "elt without a warehouse". */
    val COMPARE_FIVETRAN =
        SitePage(
            path = "/compare/fivetran-airbyte",
            title = "Fivetran or Airbyte vs datapipelines — sync or query at the source",
            description =
                "ELT tools copy your data into a warehouse on a schedule. datapipelines queries at the source, " +
                    "joins in a per-run scratch database; the result is an API.",
            view = "site/compare-fivetran-airbyte",
        )

    /** "do i need a data warehouse" + "just use postgres" + "postgres as a data warehouse". */
    val COMPARE_POSTGRES_ONLY =
        SitePage(
            path = "/compare/postgres-only",
            title = "Do you need a data warehouse? When Postgres alone is not enough",
            description =
                "For one database, Postgres alone is right. The second source, the agent needing read-only " +
                    "access, the partner API and the release step arrive next.",
            view = "site/compare-postgres-only",
        )

    /** "tableau prep alternative" + "tableau prep vs sql". */
    val TABLEAU_PREP =
        SitePage(
            path = "/tableau/prep-vs-pipelines-as-code",
            title = "Tableau Prep or pipelines as code — a flow is a file, this is code",
            description =
                "A Tableau Prep flow is a file one tool opens. A pipeline here is a JSON document of SQL nodes " +
                    "you can diff, version, hand to an agent and release.",
            view = "site/tableau-prep-vs-pipelines",
        )

    /**
     * "scheduled extract to s3" + "data driven alerts from sql" + "embedded dashboards row level
     * security" — a ROADMAP page: every roadmap item on it says roadmap, planned or later.
     */
    val TABLEAU_ROADMAP =
        SitePage(
            path = "/tableau/extracts-alerts-dashboards",
            title = "Embedded dashboards, extracts to S3, alerts — the roadmap",
            description =
                "Embedded dashboards, scheduled extracts to your own bucket, email alerts: what the roadmap " +
                    "dates, what ships today, and what is not planned.",
            view = "site/tableau-extracts-alerts-dashboards",
        )

    /** "client reporting api" + "white label reporting" + "one pipeline per client". */
    val FOR_AGENCIES =
        SitePage(
            path = "/for/agencies",
            title = "A client reporting API per client — datapipelines for agencies",
            description =
                "One workspace and one key per client, an agent drafting every pipeline, a release you can name " +
                    "in the invoice, an API for the portal today.",
            view = "site/for-agencies",
        )

    /** "product analytics without a warehouse" + "customer facing analytics api" + "embedded analytics". */
    val FOR_SAAS_TEAMS =
        SitePage(
            path = "/for/saas-teams",
            title = "Embedded analytics without a warehouse — the API ships today",
            description =
                "Show customers their own numbers in your app: an agent authors the pipeline, a person " +
                    "releases it, a versioned endpoint serves each viewer.",
            view = "site/for-saas-teams",
        )

    /** "one analyst five databases" + "join data across databases without etl" + "ai sql assistant with governance". */
    val FOR_ANALYSTS =
        SitePage(
            path = "/for/analysts",
            title = "The AI SQL assistant with governance — one analyst, five databases",
            description =
                "Ask in your words, get a pipeline you can read: the agent drafts the SQL, the join crosses " +
                    "engines in a scratch database, a release makes it an API.",
            view = "site/for-analysts",
        )

    /**
     * 115 §A.3 — the engineering page. The buyer-facing home page keeps its vocabulary below
     * the fold free of it; every term the fold guard bans lives here instead (the guard
     * asserts the move, not a removal). FAQ: `SiteFaqs.APIS_AND_OPERATIONS`, the most
     * engineering-shaped existing list.
     */
    val HOW_IT_WORKS =
        SitePage(
            path = "/how-it-works",
            // 115 §A.3's title is 79 chars against the 70 pin, so "the MCP server" tightens
            // to "MCP"; the keywords and the suffix both stay.
            title = "How it works — pipelines, federated joins, MCP | datapipelines.co",
            // 115 §A.3's description is 207 chars against the 155 pin; this is its front half,
            // which carries the three facts the page exists to state.
            description =
                "An agent authors a versioned SQL pipeline over MCP, the platform runs it where the " +
                    "data lives, and a release becomes a GET endpoint.",
            view = "site/how-it-works",
        )

    /**
     * 116 — the demo-data page: what the three published sample-data families hold, where
     * each came from, what to ask it, and the licence each ships under. Its tables render
     * from the manifests vendored at `resources/site/demo/` (pinned to the deploy versions
     * by `SiteDemoDataGuardsTest`), never from typed rows.
     */
    val DEMO_DATA =
        SitePage(
            path = "/demo-data",
            // 116 §B's title is 72 chars against the 70 pin, so "and" tightens away; the
            // search phrases (NYC taxi, rideshare, trade, weather) all stay.
            title = "Demo data — NYC taxi, rideshare, US trade, weather | datapipelines.co",
            // 116 §B's description is 159 chars against the 155 pin; the same sentence with
            // the two words its tail does not need.
            description =
                "Three public datasets, published as read-only artifacts one command loads: what each table holds, " +
                    "where it came from, what to ask, and the licence.",
            view = "site/demo-data",
        )

    /**
     * 119 §C.4 — the pricing page: there is no price. The page states the licence, what
     * "free" honestly costs (your servers, your backups, your upgrades), the no-paid-tier
     * promise in the roadmap's dated `status` markup, and how to reach a person.
     */
    val PRICING =
        SitePage(
            path = "/pricing",
            title = "Pricing — free and open source | datapipelines.co",
            // 119 §C.4's register row, ≤ 155 (SiteSeoMetaTest's pin).
            description =
                "There is no price: AGPL-3.0, self-hosted on your infrastructure, every feature, no seat count. " +
                    "What free costs you honestly, and how to reach a person.",
            view = "site/pricing",
        )

    /**
     * 119 §B — the learned semantic layer: the category term buyers and engineers search,
     * answered honestly. Ours is not a model somebody writes — it is facts the agent
     * records with the query that proved them, read back inline where it is looking.
     */
    val SEMANTIC_LAYER =
        SitePage(
            path = "/semantic-layer",
            // The brief's description is 174 chars against the 155 pin (SiteSeoMetaTest) —
            // trimmed from the end as the brief sanctions; every key phrase stays.
            title = "Semantic layer, learned by the agent — open source | datapipelines.co",
            description =
                "An open-source semantic layer your agent builds from your data, recording what it learned with " +
                    "the query that proved it. Drift is detected, never guessed.",
            view = "site/semantic-layer",
        )

    /**
     * 145 §2 — the use-cases hub: operations, product and analytics teams, one worked
     * example each, linking on to the existing audience pages (/for/agencies, /for/saas-teams,
     * /for/analysts) and the Tableau hub rather than absorbing their specialist detail.
     */
    val USE_CASES =
        SitePage(
            path = "/use-cases",
            title = "Use cases — operations, product and analytics teams | datapipelines.co",
            description =
                "Where datapipelines fits: a combined operations view, a data API inside your product, " +
                    "and a reviewed dataset behind your Tableau workbook.",
            view = "site/use-cases",
        )

    /**
     * 145 §2 — the directory. Generated from THIS registry and the packaged docs catalog
     * ([SiteExplore]), never typed: a page added here is in the directory the moment it
     * exists, and `SiteExploreTest` holds the directory equal to the registry plus the docs.
     */
    val EXPLORE =
        SitePage(
            path = "/explore",
            title = "Explore — product, engines, comparisons and docs | datapipelines.co",
            description =
                "Every page on the site, generated from its registry: product pages, the SQL MCP server " +
                    "and each engine, comparisons and Tableau, and the packaged docs.",
            view = "site/explore",
        )

    /** The route prefix the engine pages share. */
    const val ENGINE_PREFIX: String = "/mcp-server/"

    /**
     * Every dialect has its page (124 §B — the owner's eight-engines ruling: H2 is a
     * legitimate engine an engineer uses like Postgres or MySQL, LAKE is dp-lake, and tempdb
     * is implicit and never counted or listed). The measured six lead in search-volume order
     * (postgres 720 · sql-server 640 · mysql 320 · oracle 260 · sqlite 140 · duckdb 90), H2
     * and dp-lake follow. `SiteEngineFactsGuardTest` holds this set EQUAL to
     * `Dialect.entries`: a ninth dialect cannot ship without a page, and a page cannot
     * outlive its dialect.
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
                demo = "nyc/mobility/revenue_by_borough",
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
                demo = "nyc/mobility/rainy_vs_dry_ridership",
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
                demo = "nyc/mobility/revenue_by_borough",
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
            EngineFacts(
                slug = "h2",
                displayName = "H2",
                dialect = "H2",
                driver = "com.h2database:h2",
                license = "MPL 2.0 / EPL 1.0",
                bundled = true,
                otherwise = "",
                jdbcUrlExample = "jdbc:h2:tcp://db.internal:9092/~/analytics",
                demo = null,
                demoHref = null,
            ),
            EngineFacts(
                slug = "dp-lake",
                displayName = "dp-lake",
                dialect = "LAKE",
                driver = "org.duckdb:duckdb_jdbc",
                license = "MIT",
                bundled = true,
                otherwise = "",
                jdbcUrlExample = "jdbc:duckdb: — embedded; tables register in dp-catalog (docs/datasources.md §8C), no URL to paste",
                demo = "nyc/mobility/taxi_vs_rideshare",
                demoHref = "/dp-lake",
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
                DP_LAKE,
                FAQ,
                ROADMAP,
                SECURITY,
                PUBLISHED_API,
                MCP_TOOLS,
                TABLEAU,
                TABLEAU_GOVERNED_DATASET,
                COMPARE_FIVETRAN,
                COMPARE_POSTGRES_ONLY,
                TABLEAU_PREP,
                TABLEAU_ROADMAP,
                FOR_AGENCIES,
                FOR_SAAS_TEAMS,
                FOR_ANALYSTS,
                HOW_IT_WORKS,
                DEMO_DATA,
                PRICING,
                SEMANTIC_LAYER,
                USE_CASES,
                EXPLORE,
            )

    /** The cluster pages the homepage links, in nav order (the homepage links to itself nowhere). */
    val NAV: List<SitePage> = ALL.filter { it.path != HOME.path }
}
