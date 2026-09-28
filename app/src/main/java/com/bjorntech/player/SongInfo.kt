package com.bjorntech.player

import android.content.Context
import android.media.MediaMetadataRetriever
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** "Song Info" dialog shared by Now Playing and the song options sheet. */
object SongInfo {

    /** Reads bitrate/size off the main thread (file I/O), then shows the dialog. */
    fun show(fragment: Fragment, song: Song) {
        val ctx = fragment.requireContext().applicationContext
        val dialogContext = fragment.requireContext()
        fragment.viewLifecycleOwner.lifecycleScope.launch {
            val (bitrate, size) = withContext(Dispatchers.IO) { readDetails(ctx, song) }
            MaterialAlertDialogBuilder(dialogContext)
                .setTitle("Song Info")
                .setMessage(
                    "Title: ${song.title}\n" +
                    "Artist: ${song.artist}\n" +
                    "Album: ${song.album}\n" +
                    "Duration: ${song.durationFormatted}\n" +
                    "Bitrate: $bitrate\n" +
                    "Size: $size"
                )
                .setPositiveButton("Close", null)
                .show()
        }
    }

    private fun readDetails(context: Context, song: Song): Pair<String, String> {
        var bitrate = "Unknown"
        var fileSize = "Unknown"
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, song.uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let {
                bitrate = "${it.toLongOrNull()?.div(1000) ?: "?"} kbps"
            }
        } catch (_: Exception) {
        } finally {
            retriever.release()
        }
        try {
            context.contentResolver.openFileDescriptor(song.uri, "r")?.use {
                val b = it.statSize
                fileSize = when {
                    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000f)
                    b >= 1_000 -> "%.1f KB".format(b / 1_000f)
                    else -> "$b B"
                }
            }
        } catch (_: Exception) {}
        return bitrate to fileSize
    }
}
