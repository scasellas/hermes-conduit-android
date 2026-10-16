package app.hermesconduit.android

import app.hermesconduit.android.model.ArtifactFilter
import app.hermesconduit.android.model.ArtifactItem
import app.hermesconduit.android.model.ArtifactParser
import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.WorkspaceEntry
import app.hermesconduit.android.model.WorkspaceParser
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class ArtifactsWorkspaceTest {
    private fun obj(json: String) = ConduitJson.parseToJsonElement(json) as JsonObject

    @Test
    fun artifactPageParsesAndDropsInvalidRows() {
        val page = ArtifactParser.page(
            obj(
                """{"ok":true,"next_before":120,"items":[
                  {"kind":"image","source":"delivered","path":"/tmp/a.png","session_id":"s1","session_title":"Chat","message_id":5,"timestamp":1700000000.5,"size":2048},
                  {"kind":"link","source":"link","url":"https://example.com/x","host":"example.com","session_id":"s1","message_id":6,"timestamp":1700000001},
                  {"kind":"link","source":"link","url":"javascript:alert(1)","session_id":"s1","message_id":7,"timestamp":1700000001},
                  {"kind":"file","source":"written","session_id":"s1","message_id":8,"timestamp":1700000001},
                  {"kind":"weird","source":"written","path":"/x","session_id":"s1","message_id":9,"timestamp":1},
                  {"kind":"page","source":"preview","path":"/tmp/chart.html","name":" Chart ","session_id":"s2","message_id":10,"timestamp":1700000002,"exists":false},
                  {"kind":"image","source":"attached","path":"/tmp/a.png","session_id":"s3","message_id":11,"timestamp":1600000000}
                ]}""",
            ),
        )!!
        assertEquals(120L, page.nextBefore)
        assertEquals(listOf("/tmp/a.png", "https://example.com/x", "/tmp/chart.html"), page.items.map { it.id })
        val image = page.items[0]
        assertEquals("a.png", image.name)
        assertEquals(1_700_000_000_500, image.timestampMs)
        assertTrue(image.isVisual)
        assertEquals("example.com", page.items[1].name)
        assertEquals("Chart", page.items[2].name)
        assertFalse(page.items[2].exists)
    }

    @Test
    fun malformedPageIsNull() {
        assertNull(ArtifactParser.page(obj("""{"ok":false,"items":[]}""")))
        assertNull(ArtifactParser.page(obj("""{"ok":true}""")))
    }

    @Test
    fun artifactQueryAndFilters() {
        val q = ArtifactParser.query("work", "s1", ArtifactFilter.IMAGES, "  cat ", 99)
        assertEquals("60", q["limit"])
        assertEquals("s1", q["session_id"])
        assertEquals("images", q["kind"])
        assertEquals("cat", q["q"])
        assertEquals("99", q["before"])
        assertEquals("work", q["profile"])
        val all = ArtifactParser.query("default", null, ArtifactFilter.ALL, "", null)
        assertNull(all["kind"])
        assertNull(all["profile"])
        assertTrue(ArtifactFilter.FILES.includes(ArtifactItem.Kind.AUDIO))
        assertFalse(ArtifactFilter.PAGES.includes(ArtifactItem.Kind.IMAGE))
    }

    @Test
    fun daysGroupInOrder() {
        fun item(id: String, ts: Long) = ArtifactItem(
            ArtifactItem.Kind.FILE, ArtifactItem.Source.WRITTEN, id, "/$id", null, null, true, null, "s", "", 1, ts,
        )
        val day = 86_400_000L
        val days = ArtifactParser.days(listOf(item("a", 3 * day + 10), item("b", 3 * day + 5), item("c", day)), ZoneOffset.UTC)
        assertEquals(2, days.size)
        assertEquals(listOf("a", "b"), days[0].items.map { it.name })
        val merged = ArtifactParser.merged(listOf(item("a", 1)), listOf(item("a", 0), item("d", 0)))
        assertEquals(listOf("a", "d"), merged.map { it.name })
    }

    @Test
    fun workspaceEntriesSortFoldersFirst() {
        val entries = WorkspaceParser.entries(
            obj("""{"entries":[{"name":"b.txt","path":"/w/b.txt"},{"name":"src","path":"/w/src","isDirectory":true},{"name":"A.md","path":"/w/A.md"},{"name":"","path":"/w/x"}]}"""),
        )
        assertEquals(listOf("src", "A.md", "b.txt"), entries.map { it.name })
    }

    @Test(expected = IllegalStateException::class)
    fun workspaceErrorThrows() {
        WorkspaceParser.entries(obj("""{"error":"Path is outside the workspace"}"""))
    }

    @Test
    fun workspacePreviewAndDataUrl() {
        val preview = WorkspaceParser.preview(obj("""{"text":"hi","byteSize":2,"language":"markdown","truncated":true}"""))
        assertEquals("markdown", preview.language)
        assertTrue(preview.truncated)
        assertFalse(preview.binary)
        val (bytes, mime) = WorkspaceParser.decodeDataUrl("data:text/plain;base64,aGk=")!!
        assertArrayEquals("hi".toByteArray(), bytes)
        assertEquals("text/plain", mime)
        assertNull(WorkspaceParser.decodeDataUrl("data:text/plain,hi"))
    }

    @Test
    fun workspaceFlattenFollowsExpandedFolders() {
        val tree = mapOf(
            "/w" to listOf(WorkspaceEntry("src", "/w/src", true), WorkspaceEntry("a", "/w/a", false)),
            "/w/src" to listOf(WorkspaceEntry("main.kt", "/w/src/main.kt", false)),
        )
        assertEquals(listOf("src" to 0, "a" to 0), WorkspaceParser.flatten("/w", tree, emptySet()).map { it.first.name to it.second })
        assertEquals(
            listOf("src" to 0, "main.kt" to 1, "a" to 0),
            WorkspaceParser.flatten("/w", tree, setOf("/w/src")).map { it.first.name to it.second },
        )
    }
}
