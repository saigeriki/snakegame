package com.example.snakegame

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlin.math.abs
import kotlin.random.Random

/* ============================================================================
 *  NAAGA GAME — Step 1: ENGINE (pure logic, no Compose UI, no Android UI deps)
 *
 *  Fixed / added vs. the old engine:
 *   - food + power-up spawn from a FREE-CELL list  -> no infinite loop / ANR
 *   - correct tail-follow collision (only the retained body is "solid")
 *   - poison now SHRINKS the snake instead of growing it
 *   - shield really saves you (bounce + time cost) instead of dying next tick
 *   - 2-deep input queue -> responsive double turns
 *   - combo / streak multiplier + auto speed ramp + level milestones
 *   - power-up lifetime expressed in real seconds, not raw ticks
 *   - settings, stats and a top-5 leaderboard persisted
 *   - the staple food is ALWAYS safe; golden/poison are optional timed bonuses
 * ========================================================================== */

enum class ScreenState { SPLASH, MAIN_MENU, LEVEL_SELECT, SETTINGS, STATS, GAME }

enum class Direction(val dx: Int, val dy: Int) {
    UP(0, -1), DOWN(0, 1), LEFT(-1, 0), RIGHT(1, 0);

    val opposite: Direction
        get() = when (this) {
            UP -> DOWN
            DOWN -> UP
            LEFT -> RIGHT
            RIGHT -> LEFT
        }

    /** true when [other] is a legal 90° turn from this direction. */
    fun isTurnTowards(other: Direction): Boolean = this != other && this != other.opposite
}

enum class GameLevel(val title: String, val blurb: String, val wraps: Boolean) {
    OPEN("OPEN", "No walls • infinite wrap", true),
    BORDER("BORDER", "Classic box walls", false),
    OBSTACLES("OBSTACLES", "Random blocks — new layout every run", false),
    MAZE("MAZE", "Corridors + tight gaps", false)
}

enum class FoodType(val points: Int, val growth: Int, val rgb: Long) {
    NORMAL(10, 1, 0xFFFF2E63),
    GOLDEN(30, 2, 0xFFFFD400),
    POISON(-12, -1, 0xFF9B4DFF)
}

enum class PowerUpType(val label: String, val short: String, val rgb: Long, val ticks: Int) {
    SPEED_BOOST("SPEED BOOST", "SPEED", 0xFFFF9900, 34),
    SHIELD("SHIELD ONLINE", "SHIELD", 0xFF00E5FF, 26),
    DOUBLE_SCORE("2X SCORE", "2X SCORE", 0xFF00FF88, 30)
}

data class Point(val x: Int, val y: Int)

data class PowerUpItem(val position: Point, val type: PowerUpType, val ticksLeft: Int)

/**
 * A treat (GOLDEN) or a trap (POISON) that appears BESIDE the normal food.
 * Always optional: ignore it and it fades away on its own after [ticksLeft].
 */
data class BonusFood(val position: Point, val type: FoodType, val ticksLeft: Int)

data class RunStats(
    var foods: Int = 0,
    var golden: Int = 0,
    var poison: Int = 0,
    var powerUps: Int = 0,
    var shieldSaves: Int = 0
)

/** One-shot signals so the UI can play sfx / spawn particles without flows or deps. */
enum class Fx { NONE, EAT, GOLDEN, POISON, POWERUP, SHIELD, DEATH, WIN, MILESTONE }

class SnakeLogic(context: Context) {

    val prefs = SnakePrefs(context)

    private val rng = Random.Default

    val minSnakeLength = 3
    private val comboWindowTicks = 22
    private val milestoneStep = 7

    // ---- navigation & settings -------------------------------------------
    var currentScreen by mutableStateOf(ScreenState.SPLASH)
    var boardSize by mutableStateOf(prefs.boardSize)
        private set
    var selectedLevel by mutableStateOf(prefs.level)
    var speedLevel by mutableStateOf(prefs.speed)

