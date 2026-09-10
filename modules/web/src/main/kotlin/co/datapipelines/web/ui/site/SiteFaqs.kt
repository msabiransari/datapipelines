package co.datapipelines.web.ui.site

/**
 * The site's FAQ content — the product introduction in question-and-answer form (owner,
 * 2026-09-09: "Targeted, should have a great product introduction in Q/A form").
 *
 * Each answer names the doc it rests on, in the text the reader sees: the sentence Google may
 * quote as a snippet is a sentence a reader can verify one click away. Groups are the
 * page-sized slices; [HOME] is the ten the home page carries, [ALL] is the `/faq` page in
 * reading order. Every answer is a fact about what SHIPS today — the roadmap has its own page
 * and the two answers that touch it say "roadmap" in as many words.
 */
object SiteFaqs {
    val WHAT_IT_IS: List<FaqEntry> =
        listOf(
            FaqEntry(
                "What is datapipelines.co, in one sentence?",
                "A self-hosted server that lets an AI coding agent — Claude Code, Cursor, Copilot, any MCP client — author SQL " +
                    "data pipelines across your databases, while people keep the release button; every released pipeline can be " +
                    "published as a versioned HTTP API. The tool surface is specified in docs/mcp-server.md §6.",
                "docs/mcp-server.md §6",
            ),
            FaqEntry(
                "What is a pipeline here — a DAG, a notebook, a dbt project?",
                "A JSON document: named nodes, each a SQL template run against one datasource (or the per-run scratch " +
                    "engine), with explicit dependencies between them. The executor orders the graph, runs independent nodes " +
                    "in parallel, stages each result, and hands the last node's rows to the caller. The contract is " +
                    "docs/pipeline-contract.md §3 and the execution model docs/dag-executor.md §5.",
                "docs/pipeline-contract.md §3",
            ),
            FaqEntry(
                "Which databases does it talk to?",
                "PostgreSQL, MySQL, SQL Server, Oracle, SQLite, DuckDB, H2 and dp-lake — Parquet, CSV and Iceberg on S3 read " +
                    "in place by an embedded DuckDB. One pipeline can mix all of them; each node runs where its data lives. " +
                    "The dialect matrix with driver and licence notes is docs/datasources.md §4.",
                "docs/datasources.md §4",
            ),
            FaqEntry(
                "Do I need a data warehouse?",
                "No. Every node runs at its source and streams its result into a per-run scratch engine (H2, created for " +
                    "the run and dropped after it), where the joining and shaping happen. The demo joins Postgres trips, MySQL " +
                    "weather, SQLite zones and Parquet rideshare data in one run. How staging works is docs/staging.md §3.",
                "docs/staging.md §3",
            ),
            FaqEntry(
                "Is it open source, and what may I do with it?",
                "Yes — AGPL-3.0. Run it on your own infrastructure for your own company or your clients, read and change " +
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
                    "rest, and never returns them — not through a tool, not through the UI. A datasource can be marked " +
                    "read-only so write-shaped nodes are refused at save time. Credential storage is docs/datasources.md §7 and " +
                    "the read-only flag docs/datasources.md §3.",
                "docs/datasources.md §7",
            ),
            FaqEntry(
                "How is this different from a Postgres MCP server?",
                "A single-database MCP server gives one agent one database and usually one connection string on a laptop. " +
                    "This gives the agent all of your databases through one scoped key, a scratch engine to join their results, " +
                    "versioned drafts a person reviews, an audit trail per call, and an HTTP API for what it builds. Every " +
                    "tool is specified in docs/mcp-server.md §6.2.",
                "docs/mcp-server.md §6.2",
            ),
            FaqEntry(
                "What can an agent do, and what can it never do?",
                "It can read schemas, catalogue statistics and indexes, probe a SELECT, create and iterate a draft pipeline, " +
                    "run it, read the result, and cancel its own runs. It can never release a version, never read a " +
                    "credential, and never touch a datasource marked read-only with a write. Scopes are per key and the " +
                    "matrix is docs/auth.md §7.6.",
                "docs/auth.md §7.6",
            ),
            FaqEntry(
                "Is every agent action logged?",
                "Yes. Every MCP tool call is written to the audit log with the key that made it and the tool — never the " +
                    "SQL text, row data or parameter values, which are your data; every lifecycle verb records who did it " +
                    "and through which surface (session, API key or MCP). The audit log is docs/auth.md §10 and the MCP " +
                    "events docs/mcp-server.md §10.",
                "docs/auth.md §10",
            ),
            FaqEntry(
                "Can an agent release a pipeline to production?",
                "No, by design. Agents create and iterate drafts; a person releases. A released version is immutable — a " +
                    "later edit becomes a new draft — and a published endpoint serves the version you point it at. The " +
                    "lifecycle is docs/versioning.md §3.",
                "docs/versioning.md §3",
            ),
        )

