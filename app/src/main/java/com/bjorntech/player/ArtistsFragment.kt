package com.bjorntech.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bjorntech.player.databinding.FragmentListBinding

class ArtistsFragment : Fragment() {

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
            val grouped = MusicScanner.groupByArtist(songs)
            adapter.submitList(grouped.entries.map { GroupItem(it.key, it.value.size, it.value, R.drawable.ic_person) })
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

data class GroupItem(val name: String, val count: Int, val songs: List<Song>, val iconRes: Int = R.drawable.ic_music_note)

class GroupAdapter(
    private val onClick: (String, List<Song>) -> Unit
) : RecyclerView.Adapter<GroupAdapter.GroupViewHolder>() {

    private var items: List<GroupItem> = emptyList()

    fun submitList(newItems: List<GroupItem>) {
        items = newItems
        notifyDataSetChanged()
    }

    inner class GroupViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val icon: ImageView = itemView.findViewById(R.id.group_icon)
        val name: TextView = itemView.findViewById(R.id.group_name)
        val count: TextView = itemView.findViewById(R.id.group_count)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_group, parent, false)
        return GroupViewHolder(view)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        val item = items[position]
        holder.icon.setImageResource(item.iconRes)
        holder.name.text = item.name
        holder.count.text = "${item.count} songs"
        holder.itemView.setOnClickListener { onClick(item.name, item.songs) }
    }

    override fun getItemCount() = items.size
}
