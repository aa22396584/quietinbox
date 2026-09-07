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
 * The dialog that says a source change was refused (round 36 subagent I2). Read from resources,
 * not hardcoded: every string is in all five catalogues, and a test that repeated the English
 * would pass with the dialog showing the wrong one.
 */
class PolicyFailureDialogTest {

    @get:Rule
    val rule = createComposeRule()

    private val res = InstrumentationRegistry.getInstrumentation().targetContext
    private val title = res.getString(R.string.health_policy_failed_title, "Chat")
    private val settleBody = res.getString(R.string.health_policy_failed_body_settle)
    private val plainBody = res.getString(R.string.health_policy_failed_body)

    @Test
    fun aRefusedSwitchOffNamesTheAppAndSaysWhyNothingElseOnThePageWill() {
        rule.setContent { QuietInboxTheme { PolicyFailureDialog(PolicyFailure("com.example.chat", "Chat", settle = true), onDismiss = {}) } }

        rule.onNodeWithText(title).assertExists()
        rule.onAllNodes(hasText(settleBody)).assertCountEquals(1)
        rule.onAllNodes(hasText(plainBody)).assertCountEquals(0)
    }

    @Test
    fun aRefusedChangeThatSettledNothingDoesNotBlameASettlement() {
        rule.setContent { QuietInboxTheme { PolicyFailureDialog(PolicyFailure("com.example.chat", "Chat", settle = false), onDismiss = {}) } }

        rule.onNodeWithText(title).assertExists()
        rule.onAllNodes(hasText(plainBody)).assertCountEquals(1)
        rule.onAllNodes(hasText(settleBody)).assertCountEquals(0)
    }

    @Test
    fun okDismissesIt() {
        var dismissed = 0
        rule.setContent { QuietInboxTheme { PolicyFailureDialog(PolicyFailure("com.example.chat", "Chat", settle = true), onDismiss = { dismissed++ }) } }

        rule.onNodeWithText(res.getString(R.string.action_ok)).performClick()

        dismissed shouldBe 1
    }
}
