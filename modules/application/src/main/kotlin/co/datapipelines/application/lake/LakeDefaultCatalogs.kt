package co.datapipelines.application.lake

import java.util.UUID

/**
 * The default catalog of a LAKE datasource, as its own connection would name it (#790) — read from the registry of
 * record and its URL text (`co.datapipelines.datasources.DuckDbDefaultCatalog`), never by opening a connection: the
 * scan runs inside a release's or an import's metadata transaction.
 *
 * Asked only for a datasource the workspace-scoped lookup already admitted as LAKE. NULL when the catalog cannot be
 * derived — the datasource is gone by now, or its URL is a form the derivation does not know; the resolver then
 * leaves a catalog-qualified name UNRESOLVED rather than guess (owner ruling 2026-11-13).
 */
fun interface LakeDefaultCatalogs {
    fun defaultCatalogOf(
        datasource: String,
        workspaceId: UUID,
    ): String?
}
