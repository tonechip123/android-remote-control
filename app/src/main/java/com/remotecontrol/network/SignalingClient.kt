package com.remotecontrol.network

import android.os.Handler
import android.os.Looper
import com.google.gson.Gson
import com.google.gson.JsonObject
import okhttp3.*
import java.util.concurrent.TimeUnit

class SignalingClient(
    private val listener: SignalingListener
) {
    private var webSocket: WebSocket? = null
    private val client = OkHttpClient.Builder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    var deviceId: String? = null
        private set
    var isConnected = false
        private set

    fun connect(serverUrl: String, deviceName: String) {
        val request = Request.Builder().url(serverUrl).build()
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                val msg = JsonObject().apply {
                    addProperty("type", "register")
                    addProperty("deviceName", deviceName)
                }
                webSocket.send(gson.toJson(msg))
                mainHandler.post { listener.onConnected() }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                handleMessage(text)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                mainHandler.post { listener.onDisconnected() }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                mainHandler.post { listener.onError("连接失败: ${t.message}") }
            }
        })
    }

    private fun handleMessage(text: String) {
        val msg = gson.fromJson(text, JsonObject::class.java)
        val type = msg.get("type")?.asString ?: return

        mainHandler.post {
            when (type) {
                "registered" -> {
                    deviceId = msg.get("deviceId")?.asString
                    listener.onRegistered(deviceId!!)
                }
                "device_list" -> {
                    val devices = msg.getAsJsonArray("devices")
                    listener.onDeviceListUpdated(devices)
                }
                "control_accepted" -> {
                    val roomId = msg.get("roomId")?.asString ?: return@post
                    listener.onControlAccepted(roomId)
                }
                "room_joined" -> {
                    val roomId = msg.get("roomId")?.asString ?: return@post
                    listener.onRoomJoined(roomId)
                }
                "room_closed" -> {
                    listener.onRoomClosed()
                }
                "offer" -> {
                    val roomId = msg.get("roomId")?.asString ?: return@post
                    val data = msg.getAsJsonObject("data")
                    listener.onOffer(roomId, data)
                }
                "answer" -> {
                    val roomId = msg.get("roomId")?.asString ?: return@post
                    val data = msg.getAsJsonObject("data")
                    listener.onAnswer(roomId, data)
                }
                "ice_candidate" -> {
                    val roomId = msg.get("roomId")?.asString ?: return@post
                    val data = msg.getAsJsonObject("data")
                    listener.onIceCandidate(roomId, data)
                }
                "input_event" -> {
                    val event = msg.getAsJsonObject("event")
                    listener.onInputEvent(event)
                }
                "error" -> {
                    val message = msg.get("message")?.asString ?: "Unknown error"
                    listener.onError(message)
                }
            }
        }
    }

    fun connectTo(targetDeviceId: String) {
        send(JsonObject().apply {
            addProperty("type", "connect_to")
            addProperty("targetDeviceId", targetDeviceId)
        })
    }

    fun sendOffer(roomId: String, sdp: JsonObject) {
        send(JsonObject().apply {
            addProperty("type", "offer")
            addProperty("roomId", roomId)
            add("data", sdp)
        })
    }

    fun sendAnswer(roomId: String, sdp: JsonObject) {
        send(JsonObject().apply {
            addProperty("type", "answer")
            addProperty("roomId", roomId)
            add("data", sdp)
        })
    }

    fun sendIceCandidate(roomId: String, candidate: JsonObject) {
        send(JsonObject().apply {
            addProperty("type", "ice_candidate")
            addProperty("roomId", roomId)
            add("data", candidate)
        })
    }

    fun sendInputEvent(roomId: String, event: JsonObject) {
        send(JsonObject().apply {
            addProperty("type", "input_event")
            addProperty("roomId", roomId)
            add("event", event)
        })
    }

    fun disconnectRoom(roomId: String) {
        send(JsonObject().apply {
            addProperty("type", "disconnect_room")
            addProperty("roomId", roomId)
        })
    }

    fun disconnect() {
        webSocket?.close(1000, "User disconnect")
        webSocket = null
        isConnected = false
    }

    private fun send(msg: JsonObject) {
        webSocket?.send(gson.toJson(msg))
    }
}
