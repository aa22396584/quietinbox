package dev.quietinbox.feature.onboarding

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.theme.QuietInboxTheme
import dev.quietinbox.core.model.SourceVerificationTier
import org.junit.Rule
import org.junit.Test

/**
 * Rendered Compose semantics test for Onboarding SourcesStep:
 * Verifies that sources render honest tier badges (e.g. SYNTHETIC_ONLY for WhatsApp)
 * and never claim REAL_DEVICE_PASSED based solely on adapter presence.
 */
class SourcesStepSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    private val res = InstrumentationRegistry.getInstrumentation().targetContext
    private val verifiedLabel = res.getString(R.string.source_tier_verified)
    private val syntheticOnlyLabel = res.getString(R.string.source_tier_synthetic_only)
    private val untestedLabel = res.getString(R.string.source_tier_untested)

    @Test
    fun sourcesStepRendersHonestTierPerChoice(): Unit {
        val choices = listOf(
            SourceChoice(
                packageName = "com.whatsapp",
                label = "WhatsApp",
                installed = true,
                hasAdapter = true,
                tier = SourceVerificationTier.SYNTHETIC_ONLY,
            ),
            SourceChoice(
                packageName = "dev.quietinbox.app.debug",
                label = "Synthetic Publisher",
                installed = true,
                hasAdapter = true,
                tier = SourceVerificationTier.REAL_DEVICE_PASSED,
            ),
            SourceChoice(
                packageName = "com.custom.app",
                label = "Custom App",
                installed = true,
                hasAdapter = false,
                tier = SourceVerificationTier.UNTESTED,
            ),
        )

        val state = OnboardingUiState(
            step = 1,
            choices = choices,
        )

        rule.setContent {
            QuietInboxTheme {
                SourcesStep(state = state, onToggle = {})
            }
        }

        // WhatsApp has synthetic only label, NOT verified
        rule.onAllNodes(hasText(syntheticOnlyLabel)).assertCountEquals(1)
        // Synthetic publisher has verified label
        rule.onAllNodes(hasText(verifiedLabel)).assertCountEquals(1)
        // Custom app has untested label
        rule.onAllNodes(hasText(untestedLabel)).assertCountEquals(1)
    }
}
