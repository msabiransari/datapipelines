package co.datapipelines.web.pipelines

import co.datapipelines.auth.RequiredScope
import co.datapipelines.auth.ScopeMatrix
import co.datapipelines.web.api.ApiResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * The RECEIVER half of promotion (rest-api.md §18, versioning §10).
 *
 * Two endpoints, and they are the ONLY routes under `/api/v1/promotion/`:
 * `PromotionServerKeyFilter` refuses every request on that prefix without a valid pre-shared
 * server key, and reads the key on no other prefix. So the credential's scope (§10.6: "the
 * promotion/import endpoint ONLY … it grants no read access") is a property of the URL space
 * rather than of a per-handler check somebody could forget.
 *
 * The `@RequiredScope` annotations are the second gate, not the first. The peer authenticates
 * as R7's system service account with `author`, which satisfies both — they are here because
 * `ScopeInterceptor` default-denies unannotated handlers under `/api/`, and a route that
 * declares the §7.6 operation it implements is the house rule for every REST surface. A future
 * refactor that let another credential reach these handlers would still meet a declared floor.
 *
 * **No MCP tool and no schedule**: §10.1 D8 makes promotion a human action, and this round's
 * fence excludes `modules/mcp-server` to make that mechanical rather than remembered.
 */
@RestController
@RequestMapping("/api/v1/promotion")
class PromotionController(
    private val inventoryService: PromotionInventoryService,
    private val receiveService: PromotionReceiveService,
) {
    /**
     * §18.1 — what this deployment already holds in [workspace], plus its datasource names and
     * its posture. The sender's whole delta input (§10.2) and §10.5's pre-validation set, in
     * one round trip.
     *
     * An unknown workspace is `workspace.not_found` (404), not the no-oracle 403 an ordinary
     * caller gets: the peer is a trusted deployment, and "you do not have that workspace" is
     * the answer its operator needs.
     */
    @GetMapping("/inventory")
    @RequiredScope(ScopeMatrix.RestOperation.READ_RESOURCES)
    fun inventory(
        @RequestParam workspace: String,
    ): ApiResponse<PromotionWire.Inventory> = ApiResponse.of(inventoryService.inventoryOf(workspace.trim()))

    /**
     * §18.2 — apply one batch: all of it, or none of it (§10.4). The body arrives in push
     * order and is applied in the order given.
     */
    @PostMapping("/push")
    @RequiredScope(ScopeMatrix.RestOperation.MUTATE_PIPELINES_TEMPLATES)
    fun push(
        @RequestBody batch: PromotionWire.Batch,
    ): ApiResponse<PromotionWire.Applied> = ApiResponse.of(receiveService.apply(batch))
}
