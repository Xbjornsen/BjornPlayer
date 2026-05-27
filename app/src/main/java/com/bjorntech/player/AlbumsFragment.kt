package com.bjorntech.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.bjorntech.player.databinding.FragmentListBinding

class AlbumsFragment : Fragment() {

    private var _binding: FragmentListBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentListBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val adapter = GroupAdapter { groupName, songs ->
            val fragment = SongListFragment.newInstance(groupName, ArrayList(songs.map { it.id }))
            parentFragmentManager.beginTransaction()
                .replace(R.id.fragment_container, fragment)
                .addToBackStack(null)
                .commit()
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter

        viewModel.songs.observe(viewLifecycleOwner) { songs ->
            val grouped = MusicScanner.groupByAlbum(songs)
            adapter.submitList(grouped.entries.map { GroupItem(it.key, it.value.size, it.value, R.drawable.ic_album) })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
