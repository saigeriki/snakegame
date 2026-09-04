package com.example.snakegame

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.media.SoundPool
import android.net.Uri
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import java.io.File

/* ============================================================================
 *  NAAGA GAME — Step 2: AUDIO + HAPTICS  (asset-free)
 *
 *  Sound source priority:
 *    1. res/raw/<name>.*  -> if you ever drop real files there, they win
 *    2. SnakeAudioSynth   -> generated .wav in cacheDir/naaga_sfx (default)
 *  Either way: nothing here is required to compile or to run, and a missing
 *  sound is silently skipped instead of crashing.
 * ========================================================================== */

class SoundKit(context: Context) {

    private val app = context.applicationContext

    private var pool: SoundPool? = null
    private val ids = HashMap<String, Int>()
    private var music: MediaPlayer? = null
    private var musicVolume = 0.35f
    private val generated = HashMap<String, File>()

    var sfxEnabled = true
    var vibrationEnabled = true
    var musicEnabled = true

    private val vibrator: Vibrator? by lazy {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                (app.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
            } else {
                @Suppress("DEPRECATION")
                app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Renders the missing sound effects into the cache. Call this off the main
     * thread (it is pure math + file IO, a few tens of ms).
     */
    fun prepare() {
        if (resId(EAT) != 0 && resId("bgloop") != 0) return
        val dir = File(app.cacheDir, CACHE_DIR)
        generated.putAll(SnakeAudioSynth.ensure(dir))
    }

    fun init() {
        if (pool != null) return
        val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        val newPool = SoundPool.Builder()
            .setMaxStreams(6)
            .setAudioAttributes(attributes)
            .build()
        pool = newPool

        val keys = listOf(
            CLICK to "click",
            EAT to "eat",
            GOLDEN to "golden",
            POISON to "poison",
            POWERUP to "powerup",
            SHIELD to "shield",
            DEATH to "death",
            WIN to "win",
            COUNT to "count",
            GO to "go"
        )
        keys.forEach { (key, resName) ->
            val id = sampleIdFor(newPool, resName)
            if (id != 0) ids[key] = id
        }
    }

    /** res/raw first, generated cache file second. 0 = nothing available. */
    private fun sampleIdFor(newPool: SoundPool, resName: String): Int {
        val res = resId(resName)
        if (res != 0) {
            val loaded = newPool.load(app, res, 1)
            if (loaded != 0) return loaded
        }
        val file = generated[resName] ?: return 0
        return if (file.exists()) newPool.load(file.absolutePath, 1) else 0
    }

    private fun resId(name: String): Int =
        try {
            app.resources.getIdentifier(name, "raw", app.packageName)
        } catch (_: Exception) {
            0
        }

    fun play(key: String, rate: Float = 1f, volume: Float = 1f) {
        if (!sfxEnabled) return
        val id = ids[key] ?: return
        pool?.play(id, volume, volume, 1, 0, rate.coerceIn(0.5f, 2.5f))
    }

    fun vibrate(ms: Long) {
        if (!vibrationEnabled || ms <= 0L) return
        val v = vibrator ?: return
        try {
            if (!v.hasVibrator()) return
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(ms)
            }
        } catch (_: Exception) {
        }
    }

    fun buzz(pattern: LongArray, repeat: Int = -1) {
        if (!vibrationEnabled) return
        val v = vibrator ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(pattern, repeat))
            } else {
                @Suppress("DEPRECATION")
                v.vibrate(pattern, repeat)
            }
        } catch (_: Exception) {
        }
    }

    fun startMusic() {
        if (!musicEnabled || music != null) return
        try {
            val res = resId("bgloop")
            val player = if (res != 0) {
                MediaPlayer.create(app, res)
            } else {
                val file = generated["bgloop"]
                if (file != null && file.exists()) {
                    MediaPlayer().apply {
                        setDataSource(app, Uri.fromFile(file))
                        setAudioAttributes(audioForMusic())
                        isLooping = true
                        prepare()
                    }
                } else null
            } ?: return
            player.isLooping = true
            player.setVolume(musicVolume, musicVolume)
            player.start()
            music = player
        } catch (_: Exception) {
            music = null
        }
    }

    private fun audioForMusic(): android.media.AudioAttributes =
        android.media.AudioAttributes.Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()

    fun stopMusic() {
        music?.let { mp ->
            try {
                if (mp.isPlaying) mp.pause()
            } catch (_: Exception) {
            }
            try {
                mp.release()
            } catch (_: Exception) {
            }
        }
        music = null
    }

    fun setMusicPlaying(playing: Boolean) {
        if (playing) startMusic() else stopMusic()
    }

    /** menu/dialog: keep the music low while the player is reading */
    fun duck(on: Boolean) {
        val target = if (on) 0.10f else 0.35f
        musicVolume = target
        try {
            music?.setVolume(target, target)
        } catch (_: Exception) {
        }
    }

    fun release() {
        stopMusic()
        pool?.release()
        pool = null
        ids.clear()
    }

    companion object {
        private const val CACHE_DIR = "naaga_sfx"
        const val CLICK = "click"
        const val EAT = "eat"
        const val GOLDEN = "golden"
        const val POISON = "poison"
        const val POWERUP = "powerup"
        const val SHIELD = "shield"
        const val DEATH = "death"
        const val WIN = "win"
        const val COUNT = "count"
        const val GO = "go"
    }
}

/** One place that maps engine events -> sfx + haptics, so the UI stays clean. */
object GameFeedback {

    fun play(sound: SoundKit, fx: Fx, logic: SnakeLogic) {
        when (fx) {
            Fx.EAT -> {
                sound.play(SoundKit.EAT, rate = 0.95f + (logic.combo * 0.03f))
                sound.vibrate(35)
            }
            Fx.GOLDEN -> {
                sound.play(SoundKit.GOLDEN)
                sound.vibrate(60)
            }
            Fx.POISON -> {
                sound.play(SoundKit.POISON)
                sound.buzz(longArrayOf(0, 45, 60, 45))
            }
            Fx.POWERUP -> {
                sound.play(SoundKit.POWERUP)
                sound.vibrate(80)
            }
            Fx.SHIELD -> {
                sound.play(SoundKit.SHIELD)
                sound.vibrate(90)
            }
            Fx.MILESTONE -> {
                sound.play(SoundKit.COUNT, rate = 1.5f)
                sound.vibrate(25)
            }
            Fx.DEATH -> {
                sound.play(SoundKit.DEATH)
                sound.buzz(longArrayOf(0, 120, 60, 200))
            }
            Fx.WIN -> {
                sound.play(SoundKit.WIN)
                sound.buzz(longArrayOf(0, 60, 40, 60, 40, 120))
            }
            Fx.NONE -> Unit
        }
    }
}