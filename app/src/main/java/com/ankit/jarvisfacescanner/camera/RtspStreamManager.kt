package com.ankit.jarvisfacescanner.camera

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import java.util.concurrent.atomic.AtomicBoolean

@UnstableApi
class RtspStreamManager(
    private val context: Context,
    private val textureView: TextureView,
    private val onFrameSampled: (Bitmap) -> Unit,
    private val onStatusChanged: (String) -> Unit
) {
    private var exoPlayer: ExoPlayer? = null
    private val handler = Handler(Looper.getMainLooper())
    private val isRunning = AtomicBoolean(false)
    private var sampleIntervalMs = 250L

    private val frameSamplerRunnable = object : Runnable {
        override fun run() {
            if (isRunning.get() && textureView.isAvailable) {
                val bitmap = textureView.getBitmap(480, 270)
                if (bitmap != null) {
                    onFrameSampled(bitmap)
                }
            }
            if (isRunning.get()) {
                handler.postDelayed(this, sampleIntervalMs)
            }
        }
    }

    fun startStream(rtspUri: String) {
        stopStream()
        onStatusChanged("CONNECTING TO TAPO...")

        exoPlayer = ExoPlayer.Builder(context).build().apply {
            setVideoTextureView(textureView)
            val mediaItem = MediaItem.fromUri(rtspUri)
            val rtspMediaSource = RtspMediaSource.Factory()
                .setForceUseRtpTcp(true)
                .createMediaSource(mediaItem)

            setMediaSource(rtspMediaSource)
            prepare()
            playWhenReady = true

            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    when (state) {
                        Player.STATE_BUFFERING -> onStatusChanged("TAPO BUFFERING...")
                        Player.STATE_READY -> onStatusChanged("TAPO C200 LIVE")
                        Player.STATE_ENDED -> onStatusChanged("TAPO STREAM ENDED")
                        Player.STATE_IDLE -> onStatusChanged("TAPO IDLE")
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    onStatusChanged("RTSP ERROR: ${error.errorCodeName}")
                    handler.postDelayed({
                        if (isRunning.get()) startStream(rtspUri)
                    }, 3000)
                }
            })
        }

        isRunning.set(true)
        handler.post(frameSamplerRunnable)
    }

    fun stopStream() {
        isRunning.set(false)
        handler.removeCallbacks(frameSamplerRunnable)
        exoPlayer?.stop()
        exoPlayer?.release()
        exoPlayer = null
        onStatusChanged("TAPO OFFLINE")
    }
}
