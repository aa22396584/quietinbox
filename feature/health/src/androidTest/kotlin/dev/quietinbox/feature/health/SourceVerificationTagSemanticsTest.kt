package dev.quietinbox.feature.health

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import dev.quietinbox.core.designsystem.R
import dev.quietinbox.core.designsystem.components.SourceVerificationTag
import dev.quietinbox.core.designsystem.theme.QuietInboxTheme
import dev.quietinbox.core.model.SourceVerificationTier
import org.junit.Rule
import org.junit.Test

/**
 * Rendered semantics tests for RS-04 UI (round 49):
 * Verifies that SourceVerificationTag accurately renders the appropriate tier text,
 * and that adapters with only synthetic fixtures render the honest SYNTHETIC_ONLY
 * label rather than claiming real-device verified status or the old health_known_source.
 */
class SourceVerificationTagSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    private val res = InstrumentationRegistry.getInstrumentation().targetContext
    private val verifiedLabel = res.getString(R.string.source_tier_verified)
    private val syntheticOnlyLabel = res.getString(R.string.source_tier_synthetic_only)
    private val untestedLabel = res.getString(R.string.source_tier_untested)
    private val knownSourceLabel = res.getString(R.string.health_known_source)

    @Test
    fun syntheticOnlyTierRendersSyntheticOnlyTextAndNotVerifiedOrSupportedSource(): Unit {
        rule.setContent {
            QuietInboxTheme {
                SourceVerificationTag(SourceVerificationTier.SYNTHETIC_ONLY)
            }
        }

        // Exactly one node has the synthetic-only label
        rule.onAllNodes(hasText(syntheticOnlyLabel)).assertCountEquals(1)
        // Must NOT render as verified or the old supported source
        rule.onAllNodes(hasText(verifiedLabel)).assertCountEquals(0)
        rule.onAllNodes(hasText(knownSourceLabel)).assertCountEquals(0)
    }

    @Test
    fun realDevicePassedTierRendersVerifiedText(): Unit {
        rule.setContent {
            QuietInboxTheme {
                SourceVerificationTag(SourceVerificationTier.REAL_DEVICE_PASSED)
            }
        }

        rule.onAllNodes(hasText(verifiedLabel)).assertCountEquals(1)
        rule.onAllNodes(hasText(syntheticOnlyLabel)).assertCountEquals(0)
    }

    @Test
    fun untestedTierRendersUntestedText(): Unit {
        rule.setContent {
            QuietInboxTheme {
                SourceVerificationTag(SourceVerificationTier.UNTESTED)
            }
        }

        rule.onAllNodes(hasText(untestedLabel)).assertCountEquals(1)
        rule.onAllNodes(hasText(syntheticOnlyLabel)).assertCountEquals(0)
        rule.onAllNodes(hasText(verifiedLabel)).assertCountEquals(0)
    }
}
