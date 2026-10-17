package app.hermesconduit.android.watch

import app.hermesconduit.android.hermes
import app.hermesconduit.wearwire.WatchVoiceWire
import app.hermesconduit.wearwire.WearWire
import com.google.android.gms.tasks.Task
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.asTask

/**
 * Wakes the phone app for its Wear OS watch: messages (call turns, chats,
 * approvals) go to [WatchBridge], a live call's requests and queued messages
 * to [WatchDirectBroker], queued call log lines to the call log.
 */
class WatchBridgeService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (!event.path.startsWith(WearWire.PREFIX)) return
        WatchBridge.get(this).handle(event.sourceNodeId, event.path, event.data)
    }

    /** A watch voice call's request (iOS sendMessage with a reply handler): answered with the reply, or empty for none. */
    override fun onRequest(nodeId: String, path: String, request: ByteArray): Task<ByteArray>? {
        if (path != WatchVoiceWire.PATH) return null
        val message = WatchVoiceWire.decode(request)
        val broker = WatchDirectBroker.get(this)
        val log = WatchPhoneCallLog.get(this)
        return hermes.scope.async {
            if (message == null) {
                log.note("watchVoiceMessageUnread", mapOf("bytes" to request.size))
                return@async ByteArray(0)
            }
            broker.handle(message, waiting = true)?.let(WatchVoiceWire::encode) ?: ByteArray(0)
        }.asTask()
    }

    override fun onDataChanged(events: DataEventBuffer) {
        val bridge = WatchBridge.get(this)
        val data = Wearable.getDataClient(this)
        events.forEach { event ->
            val item = event.dataItem
            val path = item.uri.path.orEmpty()
            if (event.type != DataEvent.TYPE_CHANGED) return@forEach
            when {
                path.startsWith(WearWire.LOG_PREFIX) -> DataMapItem.fromDataItem(item).dataMap.getString(WearWire.DATA_KEY)?.let(bridge::watchLogLine)
                path.startsWith(WatchVoiceWire.QUEUE_PREFIX) -> {
                    val message = DataMapItem.fromDataItem(item).dataMap.getByteArray(WearWire.DATA_KEY)?.let(WatchVoiceWire::decode)
                    // Nothing reads a queued message's reply: the watch answered for itself meanwhile.
                    if (message != null) hermes.scope.launch { WatchDirectBroker.get(this@WatchBridgeService).handle(message, waiting = false) }
                }
                else -> return@forEach
            }
            // Read once: the watch's queue empties as items land here.
            data.deleteDataItems(item.uri)
        }
    }

}
