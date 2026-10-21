package app.hermesconduit.android.voice

import app.hermesconduit.android.model.ComposerDictation
import app.hermesconduit.android.model.DictationCustomWords
import app.hermesconduit.android.model.DictationEngine
import app.hermesconduit.android.model.DictationTranscript
import app.hermesconduit.android.model.HermesSpeechFailure
import app.hermesconduit.android.model.ReadAloudSpeed
import app.hermesconduit.android.model.SpeechRecognitionLocale
import app.hermesconduit.android.model.VoiceBackgroundJobCommand
import app.hermesconduit.android.model.VoiceBackgroundJobCommands
import app.hermesconduit.android.model.VoiceLevelMeterMath
import app.hermesconduit.android.model.VoiceProfilePreferences
import app.hermesconduit.android.model.VoiceSpokenCommandDefaults
import app.hermesconduit.android.model.VoiceSpokenCommands
import app.hermesconduit.android.model.VoiceTranscriptionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSpokenCommandsTest {
    private val endDefaults = VoiceSpokenCommands.legacyEndConversationPhrases
    private val stopDefaults = VoiceSpokenCommands.legacyStopPhrases

    @Test
    fun spokenCommandMatchingAllowsHowSpeechTranscribesAGoodbye() {
        for (utterance in listOf("Goodbye.", " Good bye.", "Good-bye!", "Okay, goodbye.", "Alright, bye bye!", "Bye, thanks!", "Thank you, goodbye.", "Bye for now.", "That’s all, thanks.", "好的，再见。")) {
            assertTrue(utterance, VoiceSpokenCommands.matchesSpokenCommand(utterance, endDefaults))
        }
        for (utterance in listOf("goodbye to the old server", "say goodbye to Sam", "Thanks.", "Okay.", "bye the way, one more thing", "")) {
            assertFalse(utterance, VoiceSpokenCommands.matchesSpokenCommand(utterance, endDefaults))
        }
        for (utterance in listOf("Bye Goodbye", "Bye. Goodbye.", "Okay, bye, goodbye, thanks!", "That's all, bye.")) {
            assertTrue(utterance, VoiceSpokenCommands.matchesSpokenCommand(utterance, endDefaults))
        }
        assertFalse("At most three", VoiceSpokenCommands.matchesSpokenCommand("bye bye bye bye", endDefaults))
    }

    @Test
    fun askingToEndCallAndTheEndCallMarker() {
        for (utterance in listOf("End the call.", "Hang up please.", "Okay, you can hang up.", "Cool. Okay, goodbye.")) {
            assertTrue(utterance, VoiceSpokenCommands.asksToEndCall(utterance, listOf("goodbye")))
        }
        for (utterance in listOf("Hang up the laundry reminder", "End the call with Alex at five", "Tell Sam goodbye")) {
            assertFalse(utterance, VoiceSpokenCommands.asksToEndCall(utterance, listOf("goodbye")))
        }
        assertTrue(VoiceSpokenCommands.isEndCallMarker("  end-call: goodbye"))
        assertTrue(VoiceSpokenCommands.isEndCallMarker("End the call: the user said goodbye"))
        assertFalse(VoiceSpokenCommands.isEndCallMarker("Send: end call reminders"))
        assertFalse(VoiceSpokenCommands.asksToEndCall("End the call.", emptyList()))
    }

    @Test
    fun wholeUtteranceMatchingNormalizesCaseApostrophesAndPunctuation() {
        assertTrue(VoiceSpokenCommands.matches("Goodbye!!!", listOf("goodbye")))
        assertTrue(VoiceSpokenCommands.matches(" GOODBYE ", listOf("  Goodbye!  ")))
        assertTrue(VoiceSpokenCommands.matches("That’s All!", listOf("  that's all  ")))
        assertFalse(VoiceSpokenCommands.matches("that's all folks", listOf("that's all")))
        assertFalse(VoiceSpokenCommands.matches("can you stop talking about that", listOf("stop", "stop talking")))
        assertFalse(VoiceSpokenCommands.matches("...", listOf("!!!")))
        assertFalse(VoiceSpokenCommands.matches("stop", emptyList()))
        assertTrue(VoiceSpokenCommands.matches("再见。", endDefaults))
        assertTrue(VoiceSpokenCommands.matches("停止。", stopDefaults))
        assertFalse(VoiceSpokenCommands.matches("我跟他说再见了", endDefaults))
        assertFalse(VoiceSpokenCommands.matches("别说了吗", stopDefaults))
    }

    @Test
    fun phraseListsAreCanonicalizedAndMigrated() {
        assertEquals(listOf("Stop", "be quiet", "halt!"), VoiceSpokenCommands.canonicalizedPhraseList(listOf("Stop", "stop", "STOP ", "be quiet", "be quiet.", "", "  ", "halt!")))
        val stopLegacy = listOf(VoiceSpokenCommands.previousDefaultStopPhrases, VoiceSpokenCommands.legacyStopPhrases)
        assertTrue(VoiceSpokenCommands.isUncustomized(listOf(" Stop ", "Stop Talking", "be quiet"), stopLegacy))
        assertFalse(VoiceSpokenCommands.isUncustomized(listOf("stop", "stop talking", "be quiet", "halt"), stopLegacy))
        assertFalse("an emptied list is a choice", VoiceSpokenCommands.isUncustomized(emptyList(), stopLegacy))
        val builtIns = listOf("stop", "stop talking", "be quiet")
        assertNull(VoiceSpokenCommands.storedPhrases(listOf("be quiet", " Stop", "stop talking"), builtIns))
        assertEquals(listOf("stop", "halt"), VoiceSpokenCommands.storedPhrases(listOf("stop", "halt"), builtIns))
        assertEquals(emptyList<String>(), VoiceSpokenCommands.storedPhrases(emptyList(), builtIns))
    }

    @Test
    fun builtInsComeFromTheCatalogValueOrEnglish() {
        assertEquals(VoiceSpokenCommandDefaults.englishStopPhrases, VoiceSpokenCommandDefaults.phrases("Built-in stop phrases", "Built-in stop phrases", VoiceSpokenCommandDefaults.englishStopPhrases))
        assertEquals(listOf("stopp", "hör auf"), VoiceSpokenCommandDefaults.phrases("stopp| hör auf |Stopp", "Built-in stop phrases", emptyList()))
        // No language loaded in JVM tests: English.
        assertEquals(listOf("stop", "stop talking", "be quiet"), VoiceSpokenCommandDefaults.stopPhrases)
    }

    @Test
    fun preferencesDecodeOlderBlobsAndKeepUnknownKeys() {
        assertTrue(VoiceProfilePreferences.parse("{}").continuousConversation)
        val legacy = VoiceProfilePreferences.parse("""{"spokenStopPhrases":["stop","stop talking","be quiet","停止","别说了","不要说了"],"spokenEndConversationPhrases":["goodbye","bye","end conversation","that's all"]}""")
        assertNull(legacy.spokenStopPhrases)
        assertNull(legacy.spokenEndConversationPhrases)
        val custom = VoiceProfilePreferences.parse("""{"spokenStopPhrases":["stop","halt","停止"],"spokenEndConversationPhrases":[],"geminiLiveVoice":"Kore","transcriptionMode":"speechServer"}""")
        assertEquals(listOf("stop", "halt", "停止"), custom.spokenStopPhrases)
        assertEquals(emptyList<String>(), custom.spokenEndConversationPhrases)
        assertEquals(VoiceTranscriptionMode.SPEECH_SERVER, custom.resolvedTranscriptionMode)
        val written = custom.copy(continuousConversation = false).toJson()
        assertEquals("Kore", written["geminiLiveVoice"]?.toString()?.trim('"'))
        assertNull("following the app language is not written back", VoiceProfilePreferences().toJson()["spokenStopPhrases"])
        val roundTrip = VoiceProfilePreferences.parse(written.toString())
        assertFalse(roundTrip.continuousConversation)
        assertEquals(VoiceTranscriptionMode.HERMES, VoiceProfilePreferences.parse(null).resolvedTranscriptionMode)
    }

    @Test
    fun voiceJobSessionOptionsPreferTheChosenModel() {
        val prefs = VoiceProfilePreferences(voiceJobModel = "fast-model", voiceJobProvider = "", voiceJobReasoningEffort = "low")
        assertEquals(Triple("fast-model", null, "low"), prefs.voiceJobSessionOptions("main", "openai"))
        assertEquals(Triple("main", "openai", null), VoiceProfilePreferences().voiceJobSessionOptions("main", "openai"))
    }
}

