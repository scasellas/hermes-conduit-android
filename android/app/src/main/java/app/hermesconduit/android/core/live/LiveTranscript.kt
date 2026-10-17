package app.hermesconduit.android.core.live

import java.util.UUID

/** One line of a live call's transcript. `text` grows while the line streams. */
data class LiveTranscriptEntry(
    val id: String = UUID.randomUUID().toString(),
    val fromUser: Boolean,
    val text: String,
)

/** A transcript line that just finished, with its final text: what TalkBack announces. */
data class LiveFinishedTurn(val fromUser: Boolean, val text: String, val id: String = UUID.randomUUID().toString())
