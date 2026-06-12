package com.bjorntech.player

import android.content.Context
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.android.material.imageview.ShapeableImageView

class SongAdapter(
    private val onSongClick: (Song, List<Song>) -> Unit,
    private val onLongClick: (Song) -> Unit = {}
) : ListAdapter<Song, SongAdapter.SongViewHolder>(SongDiffCallback()) {

    private var fullList: List<Song> = emptyList()
    var currentSongId: Long = -1L

    fun submitFullList(songs: List<Song>) {
        fullList = songs
        submitList(songs)
    }

    fun setCurrentSong(id: Long) {
        val oldPos = currentList.indexOfFirst { it.id == currentSongId }
        currentSongId = id
        val newPos = currentList.indexOfFirst { it.id == id }
        if (oldPos >= 0) notifyItemChanged(oldPos)
        if (newPos >= 0) notifyItemChanged(newPos)
    }

    inner class SongViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val albumArt: ShapeableImageView = itemView.findViewById(R.id.song_album_art)
        val title: TextView = itemView.findViewById(R.id.song_title)
        val artist: TextView = itemView.findViewById(R.id.song_artist)
        val duration: TextView = itemView.findViewById(R.id.song_duration)
        val equalizer: EqualizerView = itemView.findViewById(R.id.equalizer_view)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SongViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_song, parent, false)
        return SongViewHolder(view)
    }

    override fun onBindViewHolder(holder: SongViewHolder, position: Int) {
        val song = getItem(position)
        holder.title.text = song.title
        holder.artist.text = song.artist

        val isCurrent = song.id == currentSongId
        if (isCurrent) {
            holder.duration.visibility = View.GONE
            holder.equalizer.visibility = View.VISIBLE
            holder.equalizer.isAnimating = true
            holder.itemView.setBackgroundResource(R.drawable.bg_row_current)
        } else {
            holder.duration.text = song.durationFormatted
            holder.duration.visibility = View.VISIBLE
            holder.equalizer.visibility = View.GONE
            holder.equalizer.isAnimating = false
            holder.itemView.setBackgroundResource(selectableItemBackground(holder.itemView.context))
        }

        Glide.with(holder.itemView.context)
            .load(song.albumArtUri)
            .diskCacheStrategy(DiskCacheStrategy.ALL)
            .placeholder(R.drawable.ic_music_note)
            .error(R.drawable.ic_music_note)
            .into(holder.albumArt)

        holder.itemView.setOnClickListener { onSongClick(song, currentList) }
        holder.itemView.setOnLongClickListener { onLongClick(song); true }
    }

    override fun onViewRecycled(holder: SongViewHolder) {
        super.onViewRecycled(holder)
        holder.equalizer.isAnimating = false
    }

    /** Resolve the theme's ripple background so non-current rows keep their touch feedback. */
    private fun selectableItemBackground(context: Context): Int {
        val v = TypedValue()
        context.theme.resolveAttribute(android.R.attr.selectableItemBackground, v, true)
        return v.resourceId
    }
}

class SongDiffCallback : DiffUtil.ItemCallback<Song>() {
    override fun areItemsTheSame(oldItem: Song, newItem: Song) = oldItem.id == newItem.id
    override fun areContentsTheSame(oldItem: Song, newItem: Song) = oldItem == newItem
}
