package co.datapipelines.web.ui.site

/**
 * The batch-2 intent pages' FAQ content (111 §A): one list per new page, same rule as
 * [SiteFaqs] — a fact that ships, or "roadmap" in as many words. Split from [SiteFaqs] so
 * no single FAQ object carries the whole site (detekt LargeClass).
 */
object SiteFaqsBatch2 {
    /** The ELT comparison's four — honest that the nightly copy itself is not this product's job. */
    val COMPARE_FIVETRAN: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Does datapipelines copy my data?",
                "No. Every node runs as a query at its source and the result is staged into a scratch database " +
                    "created for that one run, joined there, and dropped when the run ends. Nothing is landed into " +
                    "a warehouse. How staging works is docs/staging.md §3.",
                "docs/staging.md §3",
            ),
            FaqEntry(
                "Can it replace a nightly sync?",
                "Not the copy itself. Populating a warehouse is the job an ELT tool does, and the honest page " +
                    "says so. What replaces the nightly script is the pipeline: the join an agent drafts, a person " +
                    "releases, and an API serves; write-back can also land a result in one table you already have. " +
                    "Publishing is docs/rest-api.md §19.",
                "docs/rest-api.md §19",
            ),
            FaqEntry(
                "What about history the source overwrites?",
                "This does not keep history: the query sees the source as it is now, and the per-run scratch " +
                    "database is dropped after the run. A scheduled extract to your own lake, where that history " +
                    "would land, is a later roadmap item, and the roadmap page labels it as such. The roadmap is " +
                    "docs/ROADMAP.md §3.",
                "docs/ROADMAP.md §3",
            ),
            FaqEntry(
                "Can I use both?",
                "Yes, and it is a common shape: datapipelines joins the operational sources and serves the result " +
                    "as a versioned GET endpoint, while your ELT tool keeps feeding the warehouse the wide history " +
                    "it is good at. The endpoint contract is docs/rest-api.md §19.",
                "docs/rest-api.md §19",
            ),
        )

    /** The Postgres-only page's four — the honest answer to "do I need a warehouse" is often "no". */
    val COMPARE_POSTGRES_ONLY: List<FaqEntry> =
        listOf(
            FaqEntry(
                "When is Postgres alone the right call?",
                "When one database holds the data and the people asking questions can query it directly: no second " +
                    "system to keep, no copy to go stale, and a warehouse would be an empty room. The moment a " +
                    "second engine arrives, a cross-engine join needs somewhere to happen, and docs/staging.md §3 is " +
                    "where it happens here.",
                "docs/staging.md §3",
            ),
            FaqEntry(
                "What actually arrives with the second source?",
                "Four things: a question that crosses engines, which needs a per-run scratch database to join " +
                    "in; credential sprawl, which stops when the server holds the credentials encrypted and the " +
                    "agent holds only a key; an API someone will ask for; and a version a person released behind " +
                    "it. Credential storage is docs/datasources.md §7.",
                "docs/datasources.md §7",
            ),
            FaqEntry(
                "Does datapipelines take Postgres away?",
                "No. Postgres becomes a datasource: it keeps serving your application, the agent reads it through " +
                    "the server with its own revocable key, and a pipeline can write its result back into a Postgres table " +
                    "if that is where the answer belongs. Write-back is docs/pipeline-contract.md §8.",
                "docs/pipeline-contract.md §8",
            ),
            FaqEntry(
                "Who is allowed to change what runs?",
                "A person. The agent authors and iterates a draft and can run it, but releasing, the step that " +
                    "makes the pipeline what production serves, is a human verb, and a released version is " +
                    "immutable. The lifecycle is docs/versioning.md §3.",
                "docs/versioning.md §3",
            ),
        )

    /** The Prep comparison's four, including the honest "what Prep still does better". */
    val TABLEAU_PREP: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Is a pipeline here really code?",
                "Yes: a pipeline is a JSON document of named SQL nodes with explicit dependencies, so it diffs in " +
                    "a pull request, carries a version number, and is reviewed the way code is. A flow file is a " +
                    "diagram only one tool opens. The contract is docs/pipeline-contract.md §3.",
                "docs/pipeline-contract.md §3",
            ),
            FaqEntry(
                "What does Tableau Prep still do better?",
                "Visual profiling and the click-to-pivot are real strengths, and a one-off reshape by a person " +
                    "who lives in Prep is not a problem this needs to solve. The pipeline earns its keep when the " +
                    "preparation must be repeatable, reviewable and agent-authored. The authoring surface is " +
                    "docs/mcp-server.md §6.2.",
                "docs/mcp-server.md §6.2",
            ),
            FaqEntry(
                "Can an agent build the pipeline for me?",
                "That is the intended workflow: the agent reads your schemas, catalogue statistics and indexes, " +
                    "drafts the pipeline, runs it, and reads the failure when there is one, then leaves a draft " +
                    "that a person releases. The tool surface is docs/mcp-server.md §6.2.",
                "docs/mcp-server.md §6.2",
            ),
            FaqEntry(
                "How do I migrate an existing flow?",
                "In five steps, each a shipped feature: inventory the flow's sources, register them as datasources " +
                    "marked read-only where nothing should write, describe the transformation to your agent, run the " +
                    "pipeline and compare row counts with the flow's output, then release. Registering datasources " +
                    "is docs/datasources.md §3.",
                "docs/datasources.md §3",
            ),
        )

    /**
     * The Tableau roadmap page's four. Since #699 the first answers "yes": dashboards ship. The
     * extract and the alert still say later in as many words.
     */
    val TABLEAU_ROADMAP: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Are the embedded dashboards available today?",
                "Yes. Your agent drafts visualizations and dashboards over pinned released pipelines, a person " +
                    "reviews and releases them, and your product embeds one through its own backend, which holds the " +
                    "dashboard key so it never reaches the browser. There is no drag-and-drop editor. The contracts " +
                    "are docs/dashboards.md §1 and docs/dashboards.md §6.5.",
                "docs/dashboards.md §1, docs/dashboards.md §6.5",
            ),
            FaqEntry(
                "When do scheduled extracts to my own S3 bucket arrive?",
                "Later, decided but not dated. The scheduler that would run them ships, and a scheduled pipeline " +
                    "can already write its result into a table Tableau connects to; the extract itself, a Parquet " +
                    "file on your own bucket, waits on a write path to object storage. The contracts are " +
                    "docs/ROADMAP.md §3 and docs/scheduler.md §1.",
                "docs/ROADMAP.md §3, docs/scheduler.md §1",
            ),
            FaqEntry(
                "Will there be Slack alerts?",
                "No. The roadmap names email alerts and nothing beyond them: a scheduled pipeline, a condition, " +
                    "an email. Later, not dated, and deliberately no Slack. The roadmap item is docs/ROADMAP.md §3.",
                "docs/ROADMAP.md §3",
            ),
            FaqEntry(
                "What does filtered per viewer mean?",
                "That each viewer sees their own rows because your backend decides which filter values a viewer " +
                    "may submit. Every viewer behind one dashboard key shares that key, so the key alone separates " +
                    "nobody; the allowed value binds as a declared parameter of the released pipeline and the SQL " +
                    "filters on it. The contracts are docs/dashboards.md §6.5 and docs/templates.md §4.5.",
                "docs/dashboards.md §6.5, docs/templates.md §4.5",
            ),
        )

    /** The agencies page's four — isolation, the per-client key, the release, what embeds today. */
    val FOR_AGENCIES: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Can each client be kept separate from the others?",
                "Yes, one workspace per client. A key is pinned to one workspace at issuance, so an agent " +
                    "working for client A cannot switch that key to client B. A colleague's session can switch " +
                    "only to workspaces they belong to; other workspaces' pipelines, datasources and executions " +
                    "are absent, not hidden. Workspace resolution is docs/auth.md §5.6.",
                "docs/auth.md §5.6",
            ),
            FaqEntry(
                "Can a client's key reach only that client's numbers?",
                "Yes. Endpoint keys are bound to a path prefix of the published tree, so the key you hand a client calls " +
                    "its endpoints and nothing else, and you revoke it without touching anyone else's access. Key " +
                    "kinds and bindings are docs/auth.md §7.7.",
                "docs/auth.md §7.7",
            ),
            FaqEntry(
                "Who does the work, and who signs it off?",
                "Your agent drafts each client's pipeline over the same authoring surface every user gets, and a " +
                    "person on your team releases it. Agents cannot release, and a released version is immutable. " +
                    "That is the version you can name in the invoice. The lifecycle is docs/versioning.md §3.",
                "docs/versioning.md §3",
            ),
            FaqEntry(
                "What do we put in the client's portal today?",
                "The API: a released pipeline published as a GET endpoint returns JSON with the query string " +
                    "bound to its declared parameters, which is what a portal or a white-label page calls. A " +
                    "dashboard can sit beside it, embedded through your backend with a dashboard key bound to that " +
                    "client's dashboard folder. The contracts are docs/rest-api.md §19, docs/auth.md §7.7 and " +
                    "docs/dashboards.md §6.5.",
                "docs/rest-api.md §19, docs/auth.md §7.7, docs/dashboards.md §6.5",
            ),
        )

    /** The SaaS page's five: the embedded-analytics story, the API and the dashboard (#699: both ship). */
    val FOR_SAAS_TEAMS: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Is this embedded analytics?",
                "Both halves ship. A released pipeline becomes a versioned GET endpoint your application calls, " +
                    "with per-viewer values bound as parameters, and a dashboard over it, drafted by your agent and " +
                    "released by a person, embeds in your product through your own backend. The contracts are " +
                    "docs/rest-api.md §19 and docs/dashboards.md §6.5.",
                "docs/rest-api.md §19, docs/dashboards.md §6.5",
            ),
            FaqEntry(
                "How does each customer see only their own rows?",
                "Row-level authorisation stays your backend's job: it authorises the viewer and derives or limits " +
                    "the customer value that viewer may submit. The published endpoint accepts declared parameters " +
                    "from its path or query string (docs/rest-api.md §19.3), and the released SQL filters rows using " +
                    "that value bound as a scalar on a prepared statement (docs/templates.md §4.5), never pasted SQL. " +
                    "An endpoint key restricts published paths, not result rows (docs/auth.md §7.7).",
                "docs/rest-api.md §19.3, docs/templates.md §4.5, docs/auth.md §7.7",
            ),
            FaqEntry(
                "What can a customer-facing key reach?",
                "One path subtree and nothing else: endpoint keys are bound to a path prefix of the published tree, cannot " +
                    "call your product's own API or another tenant's endpoints, and are revoked in about a minute. " +
                    "Key kinds and bindings are docs/auth.md §7.7.",
                "docs/auth.md §7.7",
            ),
            FaqEntry(
                "Who controls what customers see?",
                "A person on your team. The agent iterates drafts, but releasing is a human verb and a release is " +
                    "immutable, so the numbers your customers see are a version you can name, restore and audit. " +
                    "The lifecycle is docs/versioning.md §3.",
                "docs/versioning.md §3",
            ),
            FaqEntry(
                "What does the audit log record about a customer request?",
                "Every call is audited with the key that made it, the endpoint and the execution, and never the " +
                    "SQL text, the row data or the parameter values, which are your customers' data. The audit log " +
                    "is docs/auth.md §10.",
                "docs/auth.md §10",
            ),
        )

    /** The analyst page's four — the agent as the pair, the join, the slow query, the rerun. */
    val FOR_ANALYSTS: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Do I have to write the SQL myself?",
                "No, that is the agent's job: it reads your real schemas, catalogue statistics and indexes " +
                    "first, drafts the pipeline, runs it and reads the failure when there is one. You read the SQL " +
                    "it left, because a pipeline is a JSON document of named SQL nodes. The authoring tools are " +
                    "docs/mcp-server.md §6.2.",
                "docs/mcp-server.md §6.2",
            ),
            FaqEntry(
                "How does the join across five databases work?",
                "Each source node runs as a query at its own engine, in that engine's dialect, and its result is " +
                    "staged as a table in a scratch database created for that one run; the join is ordinary SQL " +
                    "over those tables, and the scratch database is dropped when the run ends. Staging is " +
                    "docs/staging.md §3.",
                "docs/staging.md §3",
            ),
            FaqEntry(
                "A query is slow: how do I find out why?",
                "Two agent tools answer it: catalogue statistics per table, giving row estimates, indexes and " +
                    "per-column bounds from the engine's own catalog, and the SQL probe, which runs one bounded " +
                    "SELECT and captures the EXPLAIN plan before the query, so the plan survives the timeout it " +
                    "explains. They are docs/datasources.md §7C and §7D.",
                "docs/datasources.md §7C",
            ),
            FaqEntry(
                "How does a query become something my team can rerun?",
                "It already is one: the agent saves its work as a versioned pipeline, you review and release it, " +
                    "and the released version can be published as a GET endpoint with declared parameters, " +
                    "rerun by anyone with the key and served by the version you released. The lifecycle is " +
                    "docs/versioning.md §3.",
                "docs/versioning.md §3",
            ),
        )
}
