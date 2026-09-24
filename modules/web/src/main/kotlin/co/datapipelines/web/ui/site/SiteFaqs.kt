package co.datapipelines.web.ui.site

/**
 * The site's FAQ content — the product introduction in question-and-answer form (owner,
 * 2026-09-09: "Targeted, should have a great product introduction in Q/A form").
 *
 * Each answer names the doc it rests on, in the text the reader sees: the sentence Google may
 * quote as a snippet is a sentence a reader can verify one click away. Groups are the
 * page-sized slices; [HOME] is the eight buyer questions the home page carries (115), [ALL] is
 * the `/faq` page in reading order, buyer first. Every answer is a fact about what SHIPS today
 * — the roadmap has its own page and the answers that touch it say "roadmap" in as many words.
 */
object SiteFaqs {
    /** The site's derived numbers — the engine count and names below come from the catalogs, not from prose (124 §A). */
    private val facts: SiteFacts = SiteFacts.current()

    val WHAT_IT_IS: List<FaqEntry> =
        listOf(
            FaqEntry(
                "What is datapipelines.co, in one sentence?",
                "A self-hosted server that lets an AI coding agent, whether Claude Code, Cursor, Copilot or any MCP client, author SQL " +
                    "data pipelines across your databases, while people keep the release button; every released pipeline can be " +
                    "published as a versioned HTTP API. The tool surface is specified in docs/mcp-server.md §6.",
                "docs/mcp-server.md §6",
            ),
            FaqEntry(
                "What is a pipeline here: a DAG, a notebook, a dbt project?",
                "A JSON document: named nodes, each a SQL template run against one datasource, or against the per-run scratch " +
                    "engine, with explicit dependencies between them. The executor orders the graph, runs independent nodes " +
                    "in parallel, stages each result, and hands the last node's rows to the caller. The contract is " +
                    "docs/pipeline-contract.md §3 and the execution model docs/dag-executor.md §5.",
                "docs/pipeline-contract.md §3",
            ),
            FaqEntry(
                "Which databases does it talk to?",
                "${facts.engines}. dp-lake is Parquet and Iceberg on S3 read in place by an embedded " +
                    "DuckDB. One pipeline can mix all of them; each node runs where its data lives. " +
                    "The dialect matrix with driver and licence notes is docs/datasources.md §4.",
                "docs/datasources.md §4",
            ),
            FaqEntry(
                "Do I need a data warehouse?",
                "No. Every node runs at its source and streams its result into a per-run scratch engine, H2, created for " +
                    "the run and dropped after it, where the joining and shaping happen. The demo joins Postgres trips, MySQL " +
                    "weather, SQLite zones and Parquet rideshare data in one run. How staging works is docs/staging.md §3.",
                "docs/staging.md §3",
            ),
            FaqEntry(
                "Is it open source, and what may I do with it?",
                "Yes: AGPL-3.0. Run it on your own infrastructure for your own company or your clients, read and change " +
                    "every line, keep your pipelines in your own database with no per-seat licence. The one obligation is " +
                    "the AGPL's: if you offer a modified server to others over a network, you publish the changes. The " +
                    "licence and the packaging are described in docs/deployment.md §10.",
                "docs/deployment.md §10",
            ),
        )

