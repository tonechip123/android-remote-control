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
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.remotecontrol.R
import com.remotecontrol.databinding.ActivityMainBinding
import com.remotecontrol.network.SignalingClient
import com.remotecontrol.network.SignalingListener
import com.remotecontrol.network.WebRTCManager
import com.remotecontrol.service.InputInjectionService
import com.remotecontrol.service.ScreenCaptureService
import com.remotecontrol.util.CoordinateMapper
import org.webrtc.VideoTrack

class MainActivity : AppCompatActivity(), SignalingListener, WebRTCManager.WebRTCListener {

    private lateinit var binding: ActivityMainBinding
    private lateinit var signalingClient: SignalingClient
    private lateinit var webRTCManager: WebRTCManager
    private lateinit var deviceAdapter: DeviceAdapter

    private var currentRoomId: String? = null
    private var pendingRoomId: String? = null

    companion object {
        private const val SERVER_URL = "ws://8.147.70.248:8080"
        private const val REQUEST_MEDIA_PROJECTION = 1001
    }

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

        // Auto-connect on launch
        binding.tvStatus.text = getString(R.string.status_connecting)
        signalingClient.connect(SERVER_URL, "${Build.MANUFACTURER} ${Build.MODEL}")
    }

    override fun onResume() {
        super.onResume()
        checkSetupNeeded()
    }

    private fun setupDeviceList() {
        deviceAdapter = DeviceAdapter { device ->
            // Click to control
            Toast.makeText(this, "正在连接 ${device.deviceName}...", Toast.LENGTH_SHORT).show()
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

    // ========== SignalingListener ==========

    override fun onConnected() {
        binding.tvStatus.text = getString(R.string.status_connected)
    }

    override fun onDisconnected() {
        binding.tvStatus.text = getString(R.string.status_disconnected)
        deviceAdapter.updateDevices(emptyList())
        binding.tvEmpty.visibility = View.VISIBLE
        binding.rvDevices.visibility = View.GONE
        // Auto reconnect after 3s
        binding.root.postDelayed({
            if (!signalingClient.isConnected) {
                binding.tvStatus.text = getString(R.string.status_connecting)
                signalingClient.connect(SERVER_URL, "${Build.MANUFACTURER} ${Build.MODEL}")
            }
        }, 3000)
    }

    override fun onRegistered(deviceId: String) {
        binding.tvStatus.text = "已连接 (${Build.MODEL})"
    }

    override fun onDeviceListUpdated(devices: JsonArray) {
        val myId = signalingClient.deviceId
        val list = mutableListOf<DeviceInfo>()
        for (element in devices) {
            val obj = element.asJsonObject
            val id = obj.get("deviceId").asString
            if (id != myId) {
                list.add(DeviceInfo(id, obj.get("deviceName").asString))
            }
        }
        deviceAdapter.updateDevices(list)
        if (list.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.rvDevices.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.rvDevices.visibility = View.VISIBLE
        }
    }

    override fun onControlAccepted(roomId: String) {
        currentRoomId = roomId
        binding.tvStatus.text = "远程控制中"
        val intent = Intent(this, RemoteViewActivity::class.java).apply {
            putExtra(RemoteViewActivity.EXTRA_ROOM_ID, roomId)
            putExtra(RemoteViewActivity.EXTRA_IS_CONTROLLER, true)
        }
        startActivity(intent)
    }

    override fun onRoomJoined(roomId: String) {
        // This device is being controlled — auto start screen capture
        currentRoomId = roomId
        pendingRoomId = roomId
        binding.tvStatus.text = "被控制中"
        requestScreenCapture()
    }

    override fun onRoomClosed() {
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(stopIntent)
        webRTCManager.release()
        webRTCManager = WebRTCManager(this, signalingClient, this)
        webRTCManager.initialize()
        RemoteControlHolder.webRTCManager = webRTCManager
        currentRoomId = null
        binding.tvStatus.text = getString(R.string.status_connected)
        Toast.makeText(this, "远程会话已结束", Toast.LENGTH_SHORT).show()
    }

    override fun onOffer(roomId: String, data: JsonObject) {
        webRTCManager.handleOffer(roomId, data)
    }

    override fun onAnswer(roomId: String, data: JsonObject) {
        webRTCManager.handleAnswer(data)
    }

    override fun onIceCandidate(roomId: String, data: JsonObject) {
        webRTCManager.handleIceCandidate(data)
    }

    override fun onInputEvent(event: JsonObject) {
        val mapped = CoordinateMapper.mapToScreen(this, event)
        InputInjectionService.instance?.dispatchRemoteEvent(mapped)
    }

    override fun onError(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    // ========== WebRTCListener ==========

    override fun onPeerConnected() {
        runOnUiThread {
            Toast.makeText(this, "P2P连接已建立", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onPeerDisconnected() {
        runOnUiThread {
            Toast.makeText(this, "P2P连接断开", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRemoteVideoTrackReceived(track: VideoTrack) {}

    // ========== Screen Capture ==========

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_PROJECTION_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            pendingRoomId?.let { roomId ->
                webRTCManager.startAsControlled(roomId, data)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingClient.disconnect()
        webRTCManager.release()
    }
}
