package com.bjorntech.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Timeline

/**
 * Persists "where you were": the queue (mediaIds in timeline order), its shuffle
 * play order, the current item and position, and shuffle/repeat modes.
 *
 * Saving happens on the playback side ([attach] from PlaybackService), so it keeps
 * working with the UI gone. Restoring is driven by MainActivity on a cold start,
 * because only the UI has the scanned library to rebuild MediaItems from.
 */
object PlaybackStateStore {

    private const val PREFS = "playback_state"
    private const val KEY_IDS = "ids"                 // comma-separated mediaIds, timeline order
    private const val KEY_ORDER = "shuffle_order"     // comma-separated timeline indices, play order
    private const val KEY_CURRENT_ID = "current_id"
    private const val KEY_POSITION = "position_ms"
    private const val KEY_SHUFFLE = "shuffle"
    private const val KEY_REPEAT = "repeat"

    private const val PERIODIC_SAVE_MS = 15_000L

    data class Saved(
        val ids: List<String>,
        val shuffleOrder: IntArray?,
        val currentId: String?,
        val positionMs: Long,
        val shuffle: Boolean,
        val repeatMode: Int
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(context: Context): Saved? {
        val p = prefs(context)
        val ids = p.getString(KEY_IDS, null)?.split(',')?.filter { it.isNotEmpty() }
        if (ids.isNullOrEmpty()) return null
        val order = p.getString(KEY_ORDER, null)
            ?.split(',')?.mapNotNull { it.toIntOrNull() }?.toIntArray()
            ?.takeIf { isPermutation(it, ids.size) }
        return Saved(
            ids = ids,
            shuffleOrder = order,
            currentId = p.getString(KEY_CURRENT_ID, null),
            positionMs = p.getLong(KEY_POSITION, 0L),
            shuffle = p.getBoolean(KEY_SHUFFLE, true),
            repeatMode = p.getInt(KEY_REPEAT, Player.REPEAT_MODE_OFF)
        )
    }

    fun clear(context: Context) = prefs(context).edit { clear() }

    internal fun isPermutation(order: IntArray, size: Int): Boolean {
        if (order.size != size) return false
        val seen = BooleanArray(size)
        for (i in order) {
            if (i !in 0 until size || seen[i]) return false
            seen[i] = true
        }
        return true
    }

    /** Play order of the timeline's windows (shuffle order is kept even when shuffle is off). */
    private fun shuffleOrderOf(timeline: Timeline): IntArray {
        val out = ArrayList<Int>(timeline.windowCount)
        var i = timeline.getFirstWindowIndex(true)
        while (i != C.INDEX_UNSET && out.size < timeline.windowCount) {
            out += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, true)
        }
        return out.toIntArray()
    }

    /**
     * Start saving [player]'s state. Call once from PlaybackService.onCreate; the
     * returned function detaches (call it in onDestroy, after a final save).
     */
    fun attach(context: Context, player: Player): () -> Unit {
        val appContext = context.applicationContext
        val handler = Handler(Looper.getMainLooper())

        fun saveQueue() {
            val count = player.mediaItemCount
            if (count == 0) return   // don't wipe the saved queue while the player is idle/empty
            val ids = (0 until count).joinToString(",") { player.getMediaItemAt(it).mediaId }
            val order = shuffleOrderOf(player.currentTimeline).joinToString(",")
            prefs(appContext).edit {
                putString(KEY_IDS, ids)
                putString(KEY_ORDER, order)
            }
        }

        fun savePosition() {
            val item = player.currentMediaItem ?: return
            prefs(appContext).edit {
                putString(KEY_CURRENT_ID, item.mediaId)
                putLong(KEY_POSITION, player.currentPosition.coerceAtLeast(0))
                putBoolean(KEY_SHUFFLE, player.shuffleModeEnabled)
                putInt(KEY_REPEAT, player.repeatMode)
            }
        }

        val periodic = object : Runnable {
            override fun run() {
                savePosition()
                if (player.isPlaying) handler.postDelayed(this, PERIODIC_SAVE_MS)
            }
        }

        val listener = object : Player.Listener {
            override fun onTimelineChanged(timeline: Timeline, reason: Int) {
                if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) {
                    saveQueue()
                    savePosition()
                }
            }
            override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) = savePosition()
            override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = savePosition()
            override fun onRepeatModeChanged(repeatMode: Int) = savePosition()
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                savePosition()
                handler.removeCallbacks(periodic)
                if (isPlaying) handler.postDelayed(periodic, PERIODIC_SAVE_MS)
            }
            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int
            ) {
                if (reason == Player.DISCONTINUITY_REASON_SEEK) savePosition()
            }
        }
        player.addListener(listener)

        return {
            savePosition()
            handler.removeCallbacks(periodic)
            player.removeListener(listener)
        }
    }
}
