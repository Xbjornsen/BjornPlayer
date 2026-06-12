package com.bjorntech.player

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.media.MediaMetadataRetriever
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.SeekBar
import androidx.fragment.app.activityViewModels
import androidx.media3.common.Player
import androidx.palette.graphics.Palette
import com.bjorntech.player.databinding.FragmentNowPlayingBinding
import com.bumptech.glide.Glide
import com.bumptech.glide.request.target.CustomTarget
import com.bumptech.glide.request.transition.Transition
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs

class NowPlayingFragment : BottomSheetDialogFragment() {

    private var _binding: FragmentNowPlayingBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PlayerViewModel by activityViewModels()
    private val handler = Handler(Looper.getMainLooper())

    /**
     * Always resolve the controller live from the Activity. The Activity releases
     * and rebuilds its MediaController across onStop/onStart, so caching it here
     * would leave the sheet driving a released controller after the app returns
     * from the background.
     */
    private val controller: androidx.media3.session.MediaController?
        get() = (activity as? MainActivity)?.mediaController()
    private var isSeeking = false
    private var isAnimating = false

    private val speeds = listOf(0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
    private val speedLabels = listOf("0.75×", "1×", "1.25×", "1.5×", "2×")
    private var speedIndex = 1

    private var sleepTimerRunnable: Runnable? = null

    companion object {
        fun newInstance(): NowPlayingFragment = NowPlayingFragment()
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _binding?.npBtnPlayPause?.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }

        override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
            updateShuffleIcon(shuffleModeEnabled)
        }

        override fun onRepeatModeChanged(repeatMode: Int) {
            updateRepeatIcon(repeatMode)
        }

