package app.hermesconduit.android.watch

import app.hermesconduit.wearwire.WearWire
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService

/**
 * Wakes the phone app for its Wear OS watch: messages (call turns, chats,
 * approvals) go to [WatchBridge], queued call log lines to the call log.
 */
class WatchBridgeService : WearableListenerService() {
    override fun onMessageReceived(event: MessageEvent) {
        if (!event.path.startsWith(WearWire.PREFIX)) return
        WatchBridge.get(this).handle(event.sourceNodeId, event.path, event.data)
    }

    override fun onDataChanged(events: DataEventBuffer) {
        val bridge = WatchBridge.get(this)
        val data = Wearable.getDataClient(this)
        events.forEach { event ->
            val item = event.dataItem
            val path = item.uri.path.orEmpty()
            if (event.type != DataEvent.TYPE_CHANGED || !path.startsWith(WearWire.LOG_PREFIX)) return@forEach
            DataMapItem.fromDataItem(item).dataMap.getString(WearWire.DATA_KEY)?.let(bridge::watchLogLine)
            // Read once: the watch's queue empties as lines land here.
            data.deleteDataItems(item.uri)
        }
    }
}
