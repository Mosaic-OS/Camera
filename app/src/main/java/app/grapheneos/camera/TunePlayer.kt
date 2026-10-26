package app.grapheneos.camera

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.VisibleForTesting
import androidx.core.net.toUri
import app.grapheneos.camera.ui.activities.MainActivity

// Every MediaPlayer call is a synchronous binder transaction into mediaserver, which can take
// seconds to answer. Made from the main thread, that wait was reported as an ANR, so the players
// are created, prepared and played here. A player also delivers its events to the thread that
// created it, and the prepared event calls into mediaserver too.
private val soundHandler = HandlerThread("TunePlayer").run {
    start()
    Handler(looper)
}

private fun prepareMediaPlayer(
    context: Context,
    resid: Int,
    listener: MediaPlayer.OnPreparedListener
) {
    soundHandler.post {
        TunePlayer.newMediaPlayer().apply {
            setDataSource(
                context,
                "android.resource://${context.packageName}/$resid".toUri()
            )
            setOnPreparedListener(listener)
            prepareAsync()
        }
    }
}

open class TunePlayer(val context: MainActivity) {

    // Set on the sound thread once loaded. The main thread only hands them back there to play.
    @Volatile
    private var shutterPlayer: MediaPlayer? = null

    @Volatile
    private var fSPlayer: MediaPlayer? = null

    @Volatile
    private var tIPlayer: MediaPlayer? = null
    @Volatile
    private var tCPlayer: MediaPlayer? = null

    @Volatile
    private var vRecPlayer: MediaPlayer? = null
    @Volatile
    private var vStopPlayer: MediaPlayer? = null

    // Set by release(): nothing plays afterwards, and a player that finishes loading later is released.
    @Volatile
    private var released = false

    init {
        prepareMediaPlayer(context, R.raw.image_shot) { player -> shutterPlayer = adopt(player) }

        prepareMediaPlayer(context, R.raw.focus_start) { player -> fSPlayer = adopt(player) }

        prepareMediaPlayer(context, R.raw.timer_increment) { player -> tIPlayer = adopt(player) }
        prepareMediaPlayer(context, R.raw.timer_final_second) { player -> tCPlayer = adopt(player) }

        prepareMediaPlayer(context, R.raw.video_start) { player -> vRecPlayer = adopt(player) }
        prepareMediaPlayer(context, R.raw.video_stop) { player -> vStopPlayer = adopt(player) }
    }

    // Runs on the sound thread, which delivers the prepared events.
    private fun adopt(player: MediaPlayer): MediaPlayer? {
        if (!released) return player
        player.release()
        return null
    }

    private fun shouldNotPlayTune(): Boolean {
        return released || !context.camConfig.enableCameraSounds
    }

    private fun play(player: MediaPlayer?) {
        if (shouldNotPlayTune() || player == null) return
        soundHandler.post {
            player.seekTo(0)
            player.start()
        }
    }

    fun playShutterSound() {
        play(shutterPlayer)
    }

    open fun playVRStartSound(handler: Handler, onPlayed: Runnable) {
        val player = vRecPlayer
        if (shouldNotPlayTune() || player == null) {
            onPlayed.run()
            return
        }
        soundHandler.post {
            // An unhandled playback error is also delivered to the completion listener, and a
            // failed sound must still deliver onPlayed once: the recording start is waiting on it.
            var delivered = false
            val deliverOnce = MediaPlayer.OnCompletionListener {
                if (!delivered) {
                    delivered = true
                    player.setOnCompletionListener(null)
                    player.setOnErrorListener(null)
                    handler.postDelayed(onPlayed, 10)
                }
            }
            player.setOnCompletionListener(deliverOnce)
            player.setOnErrorListener { _, _, _ ->
                deliverOnce.onCompletion(player)
                true
            }
            player.seekTo(0)
            player.start()
        }
    }

    fun playVRStopSound() {
        play(vStopPlayer)
    }

    fun playTimerIncrementSound() {
        play(tIPlayer)
    }

    fun playTimerFinalSSound() {
        play(tCPlayer)
    }

    fun playFocusStartSound() {
        play(fSPlayer)
    }

    companion object {
        // A stalled mediaserver can't be produced on demand, so tests swap in a player whose
        // calls into it block
        @VisibleForTesting
        @Volatile
        var newMediaPlayer: () -> MediaPlayer = ::MediaPlayer
    }

    // The players are only used on the sound thread, so they are released there, after any queued sound.
    fun release() {
        released = true
        soundHandler.post {
            listOf(shutterPlayer, fSPlayer, tIPlayer, tCPlayer, vRecPlayer, vStopPlayer).forEach { it?.release() }
            shutterPlayer = null
            fSPlayer = null
            tIPlayer = null
            tCPlayer = null
            vRecPlayer = null
            vStopPlayer = null
        }
    }
}
