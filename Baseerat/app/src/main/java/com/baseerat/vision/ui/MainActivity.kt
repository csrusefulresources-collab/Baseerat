package com.baseerat.vision.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.baseerat.vision.R
import com.baseerat.vision.app
import com.baseerat.vision.chat.ChatHub
import com.baseerat.vision.ui.screens.ChatScreen
import com.baseerat.vision.ui.screens.FloatingSettingsScreen
import com.baseerat.vision.ui.screens.HelpScreen
import com.baseerat.vision.ui.screens.HistoryScreen
import com.baseerat.vision.ui.screens.HomeScreen
import com.baseerat.vision.ui.screens.ModelsScreen
import com.baseerat.vision.ui.screens.Routes
import com.baseerat.vision.ui.screens.SettingsScreen
import com.baseerat.vision.ui.screens.VoiceSettingsScreen
import com.baseerat.vision.ui.theme.BaseeratTheme
import com.baseerat.vision.util.LocaleHelper

class MainActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(LocaleHelper.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) handleOpenRequest(intent)
        setContent {
            val settings by app.settings.flow.collectAsStateWithLifecycle(initialValue = app.settings.current)
            BaseeratTheme(highContrast = settings.highContrast) {
                MainScaffold()
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleOpenRequest(intent)
    }

    /** The service / Describe screen can ask us to open the chat tab. */
    private fun handleOpenRequest(i: Intent?) {
        if (i?.getStringExtra(EXTRA_OPEN) == "chat") {
            ChatHub.openRequest.value = ChatHub.openRequest.value + 1
            i.removeExtra(EXTRA_OPEN)
        }
    }

    companion object {
        const val EXTRA_OPEN = "open"
    }
}

private data class TabItem(
    val route: String,
    val labelRes: Int,
    val icon: ImageVector,
    val iconOff: ImageVector
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScaffold() {
    val nav = rememberNavController()
    val snack = remember { SnackbarHostState() }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: Routes.HOME

    val tabs = listOf(
        TabItem(Routes.HOME, R.string.tab_home, Icons.Filled.Home, Icons.Outlined.Home),
        TabItem(Routes.CHAT, R.string.tab_chat, Icons.Filled.Chat, Icons.Outlined.Chat),
        TabItem(Routes.HISTORY, R.string.tab_history, Icons.Filled.History, Icons.Outlined.History),
        TabItem(Routes.SETTINGS, R.string.tab_settings, Icons.Filled.Settings, Icons.Outlined.Settings),
        TabItem(Routes.HELP, R.string.tab_help, Icons.Filled.Info, Icons.Outlined.Info)
    )
    val isTop = tabs.any { it.route == route }

    // another screen asked for the chat tab (picture from Describe screen, floating-button action)
    val openRequest by ChatHub.openRequest.collectAsStateWithLifecycle()
    val pendingPicture by ChatHub.pendingPicture.collectAsStateWithLifecycle()
    var handledOpen by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(openRequest, pendingPicture) {
        val fresh = openRequest > handledOpen
        if (fresh || pendingPicture != null) {
            handledOpen = openRequest
            if (nav.currentDestination?.route != Routes.CHAT) {
                nav.navigate(Routes.CHAT) {
                    popUpTo(Routes.HOME) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }
    }

    val title = stringResource(
        when (route) {
            Routes.CHAT -> R.string.tab_chat
            Routes.HISTORY -> R.string.tab_history
            Routes.SETTINGS -> R.string.tab_settings
            Routes.HELP -> R.string.tab_help
            Routes.VOICE -> R.string.settings_voice
            Routes.FLOATING -> R.string.settings_floating
            Routes.MODELS -> R.string.settings_models
            else -> R.string.app_name
        }
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, modifier = Modifier.semantics { heading() }) },
                navigationIcon = {
                    if (!isTop) {
                        IconButton(onClick = { nav.popBackStack() }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                        }
                    }
                }
            )
        },
        bottomBar = {
            if (isTop) {
                NavigationBar {
                    tabs.forEach { t ->
                        val selected = route == t.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    nav.navigate(t.route) {
                                        popUpTo(Routes.HOME) { saveState = true }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(if (selected) t.icon else t.iconOff, contentDescription = null) },
                            label = { Text(stringResource(t.labelRes)) }
                        )
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) }
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Routes.HOME,
            modifier = Modifier.padding(padding)
        ) {
            composable(Routes.HOME) { HomeScreen(snack, onNavigate = { nav.navigate(it) }) }
            composable(Routes.CHAT) { ChatScreen(onOpenModels = { nav.navigate(Routes.MODELS) }) }
            composable(Routes.HISTORY) { HistoryScreen(snack) }
            composable(Routes.SETTINGS) { SettingsScreen(onNavigate = { nav.navigate(it) }) }
            composable(Routes.HELP) { HelpScreen() }
            composable(Routes.VOICE) { VoiceSettingsScreen(snack) }
            composable(Routes.FLOATING) { FloatingSettingsScreen(snack) }
            composable(Routes.MODELS) { ModelsScreen(snack) }
        }
    }
}
