package com.bjorntech.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.widget.SearchView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.bjorntech.player.databinding.FragmentSongsBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class SongsFragment : Fragment() {

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

        adapter = SongAdapter(
            onSongClick = { song, queue -> (activity as? MainActivity)?.playSong(song, queue) },
            onLongClick = { song ->
                SongOptionsFragment.newInstance(song.id)
                    .show(parentFragmentManager, "song_options")
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.setSearchQuery(newText ?: "")
                refreshList()
                return true
            }
        })

        binding.btnSort.setOnClickListener { showSortDialog() }

        viewModel.songs.observe(viewLifecycleOwner) {
            refreshList()
            updateEmptyState()
        }

        viewModel.sortOrder.observe(viewLifecycleOwner) {
            refreshList()
        }

        viewModel.isLoading.observe(viewLifecycleOwner) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
            binding.recyclerView.visibility = if (loading) View.GONE else View.VISIBLE
        }

        viewModel.currentSong.observe(viewLifecycleOwner) { song ->
            adapter.setCurrentSong(song?.id ?: -1L)
        }
    }

    private fun showSortDialog() {
        val options = arrayOf("Random", "Title", "Artist", "Duration")
        val orders = arrayOf(SortOrder.RANDOM, SortOrder.TITLE, SortOrder.ARTIST, SortOrder.DURATION)
        val current = orders.indexOf(viewModel.sortOrder.value).coerceAtLeast(0)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sort by")
            .setSingleChoiceItems(options, current) { dialog, which ->
                viewModel.setSortOrder(orders[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun refreshList() {
        val songs = viewModel.filteredSongs()
        adapter.submitFullList(songs)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        val songs = viewModel.filteredSongs()
        val loading = viewModel.isLoading.value ?: true
        binding.emptyText.visibility = if (!loading && songs.isEmpty()) View.VISIBLE else View.GONE
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
