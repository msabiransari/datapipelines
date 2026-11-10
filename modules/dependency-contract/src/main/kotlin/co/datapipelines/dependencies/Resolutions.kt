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
 * - Every provider call that carries speculation runs inside the context's [ReadIsolation], so its failure leaves the
 *   providers' shared state — a database transaction — as it was: forgetting an exception is not enough when the
 *   failed statement aborted the transaction every later read runs in (#868 R1, round 3).
 * - A provider call holding only speculative requests that fails is forgotten: its requests stay unread and are never
 *   speculated on again, so the first time the walk NEEDS one of them it is read then, and its failure propagates there.
 * - A failing call that also holds the needed request is re-issued for the needed request alone; that call's failure
 *   propagates (an infrastructure failure is never converted into a graph, §7.1).
 * - A call holding the needed request alone is never isolated: its failure is the walk's outcome, as it was the old
 *   walk's.
 * - Only the provider call itself is forgiven ([FamilyDescriptor.fetch]); a provider that breaks the contract
 *   ([FamilyDescriptor.bind]) is a defect and always propagates, and so does a failure of the fence itself (the
 *   isolation could not be set up, or could not restore the state).
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

    private fun <B : Any> readNeeded(
        descriptor: FamilyDescriptor<B>,
        chunk: List<ObjectRequest>,
        needed: ObjectRequest,
    ) {
        requireTime(descriptor.key)
        if (chunk.size == 1) {
            memo.putAll(descriptor.bind(chunk, descriptor.fetch(chunk, context)))
            return
        }
        val answers = fenced(descriptor, chunk)
        if (answers != null) {
            memo.putAll(descriptor.bind(chunk, answers))
            return
        }
        // The speculation may be what failed: the needed request alone decides, as a one-at-a-time walk's read would —
        // on the state the fence restored, so a failure here is the needed read's own.
        notSpeculated += chunk - needed
        requireTime(descriptor.key)
        val alone = listOf(needed)
        memo.putAll(descriptor.bind(alone, descriptor.fetch(alone, context)))
    }

    /**
     * The provider call for [chunk] inside the context's [ReadIsolation]: its answers, or null when the call failed and
     * the fence restored the state. Only the call's own failure is forgiven — it is carried through the fence in a
     * marker only this class throws, so a failure of the fence itself can never pass for it.
     */
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun <B : Any> fenced(
        descriptor: FamilyDescriptor<B>,
        chunk: List<ObjectRequest>,
    ): Map<ObjectRequest, Outcome<B>>? =
        try {
            context.readIsolation.isolate {
                try {
                    descriptor.fetch(chunk, context)
                } catch (failure: RuntimeException) {
                    throw ForgivableRead(failure)
                }
            }
        } catch (forgiven: ForgivableRead) {
            null
        }

    /** A speculative call's own failure, on its way through the fence; never escapes [Resolutions]. */
    private class ForgivableRead(
        cause: RuntimeException,
    ) : RuntimeException(cause.message, cause, false, false)

    /** A needed read never starts after the deadline: [DeadlineExceeded], never a partial graph (§25.4). */
    private fun requireTime(family: FamilyKey) {
        if (context.deadline.expired()) throw DeadlineExceeded(family)
    }

    /** False when the deadline has expired: the family's remaining speculation is skipped, never refused. */
    private fun <B : Any> readSpeculative(
        descriptor: FamilyDescriptor<B>,
        chunk: List<ObjectRequest>,
    ): Boolean {
        if (context.deadline.expired()) return false
        val answers = fenced(descriptor, chunk)
        if (answers == null) {
            // Not needed yet: each of these is read again as a needed request if the walk ever reaches it.
            notSpeculated += chunk
        } else {
            memo.putAll(descriptor.bind(chunk, answers))
        }
        return true
    }
}