    // ---- board state -------------------------------------------------------
    var snake by mutableStateOf<List<Point>>(emptyList())
        private set
    var previousSnake by mutableStateOf<List<Point>>(emptyList())
        private set
    var direction by mutableStateOf(Direction.UP)
        private set
    var obstacles by mutableStateOf<List<Point>>(emptyList())
        private set

    var food by mutableStateOf(Point(0, 0))
        private set
    var currentFoodType by mutableStateOf(FoodType.NORMAL)
        private set

    /** golden / poison extras -> never replace the staple food, see spawnFood() */
    var bonusFoods by mutableStateOf<List<BonusFood>>(emptyList())
        private set
    var activePowerUpOnBoard by mutableStateOf<PowerUpItem?>(null)
        private set
    var powerUpTicks by mutableStateOf<Map<PowerUpType, Int>>(emptyMap())
        private set

    // ---- run state ---------------------------------------------------------
    var score by mutableStateOf(0)
        private set
    var highScore by mutableStateOf(prefs.highScore)
        private set
    var isNewHighScore by mutableStateOf(false)
        private set
    var isGameOver by mutableStateOf(false)
        private set
    var isVictory by mutableStateOf(false)
        private set
    var isPaused by mutableStateOf(false)
    var countdown by mutableStateOf(0)

    var combo by mutableStateOf(0)
        private set
    var maxCombo by mutableStateOf(0)
        private set
    var ticksPlayed by mutableStateOf(0)
        private set
    var runTimeMs by mutableStateOf(0L)
        private set

    /** timestamp of the last tick so the renderer can interpolate between ticks */
    var lastTickNanos by mutableStateOf(0L)
        private set

    var runStats by mutableStateOf(RunStats())
        private set

    // ---- fx signals for the UI --------------------------------------------
    var fx by mutableStateOf(Fx.NONE)
        private set
    var fxToken by mutableStateOf(0)
        private set
    var fxAt by mutableStateOf(Point(0, 0))
        private set
    var lastGain by mutableStateOf(0)
        private set
    var lastGainMultiplier by mutableStateOf(1)
        private set
    var lastPowerUpType by mutableStateOf<PowerUpType?>(null)
        private set

    private val inputQueue = ArrayDeque<Direction>(3)
    private var pendingGrow = 0
    private var pendingShrink = 0
    private var comboTicksLeft by mutableStateOf(0)
    private var nextMilestone = 8
    private var runSeed = 1L

    // ======================================================================
    //  speed / delay
    // ======================================================================

    /** ms per step. Slider value + length ramp + power-ups all feed into this. */
    fun tickDelayMs(): Long {
        val base = 285L - (speedLevel.coerceIn(1f, 10f) * 20f).toLong()
        val ramp = if (prefs.autoRamp) ((snake.size - minSnakeLength) * 2L).coerceAtMost(52L) else 0L
        var delay = base - ramp
        if (hasEffect(PowerUpType.SPEED_BOOST)) delay /= 2
        return delay.coerceIn(45L, 340L)
    }

    fun multiplier(): Int = 1 + (combo / 3).coerceAtMost(3)

    /** 1f right after a bite, draining to 0f when the streak expires. */
    fun comboClock(): Float = (comboTicksLeft.toFloat() / comboWindowTicks).coerceIn(0f, 1f)

    fun secondsLeftFor(type: PowerUpType): Float =
        ((powerUpTicks[type] ?: 0) * tickDelayMs()) / 1000f

    fun hasEffect(type: PowerUpType): Boolean = (powerUpTicks[type] ?: 0) > 0

    // ======================================================================
    //  lifecycle
    // ======================================================================

