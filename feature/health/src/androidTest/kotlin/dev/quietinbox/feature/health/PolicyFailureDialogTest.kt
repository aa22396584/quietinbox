package dev.quietinbox.feature.health

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.theme.QuietInboxTheme
import io.kotest.matchers.shouldBe
import org.junit.Rule
import org.junit.Test

/**
 * The dialog that says a source change did not go through (round 36 subagent I2; round 39 Codex I1).
 * Read from resources, not hardcoded: every string is in all five catalogues, and a test that
 * repeated the English would pass with the dialog showing the wrong one.
 *
 * Each known outcome shows only its own body. The unclassified body is not the rollback copy —
 * swapping that mapping back to `health_policy_failed_body` makes this suite fail.
 */
class PolicyFailureDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val res = InstrumentationRegistry.getInstrumentation().targetContext
    private val title = res.getString(R.string.health_policy_failed_title, "Chat")
    private val settleBody = res.getString(R.string.health_policy_failed_body_settle)
    private val plainBody = res.getString(R.string.health_policy_failed_body)
    private val lockedBody = res.getString(R.string.health_policy_failed_body_locked)
    private val reloadBody = res.getString(R.string.health_policy_failed_body_reload)
    private val unknownBody = res.getString(R.string.health_policy_failed_body_unknown)

    @Test
    fun aSettlementRollbackNamesTheAppAndUsesOnlyTheSettlementCopy() {
        rule.setContent {
            QuietInboxTheme {
                PolicyFailureDialog(PolicyFailure("com.example.chat", "Chat", PolicyFailure.Kind.SETTLEMENT), onDismiss = {})
            }
        }

        rule.onNodeWithText(title).assertExists()
        rule.onAllNodes(hasText(settleBody)).assertCountEquals(1)
        rule.onAllNodes(hasText(plainBody)).assertCountEquals(0)
        rule.onAllNodes(hasText(unknownBody)).assertCountEquals(0)
    }

    @Test
    fun aRollbackThatSettledNothingDoesNotBlameASettlement() {
        rule.setContent {
            QuietInboxTheme {
                PolicyFailureDialog(PolicyFailure("com.example.chat", "Chat", PolicyFailure.Kind.REFUSED), onDismiss = {})
            }
        }

        rule.onNodeWithText(title).assertExists()
        rule.onAllNodes(hasText(plainBody)).assertCountEquals(1)
        rule.onAllNodes(hasText(settleBody)).assertCountEquals(0)
    }

    @Test
    fun anUnclassifiedFailureDoesNotUseTheRollbackCopy() {
        rule.setContent {
            QuietInboxTheme {
                PolicyFailureDialog(PolicyFailure("com.example.chat", "Chat", PolicyFailure.Kind.UNKNOWN), onDismiss = {})
            }
        }

        rule.onNodeWithText(title).assertExists()
        rule.onAllNodes(hasText(unknownBody)).assertCountEquals(1)
        rule.onAllNodes(hasText(plainBody)).assertCountEquals(0)
        rule.onAllNodes(hasText(settleBody)).assertCountEquals(0)
    }

    @Test
    fun aCommittedChangeThatCouldNotBeReadBackDoesNotClaimTheWriteRolledBack() {
        rule.setContent {
            QuietInboxTheme {
                PolicyFailureDialog(
                    PolicyFailure("com.example.chat", "Chat", PolicyFailure.Kind.COMMITTED_NOT_RELOADED),
                    onDismiss = {},
                )
            }
        }

        rule.onNodeWithText(title).assertExists()
        rule.onAllNodes(hasText(reloadBody)).assertCountEquals(1)
        rule.onAllNodes(hasText(plainBody)).assertCountEquals(0)
        rule.onAllNodes(hasText(settleBody)).assertCountEquals(0)
    }

    @Test
    fun aLockedVaultUsesTheLockCopy() {
        rule.setContent {
            QuietInboxTheme {
                PolicyFailureDialog(PolicyFailure("com.example.chat", "Chat", PolicyFailure.Kind.LOCKED), onDismiss = {})
            }
        }

        rule.onNodeWithText(title).assertExists()
        rule.onAllNodes(hasText(lockedBody)).assertCountEquals(1)
        rule.onAllNodes(hasText(plainBody)).assertCountEquals(0)
        rule.onAllNodes(hasText(settleBody)).assertCountEquals(0)
    }

    @Test
    fun okDismissesIt() {
        var dismissed = 0
        rule.setContent {
            QuietInboxTheme {
                PolicyFailureDialog(
                    PolicyFailure("com.example.chat", "Chat", PolicyFailure.Kind.SETTLEMENT),
                    onDismiss = { dismissed++ },
                )
            }
        }

        rule.onNodeWithText(res.getString(R.string.action_ok)).performClick()

        dismissed shouldBe 1
    }
}
