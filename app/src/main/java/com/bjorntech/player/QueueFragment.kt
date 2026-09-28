package com.bjorntech.player

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.fragment.app.activityViewModels
import androidx.media3.common.C
import androidx.media3.common.Player
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
        val songsById = (viewModel.songs.value ?: emptyList()).associateBy { it.id.toString() }
        val currentIndex = controller?.currentMediaItemIndex ?: C.INDEX_UNSET

        // Build the list in *play* order: with shuffle on, window index order is just
        // library order, so walk the timeline's shuffle order instead.
        val entries = playOrder(controller).mapNotNull { windowIndex ->
            val mediaId = controller?.getMediaItemAt(windowIndex)?.mediaId ?: return@mapNotNull null
            songsById[mediaId]?.let { QueueEntry(windowIndex, it) }
        }
        val currentPos = entries.indexOfFirst { it.windowIndex == currentIndex }

        val adapter = QueueAdapter(currentPos) { entry ->
            controller?.seekTo(entry.windowIndex, 0)
            dismiss()
        }

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.adapter = adapter
        adapter.submitList(entries)

        if (currentPos >= 0) binding.recyclerView.scrollToPosition(currentPos)
    }

    /** Window indices in the order they'll play, honouring shuffle. */
    private fun playOrder(controller: Player?): List<Int> {
        controller ?: return emptyList()
        val count = controller.mediaItemCount
        val timeline = controller.currentTimeline
        val shuffle = controller.shuffleModeEnabled
        if (timeline.isEmpty || timeline.windowCount != count) return (0 until count).toList()
        val order = ArrayList<Int>(count)
        var i = timeline.getFirstWindowIndex(shuffle)
        while (i != C.INDEX_UNSET && order.size < count) {
            order += i
            i = timeline.getNextWindowIndex(i, Player.REPEAT_MODE_OFF, shuffle)
        }
        // Defensive: fall back to linear order if the timeline walk was incomplete.
        return if (order.size == count) order else (0 until count).toList()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

/** A queue row: the song plus its window index in the player's timeline. */
data class QueueEntry(val windowIndex: Int, val song: Song)

class QueueAdapter(
    private val currentPos: Int,
    private val onClick: (QueueEntry) -> Unit
) : RecyclerView.Adapter<QueueAdapter.QueueViewHolder>() {

    private var items: List<QueueEntry> = emptyList()

    fun submitList(list: List<QueueEntry>) { items = list; notifyDataSetChanged() }

    inner class QueueViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val title: TextView = itemView.findViewById(R.id.queue_title)
        val artist: TextView = itemView.findViewById(R.id.queue_artist)
        val indicator: View = itemView.findViewById(R.id.queue_playing_indicator)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        QueueViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_queue, parent, false))

    override fun onBindViewHolder(holder: QueueViewHolder, position: Int) {
        val entry = items[position]
        holder.title.text = entry.song.title
        holder.artist.text = entry.song.artist
        holder.indicator.visibility = if (position == currentPos) View.VISIBLE else View.INVISIBLE
        holder.itemView.alpha = if (currentPos >= 0 && position < currentPos) 0.45f else 1f
        holder.itemView.setOnClickListener { onClick(entry) }
    }

    override fun getItemCount() = items.size
}
