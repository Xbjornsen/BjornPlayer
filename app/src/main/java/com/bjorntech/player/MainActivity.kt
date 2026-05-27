package com.bjorntech.player

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bjorntech.player.databinding.ActivityMainBinding
import com.bumptech.glide.Glide
import com.google.common.util.concurrent.ListenableFuture

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var currentQueue: List<Song> = emptyList()

    /** True once we've kicked off the initial auto-play for this Activity instance. */
    private var hasAutoPlayed = false

    fun mediaController(): MediaController? = mediaController

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            val mc = mediaController ?: return
            val duration = mc.duration.takeIf { it > 0 } ?: return
            binding.miniProgress.progress = ((mc.currentPosition * 1000L) / duration).toInt()
            progressHandler.postDelayed(this, 500)
        }
    }

    // Permission launcher
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.any { it }) {
            viewModel.loadMusic()
        } else {
            Toast.makeText(this, "Storage permission needed to read music files", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNav()
        setupNowPlayingBar()
        observeViewModel()
        checkPermissionsAndLoad()
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        controllerFuture = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture?.addListener({
            mediaController = controllerFuture?.get()
            mediaController?.addListener(playerListener)
            val isPlaying = mediaController?.isPlaying ?: false
            binding.btnPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            progressHandler.post(progressRunnable)
            maybeAutoPlay()   // songs may already be loaded — try to start
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onStop() {
        progressHandler.removeCallbacksAndMessages(null)
        mediaController?.removeListener(playerListener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        mediaController = null
        super.onStop()
    }

    private fun checkPermissionsAndLoad() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            viewModel.loadMusic()
        } else {
            permissionLauncher.launch(permissions)
        }
    }

    private fun setupBottomNav() {
        binding.bottomNav.setOnItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_songs -> {
                    showFragment(SongsFragment())
                    true
                }
                R.id.nav_artists -> {
                    showFragment(ArtistsFragment())
                    true
                }
                R.id.nav_albums -> {
                    showFragment(AlbumsFragment())
                    true
                }
                R.id.nav_favourites -> {
                    showFragment(FavouritesFragment())
                    true
                }
                else -> false
            }
        }
        // Load default fragment
        showFragment(SongsFragment())
    }

    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        supportFragmentManager.beginTransaction()
            .replace(R.id.fragment_container, fragment)
            .commit()
    }

    private fun setupNowPlayingBar() {
        binding.nowPlayingBar.setOnClickListener {
            val song = viewModel.currentSong.value ?: return@setOnClickListener
            NowPlayingFragment.newInstance().show(supportFragmentManager, "now_playing")
        }

        binding.btnPlayPause.setOnClickListener {
            mediaController?.let { mc ->
                if (mc.isPlaying) mc.pause() else mc.play()
            }
        }

        binding.btnNext.setOnClickListener {
            mediaController?.seekToNextMediaItem()
        }
    }

    private fun observeViewModel() {
        viewModel.currentSong.observe(this) { song ->
            if (song != null) {
                binding.nowPlayingTitle.text = song.title
                binding.nowPlayingArtist.text = song.artist
                Glide.with(this)
                    .load(song.albumArtUri)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .into(binding.nowPlayingArt)
                if (binding.nowPlayingBar.visibility != android.view.View.VISIBLE) {
                    binding.nowPlayingBar.visibility = android.view.View.VISIBLE
                    val pad = (170 * resources.displayMetrics.density).toInt()
                    binding.fragmentContainer.setPadding(0, 0, 0, pad)
                }
            }
        }

        // Once the song library finishes loading, attempt auto-play.
        // The controller may not be connected yet — maybeAutoPlay handles that.
        viewModel.songs.observe(this) { songs ->
            if (songs.isNotEmpty()) maybeAutoPlay()
        }
    }

    /**
     * Starts playing a random song from the full library the very first time the
     * app opens in a fresh session. Guards against:
     *   - Controller not yet connected (called again once it connects).
     *   - Songs not yet scanned (called again once they load).
     *   - Something already playing — e.g. the user brought the app back to the
     *     foreground after it was already running (currentMediaItem != null).
     */
    private fun maybeAutoPlay() {
        if (hasAutoPlayed) return
        val controller = mediaController ?: return            // not connected yet
        if (controller.currentMediaItem != null) {
            hasAutoPlayed = true                              // already playing — don't interrupt
            return
        }
        val songs = viewModel.songs.value?.takeIf { it.isNotEmpty() } ?: return  // not loaded yet
        hasAutoPlayed = true
        playSong(songs.random(), songs)
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.btnPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            // mediaId is always present across the MediaSession boundary;
            // localConfiguration (and its uri) is stripped during serialisation
            // so we must never rely on item.localConfiguration?.uri here.
            val mediaId = mediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
            val song = currentQueue.find { it.id.toString() == mediaId }
            song?.let { viewModel.setCurrentSong(it) }
        }
    }

    fun addToQueue(song: Song) {
        val controller = mediaController ?: return
        val mediaItem = buildMediaItem(song)
        controller.addMediaItem(mediaItem)
        // Keep currentQueue in sync so onMediaItemTransition can resolve this song
        if (currentQueue.none { it.id == song.id }) {
            currentQueue = currentQueue + song
        }
        Toast.makeText(this, "${song.title} added to queue", Toast.LENGTH_SHORT).show()
    }

    fun playSong(song: Song, queue: List<Song>) {
        currentQueue = queue
        val controller = mediaController ?: return

        val mediaItems = queue.map { buildMediaItem(it) }
        val startIndex = queue.indexOf(song).coerceAtLeast(0)

        controller.setMediaItems(mediaItems, startIndex, 0)
        controller.shuffleModeEnabled = true
        controller.prepare()
        controller.play()
        viewModel.setCurrentSong(song)
    }

    /** Build a MediaItem with metadata embedded so the session notification and
     *  onMediaItemTransition callbacks always carry title / artist / artwork. */
    private fun buildMediaItem(song: Song): MediaItem {
        val metadata = androidx.media3.common.MediaMetadata.Builder()
            .setTitle(song.title)
            .setArtist(song.artist)
            .setAlbumTitle(song.album)
            .setArtworkUri(song.albumArtUri)
            .build()
        return MediaItem.Builder()
            .setUri(song.uri)
            .setMediaId(song.id.toString())
            .setMediaMetadata(metadata)
            .build()
    }
}
