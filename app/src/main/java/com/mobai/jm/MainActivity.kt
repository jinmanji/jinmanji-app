package com.mobai.jm

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.mobai.jm.data.AlbumCache
import com.mobai.jm.data.BlockTagsStore
import com.mobai.jm.data.BrowserStore
import com.mobai.jm.data.CustomCatStore
import com.mobai.jm.data.FavStore
import com.mobai.jm.data.HistoryStore
import com.mobai.jm.data.HomeCache
import com.mobai.jm.data.JmApi
import com.mobai.jm.data.PagesCache
import com.mobai.jm.data.SearchHistoryStore
import com.mobai.jm.data.TagStats
import com.mobai.jm.download.DownloadQueue
import com.mobai.jm.ui.MoBaiApp
import com.mobai.jm.ui.search.SearchStore
import com.mobai.jm.util.net.MasqueManager
import com.mobai.jm.util.net.TunnelStats
import com.mobai.jm.ui.theme.MoBaiTheme
import com.mobai.jm.util.DiagLog
import com.mobai.jm.util.NavSignals
import com.mobai.jm.util.StorageUtil
import com.mobai.jm.util.ThemePrefs

class MainActivity : ComponentActivity() {

    companion object {
        const val EXTRA_OPEN_DOWNLOADS = "open_downloads"
    }

    private fun handleNavIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_DOWNLOADS, false) == true) {
            NavSignals.openDownloads.value += 1
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        DiagLog.init(applicationContext)
        JmApi.init(applicationContext)
        FavStore.init(applicationContext)
        AlbumCache.init(applicationContext)
        PagesCache.init(applicationContext)
        HomeCache.init(applicationContext)
        TagStats.init(applicationContext)
        HistoryStore.init(applicationContext)
        SearchHistoryStore.init(applicationContext)
        BlockTagsStore.init(applicationContext)
        DownloadQueue.init(applicationContext)
        MasqueManager.init(applicationContext)
        TunnelStats.init(applicationContext)
        StorageUtil.migrateIfNeeded(applicationContext)
        BrowserStore.init(applicationContext)
        CustomCatStore.init(applicationContext)
        SearchStore.init(applicationContext)
        com.mobai.jm.data.JmApi.refreshImageHostsAsync()
        com.mobai.jm.util.net.TunnelPluginClient.scan(applicationContext)
        val initConc = com.mobai.jm.util.AppPrefs(applicationContext).imgConcurrency
        com.mobai.jm.util.net.NetDispatcher.apply(initConc)
        com.mobai.jm.util.PageLoader.concurrency = initConc
        MasqueManager.autoStartIfNeeded(applicationContext)

        handleNavIntent(intent)

        setContent {
            val prefs = remember { ThemePrefs(applicationContext) }
            var mode by remember { mutableStateOf(prefs.mode) }

            val notifPermission = rememberLauncherForActivityResult(
                ActivityResultContracts.RequestPermission()
            ) { }
            LaunchedEffect(Unit) {
                if (Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(
                        applicationContext,
                        Manifest.permission.POST_NOTIFICATIONS,
                    ) != PackageManager.PERMISSION_GRANTED
                ) {
                    notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }

            MoBaiTheme(mode = mode) {
                MoBaiApp(
                    mode = mode,
                    onModeChange = {
                        mode = it
                        prefs.mode = it
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        TunnelStats.appVisible = true
    }

    override fun onPause() {
        super.onPause()
        TunnelStats.appVisible = false
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleNavIntent(intent)
    }
}
