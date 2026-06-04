package com.ditherprint.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ditherprint.app.ui.camera.CameraScreen
import com.ditherprint.app.ui.editor.EditorScreen
import com.ditherprint.app.ui.editor.EditorViewModel
import com.ditherprint.app.ui.settings.PrinterSettingsScreen
import com.ditherprint.app.ui.settings.QrScannerScreen
import com.ditherprint.app.ui.theme.DitherPrintTheme
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    private val sharedImageUri = MutableStateFlow<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Extract shared image URI if launched via share intent
        handleShareIntent(intent)?.let { sharedImageUri.value = it }

        setContent {
            DitherPrintTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    DitherPrintNavigation(sharedImageUri)
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShareIntent(intent)?.let { uri ->
            sharedImageUri.value = uri
        }
    }

    private fun handleShareIntent(intent: Intent?): Uri? {
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_STREAM)
            }
        }
        return null
    }
}

@Composable
fun DitherPrintNavigation(sharedImageUriFlow: MutableStateFlow<Uri?>) {
    val navController = rememberNavController()
    val viewModel: EditorViewModel = viewModel()

    // Load shared image when URI changes and open the editor
    val sharedUri by sharedImageUriFlow.collectAsState()
    // Capture start destination once — changing it later would recreate the NavGraph
    val startDestination = remember { if (sharedImageUriFlow.value != null) "editor" else "camera" }
    LaunchedEffect(sharedUri) {
        sharedUri?.let { uri ->
            viewModel.loadImage(uri)
            if (navController.currentDestination?.route != "editor") {
                navController.navigate("editor") {
                    launchSingleTop = true
                }
            }
            sharedImageUriFlow.value = null
        }
    }

    NavHost(navController = navController, startDestination = startDestination) {
        composable("editor") {
            EditorScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate("settings") },
                onNavigateToCamera = { navController.navigate("camera") }
            )
        }
        composable("settings") {
            PrinterSettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() },
                onScanQr = { navController.navigate("qr_scanner") }
            )
        }
        composable("camera") {
            CameraScreen(
                viewModel = viewModel,
                onBack = { navController.navigate("editor") },
                onCapture = { navController.navigate("editor") }
            )
        }
        composable("qr_scanner") {
            QrScannerScreen(
                onMacScanned = { mac ->
                    viewModel.bleManager.connectByAddress(mac)
                    navController.popBackStack()
                },
                onDismiss = { navController.popBackStack() }
            )
        }
    }
}
