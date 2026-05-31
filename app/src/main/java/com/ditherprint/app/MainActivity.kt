package com.ditherprint.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.ditherprint.app.ui.editor.EditorScreen
import com.ditherprint.app.ui.editor.EditorViewModel
import com.ditherprint.app.ui.settings.PrinterSettingsScreen
import com.ditherprint.app.ui.theme.DitherPrintTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Extract shared image URI if launched via share intent
        val sharedImageUri = handleShareIntent(intent)

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
        // Handle share intent when activity is already running
        handleShareIntent(intent)?.let { uri ->
            // Re-set content to pass new URI (simplest approach)
            setContent {
                DitherPrintTheme {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        DitherPrintNavigation(uri)
                    }
                }
            }
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
fun DitherPrintNavigation(sharedImageUri: Uri? = null) {
    val navController = rememberNavController()
    val viewModel: EditorViewModel = viewModel()

    // Load shared image if provided
    if (sharedImageUri != null) {
        androidx.compose.runtime.LaunchedEffect(sharedImageUri) {
            viewModel.loadImage(sharedImageUri)
        }
    }

    NavHost(navController = navController, startDestination = "editor") {
        composable("editor") {
            EditorScreen(
                viewModel = viewModel,
                onNavigateToSettings = { navController.navigate("settings") }
            )
        }
        composable("settings") {
            PrinterSettingsScreen(
                viewModel = viewModel,
                onBack = { navController.popBackStack() }
            )
        }
    }
}
