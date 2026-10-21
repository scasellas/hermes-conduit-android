package app.hermesconduit.android

import app.hermesconduit.android.model.ContentSegment
import app.hermesconduit.android.model.ContentSegmenter
import app.hermesconduit.android.model.MessageNormalizer
import app.hermesconduit.android.model.SlashCommand
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.ui.SlashCommands
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentSegmenterTest {
    @Test
    fun mermaidAndMathBlocksAreSplitOut() {
        val segments = ContentSegmenter.segments(
            "Intro\n\n```mermaid\ngraph TD; A-->B\n```\n\n$$\nE=mc^2\n$$\n\n```kotlin\nval x = 1\n```\nEnd",
        )
        assertEquals(ContentSegment.Markdown("Intro"), segments[0])
        assertEquals(ContentSegment.Mermaid("graph TD; A-->B", true), segments[1])
        assertEquals(ContentSegment.Math("E=mc^2", true), segments[2])
        assertTrue((segments[3] as ContentSegment.Markdown).text.contains("val x = 1"))
    }

    @Test
    fun unfinishedMermaidStaysSource() {
        val segments = ContentSegmenter.segments("```mermaid\ngraph TD; A-->", streaming = true)
        assertEquals(ContentSegment.Mermaid("graph TD; A-->", false), segments.single())
    }

    @Test
    fun mediaTagsAndDirectives() {
        assertEquals(listOf(ContentSegment.Media("/tmp/out.png")), ContentSegmenter.mediaSegments("MEDIA:/tmp/out.png"))
        assertEquals(
            listOf(ContentSegment.Markdown("Here it is:"), ContentSegment.Media("/Users/me/My Files/report.pdf")),
            ContentSegmenter.mediaSegments("Here it is: MEDIA:/Users/me/My Files/report.pdf"),
        )
        assertEquals(listOf(ContentSegment.Media("/r.pdf", "the report")), ContentSegmenter.mediaSegments("[the report](MEDIA:/r.pdf)"))
        assertNull(ContentSegmenter.mediaSegments("Use `MEDIA:/path.png` to send files"))
        assertEquals(emptyList<ContentSegment>(), ContentSegmenter.mediaSegments("[[audio_as_voice]]"))
        assertEquals("Done", ContentSegmenter.removingTags("Done\nMEDIA:/tmp/a.mp3"))
    }

    @Test
    fun previewDirective() {
        val segments = ContentSegmenter.segments("See ::preview{file=\"/tmp/page.html\"} now")
        assertEquals(
            listOf(ContentSegment.Markdown("See"), ContentSegment.Preview("/tmp/page.html"), ContentSegment.Markdown("now")),
            segments,
        )
        assertNull(ContentSegmenter.mediaSegments("std::preview{x} isn't one"))
    }

    @Test
    fun slashParsingAndCatalog() {
        assertEquals(Triple("compress", "focus on tests", "compress focus on tests"), MessageNormalizer.parseSlash("  /compress focus on tests"))
        assertNull(MessageNormalizer.parseSlash("hello /x"))
        val catalog = MessageNormalizer.slashCatalog(
            ConduitJson.parseToJsonElement("""{"categories":[{"name":"Skills","pairs":[["/deploy","Deploy it"]]}],"canon":{"ship":"deploy"}}"""),
            listOf(SlashCommand("help", "Help")),
        )
        assertEquals(listOf("deploy", "help"), catalog.map { it.name })
        assertEquals(listOf("ship"), catalog.first().aliases)
        assertEquals(listOf("deploy"), SlashCommands.suggestions("/sh", catalog).map { it.name })
        assertTrue(SlashCommands.suggestions("/deploy now", catalog).isEmpty())
    }
}