class VoiceBackgroundJobCommandsTest {
    @Test
    fun startPrefixCarriesTheTaskWithOriginalCasing() {
        assertEquals(VoiceBackgroundJobCommand.Start("check why my server went down"), VoiceBackgroundJobCommands.parse("Background job, check why my server went down."))
        assertEquals(VoiceBackgroundJobCommand.Start("Review PR 12"), VoiceBackgroundJobCommands.parse("Start a background job: Review PR 12"))
        assertEquals(VoiceBackgroundJobCommand.Start("summarize today's email"), VoiceBackgroundJobCommands.parse("run in the background summarize today's email"))
        assertEquals(VoiceBackgroundJobCommand.Start("检查服务器"), VoiceBackgroundJobCommands.parse("后台任务检查服务器"))
        assertEquals(VoiceBackgroundJobCommand.Start("检查服务器"), VoiceBackgroundJobCommands.parse("后台任务，检查服务器。"))
    }

    @Test
    fun statusAndCancelMatchWholeUtterancesOnly() {
        assertEquals(VoiceBackgroundJobCommand.Status, VoiceBackgroundJobCommands.parse("Job status."))
        assertEquals(VoiceBackgroundJobCommand.Status, VoiceBackgroundJobCommands.parse("background jobs"))
        assertEquals(VoiceBackgroundJobCommand.Status, VoiceBackgroundJobCommands.parse("后台任务状态"))
        assertEquals(VoiceBackgroundJobCommand.CancelAll, VoiceBackgroundJobCommands.parse("Cancel background jobs!"))
        assertEquals(VoiceBackgroundJobCommand.CancelAll, VoiceBackgroundJobCommands.parse("取消后台任务"))
        assertNull(VoiceBackgroundJobCommands.parse("what is the job status of the build"))
    }

