package dev.quietinbox.feature.health

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.quietinbox.core.designsystem.R

/**
 * A source-policy change that did not go through. [displayName] is the app as the list shows it;
 * [kind] is what the coordinator's exception type proves, and nothing more (round 39, Codex I1):
 * a rollback may be called "nothing was changed", a settlement refusal may say why, a change that
 * committed but could not be read back must not, and a locked vault is its own case.
 */
data class PolicyFailure(val packageName: String, val displayName: String, val kind: Kind) {
    enum class Kind {
        /** The transaction rolled back; the source stays as it was. */
        REFUSED,

        /** Rolled back because a carried-over loss could not be recorded first. */
        SETTLEMENT,

        /** The vault was locked; nothing was attempted. */
        LOCKED,

        /** Committed, and the policy could not be read back: capture follows the previous policy until the next load. */
        COMMITTED_NOT_RELOADED,

        /** An exception the coordinator did not classify: nothing may be promised. */
        UNKNOWN,
    }
}

/**
 * Says that a change to a source did not go through, what is known about it, and why nothing
 * else on the page says so.
 *
 * A settle failure rolls the whole policy transaction back — correctly, since the discard that
 * follows would clear the payload it is the last reader of — but the switch, bound to the
 * repository's flow, then simply springs back to "on" with no word. For an app whose point is
 * "stop capturing this" that silence is the defect (round 36 subagent I2); the dialog is the
 * other half of the transaction's guarantee.
 */
@Composable
fun PolicyFailureDialog(failure: PolicyFailure, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.health_policy_failed_title, failure.displayName)) },
        text = {
            Text(
                stringResource(
                    when (failure.kind) {
                        PolicyFailure.Kind.REFUSED -> R.string.health_policy_failed_body
                        PolicyFailure.Kind.SETTLEMENT -> R.string.health_policy_failed_body_settle
                        PolicyFailure.Kind.LOCKED -> R.string.health_policy_failed_body_locked
                        PolicyFailure.Kind.COMMITTED_NOT_RELOADED -> R.string.health_policy_failed_body_reload
                        PolicyFailure.Kind.UNKNOWN -> R.string.health_policy_failed_body_unknown
                    },
                ),
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