    val APIS_AND_OPERATIONS: List<FaqEntry> =
        listOf(
            FaqEntry(
                "What does “published endpoint” mean?",
                "A released pipeline gets a URL under /api/x/…; a GET runs it with the query string bound to its declared " +
                    "parameters and returns the rows as JSON. Keys are bound to a path prefix, so a partner can be given one " +
                    "endpoint and nothing else. The serving contract is docs/rest-api.md §19.",
                "docs/rest-api.md §19",
            ),
            FaqEntry(
                "From data to an API in a day — is that real?",
                "For the API half, today: an agent authors the pipeline against your databases, runs it, fixes what fails, " +
                    "you review and release, and the endpoint exists — no deployment, no service to write, no gateway to " +
                    "configure. Dashboards your agent creates and you embed in your own product are the next release; the " +
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
                    "fails with pipeline.node.query_timeout and the elapsed time — and the SQL probe returns the plan — so the " +
                    "agent fixes the shape instead of guessing. The limits and cancellation are docs/dag-executor.md §5.",
                "docs/dag-executor.md §5",
            ),
            FaqEntry(
                "Is it a Tableau or dbt replacement?",
                "Not today. It sits beside them: it can feed Tableau a governed dataset through an API or as Parquet on " +
                    "your own bucket, and it replaces the warehouse-plus-dbt step for teams that never wanted one. " +
                    "Dashboards, scheduling and alerts are on the roadmap page with dates. The comparison pages cite " +
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
                    "registry, dp-catalog, because the engine cannot list a bucket - tables are registered by REST or " +
                    "over MCP. See docs/datasources.md section 8C.",
                "docs/datasources.md §8C",
            ),
            FaqEntry(
                "Does dp-lake copy my data out of S3?",
                "No. Every registered table becomes a view at connect over the objects in your bucket, and the engine " +
                    "reads only what a query's predicates name - a filter on the partition column reads only the " +
                    "matching partitions. Nothing is downloaded at registration or at demo start; reads are in place, " +
                    "at query time. See docs/datasources.md section 8C.2.",
                "docs/datasources.md §8C.2",
            ),
            FaqEntry(
                "How do I register an Iceberg table in dp-lake?",
                "By its current metadata file - s3://bucket/table/metadata/00042-<uuid>.metadata.json - not by the " +
                    "table root: DuckDB 1.5.5 cannot resolve a pyiceberg table's root, so the registry row names the " +
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
                "Not today, and the page does not claim it. Tableau draws; datapipelines gets the data there — across " +
                    "Postgres, MySQL, SQL Server, Oracle, SQLite and S3, without a warehouse, authored by an agent and " +
                    "released by a person. Embedded dashboards created by your agent are next month's release; the " +
                    "roadmap page carries the date. What is versioned today is docs/versioning.md §3.",
                "docs/versioning.md §3",
            ),
            FaqEntry(
                "Why not just build the dashboard in Tableau?",
                "Because the dashboard usually is not the expensive part — getting a trustworthy, joined, refreshed " +
                    "dataset into it is, and embedding the result in your own product is a second project. datapipelines " +
                    "makes the dataset a versioned pipeline an agent can author and an API you can call from your app " +
                    "today; the dashboards you embed follow next month. Publishing is docs/rest-api.md §19.",
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

    /** The ten the home page carries — the introduction, in the order a first visit asks. */
    val HOME: List<FaqEntry> =
        listOf(
            WHAT_IT_IS[0],
            AGENTS_AND_SECURITY[0],
            AGENTS_AND_SECURITY[1],
            WHAT_IT_IS[3],
            AGENTS_AND_SECURITY[4],
            APIS_AND_OPERATIONS[0],
            APIS_AND_OPERATIONS[1],
            WHAT_IT_IS[4],
            APIS_AND_OPERATIONS[3],
            APIS_AND_OPERATIONS[4],
        )

    /** Every group, in reading order — the `/faq` page. */
    val ALL: List<Pair<String, List<FaqEntry>>> =
        listOf(
            "What it is" to WHAT_IT_IS,
            "Agents and security" to AGENTS_AND_SECURITY,
            "APIs and operations" to APIS_AND_OPERATIONS,
            "Tableau" to TABLEAU,
        )
}
