package co.datapipelines.dependencies

/**
 * One walk's request memo and every provider read it issues (record §7.2 step 3, §25.4); never shared between walks
 * (per-request memoization only, §7.3).
 *
 * A read is issued for the ONE request the walk needs at that point of its depth-first order ([resolve]). It carries, as
 * **speculation**, the pending requests the walk was told to [expect] — grouped per family, at most
 * [GraphLimits.resolveBatchSize] per provider call, and clamped so that the distinct requests ever read never exceed
 * [GraphLimits.nodes] through speculation (the remaining capacity, shared by every family and every batch).
 *
 * **Speculation never decides an outcome** (#868 R1): the walk's result — its snapshot, or the exception it throws — is
 * the result a walk reading one needed request at a time would give, so a refusal the walk reaches is never overtaken by
 * a failing read it did not need yet.
 * - A provider call holding only speculative requests that fails is forgotten: its requests stay unread and are never
 *   speculated on again, so the first time the walk NEEDS one of them it is read then, and its failure propagates there.
 * - A failing call that also holds the needed request is re-issued for the needed request alone; that call's failure
 *   propagates (an infrastructure failure is never converted into a graph, §7.1).
 * - Only the provider call itself is caught ([FamilyDescriptor.fetch]); a provider that breaks the contract
 *   ([FamilyDescriptor.bind]) is a defect and always propagates.
 * - The [Deadline] is checked before EVERY call: an expired deadline refuses a needed read with [DeadlineExceeded] and
 *   skips speculation, so the refusal comes from the next needed read, never from speculation.
 */
internal class Resolutions(
    private val registry: FamilyRegistry,
    private val limits: GraphLimits,
    private val context: ResolveContext,
) {
    private val memo = HashMap<ObjectRequest, Loaded>()
    private val pending = LinkedHashSet<ObjectRequest>()
    private val notSpeculated = HashSet<ObjectRequest>()

    /** The distinct requests read so far (resolved, missing or unsupported). */
    val size: Int get() = memo.size

    /** The outcome of [request] if it was already read; never reads. */
    operator fun get(request: ObjectRequest): Loaded? = memo[request]

    /** Queues [requests], in order, as candidates for a later read's speculation. */
    fun expect(requests: Iterable<ObjectRequest>) {
        requests.forEach { if (it !in memo) pending.add(it) }
    }

    /** The outcome of [needed], read now — with speculation — when it was not read before. */
    fun resolve(needed: ObjectRequest): Loaded {
        memo[needed]?.let { return it }
        val wanted = listOf(needed) + speculation(needed)
        // `needed` leads `wanted`, so its family's group comes first and `needed` sits in that group's first chunk.
        wanted.groupBy { it.family }.forEach { (family, requests) -> load(family, requests, needed) }
        pending.removeAll(wanted.toSet())
        return memo.getValue(needed)
    }

    private fun speculation(needed: ObjectRequest): List<ObjectRequest> {
        val capacity = limits.nodes - memo.size - 1
        if (capacity <= 0) return emptyList()
        return pending
            .asSequence()
            .filter { it != needed && it !in memo && it !in notSpeculated }
            .take(capacity)
            .toList()
    }

    private fun load(
        family: FamilyKey,
        requests: List<ObjectRequest>,
        needed: ObjectRequest,
    ) {
        val descriptor = registry[family]
        if (descriptor == null) {
            requests.forEach { memo[it] = Loaded.Unsupported }
            return
        }
        for (chunk in requests.chunked(limits.resolveBatchSize)) {
            if (needed in chunk) {
                readNeeded(descriptor, chunk, needed)
            } else if (!readSpeculative(descriptor, chunk)) {
                return
            }
        }
    }

    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun <B : Any> readNeeded(
        descriptor: FamilyDescriptor<B>,
        chunk: List<ObjectRequest>,
        needed: ObjectRequest,
    ) {
        requireTime(descriptor.key)
        val answers =
            try {
                descriptor.fetch(chunk, context)
            } catch (failure: RuntimeException) {
                if (chunk.size == 1) throw failure
                // The speculation may be what failed: the needed request alone decides, as a one-at-a-time walk's read would.
                notSpeculated += chunk - needed
                requireTime(descriptor.key)
                val alone = listOf(needed)
                memo.putAll(descriptor.bind(alone, descriptor.fetch(alone, context)))
                return
            }
        memo.putAll(descriptor.bind(chunk, answers))
    }

    /** A needed read never starts after the deadline: [DeadlineExceeded], never a partial graph (§25.4). */
    private fun requireTime(family: FamilyKey) {
        if (context.deadline.expired()) throw DeadlineExceeded(family)
    }

    /** False when the deadline has expired: the family's remaining speculation is skipped, never refused. */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun <B : Any> readSpeculative(
        descriptor: FamilyDescriptor<B>,
        chunk: List<ObjectRequest>,
    ): Boolean {
        if (context.deadline.expired()) return false
        val answers =
            try {
                descriptor.fetch(chunk, context)
            } catch (failure: RuntimeException) {
                // Not needed yet: each of these is read again as a needed request if the walk ever reaches it.
                notSpeculated += chunk
                return true
            }
        memo.putAll(descriptor.bind(chunk, answers))
        return true
    }
}
