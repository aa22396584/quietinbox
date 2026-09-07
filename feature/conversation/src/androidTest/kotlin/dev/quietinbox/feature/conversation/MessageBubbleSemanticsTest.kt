package dev.quietinbox.feature.conversation

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import dev.quietinbox.core.designsystem.theme.QuietInboxTheme
import dev.quietinbox.core.model.CaptureOrigin
import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.DedupState
import dev.quietinbox.core.model.MediaState
import dev.quietinbox.core.model.Message
import dev.quietinbox.core.model.MessageKind
import dev.quietinbox.core.model.TimestampQuality
import org.junit.Rule
import org.junit.Test

/**
 * A11Y-02: in a group chat the sender's name must be part of the same semantics node as the body,
 * or a screen reader reads two separate items and the message never says who sent it.
 *
 * This exists because reasoning about the modifier was not enough: an earlier attempt wrapped the
 * row in `semantics(mergeDescendants = true)` from the outside, which is a no-op — `combinedClickable`
 * already makes the bubble a merging node, and a merging node is never absorbed by an outer one.
 * Only the merged tree, which is what TalkBack consumes, settles it.
 */
class MessageBubbleSemanticsTest {

    @get:Rule
    val rule = createComposeRule()

    private val message = Message(
        id = 1,
        conversationId = 1,
        sourceMessageId = null,
        senderName = SENDER,
        senderKey = "sis",
        isSelf = false,
        body = BODY,
        kind = MessageKind.TEXT,
        sourceTimestampEpochMs = 1_757_000_000_000,
        timestampQuality = TimestampQuality.SOURCE_MESSAGE,
        observedAtEpochMs = 1_757_000_000_000,
        postedAtEpochMs = 1_757_000_000_000,
        origin = CaptureOrigin.LIVE,
        contentStatus = ContentStatus.FULL_STRUCTURED,
        dedupState = DedupState.CONFIRMED,
        revisionCount = 0,
        observationCount = 1,
        mediaState = MediaState.NONE,
        mediaBlobId = null,
        sortKey = 1_757_000_000_000,
    )

    private fun show() = rule.setContent {
        QuietInboxTheme {
            MessageBubble(
                message = message,
                isGroup = true,
                showSender = true,
                selected = false,
                highlighted = false,
                selecting = false,
                onToggleSelect = {},
                onCopy = {},
                onDeleteOnly = {},
                loadThumbnail = { null },
            )
        }
    }

    @Test
    fun theSenderAndTheBodyAreOneNodeInTheMergedTree() {
        show()
        // Exactly one node carries both: that is a single stop for a screen reader, and it names
        // the sender. Two nodes here is the defect.
        rule.onAllNodes(hasText(SENDER, substring = true) and hasText(BODY, substring = true))
            .assertCountEquals(1)
    }

    @Test
    fun theyAreStillDrawnAsSeparateTextsUnderneath() {
        show()
        // The negative control for the assertion above: in the unmerged tree they are two Texts, so
        // the merged match is the merge doing its job and not the two happening to be one composable.
        rule.onAllNodes(hasText(SENDER, substring = true) and hasText(BODY, substring = true), useUnmergedTree = true)
            .assertCountEquals(0)
    }

    private companion object {
        const val SENDER = "姊姊 Sis"
        const val BODY = "Grandma says hello."
    }
}
