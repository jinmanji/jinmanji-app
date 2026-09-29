package com.mobai.jm.ui.settings

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.imageLoader
import com.mobai.jm.data.BlockTagsStore
import com.mobai.jm.data.HistoryStore
import com.mobai.jm.data.JmApi
import com.mobai.jm.download.DownloadQueue
import com.mobai.jm.ui.components.SlimTopBar
import com.mobai.jm.util.AppPrefs
import com.mobai.jm.util.DataTransfer
import com.mobai.jm.util.DiagLog
import com.mobai.jm.util.LogLevel
import com.mobai.jm.util.StorageUtil
import com.mobai.jm.util.ThemeMode
import com.mobai.jm.util.net.DohDns
import com.mobai.jm.util.net.MasqueManager
import com.mobai.jm.util.net.TunnelStats
import com.mobai.jm.util.net.fmtBytes
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.mobai.jm.util.CrashTester

@Composable
fun SettingsScreen(
    mode: ThemeMode,
    onModeChange: (ThemeMode) -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit,
) {
    val context = LocalContext.current

    // ── 崩溃自测（开发者模式）：双击立即崩溃 ──
    var crashTapCount by remember { mutableStateOf(0) }
    var crashTapAt by remember { mutableStateOf(0L) }
    fun triggerCrashTest() {
        val now = System.currentTimeMillis()
        crashTapCount = if (now - crashTapAt < 2000) crashTapCount + 1 else 1
        crashTapAt = now
        if (crashTapCount >= 2) {
            crashTapCount = 0
            CrashTester.triggerRandomCrash()
        } else {
            Toast.makeText(context, "再点一次将立即崩溃（测试用）", Toast.LENGTH_SHORT).show()
        }
    }
    val scope = rememberCoroutineScope()
    val prefs = remember { AppPrefs(context) }

    var autoCache by remember { mutableStateOf(prefs.autoCache) }
    var pageSize by remember { mutableStateOf(prefs.randomPageSize) }
    var historyEnabled by remember { mutableStateOf(prefs.historyEnabled) }
    var stats by remember { mutableStateOf<Pair<Long, Int>?>(null) }
    var statsTick by remember { mutableStateOf(0) }
    var clearing by remember { mutableStateOf(false) }

    var showThemeDialog by remember { mutableStateOf(false) }
    var showPageSizeDialog by remember { mutableStateOf(false) }
    var showPathDialog by remember { mutableStateOf(false) }
    var pathDraft by remember { mutableStateOf(prefs.downloadPath) }
    var showLogDialog by remember { mutableStateOf(false) }
    var logLevel by remember {
        mutableStateOf(runCatching { LogLevel.valueOf(prefs.logLevel) }.getOrDefault(LogLevel.ERROR))
    }
    var showClearDialog by remember { mutableStateOf(false) }

    var devMode by remember { mutableStateOf(prefs.devMode) }
    var tapCount by remember { mutableStateOf(0) }
    var lastTap by remember { mutableStateOf(0L) }

    var showBlockDialog by remember { mutableStateOf(false) }
    var blockInput by remember { mutableStateOf("") }

    var showExportDialog by remember { mutableStateOf(false) }
    var exFav by remember { mutableStateOf(true) }
    var exHis by remember { mutableStateOf(true) }
    var exCfg by remember { mutableStateOf(true) }

    var showImportDialog by remember { mutableStateOf(false) }
    var importObj by remember { mutableStateOf<JsonObject?>(null) }
    var importInfo by remember { mutableStateOf("") }
    var imFav by remember { mutableStateOf(true) }
    var imHis by remember { mutableStateOf(true) }
    var imCfg by remember { mutableStateOf(true) }

    var sniSplit by remember { mutableStateOf(prefs.sniSplit) }
    var sniSplitPos by remember { mutableStateOf(prefs.sniSplitPos.toString()) }
    var sniSplitDelay by remember { mutableStateOf(prefs.sniSplitDelay.toString()) }
    var showSplitDialog by remember { mutableStateOf(false) }
    var sniBypass by remember { mutableStateOf(prefs.sniBypass) }
    var dohMode by remember { mutableStateOf(prefs.dohMode) }
    var showDohModeDialog by remember { mutableStateOf(false) }
    var masqueBusy by remember { mutableStateOf(false) }
    var showMasqueImport by remember { mutableStateOf(false) }
    var masqueImportText by remember { mutableStateOf("") }
    var masqueExportText by remember { mutableStateOf("") }
    var autoWarp by remember { mutableStateOf(prefs.masqueAutoStart) }
    var masqueTransfer by remember { mutableStateOf(prefs.masqueTransferMode) }
    var sniList by remember { mutableStateOf(prefs.masqueSnis) }
    var showSniDialog by remember { mutableStateOf(false) }
    var sniInput by remember { mutableStateOf("") }
    var hudEnabled by remember { mutableStateOf(prefs.hudEnabled) }
    var imgConc by remember { mutableStateOf(prefs.imgConcurrency.toString()) }
    var showConcDialog by remember { mutableStateOf(false) }
    var apiH2 by remember { mutableStateOf(prefs.apiHttp2) }
    var pdfPreset by remember { mutableStateOf(prefs.pdfPreset) }
    var showPdfDialog by remember { mutableStateOf(false) }
    var dlFmtS by remember { mutableStateOf(prefs.downloadFormat) }
    var dlLevelS by remember { mutableStateOf(prefs.archiveLevel) }
    var dlPwdS by remember { mutableStateOf(prefs.archivePwdMode) }
    var dlStratS by remember { mutableStateOf(prefs.archivePwdStrategy) }
    var dlFixedS by remember { mutableStateOf(prefs.archiveFixedPwd) }
    var archImgS by remember { mutableStateOf(prefs.archiveImgQuality) }
    var sdlPick by remember { mutableStateOf<String?>(null) }
    var showPwdInput by remember { mutableStateOf(false) }
    var tunnelMode by remember { mutableStateOf(prefs.tunnelMode) }
    var showTunnelModePick by remember { mutableStateOf(false) }
    var torBridgesDraft by remember { mutableStateOf("") }
    var showTorBridgeDialog by remember { mutableStateOf(false) }
    var pwdDraft by remember { mutableStateOf("") }
    var privacyMode by remember { mutableStateOf(prefs.privacyMode) }
    var showTrafficReset by remember { mutableStateOf(false) }
    var editConfigText by remember { mutableStateOf("") }
    var showEditConfig by remember { mutableStateOf(false) }
    var showTransferDialog by remember { mutableStateOf(false) }

    val masqueExportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { it.write(masqueExportText.toByteArray()) }
                    }.isSuccess
                }
                Toast.makeText(context, if (ok) "已导出连接信息" else "导出失败", Toast.LENGTH_SHORT).show()
            }
        }
    }
    var dohUrl by remember { mutableStateOf(prefs.dohUrl) }
    var showDohDialog by remember { mutableStateOf(false) }
    var echEnabled by remember { mutableStateOf(prefs.echEnabled) }

    fun applyNetChange() {
        JmApi.refreshNetwork(context)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    DataTransfer.buildExport(context, exFav, exHis, exCfg)
                }
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openOutputStream(uri)?.use { out ->
                            out.write(text.toByteArray())
                        }
                    }.isSuccess
                }
                Toast.makeText(
                    context,
                    if (ok) "导出成功（已保存到所选位置）" else "导出失败",
                    Toast.LENGTH_SHORT,
                ).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
                    }.getOrNull()
                }
                val obj = text?.let { DataTransfer.parse(it) }
                if (obj == null) {
                    Toast.makeText(context, "读取失败：不是有效的禁漫姬备份文件", Toast.LENGTH_LONG).show()
                } else {
                    importObj = obj
                    importInfo = DataTransfer.describe(obj)
                    showImportDialog = true
                }
            }
        }
    }

    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.4.1"
    }

    LaunchedEffect(statsTick) {
        stats = withContext(Dispatchers.IO) { StorageUtil.totalCacheStats(context) }
    }

    Column(Modifier.fillMaxSize()) {
        SlimTopBar(title = "设置")
        LazyColumn(Modifier.fillMaxSize()) {
            item { SectionTitle("外观") }
            item {
                ListItem(
                    headlineContent = { Text("主题") },
                    supportingContent = { Text(themeLabel(mode)) },
                    modifier = Modifier.clickable { showThemeDialog = true },
                )
            }

            item { SectionTitle("通用") }
            item {
                ListItem(
                    headlineContent = { Text("随机推荐每页数量") },
                    supportingContent = { Text("$pageSize 个/页") },
                    modifier = Modifier.clickable { showPageSizeDialog = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("首页屏蔽标签") },
                    supportingContent = {
                        Text(
                            if (BlockTagsStore.list.isEmpty()) "未设置（命中即隐藏）"
                            else "${BlockTagsStore.list.size} 个：" +
                                BlockTagsStore.list.take(3).joinToString("、") +
                                if (BlockTagsStore.list.size > 3) "…" else ""
                        )
                    },
                    modifier = Modifier.clickable { showBlockDialog = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("历史记录") },
                    supportingContent = { Text("${HistoryStore.list.size} 条") },
                    modifier = Modifier.clickable { onOpenHistory() },
                )
            }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("记录浏览历史", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "关闭后不再记录",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = historyEnabled, onCheckedChange = {
                        historyEnabled = it
                        prefs.historyEnabled = it
                    })
                }
            }

            item { SectionTitle("缓存") }
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动缓存", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "阅读时自动缓存图片",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = autoCache,
                        onCheckedChange = {
                            autoCache = it
                            prefs.autoCache = it
                        },
                    )
                }
            }
            if (devMode) {
                item {
                    val s = stats
                    ListItem(
                        headlineContent = { Text("已用缓存") },
                        supportingContent = {
                            Text(
                                if (s == null) "计算中…"
                                else "${StorageUtil.formatBytes(s.first)} · ${s.second} 个文件"
                            )
                        },
                        trailingContent = {
                            TextButton(onClick = { statsTick++ }) { Text("刷新") }
                        },
                    )
                }
            }
            item {
                ListItem(
                    headlineContent = { Text(if (clearing) "清除中…" else "清除缓存") },
                    supportingContent = { Text("清理图片与详情缓存") },
                    modifier = Modifier.clickable(enabled = !clearing) { showClearDialog = true },
                )
            }

            item { SectionTitle("下载") }
            item {
                ListItem(
                    headlineContent = { Text("我的下载") },
                    supportingContent = { Text("${DownloadQueue.tasks.size} 个任务") },
                    modifier = Modifier.clickable { onOpenDownloads() },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("下载保存位置") },
                    supportingContent = { Text("${prefs.downloadPath}") },
                    modifier = Modifier.clickable {
                        pathDraft = prefs.downloadPath
                        showPathDialog = true
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("PDF 图像质量") },
                    supportingContent = {
                        Text(
                            when (pdfPreset) {
                                "max" -> "最高（原始分辨率）"
                                "compact" -> "压缩（宽 ≤1240）"
                                else -> "视觉无损（原始分辨率）"
                            }
                        )
                    },
                    modifier = Modifier.clickable { showPdfDialog = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("下载格式") },
                    supportingContent = {
                        Text(
                            when (dlFmtS) {
                                "zip" -> "ZIP"
                                "7z" -> "7z"
                                "targz" -> "tar.gz"
                                else -> "PDF"
                            }
                        )
                    },
                    modifier = Modifier.clickable { sdlPick = "fmt" },
                )
            }
            if (dlFmtS != "pdf") {
                item {
                    ListItem(
                        headlineContent = { Text("压缩级别") },
                        supportingContent = {
                            Text(com.mobai.jm.util.ArchiveWriter.LEVEL_NAMES[dlLevelS.coerceIn(0, 5)])
                        },
                        modifier = Modifier.clickable { sdlPick = "level" },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("压缩包图像质量") },
                        supportingContent = {
                            Text(
                                when (archImgS) {
                                    "raw" -> "原样直存（不解密）"
                                    "lossless" -> "像素无损（体积大）"
                                    "compact" -> "压缩优先"
                                    else -> "视觉无损（推荐）"
                                }
                            )
                        },
                        modifier = Modifier.clickable { sdlPick = "imgq" },
                    )
                }
            }
            if (dlFmtS == "7z") {
                item {
                    ListItem(
                        headlineContent = { Text("压缩包密码") },
                        supportingContent = {
                            Text(
                                when (dlPwdS) {
                                    "fixed" -> "固定密码"
                                    "random" -> "随机密码"
                                    else -> "不使用"
                                }
                            )
                        },
                        modifier = Modifier.clickable { sdlPick = "pwd" },
                    )
                }
                if (dlPwdS == "fixed") {
                    item {
                        ListItem(
                            headlineContent = { Text("固定密码") },
                            supportingContent = { Text(if (dlFixedS.isBlank()) "未设置" else "已设置") },
                            modifier = Modifier.clickable {
                                pwdDraft = dlFixedS
                                showPwdInput = true
                            },
                        )
                    }
                }
                if (dlPwdS == "random") {
                    item {
                        ListItem(
                            headlineContent = { Text("随机密码方式") },
                            supportingContent = {
                                Text(if (dlStratS == "twolevel") "二级压缩包（内附解压密码.txt）" else "密码写入文件名")
                            },
                            modifier = Modifier.clickable { sdlPick = "strat" },
                        )
                    }
                }
            }

            item { SectionTitle("数据") }
            item {
                ListItem(
                    headlineContent = { Text("导出数据") },
                    supportingContent = { Text("导出收藏/历史/配置（.json）") },
                    modifier = Modifier.clickable { showExportDialog = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("导入数据") },
                    supportingContent = { Text("从备份合并导入") },
                    modifier = Modifier.clickable {
                        importLauncher.launch(
                            arrayOf("application/json", "application/octet-stream", "text/plain", "*/*")
                        )
                    },
                )
            }

            if (devMode) {
            item { SectionTitle("诊断") }
            item {
                ListItem(
                    headlineContent = { Text("日志级别") },
                    supportingContent = { Text("${logLevel.label}（默认：错误）") },
                    modifier = Modifier.clickable { showLogDialog = true },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("运行日志") },
                    supportingContent = {
                        Text("${DiagLog.path}")
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("接口信息") },
                    supportingContent = { Text(JmApi.debugInfo()) },
                )
            }
            }

            if (devMode) {
                item { SectionTitle("崩溃兜底自测（开发者模式）") }
                item {
                    ListItem(
                        headlineContent = { Text("模拟崩溃（双击立即崩溃）") },
                        supportingContent = { Text("随机触发一种真实崩溃，验证崩溃提示页与剪贴板复制") },
                        modifier = Modifier.clickable { triggerCrashTest() },
                    )
                }
            }

            if (devMode) {
                item { SectionTitle("实验性网络（开发者模式）") }
                item {
                    ListItem(
                        headlineContent = { Text("SNI 切片") },
                        supportingContent = {
                            Text("首包分段（${prefs.sniSplitPos} B / ${prefs.sniSplitDelay} ms）")
                        },
                        trailingContent = {
                            Switch(checked = sniSplit, onCheckedChange = {
                                sniSplit = it
                                prefs.sniSplit = it
                                applyNetChange()
                            })
                        },
                    )
                }
                if (sniSplit) {
                    item {
                        ListItem(
                            headlineContent = { Text("切片参数") },
                            supportingContent = { Text("切片位置 ${prefs.sniSplitPos}B · 间隔 ${prefs.sniSplitDelay}ms") },
                            modifier = Modifier.clickable { showSplitDialog = true },
                        )
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text("SNI 绕过（置空）") },
                        supportingContent = { Text("不发送 SNI（部分 CDN 会拒绝）") },
                        trailingContent = {
                            Switch(checked = sniBypass, onCheckedChange = {
                                sniBypass = it
                                prefs.sniBypass = it
                                applyNetChange()
                            })
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("DoH 模式") },
                        supportingContent = {
                            Text(
                                dohModeLabel(dohMode) + when (dohMode) {
                                    "enhanced", "strict" -> " · ${DohDns.endpoint(prefs.dohUrl)}"
                                    else -> ""
                                }
                            )
                        },
                        modifier = Modifier.clickable { showDohModeDialog = true },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("DoH 配置 / 测试") },
                        supportingContent = { Text(prefs.dohUrl) },
                        modifier = Modifier.clickable { showDohDialog = true },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("ECH") },
                        supportingContent = { Text("需 DoH；取决于服务器与系统") },
                        trailingContent = {
                            Switch(checked = echEnabled, onCheckedChange = {
                                echEnabled = it
                                prefs.echEnabled = it
                                applyNetChange()
                            })
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("清除 DNS 缓存") },
                        supportingContent = { Text("清空 DoH 解析缓存") },
                        modifier = Modifier.clickable {
                            DohDns.clearCache()
                            Toast.makeText(context, "DoH DNS 缓存已清除", Toast.LENGTH_SHORT).show()
                        },
                    )
                }

                item { SectionTitle("隐私") }
                item {
                    ListItem(
                        headlineContent = { Text("隐私模式") },
                        supportingContent = { Text("数据存到应用私有目录（重启生效）") },
                        trailingContent = {
                            Switch(checked = privacyMode, onCheckedChange = {
                                privacyMode = it
                                prefs.privacyMode = it
                                Toast.makeText(context, "重启应用后生效", Toast.LENGTH_SHORT).show()
                            })
                        },
                    )
                }

                item { SectionTitle("隧道") }
                item {
                    ListItem(
                        headlineContent = { Text("隧道模式") },
                        supportingContent = { Text(if (tunnelMode == "tor") "Tor（插件·实验）" else "WARP") },
                        modifier = Modifier.clickable { showTunnelModePick = true },
                    )
                }
                if (tunnelMode == "tor") {
                    item {
                        ListItem(
                            headlineContent = { Text("Tor 插件") },
                            supportingContent = {
                                Text(
                                    if (!com.mobai.jm.util.net.TunnelPluginClient.installed) {
                                        "未安装：安装同目录的 mobai-tunnel-tor-*.apk 后，点此重新检测"
                                    } else {
                                        com.mobai.jm.util.net.TunnelPluginClient.stateText
                                    }
                                )
                            },
                            modifier = Modifier.clickable {
                                com.mobai.jm.util.net.TunnelPluginClient.scan(context)
                            },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = {
                                Text(if (com.mobai.jm.util.net.TunnelPluginClient.running) "停止 Tor" else "启动 Tor")
                            },
                            supportingContent = {
                                Text("桥行 ${prefs.torBridges.lineSequence().count { it.isNotBlank() }} 行 · 速度慢适合应急；连不上可先打开插件 App 点启动")
                            },
                            modifier = Modifier.clickable(
                                enabled = com.mobai.jm.util.net.TunnelPluginClient.installed,
                            ) {
                                if (com.mobai.jm.util.net.TunnelPluginClient.running) {
                                    com.mobai.jm.util.net.TunnelPluginClient.stop()
                                } else {
                                    com.mobai.jm.util.net.TunnelPluginClient.start(context, prefs.torBridges)
                                }
                            },
                        )
                    }
                    item {
                        ListItem(
                            headlineContent = { Text("网桥配置") },
                            supportingContent = { Text("粘贴 Tor 官网获取的桥行，一行一条；留空则直连 Tor") },
                            modifier = Modifier.clickable {
                                torBridgesDraft = prefs.torBridges
                                showTorBridgeDialog = true
                            },
                        )
                    }
                }
                item {
                    ListItem(
                        headlineContent = { Text("状态") },
                        supportingContent = {
                            Text(
                                "${MasqueManager.state.label}" +
                                    (MasqueManager.lastMessage.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
                            )
                        },
                        trailingContent = {
                            Switch(
                                checked = MasqueManager.state == MasqueManager.MasqueState.RUNNING,
                                enabled = MasqueManager.state != MasqueManager.MasqueState.STARTING &&
                                    MasqueManager.configFile().exists(),
                                onCheckedChange = { want ->
                                    scope.launch {
                                        if (want) {
                                            val ok = MasqueManager.start(context)
                                            if (!ok) Toast.makeText(context, MasqueManager.lastMessage, Toast.LENGTH_LONG).show()
                                        } else {
                                            MasqueManager.stop()
                                        }
                                        JmApi.refreshNetwork(context)
                                    }
                                },
                            )
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("启动时自动连接") },
                        supportingContent = { Text("启动时自动连接") },
                        trailingContent = {
                            Switch(checked = autoWarp, onCheckedChange = {
                                autoWarp = it
                                prefs.masqueAutoStart = it
                            })
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("传输模式") },
                        supportingContent = {
                            Text(
                                when (masqueTransfer) {
                                    "quic" -> "仅 QUIC"
                                    "tcp" -> "仅 TCP"
                                    else -> "自动换线"
                                }
                            )
                        },
                        modifier = Modifier.clickable { showTransferDialog = true },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("隧道 SNI") },
                        supportingContent = {
                            Text(
                                if (sniList.isEmpty()) {
                                    "默认 speed.cloudflare.com"
                                } else {
                                    "随机抽取（${sniList.size} 个）：" +
                                        sniList.take(2).joinToString("、") +
                                        if (sniList.size > 2) "…" else ""
                                }
                            )
                        },
                        modifier = Modifier.clickable { showSniDialog = true },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("一键注册 WARP") },
                        supportingContent = {
                            Text(if (masqueBusy) "注册中…" else "自动注册")
                        },
                        modifier = Modifier.clickable(enabled = !masqueBusy) {
                            scope.launch {
                                masqueBusy = true
                                val r = runCatching { MasqueManager.register(context, "mobai-android") }
                                masqueBusy = false
                                Toast.makeText(
                                    context,
                                    r.fold({ "注册成功：$it" }, { "注册失败：${it.message}" }),
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("导入配置") },
                        supportingContent = { Text("粘贴配置") },
                        modifier = Modifier.clickable {
                            masqueImportText = ""
                            showMasqueImport = true
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("修改配置") },
                        supportingContent = { Text("直接编辑连接信息 JSON") },
                        modifier = Modifier.clickable(enabled = MasqueManager.configFile().exists()) {
                            editConfigText = MasqueManager.exportText() ?: ""
                            showEditConfig = true
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("导出连接信息") },
                        supportingContent = {
                            Text(if (MasqueManager.configFile().exists()) "导出配置" else "尚未配置")
                        },
                        modifier = Modifier.clickable(enabled = MasqueManager.configFile().exists()) {
                            val text = MasqueManager.exportText()
                            if (text == null) {
                                Toast.makeText(context, "没有可导出的配置", Toast.LENGTH_SHORT).show()
                            } else {
                                masqueExportText = text
                                masqueExportLauncher.launch(
                                    "mobai-masque-" +
                                        java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.getDefault())
                                            .format(java.util.Date()) + ".json"
                                )
                            }
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("通道测试") },
                        supportingContent = { Text("验证出口") },
                        modifier = Modifier.clickable(enabled = MasqueManager.isRunning) {
                            scope.launch {
                                Toast.makeText(context, MasqueManager.test(context), Toast.LENGTH_LONG).show()
                            }
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("隧道流量") },
                        supportingContent = {
                            Text("累计 ${fmtBytes(TunnelStats.totalBytes)} · 本次 ${fmtBytes(TunnelStats.sessionBytes)}")
                        },
                        modifier = Modifier.clickable { showTrafficReset = true },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("实时状态浮标") },
                        supportingContent = { Text("右上角显示延迟/速度/流量") },
                        trailingContent = {
                            Switch(checked = hudEnabled, onCheckedChange = {
                                hudEnabled = it
                                prefs.hudEnabled = it
                            })
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("图片并发数") },
                        supportingContent = {
                            Text(if (prefs.imgConcurrency <= 0) "自动" else "${prefs.imgConcurrency} / 主机")
                        },
                        modifier = Modifier.clickable {
                            imgConc = prefs.imgConcurrency.toString()
                            showConcDialog = true
                        },
                    )
                }
                item {
                    ListItem(
                        headlineContent = { Text("HTTP/2 实验") },
                        supportingContent = { Text("API 多路复用提速；异常时关闭") },
                        trailingContent = {
                            Switch(checked = apiH2, onCheckedChange = {
                                apiH2 = it
                                prefs.apiHttp2 = it
                                com.mobai.jm.data.JmApi.refreshNetwork(context)
                            })
                        },
                    )
                }
            }

            item { SectionTitle("关于") }
            item {
                ListItem(
                    headlineContent = { Text(if (devMode) "禁漫姬（开发者模式）" else "禁漫姬") },
                    supportingContent = { Text("版本 $versionName · 一半是墨，一半是白") },
                    modifier = Modifier.clickable {
                        val now = System.currentTimeMillis()
                        tapCount = if (now - lastTap > 1500L) 1 else tapCount + 1
                        lastTap = now
                        if (tapCount >= 5) {
                            tapCount = 0
                            devMode = !devMode
                            prefs.devMode = devMode
                            Toast.makeText(
                                context,
                                if (devMode) "开发者模式已开启" else "开发者模式已关闭",
                                Toast.LENGTH_SHORT,
                            ).show()
                        }
                    },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("免责声明") },
                    supportingContent = {
                        Text("个人学习项目，请勿用于商业用途")
                    },
                )
            }
        }
    }

    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = { Text("主题") },
            text = {
                Column {
                    ThemeMode.entries.forEach { m ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onModeChange(m)
                                    showThemeDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = mode == m,
                                onClick = {
                                    onModeChange(m)
                                    showThemeDialog = false
                                },
                            )
                            Text(themeLabel(m))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showPageSizeDialog) {
        AlertDialog(
            onDismissRequest = { showPageSizeDialog = false },
            title = { Text("随机推荐每页数量") },
            text = {
                Column {
                    listOf(12, 24, 36, 48).forEach { n ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pageSize = n
                                    prefs.randomPageSize = n
                                    showPageSizeDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = pageSize == n,
                                onClick = {
                                    pageSize = n
                                    prefs.randomPageSize = n
                                    showPageSizeDialog = false
                                },
                            )
                            Text("$n 个/页")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPageSizeDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            title = { Text("清除缓存") },
            text = { Text("将删除所有已缓存的图片与本子详情数据，确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    showClearDialog = false
                    clearing = true
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            StorageUtil.clearCache(context, context.imageLoader)
                        }
                        stats = withContext(Dispatchers.IO) { StorageUtil.totalCacheStats(context) }
                        clearing = false
                        Toast.makeText(context, "缓存已清除", Toast.LENGTH_SHORT).show()
                    }
                }) { Text("确定清除") }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) { Text("取消") }
            },
        )
    }

    if (showPathDialog) {
        AlertDialog(
            onDismissRequest = { showPathDialog = false },
            title = { Text("下载保存位置") },
            text = {
                Column {
                    listOf("Download", "Download/禁漫姬", "Download/漫画", "Download/JM").forEach { p ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { pathDraft = p }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = pathDraft == p, onClick = { pathDraft = p })
                            Text(p)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = pathDraft,
                        onValueChange = { pathDraft = it },
                        label = { Text("自定义（相对 Download）") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val normalized = normalizeDownloadPath(pathDraft)
                    prefs.downloadPath = normalized
                    showPathDialog = false
                    Toast.makeText(context, "已保存：$normalized", Toast.LENGTH_SHORT).show()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showPathDialog = false }) { Text("取消") }
            },
        )
    }

    if (showLogDialog) {
        AlertDialog(
            onDismissRequest = { showLogDialog = false },
            title = { Text("日志级别") },
            text = {
                Column {
                    LogLevel.entries.forEach { level ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    logLevel = level
                                    prefs.logLevel = level.name
                                    DiagLog.setLevel(level)
                                    showLogDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = logLevel == level,
                                onClick = {
                                    logLevel = level
                                    prefs.logLevel = level.name
                                    DiagLog.setLevel(level)
                                    showLogDialog = false
                                },
                            )
                            Text(level.label + if (level == LogLevel.NONE) "（不记录）" else "")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showBlockDialog) {
        AlertDialog(
            onDismissRequest = { showBlockDialog = false },
            title = { Text("首页屏蔽标签") },
            text = {
                Column {
                    Text(
                        "命中标题/作者/分类/标签即隐藏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = blockInput,
                            onValueChange = { blockInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("输入关键词") },
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            BlockTagsStore.add(blockInput)
                            blockInput = ""
                            Toast.makeText(context, "已添加，命中项将从首页/随机页隐去", Toast.LENGTH_SHORT).show()
                        }) { Text("添加") }
                    }
                    Spacer(Modifier.height(4.dp))
                    BlockTagsStore.list.forEach { t ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                t,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = { BlockTagsStore.remove(t) }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "移除",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showBlockDialog = false }) { Text("完成") }
            },
        )
    }

    if (showExportDialog) {
        AlertDialog(
            onDismissRequest = { showExportDialog = false },
            title = { Text("导出数据") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exFav, onCheckedChange = { exFav = it })
                        Text("收藏（含收藏标签）")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exHis, onCheckedChange = { exHis = it })
                        Text("历史（浏览 + 搜索）")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = exCfg, onCheckedChange = { exCfg = it })
                        Text("系统配置（主题/缓存/屏蔽标签等）")
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "将保存为 .json 文件，可在系统文件管理器中自选位置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = exFav || exHis || exCfg,
                    onClick = {
                        showExportDialog = false
                        val name = "mobai-backup-" +
                            java.text.SimpleDateFormat("yyyyMMdd-HHmm", java.util.Locale.getDefault())
                                .format(java.util.Date()) + ".json"
                        exportLauncher.launch(name)
                    },
                ) { Text("导出") }
            },
            dismissButton = {
                TextButton(onClick = { showExportDialog = false }) { Text("取消") }
            },
        )
    }

    if (showImportDialog) {
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("导入数据") },
            text = {
                Column {
                    Text(importInfo, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = imFav, onCheckedChange = { imFav = it })
                        Text("收藏（含标签）")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = imHis, onCheckedChange = { imHis = it })
                        Text("历史（浏览 + 搜索）")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = imCfg, onCheckedChange = { imCfg = it })
                        Text("系统配置（部分项重启后生效）")
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = importObj != null && (imFav || imHis || imCfg),
                    onClick = {
                        showImportDialog = false
                        val obj = importObj
                        if (obj != null) {
                            val msg = DataTransfer.applyImport(context, obj, imFav, imHis, imCfg)
                            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                        }
                        importObj = null
                    },
                ) { Text("合并导入") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("取消") }
            },
        )
    }

    if (showDohModeDialog) {
        AlertDialog(
            onDismissRequest = { showDohModeDialog = false },
            title = { Text("DoH 模式") },
            text = {
                Column {
                    listOf(
                        "off" to "关闭",
                        "system" to "遵循系统",
                        "enhanced" to "增强：失败自动降级",
                        "strict" to "完全：失败即断网",
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    dohMode = value
                                    prefs.dohMode = value
                                    showDohModeDialog = false
                                    applyNetChange()
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = dohMode == value,
                                onClick = {
                                    dohMode = value
                                    prefs.dohMode = value
                                    showDohModeDialog = false
                                    applyNetChange()
                                },
                            )
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showDohModeDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showMasqueImport) {
        AlertDialog(
            onDismissRequest = { showMasqueImport = false },
            title = { Text("导入 MASQUE 配置") },
            text = {
                Column {
                    OutlinedTextField(
                        value = masqueImportText,
                        onValueChange = { masqueImportText = it },
                        label = { Text("粘贴 JSON") },
                        minLines = 4,
                        maxLines = 8,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "支持 usque 配置文件（需含 private_key / endpoint_v4 / id / ipv4 字段）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = masqueImportText.isNotBlank(), onClick = {
                    val r = runCatching { MasqueManager.importConfig(masqueImportText) }
                    showMasqueImport = false
                    Toast.makeText(
                        context,
                        r.fold({ "导入成功：$it" }, { "导入失败：${it.message}" }),
                        Toast.LENGTH_LONG,
                    ).show()
                }) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showMasqueImport = false }) { Text("取消") }
            },
        )
    }

    if (showTunnelModePick) {
        com.mobai.jm.ui.components.PickDialog(
            "隧道模式",
            listOf("warp" to "WARP（默认）", "tor" to "Tor（插件·实验）"),
            tunnelMode,
            {
                tunnelMode = it
                prefs.tunnelMode = it
                com.mobai.jm.util.net.TunnelPluginClient.scan(context)
            },
            { showTunnelModePick = false },
        )
    }

    if (showTorBridgeDialog) {
        AlertDialog(
            onDismissRequest = { showTorBridgeDialog = false },
            title = { Text("网桥配置") },
            text = {
                Column {
                    OutlinedTextField(
                        value = torBridgesDraft,
                        onValueChange = { torBridgesDraft = it },
                        minLines = 6,
                        maxLines = 12,
                        label = { Text("桥行（一行一条）") },
                    )
                    Text(
                        "支持 obfs4 / webtunnel / snowflake 行；留空则直连 Tor",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.torBridges = torBridgesDraft
                    showTorBridgeDialog = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showTorBridgeDialog = false }) { Text("取消") } },
        )
    }

    if (showPdfDialog) {
        AlertDialog(
            onDismissRequest = { showPdfDialog = false },
            title = { Text("PDF 图像质量") },
            text = {
                Column {
                    listOf(
                        "visual" to "视觉无损（原始分辨率）",
                        "max" to "最高（原始分辨率）",
                        "compact" to "压缩（宽 ≤1240，体积最小）",
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    pdfPreset = value
                                    prefs.pdfPreset = value
                                    showPdfDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = pdfPreset == value,
                                onClick = {
                                    pdfPreset = value
                                    prefs.pdfPreset = value
                                    showPdfDialog = false
                                },
                            )
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showPdfDialog = false }) { Text("关闭") }
            },
        )
    }

    when (sdlPick) {
        "fmt" -> com.mobai.jm.ui.components.PickDialog(
            "下载格式",
            listOf("pdf" to "PDF", "zip" to "ZIP", "7z" to "7z", "targz" to "tar.gz"),
            dlFmtS,
            {
                dlFmtS = it
                prefs.downloadFormat = it
            },
            { sdlPick = null },
        )
        "level" -> com.mobai.jm.ui.components.PickDialog(
            "压缩级别",
            com.mobai.jm.util.ArchiveWriter.LEVEL_NAMES.mapIndexed { i, n -> i.toString() to n },
            dlLevelS.toString(),
            {
                dlLevelS = it.toIntOrNull() ?: 3
                prefs.archiveLevel = dlLevelS
            },
            { sdlPick = null },
        )
        "imgq" -> com.mobai.jm.ui.components.PickDialog(
            "压缩包图像质量",
            listOf(
                "visual" to "视觉无损（解密后·同格式·推荐）",
                "lossless" to "像素无损（解密后·体积大）",
                "compact" to "压缩优先（解密后·最小体积）",
                "raw" to "原样直存（不解密·需还原查看）",
            ),
            archImgS,
            {
                archImgS = it
                prefs.archiveImgQuality = it
            },
            { sdlPick = null },
        )
        "pwd" -> com.mobai.jm.ui.components.PickDialog(
            "压缩包密码",
            listOf("none" to "不使用", "fixed" to "固定密码", "random" to "随机密码"),
            dlPwdS,
            {
                dlPwdS = it
                prefs.archivePwdMode = it
            },
            { sdlPick = null },
        )
        "strat" -> com.mobai.jm.ui.components.PickDialog(
            "随机密码方式",
            listOf("append" to "密码写入文件名", "twolevel" to "二级压缩包（内附解压密码.txt）"),
            dlStratS,
            {
                dlStratS = it
                prefs.archivePwdStrategy = it
            },
            { sdlPick = null },
        )
        else -> {}
    }

    if (showPwdInput) {
        AlertDialog(
            onDismissRequest = { showPwdInput = false },
            title = { Text("固定密码") },
            text = {
                OutlinedTextField(
                    value = pwdDraft,
                    onValueChange = { pwdDraft = it },
                    singleLine = true,
                    label = { Text("密码") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    dlFixedS = pwdDraft.trim()
                    prefs.archiveFixedPwd = dlFixedS
                    showPwdInput = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showPwdInput = false }) { Text("取消") } },
        )
    }

    if (showConcDialog) {
        AlertDialog(
            onDismissRequest = { showConcDialog = false },
            title = { Text("图片并发数") },
            text = {
                Column {
                    OutlinedTextField(
                        value = imgConc,
                        onValueChange = { v -> imgConc = v.filter { it.isDigit() }.take(3) },
                        singleLine = true,
                        label = { Text("0 = 自动；1..512") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val n = imgConc.toIntOrNull()?.coerceIn(0, 512) ?: 0
                    prefs.imgConcurrency = n
                    com.mobai.jm.util.net.NetDispatcher.apply(n)
                    com.mobai.jm.util.PageLoader.concurrency = n
                    showConcDialog = false
                    Toast.makeText(
                        context,
                        if (n == 0) "已设为自动" else "已设为 $n",
                        Toast.LENGTH_SHORT,
                    ).show()
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { showConcDialog = false }) { Text("取消") } },
        )
    }

    if (showTrafficReset) {
        AlertDialog(
            onDismissRequest = { showTrafficReset = false },
            title = { Text("清零隧道流量") },
            text = { Text("累计与本次计数将归零，确定吗？") },
            confirmButton = {
                TextButton(onClick = {
                    TunnelStats.resetTotal()
                    showTrafficReset = false
                    Toast.makeText(context, "已清零", Toast.LENGTH_SHORT).show()
                }) { Text("清零") }
            },
            dismissButton = {
                TextButton(onClick = { showTrafficReset = false }) { Text("取消") }
            },
        )
    }

    if (showEditConfig) {
        AlertDialog(
            onDismissRequest = { showEditConfig = false },
            title = { Text("修改连接信息") },
            text = {
                OutlinedTextField(
                    value = editConfigText,
                    onValueChange = { editConfigText = it },
                    minLines = 6,
                    maxLines = 12,
                    label = { Text("JSON") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val r = runCatching { MasqueManager.importConfig(editConfigText) }
                    showEditConfig = false
                    Toast.makeText(
                        context,
                        r.fold({ "已保存" }, { "保存失败：${it.message}" }),
                        Toast.LENGTH_LONG,
                    ).show()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showEditConfig = false }) { Text("取消") }
            },
        )
    }

    if (showSniDialog) {
        AlertDialog(
            onDismissRequest = { showSniDialog = false },
            title = { Text("隧道 SNI") },
            text = {
                Column {
                    Text(
                        "每次连接随机抽取；留空使用 speed.cloudflare.com",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = sniInput,
                            onValueChange = { sniInput = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            placeholder = { Text("如 www.bing.com") },
                        )
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = {
                            val v = sniInput.trim().lowercase()
                            when {
                                v.isEmpty() -> {}
                                !v.matches(Regex("^[a-z0-9.-]+\\.[a-z]{2,}$")) ->
                                    Toast.makeText(context, "请输入合法域名", Toast.LENGTH_SHORT).show()
                                sniList.contains(v) ->
                                    Toast.makeText(context, "已存在", Toast.LENGTH_SHORT).show()
                                else -> {
                                    sniList = sniList + v
                                    prefs.masqueSnis = sniList
                                    sniInput = ""
                                    Toast.makeText(context, "已添加", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }) { Text("添加") }
                    }
                    Spacer(Modifier.height(4.dp))
                    sniList.forEach { s ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                s,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            IconButton(onClick = {
                                sniList = sniList - s
                                prefs.masqueSnis = sniList
                            }) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = "删除",
                                    modifier = Modifier.size(18.dp),
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSniDialog = false }) { Text("完成") }
            },
        )
    }

    if (showTransferDialog) {
        AlertDialog(
            onDismissRequest = { showTransferDialog = false },
            title = { Text("传输模式") },
            text = {
                Column {
                    listOf(
                        "auto" to "自动换线（QUIC 优先）",
                        "quic" to "仅 QUIC",
                        "tcp" to "仅 TCP",
                    ).forEach { (value, label) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    masqueTransfer = value
                                    prefs.masqueTransferMode = value
                                    prefs.masqueLastGood = ""
                                    showTransferDialog = false
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(
                                selected = masqueTransfer == value,
                                onClick = {
                                    masqueTransfer = value
                                    prefs.masqueTransferMode = value
                                    prefs.masqueLastGood = ""
                                    showTransferDialog = false
                                },
                            )
                            Text(label)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showTransferDialog = false }) { Text("关闭") }
            },
        )
    }

    if (showSplitDialog) {
        AlertDialog(
            onDismissRequest = { showSplitDialog = false },
            title = { Text("SNI 切片参数") },
            text = {
                Column {
                    OutlinedTextField(
                        value = sniSplitPos,
                        onValueChange = { sniSplitPos = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("切片位置（字节，1~1024）") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = sniSplitDelay,
                        onValueChange = { sniSplitDelay = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("间隔（毫秒，0~500）") },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.sniSplitPos = sniSplitPos.toIntOrNull()?.coerceIn(1, 1024) ?: 40
                    prefs.sniSplitDelay = sniSplitDelay.toIntOrNull()?.coerceIn(0, 500) ?: 50
                    sniSplitPos = prefs.sniSplitPos.toString()
                    sniSplitDelay = prefs.sniSplitDelay.toString()
                    showSplitDialog = false
                    applyNetChange()
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showSplitDialog = false }) { Text("取消") }
            },
        )
    }

    if (showDohDialog) {
        AlertDialog(
            onDismissRequest = { showDohDialog = false },
            title = { Text("DoH 配置") },
            text = {
                Column {
                    OutlinedTextField(
                        value = dohUrl,
                        onValueChange = { dohUrl = it },
                        label = { Text("DoH 地址模板") },
                        placeholder = { Text("https://*.gl.doh.tw/*") },
                        singleLine = true,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "支持 * 通配（任意合法字符），默认自动展开随机子域；改完点“保存并应用”。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    prefs.dohUrl = dohUrl.trim()
                    applyNetChange()
                    showDohDialog = false
                    Toast.makeText(context, "已保存并应用", Toast.LENGTH_SHORT).show()
                }) { Text("保存并应用") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        scope.launch {
                            val r = withContext(Dispatchers.IO) { DohDns.test(dohUrl) }
                            Toast.makeText(context, r, Toast.LENGTH_LONG).show()
                        }
                    }) { Text("测试") }
                    TextButton(onClick = { showDohDialog = false }) { Text("关闭") }
                }
            },
        )
    }
}

private fun dohModeLabel(mode: String): String = when (mode) {
    "system" -> "遵循系统（系统 Private DNS 生效）"
    "enhanced" -> "增强：DoH 优先，失败自动降级"
    "strict" -> "完全：DoH 失败即终止连接（Error 日志）"
    else -> "关闭"
}

private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "跟随系统"
    ThemeMode.LIGHT -> "浅色"
    ThemeMode.DARK -> "深色"
}

private fun normalizeDownloadPath(raw: String): String {
    var p = raw.trim().replace('\\', '/').trim('/')
    if (!p.startsWith("Download")) p = "Download/$p"
    return p.ifBlank { "Download" }
}

@Composable
private fun SectionTitle(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 20.dp, top = 20.dp, bottom = 4.dp),
    )
}
