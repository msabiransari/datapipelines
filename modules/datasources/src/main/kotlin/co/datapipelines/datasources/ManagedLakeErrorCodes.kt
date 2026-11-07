package co.datapipelines.datasources

/**
 * #818 — the `lake.*` codes the managed read path raises inside this module (pipeline-contract §13.25).
 * They cannot live in [DatasourceErrorCodes], whose drift guard pins that object to exactly the
 * `datasource.*` codes of §13.8. `PipelineErrorCodes.Lake` carries the same strings, and
 * `ManagedLakeErrorCodesSpecDriftTest` pins both constants to the §13.25 table.
 */
object ManagedLakeErrorCodes {
    /** No live managed generation, and the catalog read a new one needs failed. 503. */
    const val UNAVAILABLE = "lake.unavailable"

    /** A node or introspection read named a managed table with no view in the generation. 409. */
    const val TABLE_UNAVAILABLE = "lake.table.unavailable"
}
