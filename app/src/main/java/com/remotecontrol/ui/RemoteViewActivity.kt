package com.remotecontrol.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.JsonObject
import com.remotecontrol.databinding.ActivityRemoteViewBinding
import com.remotecontrol.network.SignalingClient
import com.remotecontrol.network.WebRTCManager
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

/**
 * Full-screen activity for the controller to see and interact with
 * the remote device's screen via WebRTC video and touch forwarding.
 */
class RemoteViewActivity : AppCompatActivity(), WebRTCManager.WebRTCListener {

    companion object {
        private const val TAG = "RemoteViewActivity"
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_IS_CONTROLLER = "is_controller"
    }

    private lateinit var binding: ActivityRemoteViewBinding
    private var signalingClient: SignalingClient? = null
    private var webRTCManager: WebRTCManager? = null
    private var roomId: String? = null
    private var isActive = true

    // Touch gesture tracking
    private var touchDownX = 0f
    private var touchDownY = 0f
    private var touchDownTime = 0L

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityRemoteViewBinding.inflate(layoutInflater)
        setContentView(binding.root)

        roomId = intent.getStringExtra(EXTRA_ROOM_ID)
        if (roomId == null) {
            finish()
            return
        }

        signalingClient = RemoteControlHolder.signalingClient
        webRTCManager = RemoteControlHolder.webRTCManager

        if (signalingClient == null || webRTCManager == null) {
            finish()
            return
        }

        // Initialize video renderer
        try {
            val eglBase = webRTCManager?.getEglBase()
            if (eglBase != null) {
                binding.remoteVideoView.init(eglBase.eglBaseContext, null)
                binding.remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
                binding.remoteVideoView.setEnableHardwareScaler(true)
                binding.remoteVideoView.setMirror(false)
            } else {
                Log.e(TAG, "EglBase is null, cannot init video view")
                finish()
                return
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init video renderer", e)
            finish()
            return
        }

        // Start as controller
        webRTCManager?.startAsController(roomId!!, binding.remoteVideoView)

        setupTouchOverlay()
        setupToolbar()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchOverlay() {
        binding.touchOverlay.setOnTouchListener { view, event ->
            if (!isActive) return@setOnTouchListener true

            val viewWidth = view.width.toFloat()
            val viewHeight = view.height.toFloat()
            if (viewWidth == 0f || viewHeight == 0f) return@setOnTouchListener true

            val normX = (event.x / viewWidth).coerceIn(0f, 1f)
            val normY = (event.y / viewHeight).coerceIn(0f, 1f)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = normX
                    touchDownY = normY
                    touchDownTime = System.currentTimeMillis()
                    // Show toolbar briefly on touch
                    showToolbar()
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val elapsed = System.currentTimeMillis() - touchDownTime
                    val dx = normX - touchDownX
                    val dy = normY - touchDownY
                    val distance = Math.sqrt((dx * dx + dy * dy).toDouble())

                    if (distance < 0.02 && elapsed < 300) {
                        // Tap
                        sendInputEvent(JsonObject().apply {
                            addProperty("action", "tap")
                            addProperty("x", normX)
                            addProperty("y", normY)
                        })
                    } else if (distance < 0.02 && elapsed >= 500) {
                        // Long press
                        sendInputEvent(JsonObject().apply {
                            addProperty("action", "long_press")
                            addProperty("x", normX)
                            addProperty("y", normY)
                            addProperty("duration", elapsed)
                        })
                    } else {
                        // Swipe
                        sendInputEvent(JsonObject().apply {
                            addProperty("action", "swipe")
                            addProperty("x", touchDownX)
                            addProperty("y", touchDownY)
                            addProperty("x2", normX)
                            addProperty("y2", normY)
                            addProperty("duration", elapsed.coerceIn(100, 1000))
                        })
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun setupToolbar() {
        binding.btnBack.setOnClickListener {
            sendInputEvent(JsonObject().apply { addProperty("action", "back") })
        }
        binding.btnHome.setOnClickListener {
            sendInputEvent(JsonObject().apply { addProperty("action", "home") })
        }
        binding.btnKeyboard.setOnClickListener {
            showTextInputDialog()
        }
        binding.btnDisconnectSession.setOnClickListener {
            disconnect()
        }

        // Auto-hide toolbar after 3 seconds
        scheduleHideToolbar()
    }

    private fun showToolbar() {
        binding.toolbar.animate().alpha(0.9f).setDuration(150).start()
        scheduleHideToolbar()
    }

    private fun scheduleHideToolbar() {
        binding.toolbar.removeCallbacks(hideToolbarRunnable)
        binding.toolbar.postDelayed(hideToolbarRunnable, 3000)
    }

    private val hideToolbarRunnable = Runnable {
        if (!isFinishing) {
            binding.toolbar.animate().alpha(0.2f).setDuration(500).start()
        }
    }

    private fun showTextInputDialog() {
        val editText = EditText(this).apply {
            hint = "输入要发送的文字"
            setPadding(48, 32, 48, 32)
        }
        AlertDialog.Builder(this)
            .setTitle("远程输入文字")
            .setView(editText)
            .setPositiveButton("发送") { _, _ ->
                val text = editText.text.toString()
                if (text.isNotEmpty()) {
                    sendInputEvent(JsonObject().apply {
                        addProperty("action", "type_text")
                        addProperty("text", text)
                    })
                }
            }
            .setNegativeButton("取消", null)
            .show()
        editText.requestFocus()
    }

    private fun sendInputEvent(event: JsonObject) {
        try {
            roomId?.let { signalingClient?.sendInputEvent(it, event) }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send input event", e)
        }
    }

    private fun disconnect() {
        isActive = false
        roomId?.let { signalingClient?.disconnectRoom(it) }
        finish()
    }

    // ========== WebRTCListener ==========

    override fun onPeerConnected() {
        runOnUiThread {
            binding.tvConnectionInfo.text = "已连接"
            binding.tvConnectionInfo.visibility = View.VISIBLE
            binding.tvConnectionInfo.postDelayed({
                if (!isFinishing) {
                    binding.tvConnectionInfo.animate().alpha(0f).setDuration(1000).start()
                }
            }, 3000)
        }
    }

    override fun onPeerDisconnected() {
        runOnUiThread {
            binding.tvConnectionInfo.alpha = 1f
            binding.tvConnectionInfo.visibility = View.VISIBLE
            binding.tvConnectionInfo.text = "连接断开"
        }
    }

    override fun onRemoteVideoTrackReceived(track: VideoTrack) {
        runOnUiThread {
            binding.tvConnectionInfo.text = "正在接收画面"
        }
    }

    override fun onDestroy() {
        isActive = false
        binding.toolbar.removeCallbacks(hideToolbarRunnable)
        try {
            binding.remoteVideoView.release()
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing video view", e)
        }
        super.onDestroy()
    }
}

/**
 * Simple holder for sharing instances between activities.
 */
object RemoteControlHolder {
    var signalingClient: SignalingClient? = null
    var webRTCManager: WebRTCManager? = null
}