    val AGENTS_AND_SECURITY: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Does my agent get my database password?",
                "No. The agent holds an API key to datapipelines; the server holds the datasource credentials, encrypted at " +
                    "rest, and never returns them, not through a tool and not through the UI. A datasource can be marked " +
                    "read-only so write-shaped nodes are refused at save time. Credential storage is docs/datasources.md §7 and " +
                    "the read-only flag docs/datasources.md §3.",
                "docs/datasources.md §7",
            ),
            FaqEntry(
                "How is this different from a Postgres MCP server?",
                "A single-database MCP server gives one agent one database and usually one connection string on a laptop. " +
                    "This gives the agent all of your databases through one revocable key, a scratch engine to join their results, " +
                    "versioned drafts a person reviews, an audit trail per call, and an HTTP API for what it builds. Every " +
                    "tool is specified in docs/mcp-server.md §6.2.",
                "docs/mcp-server.md §6.2",
            ),
            FaqEntry(
                "What can an agent do, and what can it never do?",
                "It can read schemas, catalogue statistics and indexes, probe a SELECT, create and iterate a draft pipeline, " +
                    "run it, read the result, and cancel its own runs. It can never release a version, never read a " +
                    "credential, and never touch a datasource marked read-only with a write. Its key holds your role, capped " +
                    "at author, and the permission catalog is docs/auth.md §7.6.",
                "docs/auth.md §7.6",
            ),
            FaqEntry(
                "Is every agent action logged?",
                "Yes. Every MCP tool call is written to the audit log with the key that made it and the tool, never the " +
                    "SQL text, row data or parameter values, which are your data; every lifecycle verb records who did it " +
                    "and through which channel it came, being session, API key or MCP. The audit log is docs/auth.md §10 and the MCP " +
                    "events docs/mcp-server.md §10.",
                "docs/auth.md §10",
            ),
            FaqEntry(
                "Can an agent release a pipeline to production?",
                "No, by design. Agents create and iterate drafts; a person releases. A released version is immutable, so a " +
                    "later edit becomes a new draft, and a published endpoint serves the version you point it at. The " +
                    "lifecycle is docs/versioning.md §3.",
                "docs/versioning.md §3",
            ),
        )

    val APIS_AND_OPERATIONS: List<FaqEntry> =
        listOf(
            FaqEntry(
                "What does “published endpoint” mean?",
                "A released pipeline gets a URL of its own, /api/<your-namespace>/<version>/<path>; a GET runs it " +
                    "with the query string bound to its declared " +
                    "parameters and returns the rows as JSON. Keys are bound to a path prefix, so a partner can be given one " +
                    "endpoint and nothing else. The serving contract is docs/rest-api.md §19.",
                "docs/rest-api.md §19",
            ),
            FaqEntry(
                "From data to an API in a day: is that real?",
                "For the API half, today: an agent authors the pipeline against your databases, runs it, fixes what fails, " +
                    "you review and release, and the endpoint exists. No deployment, no service to write, no gateway to " +
                    "configure. Dashboards your agent creates and you embed in your own product are a planned roadmap item; the " +
                    "roadmap page says so. Publishing is docs/rest-api.md §19.",
                "docs/rest-api.md §19",
            ),
            FaqEntry(
                "Where does it run?",
                "One Docker image beside a PostgreSQL metadata database and Redis. ./app.sh --start --demo nyc builds " +
                    "the image, downloads the published sample data and brings the stack up; the reference compose file " +
                    "and the demo quickstart are docs/deployment.md Appendix A and Appendix B.",
                "docs/deployment.md §Appendix B",
            ),
            FaqEntry(
                "What happens when a query is too slow?",
                "Every statement carries a query timeout and the run an overall deadline; a node that outlives its budget " +
                    "fails with pipeline.node.query_timeout and the elapsed time, and the SQL probe returns the plan, so the " +
                    "agent fixes the shape instead of guessing. The limits and cancellation are docs/dag-executor.md §5.",
                "docs/dag-executor.md §5",
            ),
            FaqEntry(
                "Is it a Tableau or dbt replacement?",
                "Not today. It sits beside them: it can feed Tableau a governed dataset through an API or as Parquet on " +
                    "your own bucket, and it replaces the warehouse-plus-dbt step for teams that never wanted one. " +
                    "Dashboards, scheduling and alerts are on the roadmap page, undated. The comparison pages cite " +
                    "docs/versioning.md §3 for what is versioned.",
                "docs/versioning.md §3",
            ),
        )

    /** dp-lake's three (089 §G, moved here so the JSON-LD and the visible answers are one list). */
    val DP_LAKE: List<FaqEntry> =
        listOf(
            FaqEntry(
                "What is dp-lake?",
                "A way to query Parquet and Apache Iceberg tables on S3 or S3-compatible object storage in place: no " +
                    "warehouse, no load step, read-only. DuckDB is the engine, and the catalog is the server's own " +
                    "registry, dp-catalog, because the engine cannot list a bucket. Tables are registered by REST or " +
                    "over MCP. See docs/datasources.md section 8C.",
                "docs/datasources.md §8C",
            ),
            FaqEntry(
                "Does dp-lake copy my data out of S3?",
                "No. Every registered table becomes a view at connect over the objects in your bucket, and the engine " +
                    "reads only what a query's predicates name; a filter on the partition column reads only the " +
                    "matching partitions. Nothing is downloaded at registration or at demo start; reads are in place, " +
                    "at query time. See docs/datasources.md section 8C.2.",
                "docs/datasources.md §8C.2",
            ),
            FaqEntry(
                "How do I register an Iceberg table in dp-lake?",
                "By its current metadata file, s3://bucket/table/metadata/00042-<uuid>.metadata.json, not by the " +
                    "table root: DuckDB 1.5.5.1 cannot resolve a pyiceberg table's root, so the registry row names the " +
                    "file, and you re-register when the table commits. See docs/datasources.md section 8C.7.",
                "docs/datasources.md §8C.7",
            ),
        )

    /** The Tableau hub's questions — beside Tableau today, the roadmap named as roadmap. */
    val TABLEAU: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Can Tableau read what datapipelines produces?",
                "Yes, two ways today. A released pipeline published as a GET endpoint returns JSON that Tableau's web " +
                    "data connector reads; or a pipeline writes its result to a datasource Tableau already connects to. " +
                    "Parquet on your own S3 bucket, read by Tableau natively, arrives with the lake write-back on the " +
                    "roadmap. The endpoint contract is docs/rest-api.md §19.",
                "docs/rest-api.md §19",
            ),
            FaqEntry(
                "Is this a Tableau alternative?",
                "Not today, and the page does not claim it. Tableau draws; datapipelines gets the data there, across " +
                    "Postgres, MySQL, SQL Server, Oracle, SQLite and S3, without a warehouse, authored by an agent and " +
                    "released by a person. Embedded dashboards created by your agent are planned; the " +
                    "roadmap page carries the status. What is versioned today is docs/versioning.md §3.",
                "docs/versioning.md §3",
            ),
            FaqEntry(
                "Why not just build the dashboard in Tableau?",
                "Because the dashboard usually is not the expensive part. Getting a trustworthy, joined, refreshed " +
                    "dataset into it is, and embedding the result in your own product is a second project. datapipelines " +
                    "makes the dataset a versioned pipeline an agent can author and an API you can call from your app " +
                    "today; the dashboards you embed are planned. Publishing is docs/rest-api.md §19.",
                "docs/rest-api.md §19",
            ),
            FaqEntry(
                "Does datapipelines replace Tableau Prep?",
                "For teams who would rather have their preparation as reviewable code than as a flow: a pipeline is a " +
                    "JSON document of SQL nodes with explicit dependencies, versioned, diffable, and runnable by an agent. " +
                    "It joins across engines in a scratch database the way Prep joins in memory, without a warehouse. " +
                    "The contract is docs/pipeline-contract.md §3.",
                "docs/pipeline-contract.md §3",
            ),
        )

    /**
     * The eight the home page carries (115): the buyer's questions, in the order a first
     * visit asks them — cost, data location, what ships when, trust, tenancy — with the
     * engineer's "which databases" deliberately LAST. Every answer speaks the buyer's
     * language; the vocabulary an engineer wants lives on /how-it-works.
     *
     * CORRECTED by 145 against the contracts
     * they cite — three answers had drifted from what the product does: "your data goes
     * nowhere" (results are kept for paging and rows the agent reads reach its provider —
     * docs/rest-api.md §7, docs/mcp-server.md §6.2.15), "built by hand in the editor" (the
     * editor is read-only in v1 — docs/pipeline-editor.md §11), and a dated "next month"
     * for dashboards and the scheduler (docs/ROADMAP.md §2 carries no date). The questions
     * that changed changed their wording too; the lane's reconciliation records each one.
     */
    val HOME: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Do I need a data engineer?",
                "Not to ask the questions. Someone technical deploys the server, registers the database " +
                    "connections and connects the AI client (docs/deployment.md §4, docs/datasources.md §3). " +
                    "After that a business question is a sentence, and a person who understands the data reviews " +
                    "the SQL and the numbers before release (docs/versioning.md §3).",
                "docs/deployment.md §4, docs/datasources.md §3, docs/versioning.md §3",
            ),
            FaqEntry(
                "Where does my data go?",
                "Your database passwords stay on the server, encrypted (docs/datasources.md §7). A run reads " +
                    "each source in place and joins across sources in a temporary staging database dropped when " +
                    "the run ends (docs/staging.md §3); the result is kept briefly so your app or agent can page it " +
                    "(docs/rest-api.md §7). Rows and metadata the agent asks for do reach the agent, and so its " +
                    "model provider; a pipeline can also write results back to a database you choose " +
                    "(docs/pipeline-contract.md §8).",
                "docs/datasources.md §7, docs/staging.md §3, docs/rest-api.md §7, docs/pipeline-contract.md §8",
            ),
            FaqEntry(
                "What do I get today, and what is planned?",
                "Today: pipelines authored with an AI agent, reviewed and released by a person, a published API " +
                    "for each released one, and datasets your Tableau workbook or your own application reads. " +
                    "Planned, without a date on this page: native dashboards, scheduled refresh, reports and " +
                    "alerts. The roadmap page carries the current order (docs/ROADMAP.md §2).",
                "docs/ROADMAP.md §2",
            ),
            FaqEntry(
                "How do I know the numbers are right?",
                "You look. Every step shows the rows it produced, the logic is readable SQL, and every run is recorded " +
                    "with its inputs so a number can be reproduced (docs/rest-api.md §10). Release checks you " +
                    "configure compare a run against expectations you declare (docs/pipeline-contract.md §12.12); " +
                    "they support the review, they do not replace it, and nothing goes live until you press Release.",
                "docs/rest-api.md §10, docs/pipeline-contract.md §12.12",
            ),
            FaqEntry(
                "Can each customer see only their own rows?",
                "Through your application: it supplies the customer's id as a declared parameter and holds the " +
                    "key. An endpoint key restricts which published paths it may call, not which rows a caller may " +
                    "see. Row-level authorisation stays your backend's job (docs/rest-api.md §19.3, docs/auth.md " +
                    "§7.7). Per-viewer filtering for native dashboards is planned with the dashboards.",
                "docs/rest-api.md §19.3, docs/auth.md §7.7",
            ),
            FaqEntry(
                "Do I have to use an AI agent?",
                "Today, yes for authoring: the surface is an agent over MCP or the REST API. The browser inspects " +
                    "a pipeline, executes it, and manages draft and released versions, but does not yet author one " +
                    "(docs/pipeline-editor.md §11). Browser authoring is a roadmap item (docs/ROADMAP.md §2).",
                "docs/pipeline-editor.md §11, docs/ROADMAP.md §2",
            ),
            FaqEntry(
                "What does it cost?",
                "Nothing to run: it is free and open source under AGPL-3.0 and you host it. There is no hosted plan today; " +
                    "when there is, the roadmap page will say so first. (Licence and packaging: docs/deployment.md §10.)",
                "docs/deployment.md §10",
            ),
            FaqEntry(
                "Which databases?",
                "The ${facts.engineCountWord} engines are ${facts.engines}, and dp-lake reads Parquet and " +
                    "Iceberg on S3 in place. One dataset can read several of them in the same run " +
                    "(docs/datasources.md §4).",
                "docs/datasources.md §4",
            ),
        )

    /**
     * The demo-data page's five questions (116 §B.8): is the data real, what the licences
     * let you do with it, how big it is on disk, whether your own data can sit beside it,
     * and whether the demo needs the internet. Sizes and dates the page's GENERATED tables
     * carry from the manifests are not repeated as literals here on purpose — the answer
     * names where the number lives so it cannot drift from the manifest on a repack.
     */
    val DEMO_DATA: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Is the demo data real?",
                "Yes. All three families are real public data, not fixtures: NYC Taxi & Limousine Commission trip records " +
                    "and NOAA weather for New York, US Census trade and Federal Reserve exchange rates, and the TLC " +
                    "high-volume for-hire feed on S3. Every family is rebuilt deterministically from pinned sources, so the " +
                    "same version loads the same numbers anywhere. What ships is documented table by table on the demo-data " +
                    "page. (The build and its pins: docs/deployment.md Appendix B.)",
                "docs/deployment.md §Appendix B",
            ),
            FaqEntry(
                "Can I use the demo data in my own product?",
                "That is what the licences are for. The NYC-derived families ship under NYC Open Data's no-restrictions " +
                    "statement; NOAA and the Federal Reserve are US Government works with no copyright; the Census slice " +
                    "carries the Bureau's API terms, including its not-endorsed notice; the Comtrade slice stays under the " +
                    "fee-free re-dissemination line. Each family's section on the demo-data page quotes the operative " +
                    "sentence and links the source. (The demo and its gates: docs/deployment.md Appendix B.)",
                "docs/deployment.md §Appendix B",
            ),
            FaqEntry(
                "How big is the demo on disk?",
                "The nyc family's files are about 118 MB and the trade family's about 223 MB, restored into the demo " +
                    "containers at start; the lake family is about 7.6 GB of Parquet and Iceberg objects that are never " +
                    "downloaded, and the engine reads only the partitions a query names. The exact per-file and per-table " +
                    "figures render from the published manifests on the demo-data page, so they are always the published " +
                    "numbers. (The artifacts the loader fetches: docs/deployment.md Appendix B.)",
                "docs/deployment.md §Appendix B",
            ),
            FaqEntry(
                "Can I add my own data next to it?",
                "Yes. The demo families are ordinary datasources. Register yours in the app, or in the bootstrap file the " +
                    "demo profiles use, and any pipeline can join them to the demo tables, exactly as the shipped examples " +
                    "join Postgres trips to SQLite zones and MySQL weather. Nothing about the demo is privileged; it is " +
                    "seeded data with example pipelines. (Registration: docs/datasources.md §3.)",
                "docs/datasources.md §3",
            ),
            FaqEntry(
                "Does the demo need internet access?",
                "At start, yes: the nyc and trade artifacts download from the published bucket and the lake datasource " +
                    "points at S3. After that they differ. nyc and trade are local files restored into the demo engines, so " +
                    "running them needs no network, while the lake family is read in place over HTTPS at query time and " +
                    "always needs it. (The consuming side: docs/deployment.md Appendix B.)",
                "docs/deployment.md §Appendix B",
            ),
        )

    /**
     * The pricing page's five questions (119 §C.4): really free, what AGPL asks of you,
     * whether a hosted version is coming, whether support can be bought, and the demo
     * data's licences. The owner's offer sentence renders on the page from
     * [co.datapipelines.web.ui.site.CONTACT_EMAIL]; the FAQ keeps the honest "no paid
     * support today".
     */
    val PRICING: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Is it really free?",
                "Yes: free to run, for yourself or your customers, with every feature on every deployment. There is " +
                    "no seat count, no feature gate and no account with us, because the licence is AGPL-3.0 and you " +
                    "host it. (Licence and packaging: docs/deployment.md §10.)",
                "docs/deployment.md §10",
            ),
            FaqEntry(
                "What does AGPL require of me?",
                "Running it inside your company, even at scale and even commercially, asks nothing of you. If you " +
                    "modify the server and offer it to others over a network, you publish your modifications under " +
                    "the same licence; that is the whole obligation. (The licence: docs/deployment.md §10.)",
                "docs/deployment.md §10",
            ),
            FaqEntry(
                "Will there be a hosted version?",
                "There is no paid tier today, and nothing hosted is planned. If a hosted version ever " +
                    "exists it will be announced on the roadmap page first, never launched quietly. What is " +
                    "deliberately not planned lives on the roadmap's later band. (Source: docs/ROADMAP.md §3.)",
                "docs/ROADMAP.md §3",
            ),
            FaqEntry(
                "Can I pay for support?",
                "Not today, because there is nothing to buy. Help is GitHub Discussions and Issues, the docs under /docs, " +
                    "and the owner answers the contact address on the pricing page personally. You also own the ops: " +
                    "the upgrade runbook is docs/deployment.md §8.",
                "docs/deployment.md §8",
            ),
            FaqEntry(
                "What about the demo data's licences?",
                "The sample families are real public data, each under its own terms: NYC Open Data's " +
                    "no-restrictions statement, US Government public-domain works and the Census API terms. The " +
                    "demo-data page quotes the operative sentence per family. (The demo and its gates: " +
                    "docs/deployment.md Appendix B.)",
                "docs/deployment.md §Appendix B",
            ),
        )

    /**
     * The learned semantic layer's five questions (119 §B.10). Every answer names the doc
     * it rests on; the "poison" answer carries the trust-and-conflict rule, which is the
     * whole reason a wrong fact cannot silently win.
     */
    val SEMANTIC_LAYER: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Is this a data catalog?",
                "No. A catalog lists what exists, the tables, columns and owners. This records what it means: a unit, a " +
                    "time zone, a grain, what a code means, how tables join and what revenue excludes, each with the " +
                    "query that proved it. The introspection surface it rides is docs/datasources.md §7.",
                "docs/datasources.md §7",
            ),
            FaqEntry(
                "Can a wrong fact poison the agent?",
                "That is what the trust ladder and the conflict rule are for: a fact is never higher-trust than its " +
                    "evidence, two live facts of the same kind are shown as a conflict rather than a silent winner, and " +
                    "any fact can be retired with a reason. Facts are specified in docs/mcp-server.md §6.2.",
                "docs/mcp-server.md §6.2",
            ),
            FaqEntry(
                "Do I have to write anything?",
                "No. The agent records what it learns while it works; you verify when you want to. Recording needs an " +
                    "author's key and a release is still a person's act, the same bar as authoring a pipeline. " +
                    "(docs/auth.md §7.6).",
                "docs/auth.md §7.6",
            ),
            FaqEntry(
                "What happens when my schema changes?",
                "Drift is detected at read time: a renamed or dropped column flips its facts to stale, shown beside " +
                    "the current columns. Nothing is silently re-mapped to a new column; someone re-verifies and " +
                    "records the successor. The drift rule is docs/metadata-db.md §4.18.",
                "docs/metadata-db.md §4.18",
            ),
            FaqEntry(
                "Does it work on my own warehouse?",
                "Yes. The skill and the tools are demo-free, and the facts come from whatever your agent probes: " +
                    "${facts.engines} (docs/datasources.md §4).",
                "docs/datasources.md §4",
            ),
        )

    /**
     * 127 §B.3 — the beta's three report paths, one entry each, so every kind of report has
     * exactly one home. The URLs render from the constants beside `CONTACT_EMAIL`
     * ([REPORT_PROBLEM_URL], [DISCUSSIONS_URL], [ADVISORY_URL]) — never typed, so the links
     * the whole project offers cannot drift apart. The footer links this group's anchor.
     */
    val SUPPORT: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Something broke: where do I report it?",
                "Open a bug report on GitHub: $REPORT_PROBLEM_URL. The form asks for the version, your " +
                    "deployment's /health build hash or the image tag, for the surface it happened on, and for the " +
                    "correlation id from the failed execution, the error toast or the REST envelope, so the " +
                    "report arrives carrying what a fix needs. What helps most during the beta is in " +
                    "CONTRIBUTING.md; the scope the beta covers is docs/ROADMAP.md §2.2.",
                "docs/ROADMAP.md §2.2",
            ),
            FaqEntry(
                "Where do I ask a question or float an idea?",
                "GitHub Discussions: $DISCUSSIONS_URL. Questions about running it, ideas that are not yet " +
                    "feature requests, and show-and-tell all belong there. A concrete defect is a bug report " +
                    "and a formed proposal is a feature request, both one click away on the same page. What is " +
                    "already being built is the roadmap's known limitations, docs/ROADMAP.md §2.2, so check " +
                    "there first.",
                "docs/ROADMAP.md §2.2",
            ),
            FaqEntry(
                "How do I report a security problem?",
                "Privately, never as a public issue: open a private security advisory at $ADVISORY_URL, or " +
                    "email the contact address in the footer if the advisory flow is not an option. What is in " +
                    "scope and what reporters are asked is SECURITY.md in the repository, and the security " +
                    "model a report is checked against is docs/auth.md.",
                "docs/auth.md §8",
            ),
        )

    /** Every group, in reading order — the `/faq` page opens with the buyer's questions (115). */
    val ALL: List<Pair<String, List<FaqEntry>>> =
        listOf(
            "For the buyer" to HOME,
            "What it is" to WHAT_IT_IS,
            "Agents and security" to AGENTS_AND_SECURITY,
            "APIs and operations" to APIS_AND_OPERATIONS,
            "Demo data" to DEMO_DATA,
            "Tableau" to TABLEAU,
            "Pricing" to PRICING,
            "The semantic layer" to SEMANTIC_LAYER,
            "Support and feedback" to SUPPORT,
        )
}
