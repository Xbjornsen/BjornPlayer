package com.bjorntech.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.media3.common.Player

/**
 * Process-wide sleep timer, owned by the playback side rather than any UI.
 *
 * PlaybackService registers its player on create and clears it on destroy, so the
 * timer keeps running with the Now Playing sheet closed, the Activity stopped and
 * the screen off (when the UI's MediaController has been released). The service
 * runs in the app's main process, so a plain singleton is enough.
 */
object SleepTimer {

    private val handler = Handler(Looper.getMainLooper())
    private var player: Player? = null

    /** Elapsed-realtime deadline, or 0 when no timer is set. */
    var endsAtElapsed: Long = 0L
        private set

    val isActive: Boolean get() = endsAtElapsed > 0L

    /** Minutes left, rounded up; 0 when inactive. */
    fun minutesRemaining(): Long {
        if (!isActive) return 0
        val ms = (endsAtElapsed - SystemClock.elapsedRealtime()).coerceAtLeast(0)
        return (ms + 59_999) / 60_000
    }

    private val fire = Runnable {
        endsAtElapsed = 0L
        player?.pause()
    }

    fun attach(p: Player) { player = p }

    fun detach(p: Player) {
        if (player === p) {
            player = null
            cancel()
        }
    }

    fun start(minutes: Int) {
        cancel()
        if (minutes <= 0) return
        val delay = minutes * 60_000L
        endsAtElapsed = SystemClock.elapsedRealtime() + delay
        handler.postDelayed(fire, delay)
    }

    fun cancel() {
        handler.removeCallbacks(fire)
        endsAtElapsed = 0L
    }
}
