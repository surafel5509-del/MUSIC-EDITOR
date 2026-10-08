package com.studioone.mobile.core.realtime.socket

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import timber.log.Timber

enum class SocketState { DISCONNECTED, CONNECTING, CONNECTED, RECONNECTING }

/** Wire frames of the collaboration channel (JSON payloads, see docs/COLLABORATION.md §4). */
sealed interface SocketFrame {
    data class Op(val json: String) : SocketFrame
    data class Presence(val json: String) : SocketFrame
    data class ServerAck(val opId: String, val serverLamport: Long) : SocketFrame
    data class ServerHello(val serverLamport: Long, val peers: List<String>) : SocketFrame
    data class Error(val code: String, val message: String) : SocketFrame
}

/**
 * WebSocket client for a single project channel.
 *
 * Reconnection policy: exponential backoff with jitter (250ms..30s), infinite
 * while the project is open; on reconnect the engine replays the sync gap via
 * REST pull (socket is treated as a hint channel, REST as the source of truth
 * — the classic "realtime with REST reconciliation" pattern that survives
 * flaky mobile networks).
 */
class CollabSocket(
    private val client: OkHttpClient,
    private val scope: CoroutineScope,
) {
    private var socket: WebSocket? = null
    private var url: String? = null
    private var tokenProvider: (suspend () -> String?)? = null
    private var reconnectJob: Job? = null
    private var attempts = 0
    @Volatile private var intentionallyClosed = false

    private val _state = MutableStateFlow(SocketState.DISCONNECTED)
    val state: StateFlow<SocketState> = _state.asStateFlow()

    private val _frames = MutableSharedFlow<SocketFrame>(extraBufferCapacity = 256)
    val frames: SharedFlow<SocketFrame> = _frames.asSharedFlow()

    /** Frame sender hooks (envelopes framed as {"t":"op","d":<json>} etc.). */
    fun connect(channelUrl: String, projectId: String, token: suspend () -> String?) {
        intentionallyClosed = false
        url = channelUrl
        tokenProvider = token
        scope.launch { openSocket(projectId) }
    }

    private suspend fun openSocket(projectId: String) {
        val base = url ?: return
        val token = tokenProvider?.invoke()
        _state.value = if (attempts == 0) SocketState.CONNECTING else SocketState.RECONNECTING
        val request = Request.Builder()
            .url("$base?project_id=$projectId${token?.let { "&access_token=$it" } ?: ""}")
            .build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                attempts = 0
                _state.value = SocketState.CONNECTED
                Timber.d("collab socket open")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Minimal framing: {"t":"op|presence|ack|hello|error","d":...}
                val frame = parseFrame(text) ?: return
                _frames.tryEmit(frame)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                _state.value = SocketState.DISCONNECTED
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Timber.w(t, "collab socket failure")
                _state.value = SocketState.DISCONNECTED
                scheduleReconnect(projectId)
            }
        })
    }

    fun sendOp(opJson: String): Boolean =
        socket?.send("""{"t":"op","d":$opJson}""") ?: false

    fun sendPresence(presenceJson: String): Boolean =
        socket?.send("""{"t":"presence","d":$presenceJson}""") ?: false

    fun disconnect() {
        intentionallyClosed = true
        reconnectJob?.cancel()
        socket?.close(1000, "client closed")
        socket = null
        _state.value = SocketState.DISCONNECTED
    }

    private fun scheduleReconnect(projectId: String) {
        if (intentionallyClosed) return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            while (isActive && !intentionallyClosed) {
                attempts++
                val backoff = minOf(30_000L, 250L * (1L shl minOf(attempts, 7)))
                delay(backoff / 2 + (0..backoff / 2).random()) // jitter
                openSocket(projectId)
                delay(1_000)
                if (_state.value == SocketState.CONNECTED) break
            }
        }
    }

    private fun parseFrame(text: String): SocketFrame? {
        return try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject
            when (obj["t"]?.jsonPrimitive?.content) {
                "op" -> SocketFrame.Op(obj["d"].toString())
                "presence" -> SocketFrame.Presence(obj["d"].toString())
                "ack" -> SocketFrame.ServerAck(
                    obj["d"]?.jsonObject?.get("op_id")?.jsonPrimitive?.content ?: return null,
                    obj["d"]?.jsonObject?.get("lamport")?.jsonPrimitive?.long ?: 0L,
                )
                "hello" -> SocketFrame.ServerHello(
                    obj["d"]?.jsonObject?.get("lamport")?.jsonPrimitive?.long ?: 0L,
                    obj["d"]?.jsonObject?.get("peers")?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList(),
                )
                "error" -> SocketFrame.Error(
                    obj["d"]?.jsonObject?.get("code")?.jsonPrimitive?.content ?: "unknown",
                    obj["d"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "",
                )
                else -> null
            }
        } catch (t: Throwable) {
            Timber.w(t, "bad collab frame")
            null
        }
    }
}

private val kotlinx.serialization.json.JsonElement.jsonObject
    get() = this as kotlinx.serialization.json.JsonObject
private val kotlinx.serialization.json.JsonElement.jsonPrimitive
    get() = this as kotlinx.serialization.json.JsonPrimitive
private val kotlinx.serialization.json.JsonElement.jsonArray
    get() = this as kotlinx.serialization.json.JsonArray
