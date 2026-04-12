package com.remotecontrol.network

import com.google.gson.JsonArray
import com.google.gson.JsonObject

interface SignalingListener {
    fun onConnected()
    fun onDisconnected()
    fun onRegistered(deviceId: String)
    fun onDeviceListUpdated(devices: JsonArray)
    fun onControlAccepted(roomId: String)
    fun onRoomJoined(roomId: String)
    fun onRoomClosed()
    fun onOffer(roomId: String, data: JsonObject)
    fun onAnswer(roomId: String, data: JsonObject)
    fun onIceCandidate(roomId: String, data: JsonObject)
    fun onInputEvent(event: JsonObject)
    fun onError(message: String)
}
