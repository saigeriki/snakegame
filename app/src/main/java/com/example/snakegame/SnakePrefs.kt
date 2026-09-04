package com.example.snakegame

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

data class RunRecord(
    val score: Int,
    val level: String,
    val length: Int,
    val combo: Int,
    val timeMs: Long,
    val atMillis: Long
)

/* ============================================================================
 *  NAAGA GAME — Step 1b: PERSISTENCE
 *  Every setting + the high score + a top-5 local leaderboard + lifetime stats
 *  live here. Observable (Compose) reads, SharedPreferences writes.
 * ========================================================================== */

class SnakePrefs(context: Context) {

    private val app = context.applicationContext
    private val p = app.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- observable mirrors ------------------------------------------------

    private val _highScore = mutableStateOf(p.getInt(KEY_HIGH, 0))
    var highScore: Int
        get() = _highScore.value
        set(value) {
            if (value == _highScore.value) return
            _highScore.value = value
            p.edit().putInt(KEY_HIGH, value).apply()
        }

    private val _boardSize = mutableStateOf(p.getInt(KEY_BOARD, 20))
    var boardSize: Int
        get() = _boardSize.value.coerceIn(14, 32)
        set(value) {
            val v = value.coerceIn(14, 32)
            _boardSize.value = v
            p.edit().putInt(KEY_BOARD, v).apply()
        }

    private val _speed = mutableStateOf(p.getFloat(KEY_SPEED, 5f))
    var speed: Float
        get() = _speed.value
        set(value) {
            val v = value.coerceIn(1f, 10f)
            _speed.value = v
            p.edit().putFloat(KEY_SPEED, v).apply()
        }

    private val _level = mutableStateOf(runCatching { GameLevel.valueOf(p.getString(KEY_LEVEL, null) ?: "") }
        .getOrDefault(GameLevel.BORDER))
    var level: GameLevel
        get() = _level.value
        set(value) {
            _level.value = value
            p.edit().putString(KEY_LEVEL, value.name).apply()
        }

    private val _soundOn = mutableStateOf(p.getBoolean(KEY_SOUND, true))
    var soundOn: Boolean
        get() = _soundOn.value
        set(value) {
            _soundOn.value = value
            p.edit().putBoolean(KEY_SOUND, value).apply()
        }

    private val _musicOn = mutableStateOf(p.getBoolean(KEY_MUSIC, true))
    var musicOn: Boolean
        get() = _musicOn.value
        set(value) {
            _musicOn.value = value
            p.edit().putBoolean(KEY_MUSIC, value).apply()
        }

    private val _vibrationOn = mutableStateOf(p.getBoolean(KEY_VIBE, true))
    var vibrationOn: Boolean
        get() = _vibrationOn.value
        set(value) {
            _vibrationOn.value = value
            p.edit().putBoolean(KEY_VIBE, value).apply()
        }

    private val _smoothMotion = mutableStateOf(p.getBoolean(KEY_SMOOTH, true))
    var smoothMotion: Boolean
        get() = _smoothMotion.value
        set(value) {
            _smoothMotion.value = value
            p.edit().putBoolean(KEY_SMOOTH, value).apply()
        }

    private val _trail = mutableStateOf(p.getBoolean(KEY_TRAIL, true))
    var trailEffect: Boolean
        get() = _trail.value
        set(value) {
            _trail.value = value
            p.edit().putBoolean(KEY_TRAIL, value).apply()
        }

    private val _autoRamp = mutableStateOf(p.getBoolean(KEY_RAMP, true))
    var autoRamp: Boolean
        get() = _autoRamp.value
        set(value) {
            _autoRamp.value = value
            p.edit().putBoolean(KEY_RAMP, value).apply()
        }

    private val _showGrid = mutableStateOf(p.getBoolean(KEY_GRID, true))
    var showGrid: Boolean
        get() = _showGrid.value
        set(value) {
            _showGrid.value = value
            p.edit().putBoolean(KEY_GRID, value).apply()
        }

    // ---- lifetime stats ----------------------------------------------------

    private val _games = mutableStateOf(p.getInt(KEY_GAMES, 0))
    var gamesPlayed: Int
        get() = _games.value
        private set(value) {
            _games.value = value
            p.edit().putInt(KEY_GAMES, value).apply()
        }