        // Defensive: the ViewModel is the primary source of truth (updated in
        // MainActivity.playerListener), but observing the transition here ensures
        // the seekbar max and favourite icon are always in sync even if the
        // ViewModel update races with the UI.
        override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int) {
            // The ViewModel observer will fire shortly; reset the seekbar to 0
            // immediately so it doesn't show stale progress while the new song
            // metadata arrives via LiveData.
            _binding?.npSeekBar?.progress = 0
            _binding?.npTimeElapsed?.text = formatTime(0)
        }
    }

    override fun onResume() {
        super.onResume()
        // (Re)attach to the current controller — it may have been rebuilt by the
        // Activity while we were backgrounded — and sync all transport icons.
        controller?.addListener(playerListener)
        syncTransportUi()
    }

    override fun onPause() {
        controller?.removeListener(playerListener)
        super.onPause()
    }

    /** Pull play/pause, shuffle, repeat and speed state from the live controller. */
    private fun syncTransportUi() {
        val mc = controller ?: return
        _binding?.npBtnPlayPause?.setImageResource(
            if (mc.isPlaying) R.drawable.ic_pause else R.drawable.ic_play
        )
        updateShuffleIcon(mc.shuffleModeEnabled)
        updateRepeatIcon(mc.repeatMode)
        val currentSpeed = mc.playbackParameters.speed
        speedIndex = speeds.indexOfFirst { s -> s == currentSpeed }.takeIf { i -> i >= 0 } ?: 1
        _binding?.npBtnSpeed?.text = speedLabels[speedIndex]
        val speedColor = if (speedIndex == 1) R.color.text_secondary else R.color.accent
        _binding?.npBtnSpeed?.setTextColor(resources.getColor(speedColor, context?.theme))
    }

    override fun onStart() {
        super.onStart()
        val sheet = dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
        sheet?.let {
            val behavior = com.google.android.material.bottomsheet.BottomSheetBehavior.from(it)
            behavior.skipCollapsed = true
            behavior.state = com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentNowPlayingBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        viewModel.currentSong.observe(viewLifecycleOwner) { song ->
            if (song != null) {
                binding.npTitle.text = song.title
                binding.npArtist.text = song.artist
                binding.npAlbum.text = song.album
                binding.npSeekBar.max = song.duration.toInt()
                loadAlbumArtWithPalette(song)
                refreshFavouriteIcon(song.id)
            }
        }

        // Route transport through the Activity so the sheet and the mini-bar share
        // the same self-healing play/next/prev logic.
        binding.npBtnPlayPause.setOnClickListener { (activity as? MainActivity)?.togglePlayPause() }
        binding.npBtnPrev.setOnClickListener { (activity as? MainActivity)?.playPrevious() }
        binding.npBtnNext.setOnClickListener { (activity as? MainActivity)?.playNext() }

        binding.npBtnShuffle.setOnClickListener {
            controller?.let {
                val newState = !it.shuffleModeEnabled
                it.shuffleModeEnabled = newState
                updateShuffleIcon(newState)
            }
        }

        binding.npBtnRepeat.setOnClickListener {
            controller?.let {
                val next = when (it.repeatMode) {
                    Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                    Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                    else -> Player.REPEAT_MODE_OFF
                }
                it.repeatMode = next
                updateRepeatIcon(next)
            }
        }

        binding.npSeekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(sb: SeekBar) { isSeeking = true }
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {}
            override fun onStopTrackingTouch(sb: SeekBar) {
                controller?.seekTo(sb.progress.toLong())
                isSeeking = false
            }
        })

        binding.npBtnFavourite.setOnClickListener {
            val song = viewModel.currentSong.value ?: return@setOnClickListener
            FavouritesManager.toggle(requireContext(), song.id)
            refreshFavouriteIcon(song.id)
        }

        binding.npBtnQueue.setOnClickListener {
            QueueFragment.newInstance().show(parentFragmentManager, "queue")
        }

        binding.npBtnSpeed.setOnClickListener {
            speedIndex = (speedIndex + 1) % speeds.size
            binding.npBtnSpeed.text = speedLabels[speedIndex]
            controller?.setPlaybackSpeed(speeds[speedIndex])
            val color = if (speedIndex == 1) R.color.text_secondary else R.color.accent
            binding.npBtnSpeed.setTextColor(resources.getColor(color, context?.theme))
        }

        binding.npBtnTimer.setOnClickListener { showSleepTimerDialog() }

        binding.npBtnInfo.setOnClickListener {
            val song = viewModel.currentSong.value ?: return@setOnClickListener
            showSongInfo(song)
        }

        setupSwipeGesture()
        handler.post(seekUpdateRunnable)
    }

    private fun loadAlbumArtWithPalette(song: Song) {
        val surfaceColor = resources.getColor(R.color.surface, context?.theme)
        Glide.with(this)
            .asBitmap()
            .load(song.albumArtUri)
            .placeholder(R.drawable.ic_music_note)
            .error(R.drawable.ic_music_note)
            .into(object : CustomTarget<Bitmap>() {
                override fun onResourceReady(resource: Bitmap, transition: Transition<in Bitmap>?) {
                    _binding?.npAlbumArt?.setImageBitmap(resource)
                    Palette.from(resource).generate { palette ->
                        val dominant = palette?.dominantSwatch?.rgb ?: return@generate
                        val blended = blendColors(surfaceColor, dominant, 0.12f)
                        ValueAnimator.ofObject(ArgbEvaluator(), surfaceColor, blended).apply {
                            duration = 600
                            addUpdateListener { _binding?.root?.setBackgroundColor(it.animatedValue as Int) }
                        }.start()
                    }
                }

                override fun onLoadCleared(placeholder: Drawable?) {
                    _binding?.npAlbumArt?.setImageDrawable(placeholder)
                }
            })
    }

    private fun blendColors(base: Int, overlay: Int, ratio: Float): Int {
        val r = (Color.red(base) * (1 - ratio) + Color.red(overlay) * ratio).toInt()
        val g = (Color.green(base) * (1 - ratio) + Color.green(overlay) * ratio).toInt()
        val b = (Color.blue(base) * (1 - ratio) + Color.blue(overlay) * ratio).toInt()
        return Color.rgb(r, g, b)
    }

    private fun refreshFavouriteIcon(songId: Long) {
        val isFav = FavouritesManager.isFavourite(requireContext(), songId)
        _binding?.npBtnFavourite?.apply {
            setImageResource(if (isFav) R.drawable.ic_favorite_filled else R.drawable.ic_favorite)
            val color = if (isFav) R.color.accent else R.color.text_secondary
            setColorFilter(resources.getColor(color, context?.theme))
        }
    }

    private fun showSleepTimerDialog() {
        val options = arrayOf("Off", "5 minutes", "15 minutes", "30 minutes", "60 minutes")
        val minutes = intArrayOf(0, 5, 15, 30, 60)
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Sleep Timer")
            .setItems(options) { _, which ->
                cancelSleepTimer()
                if (minutes[which] > 0) {
                    val runnable = Runnable { controller?.pause() }
                    sleepTimerRunnable = runnable
                    handler.postDelayed(runnable, minutes[which] * 60_000L)
                    _binding?.npBtnTimer?.setColorFilter(resources.getColor(R.color.accent, context?.theme))
                } else {
                    _binding?.npBtnTimer?.setColorFilter(resources.getColor(R.color.text_secondary, context?.theme))
                }
            }
            .show()
    }

    private fun cancelSleepTimer() {
        sleepTimerRunnable?.let { handler.removeCallbacks(it) }
        sleepTimerRunnable = null
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
        } catch (_: Exception) {}
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Song Info")
            .setMessage(
                "Title: ${song.title}\nArtist: ${song.artist}\nAlbum: ${song.album}\n" +
                "Duration: ${song.durationFormatted}\nBitrate: $bitrate\nSize: $fileSize"
            )
            .setPositiveButton("Close", null)
            .show()
    }

    private fun setupSwipeGesture() {
        val threshold = (40 * resources.displayMetrics.density)
        var startX = 0f
        var startY = 0f
        var isDragging = false

        binding.npAlbumArt.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (isAnimating) return@setOnTouchListener true
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!isDragging && abs(dx) > abs(dy) && abs(dx) > 8f) isDragging = true
                    if (isDragging) {
                        binding.npAlbumArt.translationX = dx
                        val drag = abs(dx) / (binding.npAlbumArt.width / 2f)
                        binding.npAlbumArt.alpha = (1f - drag * 0.5f).coerceAtLeast(0.3f)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!isAnimating && isDragging) {
                        val dx = event.rawX - startX
                        if (abs(dx) > threshold) {
                            animateSwipeOut(dx < 0) {
                                if (dx < 0) controller?.seekToNextMediaItem()
                                else controller?.seekToPreviousMediaItem()
                            }
                        } else {
                            springBack()
                        }
                    }
                    isDragging = false
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (isDragging) springBack()
                    isDragging = false
                    true
                }
                else -> false
            }
        }
    }

    private fun springBack() {
        binding.npAlbumArt.animate()
            .translationX(0f)
            .alpha(1f)
            .setDuration(300)
            .setInterpolator(OvershootInterpolator(2f))
            .start()
    }

    private fun animateSwipeOut(goNext: Boolean, onMidpoint: () -> Unit) {
        isAnimating = true
        val art = binding.npAlbumArt
        val width = art.width.toFloat()
        val exitX = if (goNext) -width else width
        val enterX = if (goNext) width else -width
        art.animate()
            .translationX(exitX)
            .alpha(0f)
            .setDuration(220)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                onMidpoint()
                art.translationX = enterX
                art.animate()
                    .translationX(0f)
                    .alpha(1f)
                    .setDuration(260)
                    .setInterpolator(DecelerateInterpolator())
                    .withEndAction { isAnimating = false }
                    .start()
            }
            .start()
    }

    private val seekUpdateRunnable = object : Runnable {
        override fun run() {
            val position = controller?.currentPosition ?: 0
            val duration = viewModel.currentSong.value?.duration ?: 0
            if (!isSeeking) {
                _binding?.npSeekBar?.progress = position.toInt()
            }
            _binding?.npTimeElapsed?.text = formatTime(position)
            _binding?.npTimeRemaining?.text = "-${formatTime((duration - position).coerceAtLeast(0))}"
            handler.postDelayed(this, 500)
        }
    }

    private fun formatTime(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    private fun updateShuffleIcon(enabled: Boolean) {
        val color = if (enabled) R.color.accent else R.color.text_secondary
        _binding?.npBtnShuffle?.setColorFilter(resources.getColor(color, context?.theme))
    }

    private fun updateRepeatIcon(repeatMode: Int) {
        _binding?.npBtnRepeat?.apply {
            setImageResource(
                if (repeatMode == Player.REPEAT_MODE_ONE) R.drawable.ic_repeat_one
                else R.drawable.ic_repeat
            )
            val color = if (repeatMode == Player.REPEAT_MODE_OFF) R.color.text_secondary else R.color.accent
            setColorFilter(resources.getColor(color, context?.theme))
        }
    }

    override fun onDestroyView() {
        handler.removeCallbacksAndMessages(null)   // listener detached in onPause
        super.onDestroyView()
        _binding = null
    }
}
