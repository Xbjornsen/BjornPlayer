package com.bjorntech.player

import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var detachStateStore: (() -> Unit)? = null

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus= */ true)
            .setHandleAudioBecomingNoisy(true) // pause on headphone unplug
            .build()
        // Tapped song plays first; queued songs play next (see QueueShuffleOrder).
        player.setShuffleOrder(QueueShuffleOrder())

        mediaSession = MediaSession.Builder(this, player).build()
        SleepTimer.attach(player)
        detachStateStore = PlaybackStateStore.attach(this, player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    override fun onDestroy() {
        detachStateStore?.invoke()   // final save of position before the player goes away
        detachStateStore = null
        mediaSession?.run {
            SleepTimer.detach(player)
            player.release()
            release()
            mediaSession = null
        }
        super.onDestroy()
    }
}
