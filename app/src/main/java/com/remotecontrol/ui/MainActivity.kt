package com.remotecontrol.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    private var currentRoomId: String? = null
    private var pendingRoomId: String? = null
    private var isControlled = false

    companion object {
        private const val REQUEST_MEDIA_PROJECTION = 1001
        private const val PREFS_NAME = "remote_control_prefs"
        private const val KEY_SERVER_URL = "server_url"
        private const val KEY_DEVICE_NAME = "device_name"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        signalingClient = SignalingClient(this)
        webRTCManager = WebRTCManager(this, signalingClient, this)
        webRTCManager.initialize()

        // Share with RemoteViewActivity
        RemoteControlHolder.signalingClient = signalingClient
        RemoteControlHolder.webRTCManager = webRTCManager

        loadPreferences()
        setupDefaultDeviceName()
        setupClickListeners()
    }

    override fun onResume() {
        super.onResume()
        updateAccessibilityStatus()
    }

    private fun loadPreferences() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.getString(KEY_SERVER_URL, null)?.let {
            binding.etServerUrl.setText(it)
        }
        prefs.getString(KEY_DEVICE_NAME, null)?.let {
            binding.etDeviceName.setText(it)
        }
    }

    private fun savePreferences() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            putString(KEY_SERVER_URL, binding.etServerUrl.text.toString())
            putString(KEY_DEVICE_NAME, binding.etDeviceName.text.toString())
            apply()
        }
    }

    private fun setupDefaultDeviceName() {
        if (binding.etDeviceName.text.isNullOrBlank()) {
            binding.etDeviceName.setText("${Build.MANUFACTURER} ${Build.MODEL}")
        }
    }

    private fun setupClickListeners() {
        binding.btnConnect.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            val name = binding.etDeviceName.text.toString().trim()
            if (url.isBlank()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            savePreferences()
            binding.tvStatus.text = getString(R.string.status_connecting)
            binding.btnConnect.isEnabled = false
            signalingClient.connect(url, name)
        }

        binding.btnRequestControl.setOnClickListener {
            val code = binding.etRemoteCode.text.toString().trim()
            if (code.length != 6) {
                Toast.makeText(this, "请输入6位连接码", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            binding.btnRequestControl.isEnabled = false
            signalingClient.requestControl(code)
        }

        binding.btnDisconnect.setOnClickListener {
            currentRoomId?.let { signalingClient.disconnectRoom(it) }
            signalingClient.disconnect()
            resetUI()
        }

        binding.btnEnableAccessibility.setOnClickListener {
            val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
            startActivity(intent)
        }
    }

    private fun updateAccessibilityStatus() {
        val running = InputInjectionService.isRunning()
        binding.tvAccessibilityStatus.text = if (running) {
            "已开启 ✓"
        } else {
            "未开启 - 被控端需要此服务来执行触摸操作"
        }
        binding.tvAccessibilityStatus.setTextColor(
            if (running) 0xFF4CAF50.toInt() else 0xFF666666.toInt()
        )
        binding.btnEnableAccessibility.visibility = if (running) View.GONE else View.VISIBLE
    }

    private fun resetUI() {
        currentRoomId = null
        isControlled = false
        binding.tvStatus.text = getString(R.string.status_disconnected)
        binding.btnConnect.isEnabled = true
        binding.cardMyCode.visibility = View.GONE
        binding.cardRemote.visibility = View.GONE
        binding.btnDisconnect.visibility = View.GONE
        binding.btnRequestControl.isEnabled = true
    }

    // ========== SignalingListener ==========

    override fun onConnected() {
        binding.tvStatus.text = getString(R.string.status_connected)
    }

    override fun onDisconnected() {
        resetUI()
        Toast.makeText(this, "已断开连接", Toast.LENGTH_SHORT).show()
    }

    override fun onRegistered(deviceId: String, connectionCode: String) {
        binding.tvMyCode.text = connectionCode
        binding.cardMyCode.visibility = View.VISIBLE
        binding.cardRemote.visibility = View.VISIBLE
        binding.btnDisconnect.visibility = View.VISIBLE
        binding.btnConnect.isEnabled = false
    }

    override fun onControlRequest(roomId: String, fromDeviceName: String) {
        pendingRoomId = roomId
        AlertDialog.Builder(this)
            .setTitle(R.string.dialog_control_request_title)
            .setMessage(getString(R.string.dialog_control_request_msg, fromDeviceName))
            .setPositiveButton(R.string.btn_accept) { _, _ ->
                signalingClient.respondToControl(true)
                // Start screen capture
                requestScreenCapture()
            }
            .setNegativeButton(R.string.btn_reject) { _, _ ->
                signalingClient.respondToControl(false)
                pendingRoomId = null
            }
            .setCancelable(false)
            .show()
    }

    override fun onControlAccepted(roomId: String) {
        currentRoomId = roomId
        isControlled = false
        binding.tvStatus.text = getString(R.string.status_in_session)
        Toast.makeText(this, "对方已接受，正在建立连接...", Toast.LENGTH_SHORT).show()

        // Open RemoteViewActivity as controller
        val intent = Intent(this, RemoteViewActivity::class.java).apply {
            putExtra(RemoteViewActivity.EXTRA_ROOM_ID, roomId)
            putExtra(RemoteViewActivity.EXTRA_IS_CONTROLLER, true)
        }
        startActivity(intent)
    }

    override fun onControlRejected() {
        binding.btnRequestControl.isEnabled = true
        Toast.makeText(this, "对方拒绝了控制请求", Toast.LENGTH_SHORT).show()
    }

    override fun onControlTimeout() {
        binding.btnRequestControl.isEnabled = true
        Toast.makeText(this, "请求超时，对方未响应", Toast.LENGTH_SHORT).show()
    }

    override fun onConnectPending() {
        Toast.makeText(this, "请求已发送，等待对方确认...", Toast.LENGTH_SHORT).show()
    }

    override fun onRoomJoined(roomId: String) {
        currentRoomId = roomId
        isControlled = true
        binding.tvStatus.text = "被控制中"
    }

    override fun onRoomClosed() {
        // Stop screen capture service
        val stopIntent = Intent(this, ScreenCaptureService::class.java).apply {
            action = ScreenCaptureService.ACTION_STOP
        }
        startService(stopIntent)
        webRTCManager.release()
        webRTCManager = WebRTCManager(this, signalingClient, this)
        webRTCManager.initialize()
        currentRoomId = null
        isControlled = false
        binding.tvStatus.text = getString(R.string.status_connected)
        Toast.makeText(this, "远程会话已结束", Toast.LENGTH_SHORT).show()
    }

    override fun onOffer(roomId: String, data: JsonObject) {
        // Controller receives offer from controlled device
        webRTCManager.handleOffer(roomId, data)
    }

    override fun onAnswer(roomId: String, data: JsonObject) {
        webRTCManager.handleAnswer(data)
    }

    override fun onIceCandidate(roomId: String, data: JsonObject) {
        webRTCManager.handleIceCandidate(data)
    }

    override fun onInputEvent(event: JsonObject) {
        // Controlled device receives input — map normalized coords to screen pixels
        val mapped = CoordinateMapper.mapToScreen(this, event)
        InputInjectionService.instance?.dispatchRemoteEvent(mapped)
    }

    override fun onError(message: String) {
        binding.btnConnect.isEnabled = true
        binding.btnRequestControl.isEnabled = true
        Toast.makeText(this, "错误: $message", Toast.LENGTH_LONG).show()
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

    override fun onRemoteVideoTrackReceived(track: VideoTrack) {
        // Handled in RemoteViewActivity
    }

    // ========== Screen Capture ==========

    private fun requestScreenCapture() {
        if (!InputInjectionService.isRunning()) {
            Toast.makeText(this, R.string.enable_accessibility, Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            return
        }
        val projectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_MEDIA_PROJECTION)
    }

    @Deprecated("Use Activity Result API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_MEDIA_PROJECTION && resultCode == Activity.RESULT_OK && data != null) {
            // Start foreground service
            val serviceIntent = Intent(this, ScreenCaptureService::class.java).apply {
                action = ScreenCaptureService.ACTION_START
                putExtra(ScreenCaptureService.EXTRA_PROJECTION_DATA, data)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            // Start WebRTC as controlled device
            pendingRoomId?.let { roomId ->
                currentRoomId = roomId
                webRTCManager.startAsControlled(roomId, data)
            }
        } else {
            // User denied screen capture
            signalingClient.respondToControl(false)
            pendingRoomId = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        signalingClient.disconnect()
        webRTCManager.release()
    }

    /** Expose signalingClient and webRTCManager for RemoteViewActivity */
    fun getSignaling(): SignalingClient = signalingClient
    fun getWebRTC(): WebRTCManager = webRTCManager
}