    @Test
    fun ordinarySentencesNeverStartJobs() {
        assertNull(VoiceBackgroundJobCommands.parse("background job"))
        assertNull(VoiceBackgroundJobCommands.parse("background jobsite cleanup"))
        assertNull(VoiceBackgroundJobCommands.parse("tell me about background jobs in iOS"))
        assertNull(VoiceBackgroundJobCommands.parse("what's in the background of this photo"))
        assertNull(VoiceBackgroundJobCommands.parse("   "))
    }
}

class DictationModelsTest {
    @Test
    fun engineFallsBackToTheDevice() {
        assertEquals(DictationEngine.DEVICE, DictationEngine.resolved(null))
        assertEquals(DictationEngine.DEVICE, DictationEngine.resolved("whisper"))
        assertEquals(DictationEngine.HERMES, DictationEngine.resolved("hermes"))
        assertEquals(DictationEngine.DEVICE, DictationEngine.resolved("speechServer") { it != DictationEngine.SPEECH_SERVER })
    }

    @Test
    fun customWordsKeepSpellingAndDropRepeats() {
        assertEquals(listOf("Milim", "Hermes Conduit", "OpenAI"), DictationCustomWords.canonicalized(listOf("  Milim  ", "milim", "", "   ", "Hermes   Conduit", "OpenAI")))
        assertEquals(DictationCustomWords.MAXIMUM_COUNT, DictationCustomWords.canonicalized((0 until 150).map { "word$it" }).size)
        assertEquals(DictationCustomWords.MAXIMUM_LENGTH, DictationCustomWords.canonicalized("a".repeat(200)).length)
        assertEquals(listOf("Acme", "Zyx Corp"), DictationCustomWords.decode(DictationCustomWords.encode(listOf("Acme", "acme", "Zyx Corp"))))
        assertEquals("", DictationCustomWords.encode(emptyList()))
    }

    @Test
    fun aTapWhileTranscribingWaitsForTheWords() {
        assertEquals(ComposerDictation.Tap.NOTHING, ComposerDictation.tap(isCapturing = false, isStarting = false, canDictate = true, isTranscribing = true))
        assertEquals(ComposerDictation.Tap.START, ComposerDictation.tap(isCapturing = false, isStarting = false, canDictate = true))
        assertEquals(ComposerDictation.Tap.STOP, ComposerDictation.tap(isCapturing = true, isStarting = false, canDictate = true))
        assertEquals(ComposerDictation.Tap.CANCEL_START, ComposerDictation.tap(isCapturing = false, isStarting = true, canDictate = true))
    }

    @Test
    fun dictatedTextGoesAfterTheDraft() {
        assertEquals("Hello there", ComposerDictation.draft("Hello", " there "))
        assertEquals("Hello\nthere", ComposerDictation.draft("Hello\n", "there"))
        assertEquals("there", ComposerDictation.draft("", "there"))
        assertEquals("Hello", ComposerDictation.draft("Hello", "  "))
        assertTrue(ComposerDictation.draftIsAsDictationLeftIt("Hi", "Hi", null))
        assertFalse(ComposerDictation.draftIsAsDictationLeftIt("Hi you", "Hi", "Hi there"))
    }