    fun restartGame() {
        runSeed = System.currentTimeMillis()
        score = 0
        combo = 0
        maxCombo = 0
        comboTicksLeft = 0
        ticksPlayed = 0
        runTimeMs = 0
        isGameOver = false
        isVictory = false
        isPaused = false
        isNewHighScore = false
        countdown = 0
        powerUpTicks = emptyMap()
        activePowerUpOnBoard = null
        bonusFoods = emptyList()
        pendingGrow = 0
        pendingShrink = 0
        nextMilestone = 8
        lastPowerUpType = null
        inputQueue.clear()
        runStats = RunStats()
        direction = Direction.UP

        val c = boardSize / 2
        snake = listOf(Point(c, c), Point(c, c + 1), Point(c, c + 2))
        previousSnake = snake
        lastTickNanos = System.nanoTime()

        buildObstacles()
        spawnFood()
        startCountdown()
    }

    fun startCountdown() {
        countdown = COUNTDOWN_TICKS
    }

    fun togglePause() {
        if (isGameOver) return
        isPaused = !isPaused
    }

    fun pauseGame() {
        if (!isGameOver) isPaused = true
    }

    fun resumeGame() {
        isPaused = false
        countdown = COUNTDOWN_TICKS
    }

    fun onCountdownTick(): Boolean {
        // returns true while the countdown is still running
        if (countdown <= 0) return false
        countdown--
        return countdown > 0
    }

    fun updateSpeed(value: Float) {
        speedLevel = value
        prefs.speed = value
    }

    fun setLevel(level: GameLevel) {
        selectedLevel = level
        prefs.level = level
        if (isGameOver || snake.isEmpty()) buildObstacles()
    }

    /**
     * Board size lives in a `var boardSize` property, so Kotlin would already
     * generate setBoardSize(I)V for it -> a fun with that name clashes on the
     * JVM. Hence the verb form (same style as changeDirection).
     */
    fun changeBoardSize(size: Int) {
        val safe = size.coerceIn(14, 32)
        if (safe == boardSize) return
        prefs.boardSize = safe
        boardSize = safe
        restartGame()
    }

    fun changeDirection(newDir: Direction) {
        if (isGameOver || isPaused || countdown > 0) return
        val last = inputQueue.lastOrNull() ?: direction
        if (!last.isTurnTowards(newDir)) return
        if (inputQueue.size >= 2) return
        inputQueue.addLast(newDir)
    }

    // ======================================================================
    //  the tick
    // ======================================================================

