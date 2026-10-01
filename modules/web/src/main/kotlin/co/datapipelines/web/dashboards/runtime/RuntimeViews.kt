package co.datapipelines.web.dashboards.runtime

import co.datapipelines.parameters.EvaluateResponse
import co.datapipelines.parameters.EvaluateResponseJson
import co.datapipelines.visualization.ArtifactJson
import co.datapipelines.visualization.ArtifactVersion
import co.datapipelines.visualization.DashboardBody
import co.datapipelines.visualization.DashboardRuntimeConfig
import co.datapipelines.visualization.RefreshExecutionLink
import co.datapipelines.visualization.RefreshRecord
import co.datapipelines.visualization.RendererConfigValidators
import co.datapipelines.visualization.RendererKind
import co.datapipelines.visualization.StateSetting
import co.datapipelines.visualization.VisualizationBody
import co.datapipelines.visualization.VisualizationOccurrence
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode

/**
 * The JSON the runtime routes answer (the implementation spec's §8.1, §8.2, §6.2) — assembled from the stored bodies
 * the module's strict mapper wrote, never from a re-spelled DTO, so the wire form of a layout, a group or an action
 * is the one definition the authoring routes already serve.
 */
internal object RuntimeViews {
    private val nodes = JsonNodeFactory.instance

    /**
     * `GET /runtime/config` (spec §8.1). `bindings` ride BESIDE each `config` — the renderer configuration is stored
     * verbatim and the bindings (a config path → a column) are what the client fills from `visualization_data`.
     * `timeouts.render_seconds` is an addition to the spec's list: the client's render allowance is a server number
     * (§9.6) and this is the one place a client learns it.
     */
    fun config(
        resolved: ResolvedDashboard,
        runtime: DashboardRuntimeConfig,
    ): ObjectNode {
        val served = resolved.served
        val body = served.body
        val mapper = ArtifactJson.mapper
        return nodes.objectNode().also { out ->
            out.put("configuration_id", resolved.configurationId)
            out.putObject("renderer").also {
                it.put("bundle", rendererBundle(resolved.visualizations.values))
            }
            out.putObject("dashboard").also {
                it.put("id", served.record.id.toString())
                it.put("name", served.record.name)
                it.put("version", served.detail.version)
                it.put("status", served.detail.status.name)
            }
            out.set<JsonNode>("layout", mapper.valueToTree(body.layout))
            out.set<JsonNode>("parameter_set", body.parameterSet?.let { mapper.valueToTree(it) } ?: nodes.nullNode())
            val visualizations = out.putArray("visualizations")
            body.visualizations.forEach { occurrence -> visualizations.add(visualization(resolved, occurrence, runtime)) }
            out.set<JsonNode>("groups", mapper.valueToTree(body.groups))
            out.set<JsonNode>("actions", mapper.valueToTree(body.actions))
            out.set<JsonNode>("action_controls", mapper.valueToTree(body.actionControls))
            out.set<JsonNode>("parameter_scopes", mapper.valueToTree(body.parameterScopes))
            out.set<JsonNode>("parameter_state", body.parameterState?.let { mapper.valueToTree(it) } ?: nodes.nullNode())
            out.putObject("timeouts").also {
                it.put("refresh_seconds", refreshSeconds(body, runtime))
                it.put("parameter_lock_seconds", runtime.parameterLockSeconds)
                it.put("render_seconds", runtime.renderSeconds)
            }
            out.putObject("budgets").also {
                it.put("max_bytes_per_source", runtime.maxBytesPerSource)
                it.put("max_bytes_per_refresh", runtime.maxBytesPerRefresh)
            }
        }
    }

    /** One `visualizations[]` entry: the pinned artifact, its renderer, its stored config and the effective timeout. */
    private fun visualization(
        resolved: ResolvedDashboard,
        occurrence: VisualizationOccurrence,
        runtime: DashboardRuntimeConfig,
    ): ObjectNode {
        val body = resolved.served.body
        val mapper = ArtifactJson.mapper
        val pinned = resolved.visualizations.getValue(occurrence.name)
        val refresh = refreshSeconds(body, runtime)
        return nodes.objectNode().also { v ->
            v.put("name", occurrence.name)
            v.putObject("artifact").also {
                it.put("id", pinned.record.id.toString())
                it.put("name", pinned.record.name)
                it.put("version", pinned.detail.version)
            }
            v.putObject("renderer").also {
                it.put("kind", pinned.body.renderer.kind.wire)
                it.put("version", pinned.body.renderer.version)
            }
            v.set<JsonNode>("config", pinned.body.config)
            v.set<JsonNode>("bindings", mapper.valueToTree(pinned.body.bindings))
            v.set<JsonNode>("presentation", pinned.body.presentation?.let { mapper.valueToTree(it) } ?: nodes.nullNode())
            v.put("timeout_seconds", minOf(occurrence.timeoutSeconds ?: refresh, refresh))
        }
    }

