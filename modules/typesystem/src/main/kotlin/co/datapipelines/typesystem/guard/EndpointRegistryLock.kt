package co.datapipelines.typesystem.guard

/**
 * The endpoint registry's advisory key (published-endpoints design §4.1; dependency-graph record §15.1 class 3): ONE
 * deployment-wide constant, not one key per path — overlap is a property of parsed patterns, so distinct paths can
 * still collide and per-path keys would not protect absence. Shared by the registry's own insert lock and the writer
 * guard, so both name the same lock; no other advisory lock of this application may reuse it (prerequisite keys use
 * the two-`int4` form, a disjoint key space).
 */
object EndpointRegistryLock {
    const val KEY = 74_0001L
}
