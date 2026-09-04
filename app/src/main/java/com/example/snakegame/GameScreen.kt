package com.example.snakegame

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/* ============================================================================
 *  NAAGA GAME — Step 4: GAME SCREEN
 *   - 60fps interpolated renderer (logical ticks stay coarse, motion looks smooth)
 *   - deterministic particles / floating score popups (no per-frame allocations)
 *   - swipe-to-steer on the board + neon D-pad
 *   - combo clock, power-up pills, screen shake, countdown, confetti on a new record
 * ========================================================================== */

private const val POPUP_LIFE_NANOS = 900_000_000L

private data class Particle(
    val ox: Float,
    val oy: Float,
    val vx: Float,
    val vy: Float,
    val born: Long,
    val life: Long,
    val color: Color,
    val radius: Float
) {
    fun dead(nanos: Long): Boolean = nanos - born > life
}

private data class FloatText(val text: String, val xPx: Float, val yPx: Float, val born: Long, val color: Color) {
    fun dead(nanos: Long): Boolean = nanos - born > POPUP_LIFE_NANOS
}

@Composable
fun GameScreen(game: SnakeLogic, sound: SoundKit, onBackToMenu: () -> Unit) {
    val prefs = game.prefs
    var showPausePopup by remember { mutableStateOf(false) }
    val frameNanos = remember { mutableStateOf(0L) }
    val particles = remember { mutableStateListOf<Particle>() }
    val popups = remember { mutableStateListOf<FloatText>() }
    val shake = remember { mutableStateOf(0f) }
    var boardPx by remember { mutableStateOf(0f) }
    var lastCountdown by remember { mutableStateOf(0) }
    var scoreScale by remember { mutableStateOf(1f) }
    val animatedScoreScale by animateFloatAsState(scoreScale, tween(130), label = "scorePop")
    val density = LocalDensity.current

    BackHandler {
        if (!game.isGameOver) {
            game.pauseGame()
            showPausePopup = true
        } else {
            onBackToMenu()
        }
    }

    // ---- the tick loop -----------------------------------------------------
    val running = !game.isGameOver && !game.isPaused && game.countdown <= 0
    LaunchedEffect(running, game.tickDelayMs()) {
        while (running) {
            delay(game.tickDelayMs())
            game.tick()
        }
    }

    // ---- countdown (fresh run + resume after pause) -----------------------
    LaunchedEffect(game.countdown) {
        if (game.countdown > 0) {
            sound.play(SoundKit.COUNT, rate = 0.95f + (SnakeLogic.COUNTDOWN_TICKS - game.countdown) * 0.18f)
            delay(560)
            if (game.countdown > 0) game.onCountdownTick()
        } else if (lastCountdown > 0) {
            sound.play(SoundKit.GO)
        }
        lastCountdown = game.countdown
    }

    // ---- every frame: publish a timestamp + prune effects + decay shake --
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos { nanos -> frameNanos.value = nanos }
            if (shake.value > 0.002f) shake.value *= 0.90f else if (shake.value != 0f) shake.value = 0f
            val now = frameNanos.value
            if (particles.isNotEmpty() && particles.last().dead(now)) particles.removeAll { it.dead(now) }
            if (popups.isNotEmpty() && popups.last().dead(now)) popups.removeAll { it.dead(now) }
        }
    }

    // ---- score pop --------------------------------------------------------
    LaunchedEffect(game.score) {
        scoreScale = 1.22f
        delay(110)
        scoreScale = 1f
    }

    // ---- engine events -> sfx + particles + floating text -----------------
    LaunchedEffect(game.fxToken) {
        val fx = game.fx
        if (fx == Fx.NONE) return@LaunchedEffect
        GameFeedback.play(sound, fx, game)

        val tile = if (boardPx > 0f) boardPx / game.boardSize else 0f
        val at = game.fxAt
        val cx = (at.x + 0.5f) * tile
        val cy = (at.y + 0.5f) * tile
        val now = frameNanos.value

        when (fx) {
            Fx.EAT -> spawnBurst(particles, cx, cy, Color(0xFFFF2E63), 12, now, 95f)
            Fx.GOLDEN -> spawnBurst(particles, cx, cy, Color(0xFFFFD400), 22, now, 145f)
            Fx.POISON -> spawnBurst(particles, cx, cy, Color(0xFF9B4DFF), 16, now, 70f)
            Fx.POWERUP -> spawnBurst(particles, cx, cy, Color(0xFF00E5FF), 24, now, 160f)
            Fx.SHIELD -> spawnBurst(particles, cx, cy, Color(0xFF00E5FF), 10, now, 60f)
            Fx.MILESTONE -> spawnBurst(particles, cx, cy, Neon.green, 16, now, 120f)
            Fx.DEATH -> {
                spawnBurst(particles, cx, cy, Neon.danger, 30, now, 210f)
                shake.value = 1f
            }
            Fx.WIN -> spawnBurst(particles, cx, cy, Neon.gold, 36, now, 220f)
            Fx.NONE -> Unit
        }

        val labelY = cy - tile * 0.45f
        when (fx) {
            Fx.EAT, Fx.GOLDEN, Fx.POISON -> {
                val gain = game.lastGain
                popups.add(
                    FloatText(
                        text = if (gain >= 0) "+$gain" else "$gain",
                        xPx = cx - tile, yPx = labelY, born = now,
                        color = if (gain >= 0) Neon.green else Color(0xFF9B4DFF)
                    )
                )
            }
            Fx.POWERUP -> game.lastPowerUpType?.let {
                popups.add(FloatText(it.short, cx - tile, labelY, now, Color(it.rgb)))
            }
            Fx.SHIELD -> popups.add(FloatText("SHIELD!", cx - tile, labelY, now, Neon.cyan))
            Fx.MILESTONE -> popups.add(FloatText("GROW!", cx - tile, labelY, now, Neon.green))
            else -> Unit
        }
    }

    // ---- swipe steering ---------------------------------------------------
    val swipeArea = Modifier.pointerInput(game.boardSize) {
        var accX = 0f
        var accY = 0f
        val threshold = 24.dp.toPx()
        detectDragGestures(
            onDragStart = { accX = 0f; accY = 0f },
            onDrag = { change, drag ->
                accX += drag.x
                accY += drag.y
                if (maxOf(abs(accX), abs(accY)) >= threshold) {
                    val dir = if (abs(accX) > abs(accY)) {
                        if (accX > 0f) Direction.RIGHT else Direction.LEFT
                    } else {
                        if (accY > 0f) Direction.DOWN else Direction.UP
                    }
                    game.changeDirection(dir)
                    if (abs(accX) > abs(accY)) accX = 0f else accY = 0f
                }
                change.consume()
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .background(screenGradient)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            GameHud(
                game = game,
                scoreScale = animatedScoreScale,
                onMenu = {
                    game.pauseGame()
                    showPausePopup = true
                },
                onTogglePause = {
                    if (game.isGameOver) game.restartGame() else game.togglePause()
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ------------------------------------------------ board ---------
            val shieldOn = game.hasEffect(PowerUpType.SHIELD)
            val accent = when {
                game.isGameOver -> Neon.danger
                shieldOn -> Neon.cyan
                game.selectedLevel.wraps -> Neon.blue
                else -> Neon.green
            }

            Box(
                modifier = Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        val amp = shake.value
                        if (amp > 0.002f) {
                            val t = frameNanos.value / 1_000_000f
                            translationX = sin(t * 1.7f) * 16f * amp
                            translationY = cos(t * 2.3f) * 13f * amp
                        }
                    }
                    .shadow(18.dp, RoundedCornerShape(18.dp), spotColor = accent)
                    .border(
                        width = if (shieldOn) 2.5.dp else 2.dp,
                        color = accent.copy(alpha = if (game.isGameOver) 0.9f else 0.75f),
                        shape = RoundedCornerShape(18.dp)
                    )
                    .clip(RoundedCornerShape(18.dp))
                    .background(Color(0xFF070912))
                    .then(swipeArea)
            ) {
                Canvas(modifier = Modifier.fillMaxSize().onSizeChanged { boardPx = it.width.toFloat() }) {
                    drawBoard(game, frameNanos.value, particles, prefs.smoothMotion, prefs.showGrid, prefs.trailEffect)
                }

                // floating score / pickup labels
                Box(modifier = Modifier.matchParentSize()) {
                    popups.forEach { item ->
                        Text(
                            text = item.text,
                            color = item.color,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            maxLines = 1,
                            modifier = Modifier
                                .offset(
                                    x = with(density) { item.xPx.toDp() },
                                    y = with(density) { item.yPx.toDp() }
                                )
                                .graphicsLayer {
                                    val age = (frameNanos.value - item.born) / 1_000_000_000f
                                    val k = (age / 0.9f).coerceIn(0f, 1f)
                                    translationY = -46f * k
                                    alpha = 1f - k * k
                                    scaleX = 1f + 0.25f * (1f - k)
                                    scaleY = 1f + 0.25f * (1f - k)
                                }
                        )
                    }
                }

                if (game.countdown > 0) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Neon.bg.copy(alpha = 0.62f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = game.countdown.toString(),
                                color = Neon.green,
                                fontSize = 92.sp,
                                fontWeight = FontWeight.Black,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.graphicsLayer {
                                    val cycle = (frameNanos.value / 1_000_000_000f) % 0.6f / 0.6f
                                    scaleX = 1.55f - 0.55f * cycle
                                    scaleY = 1.55f - 0.55f * cycle
                                    alpha = 1f - 0.35f * cycle
                                }
                            )
                            Text("GET READY", color = Neon.dim, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.sp)
                            Text(
                                text = game.selectedLevel.blurb,
                                color = Neon.cyan,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    }
                } else if (game.isPaused && !showPausePopup && !game.isGameOver) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(Neon.bg.copy(alpha = 0.7f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("PAUSED", color = Neon.cyan, fontSize = 30.sp, fontWeight = FontWeight.Black, letterSpacing = 4.sp)
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(
                                onClick = { game.resumeGame() },
                                colors = ButtonDefaults.buttonColors(containerColor = Neon.green),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Text("RESUME", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 13.sp)
                            }
                        }
                    }
                }

                if (game.isGameOver) {
                    GameOverOverlay(
                        game = game,
                        onRetry = { game.restartGame() },
                        onMenu = onBackToMenu
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // ------------------------------------------------ d-pad ---------
            DPad(
                enabled = !game.isGameOver && !game.isPaused && game.countdown <= 0,
                onDirection = { dir ->
                    game.changeDirection(dir)
                    sound.play(SoundKit.CLICK, rate = 1.1f, volume = 0.35f)
                }
            )
        }

        if (showPausePopup) {
            NeonDialog(
                icon = "⏸️",
                title = "GAME PAUSED",
                message = "Abandon this run and go back to the main menu? Your score will not count.",
                primaryLabel = "EXIT TO MENU",
                secondaryLabel = "RESUME",
                primaryColor = Neon.danger,
                secondaryColor = Neon.green,
                onDismiss = {
                    showPausePopup = false
                    game.resumeGame()
                },
                onConfirm = {
                    showPausePopup = false
                    game.isPaused = false
                    onBackToMenu()
                }
            )
        }
    }
}

/* ------------------------------- HUD ------------------------------------- */

@Composable
private fun GameHud(
    game: SnakeLogic,
    scoreScale: Float,
    onMenu: () -> Unit,
    onTogglePause: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .shadow(10.dp, RoundedCornerShape(16.dp), spotColor = Neon.green.copy(alpha = 0.6f))
                .border(1.dp, Neon.line, RoundedCornerShape(16.dp)),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1322).copy(alpha = 0.92f)),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HudButton("◀", Neon.panelSoft, onClick = onMenu)
                Spacer(modifier = Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                    Text("SCORE", color = Neon.green, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Text(
                        text = "${game.score}",
                        color = Neon.white,
                        fontSize = 26.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.scale(scoreScale)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("BEST ${game.highScore}", color = Neon.gold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        if (game.multiplier() > 1) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .background(Neon.orange.copy(alpha = 0.22f), RoundedCornerShape(6.dp))
                                    .border(1.dp, Neon.orange, RoundedCornerShape(6.dp))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text("x${game.multiplier()}", color = Neon.orange, fontSize = 10.sp, fontWeight = FontWeight.Black)
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                HudButton(
                    label = when {
                        game.isGameOver -> "↺"
                        game.isPaused -> "▶"
                        else -> "❚❚"
                    },
                    color = if (game.isGameOver) Neon.danger else Neon.panelSoft,
                    onClick = onTogglePause
                )
            }
        }

        // combo clock + level / length / time strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = game.selectedLevel.title,
                color = if (game.selectedLevel.wraps) Neon.blue else Neon.danger,
                fontSize = 10.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp,
                modifier = Modifier
                    .background(Neon.panel, RoundedCornerShape(6.dp))
                    .border(1.dp, Neon.line, RoundedCornerShape(6.dp))
                    .padding(horizontal = 7.dp, vertical = 3.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("LEN ${game.snake.size}", color = Neon.dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.width(10.dp))
            Text(formatMs(game.runTimeMs), color = Neon.dim, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.width(10.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(4.dp)
                    .background(Neon.panelSoft, RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(game.comboClock())
                        .height(4.dp)
                        .background(
                            Brush.horizontalGradient(listOf(Neon.green, Neon.cyan)),
                            RoundedCornerShape(2.dp)
                        )
                )
            }
        }

        val active = game.powerUpTicks
        if (active.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                active.forEach { (type, _) ->
                    val seconds = game.secondsLeftFor(type)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .background(Color(type.rgb).copy(alpha = 0.18f), RoundedCornerShape(8.dp))
                            .border(1.dp, Color(type.rgb).copy(alpha = 0.8f), RoundedCornerShape(8.dp))
                            .padding(vertical = 3.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "${type.label}  ${"%.1f".format(seconds)}s",
                            color = Color(type.rgb),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Black,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun HudButton(label: String, color: Color, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.size(width = 46.dp, height = 40.dp),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        contentPadding = PaddingValues(0.dp),
        shape = RoundedCornerShape(10.dp)
    ) {
        Text(label, color = Neon.white, fontSize = 13.sp, fontWeight = FontWeight.Black)
    }
}

/* ---------------------------- controls (D-pad) ---------------------------- */

/**
 * One plus-shaped pad, 210dp across: the body is painted once (two overlapping
 * rounded bars + centre ring with four scale ticks), and the four arms are the
 * touch targets — the middle stays decorative so it can never be tapped by
 * mistake. Lifted 54dp above the safe-area inset (half a centimetre of extra
 * thumb room) so DOWN never lands on the system navigation / gesture strip.
 */
@Composable
private fun DPad(enabled: Boolean, onDirection: (Direction) -> Unit) {
    val arm = 70.dp
    val bodyRadius = 22.dp

    Box(
        modifier = Modifier
            .padding(top = 14.dp, bottom = 54.dp)
            .size(arm * 3)
            .shadow(20.dp, RoundedCornerShape(26.dp), spotColor = Neon.green.copy(alpha = 0.40f))
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val bar = size.width / 3f
            val r = bodyRadius.toPx()
            val mid = Offset(size.width / 2f, size.height / 2f)
            val body = Brush.verticalGradient(
                0f to Color(0xFF232E47),
                0.55f to Color(0xFF141B2C),
                1f to Color(0xFF090D18)
            )
            drawRoundRect(body, topLeft = Offset(bar, 0f), size = Size(bar, size.height), cornerRadius = CornerRadius(r, r))
            drawRoundRect(body, topLeft = Offset(0f, bar), size = Size(size.width, bar), cornerRadius = CornerRadius(r, r))

            // centre hub: decorative only, never a touch target
            val ring = bar * 0.24f
            drawCircle(color = Color(0xFF06090F), radius = ring, center = mid)
            drawCircle(
                color = Neon.green.copy(alpha = 0.32f),
                radius = ring,
                center = mid,
                style = Stroke(width = 1.dp.toPx())
            )
            // four ticks pointing at each arm -> reads as one instrument
            val tickIn = ring + 5.dp.toPx()
            val tickOut = ring + 13.dp.toPx()
            val tick = 1.5.dp.toPx()
            listOf(
                Offset(mid.x, mid.y - tickOut) to Offset(mid.x, mid.y - tickIn),
                Offset(mid.x, mid.y + tickIn) to Offset(mid.x, mid.y + tickOut),
                Offset(mid.x - tickOut, mid.y) to Offset(mid.x - tickIn, mid.y),
                Offset(mid.x + tickIn, mid.y) to Offset(mid.x + tickOut, mid.y)
            ).forEach { (from, to) ->
                drawLine(Neon.green.copy(alpha = 0.18f), from, to, strokeWidth = tick, cap = StrokeCap.Round)
            }
        }

        DPadButton(Direction.UP, enabled, Modifier.align(Alignment.TopCenter), onDirection)
        DPadButton(Direction.DOWN, enabled, Modifier.align(Alignment.BottomCenter), onDirection)
        DPadButton(Direction.LEFT, enabled, Modifier.align(Alignment.CenterStart), onDirection)
        DPadButton(Direction.RIGHT, enabled, Modifier.align(Alignment.CenterEnd), onDirection)
    }
}

@Composable
private fun DPadButton(
    direction: Direction,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onDirection: (Direction) -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.92f else 1f, tween(90), label = "dpad")
    val keycap = RoundedCornerShape(18.dp)

    // keycap gradient leans on the direction that is being pushed
    val capBrush = when {
        pressed -> Brush.radialGradient(listOf(Neon.green, Color(0xFF00A05A)))
        direction == Direction.UP || direction == Direction.LEFT ->
            Brush.linearGradient(listOf(Color(0xFF2B3A57), Color(0xFF161E30)))
        else -> Brush.linearGradient(listOf(Color(0xFF232F49), Color(0xFF121828)))
    }

    Box(
        modifier = modifier
            .size(70.dp)
            .padding(6.dp)
            .scale(scale)
            .graphicsLayer { alpha = if (enabled) 1f else 0.38f }
            .background(brush = capBrush, shape = keycap)
            .border(1.dp, Neon.green.copy(alpha = if (pressed) 1f else 0.25f), keycap)
            .clickable(enabled = enabled, interactionSource = interactionSource, indication = null) {
                onDirection(direction)
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(26.dp)) {
            val path = Path()
            when (direction) {
                Direction.UP -> {
                    path.moveTo(size.width * 0.1f, size.height * 0.68f)
                    path.lineTo(size.width * 0.5f, size.height * 0.24f)
                    path.lineTo(size.width * 0.9f, size.height * 0.68f)
                }
                Direction.DOWN -> {
                    path.moveTo(size.width * 0.1f, size.height * 0.32f)
                    path.lineTo(size.width * 0.5f, size.height * 0.76f)
                    path.lineTo(size.width * 0.9f, size.height * 0.32f)
                }
                Direction.LEFT -> {
                    path.moveTo(size.width * 0.68f, size.height * 0.1f)
                    path.lineTo(size.width * 0.24f, size.height * 0.5f)
                    path.lineTo(size.width * 0.68f, size.height * 0.9f)
                }
                Direction.RIGHT -> {
                    path.moveTo(size.width * 0.32f, size.height * 0.1f)
                    path.lineTo(size.width * 0.76f, size.height * 0.5f)
                    path.lineTo(size.width * 0.32f, size.height * 0.9f)
                }
            }
            drawPath(
                path,
                if (pressed) Color.Black else Neon.green,
                style = Stroke(width = 3.6.dp.toPx(), cap = StrokeCap.Round)
            )
        }
    }
}

/* ------------------------------ overlays --------------------------------- */

@Composable
private fun GameOverOverlay(game: SnakeLogic, onRetry: () -> Unit, onMenu: () -> Unit) {
    val stats = game.runStats
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Neon.bg.copy(alpha = 0.9f)),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = if (game.isVictory) "BOARD CLEARED" else "GAME OVER",
            color = if (game.isVictory) Neon.gold else Neon.danger,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 2.sp
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (game.isVictory) "You filled every cell. Legend status: unlocked." else "The snake met its end",
            color = Neon.dim,
            fontSize = 11.sp
        )

        if (game.isNewHighScore) {
            Spacer(modifier = Modifier.height(10.dp))
            Text(
                text = "🏆 NEW HIGH SCORE",
                color = Neon.gold,
                fontSize = 13.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .background(Neon.gold.copy(alpha = 0.16f), RoundedCornerShape(8.dp))
                    .border(1.dp, Neon.gold.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("${game.score} PTS", color = Neon.white, fontSize = 34.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)

        Spacer(modifier = Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MiniStat("LENGTH", "${game.snake.size}", Neon.green)
            MiniStat("TIME", formatMs(game.runTimeMs), Neon.cyan)
            MiniStat("COMBO", "x${1 + (game.maxCombo / 3).coerceAtMost(3)}", Neon.orange)
        }
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            MiniStat("BITES", "${stats.foods}", Neon.white)
            MiniStat("GOLD", "${stats.golden}", Neon.gold)
            MiniStat("POISON", "${stats.poison}", Color(0xFF9B4DFF))
            MiniStat("SAVES", "${stats.shieldSaves}", Neon.cyan)
        }

        Spacer(modifier = Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onRetry,
                modifier = Modifier.height(46.dp).width(130.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Neon.green),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("RETRY", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 13.sp)
            }
            Button(
                onClick = onMenu,
                modifier = Modifier.height(46.dp).width(130.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Neon.panelSoft),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("MENU", color = Neon.white, fontWeight = FontWeight.Bold, fontSize = 13.sp)
            }
        }
    }

    if (game.isNewHighScore) ConfettiOverlay()
}

@Composable
private fun MiniStat(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = color, fontSize = 14.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text(label, color = Neon.dim, fontSize = 8.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

/** Deterministic confetti: positions are a pure function of the frame clock. */
@Composable
private fun ConfettiOverlay() {
    val bits = remember {
        List(46) { i ->
            val colors = listOf(Neon.green, Neon.gold, Neon.danger, Neon.cyan, Color(0xFF9B4DFF))
            ConfettiBit(
                xFrac = ((i * 37) % 100) / 100f,
                phase = ((i * 53) % 100) / 100f,
                speed = 0.32f + (i % 7) * 0.05f,
                sway = 14f + (i % 5) * 8f,
                spin = 180f + (i % 6) * 120f,
                size = 5f + (i % 4) * 3f,
                color = colors[i % colors.size]
            )
        }
    }
    val clock = remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        while (true) withFrameNanos { clock.value = it }
    }
    Canvas(modifier = Modifier.fillMaxSize()) {
        val t = clock.value / 1_000_000_000f
        bits.forEach { bit ->
            val prog = ((t * bit.speed + bit.phase) % 1f)
            val x = bit.xFrac * size.width + sin(prog * 6f) * bit.sway
            val y = -30f + prog * (size.height + 60f)
            // pure function of the frame clock -> no per-frame state writes
            drawRoundRect(
                color = bit.color.copy(alpha = 0.85f),
                topLeft = Offset(x, y),
                size = Size(bit.size * (1f + 0.6f * abs(sin(prog * bit.spin * 0.0174f))), bit.size * 1.6f),
                cornerRadius = CornerRadius(2f, 2f)
            )
        }
    }
}

private data class ConfettiBit(
    val xFrac: Float,
    val phase: Float,
    val speed: Float,
    val sway: Float,
    val spin: Float,
    val size: Float,
    val color: Color
)

/* --------------------------- board rendering ----------------------------- */

private fun DrawScope.drawBoard(
    game: SnakeLogic,
    frame: Long,
    particles: List<Particle>,
    smooth: Boolean,
    showGrid: Boolean,
    trail: Boolean
) {
    val n = game.boardSize
    val tile = size.width / n
    val board = game.snake

    // interpolation factor between the previous tick and the current one
    val progress = if (smooth && !game.isGameOver && !game.isPaused && game.countdown <= 0) {
        val elapsed = (frame - game.lastTickNanos) / 1_000_000f
        (elapsed / game.tickDelayMs()).coerceIn(0f, 1f)
    } else {
        1f
    }

    fun center(index: Int): Offset {
        val cur = board[index]
        if (progress >= 1f) return Offset((cur.x + 0.5f) * tile, (cur.y + 0.5f) * tile)
        val prev = game.previousSnake.getOrNull(index) ?: cur
        var dx = cur.x - prev.x
        var dy = cur.y - prev.y
        // take the wrapped short path so a wrap looks like sliding off the edge
        if (dx > n / 2) dx -= n else if (dx < -n / 2) dx += n
        if (dy > n / 2) dy -= n else if (dy < -n / 2) dy += n
        return Offset(
            (cur.x - dx * (1f - progress) + 0.5f) * tile,
            (cur.y - dy * (1f - progress) + 0.5f) * tile
        )
    }

    // ---- grid ----
    if (showGrid) {
        val gridColor = Color(0xFF141A2E)
        var i = 1
        while (i < n) {
            drawLine(gridColor, Offset(i * tile, 0f), Offset(i * tile, size.height), strokeWidth = 1f)
            drawLine(gridColor, Offset(0f, i * tile), Offset(size.width, i * tile), strokeWidth = 1f)
            i++
        }
    }

    // ---- obstacles ----
    game.obstacles.forEach { cell ->
        drawRoundRect(
            color = Color(0xFFFF3366).copy(alpha = 0.85f),
            topLeft = Offset(cell.x * tile + 1f, cell.y * tile + 1f),
            size = Size(tile - 2f, tile - 2f),
            cornerRadius = CornerRadius(tile * 0.28f, tile * 0.28f)
        )
        drawRoundRect(
            color = Color(0xFF3A0D1E),
            topLeft = Offset(cell.x * tile + tile * 0.28f, cell.y * tile + tile * 0.28f),
            size = Size(tile * 0.44f, tile * 0.44f),
            cornerRadius = CornerRadius(tile * 0.12f, tile * 0.12f)
        )
    }

    // ---- food: the staple is always safe; bonuses sit beside it and fade out ----
    run {
        val t = frame / 1_000_000_000f

        fun item(cell: Point, type: FoodType, alpha: Float, phase: Float) {
            val pulse = 0.5f + 0.5f * sin(t * (2f * PI.toFloat() / 1.1f) + phase)
            val foodColor = Color(type.rgb)
            val c = Offset((cell.x + 0.5f) * tile, (cell.y + 0.5f) * tile)
            drawCircle(foodColor.copy(alpha = (0.18f + 0.14f * pulse) * alpha), tile * (0.75f + 0.28f * pulse), c)
            drawCircle(foodColor.copy(alpha = alpha), tile * (0.32f + 0.05f * pulse), c)
            when (type) {
                // rotating spikes so a trap reads differently at a glance
                FoodType.POISON -> {
                    var k = 0
                    while (k < 6) {
                        val ang = k / 6f * 2f * PI.toFloat() + t * 0.9f
                        drawLine(
                            Neon.purple.copy(alpha = alpha),
                            Offset(c.x + cos(ang) * tile * 0.34f, c.y + sin(ang) * tile * 0.34f),
                            Offset(c.x + cos(ang) * tile * 0.56f, c.y + sin(ang) * tile * 0.56f),
                            strokeWidth = tile * 0.08f,
                            cap = StrokeCap.Round
                        )
                        k++
                    }
                }
                FoodType.GOLDEN -> drawCircle(
                    Color.White.copy(alpha = 0.55f * alpha),
                    tile * 0.11f,
                    Offset(c.x - tile * 0.1f, c.y - tile * 0.1f)
                )
                else -> Unit
            }
        }

        item(game.food, FoodType.NORMAL, 1f, 0f)
        game.bonusFoods.forEachIndexed { index, bonus ->
            // fades during the last third of its life -> clearly temporary
            val left = (bonus.ticksLeft / 45f).coerceIn(0.3f, 1f)
            item(bonus.position, bonus.type, left, (index + 1) * 0.9f)
        }
    }

    // ---- power-up on the board (rings + diamond) ----
    game.activePowerUpOnBoard?.let { item ->
        val c = Offset((item.position.x + 0.5f) * tile, (item.position.y + 0.5f) * tile)
        val color = Color(item.type.rgb)
        val spin = (frame / 1_000_000_000f) * 1.4f
        val blink = if (item.ticksLeft < 18 && (frame / 125_000_000L) % 2L == 0L) 0.35f else 1f
        drawCircle(color.copy(alpha = 0.22f * blink), tile * 0.85f, c)
        drawCircle(color.copy(alpha = 0.6f * blink), tile * 0.62f, c, style = Stroke(width = tile * 0.08f))
        val r = tile * 0.3f
        val path = Path().apply {
            val pts = floatArrayOf(0f, -1f, 0.75f, 0f, 0f, 1f, -0.75f, 0f)
            var i = 0
            while (i < 4) {
                val a = (i / 4f) * 2f * PI.toFloat() + spin
                val px = c.x + (pts[i * 2] * cos(a) - pts[i * 2 + 1] * sin(a)) * r
                val py = c.y + (pts[i * 2] * sin(a) + pts[i * 2 + 1] * cos(a)) * r
                if (i == 0) moveTo(px, py) else lineTo(px, py)
                i++
            }
            close()
        }
        drawPath(path, color.copy(alpha = blink))
    }

    // ---- snake: one stroked path = smooth body, then head + eyes ----
    if (board.isNotEmpty()) {
        val pts = ArrayList<Offset>(board.size)
        for (i in board.indices) pts.add(center(i))

        val headColor = when {
            game.hasEffect(PowerUpType.SHIELD) -> Neon.cyan
            game.hasEffect(PowerUpType.SPEED_BOOST) -> Neon.orange
            game.hasEffect(PowerUpType.DOUBLE_SCORE) -> Neon.green
            else -> Neon.green
        }
        val tailColor = Color(0xFF0061FF)

        // bloom
        forEachRun(pts, tile) { run ->
            if (run.size > 1) drawPath(smoothPath(run), headColor.copy(alpha = 0.10f), style = Stroke(width = tile * 1.35f, cap = StrokeCap.Round))
        }
        // body (gradient-ish by drawing per link)
        forEachRun(pts, tile) { run ->
            for (i in run.size - 1 downTo 1) {
                val f = (i.toFloat() / run.size.coerceAtLeast(1)).coerceIn(0f, 1f)
                val linkColor = mixColor(headColor, tailColor, 1f - f)
                val alpha = if (trail) (0.45f + 0.55f * f).coerceIn(0.25f, 1f) else 1f
                drawLine(
                    color = linkColor.copy(alpha = alpha),
                    start = run[i],
                    end = run[i - 1],
                    strokeWidth = tile * 0.72f,
                    cap = StrokeCap.Round
                )
            }
            if (run.size == 1) drawCircle(mixColor(headColor, tailColor, 0f), tile * 0.36f, run[0])
        }
        // spine highlight
        forEachRun(pts, tile) { run ->
            if (run.size > 1) drawPath(smoothPath(run), Color.White.copy(alpha = 0.12f), style = Stroke(width = tile * 0.16f, cap = StrokeCap.Round))
        }

        val head = pts.first()
        drawRoundRect(
            color = headColor,
            topLeft = Offset(head.x - tile * 0.44f, head.y - tile * 0.44f),
            size = Size(tile * 0.88f, tile * 0.88f),
            cornerRadius = CornerRadius(tile * 0.3f, tile * 0.3f)
        )
        if (game.hasEffect(PowerUpType.SHIELD)) {
            drawCircle(Neon.cyan.copy(alpha = 0.75f), tile * 0.72f, head, style = Stroke(width = tile * 0.1f))
        }

        // eyes + tongue follow the heading
        val dir = game.direction
        val eye = tile * 0.11f
        val off = tile * 0.19f
        val perpX = if (dir.dx != 0) 0f else 1f
        val perpY = if (dir.dx != 0) 1f else 0f
        val fwd = tile * 0.13f
        listOf(-1f, 1f).forEach { side ->
            val ex = head.x + dir.dx * fwd + perpX * off * side
            val ey = head.y + dir.dy * fwd + perpY * off * side
            drawCircle(Color.White, eye, Offset(ex, ey))
            drawCircle(Color(0xFF05060A), eye * 0.52f, Offset(ex + dir.dx * 1.5f, ey + dir.dy * 1.5f))
        }
        if (!game.isGameOver) {
            val tongueLen = tile * (0.28f + 0.12f * (sin(frame / 1_000_000_000f * 6f) * 0.5f + 0.5f))
            drawLine(
                Neon.danger,
                Offset(head.x + dir.dx * tile * 0.4f, head.y + dir.dy * tile * 0.4f),
                Offset(head.x + dir.dx * (tile * 0.4f + tongueLen), head.y + dir.dy * (tile * 0.4f + tongueLen)),
                strokeWidth = tile * 0.09f,
                cap = StrokeCap.Round
            )
        }
    }

    // ---- particles ----
    particles.forEach { p ->
        val ageSec = (frame - p.born) / 1_000_000_000f
        val k = (ageSec / (p.life / 1_000_000_000f)).coerceIn(0f, 1f)
        if (k < 1f) {
            val x = p.ox + p.vx * ageSec
            val y = p.oy + p.vy * ageSec + 210f * ageSec * ageSec
            drawCircle(p.color.copy(alpha = (1f - k) * 0.9f), p.radius * (1f - 0.45f * k), Offset(x, y))
        }
    }
}

/** Splits the segment list into continuous runs so wrap teleports never draw a line across the board. */
private inline fun forEachRun(points: List<Offset>, tile: Float, action: (List<Offset>) -> Unit) {
    if (points.isEmpty()) return
    var start = 0
    for (i in 1 until points.size) {
        val jump = abs(points[i].x - points[i - 1].x) > tile * 1.6f || abs(points[i].y - points[i - 1].y) > tile * 1.6f
        if (jump) {
            if (i - start > 0) action(points.subList(start, i))
            start = i
        }
    }
    action(points.subList(start, points.size))
}

private fun smoothPath(points: List<Offset>): Path {
    val path = Path()
    if (points.size == 1) {
        path.moveTo(points[0].x, points[0].y)
        path.lineTo(points[0].x + 0.01f, points[0].y)
        return path
    }
    path.moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) path.lineTo(points[i].x, points[i].y)
    return path
}

private fun mixColor(a: Color, b: Color, t: Float): Color {
    val f = t.coerceIn(0f, 1f)
    return Color(
        red = a.red + (b.red - a.red) * f,
        green = a.green + (b.green - a.green) * f,
        blue = a.blue + (b.blue - a.blue) * f,
        alpha = 1f
    )
}

private fun spawnBurst(
    out: MutableList<Particle>,
    cx: Float,
    cy: Float,
    color: Color,
    count: Int,
    nowNanos: Long,
    speed: Float
) {
    repeat(count) { i ->
        val angle = (i.toFloat() / count) * 2f * PI.toFloat() + (i % 3) * 0.22f
        val v = speed * (0.5f + (i % 5) * 0.16f)
        out.add(
            Particle(
                ox = cx, oy = cy,
                vx = cos(angle) * v,
                vy = sin(angle) * v - speed * 0.25f,
                born = nowNanos,
                life = 380_000_000L + (i % 4) * 90_000_000L,
                color = color,
                radius = 2.5f + (i % 3) * 1.4f
            )
        )
    }
    while (out.size > 110) out.removeAt(0)
}