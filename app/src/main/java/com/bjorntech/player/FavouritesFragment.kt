package com.bjorntech.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.bjorntech.player.databinding.FragmentSongsBinding

class FavouritesFragment : Fragment() {

    private var _binding: FragmentSongsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()
    private lateinit var adapter: SongAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSongsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.searchView.visibility = View.GONE
        binding.btnSort.visibility = View.GONE

        adapter = SongAdapter(
            onSongClick = { song, queue -> (activity as? MainActivity)?.playSong(song, queue) },
            onLongClick = { song ->
                SongOptionsFragment.newInstance(song.id)
                    .show(parentFragmentManager, "song_options")
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (loading) View.GONE else View.VISIBLE
        }

        viewModel.songs.observe(viewLifecycleOwner) { refreshList() }

        viewModel.currentSong.observe(viewLifecycleOwner) { song ->
            adapter.setCurrentSong(song?.id ?: -1L)
        }
    }

    override fun onResume() {
        super.onResume()
        refreshList()
    }

    private fun refreshList() {
        val favIds = FavouritesManager.getFavourites(requireContext())
        val songs = (viewModel.songs.value ?: emptyList())
            .filter { it.id in favIds }
            .sortedBy { it.title.lowercase() }
        adapter.submitFullList(songs)
        binding.emptyText.text = "No favourites yet.\nLong-press any song to add one."
        binding.emptyText.visibility = if (songs.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
