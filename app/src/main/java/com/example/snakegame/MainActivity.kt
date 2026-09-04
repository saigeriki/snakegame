package com.example.snakegame

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/* ============================================================================
 *  NAAGA GAME — Step 5: ENTRY POINT
 *   - one retained runtime (logic + audio) so rotation never wipes a run
 *   - sound effects are synthesised into the cache off the main thread
 *   - central navigation + hardware-back behaviour + music ducking
 * ========================================================================== */

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        enableEdgeToEdge()

        val runtime = GameRuntime.get(applicationContext)
        runtime.applySettings()

        setContent {
            /* Audio boot: SnakeAudioSynth renders .wav files into the cache
               (first launch only, ~a few tens of ms) -> then SoundPool loads
               them. Both calls are safe to repeat; nothing can crash if a file
               is missing, the game simply stays silent. */
            LaunchedEffect(Unit) {
                withContext(Dispatchers.IO) { runtime.sound.prepare() }
                runtime.sound.init()
                runtime.applySettings()
                // first launch: onResume() already happened before the audio was
                // ready, so the loop has to be kicked off here
                runtime.sound.startMusic()
            }

            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Neon.green,
                    onPrimary = Color.Black,
                    background = Neon.bg,
                    onBackground = Neon.white,
                    surface = Neon.panel,
                    onSurface = Neon.white
                )
            ) {
                Surface(modifier = Modifier.fillMaxSize(), color = Neon.bg) {
                    MainAppNavigation(runtime.logic, runtime.sound)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        GameRuntime.peek()?.let { rt ->
            rt.applySettings()
            rt.sound.startMusic()
        }
    }

    override fun onPause() {
        // never die while the app is in the background: pause first, then mute
        GameRuntime.peek()?.let { rt ->
            rt.logic.pauseGame()
            rt.sound.stopMusic()
        }
        super.onPause()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isFinishing) GameRuntime.release()
    }
}

/**
 * Keeps [SnakeLogic] + [SoundKit] alive across configuration changes without
 * pulling in a ViewModel dependency. Released only when the activity really
 * finishes.
 */
class GameRuntime private constructor(
    val logic: SnakeLogic,
    val sound: SoundKit
) {

    /** mirror the persisted settings into the live audio engine */
    fun applySettings() {
        sound.sfxEnabled = logic.prefs.soundOn
        sound.musicEnabled = logic.prefs.musicOn
        sound.vibrationEnabled = logic.prefs.vibrationOn
    }

    companion object {
        private var instance: GameRuntime? = null

        fun get(context: Context): GameRuntime = instance ?: synchronized(this) {
            instance ?: GameRuntime(
                logic = SnakeLogic(context),
                sound = SoundKit(context)
            ).also { instance = it }
        }

        fun peek(): GameRuntime? = instance

        fun release() {
            synchronized(this) {
                runCatching { instance?.sound?.release() }
                instance = null
            }
        }
    }
}

@Composable
fun MainAppNavigation(game: SnakeLogic, sound: SoundKit) {
    var showQuitDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    // settings screen is the source of truth -> keep the live engine in sync
    LaunchedEffect(game.prefs.soundOn, game.prefs.musicOn, game.prefs.vibrationOn) {
        sound.sfxEnabled = game.prefs.soundOn
        sound.musicEnabled = game.prefs.musicOn
        sound.vibrationEnabled = game.prefs.vibrationOn
    }

    // menu screens, pause or a dialog: music drops to a whisper instead of stopping
    LaunchedEffect(game.currentScreen, game.isPaused, showQuitDialog) {
        sound.duck(showQuitDialog || game.isPaused || game.currentScreen != ScreenState.GAME)
    }

    BackHandler(enabled = game.currentScreen != ScreenState.GAME && !showQuitDialog) {
        when (game.currentScreen) {
            ScreenState.MAIN_MENU -> showQuitDialog = true
            ScreenState.LEVEL_SELECT, ScreenState.SETTINGS, ScreenState.STATS -> {
                sound.play(SoundKit.CLICK)
                game.currentScreen = ScreenState.MAIN_MENU
            }
            ScreenState.SPLASH, ScreenState.GAME -> Unit
        }
    }

    when (game.currentScreen) {
        ScreenState.SPLASH -> SplashScreen { game.currentScreen = ScreenState.MAIN_MENU }

        ScreenState.MAIN_MENU -> MainMenuScreen(
            game = game,
            sound = sound,
            onPlay = {
                sound.play(SoundKit.GO, volume = 0.6f)
                game.restartGame()
                game.currentScreen = ScreenState.GAME
            },
            onLevels = { game.currentScreen = ScreenState.LEVEL_SELECT },
            onSettings = { game.currentScreen = ScreenState.SETTINGS },
            onStats = { game.currentScreen = ScreenState.STATS },
            onQuit = { showQuitDialog = true }
        )

        ScreenState.LEVEL_SELECT -> LevelSelectScreen(
            game = game,
            sound = sound,
            onBack = { game.currentScreen = ScreenState.MAIN_MENU }
        )

        ScreenState.SETTINGS -> SettingsScreen(
            game = game,
            sound = sound,
            onBack = { game.currentScreen = ScreenState.MAIN_MENU }
        )

        ScreenState.STATS -> StatsScreen(
            game = game,
            onBack = { game.currentScreen = ScreenState.MAIN_MENU }
        )

        ScreenState.GAME -> GameScreen(
            game = game,
            sound = sound,
            onBackToMenu = {
                game.isPaused = false
                game.currentScreen = ScreenState.MAIN_MENU
            }
        )
    }

    if (showQuitDialog) {
        NeonDialog(
            icon = "⚠️",
            title = "QUIT GAME?",
            message = "Are you sure you want to exit Naaga Game?",
            primaryLabel = "YES, EXIT",
            secondaryLabel = "CANCEL",
            primaryColor = Neon.danger,
            secondaryColor = Neon.panelSoft,
            onDismiss = { showQuitDialog = false },
            onConfirm = {
                showQuitDialog = false
                GameRuntime.release()
                (context as? Activity)?.finish()
            }
        )
    }
}