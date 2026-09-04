package com.example.snakegame

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.sin

/* ============================================================================
 *  NAAGA GAME — Step 3: MENU / SETTINGS / STATS
 * ========================================================================== */

object Neon {
    val bg = Color(0xFF05060A)
    val panel = Color(0xFF0F1322)
    val panelSoft = Color(0xFF14192B)
    val line = Color(0xFF1E2640)
    val green = Color(0xFF00FF88)
    val cyan = Color(0xFF00E5FF)
    val blue = Color(0xFF00B0FF)
    val danger = Color(0xFFFF0055)
    val gold = Color(0xFFFFD700)
    val orange = Color(0xFFFF9900)
    val purple = Color(0xFF9B4DFF)
    val dim = Color(0xFF8A93B0)
    val white = Color(0xFFF3F6FF)
}

val screenGradient = Brush.verticalGradient(listOf(Color(0xFF0A0C1B), Color(0xFF030408)))

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        delay(2200)
        onFinished()
    }

    val t = rememberInfiniteTransition(label = "logo")
    val slide by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing), RepeatMode.Restart), label = "slide")

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(screenGradient),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Canvas(modifier = Modifier.size(120.dp)) {
                val segments = 9
                for (i in segments - 1 downTo 0) {
                    val phase = (i / segments.toFloat() + slide) % 1f
                    val x = size.width * (0.08f + 0.84f * phase)
                    val y = size.height * (0.5f + sin(phase * 2f * PI.toFloat()) * 0.28f)
                    val r = (size.width * 0.075f) * (1f - i / (segments * 1.7f))
                    drawCircle(
                        color = if (i == 0) Neon.green else Color(0xFF00C96A).copy(alpha = 1f - i * 0.07f),
                        radius = r,
                        center = Offset(x, y)
                    )
                }
                drawCircle(Neon.danger, size.width * 0.045f, Offset(size.width * 0.86f, size.height * 0.36f))
            }

            Spacer(modifier = Modifier.height(18.dp))
            Text("NAAGA GAME", fontSize = 40.sp, fontWeight = FontWeight.Black, color = Neon.green, letterSpacing = 5.sp)
            Spacer(modifier = Modifier.height(14.dp))
            Text("DEVELOPED BY", fontSize = 11.sp, color = Neon.dim, letterSpacing = 3.sp)
            Text("RS NOVA Studios", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Neon.cyan, fontFamily = FontFamily.Serif)
            Spacer(modifier = Modifier.height(26.dp))
            Box(
                modifier = Modifier
                    .width(150.dp)
                    .height(3.dp)
                    .background(Neon.line, RoundedCornerShape(2.dp))
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(slide)
                        .height(3.dp)
                        .background(Neon.green, RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

@Composable
fun MainMenuScreen(
    game: SnakeLogic,
    sound: SoundKit,
    onPlay: () -> Unit,
    onLevels: () -> Unit,
    onSettings: () -> Unit,
    onStats: () -> Unit,
    onQuit: () -> Unit
) {
    MenuScaffold(title = "NAAGA GAME", subtitle = "RS NOVA Studios") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            StatChip("HIGH SCORE", "${game.highScore}", Neon.gold, Modifier.weight(1f))
            StatChip("BEST LENGTH", "${game.prefs.bestLength}", Neon.cyan, Modifier.weight(1f))
            StatChip("GAMES", "${game.prefs.gamesPlayed}", Neon.green, Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(14.dp))

        NeonCard {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("GAME SPEED", color = Neon.white, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                Text(speedLabel(game.speedLevel), color = Neon.green, fontSize = 12.sp, fontWeight = FontWeight.Black)
            }
            Slider(
                value = game.speedLevel,
                onValueChange = {
                    game.updateSpeed(it)
                    sound.play(SoundKit.CLICK, rate = 1f + it * 0.03f)
                },
                valueRange = 1f..10f,
                steps = 8,
                modifier = Modifier.padding(horizontal = 12.dp),
                colors = SliderDefaults.colors(
                    thumbColor = Neon.green,
                    activeTrackColor = Neon.green,
                    inactiveTrackColor = Neon.panelSoft
                )
            )
            Text(
                text = if (game.prefs.autoRamp) "Auto-ramp ON — speeds up as you grow" else "Auto-ramp OFF — constant tempo",
                color = Neon.dim,
                fontSize = 11.sp,
                modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GameLevel.entries.forEach { level ->
                val selected = game.selectedLevel == level
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(
                            if (selected) Neon.green.copy(alpha = 0.16f) else Neon.panelSoft,
                            RoundedCornerShape(10.dp)
                        )
                        .border(
                            1.dp,
                            if (selected) Neon.green else Neon.line,
                            RoundedCornerShape(10.dp)
                        )
                        .clickable {
                            game.setLevel(level)
                            sound.play(SoundKit.CLICK)
                        }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = level.title.take(4),
                        color = if (selected) Neon.green else Neon.dim,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        MenuButton("PLAY  GAME", Neon.green, Color.Black, onClick = onPlay)
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MenuButton("LEVELS", Neon.panelSoft, Neon.white, Modifier.weight(1f), onClick = onLevels)
            MenuButton("STATS", Neon.panelSoft, Neon.white, Modifier.weight(1f), onClick = onStats)
        }
        Spacer(modifier = Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
            MenuButton("SETTINGS", Neon.panel, Neon.cyan, Modifier.weight(1f), onClick = onSettings)
            MenuButton("QUIT", Neon.danger.copy(alpha = 0.18f), Neon.danger, Modifier.weight(1f), onClick = onQuit)
        }

        Spacer(modifier = Modifier.height(14.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TogglePill("SFX", game.prefs.soundOn) {
                game.prefs.soundOn = it
                sound.sfxEnabled = it
                if (it) sound.play(SoundKit.CLICK)
            }
            Spacer(modifier = Modifier.width(8.dp))
            TogglePill("MUSIC", game.prefs.musicOn) {
                game.prefs.musicOn = it
                sound.musicEnabled = it
                sound.setMusicPlaying(it)
            }
            Spacer(modifier = Modifier.width(8.dp))
            TogglePill("HAPTICS", game.prefs.vibrationOn) {
                game.prefs.vibrationOn = it
                sound.vibrationEnabled = it
                if (it) sound.vibrate(40)
            }
        }
    }
}

@Composable
fun LevelSelectScreen(game: SnakeLogic, sound: SoundKit, onBack: () -> Unit) {
    MenuScaffold(title = "SELECT LEVEL", subtitle = "${game.boardSize}x${game.boardSize} board", onBack = onBack) {
        GameLevel.entries.forEach { level ->
            val selected = game.selectedLevel == level
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)
                    .border(
                        if (selected) 2.dp else 1.dp,
                        if (selected) Neon.green else Neon.line,
                        RoundedCornerShape(16.dp)
                    )
                    .clickable {
                        game.setLevel(level)
                        sound.play(SoundKit.CLICK)
                    },
                colors = CardDefaults.cardColors(
                    containerColor = if (selected) Neon.green.copy(alpha = 0.12f) else Color(0xFF0F1322)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LevelPreview(level, Modifier.size(56.dp))
                    Spacer(modifier = Modifier.width(14.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(level.title, fontSize = 17.sp, fontWeight = FontWeight.Black, color = if (selected) Neon.green else Neon.white)
                        Text(level.blurb, fontSize = 11.sp, color = Neon.dim, lineHeight = 14.sp)
                    }
                    if (selected) Text("ACTIVE", color = Neon.green, fontWeight = FontWeight.Bold, fontSize = 10.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        MenuButton("START HERE", Neon.green, Color.Black, onClick = {
            game.restartGame()
            game.currentScreen = ScreenState.GAME
        })
    }
}

@Composable
private fun LevelPreview(level: GameLevel, modifier: Modifier = Modifier) {
    val cell = 4f
    Box(
        modifier = modifier
            .background(Neon.bg, RoundedCornerShape(8.dp))
            .border(1.dp, if (level.wraps) Neon.blue else Neon.line, RoundedCornerShape(8.dp))
    ) {
        Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
            fun block(cx: Float, cy: Float, color: Color) {
                drawRoundRect(
                    color = color,
                    topLeft = Offset(cx * cell, cy * cell),
                    size = Size(cell - 1f, cell - 1f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(1f, 1f)
                )
            }
            val snake = listOf(7f to 6f, 6f to 6f, 5f to 6f)
            snake.forEachIndexed { i, (x, y) -> block(x, y, if (i == 0) Neon.green else Neon.cyan) }
            when (level) {
                GameLevel.OPEN -> {
                    drawLine(Neon.blue, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1f, cap = StrokeCap.Round)
                    block(1f, 1f, Neon.danger.copy(alpha = 0.5f))
                    block(12f, 10f, Neon.danger.copy(alpha = 0.5f))
                }
                GameLevel.BORDER -> {
                    drawRect(
                        color = Neon.danger,
                        topLeft = Offset(0.5f, 0.5f),
                        size = Size(size.width - 1f, size.height - 1f),
                        style = Stroke(width = 1.5f)
                    )
                }
                GameLevel.OBSTACLES -> {
                    block(3f, 3f, Neon.danger); block(4f, 3f, Neon.danger)
                    block(10f, 9f, Neon.danger); block(10f, 10f, Neon.danger)
                    block(3f, 10f, Neon.danger)
                }
                GameLevel.MAZE -> {
                    for (x in 1..12) if (x !in 5..7) { block(x.toFloat(), 3f, Neon.danger); block(x.toFloat(), 9f, Neon.danger) }
                }
            }
            block(11f, 6f, Neon.gold)
        }
    }
}

@Composable
fun SettingsScreen(game: SnakeLogic, sound: SoundKit, onBack: () -> Unit) {
    var showResetDialog by remember { mutableStateOf(false) }

    MenuScaffold(title = "SETTINGS", subtitle = "RS NOVA Studios", onBack = onBack) {
        NeonCard {
            Column(modifier = Modifier.padding(vertical = 4.dp)) {
                SettingRow("Sound effects", "Bites, pickups, crash cues", game.prefs.soundOn) {
                    game.prefs.soundOn = it; sound.sfxEnabled = it; if (it) sound.play(SoundKit.CLICK)
                }
                SettingDivider()
                SettingRow("Music", "Chiptune loop in the menu & game", game.prefs.musicOn) {
                    game.prefs.musicOn = it; sound.musicEnabled = it; sound.setMusicPlaying(it)
                }
                SettingDivider()
                SettingRow("Haptics", "Vibration on eat / shield / death", game.prefs.vibrationOn) {
                    game.prefs.vibrationOn = it; sound.vibrationEnabled = it
                }
                SettingDivider()
                SettingRow("Smooth motion", "Interpolates snake between ticks", game.prefs.smoothMotion) {
                    game.prefs.smoothMotion = it
                }
                SettingDivider()
                SettingRow("Neon trail", "Fade the tail into darkness", game.prefs.trailEffect) {
                    game.prefs.trailEffect = it
                }
                SettingDivider()
                SettingRow("Auto speed ramp", "Tempo rises with your length", game.prefs.autoRamp) {
                    game.prefs.autoRamp = it
                }
                SettingDivider()
                SettingRow("Grid lines", "Show the board lattice", game.prefs.showGrid) {
                    game.prefs.showGrid = it
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text("BOARD SIZE", color = Neon.white, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(16, 20, 24, 28).forEach { size ->
                val selected = game.boardSize == size
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (selected) Neon.green else Neon.panelSoft, RoundedCornerShape(10.dp))
                        .border(1.dp, if (selected) Neon.green else Neon.line, RoundedCornerShape(10.dp))
                        .clickable {
                            game.changeBoardSize(size)
                            sound.play(SoundKit.CLICK, rate = 1.2f)
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${size}x$size",
                        color = if (selected) Color.Black else Neon.white,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }
            }
        }
        Text(
            text = "Bigger board = more room, slower pace. Changing it restarts the run.",
            color = Neon.dim,
            fontSize = 11.sp,
            modifier = Modifier.padding(top = 6.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))
        MenuButton("RESET STATS & LEADERBOARD", Neon.danger.copy(alpha = 0.16f), Neon.danger) { showResetDialog = true }

        if (showResetDialog) {
            NeonDialog(
                icon = "🧹",
                title = "RESET DATA?",
                message = "High score, leaderboard and lifetime stats will be wiped. This cannot be undone.",
                primaryLabel = "RESET",
                primaryColor = Neon.danger,
                onDismiss = { showResetDialog = false },
                onConfirm = {
                    game.prefs.resetStats()
                    showResetDialog = false
                }
            )
        }
    }
}

@Composable
fun StatsScreen(game: SnakeLogic, onBack: () -> Unit) {
    val prefs = game.prefs
    MenuScaffold(title = "STATS", subtitle = "Local records", onBack = onBack) {
        NeonCard {
            Column(modifier = Modifier.padding(14.dp)) {
                StatLine("High score", "${prefs.highScore}", Neon.gold)
                StatLine("Games played", "${prefs.gamesPlayed}", Neon.white)
                StatLine("Longest snake", "${prefs.bestLength}", Neon.green)
                StatLine("Best combo", "x${1 + (prefs.bestCombo / 3).coerceAtMost(3)} (${prefs.bestCombo} chain)", Neon.orange)
                StatLine("Food eaten", "${prefs.totalFoodEaten}", Neon.danger)
                StatLine("Longest run", formatMs(prefs.longestRunMs), Neon.cyan)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
        Text("TOP RUNS", color = Neon.white, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
        Spacer(modifier = Modifier.height(8.dp))

        if (prefs.leaderboard.isEmpty()) {
            NeonCard {
                Text(
                    "No runs recorded yet.\nPlay a game to set the bar 🐍",
                    color = Neon.dim,
                    fontSize = 12.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                )
            }
        } else {
            prefs.leaderboard.forEachIndexed { index, record ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .border(1.dp, Neon.line, RoundedCornerShape(12.dp)),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1322)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "#${index + 1}",
                            color = if (index == 0) Neon.gold else Neon.dim,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Black,
                            modifier = Modifier.width(30.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text("${record.score} pts", color = Neon.white, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "${record.level} • len ${record.length} • x${1 + (record.combo / 3).coerceAtMost(3)} • ${formatMs(record.timeMs)}",
                                color = Neon.dim,
                                fontSize = 10.sp
                            )
                        }
                        Text(
                            when (index) {
                                0 -> "🥇"
                                1 -> "🥈"
                                2 -> "🥉"
                                else -> ""
                            },
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

/* ------------------------------ shared bits ------------------------------ */

@Composable
fun MenuScaffold(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(screenGradient)
            .padding(horizontal = 22.dp, vertical = 18.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (onBack != null) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Neon.panelSoft, CircleShape)
                        .border(1.dp, Neon.line, CircleShape)
                        .clickable { onBack() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("←", color = Neon.white, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(12.dp))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontSize = 26.sp, fontWeight = FontWeight.Black, color = Neon.green, letterSpacing = 2.sp)
                Text(subtitle, fontSize = 11.sp, color = Neon.cyan, letterSpacing = 1.sp)
            }
        }

        Spacer(modifier = Modifier.height(18.dp))
        content()
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
fun NeonCard(content: @Composable () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .shadow(8.dp, RoundedCornerShape(16.dp), spotColor = Neon.green.copy(alpha = 0.5f))
            .border(1.dp, Neon.line, RoundedCornerShape(16.dp)),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F1322).copy(alpha = 0.92f)),
        shape = RoundedCornerShape(16.dp)
    ) { content() }
}

@Composable
fun StatChip(label: String, value: String, accent: Color, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .background(Neon.panel, RoundedCornerShape(12.dp))
            .border(1.dp, accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(value, color = accent, fontSize = 17.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace)
        Text(label, color = Neon.dim, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}

@Composable
fun MenuButton(
    text: String,
    color: Color,
    textColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadow(4.dp, RoundedCornerShape(14.dp)),
        colors = ButtonDefaults.buttonColors(containerColor = color),
        shape = RoundedCornerShape(14.dp),
        contentPadding = PaddingValues(horizontal = 12.dp)
    ) {
        Text(text, color = textColor, fontSize = 15.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp, maxLines = 1)
    }
}

@Composable
fun TogglePill(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .background(if (on) Neon.green.copy(alpha = 0.14f) else Neon.panelSoft, CircleShape)
            .border(1.dp, if (on) Neon.green.copy(alpha = 0.7f) else Neon.line, CircleShape)
            .clickable { onChange(!on) }
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .background(if (on) Neon.green else Neon.dim, CircleShape)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(label, color = if (on) Neon.green else Neon.dim, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
    }
}

@Composable
fun SettingRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = Neon.white, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Neon.dim, fontSize = 10.5.sp, lineHeight = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Neon.green,
                uncheckedThumbColor = Neon.dim,
                uncheckedTrackColor = Neon.panelSoft
            )
        )
    }
}

@Composable
fun SettingDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(1.dp)
            .background(Neon.line)
    )
}

@Composable
fun StatLine(label: String, value: String, accent: Color) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Neon.dim, fontSize = 12.sp)
        Text(value, color = accent, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    }
}

/** Gradient confirm dialog — replaces the two near-identical dialogs in the old code. */
@Composable
fun NeonDialog(
    icon: String,
    title: String,
    message: String,
    primaryLabel: String,
    onConfirm: () -> Unit,
    secondaryLabel: String = "CANCEL",
    onDismiss: () -> Unit,
    primaryColor: Color = Neon.green,
    secondaryColor: Color = Neon.danger
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
        modifier = Modifier
            .fillMaxWidth(0.88f)
            .shadow(24.dp, RoundedCornerShape(24.dp), spotColor = primaryColor),
        confirmButton = {},
        containerColor = Color.Transparent,
        text = {
            Box(
                modifier = Modifier
                    .background(
                        Brush.verticalGradient(listOf(Color(0xFF161B2E), Color(0xFF0D101D))),
                        RoundedCornerShape(24.dp)
                    )
                    .border(1.5.dp, Brush.horizontalGradient(listOf(primaryColor, Neon.cyan)), RoundedCornerShape(24.dp))
                    .padding(22.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        modifier = Modifier
                            .size(52.dp)
                            .shadow(12.dp, CircleShape, spotColor = primaryColor)
                            .background(primaryColor.copy(alpha = 0.15f), CircleShape)
                            .border(1.dp, primaryColor, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(icon, fontSize = 22.sp)
                    }
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(title, fontSize = 21.sp, fontWeight = FontWeight.Black, color = Neon.white, letterSpacing = 1.4.sp)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(message, fontSize = 12.5.sp, color = Color.LightGray, textAlign = TextAlign.Center, lineHeight = 17.sp)
                    Spacer(modifier = Modifier.height(20.dp))
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f).height(46.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = secondaryColor.copy(alpha = 0.18f)),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(secondaryLabel, color = secondaryColor, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                        }
                        Button(
                            onClick = onConfirm,
                            modifier = Modifier.weight(1f).height(46.dp).shadow(8.dp, RoundedCornerShape(12.dp), spotColor = primaryColor),
                            colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(primaryLabel, color = Color.Black, fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    )
}

fun speedLabel(value: Float): String {
    val v = value.toInt()
    val tag = when (v) {
        in 1..3 -> "SLOW"
        in 4..6 -> "MEDIUM"
        in 7..8 -> "FAST"
        else -> "INSANE"
    }
    return "$tag ($v)"
}

fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}