package com.radiolauncher

import android.annotation.TargetApi
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Build
import androidx.annotation.RequiresApi

/** Snapshot of what's currently playing, for the media widget. */
data class MediaInfo(
    val title: String,
    val artist: String,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val packageName: String
)

/**
 * Watches the active MediaSession (Spotify, YouTube Music, radio apps, etc.) and
 * reports now-playing metadata + play state, plus exposes transport controls.
 *
 * Requires Android 5.0+ (MediaSessionManager) AND the user granting Notification
 * access. On failure it reports `null` (nothing to show) and the UI falls back to
 * the "launch a media app" state.
 */
@RequiresApi(Build.VERSION_CODES.LOLLIPOP)
class MediaMonitor(
    private val context: Context,
    private val onUpdate: (MediaInfo?) -> Unit
) {
    private val listenerComponent = ComponentName(context, MediaNotificationListener::class.java)
    private val sessionManager =
        context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager

    private var controller: MediaController? = null

    private val sessionsChangedListener =
        MediaSessionManager.OnActiveSessionsChangedListener { controllers ->
            bindTo(pickActive(controllers))
        }

    private val controllerCallback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = emit()
        override fun onPlaybackStateChanged(state: PlaybackState?) = emit()
        override fun onSessionDestroyed() = bindTo(null)
    }

    fun start() {
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                sessionsChangedListener, listenerComponent
            )
            bindTo(pickActive(sessionManager.getActiveSessions(listenerComponent)))
        } catch (e: SecurityException) {
            // Notification access not granted yet.
            onUpdate(null)
        }
    }

    fun stop() {
        try {
            sessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener)
        } catch (e: Exception) {
            // ignore
        }
        controller?.unregisterCallback(controllerCallback)
        controller = null
    }

    fun playPause() {
        val c = controller ?: return
        val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
        if (playing) c.transportControls.pause() else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun previous() = controller?.transportControls?.skipToPrevious()

    fun seekTo(ms: Long) {
        controller?.transportControls?.seekTo(ms)
    }

    /** Track length in ms, or 0 if unknown (e.g. live radio streams). */
    fun durationMs(): Long =
        controller?.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L

    /** Estimated current position in ms, extrapolated while playing. */
    fun positionMs(): Long {
        val s = controller?.playbackState ?: return 0L
        var pos = s.position
        if (s.state == PlaybackState.STATE_PLAYING) {
            val delta = android.os.SystemClock.elapsedRealtime() - s.lastPositionUpdateTime
            pos += (delta * s.playbackSpeed).toLong()
        }
        return pos.coerceAtLeast(0L)
    }

    // ---- internals ---------------------------------------------------------

    /** Prefer a session that is actively playing; otherwise the first available. */
    private fun pickActive(controllers: List<MediaController>?): MediaController? {
        if (controllers.isNullOrEmpty()) return null
        return controllers.firstOrNull {
            it.playbackState?.state == PlaybackState.STATE_PLAYING
        } ?: controllers.first()
    }

    private fun bindTo(newController: MediaController?) {
        if (newController?.sessionToken == controller?.sessionToken) {
            emit()
            return
        }
        controller?.unregisterCallback(controllerCallback)
        controller = newController
        controller?.registerCallback(controllerCallback)
        emit()
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private fun emit() {
        val c = controller
        if (c == null) {
            onUpdate(null)
            return
        }
        val md = c.metadata
        val state = c.playbackState
        val title = md?.getText(MediaMetadata.METADATA_KEY_TITLE)?.toString().orEmpty()
        val artist = (md?.getText(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getText(MediaMetadata.METADATA_KEY_ALBUM_ARTIST))?.toString().orEmpty()
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        val isPlaying = state?.state == PlaybackState.STATE_PLAYING

        if (title.isEmpty() && artist.isEmpty()) {
            onUpdate(null)
            return
        }
        onUpdate(MediaInfo(title, artist, art, isPlaying, c.packageName))
    }
}