    private val _bestLength = mutableStateOf(p.getInt(KEY_BEST_LEN, 3))
    var bestLength: Int
        get() = _bestLength.value
        private set(value) {
            if (value <= _bestLength.value) return
            _bestLength.value = value
            p.edit().putInt(KEY_BEST_LEN, value).apply()
        }

    private val _bestCombo = mutableStateOf(p.getInt(KEY_BEST_COMBO, 0))
    var bestCombo: Int
        get() = _bestCombo.value
        private set(value) {
            if (value <= _bestCombo.value) return
            _bestCombo.value = value
            p.edit().putInt(KEY_BEST_COMBO, value).apply()
        }

    private val _eaten = mutableStateOf(p.getInt(KEY_EATEN, 0))
    var totalFoodEaten: Int
        get() = _eaten.value
        private set(value) {
            _eaten.value = value
            p.edit().putInt(KEY_EATEN, value).apply()
        }

    private val _longestRunMs = mutableStateOf(p.getLong(KEY_LONGEST_RUN, 0L))
    var longestRunMs: Long
        get() = _longestRunMs.value
        private set(value) {
            if (value <= _longestRunMs.value) return
            _longestRunMs.value = value
            p.edit().putLong(KEY_LONGEST_RUN, value).apply()
        }

    fun commitRun(length: Int, combo: Int, foodCount: Int, timeMs: Long) {
        gamesPlayed = _games.value + 1
        bestLength = length
        bestCombo = combo
        totalFoodEaten = _eaten.value + foodCount
        longestRunMs = timeMs
    }

    // ---- leaderboard (top 5, stored as delimited text -> no JSON dep) ------

    private val _leaderboard = mutableStateOf(loadLeaderboard())
    val leaderboard: List<RunRecord>
        get() = _leaderboard.value

    private fun loadLeaderboard(): List<RunRecord> {
        val raw = p.getString(KEY_LEADER, "").orEmpty()
        if (raw.isBlank()) return emptyList()
        return raw.split("\n").mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size < 6) return@mapNotNull null
            RunRecord(
                score = parts[0].toIntOrNull() ?: return@mapNotNull null,
                level = parts[1],
                length = parts[2].toIntOrNull() ?: 0,
                combo = parts[3].toIntOrNull() ?: 0,
                timeMs = parts[4].toLongOrNull() ?: 0L,
                atMillis = parts[5].toLongOrNull() ?: 0L
            )
        }
    }

    fun recordRun(record: RunRecord) {
        val next = (listOf(record) + _leaderboard.value)
            .sortedWith(compareByDescending<RunRecord> { it.score }.thenByDescending { it.length })
            .take(5)
        _leaderboard.value = next
        val raw = next.joinToString("\n") {
            "${it.score}|${it.level}|${it.length}|${it.combo}|${it.timeMs}|${it.atMillis}"
        }
        p.edit().putString(KEY_LEADER, raw).apply()
    }

    fun resetStats() {
        p.edit()
            .remove(KEY_GAMES).remove(KEY_BEST_LEN).remove(KEY_BEST_COMBO)
            .remove(KEY_EATEN).remove(KEY_LONGEST_RUN).remove(KEY_LEADER).remove(KEY_HIGH)
            .apply()
        _games.value = 0
        _bestLength.value = 3
        _bestCombo.value = 0
        _eaten.value = 0
        _longestRunMs.value = 0L
        _highScore.value = 0
        _leaderboard.value = emptyList()
    }

    private companion object {
        const val FILE = "SnakeGamePrefs"
        const val KEY_HIGH = "HIGH_SCORE"
        const val KEY_BOARD = "BOARD_SIZE"
        const val KEY_SPEED = "SPEED"
        const val KEY_LEVEL = "LEVEL"
        const val KEY_SOUND = "SOUND"
        const val KEY_MUSIC = "MUSIC"
        const val KEY_VIBE = "VIBRATION"
        const val KEY_SMOOTH = "SMOOTH"
        const val KEY_TRAIL = "TRAIL"
        const val KEY_RAMP = "AUTO_RAMP"
        const val KEY_GRID = "GRID"
        const val KEY_GAMES = "GAMES"
        const val KEY_BEST_LEN = "BEST_LEN"
        const val KEY_BEST_COMBO = "BEST_COMBO"
        const val KEY_EATEN = "EATEN"
        const val KEY_LONGEST_RUN = "LONGEST_RUN"
        const val KEY_LEADER = "LEADERBOARD"
    }
}