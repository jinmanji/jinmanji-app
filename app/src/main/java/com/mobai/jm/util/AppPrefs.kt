package com.mobai.jm.util

import android.content.Context

/** 应用偏好（缓存开关 / 日志级别 / 下载路径） */
class AppPrefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("mobai_prefs", Context.MODE_PRIVATE)

    var autoCache: Boolean
        get() = sp.getBoolean("auto_cache", true)
        set(value) {
            sp.edit().putBoolean("auto_cache", value).apply()
        }

    /** 日志级别（LogLevel 名称，默认 ERROR） */
    var logLevel: String
        get() = sp.getString("log_level", LogLevel.ERROR.name) ?: LogLevel.ERROR.name
        set(value) {
            sp.edit().putString("log_level", value).apply()
        }

    // ── 实验性网络（开发者模式）──
    var sniSplit: Boolean
        get() = sp.getBoolean("sni_split", false)
        set(value) {
            sp.edit().putBoolean("sni_split", value).apply()
        }

    var sniSplitPos: Int
        get() = sp.getInt("sni_split_pos", 40)
        set(value) {
            sp.edit().putInt("sni_split_pos", value).apply()
        }

    var sniSplitDelay: Int
        get() = sp.getInt("sni_split_delay", 50)
        set(value) {
            sp.edit().putInt("sni_split_delay", value).apply()
        }

    var sniBypass: Boolean
        get() = sp.getBoolean("sni_bypass", false)
        set(value) {
            sp.edit().putBoolean("sni_bypass", value).apply()
        }

    /** API 走 HTTP/2（实验，默认关；CN 网络下 h2 假死会导致请求卡住） */
    var apiHttp2: Boolean
        get() = sp.getBoolean("api_http2", false)
        set(value) {
            sp.edit().putBoolean("api_http2", value).apply()
        }

    /** 隧道模式：warp / tor（Tor 隧道插件） */
    var tunnelMode: String
        get() = sp.getString("tunnel_mode", "warp") ?: "warp"
        set(value) {
            sp.edit().putString("tunnel_mode", value).apply()
        }

    /** Tor 网桥配置（多行，原样传给插件） */
    var torBridges: String
        get() = sp.getString("tor_bridges", "") ?: ""
        set(value) {
            sp.edit().putString("tor_bridges", value).apply()
        }

    /** 压缩包图像质量：lossless(原图无损) / visual(视觉无损 WebP 95) / compact(WebP 80) */
    var archiveImgQuality: String
        get() = sp.getString("archive_img_quality", "visual") ?: "visual"
        set(value) {
            sp.edit().putString("archive_img_quality", value).apply()
        }

    /** 下载格式：pdf / zip / 7z / targz */
    var downloadFormat: String
        get() = sp.getString("download_format", "pdf") ?: "pdf"
        set(value) {
            sp.edit().putString("download_format", value).apply()
        }

    /** 压缩级别预设索引 0..5（储存/极速/快速/标准/最大/极限） */
    var archiveLevel: Int
        get() = sp.getInt("archive_level", 3)
        set(value) {
            sp.edit().putInt("archive_level", value.coerceIn(0, 5)).apply()
        }

    /** 压缩包密码模式：none / fixed / random */
    var archivePwdMode: String
        get() = sp.getString("archive_pwd_mode", "none") ?: "none"
        set(value) {
            sp.edit().putString("archive_pwd_mode", value).apply()
        }

    /** 固定密码内容 */
    var archiveFixedPwd: String
        get() = sp.getString("archive_fixed_pwd", "") ?: ""
        set(value) {
            sp.edit().putString("archive_fixed_pwd", value).apply()
        }

    /** 随机密码策略：append=写入文件名 / twolevel=二级压缩包（内附解压密码.txt） */
    var archivePwdStrategy: String
        get() = sp.getString("archive_pwd_strategy", "append") ?: "append"
        set(value) {
            sp.edit().putString("archive_pwd_strategy", value).apply()
        }

    /** 下载设置弹窗「以后不再弹出」 */
    var downloadRemember: Boolean
        get() = sp.getBoolean("download_remember", false)
        set(value) {
            sp.edit().putBoolean("download_remember", value).apply()
        }

    /** PDF 图像质量预设：visual(视觉无损 97) / max(100) / compact(90 且宽度≤1240) */
    var pdfPreset: String
        get() = sp.getString("pdf_preset", "visual") ?: "visual"
        set(value) {
            sp.edit().putString("pdf_preset", value).apply()
        }

    /** 图片并发数量（0 = 自动；1..512 手动） */
    var imgConcurrency: Int
        get() = sp.getInt("img_concurrency", 0)
        set(value) {
            sp.edit().putInt("img_concurrency", value.coerceIn(0, 512)).apply()
        }

    /** 实时状态浮标（右上角网络 HUD，默认开） */
    var hudEnabled: Boolean
        get() = sp.getBoolean("hud_enabled", true)
        set(value) {
            sp.edit().putBoolean("hud_enabled", value).apply()
        }

    /** 隐私模式：数据存应用私有目录（默认开，重启生效） */
    var privacyMode: Boolean
        get() = sp.getBoolean("privacy_mode", true)
        set(value) {
            sp.edit().putBoolean("privacy_mode", value).apply()
        }

    /** 隧道 SNI 列表（JSON 数组字符串；每次连接随机抽取；空 = 默认 speed.cloudflare.com） */
    var masqueSnis: List<String>
        get() = runCatching {
            val arr = org.json.JSONArray(sp.getString("masque_snis", "[]") ?: "[]")
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
        set(value) {
            val arr = org.json.JSONArray()
            value.forEach { arr.put(it) }
            sp.edit().putString("masque_snis", arr.toString()).apply()
        }

    /** MASQUE 传输模式：auto / quic / tcp（默认 auto 自动换线） */
    var masqueTransferMode: String
        get() = sp.getString("masque_transfer_mode", "auto") ?: "auto"
        set(value) {
            sp.edit().putString("masque_transfer_mode", value).apply()
        }

    /** 上次成功的线路（优先重试） */
    var masqueLastGood: String
        get() = sp.getString("masque_last_good", "") ?: ""
        set(value) {
            sp.edit().putString("masque_last_good", value).apply()
        }

    /** 浏览器网络模式：auto / direct / tunnel（默认 auto） */
    var browserNetMode: String
        get() = sp.getString("browser_net_mode", "auto") ?: "auto"
        set(value) {
            sp.edit().putString("browser_net_mode", value).apply()
        }

    /** 浏览器内禁止截屏（默认开） */
    var browserSecure: Boolean
        get() = sp.getBoolean("browser_secure", true)
        set(value) {
            sp.edit().putBoolean("browser_secure", value).apply()
        }

    /** 启动时自动连接 MASQUE/WARP（默认关） */
    var masqueAutoStart: Boolean
        get() = sp.getBoolean("masque_auto_start", false)
        set(value) {
            sp.edit().putBoolean("masque_auto_start", value).apply()
        }

    /** DoH 模式：off / system / enhanced / strict（旧布尔值自动迁移） */
    var dohMode: String
        get() = sp.getString("doh_mode", null)
            ?: if (sp.getBoolean("doh_enabled", false)) "enhanced" else "off"
        set(value) {
            sp.edit().putString("doh_mode", value).apply()
        }

    var dohUrl: String
        get() = sp.getString("doh_url", "https://*.gl.doh.tw/*") ?: "https://*.gl.doh.tw/*"
        set(value) {
            sp.edit().putString("doh_url", value).apply()
        }

    var echEnabled: Boolean
        get() = sp.getBoolean("ech_enabled", false)
        set(value) {
            sp.edit().putBoolean("ech_enabled", value).apply()
        }

    /** 随机推荐每页数量（默认 24） */
    var randomPageSize: Int
        get() = sp.getInt("random_page_size", 24)
        set(value) {
            sp.edit().putInt("random_page_size", value).apply()
        }

    /** 开发者模式（连续点击关于页“禁漫姬”5 次开关；默认关闭） */
    var devMode: Boolean
        get() = sp.getBoolean("dev_mode", false)
        set(value) {
            sp.edit().putBoolean("dev_mode", value).apply()
        }

    /** 是否启用浏览历史（默认开） */
    var historyEnabled: Boolean
        get() = sp.getBoolean("history_enabled", true)
        set(value) {
            sp.edit().putBoolean("history_enabled", value).apply()
        }

    /** 收藏页排序（time_desc / time_asc / title_asc / title_desc） */
    var favSort: String
        get() = sp.getString("fav_sort", "time_desc") ?: "time_desc"
        set(value) {
            sp.edit().putString("fav_sort", value).apply()
        }

    /** 历史记录页排序 */
    var historySort: String
        get() = sp.getString("history_sort", "time_desc") ?: "time_desc"
        set(value) {
            sp.edit().putString("history_sort", value).apply()
        }

    /** PDF 下载保存位置（相对 Download，如 "Download" 或 "Download/禁漫姬"） */
    var downloadPath: String
        get() = sp.getString("download_path", "Download") ?: "Download"
        set(value) {
            sp.edit().putString("download_path", value).apply()
        }

    /** 阅读器横向翻页模式（默认关 = 纵向滚动） */
    var readerHorizontal: Boolean
        get() = sp.getBoolean("reader_horizontal", false)
        set(value) {
            sp.edit().putBoolean("reader_horizontal", value).apply()
        }
}
