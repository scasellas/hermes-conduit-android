package app.hermesconduit.android

import app.hermesconduit.android.model.ConduitJson
import app.hermesconduit.android.model.WakeConfiguration
import app.hermesconduit.android.model.WakeLifecycleCoordinator
import app.hermesconduit.android.model.WakeLifecycleSnapshot
import app.hermesconduit.android.model.WakeModelDescriptor
import app.hermesconduit.android.model.WakePhraseBinding
import app.hermesconduit.android.model.WakePhraseCompiler
import app.hermesconduit.android.model.WakePhraseMatcher
import app.hermesconduit.android.model.WakePhraseValidationError
import app.hermesconduit.android.model.WakeProfileKey
import app.hermesconduit.android.model.WakeProfilePreferences
import app.hermesconduit.android.model.WakeTokenizationTables
import app.hermesconduit.android.model.WakeWordService
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class WakePhraseTest {
    private val key = WakeProfileKey.of("gw", "default")
    private fun binding(phrase: String, profile: String = "default") = WakePhraseBinding(WakeProfileKey.of("gw", profile), phrase, true)

    // Matcher

    @Test fun matchesWholeWordsInOrder() {
        val hey = binding("Hey Fam")
        assertEquals(hey, WakePhraseMatcher.match("ok hey fam what's up", listOf(hey)))
        assertNull(WakePhraseMatcher.match("hey family", listOf(hey)))
        assertNull(WakePhraseMatcher.match("fam hey", listOf(hey)))
    }

    @Test fun foldsCaseAccentsAndPunctuation() {
        val phrase = binding("hey hermès")
        assertEquals(phrase, WakePhraseMatcher.match("HEY, HERMES!", listOf(phrase)))
    }

    @Test fun mostSpecificPhraseWins() {
        val short = binding("hey fam")
        val long = binding("hey fam work", "work")
        assertEquals(long, WakePhraseMatcher.match("hey fam work please", listOf(short, long)))
        assertEquals(short, WakePhraseMatcher.match("hey fam", listOf(short, long)))
    }

    @Test fun scriptsWithoutSpacingMatchContiguousCharacters() {
        val zh = binding("小爱同学")
        assertEquals(zh, WakePhraseMatcher.match("你好小爱同学在吗", listOf(zh)))
        assertNull(WakePhraseMatcher.match("小爱", listOf(zh)))
    }

    @Test fun singleWordsAreNotUsable() {
        assertFalse(WakePhraseMatcher.isUsable("Hermes"))
        assertFalse(WakePhraseMatcher.isUsable("  "))
        assertTrue(WakePhraseMatcher.isUsable("hey hermes"))
        assertTrue(WakePhraseMatcher.isUsable("你好"))
        assertFalse(WakePhraseMatcher.isUsable("好"))
        assertNull(WakePhraseMatcher.match("hermes", listOf(binding("hermes"))))
    }

    // Compiler

    private val compiler = WakePhraseCompiler(WakeTokenizationTables.testFixtures)

    @Test fun compilesEnglishAndChineseTokens() {
        val compiled = compiler.compile(listOf(binding("  Hey   Conduit "), binding("你好", "zh")))
        assertEquals("hey conduit", compiled[0].phrase)
        assertEquals(listOf("HH", "EY", "K", "AA", "N", "D", "UW", "IH", "T"), compiled[0].tokens)
        assertEquals(listOf("ni3", "hao3"), compiled[1].tokens)
        assertEquals("HH EY K AA N D UW IH T @" + compiled[0].alias, compiled[0].keywordLine)
    }

    @Test fun stableAliasIsFnv1a() {
        assertEquals("conduit-4de47e8dc5424b9e", WakePhraseCompiler.stableAlias(WakeProfileKey.of("gw", "default"), "Hey  Conduit"))
    }

    @Test fun rejectsWhatTheModelCantSay() {
        fun expect(error: WakePhraseValidationError, phrase: String, key: WakeProfileKey = this.key) {
            try {
                compiler.compile(listOf(WakePhraseBinding(key, phrase, true)))
                fail("expected $error")
            } catch (e: WakePhraseValidationError) {
                assertEquals(error, e)
            }
        }
        expect(WakePhraseValidationError.UnsupportedEnglishWord("family"), "hey family")
        expect(WakePhraseValidationError.MissingChinesePronunciation("世"), "你世")
        expect(WakePhraseValidationError.UnsupportedCharacter("!"), "hey!")
        expect(WakePhraseValidationError.EmptyPhrase(), "   ")
        expect(WakePhraseValidationError.MissingGatewayOrProfile(), "hey conduit", WakeProfileKey.of("", "default"))
        try {
            compiler.compile(listOf(binding("hey conduit"), binding("Hey Conduit", "other")))
            fail()
        } catch (e: WakePhraseValidationError.DuplicatePhrase) {
            assertEquals("hey conduit", e.phrase)
        }
    }

    @Test fun lexiconTablesKeepTheFirstPronunciation() {
        val tables = WakeTokenizationTables.fromLexicon(sequenceOf("HEY HH EY1", "HERMES HH ER1 M IY0 Z", "HERMES HH EH1 R M IY0 Z", "BAD"))
        assertEquals(listOf("HH", "EY1", "HH", "ER1", "M", "IY0", "Z"), WakePhraseCompiler(tables).tokens("hey hermes"))
    }

    // Descriptor

    @Test fun descriptorMatchesIos() {
        val pack = WakeModelDescriptor.bundledBilingualPack
        assertEquals("sherpa-onnx-kws-zipformer-zh-en-3m-chunk8", pack.id)
        assertEquals("68447f4fbc67e70eee3a93961f36e81e98f47aef73ce7e7ca00885c6cd3616a6", pack.archiveSHA256)
        assertEquals("sherpa-onnx-kws-zipformer-zh-en-3M-2025-12-20", pack.archiveFolder)
        assertEquals("en.phone", pack.lexicon)
        assertEquals("encoder-epoch-13-avg-2-chunk-8-left-64.int8.onnx", pack.encoder)
    }

    // Configuration

    @Test fun configurationStoresAsIosDoes() {
        val config = WakeConfiguration()
            .saving(WakeProfilePreferences(listOf("Hey Conduit", "hey conduit", " ", "Hey  Hermes"), startsFreshConversation = false), key)
        assertEquals(listOf("hey conduit", "hey hermes"), config.preferences(key).enabledPhrases)
        val encoded = config.encodeValues()
        val array = ConduitJson.parseToJsonElement(encoded) as JsonArray
        assertEquals(2, array.size)
        assertEquals("""{"gatewayID":"gw","profileID":"default"}""", array[0].toString())
        assertEquals(config.values, WakeConfiguration.decodeValues(encoded))
        assertEquals(listOf("gw|default|hey conduit", "gw|default|hey hermes"), config.enabledBindings().map { it.id })
        assertTrue(config.removing(key).enabledBindings().isEmpty())
        assertTrue(WakeConfiguration.decodeValues("not json").isEmpty())
        assertEquals(WakeProfilePreferences(), WakeConfiguration().preferences(key))
    }

    // Lifecycle

    private val ready = WakeLifecycleSnapshot(true, true, true, true, true, true)

    @Test fun snapshotArmsOnlyWhenEverythingAllows() {
        assertTrue(ready.canArm)
        assertFalse(ready.copy(isVoiceIdle = false).canArm)
        assertFalse(ready.copy(isForegroundActive = false).canArm)
        val paused = ready.copy(otherAudioAllowsListening = false)
        assertFalse(paused.canArm)
        assertTrue(paused.isPausedForOtherAudio)
        assertFalse(ready.copy(hasWakePhrases = false, otherAudioAllowsListening = false).isPausedForOtherAudio)
    }

    private class FakeService(var fails: Boolean = false) : WakeWordService {
        override var isArmed = false
        var arms = 0
        override fun arm() {
            arms++
            if (fails) throw IllegalStateException("no mic")
            isArmed = true
        }
        override fun disarm() { isArmed = false }
    }

    @Test fun coordinatorArmsOnceAndReportsFailures() {
        val service = FakeService()
        val coordinator = WakeLifecycleCoordinator(service)
        coordinator.update(ready)
        coordinator.update(ready)
        assertEquals(1, service.arms)
        assertTrue(coordinator.isArmed)
        coordinator.update(ready.copy(isVoiceIdle = false))
        assertFalse(service.isArmed)
        val failing = FakeService(fails = true)
        val second = WakeLifecycleCoordinator(failing)
        second.update(ready)
        assertEquals("no mic", second.lastFailureReason)
        second.update(ready.copy(isGatewayConnected = false))
        assertNull(second.lastFailureReason)
    }
}