    @Test
    fun transcriptKeepsFinishedUtterancesAcrossPauses() {
        val transcript = DictationTranscript()
        transcript.receive("send the report", endsUtterance = false)
        transcript.receive("send the report to Sam", endsUtterance = true)
        transcript.receive("and copy", endsUtterance = false)
        assertEquals("send the report to Sam and copy", transcript.text)
        // A final result that repeats the whole text replaces it.
        transcript.receive("send the report to Sam and copy Alex", endsUtterance = true)
        assertEquals("send the report to Sam and copy Alex", transcript.text)
        assertFalse(DictationTranscript.startsOver("one two three", "one two three four", afterBoundary = false))
        assertTrue(DictationTranscript.startsOver("one two three four", "five", afterBoundary = false))
    }

    @Test
    fun readAloudSpeedLabelsAndFallback() {
        assertEquals(listOf("1x", "1.25x", "1.5x", "1.75x", "2x"), ReadAloudSpeed.entries.map { it.label })
        assertEquals(ReadAloudSpeed.NORMAL, ReadAloudSpeed.of(3.3f))
        assertEquals(ReadAloudSpeed.FASTER, ReadAloudSpeed.of(1.5f))
    }

    @Test
    fun levelMeterMapsLinearPeaksToDbfs() {
        assertEquals(0.0, VoiceLevelMeterMath.displayFraction(0f), 0.0001)
        assertEquals(0.0, VoiceLevelMeterMath.displayFraction(0.0001f), 0.0001)
        assertEquals(0.0, VoiceLevelMeterMath.displayFraction(-0.5f), 0.0001)
        assertEquals(0.0, VoiceLevelMeterMath.displayFraction(Float.NaN), 0.0001)
        assertEquals(0.51, VoiceLevelMeterMath.displayFraction(0.034f), 0.02)
        assertEquals(2.0 / 3.0, VoiceLevelMeterMath.displayFraction(0.1f), 0.01)
        assertEquals(1.0, VoiceLevelMeterMath.displayFraction(5f), 0.0001)
        assertEquals(1.0, VoiceLevelMeterMath.displayFraction(Float.POSITIVE_INFINITY), 0.0001)
    }

    @Test
    fun speechFailuresNeverShowLinksOrTokens() {
        val edge = "403, message='Invalid response status', url='wss://speech.platform.bing.com/consumer?TrustedClientToken=abc' (edge)"
        assertTrue(HermesSpeechFailure.message(edge).contains("edge-tts"))
        val cleaned = HermesSpeechFailure.cleaned("Provider failed: see https://example.com/x?token=1 for details")
        assertFalse(cleaned.contains("https://"))
        assertEquals("Hermes speech stream failed.", HermesSpeechFailure.cleaned("   "))
        assertTrue(HermesSpeechFailure.cleaned("x".repeat(500)).endsWith("…"))
    }
}

class SpeechRecognitionLocaleTest {
    private val recognizer = listOf("en-US", "en-GB", "ru-RU", "zh-CN", "zh-TW", "yue-CN", "fr-FR", "fr-CA")

    @Test
    fun dictationUsesTheDeviceLanguageOverTheAppLocale() {
        assertEquals("ru-RU", SpeechRecognitionLocale.preferred(listOf("ru-RU", "en-US"), "en-RU", recognizer))
    }

    @Test
    fun dictationFillsInTheRegionAndScript() {
        assertEquals("ru-RU", SpeechRecognitionLocale.preferred(listOf("ru"), "en", recognizer))
        assertEquals("fr-CA", SpeechRecognitionLocale.preferred(listOf("fr"), "en-CA", recognizer))
        assertEquals("zh-TW", SpeechRecognitionLocale.preferred(listOf("zh-Hant-HK"), "en-HK", recognizer))
        assertEquals("zh-CN", SpeechRecognitionLocale.preferred(listOf("zh-Hans-CN"), "zh-CN", recognizer))
    }

    @Test
    fun onDeviceTranscriptionSkipsServerOnlyLanguages() {
        assertEquals("en-GB", SpeechRecognitionLocale.preferred(listOf("ru-RU", "en-GB"), "en-RU", recognizer) { !it.startsWith("ru") })
    }

    @Test
    fun cantoneseNeverStandsInForMandarin() {
        assertEquals("yue-CN", SpeechRecognitionLocale.preferred(listOf("yue-Hans-CN"), "en-US", recognizer))
        assertEquals("zh-CN", SpeechRecognitionLocale.preferred(listOf("zh-Hans"), "en-US", recognizer))
    }

    @Test
    fun dictationSkipsLanguagesTheRecognizerLacks() {
        assertEquals("en-GB", SpeechRecognitionLocale.preferred(listOf("eo", "en-GB"), "en-US", recognizer))
        assertEquals("en-US", SpeechRecognitionLocale.preferred(listOf("eo"), "en-US", recognizer))
        assertEquals("en-US", SpeechRecognitionLocale.preferred(listOf("eo"), "en-RU", recognizer))
    }
}