    fun tick() {
        if (isGameOver || isPaused || countdown > 0) return

        val n = boardSize
        previousSnake = snake

        inputQueue.removeFirstOrNull()?.let { direction = it }
        val dir = direction
        val head = snake.firstOrNull() ?: return

        var nx = head.x + dir.dx
        var ny = head.y + dir.dy
        if (selectedLevel.wraps) {
            nx = floorMod(nx, n)
            ny = floorMod(ny, n)
        }
        val newHead = Point(nx, ny)
        val outOfBounds = !selectedLevel.wraps && (nx < 0 || nx >= n || ny < 0 || ny >= n)

        val willEat = newHead == food

        // growth / shrink are resolved through counters so GOLDEN (+2) and
        // POISON (-1) behave correctly even when they happen back to back
        if (willEat) {
            val g = currentFoodType.growth
            if (g >= 0) pendingGrow += g else pendingShrink += -g
        }
        val growPlanned = if (pendingGrow > 0) 1 else 0
        val shrinkPlanned = if (pendingShrink > 0 && snake.size - 1 > minSnakeLength) 1 else 0
        val keepOld = (snake.size + growPlanned - shrinkPlanned - 1)
            .coerceIn(minSnakeLength - 1, snake.size)

        // only the segments that survive this tick are "solid" -> moving into the
        // cell the tail is vacating is legal, which is how snake is supposed to feel
        val hitsSelf = keepOld >= 1 && snake.subList(0, keepOld).contains(newHead)
        val hitsObstacle = obstacles.contains(newHead)

        if (outOfBounds || hitsSelf || hitsObstacle) {
            if (hasEffect(PowerUpType.SHIELD)) {
                bounceOffShield()
                return
            }
            gameOver()
            return
        }

        if (growPlanned > 0) pendingGrow--
        if (shrinkPlanned > 0) pendingShrink--

        val targetLen = (keepOld + 1).coerceAtMost(maxSnakeLength())
        val next = ArrayList<Point>(targetLen)
        next.add(newHead)
        var i = 0
        while (next.size < targetLen && i < snake.size) {
            next.add(snake[i]); i++
        }
        snake = next

        val boardPower = activePowerUpOnBoard
        if (boardPower != null && newHead == boardPower.position) applyPowerUp(boardPower.type, newHead)

        if (willEat) onFoodEaten(newHead)

        // golden / poison extras: consumed on touch, otherwise they age out
        if (bonusFoods.isNotEmpty()) {
            bonusFoods.firstOrNull { it.position == newHead }?.let { onBonusEaten(it, newHead) }
            bonusFoods = bonusFoods
                .map { it.copy(ticksLeft = it.ticksLeft - 1) }
                .filter { it.ticksLeft > 0 }
        }

        // timers
        ticksPlayed++
        runTimeMs += tickDelayMs()
        lastTickNanos = System.nanoTime()

        if (comboTicksLeft > 0) {
            comboTicksLeft--
            if (comboTicksLeft == 0) combo = 0
        }

        if (powerUpTicks.isNotEmpty()) {
            val updated = powerUpTicks.toMutableMap()
            val it = updated.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                val left = e.value - 1
                if (left <= 0) it.remove() else updated[e.key] = left
            }
            powerUpTicks = updated
        }

        activePowerUpOnBoard?.let { item ->
            val left = item.ticksLeft - 1
            activePowerUpOnBoard = if (left <= 0) null else item.copy(ticksLeft = left)
        }

        if (activePowerUpOnBoard == null && powerUpTicks.isEmpty() && rng.nextInt(110) == 0) {
            spawnPowerUp()
        }

