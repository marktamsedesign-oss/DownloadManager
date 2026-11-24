package com.markt.dlmanager.ui

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.markt.dlmanager.browser.Browser
import com.markt.dlmanager.engine.Downloads
import com.markt.dlmanager.engine.PendingDownload
import com.markt.dlmanager.update.Updater
import kotlinx.coroutines.flow.collectLatest

enum class Screen { BROWSER, DOWNLOADS }

class MainActivity : ComponentActivity() {
    private lateinit var browser: Browser
    private val pending = mutableStateOf<PendingDownload?>(null)
    private val screen = mutableStateOf(Screen.BROWSER)
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // <input type="file"> on web pages
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private val filePicker =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val cb = fileCallback ?: return@registerForActivityResult
            fileCallback = null
            val clip = result.data?.clipData
            val uris = when {
                result.resultCode != RESULT_OK -> null
                clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
                else -> WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            }
            cb.onReceiveValue(uris)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        browser = Browser(this).apply {
            onDownload = { pending.value = it }
            fileChooser = this@MainActivity::showFileChooser
        }

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        handleIntent(intent)
        setContent {
            DLTheme {
                val fullscreen = browser.fullscreenView != null
                LaunchedEffect(fullscreen) { setImmersive(fullscreen) }
                AppRoot(browser, screen, pending)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        browser.onResume()
        Downloads.schedule()     // continue anything that was interrupted
        Updater.checkIfDue()
    }

    override fun onPause() {
        browser.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        fileCallback?.onReceiveValue(null)
        fileCallback = null
        browser.destroy()
        super.onDestroy()
    }

    private fun showFileChooser(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        fileCallback?.onReceiveValue(null)
        fileCallback = cb
        val intent = params.createIntent()
        if (params.mode == WebChromeClient.FileChooserParams.MODE_OPEN_MULTIPLE) {
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        }
        return try {
            filePicker.launch(intent)
            true
        } catch (e: ActivityNotFoundException) {
            fileCallback = null
            false
        }
    }

    /** Full-screen video: hide the status and navigation bars and keep the screen on. */
    private fun setImmersive(on: Boolean) {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars())
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                val link = Regex("""https?://\S+""").find(text)?.value
                if (link != null) pending.value = PendingDownload.fromUrl(link, browser.current.userAgent)
            }
            Intent.ACTION_VIEW -> {
                val link = intent.dataString
                if (link != null && link.startsWith("http")) {
                    screen.value = Screen.BROWSER
                    browser.showTabs = false
                    browser.newTab(link)
                }
            }
        }
        if (intent.getBooleanExtra(EXTRA_SHOW_DOWNLOADS, false)) screen.value = Screen.DOWNLOADS
        // don't handle the same intent again after a configuration change
        setIntent(Intent(this, MainActivity::class.java))
    }

    companion object {
        const val EXTRA_SHOW_DOWNLOADS = "show_downloads"
    }
}

@Composable
private fun AppRoot(
    browser: Browser,
    screen: MutableState<Screen>,
    pending: MutableState<PendingDownload?>,
) {
    val downloads by Downloads.state.collectAsStateWithLifecycle()
    val active = downloads.count { it.status.isActive }
    val snackbar = remember { SnackbarHostState() }
    var showSettings by remember { mutableStateOf(false) }

    LaunchedEffect(browser) {
        browser.notices.collectLatest { n ->
            val result = snackbar.showSnackbar(n.message, n.actionLabel, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) n.action?.invoke()
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
        ) {
            when (screen.value) {
                Screen.BROWSER -> BrowserScreen(
                    browser = browser,
                    activeDownloads = active,
                    onDownload = { pending.value = it },
                    onOpenDownloads = { screen.value = Screen.DOWNLOADS },
                    onOpenSettings = { showSettings = true },
                )
                Screen.DOWNLOADS -> DownloadsScreen(
                    items = downloads,
                    onBack = { screen.value = Screen.BROWSER },
                    onOpenSettings = { showSettings = true },
                    onDownload = { pending.value = it },
                )
            }
            SnackbarHost(
                snackbar,
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(bottom = if (screen.value == Screen.BROWSER) 58.dp else 0.dp),
            )
        }

        browser.fullscreenView?.let { view -> FullscreenVideo(view, onExit = browser::exitFullscreen) }
    }

    if (showSettings) SettingsDialog(browser, onDismiss = { showSettings = false })

    pending.value?.let { p ->
        DownloadDialog(
            pending = p,
            onDismiss = { pending.value = null },
            onOpenInBrowser = {
                pending.value = null
                screen.value = Screen.BROWSER
                browser.showTabs = false
                browser.newTab(p.url)
            },
            onConfirm = { name, threads ->
                Downloads.add(p, name, threads)
                pending.value = null
            },
        )
    }

    UpdatePrompt(downloads)
}

@Composable
private fun FullscreenVideo(view: View, onExit: () -> Unit) {
    BackHandler(onBack = onExit)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        key(view) {
            AndroidView(
                factory = { view.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize(),
            )
        }
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
