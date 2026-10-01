package co.datapipelines.mcp

import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.TestSessionLinks
import co.datapipelines.visualization.TestSessionWire
import co.datapipelines.visualization.TestSubmissionReader
import co.datapipelines.visualization.VisualizationTestSessionService
import io.modelcontextprotocol.spec.McpSchema

/**
 * `visualizations_test_start` (mcp-server.md §6.2.61; the implementation spec's §7, §11.2) — the REST
 * `POST /api/v1/visualizations/{id}/tests/sessions` twin: opens a test session on the WORKING version and answers the
 * preview URL (the preview capability rides inside it — the agent's browser's ONLY credential, shown once), the case
 * inventory and the deadline. Permission: `visualization.update`. Answers exactly the REST shape ([TestSessionWire]).
 */
class VisualizationsTestStartTool(
    private val sessions: VisualizationTestSessionService,
    private val links: TestSessionLinks,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "visualizations_test_start",
            description =
                "Start a test session for a visualization by ID, on its WORKING version (the draft when one exists). Returns " +
                    "preview_url — open it in a browser: it serves the visualization's saved test fixtures through the real " +
                    "renderer, with no login (the token inside the URL is the only credential; it is shown once and dies " +
                    "when you submit or at expires_at) — plus the case names you must give verdicts for and expires_at. " +
                    "Check every case in the page, then call visualizations_test_submit with the SAME key. Editing the " +
                    "visualization voids the session.",
            schema = START_SCHEMA,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val started = sessions.start(ctx.principal.requireWorkspace().id, args.requiredUuid("id"), ctx.principal.userId)
        return TestSessionWire.started(started, links)
    }
}

/**
 * `visualizations_test_submit` (mcp-server.md §6.2.62; the implementation spec's §7, §11.2) — the REST
 * `POST …/tests/sessions/{sid}/results` twin: the per-case verdicts, notes and the CLOSED environment set, parsed by
 * the one [TestSubmissionReader] REST uses; the server runs the mechanical test and derives the status. A GREEN run
 * answers the screenshot upload — URL, header and the single-use token, once. The upload itself is HTTP, not a tool:
 * an `mcp` key reaches no REST route (the owner's ruling (b)). Permission: `visualization.update`.
 */
class VisualizationsTestSubmitTool(
    private val sessions: VisualizationTestSessionService,
    private val links: TestSessionLinks,
) : McpTool {
    override val definition: McpSchema.Tool =
        McpTools.tool(
            name = "visualizations_test_submit",
            description =
                "Submit the verdicts of a test session started with visualizations_test_start — with the SAME key that " +
                    "started it. Give every case a verdict (green or red, optional notes): a case without one makes the run " +
                    "INCOMPLETE. The server re-runs its own mechanical test now; the run is GREEN only when every verdict is " +
                    "green AND that test passes. A GREEN answer carries upload: {url, header, token, expires_at} — POST one " +
                    "PNG or WebP screenshot (at most 4 MiB, raw bytes) to url with the token in that header, once, from your " +
                    "browser or HTTP client. No tool releases: a person releases the visualization in the UI after review.",
            schema = SUBMIT_SCHEMA,
        )

    override fun call(
        args: McpArguments,
        ctx: McpToolContext,
    ): Any {
        val id = args.requiredUuid("id")
        val sessionId = args.requiredUuid("session_id")
        val body = ArtifactJson.mapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(args.rawMap().filterKeys { it in BODY_KEYS })
        val submission = TestSubmissionReader.read(body)
        val submitted =
            sessions.submit(
                ctx.principal.requireWorkspace().id,
                id,
                sessionId,
                ctx.principal.userId,
                submission.verdicts,
                submission.environment,
            )
        return TestSessionWire.submitted(submitted, id, links)
    }

    private companion object {
        /** The arguments that ARE the results body — the reader judges their shape exactly as REST's does. */
        val BODY_KEYS = setOf("cases", "environment")
    }
}

private val START_SCHEMA =
    """
    {
      "type": "object",
      "required": ["id"],
      "properties": {
        "id": {"type": "string", "format": "uuid", "description": "The visualization's id (visualizations_list returns it). It needs at least one test case."}
      },
      "additionalProperties": false
    }
    """.trimIndent()

private val SUBMIT_SCHEMA =
    """
    {
      "type": "object",
      "required": ["id", "session_id", "cases"],
      "properties": {
        "id": {"type": "string", "format": "uuid", "description": "The visualization's id."},
        "session_id": {"type": "string", "format": "uuid", "description": "The session_id visualizations_test_start returned."},
        "cases": {
          "type": "array",
          "description": "One verdict per case name visualizations_test_start listed; an unknown or repeated name is refused.",
          "items": {
            "type": "object",
            "required": ["name", "verdict"],
            "properties": {
              "name": {"type": "string", "description": "The case name, exactly as listed."},
              "verdict": {"type": "string", "enum": ["green", "red"], "description": "green when every assertion of the case held in the preview, else red."},
              "notes": {"type": "string", "description": "What you saw; 2000 characters max."}
            },
            "additionalProperties": false
          }
        },
        "environment": {
          "type": "object",
          "description": "Optional claims about where you looked — a closed set of short strings (120 characters max each).",
          "properties": {
            "theme": {"type": "string"},
            "viewport": {"type": "string"},
            "browser": {"type": "string"},
            "locale": {"type": "string"},
            "renderer_version": {"type": "string"}
          },
          "additionalProperties": false
        }
      },
      "additionalProperties": false
    }
    """.trimIndent()

/** The two test-session tools, in §6.1's order. */
internal fun visualizationTestTools(
    sessions: VisualizationTestSessionService,
    links: TestSessionLinks,
): List<McpTool> = listOf(VisualizationsTestStartTool(sessions, links), VisualizationsTestSubmitTool(sessions, links))