        if (snake.size >= nextMilestone) {
            nextMilestone += milestoneStep
            fxAt = newHead
            emit(Fx.MILESTONE)
        }
    }

    private fun maxSnakeLength(): Int = (boardSize * boardSize - obstacles.size).coerceAtLeast(minSnakeLength)

    // ======================================================================
    //  scoring, food, power-ups
    // ======================================================================

    /** the staple food: always safe, always replaced by a fresh one */
    private fun onFoodEaten(at: Point) {
        fxAt = at
        runStats.foods++
        awardPoints(FoodType.NORMAL)
        bumpCombo()
        emit(Fx.EAT)

        if (rng.nextInt(100) < 12) spawnPowerUp()
        spawnFood()
    }

    /** a golden treat or a poison trap: score + growth apply, and it is removed */
    private fun onBonusEaten(item: BonusFood, at: Point) {
        fxAt = at
        bonusFoods = bonusFoods - item

        val g = item.type.growth
        if (g >= 0) pendingGrow += g else pendingShrink += -g
        awardPoints(item.type)

        when (item.type) {
            FoodType.GOLDEN -> {
                runStats.golden++
                bumpCombo()
                emit(Fx.GOLDEN)
            }
            FoodType.POISON -> {
                runStats.poison++
                combo = 0
                comboTicksLeft = 0
                emit(Fx.POISON)
            }
            else -> {
                runStats.foods++
                bumpCombo()
                emit(Fx.EAT)
            }
        }
    }

    private fun bumpCombo() {
        combo++
        maxCombo = maxOf(maxCombo, combo)
        comboTicksLeft = comboWindowTicks
    }

    private fun awardPoints(type: FoodType) {
        val doubleActive = hasEffect(PowerUpType.DOUBLE_SCORE) && type.points > 0
        val mult = multiplier() * if (doubleActive) 2 else 1
        lastGainMultiplier = mult
        lastGain = type.points * mult
        score = (score + lastGain).coerceAtLeast(0)

        if (score > highScore) {
            highScore = score
            prefs.highScore = score
            if (score > 0) isNewHighScore = true
        }
    }

    private fun applyPowerUp(type: PowerUpType, at: Point) {
        powerUpTicks = powerUpTicks + (type to (powerUpTicks[type] ?: 0) + type.ticks)
        activePowerUpOnBoard = null
        runStats.powerUps++
        lastPowerUpType = type
        fxAt = at
        emit(Fx.POWERUP)
    }

    private fun bounceOffShield() {
        // undo the fatal move, flip heading and pay a little shield time
        direction = direction.opposite
        inputQueue.clear()
        val updated = powerUpTicks.toMutableMap()
        val left = (updated[PowerUpType.SHIELD] ?: 0) - 6
        if (left <= 0) updated.remove(PowerUpType.SHIELD) else updated[PowerUpType.SHIELD] = left
        powerUpTicks = updated
        runStats.shieldSaves++
        lastTickNanos = System.nanoTime()
        emit(Fx.SHIELD)
    }

    private fun gameOver() {
        isGameOver = true
        isVictory = false
        emit(Fx.DEATH)
        prefs.recordRun(
            RunRecord(
                score = score,
                level = selectedLevel.title,
                length = snake.size,
                combo = maxCombo,
                timeMs = runTimeMs,
                atMillis = System.currentTimeMillis()
            )
        )
        prefs.commitRun(snake.size, maxCombo, runStats.foods + runStats.golden, runTimeMs)
    }

    private fun winGame() {
        isGameOver = true
        isVictory = true
        emit(Fx.WIN)
        prefs.recordRun(RunRecord(score, selectedLevel.title, snake.size, maxCombo, runTimeMs, System.currentTimeMillis()))
        prefs.commitRun(snake.size, maxCombo, runStats.foods + runStats.golden, runTimeMs)
    }

    private fun spawnFood() {
        val occupied = HashSet<Point>(snake.size + obstacles.size + 2)
        occupied.addAll(snake)
        occupied.addAll(obstacles)
        activePowerUpOnBoard?.let { occupied.add(it.position) }
        bonusFoods.forEach { occupied.add(it.position) }

        var free = freeCells(occupied)
        if (free.isEmpty()) {
            // if the only cells left hold a fading bonus, they are still playable
            val relaxed = HashSet<Point>(snake.size + obstacles.size)
            relaxed.addAll(snake)
            relaxed.addAll(obstacles)
            free = freeCells(relaxed)
            if (free.isEmpty()) {
                winGame()
                return
            }
        }

        food = free[rng.nextInt(free.size)]
        // the food on the board is ALWAYS safe, so a run can never be blocked
        currentFoodType = FoodType.NORMAL
        maybeSpawnBonus(free)
    }

    /**
     * GOLDEN = 3x points worth chasing, POISON = a trap worth dodging. They are
     * spawned next to the normal food (never instead of it) and expire, so the
     * player is free to ignore them.
     */
    private fun maybeSpawnBonus(freeCells: List<Point>) {
        if (bonusFoods.size >= 2) return
        val roll = rng.nextInt(100)
        val type = when {
            roll < 14 -> FoodType.GOLDEN
            roll < 26 -> FoodType.POISON
            else -> return
        }
        val usable = ArrayList<Point>(freeCells.size)
        for (cell in freeCells) if (cell != food) usable.add(cell)
        if (usable.isEmpty()) return

        val life = if (type == FoodType.POISON) 60 + rng.nextInt(50) else 45 + rng.nextInt(35)
        bonusFoods = bonusFoods + BonusFood(usable[rng.nextInt(usable.size)], type, life)
    }

    private fun spawnPowerUp() {
        if (activePowerUpOnBoard != null) return
        val occupied = HashSet<Point>(snake.size + obstacles.size + 2)
        occupied.addAll(snake)
        occupied.addAll(obstacles)
        occupied.add(food)
        bonusFoods.forEach { occupied.add(it.position) }
        val free = freeCells(occupied)
        if (free.isEmpty()) return
        val cell = free[rng.nextInt(free.size)]
        activePowerUpOnBoard = PowerUpItem(cell, PowerUpType.entries.random(rng), POWERUP_LIFETIME_TICKS)
    }

    private fun freeCells(blocked: Set<Point>): List<Point> {
        val n = boardSize
        val out = ArrayList<Point>(n * n / 2)
        for (y in 0 until n) {
            for (x in 0 until n) {
                val p = Point(x, y)
                if (p !in blocked) out.add(p)
            }
        }
        return out
    }

    // ======================================================================
    //  level geometry
    // ======================================================================

    private fun buildObstacles() {
        obstacles = when (selectedLevel) {
            GameLevel.OPEN, GameLevel.BORDER -> emptyList()
            GameLevel.OBSTACLES -> randomBlocks()
            GameLevel.MAZE -> mazeWalls()
        }
    }

    private fun isNearStart(p: Point): Boolean {
        val c = boardSize / 2
        return p.x in (c - 2)..(c + 2) && p.y in (c - 3)..(c + 3)
    }

    private fun randomBlocks(): List<Point> {
        val n = boardSize
        val local = Random(runSeed)
        val out = LinkedHashSet<Point>()
        val clusters = 3 + ((n - 20) / 4).coerceAtLeast(0)
        repeat(clusters) {
            val horizontal = local.nextBoolean()
            val len = 2 + local.nextInt(3)
            val baseX = 1 + local.nextInt((n - len - 2).coerceAtLeast(1))
            val baseY = 1 + local.nextInt((n - len - 2).coerceAtLeast(1))
            val cells = ArrayList<Point>(len * 2)
            for (k in 0 until len) {
                val p = if (horizontal) Point(baseX + k, baseY) else Point(baseX, baseY + k)
                cells.add(p)
                cells.add(Point(n - 1 - p.x, n - 1 - p.y)) // 180° mirror = fair board
            }
            cells.forEach { p ->
                if (p.x in 0 until n && p.y in 0 until n && !isNearStart(p)) out.add(p)
            }
        }
        return out.toList()
    }

    private fun mazeWalls(): List<Point> {
        val n = boardSize
        val local = Random(runSeed)
        val rows = listOf((n * 0.30f).toInt(), (n * 0.70f).toInt())
        val gapWidth = 4
        val firstGap = 2 + local.nextInt((n - gapWidth - 4).coerceAtLeast(1))
        val secondGap = (firstGap + n / 2) % (n - gapWidth - 2)
        val gaps = listOf(firstGap, secondGap)
        val out = LinkedHashSet<Point>()

        rows.forEachIndexed { index, y ->
            val gap = gaps[index % gaps.size]
            for (x in 1 until n - 1) {
                if (x in gap until (gap + gapWidth)) continue
                out.add(Point(x, y))
            }
        }
        // two short pillars that force a weave
        val pillarY = (n / 2)
        for (y in (pillarY - 2)..(pillarY + 2)) {
            out.add(Point(n / 4, y))
            out.add(Point(3 * n / 4, y))
        }
        return out.filter { it.x in 0 until n && it.y in 0 until n && !isNearStart(it) }.toList()
    }

    private fun floorMod(value: Int, mod: Int): Int {
        val r = value % mod
        return if (r < 0) r + mod else r
    }

    private fun emit(event: Fx) {
        fx = event
        fxToken++
    }

    companion object {
        const val COUNTDOWN_TICKS = 3
        const val POWERUP_LIFETIME_TICKS = 90
    }
}

/** distance helper used by the renderer for particle placement */
fun Point.distanceTo(other: Point): Int = abs(x - other.x) + abs(y - other.y)