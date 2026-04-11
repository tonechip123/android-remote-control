package com.remotecontrol.network

import com.google.gson.JsonObject

interface SignalingListener {
    fun onConnected()
    fun onDisconnected()
    fun onRegistered(deviceId: String, connectionCode: String)
    fun onControlRequest(roomId: String, fromDeviceName: String)
    fun onControlAccepted(roomId: String)
    fun onControlRejected()
    fun onControlTimeout()
    fun onConnectPending()
    fun onRoomJoined(roomId: String)
    fun onRoomClosed()
    fun onOffer(roomId: String, data: JsonObject)
    fun onAnswer(roomId: String, data: JsonObject)
    fun onIceCandidate(roomId: String, data: JsonObject)
    fun onInputEvent(event: JsonObject)
    fun onError(message: String)
}
