package dev.quietinbox.platform.capture

/**
 * Why a source-policy change did not go through, as far as the coordinator can tell, so that the
 * screen promises only what is known (round 39, Codex I1). A vault that is locked throws
 * [dev.quietinbox.platform.storage.db.VaultUnavailableException] itself, before any transaction,
 * and keeps its own type.
 */
sealed class PolicyChangeException(message: String, cause: Throwable) : Exception(message, cause) {
    /** The change is one transaction and it threw: rolled back, nothing was written. */
    class Refused(cause: Throwable) : PolicyChangeException("policy change rolled back", cause)

    /** The settle walk inside that transaction could not record a carried-over loss: rolled back. */
    class SettlementRefused(cause: SettlementFailedException) : PolicyChangeException("settlement refused, policy change rolled back", cause)

    /**
     * The transaction committed and the policy could not be read back afterwards, twice: the
     * in-memory allow-list follows the previous policy until the next load (the vault's next open).
     */
    class CommittedNotReloaded(cause: Throwable) : PolicyChangeException("policy change committed, reload failed", cause)
}

/** Thrown inside the policy transaction when a carried-over loss cannot be recorded. */
class SettlementFailedException(message: String) : IllegalStateException(message)
