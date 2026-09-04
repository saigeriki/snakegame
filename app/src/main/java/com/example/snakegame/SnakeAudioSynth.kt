package com.example.snakegame

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin

/* ============================================================================
 *  NAAGA GAME — runtime chiptune synth
 *
 *  Zero assets: every sound effect (and the music loop) is generated from
 *  oscillators into the app's cache folder on first launch, then loaded by
 *  SoundKit. Nothing to download, nothing to ship, nothing to license —
 *  these are hand-written sine/square/saw waves, not samples.
 *
 *  If you DO drop .ogg/.mp3/.wav files into res/raw later, SoundKit prefers
 *  those and skips the generated ones, so both paths keep working.
 * ========================================================================== */

object SnakeAudioSynth {

    private const val SR = 22050
    private const val TAU = 6.2831855f

    /** base names SoundKit looks for */
    val SOUNDS = listOf(
        "click", "eat", "golden", "poison", "powerup",
        "shield", "death", "win", "count", "go", "bgloop"
    )

    private enum class Wave { SINE, SQUARE, SAW }

    /**
     * Writes every missing sound into [dir] and returns name -> file.
     * Thread-safe enough for our use (called once from an IO coroutine, files
     * are only created, never read back here). Safe to call again after the OS
     * wipes the cache — missing files are simply regenerated.
     */
    fun ensure(dir: File): Map<String, File> {
        if (!dir.exists() && !dir.mkdirs()) return emptyMap()
        val out = HashMap<String, File>()
        for (name in SOUNDS) {
            val file = File(dir, "$name.wav")
            if (!file.exists() || file.length() < 44L) {
                val samples = render(name) ?: continue
                val ok = runCatching { writeWav(file, samples) }.isSuccess
                if (!ok) {
                    runCatching { file.delete() }
                    continue
                }
            }
            if (file.exists() && file.length() > 44L) out[name] = file
        }
        return out
    }

    private fun render(name: String): FloatArray? = when (name) {
        "click" -> tone(0.055, 1000f, 780f, Wave.SQUARE)
        "eat" -> mix(
            tone(0.110, 480f, 940f, Wave.SINE),
            scale(tone(0.060, 1400f, 1900f, Wave.SQUARE), 0.5f)
        )
        "golden" -> seq(
            tone(0.070, 880f, 880f, Wave.SQUARE),
            tone(0.070, 1108f, 1108f, Wave.SQUARE),
            tone(0.070, 1318f, 1318f, Wave.SQUARE),
            tone(0.130, 1760f, 2100f, Wave.SINE)
        )
        "poison" -> mix(
            tone(0.200, 240f, 90f, Wave.SAW, vib = 0.35f, vibHz = 17f),
            scale(noise(0.12), 0.35f)
        )
        "powerup" -> mix(
            tone(0.240, 420f, 1500f, Wave.SINE, vib = 0.12f, vibHz = 22f),
            scale(tone(0.240, 840f, 3000f, Wave.SQUARE), 0.35f)
        )
        "shield" -> mix(
            tone(0.160, 330f, 300f, Wave.SINE),
            scale(noise(0.09, 0.15f), 0.55f),
            tone(0.070, 660f, 660f, Wave.SQUARE)
        )
        "death" -> mix(
            tone(0.550, 420f, 70f, Wave.SAW, vib = 0.25f, vibHz = 9f),
            scale(noise(0.30, 0.25f), 0.5f)
        )
        "win" -> seq(
            tone(0.100, 523.25f, 523.25f, Wave.SQUARE),
            tone(0.100, 659.25f, 659.25f, Wave.SQUARE),
            tone(0.100, 783.99f, 783.99f, Wave.SQUARE),
            tone(0.340, 1046.5f, 1046.5f, Wave.SINE)
        )
        "count" -> tone(0.090, 700f, 700f, Wave.SQUARE)
        "go" -> tone(0.220, 900f, 1500f, Wave.SINE)
        "bgloop" -> musicLoop()
        else -> null
    }

    // ---------------------------------------------------------------- oscs ---

    private fun tone(
        durSec: Double,
        f0: Float,
        f1: Float,
        wave: Wave,
        vib: Float = 0f,
        vibHz: Float = 8f
    ): FloatArray {
        val n = (durSec * SR).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        var phase = 0f
        for (i in 0 until n) {
            val t = i.toFloat() / SR
            val frac = if (n > 1) i.toFloat() / (n - 1) else 0f
            var f = f0 + (f1 - f0) * frac
            if (vib > 0f) f *= 1f + vib * sin(TAU * vibHz * t)
            phase += TAU * f / SR
            out[i] = shape(wave, phase) * envelope(i, n)
        }
        return out
    }

    private fun shape(wave: Wave, phase: Float): Float = when (wave) {
        Wave.SINE -> sin(phase)
        Wave.SQUARE -> if (sin(phase) >= 0f) 1f else -1f
        Wave.SAW -> 2f * ((phase / TAU) - floor(phase / TAU)) - 1f
    }

