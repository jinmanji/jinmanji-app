package com.mobai.jm.ui.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.mobai.jm.data.BrowserRecord
import com.mobai.jm.data.BrowserStore
import com.mobai.jm.data.UserScript
import com.mobai.jm.util.AppPrefs
import com.mobai.jm.util.DiagLog
import com.mobai.jm.util.net.MasqueManager
import com.mobai.jm.util.net.NetTweaks
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.ResponseBody
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

private enum class BrowserMode(val label: String) {
    WEB(""), BOOKMARKS("收藏夹"), HISTORY("历史"), DOWNLOADS("下载"), SCRIPTS("用户脚本")
}

private const val START_PAGE = """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;margin:20px;background:#faf9f7;color:#222}
a{display:block;padding:14px;margin:10px 0;background:#eef2f7;border-radius:12px;text-decoration:none;color:#1b4a77;font-size:16px}
p{color:#888;font-size:12px}</style></head>
<body><h3>禁漫姬浏览器</h3>
<a href="https://18comic.vip">禁漫社区（网页版）</a>
<a href="https://www.bing.com">Bing</a>
<a href="https://cloudflare.com/cdn-cgi/trace">检查通道（warp=on）</a>
<p>请求走 WARP/DoH（按 App 设置）· 支持用户脚本</p>
</body></html>"""

/**
 * 浏览器专用 HTTP：
 *  - main：下载等常规用途（跟随重定向）
 *  - intercept：页面资源拦截（不跟随重定向，3xx 透传给 WebView，保证 URL/相对链接正确）
 *  - 带 64MB 磁盘缓存；挂载全部网络实验特性（WARP 动态代理 + DoH）
 */
private object BrowserHttp {
    private const val CACHE_MB = 64L

    @Volatile
    private var mainClient: OkHttpClient? = null

    @Volatile
    private var interceptClient: OkHttpClient? = null

    fun refresh(context: Context) {
        mainClient = build(context, followRedirects = true)
        interceptClient = build(context, followRedirects = false)
    }

    fun get(context: Context): OkHttpClient =
        mainClient ?: build(context, true).also { mainClient = it }

    fun forIntercept(context: Context): OkHttpClient =
        interceptClient ?: build(context, false).also { interceptClient = it }

    private fun build(context: Context, followRedirects: Boolean): OkHttpClient {
        val b = OkHttpClient.Builder()
            .followRedirects(followRedirects)
            .followSslRedirects(followRedirects)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .cache(
                runCatching {
                    Cache(File(context.cacheDir, "browser_http"), CACHE_MB * 1024 * 1024)
                }.getOrNull()
            )
        NetTweaks.apply(b, context)
        return b.build()
    }
}

private class UserscriptBridge(context: Context) {
    private val sp = context.getSharedPreferences("mobai_userscripts", Context.MODE_PRIVATE)

    @JavascriptInterface
    fun get(key: String): String = sp.getString(key, "") ?: ""

    @JavascriptInterface
    fun set(key: String, value: String) {
        sp.edit().putString(key, value).apply()
    }

    @JavascriptInterface
    fun log(msg: String) {
        DiagLog.d("[js] $msg")
    }
}

