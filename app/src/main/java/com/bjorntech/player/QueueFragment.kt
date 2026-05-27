package com.bjorntech.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bjorntech.player.databinding.FragmentQueueBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

class QueueFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentQueueBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()

    companion object {
        fun newInstance() = QueueFragment()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentQueueBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val controller = (activity as? MainActivity)?.mediaController()
        val allSongs = viewModel.songs.value ?: emptyList()
        val currentIndex = controller?.currentMediaItemIndex ?: -1
        val count = controller?.mediaItemCount ?: 0

        val queue = (0 until count).mapNotNull { i ->
            val mediaId = controller?.getMediaItemAt(i)?.mediaId ?: return@mapNotNull null
            allSongs.find { it.id.toString() == mediaId }
        }

        val adapter = QueueAdapter(currentIndex) { index ->
            controller?.seekTo(index, 0)
            dismiss()
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        adapter.submitList(queue)

        if (currentIndex >= 0) binding.recyclerView.scrollToPosition(currentIndex)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

class QueueAdapter(
    private val currentIndex: Int,
    private val onClick: (Int) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private var items: List<Song> = emptyList()

    fun submitList(list: List<Song>) { items = list; notifyDataSetChanged() }

    inner class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.queue_title)
        val artist: TextView = itemView.findViewById(R.id.queue_artist)
        val indicator: View = itemView.findViewById(R.id.queue_playing_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        QueueViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false))

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val song = items[position]
        holder.title.text = song.title
        holder.artist.text = song.artist
        holder.indicator.visibility = if (position == currentIndex) View.VISIBLE else View.INVISIBLE
        holder.itemView.alpha = if (position < currentIndex) 0.45f else 1f
        holder.itemView.setOnClickListener { onClick(position) }
    }

    override fun getItemCount() = items.size
}