    /** attack + power decay — keeps the blips punchy instead of clicky */
    private fun envelope(i: Int, n: Int, attack: Float = 0.012f, decay: Float = 2.4f): Float {
        val t = if (n > 1) i.toFloat() / (n - 1) else 0f
        return if (t < attack) t / attack else (1f - t).pow(decay)
    }

    /** deterministic pseudo-noise (no Random -> the file is byte-identical every build) */
    private fun noise(durSec: Double, lowpass: Float = 0.35f): FloatArray {
        val n = (durSec * SR).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        var prev = 0f
        for (i in 0 until n) {
            val raw = sin(i * 12.9898f) * 43758.5453f
            val r = (raw - floor(raw)) * 2f - 1f
            prev += lowpass * (r - prev)
            out[i] = prev * envelope(i, n, 0.004f, 3.4f)
        }
        return out
    }

    private fun scale(src: FloatArray, k: Float): FloatArray {
        val out = FloatArray(src.size)
        for (i in src.indices) out[i] = src[i] * k
        return out
    }

    private fun mix(vararg parts: FloatArray): FloatArray {
        var n = 0
        for (p in parts) if (p.size > n) n = p.size
        val out = FloatArray(n)
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out
    }

    private fun seq(vararg parts: FloatArray): FloatArray {
        var total = 0
        for (p in parts) total += p.size
        val out = FloatArray(total)
        var offset = 0
        for (p in parts) {
            p.copyInto(out, offset)
            offset += p.size
        }
        return out
    }

    // ---------------------------------------------------------------- music --

    /**
     * 4-bar A-minor loop @112bpm: sine bass on the 8ths + square lead one beat
     * later. Everything is written on exact beat boundaries so the MediaPlayer
     * loop point lines up (no audible seam).
     */
    private fun musicLoop(): FloatArray {
        val beat = 60.0 / 112.0
        val bar = beat * 4.0
        val length = (bar * 4.0 * SR).toInt()
        val music = FloatArray(length)
        val bass = doubleArrayOf(110.0, 110.0, 130.81, 146.83)
        val lead = arrayOf(
            doubleArrayOf(440.00, 523.25, 659.25, 523.25),
            doubleArrayOf(440.00, 523.25, 659.25, 783.99),
            doubleArrayOf(415.30, 523.25, 622.25, 523.25),
            doubleArrayOf(392.00, 523.25, 587.33, 783.99)
        )

        for (b in 0 until 4) {
            for (step in 0 until 8) {
                val root = bass[b] * (if (step % 2 == 0) 1.0 else 1.5)
                addAt(
                    music,
                    hit(root.toFloat(), beat * 0.42, Wave.SINE, 0.95f, 2.4f),
                    ((b * bar + step * (beat / 2.0)) * SR).toInt()
                )
            }
        }
        for (b in 0 until 4) {
            for (step in 0 until 4) {
                addAt(
                    music,
                    hit(lead[b][step].toFloat(), beat * 0.40, Wave.SQUARE, 0.22f, 3.6f),
                    ((b * bar + step * beat + beat / 2.0) * SR).toInt()
                )
            }
        }
        return music
    }

    /** exponentially decaying note (chiptune-ish "pluck") */
    private fun hit(freq: Float, durSec: Double, wave: Wave, vol: Float, decay: Float): FloatArray {
        val n = (durSec * SR).toInt().coerceAtLeast(1)
        val out = FloatArray(n)
        var phase = 0f
        for (i in 0 until n) {
            phase += TAU * freq / SR
            val s = shape(wave, phase)
            val t = if (n > 1) i.toFloat() / (n - 1) else 0f
            out[i] = s * exp(-decay * t) * vol
        }
        return out
    }

    private fun addAt(dst: FloatArray, src: FloatArray, at: Int) {
        for (i in src.indices) {
            val j = (at + i) % dst.size
            dst[j] += src[i]
        }
    }

    // ------------------------------------------------------------------ wav --

    /** canonical 44-byte RIFF/WAVE header + little endian 16-bit mono PCM */
    private fun writeWav(file: File, samples: FloatArray) {
        var peak = 0f
        for (s in samples) {
            val a = if (s < 0f) -s else s
            if (a > peak) peak = a
        }
        val gain = if (peak > 0.0001f) 0.85f / peak else 1f

        val pcm = ByteArray(samples.size * 2)
        val data = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN)
        for (s in samples) {
            val v = (s * gain).coerceIn(-1f, 1f) * 32767f
            data.putShort(v.toInt().toShort())
        }

        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toAscii())
        header.putInt(36 + pcm.size)
        header.put("WAVE".toAscii())
        header.put("fmt ".toAscii())
        header.putInt(16)              // PCM fmt chunk size
        header.putShort(1)             // audio format = PCM
        header.putShort(1)             // channels = mono
        header.putInt(SR)              // sample rate
        header.putInt(SR * 2)          // byte rate
        header.putShort(2)             // block align
        header.putShort(16)            // bits per sample
        header.put("data".toAscii())
        header.putInt(pcm.size)

        file.writeBytes(header.array() + pcm)
    }

    private fun String.toAscii(): ByteArray = toByteArray(Charsets.US_ASCII)
}