package com.remotecontrol.network

import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjection
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import com.google.gson.JsonObject
import org.webrtc.*

/**
 * Manages WebRTC peer connections for screen sharing.
 *
 * Controller mode: receives remote video stream
 * Controlled mode: captures screen and sends video stream
 */
class WebRTCManager(
    private val context: Context,
    private val signalingClient: SignalingClient,
    private val listener: WebRTCListener
) {
    companion object {
        private const val TAG = "WebRTCManager"
        private val ICE_SERVERS = listOf(
            // Free STUN servers for NAT traversal
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun1.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.stunprotocol.org:3478").createIceServer(),
            // Free TURN server (for testing; replace with your own in production)
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:80")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
            PeerConnection.IceServer.builder("turn:openrelay.metered.ca:443")
                .setUsername("openrelayproject")
                .setPassword("openrelayproject")
                .createIceServer(),
        )
    }

    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var videoCapturer: VideoCapturer? = null
    private var localVideoSource: VideoSource? = null
    private var localVideoTrack: VideoTrack? = null
    private var eglBase: EglBase? = null
    private var roomId: String? = null
    private var isController = false

    fun initialize() {
        eglBase = EglBase.create()

        val options = PeerConnectionFactory.InitializationOptions.builder(context)
            .setEnableInternalTracer(false)
            .createInitializationOptions()
        PeerConnectionFactory.initialize(options)

        val encoderFactory = DefaultVideoEncoderFactory(
            eglBase!!.eglBaseContext, true, true
        )
        val decoderFactory = DefaultVideoDecoderFactory(eglBase!!.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()
    }

    fun getEglBase(): EglBase? = eglBase

    /**
     * Start as controlled device: capture screen and stream via WebRTC
     */
    fun startAsControlled(roomId: String, projectionData: Intent) {
        this.roomId = roomId
        this.isController = false

        createPeerConnection()

        // Get screen dimensions
        val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi = metrics.densityDpi

        // Create screen capturer
        val screenCapturer = ScreenCapturerAndroid(projectionData, object : MediaProjection.Callback() {
            override fun onStop() {
                Log.d(TAG, "MediaProjection stopped")
            }
        })
        videoCapturer = screenCapturer

        // Create video source and track
        val surfaceTextureHelper = SurfaceTextureHelper.create("CaptureThread", eglBase!!.eglBaseContext)
        localVideoSource = peerConnectionFactory!!.createVideoSource(screenCapturer.isScreencast)
        screenCapturer.initialize(surfaceTextureHelper, context, localVideoSource!!.capturerObserver)

        // Start capturing - scale down for bandwidth
        val captureWidth = (width * 0.75).toInt()
        val captureHeight = (height * 0.75).toInt()
        screenCapturer.startCapture(captureWidth, captureHeight, 30)

        localVideoTrack = peerConnectionFactory!!.createVideoTrack("screen_track", localVideoSource)
        localVideoTrack?.setEnabled(true)

        // Add track to peer connection
        val streamIds = listOf("screen_stream")
        peerConnection?.addTrack(localVideoTrack, streamIds)

        // Create offer (controlled device initiates the stream)
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "false"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection?.createOffer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                val sdpJson = JsonObject().apply {
                    addProperty("type", sdp.type.canonicalForm())
                    addProperty("sdp", sdp.description)
                }
                signalingClient.sendOffer(roomId, sdpJson)
            }
        }, constraints)
    }

    /**
     * Start as controller: receive remote video stream
     */
    fun startAsController(roomId: String, remoteVideoSink: VideoSink) {
        this.roomId = roomId
        this.isController = true

        createPeerConnection(remoteVideoSink)
    }

    fun handleOffer(roomId: String, data: JsonObject) {
        val sdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(data.get("type").asString),
            data.get("sdp").asString
        )
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)

        // Create answer
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "false"))
        }
        peerConnection?.createAnswer(object : SdpObserverAdapter() {
            override fun onCreateSuccess(sdp: SessionDescription) {
                peerConnection?.setLocalDescription(SdpObserverAdapter(), sdp)
                val sdpJson = JsonObject().apply {
                    addProperty("type", sdp.type.canonicalForm())
                    addProperty("sdp", sdp.description)
                }
                signalingClient.sendAnswer(roomId, sdpJson)
            }
        }, constraints)
    }

    fun handleAnswer(data: JsonObject) {
        val sdp = SessionDescription(
            SessionDescription.Type.fromCanonicalForm(data.get("type").asString),
            data.get("sdp").asString
        )
        peerConnection?.setRemoteDescription(SdpObserverAdapter(), sdp)
    }

    fun handleIceCandidate(data: JsonObject) {
        val candidate = IceCandidate(
            data.get("sdpMid").asString,
            data.get("sdpMLineIndex").asInt,
            data.get("candidate").asString
        )
        peerConnection?.addIceCandidate(candidate)
    }

    private fun createPeerConnection(remoteVideoSink: VideoSink? = null) {
        val rtcConfig = PeerConnection.RTCConfiguration(ICE_SERVERS).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(
            rtcConfig,
            object : PeerConnection.Observer {
                override fun onIceCandidate(candidate: IceCandidate) {
                    val candidateJson = JsonObject().apply {
                        addProperty("sdpMid", candidate.sdpMid)
                        addProperty("sdpMLineIndex", candidate.sdpMLineIndex)
                        addProperty("candidate", candidate.sdp)
                    }
                    roomId?.let { signalingClient.sendIceCandidate(it, candidateJson) }
                }

                override fun onTrack(transceiver: RtpTransceiver) {
                    val track = transceiver.receiver.track()
                    if (track is VideoTrack && remoteVideoSink != null) {
                        track.addSink(remoteVideoSink)
                        listener.onRemoteVideoTrackReceived(track)
                    }
                }

                override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                    Log.d(TAG, "ICE connection state: $state")
                    when (state) {
                        PeerConnection.IceConnectionState.CONNECTED -> listener.onPeerConnected()
                        PeerConnection.IceConnectionState.DISCONNECTED,
                        PeerConnection.IceConnectionState.FAILED -> listener.onPeerDisconnected()
                        else -> {}
                    }
                }

                override fun onSignalingChange(state: PeerConnection.SignalingState) {}
                override fun onIceConnectionReceivingChange(receiving: Boolean) {}
                override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {}
                override fun onIceCandidatesRemoved(candidates: Array<IceCandidate>) {}
                override fun onAddStream(stream: MediaStream) {}
                override fun onRemoveStream(stream: MediaStream) {}
                override fun onDataChannel(channel: DataChannel) {}
                override fun onRenegotiationNeeded() {}
            }
        )
    }

    fun release() {
        videoCapturer?.stopCapture()
        videoCapturer?.dispose()
        localVideoTrack?.dispose()
        localVideoSource?.dispose()
        peerConnection?.close()
        peerConnection?.dispose()
        peerConnectionFactory?.dispose()
        eglBase?.release()

        videoCapturer = null
        localVideoTrack = null
        localVideoSource = null
        peerConnection = null
        peerConnectionFactory = null
        eglBase = null
    }

    interface WebRTCListener {
        fun onPeerConnected()
        fun onPeerDisconnected()
        fun onRemoteVideoTrackReceived(track: VideoTrack)
    }
}

/** Convenience adapter so callers only override what they need */
open class SdpObserverAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String) {
        Log.e("SdpObserver", "Create failed: $error")
    }
    override fun onSetFailure(error: String) {
        Log.e("SdpObserver", "Set failed: $error")
    }
}
