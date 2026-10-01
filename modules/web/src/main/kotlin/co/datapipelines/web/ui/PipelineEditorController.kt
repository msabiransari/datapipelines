package co.datapipelines.web.ui

import co.datapipelines.auth.Permission
import co.datapipelines.auth.RequiredScope
import org.springframework.stereotype.Controller
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.servlet.view.RedirectView
import org.springframework.web.util.UriComponentsBuilder
import java.util.UUID

/**
 * The OLD editor route, kept as a **compatibility redirect** (workspace spec §3.2, #348): the
 * canonical read page is `/pipelines/{id}` ([PipelineWorkspaceController]) since the read
 * workspace made version selection explicit, and every historical link into
 * `/pipelines/{id}/editor` — the explorers' Open actions, the version rows before their
 * repoint, datasource facts, the global search — enters the same page through this 302.
 *
 * The floor dropped from `PIPELINE_EXECUTE` to [Permission.PIPELINE_READ] **in the same
 * commit** as the auth.md §7.6 rows and the [co.datapipelines.web.api.ReadFloorTest] family:
 * the redirect is a read of the route table, and the page it names is a read page — the
 * promoter walks through it to the released content exactly as the canonical route admits
 * (122's execute floor described the page the route USED to serve; that page is gone).
 *
 * An explicit valid `version` and a supported `tab` survive the redirect; a malformed version
 * is the same house 400 the canonical route answers — a compatibility shim is not a licence
 * to clamp a caller's input to a different version. No-version links follow the canonical
 * current-first default on purpose: that is the intended UI change (spec §3.2).
 */
@Controller
class PipelineEditorController {
    @GetMapping("/pipelines/{id}/editor")
    @RequiredScope(Permission.PIPELINE_READ)
    fun editor(
        @PathVariable id: UUID,
        @RequestParam(required = false) version: String?,
        @RequestParam(required = false) tab: String?,
    ): RedirectView {
        // Validated, not forwarded blind: the same parse the canonical page runs, and only a
        // tab of the closed set survives. A UUID path variable needs no escaping; the parsed
        // version is digits and the tab a wire word, so the built URI carries no free input.
        val parsedVersion = PipelineWorkspaceModel.parseRequestedVersion(version)
        val supportedTab = PipelineWorkspaceController.PipelineWorkspaceTab.entries.firstOrNull { it.wire == tab }
        val builder = UriComponentsBuilder.fromPath("/pipelines/$id")
        if (parsedVersion != null) builder.queryParam("version", parsedVersion)
        if (supportedTab != null) builder.queryParam("tab", supportedTab.wire)
        return RedirectView(builder.build().toUriString())
    }
}
