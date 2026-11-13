package co.datapipelines.web.ui.site

/**
 * The #699 capability pages' FAQ lists. Same contract as every list in [SiteFaqs]: each answer
 * names the doc it rests on and renders once as the visible `<details>` and once as the page's
 * `FAQPage` JSON-LD. Each question exists because a reader would otherwise assume the wrong
 * thing (the design record's §3 limits): mail delivery, draft pointers, MCP reach, the shared
 * dashboard key, and the difference between a set and a pipeline's own parameters.
 */
object SiteCapabilitiesFaqs {
    /** `/pipeline-scheduling` — what a schedule does NOT do yet, and which version it runs. */
    val SCHEDULING: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Does a schedule email anyone when a run fails?",
                "Yes, when scheduler mail is enabled and the schedule has recipients. Failure, unknown and blocked " +
                    "events default on; start and success are opt-in. Scheduler mail is off by default, and the " +
                    "deployment's mail and recipient-domain policy still apply. Reports, condition alerts and " +
                    "PDF delivery are not built. " +
                    "The contract is docs/scheduler.md §2.3.",
                "docs/scheduler.md §2.3",
            ),
            FaqEntry(
                "Which version of the pipeline does a schedule run?",
                "The one the pipeline's current-version pointer names when the run is prepared, and a person can " +
                    "point that at a draft under the development posture. Each run freezes the exact version and the " +
                    "resolved parameter values it launched with, so its history says what actually ran. The contract " +
                    "is docs/scheduler.md §2.1.",
                "docs/scheduler.md §2.1",
            ),
            FaqEntry(
                "Can an AI agent create or change a schedule?",
                "No. Nothing about a schedule is exposed over MCP. Authors, workspace admins and super admins " +
                    "create, edit, pause, resume, unblock, delete and run schedules in the application or over the REST " +
                    "API with a signed-in session; every workspace member can read schedules, with promoters limited to " +
                    "pipelines they can see. The schedule job is docs/scheduler.md §1; its execution identity is " +
                    "docs/scheduler.md §6.",
                "docs/scheduler.md §1, docs/scheduler.md §6",
            ),
            FaqEntry(
                "Does the scheduler run anything besides SQL pipelines?",
                "The scheduler is a job scheduler; pipelines are the job type it runs today. This page's example " +
                    "schedules a SQL pipeline. The scheduler stores " +
                    "the payload without reading it, so a report or AI job would be a new job type, decided later, " +
                    "not a capability to count on today. Pipeline scheduling is the example this page walks " +
                    "through. The contracts are docs/scheduler.md §1 and docs/scheduler.md §2.",
                "docs/scheduler.md §1, docs/scheduler.md §2",
            ),
        )

    /** `/sql-dashboards` — embedding through a backend, and what refreshes a dashboard. */
    val DASHBOARDS: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Can I embed a dashboard in my own product?",
                "Yes, through your backend. Your application holds a dashboard key and relays the four runtime " +
                    "routes, so the key never reaches the browser; a dependency-free reference proxy ships with the " +
                    "source. Your backend signs your own user in first. Every viewer behind one key shares that key's " +
                    "identity and budget, so which customer's values a viewer may submit is decided in your backend, " +
                    "not by the key. The contract is docs/dashboards.md §6.5.",
                "docs/dashboards.md §6.5",
            ),
            FaqEntry(
                "Does a schedule refresh my dashboards?",
                "No. A schedule runs a pipeline. Configured refresh actions run its pinned sources: an action can " +
                    "run when the dashboard opens, from a button or from a bound filter control. Changing an unbound " +
                    "filter marks charts stale. A refresh streams each chart's result as it arrives. No MCP tool " +
                    "refreshes a dashboard. The contracts are docs/dashboards.md " +
                    "§5.5 and docs/dashboards.md §5.8.",
                "docs/dashboards.md §5.5, docs/dashboards.md §5.8",
            ),
            FaqEntry(
                "Who builds a dashboard, and how is it reviewed?",
                "An AI agent authors visualizations and dashboards over MCP, and everything it writes lands as a " +
                    "draft. A person reviews it in the application and releases it; a visualization releases only with " +
                    "passing test evidence for its exact content. There is no drag-and-drop editor: the layout is a " +
                    "12-column grid in the dashboard document (docs/dashboards.md §2.2). The contract is " +
                    "docs/dashboards.md §3.1.",
                "docs/dashboards.md §2.2, docs/dashboards.md §3.1",
            ),
        )

    /** `/reusable-parameter-sets` — a set is not a pipeline parameter, and a pinned version is protected. */
    val PARAMETER_SETS: List<FaqEntry> =
        listOf(
            FaqEntry(
                "Is a parameter set the same as a pipeline parameter?",
                "No. A pipeline declares its own typed parameters, and a published endpoint or a schedule " +
                    "supplies values for those. A parameter set is a separate, versioned form definition. Today its " +
                    "consumer is a dashboard, which pins one set version and maps the set's values onto its source " +
                    "pipelines' parameters. The contract is docs/dashboards.md §2.2.",
                "docs/dashboards.md §2.2",
            ),
            FaqEntry(
                "Does the browser run the SQL behind a dropdown?",
                "No. The client submits every control's current value and the server evaluates the whole set " +
                    "again: it runs each pinned selector template with the parent values bound as parameters and " +
                    "returns fresh options, resolved values and per-field errors. The client runs no SQL and no " +
                    "dependency logic. The contract is docs/rest-api.md §21.3.",
                "docs/rest-api.md §21.3",
            ),
            FaqEntry(
                "What happens to a dashboard when its parameter set changes?",
                "Nothing until someone moves the pin. An edit opens a new draft version, and the dashboard keeps " +
                    "the exact version it pinned. A version a dashboard pins cannot be discarded or purged: the " +
                    "server refuses with parameter.in_use and names the dashboards. The contract is docs/rest-api.md " +
                    "§21.2.",
                "docs/rest-api.md §21.2",
            ),
        )
}
