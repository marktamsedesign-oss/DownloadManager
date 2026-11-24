package com.markt.dlmanager.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.markt.dlmanager.engine.Downloads
import com.markt.dlmanager.engine.PendingDownload

class MainActivity : ComponentActivity() {
    private lateinit var browser: BrowserController
    private val pending = mutableStateOf<PendingDownload?>(null)
    private val tab = mutableIntStateOf(0)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        browser = BrowserController(this) { pending.value = it }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntent(intent)
        setContent {
            DLTheme {
                AppRoot(browser, tab, pending)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        Downloads.schedule()     // continue anything that was interrupted
    }

    override fun onDestroy() {
        browser.webView.destroy()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val link = Regex("""https?://\S+""").find(text)?.value
                if (link != null) pending.value = PendingDownload.fromUrl(link, browser.userAgent)
            }
            Intent.ACTION_VIEW -> {
                val link = intent.dataString
                if (link != null && link.startsWith("http")) {
                    tab.intValue = 0
                    browser.load(link)
                }
            }
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_DOWNLOADS, false)) tab.intValue = 1
        // don't handle the same intent again after a configuration change
        setIntent(Intent(this, MainActivity::class.java))
    }

    companion object {
        const val EXTRA_SHOW_DOWNLOADS = "show_downloads"
    }
}

@Composable
private fun AppRoot(
    browser: BrowserController,
    tab: MutableIntState,
    pending: MutableState<PendingDownload?>,
) {
    val downloads by Downloads.state.collectAsStateWithLifecycle()
    val active = downloads.count { it.status.isActive }

    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab.intValue == 0,
                    onClick = { tab.intValue = 0 },
                    icon = { Icon(Icons.Outlined.Language, contentDescription = null) },
                    label = { Text("Browser") },
                )
                NavigationBarItem(
                    selected = tab.intValue == 1,
                    onClick = { tab.intValue = 1 },
                    icon = {
                        BadgedBox(badge = { if (active > 0) Badge { Text("$active") } }) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = null)
                        }
                    },
                    label = { Text("Downloads") },
                )
            }
        }
    ) { padding ->
        Box(
            Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            if (tab.intValue == 0) {
                BrowserScreen(browser, onDownload = { pending.value = it })
            } else {
                DownloadsScreen(downloads)
            }
        }
    }

    pending.value?.let { p ->
        DownloadDialog(
            pending = p,
            onDismiss = { pending.value = null },
            onOpenInBrowser = {
                pending.value = null
                tab.intValue = 0
                browser.load(p.url)
            },
            onConfirm = { name, threads ->
                Downloads.add(p, name, threads)
                pending.value = null
            },
        )
    }
}

@Composable
fun DLTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val colors = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF8AB4FF))
        else -> lightColorScheme(primary = Color(0xFF1565E0))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
