package com.example

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.model.DownloadStatus
import com.example.ui.MainViewModel
import com.example.ui.navigation.AppBottomNavBar
import com.example.ui.navigation.AppDestination
import com.example.ui.screens.DownloadsScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val mainViewModel: MainViewModel = viewModel()
            val settings by mainViewModel.settings.collectAsStateWithLifecycle()
            val allDownloads by mainViewModel.allDownloads.collectAsStateWithLifecycle()

            // Notification permission on Android 13+
            val notificationPermissionLauncher = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.RequestPermission()
            ) { isGranted ->
                if (!isGranted) {
                    mainViewModel.updateNotificationsEnabled(false)
                }
            }

            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    if (ContextCompat.checkSelfPermission(
                            this@MainActivity,
                            Manifest.permission.POST_NOTIFICATIONS
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }
            }

            val darkTheme = when (settings.themeMode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }

            MyApplicationTheme(darkTheme = darkTheme) {
                var currentDestination by rememberSaveable { mutableStateOf(AppDestination.HOME) }
                val activeCount = allDownloads.count { it.status.isActive }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        AppBottomNavBar(
                            currentDestination = currentDestination,
                            onNavigate = { currentDestination = it },
                            activeDownloadCount = activeCount
                        )
                    }
                ) { innerPadding ->
                    Crossfade(
                        targetState = currentDestination,
                        label = "screen_transition",
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(innerPadding)
                    ) { destination ->
                        when (destination) {
                            AppDestination.HOME -> HomeScreen(
                                viewModel = mainViewModel,
                                onNavigateToDownloads = { currentDestination = AppDestination.DOWNLOADS }
                            )
                            AppDestination.DOWNLOADS -> DownloadsScreen(
                                viewModel = mainViewModel
                            )
                            AppDestination.SETTINGS -> SettingsScreen(
                                viewModel = mainViewModel
                            )
                        }
                    }
                }
            }
        }
    }
}
