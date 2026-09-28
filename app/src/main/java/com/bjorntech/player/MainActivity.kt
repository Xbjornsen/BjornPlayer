package com.bjorntech.player

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.annotation.RequiresApi
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

    /** mediaIds the user queued that haven't started yet, oldest first (FIFO). */
    private val pendingQueued = mutableListOf<String>()

    /** True once we've kicked off the initial auto-play for this Activity instance. */
    private var hasAutoPlayed = false

    /** Update-check runs once per Activity instance; APK awaiting install permission. */
    private var hasCheckedForUpdate = false
    private var pendingApk: File? = null

    fun mediaController(): MediaController? = mediaController

    private val progressHandler = Handler(Looper.getMainLooper())
    private val progressRunnable = object : Runnable {
        override fun run() {
            // Always reschedule: bailing out while the duration is still unknown (e.g.
            // right after connect, before auto-play has prepared a track) used to stop
            // this loop for good. onStop removes it.
            mediaController?.let { mc ->
                val duration = mc.duration
                binding.miniProgress.progress =
                    if (duration > 0) ((mc.currentPosition * 1000L) / duration).toInt() else 0
            }
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
            // After a second refusal Android stops showing the dialog (no rationale);
            // from then on the only route is the app's system settings page.
            permissionPermanentlyDenied = results.keys.none { shouldShowRequestPermissionRationale(it) }
            viewModel.onPermissionDenied()
        }
    }

    private var permissionPermanentlyDenied = false

    /** Song awaiting a system delete confirmation / write permission. */
    private var pendingDeleteSong: Song? = null

    // System delete-confirmation dialog (Android 10+ scoped storage).
    private val deleteRequestLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        val song = pendingDeleteSong
        pendingDeleteSong = null
        if (result.resultCode == RESULT_OK && song != null) onSongDeleted(song)
    }

    // WRITE_EXTERNAL_STORAGE consent for deletes on Android 9 and below.
    private val deleteWritePermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val song = pendingDeleteSong
        pendingDeleteSong = null
        if (granted && song != null) legacyDelete(song)
        else if (!granted) Toast.makeText(this, "Permission needed to delete files", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupBottomNav(restoring = savedInstanceState != null)
        setupNowPlayingBar()
        observeViewModel()
        checkPermissionsAndLoad()
        maybeCheckForUpdate()
    }

    override fun onResume() {
        super.onResume()
        // If we sent the user to grant "install unknown apps", finish the install on return.
        if (pendingApk != null) installPendingApk()
        // Returning from system settings after granting library access.
        if (viewModel.permissionDenied.value == true && hasLibraryPermission()) viewModel.loadMusic()
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
            syncCurrentSongFromController()
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

    private fun libraryPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun hasLibraryPermission(): Boolean = libraryPermissions().all {
        ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun checkPermissionsAndLoad() {
        if (hasLibraryPermission()) {
            viewModel.loadMusic()
        } else {
            permissionLauncher.launch(libraryPermissions())
        }
    }

    /** "Grant access" from the empty state: re-ask, or open app settings if blocked. */
    fun requestLibraryAccess() {
        if (permissionPermanentlyDenied) {
            startActivity(
                android.content.Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    android.net.Uri.fromParts("package", packageName, null)
                )
            )
        } else {
            permissionLauncher.launch(libraryPermissions())
        }
    }

    private fun setupBottomNav(restoring: Boolean) {
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
                R.id.nav_settings -> {
                    showFragment(SettingsFragment())
                    true
                }
                else -> false
            }
        }
        // Tapping the current tab again returns from a drill-down to its list.
        binding.bottomNav.setOnItemReselectedListener {
            supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        // Load the default tab only on a fresh start. On recreation (rotation, theme
        // change) the FragmentManager and BottomNavigationView restore themselves;
        // replacing here showed Songs while the nav still highlighted the old tab.
        if (!restoring) showFragment(SongsFragment())
    }

    private fun showFragment(fragment: androidx.fragment.app.Fragment) {
        // Switching tabs drops any artist/album drill-down, so Back doesn't pop an
        // old drill-down over a different tab.
        supportFragmentManager.popBackStack(null, androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
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
            else -> {
                // Wrap to the first item in *play* order (shuffle-aware), not timeline index 0.
                val first = mc.currentTimeline.getFirstWindowIndex(mc.shuffleModeEnabled)
                mc.seekTo(if (first == androidx.media3.common.C.INDEX_UNSET) 0 else first, 0)
                mc.play()
            }
        }
    }

    /**
     * Previous, rebuilding the queue if the session lost it. Like most players, the
     * button restarts the current track if more than ~3 s in (Media3's seekToPrevious);
     * [forceTrackChange] always goes to the previous item (used by swipe).
     */
    fun playPrevious(forceTrackChange: Boolean = false) {
        val mc = mediaController ?: return
        when {
            mc.mediaItemCount == 0 -> restartFromCurrentSong()
            forceTrackChange -> mc.seekToPreviousMediaItem()
            else -> mc.seekToPrevious()
        }
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

    // ── Delete a song from the device (protected) ────────────────────────────

    /**
     * Deletes a song's file from the device. On Android 10+ this routes through
     * MediaStore.createDeleteRequest, so the SYSTEM shows its own confirmation
     * dialog — the protected delete. On Android 9 and below it needs
     * WRITE_EXTERNAL_STORAGE and deletes directly; on Android 10 it catches the
     * RecoverableSecurityException and launches the system consent dialog.
     */
    fun deleteSong(song: Song) {
        pendingDeleteSong = song
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val pendingIntent = MediaStore.createDeleteRequest(contentResolver, listOf(song.uri))
            deleteRequestLauncher.launch(IntentSenderRequest.Builder(pendingIntent.intentSender).build())
        } else {
            legacyDelete(song)
        }
    }

    private fun legacyDelete(song: Song) {
        // Android 9 and below need explicit write permission first.
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pendingDeleteSong = song
            deleteWritePermLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return
        }
        try {
            val rows = contentResolver.delete(song.uri, null, null)
            pendingDeleteSong = null
            if (rows > 0) onSongDeleted(song)
            else Toast.makeText(this, "Couldn't delete the file", Toast.LENGTH_LONG).show()
        } catch (e: SecurityException) {
            // Android 10 hands back a user-consent dialog to launch.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && launchRecoverableDelete(e, song)) return
            pendingDeleteSong = null
            Toast.makeText(this, "Couldn't delete: permission denied", Toast.LENGTH_LONG).show()
        }
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun launchRecoverableDelete(e: SecurityException, song: Song): Boolean {
        val recoverable = e as? RecoverableSecurityException ?: return false
        pendingDeleteSong = song
        val sender = recoverable.userAction.actionIntent.intentSender
        deleteRequestLauncher.launch(IntentSenderRequest.Builder(sender).build())
        return true
    }

    private fun onSongDeleted(song: Song) {
        // Drop it from the live playback queue (the file is gone now).
        mediaController?.let { mc ->
            for (i in 0 until mc.mediaItemCount) {
                if (mc.getMediaItemAt(i).mediaId == song.id.toString()) {
                    mc.removeMediaItem(i)   // ExoPlayer advances if this was the current item
                    break
                }
            }
        }
        currentQueue = currentQueue.filterNot { it.id == song.id }
        viewModel.removeSong(song.id)
        Toast.makeText(this, "Deleted \"${song.title}\"", Toast.LENGTH_SHORT).show()
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

    /** Manual "Check for updates" from Settings — reports when already up to date. */
    fun checkForUpdateManual() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            val info = UpdateManager.checkForUpdate()
            if (info != null) showUpdateDialog(info)
            else Toast.makeText(this@MainActivity, "You're on the latest version", Toast.LENGTH_SHORT).show()
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

    /**
     * The listener is detached while stopped, so track changes that happened in the
     * background (or before this Activity existed) were never seen. Pull the real
     * current item from the session whenever we (re)connect or the library loads.
     */
    private fun syncCurrentSongFromController() {
        val mediaId = mediaController?.currentMediaItem?.mediaId?.takeIf { it.isNotEmpty() } ?: return
        val song = resolveSong(mediaId) ?: return
        if (viewModel.currentSong.value?.id != song.id) viewModel.setCurrentSong(song)
    }

    /** Reflect the controller's real playing state on the mini-bar button. */
    private fun syncPlayPauseIcon() {
        val playing = mediaController?.isPlaying == true
        binding.btnPlayPause.setImageResource(if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        viewModel.setPlaying(playing)
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
                    // Pad the content so the last row clears the mini-bar, measured
                    // from the real layout rather than a hard-coded 170dp.
                    binding.nowPlayingBar.post {
                        val gap = (8 * resources.displayMetrics.density).toInt()
                        val pad = binding.root.height - binding.nowPlayingBar.top + gap
                        if (pad > 0) binding.fragmentContainer.setPadding(0, 0, 0, pad)
                    }
                }
            }
        }

        // Once the song library finishes loading, attempt auto-play.
        // The controller may not be connected yet — maybeAutoPlay handles that.
        viewModel.songs.observe(this) { songs ->
            if (songs.isEmpty()) return@observe
            syncCurrentSongFromController()   // library may load after the controller connects
            maybeAutoPlay()
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
        val autoplay = SettingsManager.isAutoplayOnLaunch(this)

        // Cold start: pick up where we left off (paused unless autoplay is on).
        if (SettingsManager.isResumeLastSession(this) && restoreLastSession(controller, songs, autoplay)) return

        if (autoplay) playSong(songs.random(), songs)
    }

    /**
     * Rebuild the saved queue from the scanned library. Songs deleted since are
     * dropped; if the queue is unchanged its exact shuffle order is reused.
     * Returns false if there's nothing usable to restore.
     */
    private fun restoreLastSession(controller: MediaController, library: List<Song>, play: Boolean): Boolean {
        val saved = PlaybackStateStore.load(this) ?: return false
        val byId = library.associateBy { it.id.toString() }
        val queue = saved.ids.mapNotNull { byId[it] }
        if (queue.isEmpty()) return false

        val currentIndex = saved.currentId?.let { id -> queue.indexOfFirst { it.id.toString() == id } } ?: -1
        val startIndex = currentIndex.coerceAtLeast(0)
        val startPosition = if (currentIndex >= 0) saved.positionMs else 0L

        currentQueue = queue
        pendingQueued.clear()
        // Only valid if no songs went missing (indices would shift otherwise).
        QueueShuffleOrder.pendingRestoreOrder = saved.shuffleOrder?.takeIf { queue.size == saved.ids.size }
        controller.setMediaItems(queue.map { buildMediaItem(it) }, startIndex, startPosition)
        controller.shuffleModeEnabled = saved.shuffle
        controller.repeatMode = saved.repeatMode
        controller.prepare()
        if (play) controller.play()
        viewModel.setCurrentSong(queue[startIndex])
        return true
    }

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            binding.btnPlayPause.setImageResource(
                if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play
            )
            viewModel.setPlaying(isPlaying)
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
            // Consume the user queue up to this item; landing anywhere else means the
            // queue was skipped or finished, so new additions go after the current song.
            val qi = pendingQueued.indexOf(mediaId)
            if (qi >= 0) repeat(qi + 1) { pendingQueued.removeAt(0) } else pendingQueued.clear()
            resolveSong(mediaId)?.let { viewModel.setCurrentSong(it) }
        }
    }

    /**
     * Map a mediaId back to a Song. currentQueue is only populated by playSong() in
     * this Activity instance, so after a rotation/theme change/process restart it's
     * empty while the service keeps playing — fall back to the full library.
     */
    private fun resolveSong(mediaId: String): Song? =
        currentQueue.find { it.id.toString() == mediaId }
            ?: viewModel.songs.value?.find { it.id.toString() == mediaId }

    /**
     * Queue a song to play after the current one and after anything queued before it.
     * Inserting next to its timeline predecessor makes QueueShuffleOrder put it at
     * the same spot in play order, so this works with shuffle on or off.
     */
    fun addToQueue(song: Song) {
        val controller = mediaController ?: return
        if (controller.mediaItemCount == 0) { playSong(song, listOf(song)); return }
        val mediaItem = buildMediaItem(song)
        val lastQueuedIndex = pendingQueued.lastOrNull()?.let { id ->
            (0 until controller.mediaItemCount).firstOrNull { controller.getMediaItemAt(it).mediaId == id }
        }
        val insertAt = (lastQueuedIndex ?: controller.currentMediaItemIndex) + 1
        controller.addMediaItem(insertAt, mediaItem)
        pendingQueued += song.id.toString()
        // Keep currentQueue in sync so onMediaItemTransition can resolve this song
        if (currentQueue.none { it.id == song.id }) {
            currentQueue = currentQueue + song
        }
        Toast.makeText(this, "${song.title} added to queue", Toast.LENGTH_SHORT).show()
    }

    /**
     * Replace the queue and start [song]. [shuffle] true for the library/favourites
     * (and auto-play); false for artist/album drill-downs, which play in order.
     */
    fun playSong(song: Song, queue: List<Song>, shuffle: Boolean = true) {
        currentQueue = queue
        val controller = mediaController ?: return

        val mediaItems = queue.map { buildMediaItem(it) }
        val startIndex = queue.indexOf(song).coerceAtLeast(0)

        pendingQueued.clear()
        // startIndex also reaches QueueShuffleOrder.cloneAndSet, so it plays first when shuffled.
        controller.setMediaItems(mediaItems, startIndex, 0)
        controller.shuffleModeEnabled = shuffle
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
