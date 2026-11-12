package co.datapipelines.web.ui

/**
 * #876 G6 — the eight catalog entries (brief §A.6, in order) every workspace that shows a Dependencies pane lists
 * last in its `template[data-page-assets]`: the three graph vendors, the four dependency modules, then the mount.
 * The render tests of the five workspaces read their catalog's tail against this one list.
 */
internal val DEPENDENCY_CATALOG =
    listOf(
        "/vendor/dagre/dagre.min.js",
        "/vendor/cytoscape/cytoscape.min.js",
        "/vendor/cytoscape/cytoscape-dagre.js",
        "/js/dependencies/renderer.js",
        "/js/dependencies/tree-view.js",
        "/js/dependencies/list.js",
        "/js/dependencies/source.js",
        "/js/dependencies/mount.js",
    )

/** The five module entries a page that already lists the three graph vendors adds (the pipeline editor). */
internal val DEPENDENCY_MODULES = DEPENDENCY_CATALOG.drop(3)
