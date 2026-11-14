package com.mononymm.glasstv

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import com.mononymm.glasstv.ui.AppModel
import com.mononymm.glasstv.ui.DetailScreen
import com.mononymm.glasstv.ui.G
import com.mononymm.glasstv.ui.HomeScreen
import com.mononymm.glasstv.ui.LibraryScreen
import com.mononymm.glasstv.ui.LocalApp
import com.mononymm.glasstv.ui.PlayerScreen
import com.mononymm.glasstv.ui.Screen
import com.mononymm.glasstv.ui.SearchScreen
import com.mononymm.glasstv.ui.ServersScreen
import com.mononymm.glasstv.ui.SettingsScreen
import com.mononymm.glasstv.ui.SignInScreen

class MainActivity : ComponentActivity() {
    private lateinit var app: AppModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val version = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "dev"
        app = AppModel(this, version ?: "dev")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!app.back()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                    isEnabled = true
                }
            }
        })

        setContent {
            val saver = rememberSaveableStateHolder()
            CompositionLocalProvider(LocalApp provides app) {
                Box(Modifier.fillMaxSize().background(G.bg)) {
                    val screen = app.current
                    val keepAwake = screen is Screen.Player
                    remember(keepAwake) {
                        if (keepAwake) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        keepAwake
                    }
                    saver.SaveableStateProvider("${app.stack.size}:${screen.hashCode()}") {
                        when (screen) {
                            Screen.SignIn -> SignInScreen()
                            Screen.Servers -> ServersScreen()
                            Screen.Home -> HomeScreen()
                            is Screen.Library -> LibraryScreen(screen.section)
                            is Screen.Detail -> DetailScreen(screen.item)
                            Screen.Search -> SearchScreen()
                            Screen.Settings -> SettingsScreen()
                            is Screen.Player -> PlayerScreen(screen.item, screen.fromStart)
                        }
                    }
                }
            }
        }
    }
}
