package app.hermesconduit.android

import app.hermesconduit.android.model.BotMentions
import app.hermesconduit.android.model.BotProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Port of ConduitTests/BotMentionTests.swift. */
class BotMentionsTest {
    private fun bot(name: String, title: String? = null, displayName: String = "", previousNames: List<String> = emptyList()) =
        BotProfile(name = name, botTitle = title, displayName = displayName, previousNames = previousNames)

    private fun names(text: String, roster: List<BotProfile>, active: String? = "default") =
        BotMentions.resolve(text, roster, active).map { it.name }

    @Test
    fun resolution() {
        assertEquals(listOf("researcher"), names("@researcher have a look", listOf(bot("researcher"), bot("writer"))))
        val buddy = listOf(bot("Researcher", title = "Research Buddy"))
        assertEquals(listOf("Researcher"), names("@research-buddy please", buddy))
        assertEquals(listOf("Researcher"), names("@researchbuddy please", buddy))
        assertEquals(listOf("atlas"), names("@atlas-prime go", listOf(bot("atlas", displayName = "Atlas Prime"))))
        assertEquals(emptyList<String>(), names("@nobody here", listOf(bot("researcher"))))
        assertEquals(emptyList<String>(), names("contact user@example.com today", listOf(bot("example", title = "Example"))))
        assertEquals(emptyList<String>(), names("```\n@researcher\n```", listOf(bot("researcher"))))
        assertEquals(emptyList<String>(), names("ping `@researcher` done", listOf(bot("researcher"))))
        assertEquals(listOf("writer", "researcher"), names("@writer then @researcher review", listOf(bot("researcher"), bot("writer"))))
        assertEquals(listOf("researcher"), names("@researcher and @researcher again", listOf(bot("researcher"))))
        assertEquals(emptyList<String>(), names("@researcher@remote go", listOf(bot("researcher"))))
    }

    @Test
    fun primaryProfileAnswersToHermesExceptAsListener() {
        val roster = listOf(bot("default"), bot("researcher"))
        assertEquals(listOf("default"), names("@hermes do the thing", roster, active = "researcher"))
        assertEquals(listOf("default"), names("@hermes do the thing", roster, active = null))
        assertEquals(emptyList<String>(), names("@hermes do the thing", roster, active = "default"))
        assertEquals(emptyList<String>(), names("@researcher yourself", listOf(bot("researcher")), active = "researcher"))
        assertEquals(listOf("researcher"), names("@researcher yourself", listOf(bot("researcher")), active = "default"))
    }

    @Test
    fun ambiguityAndRenameHistory() {
        val reviewers = listOf(bot("alpha", title = "Reviewer"), bot("beta", title = "Reviewer"))
        assertEquals(emptyList<String>(), names("@reviewer check this", reviewers))
        assertEquals(listOf("alpha"), names("@alpha check this", reviewers))
        assertEquals(listOf("researcher"), names("@scout where were you", listOf(bot("researcher", previousNames = listOf("scout")), bot("writer"))))
        val renamed = listOf(bot("alpha"), bot("beta", previousNames = listOf("alpha")))
        assertEquals(listOf("alpha"), names("@alpha hello", renamed))
        assertEquals(emptyList<String>(), names("@alpha hello", renamed, active = "alpha"))
        val shared = listOf(bot("researcher", previousNames = listOf("scout")), bot("writer", previousNames = listOf("scout")))
        assertEquals(emptyList<String>(), names("@scout where were you", shared))
    }

    @Test
    fun annotationAppendsUpstreamNote() {
        val roster = listOf(bot("default"), bot("researcher", title = "Research Buddy"))
        val text = "have a look @researcher"
        val annotated = BotMentions.annotated(text, BotMentions.resolve(text, roster, "default"))
        assertTrue(annotated.startsWith(text))
        assertTrue("@researcher = agent profile \"researcher\" (\"Research Buddy\")" in annotated)
        assertTrue("[@mentions resolved from the Bot Mode roster — the user is referring to: " in annotated)
        assertTrue("never forward the user’s text verbatim" in annotated)
        assertTrue(annotated.endsWith("If this session has no message_agent tool, agent messaging is unavailable here — say so.]"))
        assertTrue("@hermes = agent profile \"default\"" in BotMentions.annotated("x", listOf(bot("default"))))
    }

    @Test
    fun middleware() {
        val researcher = bot("researcher")
        assertNull(BotMentions.middlewareAnnotation("plain text, user@example.com, `/quoted`", listOf(researcher), "default"))
        assertNull(BotMentions.middlewareAnnotation("@researcher hello", emptyList(), "default"))
        val result = BotMentions.middlewareAnnotation("@researcher hello", listOf(researcher), "default")
        assertNotNull(result)
        assertTrue(result!!.startsWith("@researcher hello"))
        val roster = listOf(researcher, bot("writer"))
        assertNull(BotMentions.middlewareAnnotation("> Ask @writer for a draft\r\n\r\nSounds good", roster, "default"))
        val quoted = "> Ask @writer for a draft\n\n@researcher check this first"
        assertEquals(BotMentions.annotated(quoted, listOf(researcher)), BotMentions.middlewareAnnotation(quoted, roster, "default"))
    }

    @Test
    fun foreverChatReroute() {
        assertEquals("/compact", BotMentions.foreverChatRerouteText("/new", true))
        assertEquals("/compact", BotMentions.foreverChatRerouteText("/reset", true))
        assertNull(BotMentions.foreverChatRerouteText("/new", false))
        assertEquals("/compact", BotMentions.foreverChatRerouteText("  /new  ", true))
        assertNull(BotMentions.foreverChatRerouteText("/new extra", true))
        assertNull(BotMentions.foreverChatRerouteText("/compact", true))
    }

    @Test
    fun tagHelpers() {
        assertEquals("research-buddy", BotMentions.mentionTag(bot("a", title = "Research Buddy")))
        assertEquals("researcher", BotMentions.mentionTag(bot("researcher")))
        assertEquals("hermes", BotMentions.handle(bot("default")))
        assertEquals(emptyList<String>(), BotMentions.mentionNameForms("All"))
        assertEquals(emptyList<String>(), BotMentions.mentionNameForms("Everyone"))
        assertEquals(emptyList<String>(), BotMentions.mentionNameForms("Hermes!"))
        assertEquals(emptyList<String>(), BotMentions.mentionNameForms("   "))
        assertEquals(listOf("research----buddy-v2", "research--buddyv2"), BotMentions.mentionNameForms("Research -- Buddy (v2)"))
        assertEquals(listOf("research-buddy", "researchbuddy"), BotMentions.mentionNameForms("Research Buddy"))
        assertFalse(BotMentions.isValidMentionToken("-x"))
        assertFalse(BotMentions.isValidMentionToken("_admin"))
        assertEquals(emptyList<String>(), BotMentions.mentionNameForms("_admin"))
        assertTrue(BotMentions.isValidMentionToken("a_b-c9"))
        assertTrue(BotMentions.isValidMentionToken("Researcher"))
    }
}
