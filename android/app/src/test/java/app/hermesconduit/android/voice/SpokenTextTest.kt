package app.hermesconduit.android.voice

import app.hermesconduit.android.model.ContentSegmenter
import app.hermesconduit.android.model.HermesSessionReference
import app.hermesconduit.android.model.SpokenTextFilter
import app.hermesconduit.android.ui.voice.ReadAloudController
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpokenTextTest {
    @After
    fun clearTitles() {
        HermesSessionReference.titles = emptyMap()
    }

    @Test
    fun dropsActionsAndEmojiButKeepsEmphasis() {
        assertEquals(
            " The heist is complete! Report to the court:",
            SpokenTextFilter.filter("*sets down the gavel with a decisive THUMP* The heist is complete! 🎉 Report to the court:"),
        )
        assertEquals(
            "This is *really* important and **Report to the court:** now.",
            SpokenTextFilter.filter("This is *really* important and **Report to the court:** now."),
        )
        assertEquals("Use snake_case_names and ok.", SpokenTextFilter.filter("Use snake_case_names and _leans back in the chair_ ok."))
        assertEquals("* item one\n* item two", SpokenTextFilter.filter("* item one\n* item two"))
        assertEquals("Unclosed *star here and more words.", SpokenTextFilter.filter("Unclosed *star here and more words."))
        assertEquals("2*3*4 is 24 © ™ #1", SpokenTextFilter.filter("2*3*4 is 24 © ™ #1"))
        assertEquals("Done ", SpokenTextFilter.filter("Done 👍🏽 ✨❤️ 🇨🇦 👨‍👩‍👧"))
    }

    @Test
    fun givesTheSameSpeechHoweverTheReplyIsSplit() {
        val reply = "Order! *bangs the gavel twice* The court finds snake_case_var and **bold words here** and _whispers very quietly now_ fine. 🎉 2*3 and *really* done_"
        val whole = SpokenTextFilter.filter(reply)
        for (size in 1..9) {
            val filter = SpokenTextFilter()
            val spoken = StringBuilder()
            reply.chunked(size).forEach { spoken.append(filter.feed(it)) }
            spoken.append(filter.finish())
            assertEquals("chunks of $size", whole.split(" ").filter { it.isNotEmpty() }, spoken.toString().split(" ").filter { it.isNotEmpty() })
        }
        assertFalse(whole.contains("gavel"))
        assertFalse(whole.contains("whispers"))
    }

    @Test
    fun sessionReferencesAreSaidAsTheChatName() {
        HermesSessionReference.titles = mapOf("20261011_142233_ab12cd" to "Router  logs")
        assertEquals("See Router logs for details.", SpokenTextFilter.filter("See @session:default/20261011_142233_ab12cd for details."))
        assertEquals("See the old chat.", SpokenTextFilter.filter("See [the old chat](@session:unknown_id)."))
        assertEquals("Unknown.", SpokenTextFilter.filter("Unknown @session:nope."))
        // Split across deltas, the reference is held until it ends.
        val filter = SpokenTextFilter()
        val spoken = filter.feed("See @sess") + filter.feed("ion:20261011_142233_ab12cd") + filter.feed(" now") + filter.finish()
        assertEquals("See Router logs now", spoken)
    }

    @Test
    fun readAloudSkipsMediaTagsAndTitlesTheFirstLine() {
        val content = "Here is the chart.\nMEDIA:/tmp/chart.png\nDone."
        val readable = ContentSegmenter.removingTags(content)
        assertFalse(readable.contains("MEDIA:"))
        assertTrue(readable.contains("Here is the chart."))
        assertEquals("Summary", ReadAloudController.title("# **Summary**\nbody"))
        val long = "word ".repeat(40)
        val title = ReadAloudController.title(long)
        assertTrue(title.endsWith("…"))
        assertTrue(title.length <= ReadAloudController.MAX_TITLE_LENGTH)
    }
}