    /** The refresh deadline: the dashboard's own `timeouts.refresh_seconds`, else the default; never above the cap. */
    fun refreshSeconds(
        body: DashboardBody,
        runtime: DashboardRuntimeConfig,
    ): Int = minOf(body.timeouts?.refreshSeconds ?: runtime.defaultRefreshSeconds, runtime.maxRefreshSeconds)

    /**
     * The Plotly bundle the host page loads (the implementation spec's §10.4, D63): `"3d"` when ANY pinned
     * visualization is a Plotly renderer whose traces name a 3D type (WebGL), else `"2d"` — the default. One
     * dashboard, exactly one bundle: the two are never on one page, so the derivation is dashboard-level and a
     * single 3D trace loads the heavier bundle for the whole board. Trace types are the validator's closed list
     * at save; anything else here is `"2d"` by the same reading that makes the page default light.
     */
    fun rendererBundle(visualizations: Collection<ArtifactVersion<VisualizationBody>>): String {
        val threeD =
            visualizations.any { pinned ->
                pinned.body.renderer.kind == RendererKind.PLOTLY &&
                    pinned.body.config
                        .path("data")
                        .any { trace -> trace.path("type").asText("") in RendererConfigValidators.PLOTLY_3D_TRACES }
            }
        return if (threeD) "3d" else "2d"
    }

    /**
     * `POST /runtime/parameters` (spec §8.2): the engine's [EvaluateResponseJson] UNCHANGED plus
     * `overrides_applied`, `parents` and the server-assigned `parameter_revision`. The overrides are the dashboard's
     * `parameter_state` (D20): a dimension set to `force_true`/`force_false` wins over the engine's hidden/disabled,
     * per-parameter beating dashboard-wide; a parameter appears in `overrides_applied` only when an override CHANGED
     * something, with both effective dimensions.
     */
    fun parameters(
        response: EvaluateResponse,
        body: DashboardBody,
        revision: Int,
    ): ObjectNode {
        val out = EvaluateResponseJson.write(response)
        val applied = out.putObject("overrides_applied")
        response.parameters.forEach { parameter ->
            val name = parameter.definition.name
            val engineVisible = !parameter.state.hidden
            val engineEnabled = !parameter.state.disabled
            val visible =
                effective(
                    engineVisible,
                    body.parameterState?.dashboard?.visible,
                    body.parameterState
                        ?.parameters
                        ?.get(name)
                        ?.visible,
                )
            val enabled =
                effective(
                    engineEnabled,
                    body.parameterState?.dashboard?.enabled,
                    body.parameterState
                        ?.parameters
                        ?.get(name)
                        ?.enabled,
                )
            if (visible != engineVisible || enabled != engineEnabled) {
                applied.putObject(name).also {
                    it.put("visible", visible)
                    it.put("enabled", enabled)
                }
            }
        }
        val parents = out.putArray("parents")
        response.parameters.filter { it.dependents.isNotEmpty() }.forEach { parents.add(it.definition.name) }
        out.put("parameter_revision", revision)
        return out
    }

    private fun effective(
        engine: Boolean,
        dashboardWide: StateSetting?,
        perParameter: StateSetting?,
    ): Boolean {
        val setting = perParameter.takeIf { it != null && it != StateSetting.INHERIT } ?: dashboardWide
        return when (setting) {
            StateSetting.FORCE_TRUE -> true
            StateSetting.FORCE_FALSE -> false
            else -> engine
        }
    }

    /**
     * A refresh row for the list and read routes (spec §6.2). The execution links are shown only when [showExecutions]
     * — the caller reads executions (`execution.read`): a promoter refreshes a dashboard she may read but reads no
     * execution, so her refresh names none. `selections` are the caller's own or an `execution.read_all` reader's.
     */
    fun refresh(
        record: RefreshRecord,
        links: List<RefreshExecutionLink>?,
        showExecutions: Boolean,
    ): ObjectNode =
        nodes.objectNode().also { out ->
            out.put("refresh_id", record.id.toString())
            out.put("dashboard_id", record.dashboardId.toString())
            out.put("dashboard_version", record.dashboardVersion)
            out.put("instance_id", record.instanceId.toString())
            out.put("scope", record.scope.wire)
            out.set<JsonNode>("targets", ArtifactJson.mapper.readTree(record.targetsJson))
            out.put("parameter_revision", record.parameterRevision)
            out.set<JsonNode>("selections", ArtifactJson.mapper.readTree(record.selectionsJson))
            out.put("status", record.status.name)
            out.put("started_at", record.startedAt.toString())
            out.put("finished_at", record.finishedAt?.toString())
            out.set<JsonNode>("summary", ArtifactJson.mapper.readTree(record.summaryJson))
            if (showExecutions && links != null) {
                val executions = out.putArray("executions")
                links.forEach { link ->
                    executions.addObject().also {
                        it.put("source", link.sourceName)
                        it.put("execution_id", link.executionId.toString())
                        it.put("shared", link.shared)
                    }
                }
            }
        }
}
