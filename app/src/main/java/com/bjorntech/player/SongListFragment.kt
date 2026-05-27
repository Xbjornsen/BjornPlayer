package com.bjorntech.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.bjorntech.player.databinding.FragmentSongsBinding

class SongListFragment : Fragment() {

    private var _binding: FragmentSongsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    companion object {
        private const val ARG_TITLE = "title"
        private const val ARG_IDS = "ids"

        fun newInstance(title: String, ids: ArrayList<Long>): SongListFragment {
            return SongListFragment().apply {
                arguments = Bundle().apply {
                    putString(ARG_TITLE, title)
                    putLongArray(ARG_IDS, ids.toLongArray())
                }
            }
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSongsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val title = arguments?.getString(ARG_TITLE) ?: ""
        val ids = arguments?.getLongArray(ARG_IDS)?.toSet() ?: emptySet()

        binding.searchView.visibility = View.GONE

        val adapter = SongAdapter(
            onSongClick = { song, queue -> (activity as? MainActivity)?.playSong(song, queue) },
            onLongClick = { song ->
                SongOptionsFragment.newInstance(song.id)
                    .show(parentFragmentManager, "song_options")
            }
        )

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        viewModel.songs.observe(viewLifecycleOwner) { allSongs ->
            val filtered = allSongs.filter { it.id in ids }
            adapter.submitFullList(filtered)
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