/**
 * 内置极简浏览器：
 *  - 正常渲页面；资源请求经 App 网络栈（WARP 开则走隧道，DNS 跟随 DoH 设置）
 *  - 默认禁止截屏（设置可关）；支持用户脚本（GM_* 兼容）
 *  - 收藏 / 下载 / 历史（时间排序可切），浏览器内下载走同一网络栈并实时写入 Download
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = context as? Activity
    val prefs = remember { AppPrefs(context) }
    val scope = rememberCoroutineScope()
    val pageBackground = MaterialTheme.colorScheme.background
    val pageBackgroundArgb = pageBackground.toArgb()

    var mode by remember { mutableStateOf(BrowserMode.WEB) }
    var urlText by remember { mutableStateOf("") }
    var progress by remember { mutableStateOf(0) }
    var menuOpen by remember { mutableStateOf(false) }
    var sortAsc by remember { mutableStateOf(false) }
    var netMode by remember { mutableStateOf(prefs.browserNetMode) }

    var showAddScript by remember { mutableStateOf(false) }
    var scriptName by remember { mutableStateOf("") }
    var scriptMatch by remember { mutableStateOf("*") }
    var scriptCode by remember { mutableStateOf("") }
    var scriptError by remember { mutableStateOf<String?>(null) }

    val webState = remember { mutableStateOf<WebView?>(null) }

    // 进入时按当前网络设置重建浏览器专用客户端
    LaunchedEffect(Unit) { BrowserHttp.refresh(context) }

    // 默认禁止截屏（设置可关）
    LaunchedEffect(Unit) {
        if (prefs.browserSecure) {
            activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            webState.value?.let { v ->
                runCatching { v.stopLoading() }
                runCatching { (v.parent as? ViewGroup)?.removeView(v) }
                runCatching { v.destroy() }
            }
        }
    }

    // 页面模式下暂停 WebView（省电/省流量），返回页面时恢复
    LaunchedEffect(mode) {
        webState.value?.let { w -> if (mode == BrowserMode.WEB) w.onResume() else w.onPause() }
    }

    fun navigate(raw: String) {
        var u = raw.trim()
        if (u.isEmpty()) return
        if (!u.startsWith("http://") && !u.startsWith("https://") && !u.startsWith("about:")) {
            u = if (u.contains(" ") || !u.contains(".")) {
                "https://www.bing.com/search?q=" + java.net.URLEncoder.encode(u, "UTF-8")
            } else {
                "https://$u"
            }
        }
        mode = BrowserMode.WEB
        urlText = u
        webState.value?.loadUrl(u)
    }

    BackHandler {
        when {
            mode != BrowserMode.WEB -> mode = BrowserMode.WEB
            webState.value?.canGoBack() == true -> webState.value?.goBack()
            else -> onBack()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // 工具栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = {
                when {
                    mode != BrowserMode.WEB -> mode = BrowserMode.WEB
                    webState.value?.canGoBack() == true -> webState.value?.goBack()
                    else -> onBack()
                }
            }) {
                Icon(Icons.Default.ArrowBack, contentDescription = "返回")
            }
            OutlinedTextField(
                value = urlText,
                onValueChange = { urlText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text(if (mode == BrowserMode.WEB) "输入网址或关键词" else mode.label) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { navigate(urlText) }),
            )
            if (mode == BrowserMode.WEB && progress in 1..99) {
                IconButton(onClick = { webState.value?.stopLoading() }) {
                    Icon(Icons.Default.Close, contentDescription = "停止")
                }
            } else if (mode == BrowserMode.WEB) {
                IconButton(onClick = { webState.value?.reload() }) {
                    Icon(Icons.Default.Refresh, contentDescription = "刷新")
                }
            }
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "菜单")
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("刷新") },
                        onClick = { menuOpen = false; webState.value?.reload() },
                    )
                    DropdownMenuItem(
                        text = { Text("收藏本页") },
                        onClick = {
                            menuOpen = false
                            val w = webState.value
                            val u = w?.url ?: urlText
                            if (u.isNotBlank() && u != "about:blank") {
                                val added = BrowserStore.toggleBookmark(u, w?.title ?: u)
                                Toast.makeText(context, if (added) "已收藏" else "已取消收藏", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                    listOf(
                        "auto" to "网络：自动",
                        "direct" to "网络：直连",
                        "tunnel" to "网络：强制隧道",
                    ).forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label) },
                            onClick = {
                                netMode = value
                                prefs.browserNetMode = value
                                menuOpen = false
                                webState.value?.reload()
                            },
                            trailingIcon = {
                                if (netMode == value) Icon(Icons.Default.Check, contentDescription = null)
                            },
                        )
                    }
                    HorizontalDivider()
                    BrowserMode.entries.forEach { m ->
                        if (m != BrowserMode.WEB) {
                            DropdownMenuItem(
                                text = { Text(m.label) },
                                onClick = { menuOpen = false; mode = m },
                            )
                        }
                    }
                }
            }
        }

        if (mode == BrowserMode.WEB && progress in 1..99) {
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // WebView 常驻（记录页作为覆盖层，避免页面状态被卸载/重载）
        Box(Modifier.weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    webState.value ?: WebView(ctx).apply {
                        setBackgroundColor(pageBackgroundArgb)
                        settings.apply {
                            javaScriptEnabled = true
                            domStorageEnabled = true
                            useWideViewPort = true
                            loadWithOverviewMode = true
                            builtInZoomControls = true
                            displayZoomControls = false
                            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                            // 去掉 WebView 的 "wv" 标记，避免 Cloudflare/Google 将我们判为不可信环境
                            userAgentString = userAgentString.replace("; wv", "").replace("Version/4.0 ", "")
                        }
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        runCatching { cm.setAcceptThirdPartyCookies(this, true) }
                        addJavascriptInterface(UserscriptBridge(ctx), "MobaiGM")
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                url?.let { if (it != "about:blank") urlText = it }
                            }

                            override fun onPageFinished(view: WebView?, url: String?) {
                                url?.let { if (it != "about:blank") urlText = it }
                                view?.let { v ->
                                    val u = url.orEmpty()
                                    if (u.startsWith("http")) {
                                        v.title?.let { BrowserStore.addHistory(u, it) }
                                    }
                                    injectScripts(v, u)
                                }
                            }

                            // 前进/后退/重定向都会触发：同步地址栏（修复返回后 URL 不更新）
                            override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                if (!url.isNullOrBlank() && url != "about:blank") urlText = url
                            }

                            override fun shouldInterceptRequest(
                                view: WebView?,
                                request: WebResourceRequest?,
                            ): WebResourceResponse? {
                                request ?: return null
                                return interceptRequest(ctx, request)
                            }

                            override fun onReceivedError(
                                view: WebView?,
                                request: WebResourceRequest?,
                                error: WebResourceError?,
                            ) {
                                if (view != null && request?.isForMainFrame == true) {
                                    val u = request.url.toString()
                                    if (u.startsWith("http")) {
                                        val msg = error?.description?.toString() ?: "加载失败"
                                        view.loadDataWithBaseURL(null, errorPage(u, msg), "text/html", "utf-8", null)
                                    }
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView?, newProgress: Int) {
                                progress = newProgress
                            }
                        }
                        setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
                            val name = URLUtil.guessFileName(url, contentDisposition, mimetype)
                            Toast.makeText(ctx, "开始下载：$name", Toast.LENGTH_SHORT).show()
                            scope.launch {
                                val ok = withContext(Dispatchers.IO) {
                                    runCatching {
                                        val req = Request.Builder().url(url)
                                            .apply { userAgent?.takeIf { it.isNotBlank() }?.let { header("User-Agent", it) } }
                                            .build()
                                        BrowserHttp.get(ctx).newCall(req).execute().use { r ->
                                            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                                            val body = r.body ?: throw IllegalStateException("空响应")
                                            val saved = streamToDownloads(ctx, name, mimetype, body)
                                                ?: throw IllegalStateException("保存失败")
                                            BrowserStore.addDownload(
                                                BrowserRecord(url, name, System.currentTimeMillis(), saved)
                                            )
                                        }
                                        true
                                    }.getOrElse {
                                        DiagLog.w("浏览器下载失败", it)
                                        false
                                    }
                                }
                                Toast.makeText(
                                    ctx,
                                    if (ok) "已保存到下载文件夹" else "下载失败",
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }
                        webState.value = this
                        loadDataWithBaseURL(null, START_PAGE, "text/html", "utf-8", null)
                    }
                },
                update = {},
                modifier = Modifier.fillMaxSize(),
            )

            if (mode != BrowserMode.WEB) {
                Surface(
                    color = pageBackground,
                    modifier = Modifier.fillMaxSize(),
                ) {
                    when (mode) {
                        BrowserMode.BOOKMARKS -> RecordsPage(
                            title = "收藏夹",
                            records = BrowserStore.bookmarks,
                            sortAsc = sortAsc,
                            onToggleSort = { sortAsc = !sortAsc },
                            emptyHint = "还没有收藏的网页",
                            onOpen = { navigate(it.url) },
                            onDelete = { BrowserStore.removeBookmark(it.url) },
                            extraLabel = null,
                        )

                        BrowserMode.HISTORY -> RecordsPage(
                            title = "历史记录",
                            records = BrowserStore.history,
                            sortAsc = sortAsc,
                            onToggleSort = { sortAsc = !sortAsc },
                            emptyHint = "还没有浏览历史",
                            onOpen = { navigate(it.url) },
                            onDelete = { BrowserStore.removeHistory(it.url) },
                            onClear = { BrowserStore.clearHistory() },
                            extraLabel = null,
                        )

                        BrowserMode.DOWNLOADS -> RecordsPage(
                            title = "下载",
                            records = BrowserStore.downloads,
                            sortAsc = sortAsc,
                            onToggleSort = { sortAsc = !sortAsc },
                            emptyHint = "还没有下载记录",
                            onOpen = { rec ->
                                val path = rec.extra
                                val opened = runCatching {
                                    val uri = android.provider.DocumentsContract.buildDocumentUri(
                                        "com.android.externalstorage.documents",
                                        "primary:$path",
                                    )
                                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                        .setDataAndType(uri, "*/*")
                                        .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    context.startActivity(intent)
                                }.isSuccess
                                if (!opened) {
                                    Toast.makeText(context, "文件位置：$path", Toast.LENGTH_LONG).show()
                                }
                            },
                            onDelete = { BrowserStore.removeDownload(it) },
                            extraLabel = { it.extra },
                        )

                        BrowserMode.SCRIPTS -> ScriptsPage(
                            onAdd = {
                                scriptName = ""
                                scriptMatch = "*"
                                scriptCode = ""
                                scriptError = null
                                showAddScript = true
                            },
                        )

                        else -> Box(Modifier.fillMaxSize())
                    }
                }
            }
        }
    }

    if (showAddScript) {
        AlertDialog(
            onDismissRequest = { showAddScript = false },
            title = { Text("添加用户脚本") },
            text = {
                Column {
                    OutlinedTextField(
                        value = scriptName,
                        onValueChange = { scriptName = it },
                        singleLine = true,
                        label = { Text("名称") },
                    )
                    OutlinedTextField(
                        value = scriptMatch,
                        onValueChange = { scriptMatch = it },
                        singleLine = true,
                        label = { Text("生效网址（* 通配，逗号分隔）") },
                    )
                    OutlinedTextField(
                        value = scriptCode,
                        onValueChange = { scriptCode = it },
                        minLines = 4,
                        maxLines = 8,
                        label = { Text("JS 代码（支持 GM_setValue / GM_getValue / GM_log）") },
                    )
                    if (scriptError != null) {
                        Text(
                            scriptError ?: "",
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val err = BrowserStore.addScript(UserScript(scriptName, scriptMatch, scriptCode))
                    if (err == null) showAddScript = false else scriptError = err
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = { showAddScript = false }) { Text("取消") }
            },
        )
    }
}

/** 错误页（主框架加载失败时替换显示，带重试） */
private fun errorPage(url: String, message: String): String {
    val safeUrl = url.replace("\"", "%22")
    val safeMsg = message.replace("<", "&lt;")
    return """<!doctype html><html><head><meta name="viewport" content="width=device-width,initial-scale=1">
<style>body{font-family:sans-serif;margin:24px;background:#faf9f7;color:#333}
a{display:inline-block;margin-top:14px;padding:10px 18px;background:#1b4a77;color:#fff;border-radius:10px;text-decoration:none}
p{color:#888;font-size:13px;word-break:break-all}</style></head>
<body><h3>页面加载失败</h3><p>$safeMsg</p><p>$safeUrl</p><a href="$safeUrl">重试</a></body></html>"""
}

/**
 * 页面资源拦截：经 App 网络栈转发。
 * 关键点：
 *  - 不跟随重定向：3xx + Location 原样交给 WebView（否则 URL 栏/相对链接会错乱）
 *  - 按 Content-Type 传 charset；二进制不带编码
 *  - 剥离逐跳/编码头；Set-Cookie 等原样透传
 *  - 未开启 WARP 且未开 DoH 时返回 null（交给系统 WebView，最快）
 */
private fun interceptRequest(context: Context, request: WebResourceRequest): WebResourceResponse? {
    val prefs = AppPrefs(context)
    val netMode = prefs.browserNetMode
    if (netMode == "direct") return null
    val doh = prefs.dohMode == "enhanced" || prefs.dohMode == "strict"
    if (netMode != "tunnel" && !MasqueManager.isRunning && !doh) return null
    if (request.method != "GET" && request.method != "HEAD") {
        DiagLog.d("浏览器非 GET 直连: ${request.method} ${request.url}")
        return null
    }
    val url = request.url.toString()
    if (!url.startsWith("http")) return null
    return try {
        val req = Request.Builder().url(url)
            .apply {
                request.requestHeaders.forEach { (k, v) ->
                    val lk = k.lowercase()
                    if (lk !in setOf(
                            "host", "connection", "content-length", "accept-encoding",
                            "transfer-encoding", "keep-alive", "upgrade", "te", "trailer",
                        )
                    ) {
                        header(k, v)
                    }
                }
            }
            .method(request.method, null)
            .build()
        val resp = BrowserHttp.forIntercept(context).newCall(req).execute()
        val body = resp.body ?: return null
        // 逐条写入 CookieManager：多个 Set-Cookie 用逗号拼接会损坏 Cookie 链（Cloudflare 质询失败的关键原因）
        runCatching {
            resp.headers("Set-Cookie").forEach { c ->
                if (c.isNotBlank()) CookieManager.getInstance().setCookie(url, c)
            }
        }
        val contentType = resp.header("Content-Type").orEmpty()
        val mime = contentType.substringBefore(';').trim().ifBlank { "text/html" }
        val charset = Regex("charset=([A-Za-z0-9_\\-.]+)", RegexOption.IGNORE_CASE)
            .find(contentType)?.groupValues?.get(1)
        val encoding = when {
            charset != null -> charset
            mime.startsWith("text/") || mime.contains("html") || mime.contains("json") -> "utf-8"
            else -> null
        }
        val headers = resp.headers.toMultimap()
            .filterKeys {
                it.lowercase() !in setOf(
                    "content-encoding", "content-length", "transfer-encoding",
                    "connection", "keep-alive", "trailer", "upgrade", "set-cookie",
                )
            }
            .mapValues { it.value.joinToString(", ") }
        WebResourceResponse(mime, encoding, resp.code, resp.message.ifBlank { "OK" }, headers, body.byteStream())
    } catch (e: Exception) {
        DiagLog.w("浏览器请求失败: $url", e)
        null
    }
}

/** 注入用户脚本（GM_* 简化兼容） */
private fun injectScripts(view: WebView, url: String) {
    if (!url.startsWith("http")) return
    val scripts = BrowserStore.scriptsFor(url)
    if (scripts.isEmpty()) return
    val gmPrelude = """
        (function(){
          if (!window.GM_setValue) {
            window.GM_setValue = function(k, v){ try { MobaiGM.set(String(k), JSON.stringify(v)); } catch(e){} };
            window.GM_getValue = function(k, d){ try { var s = MobaiGM.get(String(k)); return s ? JSON.parse(s) : d; } catch(e){ return d; } };
            window.GM_log = function(m){ try { MobaiGM.log(String(m)); } catch(e){} };
          }
        })();
    """.trimIndent()
    val code = gmPrelude + "\n" + scripts.joinToString("\n") { s ->
        "try {\n${s.code}\n} catch (e) { try { MobaiGM.log('[${s.name}] ' + e); } catch (_) {} }"
    }
    runCatching { view.evaluateJavascript(code, null) }
}

/** 流式写入系统下载目录（不再整段读进内存） */
private fun streamToDownloads(
    context: Context,
    name: String,
    mime: String,
    body: ResponseBody,
): String? {
    val safeName = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(100).ifBlank { "download.bin" }
    val safeMime = mime.ifBlank { "application/octet-stream" }
    return try {
        if (Build.VERSION.SDK_INT >= 29) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, safeName)
                put(MediaStore.Downloads.MIME_TYPE, safeMime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
            resolver.openOutputStream(uri)?.use { out -> body.byteStream().use { it.copyTo(out) } } ?: return null
            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            "Download/$safeName"
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "",
            ).apply { mkdirs() }
            val f = File(dir, safeName)
            body.byteStream().use { input -> f.outputStream().use { input.copyTo(it) } }
            f.absolutePath
        }
    } catch (e: Exception) {
        DiagLog.w("保存下载失败", e)
        null
    }
}

