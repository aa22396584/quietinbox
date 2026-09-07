package dev.quietinbox.core.designsystem.components

import io.kotest.matchers.shouldBe
import org.junit.Test

/** Avatar monograms: one glyph for a Han, kana or hangul name, two Latin initials otherwise. */
class MonogramTest {

    @Test
    fun hanNameGivesItsFirstCharacter() {
        monogram("林小美 Mia Lin") shouldBe "林"
    }

    @Test
    fun kanaAndHangulNamesGiveOneGlyphLikeHan() {
        monogram("さくら") shouldBe "さ"
        monogram("プロダクトチーム Product Team") shouldBe "プ"
        monogram("김민수") shouldBe "김"
        monogram("가족 단톡방 Family") shouldBe "가"
    }

    @Test
    fun latinNamesGiveTwoInitials() {
        monogram("Diego Ramos") shouldBe "DR"
        monogram("Mom") shouldBe "MO"
    }

    @Test
    fun blankOrMissingLabelIsAQuestionMark() {
        monogram(null) shouldBe "?"
        monogram("   ") shouldBe "?"
    }

    /** `take` counts UTF-16 units, so half of a surrogate pair used to reach the avatar as tofu. */
    @Test
    fun anEmojiIsNeverCutInHalf() {
        monogram("😀 Mom") shouldBe "😀M"
        monogram("Mom 😀") shouldBe "M😀"
        monogram("😀") shouldBe "😀"
        monogram("😀🎉") shouldBe "😀🎉"
        monogram("A😀") shouldBe "A😀"
        // The invariant behind all of the above: every surrogate that survives is still paired.
        listOf("😀 Mom", "Mom 😀", "😀", "😀🎉", "A😀", "🎉 Party 🎉").forEach { label ->
            val m = monogram(label)
            m.count { it.isHighSurrogate() } shouldBe m.count { it.isLowSurrogate() }
        }
    }

    /** A right-to-left name still gets two initials; the platform handles the display order. */
    @Test
    fun rightToLeftNamesGiveTwoInitials() {
        monogram("موسى الأحمد") shouldBe "ما"
        monogram("דנה כהן") shouldBe "דכ"
    }
}
