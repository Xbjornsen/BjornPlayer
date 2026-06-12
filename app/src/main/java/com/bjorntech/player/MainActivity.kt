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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.bjorntech.player.databinding.ActivityMainBinding
import com.bumptech.glide.Glide
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: PlayerViewModel by viewModels()

    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var mediaController: MediaController? = null
    private var currentQueue: List<Song> = emptyList()

    /** True once we've kicked off the initial auto-play for this Activity instance. */
    private var hasAutoPlayed = false

    /** Update-check runs once per Activity instance; APK awaiting install permission. */
    private var hasCheckedForUpdate = false
    private var pendingApk: File? = null

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
        maybeCheckForUpdate()
    }

    override fun onResume() {
        super.onResume()
        // If we sent the user to grant "install unknown apps", finish the install on return.
        if (pendingApk != null) installPendingApk()
    }

    override fun onStart() {
        super.onStart()
        val sessionToken = SessionToken(this, ComponentName(this, PlaybackService::class.java))
        val future = MediaController.Builder(this, sessionToken).buildAsync()
        controllerFuture = future
        future.addListener({
            // get() can throw if the connection failed; never wire the UI up to a
            // half-connected controller.
            val controller = try {
                future.get()
            } catch (e: Exception) {
                null
            } ?: return@addListener

            mediaController = controller
            controller.addListener(playerListener)
            syncPlayPauseIcon()
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

        binding.btnPlayPause.setOnClickListener { togglePlayPause() }
        binding.btnNext.setOnClickListener { playNext() }
    }

    /**
     * Play/pause that self-heals. The MediaSession can come back with an empty
     * queue if the service was reclaimed while the app was backgrounded; in that
     * case a bare play() no-ops, so we rebuild the queue from the song the UI is
     * showing. Also handles the ended/idle states where play() alone does nothing.
     * Public so both the mini-bar and the Now Playing sheet share one code path.
     */
    fun togglePlayPause() {
        val mc = mediaController ?: return
        when {
            mc.isPlaying -> mc.pause()
            mc.mediaItemCount == 0 -> restartFromCurrentSong()
            else -> {
                when (mc.playbackState) {
                    Player.STATE_ENDED -> mc.seekTo(0, 0)
                    Player.STATE_IDLE -> mc.prepare()
                }
                mc.play()
            }
        }
    }

    /** Skip to next, rebuilding the queue if the session lost it, and wrapping at the end. */
    fun playNext() {
        val mc = mediaController ?: return
        when {
            mc.mediaItemCount == 0 -> restartFromCurrentSong()
            mc.hasNextMediaItem() -> mc.seekToNextMediaItem()
            else -> { mc.seekTo(0, 0); mc.play() }   // wrap around at the end
        }
    }

    /** Skip to previous, rebuilding the queue if the session lost it. */
    fun playPrevious() {
        val mc = mediaController ?: return
        if (mc.mediaItemCount == 0) restartFromCurrentSong() else mc.seekToPreviousMediaItem()
    }

    /**
     * Rebuilds the playback queue from the song shown in the UI. Self-heals the
     * transport controls when the MediaSession comes back with an empty queue
     * (e.g. the service was reclaimed while the app was backgrounded), so the user
     * doesn't have to re-pick a song from the list.
     */
    private fun restartFromCurrentSong() {
        val song = viewModel.currentSong.value ?: return
        val songs = viewModel.songs.value?.takeIf { it.isNotEmpty() } ?: return
        playSong(song, songs)
    }

    // ── In-app auto-update (checks GitHub Releases) ──────────────────────────

    /** Once per launch, ask GitHub if a newer release exists and prompt the user. */
    private fun maybeCheckForUpdate() {
        if (hasCheckedForUpdate) return
        hasCheckedForUpdate = true
        lifecycleScope.launch {
            val info = UpdateManager.checkForUpdate() ?: return@launch
            showUpdateDialog(info)
        }
    }

    private fun showUpdateDialog(info: UpdateManager.UpdateInfo) {
        MaterialAlertDialogBuilder(this)
            .setTitle("Update available")
            .setMessage("BjornPlayer ${info.versionName} is available. Download and install it now?")
            .setPositiveButton("Update") { _, _ -> downloadAndInstall(info) }
            .setNegativeButton("Later", null)
            .show()
    }

    private fun downloadAndInstall(info: UpdateManager.UpdateInfo) {
        Toast.makeText(this, "Downloading update…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val apk = UpdateManager.downloadApk(this@MainActivity, info)
            if (apk == null) {
                Toast.makeText(this@MainActivity, "Update download failed", Toast.LENGTH_LONG).show()
                return@launch
            }
            pendingApk = apk
            installPendingApk()
        }
    }

    private fun installPendingApk() {
        val apk = pendingApk ?: return
        if (UpdateManager.canInstall(this)) {
            pendingApk = null
            UpdateManager.installApk(this, apk)
        } else {
            // Needs one-time consent; onResume retries the install once granted.
            Toast.makeText(this, "Allow BjornPlayer to install apps, then come back", Toast.LENGTH_LONG).show()
            UpdateManager.requestInstallPermission(this)
        }
    }

    /** Reflect the controller's real playing state on the mini-bar button. */
    private fun syncPlayPauseIcon() {
        binding.btnPlayPause.setImageResource(
            if (mediaController?.isPlaying == true) R.drawable.ic_pause else R.drawable.ic_play
        )
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

        override fun onPlaybackStateChanged(playbackState: Int) {
            // Ended/idle leaves isPlaying false — keep the button showing "play".
            syncPlayPauseIcon()
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
