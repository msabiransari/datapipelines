// module-structure.md §5.11 — layer 0 beside `typesystem`: the ONLY internal dependency is
// `typesystem`, for the canonical [LogicalType] a kind declares its inputs and output in.
//
// The purity is the point (calculators design §0.4, C12). A kind is a pure function of its
// inputs; it reads no database, no HTTP, no clock and no configuration. That property is worth
// nothing as prose — so it is a BUILD rule (`verifyModuleDependencies` over the §4.2 table) and
// a source rule (`CalculatorPurityTest` below refuses an I/O import), and the two together are
// what let the executor treat a kind as safe to evaluate anywhere, in any order.
plugins { id("datapipelines.common-conventions") }

dependencies {
    implementation(project(":modules:typesystem"))
}
