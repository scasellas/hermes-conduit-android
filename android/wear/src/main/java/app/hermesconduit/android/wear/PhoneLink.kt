package app.hermesconduit.android.wear

import android.content.Context
import android.net.Uri
import app.hermesconduit.wearwire.PhoneContext
import app.hermesconduit.wearwire.WatchVoiceWire
import app.hermesconduit.wearwire.WearMessage
import app.hermesconduit.wearwire.WearWire
import com.google.android.gms.wearable.CapabilityClient
import com.google.android.gms.wearable.CapabilityInfo
import com.google.android.gms.wearable.DataClient
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.PutDataMapRequest
import com.google.android.gms.wearable.PutDataRequest
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * The watch end of the Data Layer link to Conduit on the phone (iOS
 * WatchLink): finds the phone by its capability, sends [WearMessage]s,
 * delivers the phone's messages as [incoming], and keeps the phone's
 * [PhoneContext] current.
 */
class PhoneLink(private val context: Context, private val scope: CoroutineScope) {
    private val messages: MessageClient = Wearable.getMessageClient(context)
    private val capabilities: CapabilityClient = Wearable.getCapabilityClient(context)
    private val data: DataClient = Wearable.getDataClient(context)

    private val _reachable = MutableStateFlow(false)
    /** Conduit on the phone can be reached right now. */
    val reachable: StateFlow<Boolean> = _reachable

    private val _phoneContext = MutableStateFlow<PhoneContext?>(null)
    val phoneContext: StateFlow<PhoneContext?> = _phoneContext

    private val _incoming = MutableSharedFlow<WearMessage>(extraBufferCapacity = 256)
    val incoming: SharedFlow<WearMessage> = _incoming

    @Volatile private var phone: Node? = null

    private val messageListener = MessageClient.OnMessageReceivedListener { event ->
        if (!event.path.startsWith(WearWire.PREFIX)) return@OnMessageReceivedListener
        WearWire.decode(event.path, event.data)?.let { _incoming.tryEmit(it) }
    }

    private val capabilityListener = CapabilityClient.OnCapabilityChangedListener { info -> pick(info) }

    private val dataListener = DataClient.OnDataChangedListener { events ->
        events.forEach { event ->
            if (event.type == DataEvent.TYPE_CHANGED && event.dataItem.uri.path == WearWire.CONTEXT) {
                DataMapItem.fromDataItem(event.dataItem).dataMap.getString(WearWire.DATA_KEY)?.let(PhoneContext::decode)?.let { _phoneContext.value = it }
            }
        }
    }

    fun start() {
        messages.addListener(messageListener)
        capabilities.addListener(capabilityListener, WearWire.CAPABILITY_PHONE)
        data.addListener(dataListener)
        scope.launch { refresh() }
    }

    private fun pick(info: CapabilityInfo) {
        val nodes = info.nodes
        phone = nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        _reachable.value = phone != null
    }

    /** Looks for the phone again and reads its latest context. */
    suspend fun refresh(): Boolean {
        runCatching { pick(capabilities.getCapability(WearWire.CAPABILITY_PHONE, CapabilityClient.FILTER_REACHABLE).await()) }
            .onFailure { _reachable.value = false }
        runCatching {
            val uri = Uri.Builder().scheme(PutDataRequest.WEAR_URI_SCHEME).authority("*").path(WearWire.CONTEXT).build()
            val items = data.getDataItems(uri).await()
            try {
                items.mapNotNull { DataMapItem.fromDataItem(it).dataMap.getString(WearWire.DATA_KEY)?.let(PhoneContext::decode) }
                    .maxByOrNull { it.updatedAtMs }?.let { _phoneContext.value = it }
            } finally {
                items.release()
            }
        }
        return _reachable.value
    }

    /** False when the phone can't be reached or the message didn't go. */
    suspend fun send(message: WearMessage): Boolean {
        val node = phone ?: run { refresh(); phone } ?: return false
        val (path, bytes) = WearWire.encode(message)
        return runCatching { messages.sendMessage(node.id, path, bytes).await() }.isSuccess
    }

    /** Sends [request] and waits for the first message [answer] accepts. */
    suspend fun <T : Any> ask(request: WearMessage, timeoutMs: Long = 15_000, answer: (WearMessage) -> T?): T? =
        withTimeoutOrNull(timeoutMs) {
            val waiting = CompletableDeferred<T>()
            val collector = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                incoming.first { message -> answer(message)?.let(waiting::complete) != null }
            }
            try {
                if (!send(request)) null else waiting.await()
            } finally {
                collector.cancel()
            }
        }

    /**
     * A live call's request to the phone, answered with its reply (iOS
     * sendMessage with a reply handler). Throws when the phone can't be
     * reached or doesn't answer within [timeoutMs]; null for an empty reply.
     */
    suspend fun request(message: WatchVoiceWire.Message, timeoutMs: Long = 15_000): WatchVoiceWire.Message? {
        val node = phone ?: run { refresh(); phone } ?: throw java.io.IOException(L10n.tr("Couldn't reach Conduit on your phone."))
        val reply = withTimeoutOrNull(timeoutMs) { messages.sendRequest(node.id, WatchVoiceWire.PATH, WatchVoiceWire.encode(message)).await() }
            ?: throw java.io.IOException(L10n.tr("Conduit on the phone didn't answer in time."))
        if (reply.isEmpty()) return null
        return WatchVoiceWire.decode(reply) ?: throw java.io.IOException(L10n.tr("Conduit on the phone sent something this Watch app can't read."))
    }

    /** Queues a live call's message for the phone (its end, a job): it arrives whenever Conduit next runs there. */
    fun queue(message: WatchVoiceWire.Message): Boolean {
        val request = PutDataMapRequest.create(WatchVoiceWire.QUEUE_PREFIX + UUID.randomUUID()).apply {
            dataMap.putByteArray(WearWire.DATA_KEY, WatchVoiceWire.encode(message))
        }
        return runCatching { data.putDataItem(request.asPutDataRequest().setUrgent()) }.isSuccess
    }

    /** Queues a call log line for the phone: it arrives even when the phone isn't reachable now. */
    fun queueLog(line: String) {
        val request = PutDataMapRequest.create(WearWire.LOG_PREFIX + UUID.randomUUID()).apply { dataMap.putString(WearWire.DATA_KEY, line) }
        runCatching { data.putDataItem(request.asPutDataRequest()) }
    }
}
