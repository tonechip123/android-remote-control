package com.remotecontrol.ui

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import com.google.gson.JsonObject
import com.remotecontrol.databinding.ActivityRemoteViewBinding
import com.remotecontrol.network.SignalingClient
import com.remotecontrol.network.SignalingListener
import com.remotecontrol.network.WebRTCManager
import org.webrtc.RendererCommon
import org.webrtc.VideoTrack

/**
 * Full-screen activity for the controller to see and interact with
 * the remote device's screen via WebRTC video and touch forwarding.
 */
class RemoteViewActivity : AppCompatActivity(), WebRTCManager.WebRTCListener {

    companion object {
        const val EXTRA_ROOM_ID = "room_id"
        const val EXTRA_IS_CONTROLLER = "is_controller"
    }

    private lateinit var binding: ActivityRemoteViewBinding
    private var signalingClient: SignalingClient? = null
    private var webRTCManager: WebRTCManager? = null
    private var roomId: String? = null

    // Track swipe gesture
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

        // Get references from singleton/application scope
        // In production, use a shared ViewModel or DI framework
        signalingClient = RemoteControlHolder.signalingClient
        webRTCManager = RemoteControlHolder.webRTCManager

        if (signalingClient == null || webRTCManager == null) {
            finish()
            return
        }

        // Initialize video renderer
        val eglBase = webRTCManager!!.getEglBase()
        binding.remoteVideoView.init(eglBase?.eglBaseContext, null)
        binding.remoteVideoView.setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        binding.remoteVideoView.setEnableHardwareScaler(true)

        // Start as controller
        webRTCManager!!.startAsController(roomId!!, binding.remoteVideoView)

        setupTouchOverlay()
        setupToolbar()
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchOverlay() {
        binding.touchOverlay.setOnTouchListener { view, event ->
            val viewWidth = view.width.toFloat()
            val viewHeight = view.height.toFloat()

            // Normalize coordinates to 0..1 range, then scale to remote screen
            val normX = event.x / viewWidth
            val normY = event.y / viewHeight

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    touchDownX = normX
                    touchDownY = normY
                    touchDownTime = System.currentTimeMillis()
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
            sendInputEvent(JsonObject().apply {
                addProperty("action", "back")
            })
        }
        binding.btnHome.setOnClickListener {
            sendInputEvent(JsonObject().apply {
                addProperty("action", "home")
            })
        }
        binding.btnDisconnectSession.setOnClickListener {
            roomId?.let { signalingClient?.disconnectRoom(it) }
            finish()
        }

        // Auto-hide toolbar
        binding.toolbar.postDelayed({
            binding.toolbar.animate().alpha(0.3f).duration = 500
        }, 3000)
        binding.touchOverlay.setOnClickListener {
            binding.toolbar.animate().alpha(0.8f).duration = 200
            binding.toolbar.postDelayed({
                binding.toolbar.animate().alpha(0.3f).duration = 500
            }, 3000)
        }
    }

    private fun sendInputEvent(event: JsonObject) {
        roomId?.let { signalingClient?.sendInputEvent(it, event) }
    }

    // ========== WebRTCListener ==========

    override fun onPeerConnected() {
        runOnUiThread {
            binding.tvConnectionInfo.text = "已连接"
        }
    }

    override fun onPeerDisconnected() {
        runOnUiThread {
            binding.tvConnectionInfo.text = "连接断开"
        }
    }

    override fun onRemoteVideoTrackReceived(track: VideoTrack) {
        runOnUiThread {
            binding.tvConnectionInfo.text = "正在接收画面"
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        binding.remoteVideoView.release()
    }
}

/**
 * Simple holder for sharing instances between activities.
 * In production, use Hilt/Koin DI or a shared ViewModel.
 */
object RemoteControlHolder {
    var signalingClient: SignalingClient? = null
    var webRTCManager: WebRTCManager? = null
}
