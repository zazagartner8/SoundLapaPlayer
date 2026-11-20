package com.example

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.fragment.app.FragmentActivity
import com.example.data.remote.configureAppCheck
import com.example.notifications.AdminNotificationHelper
import com.example.ui.SoundLapaApp

class MainActivity : FragmentActivity() {

    private var pendingAdminTabDeepLink by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        AdminNotificationHelper.ensureChannels(this)
        extractAdminDeepLink(intent)
        enableEdgeToEdge()
        preferHighestRefreshRate()
        setContent {
            SoundLapaApp(
                pendingAdminTabDeepLink = pendingAdminTabDeepLink,
                onConsumeAdminTabDeepLink = { pendingAdminTabDeepLink = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractAdminDeepLink(intent)
    }

    private fun extractAdminDeepLink(intent: Intent?) {
        val tab = intent?.getStringExtra(AdminNotificationHelper.EXTRA_OPEN_ADMIN_TAB)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        if (tab != null) {
            pendingAdminTabDeepLink = tab
            intent.removeExtra(AdminNotificationHelper.EXTRA_OPEN_ADMIN_TAB)
        }
    }

    private fun preferHighestRefreshRate() {
        runCatching {
            val currentDisplay = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                display
            } else {
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay
            } ?: return

            val currentMode = currentDisplay.mode
            val bestMode = currentDisplay.supportedModes
                .filter {
                    it.physicalWidth == currentMode.physicalWidth &&
                        it.physicalHeight == currentMode.physicalHeight
                }
                .maxByOrNull { it.refreshRate }
                ?: return

            if (bestMode.modeId != currentMode.modeId) {
                window.attributes = window.attributes.apply {
                    preferredDisplayModeId = bestMode.modeId
                }
            }
        }
    }
}
