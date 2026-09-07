package dev.quietinbox.feature.health

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import dev.quietinbox.core.designsystem.R

/**
 * A source-policy change the coordinator refused. [displayName] is the app as the list shows it;
 * [settle] is true for the two changes that must first record a loss the source's pending rows
 * carry — switching it off and removing it — because that is the write that fails, and the
 * user's action is the one that was refused: the source stays on, and nothing else says so.
 */
data class PolicyFailure(val packageName: String, val displayName: String, val settle: Boolean)

/**
 * Says that a change to a source was not made, and why nothing else on the page says so.
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
        text = { Text(stringResource(if (failure.settle) R.string.health_policy_failed_body_settle else R.string.health_policy_failed_body)) },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
    )
}
