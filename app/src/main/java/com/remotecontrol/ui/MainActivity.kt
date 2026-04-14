package com.remotecontrol.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.remotecontrol.App
import com.remotecontrol.R
import com.remotecontrol.databinding.ActivityMainBinding
import com.remotecontrol.network.SignalingClient
import com.remotecontrol.network.SignalingListener
import com.remotecontrol.network.WebRTCManager
import com.remotecontrol.service.ConnectionService
import com.remotecontrol.service.InputInjectionService
import com.remotecontrol.service.ScreenCaptureService
import com.remotecontrol.util.CoordinateMapper
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity(), SignalingListener, WebRTCManager.WebRTCListener {

    companion object {
        private const val TAG = "MainActivity"
        private const val SERVER_URL = "ws://8.147.70.248:8080"
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRTCManager: WebRTCManager
    private lateinit var deviceAdapter: DeviceAdapter

    private var currentRoomId: String? = null
    private var savedProjectionData: Intent? = null
    private var isDestroyed_ = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        signalingClient = SignalingClient(this)
        webRTCManager = WebRTCManager(this, signalingClient, this)
        webRTCManager.initialize()

        RemoteControlHolder.signalingClient = signalingClient
        RemoteControlHolder.webRTCManager = webRTCManager

        setupDeviceList()
        setupSetupGuide()

        // Start keep-alive foreground service
        try {
            val connIntent = Intent(this, ConnectionService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(connIntent)
            } else {
                startService(connIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ConnectionService", e)
        }

        // Auto-connect
        updateStatus(getString(R.string.status_connecting), false)
        signalingClient.connect(SERVER_URL, "${Build.MANUFACTURER} ${Build.MODEL}")

        // Don't request screen capture on launch
        // Only request when actually being controlled (onRoomJoined)
        // This avoids crash on Android 16 (Honor 400 Pro)
    }

    override fun onResume() {
        super.onResume()
        checkSetupNeeded()
    }

    private fun setupDeviceList() {
        deviceAdapter = DeviceAdapter { device ->
            Toast.makeText(this, "正在连接 ${device.deviceName}…", Toast.LENGTH_SHORT).show()
            signalingClient.connectTo(device.deviceId)
        }
        binding.rvDevices.layoutManager = LinearLayoutManager(this)
        binding.rvDevices.adapter = deviceAdapter
    }

    private fun setupSetupGuide() {
        binding.btnSetupAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        binding.btnSetupBattery.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        }
    }

    private fun checkSetupNeeded() {
        val accessibilityOk = InputInjectionService.isRunning()
        val batteryOk = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true

        if (!accessibilityOk || !batteryOk) {
            binding.cardSetup.visibility = View.VISIBLE
            binding.btnSetupAccessibility.text = if (accessibilityOk)
                "1. 无障碍服务 已开启 ✓" else "1. 开启无障碍服务（被控端必须）"
            binding.btnSetupAccessibility.isEnabled = !accessibilityOk
            binding.btnSetupBattery.text = if (batteryOk)
                "2. 电池优化 已关闭 ✓" else "2. 关闭电池优化（保持后台运行）"
            binding.btnSetupBattery.isEnabled = !batteryOk
        } else {
            binding.cardSetup.visibility = View.GONE
        }
    }

    private fun updateStatus(text: String, connected: Boolean) {
        binding.tvStatus.text = text
        binding.tvStatus.setBackgroundResource(
            if (connected) R.drawable.bg_status_connected else R.drawable.bg_status_pill
        )
        binding.tvStatus.setTextColor(
            if (connected) 0xFF2E7D32.toInt() else 0xFF999999.toInt()
        )
    }

    private fun updateDeviceCount(count: Int) {
        binding.tvDeviceCount.text = getString(R.string.online_devices, count)
    }

    // ========== SignalingListener ==========

    override fun onConnected() {
        updateStatus(getString(R.string.status_connected), true)
        updateServiceNotification("待机中，等待连接...")
    }

    override fun onDisconnected() {
        if (isDestroyed_) return
        updateStatus(getString(R.string.status_disconnected), false)
        deviceAdapter.updateDevices(emptyList())
        updateDeviceCount(0)
        binding.layoutEmpty.visibility = View.VISIBLE
        binding.rvDevices.visibility = View.GONE
    }

    override fun onRegistered(deviceId: String) {
        updateStatus("已连接", true)
    }

    override fun onDeviceListUpdated(devices: JsonArray) {
        val myId = signalingClient.deviceId
        val list = mutableListOf<DeviceInfo>()
        for (element in devices) {
            val obj = element.asJsonObject
            val id = obj.get("deviceId")?.asString ?: continue
            if (id != myId) {
                val name = obj.get("deviceName")?.asString ?: "未知设备"
                list.add(DeviceInfo(id, name))
            }
        }
        deviceAdapter.updateDevices(list)
        updateDeviceCount(list.size)
        if (list.isEmpty()) {
            binding.layoutEmpty.visibility = View.VISIBLE
            binding.rvDevices.visibility = View.GONE
        } else {
            binding.layoutEmpty.visibility = View.GONE
            binding.rvDevices.visibility = View.VISIBLE
        }
    }

    override fun onControlAccepted(roomId: String) {
        currentRoomId = roomId
        updateStatus(getString(R.string.status_controlling), true)
        updateServiceNotification("远程控制中...")
        val intent = Intent(this, RemoteViewActivity::class.java).apply {
            putExtra(RemoteViewActivity.EXTRA_ROOM_ID, roomId)
            putExtra(RemoteViewActivity.EXTRA_IS_CONTROLLER, true)
        }
        startActivity(intent)
    }

    override fun onRoomJoined(roomId: String) {
        currentRoomId = roomId
        updateStatus(getString(R.string.status_controlled), true)
        updateServiceNotification("被控制中，屏幕共享中...")

        val projData = savedProjectionData
        if (projData != null) {
            try {
                // Start screen capture foreground service NOW (with valid token)
                val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                    action = ScreenCaptureService.ACTION_START
                    putExtra(ScreenCaptureService.EXTRA_PROJECTION_DATA, projData)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(serviceIntent)
                } else {
                    startService(serviceIntent)
                }
                // Small delay to let service start, then begin WebRTC
                binding.root.postDelayed({
                    try {
                        webRTCManager.startAsControlled(roomId, projData)
                    } catch (e: Exception) {
                        Log.e(TAG, "WebRTC start failed", e)
                    }
                }, 300)
            } catch (e: Exception) {
                Log.e(TAG, "Screen capture failed", e)
                Toast.makeText(this, "屏幕采集失败: ${e.message}", Toast.LENGTH_LONG).show()
            }
        } else {
            // No token yet — need to request (first time only)
            Toast.makeText(this, "请先授权屏幕录制", Toast.LENGTH_LONG).show()
            requestScreenCapture()
        }
    }

    override fun onRoomClosed() {
        try {
            webRTCManager.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing WebRTC", e)
        }
        webRTCManager = WebRTCManager(this, signalingClient, this)
        webRTCManager.initialize()
        RemoteControlHolder.webRTCManager = webRTCManager
        currentRoomId = null
        updateStatus(getString(R.string.status_connected), true)
        Toast.makeText(this, "远程会话已结束", Toast.LENGTH_SHORT).show()
        updateServiceNotification("待机中，等待连接...")
    }

    private fun updateServiceNotification(text: String) {
        try {
            val notification = NotificationCompat.Builder(this, App.CHANNEL_ID)
                .setContentTitle("LinkView")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
            manager.notify(ConnectionService.NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update notification", e)
        }
    }

    override fun onOffer(roomId: String, data: JsonObject) {
        try {
            webRTCManager.handleOffer(roomId, data)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling offer", e)
        }
    }

    override fun onAnswer(roomId: String, data: JsonObject) {
        try {
            webRTCManager.handleAnswer(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling answer", e)
        }
    }

    override fun onIceCandidate(roomId: String, data: JsonObject) {
        try {
            webRTCManager.handleIceCandidate(data)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling ICE candidate", e)
        }
    }

    override fun onInputEvent(event: JsonObject) {
        try {
            val mapped = CoordinateMapper.mapToScreen(this, event)
            InputInjectionService.instance?.dispatchRemoteEvent(mapped)
        } catch (e: Exception) {
            Log.e(TAG, "Error dispatching input", e)
        }
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ========== WebRTCListener ==========

    override fun onPeerConnected() {
        runOnUiThread { Toast.makeText(this, "P2P连接已建立", Toast.LENGTH_SHORT).show() }
    }

    override fun onPeerDisconnected() {
        runOnUiThread { Toast.makeText(this, "P2P连接断开", Toast.LENGTH_SHORT).show() }
    }

    override fun onRemoteVideoTrackReceived(track: VideoTrack) {}

    // ========== Screen Capture ==========

    private fun requestScreenCapture() {
        try {
            val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request screen capture", e)
        }
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            // Just save the token, don't start service yet
            // Service will be started when actually being controlled (onRoomJoined)
            savedProjectionData = data
            Toast.makeText(this, "屏幕录制已授权，待机中", Toast.LENGTH_SHORT).show()
            checkSetupNeeded()
        }
    }

    override fun onDestroy() {
        isDestroyed_ = true
        // Don't disconnect — ConnectionService keeps it alive in background
        // Only disconnect if user explicitly stops the service
        super.onDestroy()
    }
}
