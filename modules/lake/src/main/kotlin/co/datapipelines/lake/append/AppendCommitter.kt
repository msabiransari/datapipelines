package co.datapipelines.lake.append

import co.datapipelines.lake.LakeCatalogSession
import co.datapipelines.lake.delivery.DeliveryCommitEvidence
import co.datapipelines.lake.delivery.DeliveryLedgerRepository
import co.datapipelines.lake.delivery.DeliveryManifest
import co.datapipelines.lake.delivery.LakeDelivery
import org.apache.iceberg.Table
import java.util.concurrent.CancellationException

/** A verdict about catalog publication, before the conditional ledger stamp. */
sealed interface AppendCommitOutcome {
    data class Published(
        val evidence: DeliveryCommitEvidence,
    ) : AppendCommitOutcome

    data object Contended : AppendCommitOutcome

    data object Refused : AppendCommitOutcome

    data object Unknown : AppendCommitOutcome
}

/** Refresh, durable duplicate check, and one fenced Iceberg transaction per CAS attempt (§8a). */
class AppendCommitter(
    private val deliveries: DeliveryLedgerRepository,
) {
    /** [beforeCommit] marks uncertainty before entering the library; errors never authorize file deletion. */
    @Suppress("LongParameterList", "TooGenericExceptionCaught", "ReturnCount") // Each terminal verdict exits without another library call.
    fun commit(
        session: LakeCatalogSession,
        table: Table,
        delivery: LakeDelivery,
        manifest: DeliveryManifest,
        files: WrittenAppend,
        recipe: WrittenRecipe,
        checkActive: () -> Unit,
        beforeCommit: () -> Unit,
    ): AppendCommitOutcome {
        repeat(MAX_ATTEMPTS) {
            checkActive()
            table.refresh()
            deliveries.committedEvidence(delivery.id)?.let { evidence -> return AppendCommitOutcome.Published(evidence) }
            try {
                beforeCommit()
                session.write {
                    val transaction = table.newTransaction()
                    val append = transaction.newAppend()
                    files.files.forEach { append.appendFile(it) }
                    append
                        .set("dp.delivery.key", "${manifest.key.feed}/${manifest.key.id}")
                        .set("dp.operation.id", delivery.operationId.toString())
                        .set("dp.recipe", recipe.key)
                        .set("dp.recipe.sha256", recipe.sha256)
                        .commit()
                    checkActive()
                    transaction.commitTransaction()
                }
                return AppendCommitOutcome.Published(requireNotNull(deliveries.committedEvidence(delivery.id, delivery.operationId)))
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                when (CommitClassifier.classify(failure)) {
                    CommitVerdict.RecheckRevisionLog -> Unit
                    CommitVerdict.Refused -> return AppendCommitOutcome.Refused
                    CommitVerdict.Unknown -> return AppendCommitOutcome.Unknown
                    CommitVerdict.Committed -> error("A thrown commit failure cannot be a normal return.")
                }
            }
        }
        // The final CAS miss also gets a fresh durable-evidence check, before the negative verdict.
        return deliveries.committedEvidence(delivery.id)?.let { AppendCommitOutcome.Published(it) } ?: AppendCommitOutcome.Contended
    }

    private companion object {
        const val MAX_ATTEMPTS = 3
    }
}
