package dev.quietinbox.parsers.apps

import dev.quietinbox.core.model.ContentStatus
import dev.quietinbox.core.model.KnownSources
import dev.quietinbox.core.model.MessageCandidate
import dev.quietinbox.core.model.NotificationShape
import dev.quietinbox.core.model.NotificationSnapshot
import dev.quietinbox.core.model.ParseWarning
import dev.quietinbox.core.model.SenderCandidate
import dev.quietinbox.core.model.TimestampQuality
import dev.quietinbox.core.parser.TextHeuristics

/**
 * WhatsApp (`com.whatsapp`).
 *
 * Adds two things on top of the standard behaviour:
 *  - status wording (`Checking for new messages`, `WhatsApp Web is currently active`, backup
 *    progress) is classified as a notice and yields zero messages;
 *  - a multi-line plain body whose every line carries a `Sender: ` prefix is split into one
 *    candidate per line, which the standard parser keeps as a single blob.
 *
 * Every phrase is a synthetic guess (see README, SYNTHETIC_ONLY).
 */
class WhatsAppParser : AppParser() {
    override val id: String = "whatsapp"
    override val packages: Set<String> = setOf(KnownSources.WHATSAPP)

    override val placeholderPhrases: Set<String> = setOf(
        "您可能有新訊息",
        "你可能有新訊息",
        "you have unread messages",
    )

    override val noticePhrases: Set<String> = setOf(
        "checking for new messages",
        "正在檢查新訊息",
        "whatsapp web is currently active",
        "whatsapp web 目前已啟用",
    )

    override val noticePrefixes: Set<String> = setOf(
        "backing up",
        "backup",
        "restoring",
        "正在備份",
        "正在還原",
    )

    override fun appSingleCandidates(
        snapshot: NotificationSnapshot,
        warnings: MutableSet<ParseWarning>,
    ): List<MessageCandidate> {
        val rows = groupRows(snapshot.shape) ?: return super.appSingleCandidates(snapshot, warnings)

        warnings += ParseWarning.SENDER_SPLIT_HEURISTIC
        val (timestamp, quality) = timestamp(null, snapshot)
        if (quality == TimestampQuality.OBSERVED_ONLY) warnings += ParseWarning.NO_TIMESTAMP
        return rows.pairs.mapIndexed { index, pair ->
            MessageCandidate(
                ordinal = index,
                body = pair.second,
                sender = SenderCandidate(displayName = pair.first),
                sourceTimestampEpochMs = timestamp,
                timestampQuality = quality,
                contentStatus = ContentStatus.NOTIFICATION_TEXT,
                textTruncated = rows.cutInsideLastRow && index == rows.pairs.lastIndex,
            )
        }
    }

    /**
     * The other half of [GroupRows.cutInsideLastRow]: when the cut fell on a separator the last
     * surviving row is whole and the row that began after the separator is gone — a loss that
     * lives on no row, so it goes on the batch (round 35 Codex I1). Decided by the same analysis
     * the rows were split by; the standard parser's plain path, which this adapter leaves alone
     * for anything [groupRows] rejects, never splits and so never reaches here with a true.
     */
    override fun wholeMessagesLost(shape: NotificationShape, messages: List<MessageCandidate>): Boolean {
        if (shape.messages.isNotEmpty() || shape.historicMessages.isNotEmpty() || shape.textLines.isNotEmpty()) return false
        val rows = groupRows(shape) ?: return false
        return rows.truncatedBody && !rows.cutInsideLastRow
    }

    private class GroupRows(val pairs: List<Pair<String, String>>, val truncatedBody: Boolean, val cutInsideLastRow: Boolean)

    /** The `Sender: text` rows of a group body, or null when the body is not one this adapter splits. */
    private fun groupRows(shape: NotificationShape): GroupRows? {
        val bounded = pickBodyBounded(shape) ?: return null
        val body = bounded.value
        val truncatedBody = bounded.truncated
        val lines = body.split('\n').map(String::trim).filter(String::isNotEmpty)
        if (lines.size < 2) return null

        val split = lines.map { TextHeuristics.splitSenderPrefix(it) }
        if (split.any { it == null }) return null
        val pairs = split.filterNotNull()

        val senders = pairs.map { it.first }
        val title = shape.title?.value
        val looksLikeGroup = shape.isGroupConversation == true ||
            (senders.distinct().size >= 2 && senders.none { it == title })
        if (!looksLikeGroup) return null

        // Truncation takes the tail, so only the last of these rows can be the one that lost
        // text — but only when the cut fell inside it. A cut landing on a line separator leaves
        // the last surviving row complete: what was lost is a whole row, and marking this one
        // would say the wrong thing about text that is all there (round 34 I1 / M4). What
        // remains after the final separator decides it; a cut in the middle of that segment is
        // still only an upper bound, which is the honest one to keep.
        val cutInsideLastRow = truncatedBody && body.substringAfterLast('\n').isNotBlank()
        return GroupRows(pairs, truncatedBody, cutInsideLastRow)
    }
}
