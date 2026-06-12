package com.bjorntech.player

import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.activityViewModels
import com.bjorntech.player.databinding.FragmentSongOptionsBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SongOptionsFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentSongOptionsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    companion object {
        private const val ARG_SONG_ID = "song_id"
        fun newInstance(songId: Long) = SongOptionsFragment().apply {
            arguments = Bundle().apply { putLong(ARG_SONG_ID, songId) }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSongOptionsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val songId = arguments?.getLong(ARG_SONG_ID) ?: run { dismiss(); return }
        val song = viewModel.songs.value?.find { it.id == songId } ?: run { dismiss(); return }
        val allSongs = viewModel.songs.value ?: emptyList()

        binding.soTitle.text = song.title
        binding.soArtist.text = song.artist
        refreshFavouriteOption(song.id)

        binding.soPlay.setOnClickListener {
            (activity as? MainActivity)?.playSong(song, allSongs)
            dismiss()
        }

        binding.soQueue.setOnClickListener {
            (activity as? MainActivity)?.addToQueue(song)
            dismiss()
        }

        binding.soFavourite.setOnClickListener {
            FavouritesManager.toggle(requireContext(), song.id)
            refreshFavouriteOption(song.id)
        }

        binding.soInfo.setOnClickListener {
            showSongInfo(song)
        }

        binding.soDelete.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Delete from device?")
                .setMessage("\"${song.title}\" will be permanently deleted from your phone. This can't be undone.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Delete") { _, _ ->
                    (activity as? MainActivity)?.deleteSong(song)
                    dismiss()
                }
                .show()
        }
    }

    private fun refreshFavouriteOption(songId: Long) {
        val isFav = FavouritesManager.isFavourite(requireContext(), songId)
        _binding?.soFavouriteText?.text = if (isFav) "Remove from Favourites" else "Add to Favourites"
        _binding?.soFavouriteIcon?.setImageResource(
            if (isFav) R.drawable.ic_favorite_filled else R.drawable.ic_favorite
        )
    }

    private fun showSongInfo(song: Song) {
        val retriever = MediaMetadataRetriever()
        var bitrate = "Unknown"
        var fileSize = "Unknown"
        try {
            retriever.setDataSource(requireContext(), song.uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.let {
                bitrate = "${it.toLongOrNull()?.div(1000) ?: "?"} kbps"
            }
        } catch (_: Exception) {
        } finally {
            retriever.release()
        }
        try {
            requireContext().contentResolver.openFileDescriptor(song.uri, "r")?.use {
                val b = it.statSize
                fileSize = when {
                    b >= 1_000_000 -> "%.1f MB".format(b / 1_000_000f)
                    b >= 1_000 -> "%.1f KB".format(b / 1_000f)
                    else -> "$b B"
                }
            }
        } catch (_: Exception) { }

        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Song Info")
            .setMessage(
                "Title: ${song.title}\n" +
                "Artist: ${song.artist}\n" +
                "Album: ${song.album}\n" +
                "Duration: ${song.durationFormatted}\n" +
                "Bitrate: $bitrate\n" +
                "Size: $fileSize"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
