package co.datapipelines.web.ui.site

/**
 * The 145 FAQ lists: the practical questions the redesigned `/how-it-works` opens with, and
 * the `/use-cases` hub's three. Same contract as every list in [SiteFaqs] — each answer is a
 * paragraph that names the doc it rests on, rendered once as the visible `<details>` and once
 * as the page's `FAQPage` JSON-LD (SiteV2GuardsTest holds the two equal).
 *
 * Kept beside [SiteFaqs] rather than inside it: that file is the site's long-standing
 * catalogue and the `/faq` page renders its groups; these lists are page-local and do not
 * join `/faq` (the buyer's group there is the corrected [SiteFaqs.HOME]).
 */
object SiteFaqsHub {
    /**
     * The three practical questions from the approved preview, each checked against the
     * contract it states: an operator deploys and registers connections (no tool registers a
     * datasource — docs/mcp-server.md §6.2.22); rows the agent reads reach its provider
     * (docs/mcp-server.md §6.2.15); the browser does not author yet (docs/pipeline-editor.md §11).
     */
    val HOW_IT_WORKS_PRACTICAL: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Do I need someone technical to set it up?",
                "Yes. Someone deploys the server on your infrastructure, registers the database connections in " +
                    "the application (there is deliberately no agent tool that registers one — docs/mcp-server.md " +
                    "§6.2.22) and connects the AI client. After setup, business questions can be asked in plain " +
                    "language; a person who understands your data reviews the resulting SQL and numbers.",
                "docs/deployment.md §4, docs/mcp-server.md §6.2.22",
            ),
            FaqEntry(
                "Does my data reach the AI provider?",
                "The server holds your database credentials and the agent never sees them (docs/datasources.md " +
                    "§7), but the schema, statistics, preview rows and execution results the agent asks for are " +
                    "returned to it (docs/mcp-server.md §6.2.15) — and so may reach the model provider behind your " +
                    "client. Choose the client, the model and the datasources' access to match your requirements.",
                "docs/datasources.md §7, docs/mcp-server.md §6.2.15",
            ),
            FaqEntry(
                "Can I build the pipeline entirely in the browser?",
                "Not yet. Today authoring is through an AI agent over MCP or the REST API; the browser helps you " +
                    "inspect a pipeline, execute it, read results and errors, and manage draft and released versions " +
                    "(docs/pipeline-editor.md §11). Browser authoring is a roadmap item (docs/ROADMAP.md §2).",
                "docs/pipeline-editor.md §11, docs/ROADMAP.md §2",
            ),
        )

    /** The whole `/how-it-works` FAQ: the practical three, then the operational list the page always carried. */
    val HOW_IT_WORKS: List<FaqEntry> = HOW_IT_WORKS_PRACTICAL + SiteFaqs.APIS_AND_OPERATIONS

    /** The `/use-cases` hub's three — one per audience section, each pointing at the contract behind the example. */
    val USE_CASES: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Do I need to move the data into a warehouse first?",
                "No. A pipeline reads each source where it lives and joins across sources in a temporary staging " +
                    "database that exists only for the run (docs/staging.md §3). If you want a copy, a node can " +
                    "write its result back to a database you choose (docs/pipeline-contract.md §8) — that is a " +
                    "choice, not a prerequisite.",
                "docs/staging.md §3, docs/pipeline-contract.md §8",
            ),
            FaqEntry(
                "Can my product show each customer only their own data?",
                "Your backend does that: it supplies the customer's identifier as a declared parameter and holds " +
                    "the endpoint key (docs/rest-api.md §19.3). The key restricts which published paths may be called " +
                    "(docs/auth.md §7.7); it does not turn an arbitrary customer id into row-level authorisation, so " +
                    "the check stays where your product already makes it.",
                "docs/rest-api.md §19.3, docs/auth.md §7.7",
            ),
            FaqEntry(
                "Can I use it with Tableau today?",
                "Yes, for the data preparation: a released pipeline serves a versioned JSON endpoint " +
                    "(docs/rest-api.md §19), or writes its result into a database Tableau already connects to " +
                    "(docs/pipeline-contract.md §8). Visualisation stays in Tableau or your own application; " +
                    "native dashboards and scheduled refresh are planned (docs/ROADMAP.md §2).",
                "docs/rest-api.md §19, docs/pipeline-contract.md §8, docs/ROADMAP.md §2",
            ),
        )
}