@Composable
private fun RecordsPage(
    title: String,
    records: List<BrowserRecord>,
    sortAsc: Boolean,
    onToggleSort: () -> Unit,
    emptyHint: String,
    onOpen: (BrowserRecord) -> Unit,
    onDelete: (BrowserRecord) -> Unit,
    onClear: (() -> Unit)? = null,
    extraLabel: ((BrowserRecord) -> String)?,
) {
    val sorted = remember(records.toList(), sortAsc) {
        if (sortAsc) records.sortedBy { it.time } else records.sortedByDescending { it.time }
    }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onToggleSort) { Text(if (sortAsc) "旧→新" else "新→旧") }
            if (onClear != null) {
                TextButton(onClick = onClear) { Text("清空") }
            }
        }
        if (sorted.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(emptyHint, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(sorted, key = { it.url + it.time }) { rec ->
                    ListItem(
                        headlineContent = {
                            Text(rec.title.ifBlank { rec.url }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        },
                        supportingContent = {
                            val extra = extraLabel?.invoke(rec)
                            Text(
                                text = (if (extra.isNullOrBlank()) rec.url else extra) + " · " + fmt.format(Date(rec.time)),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = { onDelete(rec) }) {
                                Icon(Icons.Default.Delete, contentDescription = "删除")
                            }
                        },
                        modifier = Modifier.clickable { onOpen(rec) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ScriptsPage(onAdd: () -> Unit) {
    Column(Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("用户脚本", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            TextButton(onClick = onAdd) { Text("添加") }
        }
        if (BrowserStore.scripts.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "还没有脚本\n支持 GM_setValue / GM_getValue / GM_log",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(BrowserStore.scripts.toList(), key = { it.name }) { s ->
                    ListItem(
                        headlineContent = { Text(s.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { Text(s.matches.ifBlank { "*" }, maxLines = 1) },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Switch(
                                    checked = s.enabled,
                                    onCheckedChange = { BrowserStore.toggleScript(s.name) },
                                )
                                Spacer(Modifier.width(4.dp))
                                IconButton(onClick = { BrowserStore.removeScript(s.name) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "删除")
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}
