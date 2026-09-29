package com.mobai.jm

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.mobai.jm.util.DiagLog
import com.mobai.jm.util.net.NetTweaks
import okhttp3.Dispatcher
import com.mobai.jm.util.StorageUtil
import okhttp3.OkHttpClient
import okhttp3.Protocol
import java.util.concurrent.TimeUnit
import com.mobai.jm.util.CrashHandler

/**
 * 全局配置 Coil 图片加载器：
 *  - 强制 HTTP/1.1（该网络环境下 HTTP/2 连接这些 CDN 容易卡死）
 *  - 自定义持久化磁盘缓存目录（可展示大小/一键清除）
 *  - respectCacheHeaders=false：强制缓存图片（节省流量）
 *  - 失败/慢速日志写入 mobai.log
 */
class MoBaiApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        // 全局崩溃兜底（提示页跑在 :crash 独立进程，不受主进程被杀影响）
        CrashHandler.install(this)
    }

    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .okHttpClient {
                val b = OkHttpClient.Builder()
                    // 图片 CDN 支持 HTTP/2：多路复用大幅降低首图/多图并发延迟（旧版强制 H1.1 已过时）
                    .protocols(listOf(Protocol.HTTP_2, Protocol.HTTP_1_1))
                    .connectTimeout(6, TimeUnit.SECONDS)
                    .readTimeout(20, TimeUnit.SECONDS)
                    .callTimeout(25, TimeUnit.SECONDS)
                    .dispatcher(com.mobai.jm.util.net.NetDispatcher.dispatcher)
                    .connectionPool(com.mobai.jm.util.net.NetDispatcher.pool)
                    .addInterceptor { chain ->
                        val request = chain.request()
                        val start = System.currentTimeMillis()
                        val response = chain.proceed(request)
                        val ms = System.currentTimeMillis() - start
                        if (!response.isSuccessful) {
                            DiagLog.w("img ${response.code} (${ms}ms) ${request.url}")
                        } else if (ms > 5000) {
                            DiagLog.d("img slow (${ms}ms) ${request.url}")
                        }
                        response
                    }
                // 图片加载失败/换域名重试（最多 2 次）+ 失败域名降级
                .addInterceptor { chain ->
                    val req = chain.request()
                    if (!req.url.encodedPath.contains("/media/")) return@addInterceptor chain.proceed(req)
                    val resp = runCatching { chain.proceed(req) }.getOrNull()
                    if (resp != null && resp.isSuccessful) return@addInterceptor resp
                    val origin = req.url.host
                    resp?.close()
                    var base = origin
                    for (i in 1..2) {
                        val alt = com.mobai.jm.data.JmApi.nextImageHost(base) ?: break
                        val newReq = req.newBuilder()
                            .url(req.url.newBuilder().host(alt).build())
                            .build()
                        val r = runCatching { chain.proceed(newReq) }.getOrNull()
                        if (r != null) return@addInterceptor r
                        base = alt
                    }
                    com.mobai.jm.data.JmApi.reportImgHostFailure(origin)
                    throw java.io.IOException("image load failed after host retries")
                }
                NetTweaks.apply(b, this@MoBaiApplication)
                b.build()
            }
            .diskCache {
                val dir = StorageUtil.imageCacheDir(this@MoBaiApplication)
                runCatching { dir.mkdirs() }
                DiskCache.Builder()
                    .directory(dir)
                    .maxSizeBytes(2L * 1024 * 1024 * 1024)
                    .build()
            }
            .memoryCache {
                MemoryCache.Builder(this@MoBaiApplication)
                    .maxSizePercent(0.35)
                    .build()
            }
            .crossfade(false)
            .components {
                add(
                    coil.key.Keyer { data, _ ->
                        if (data is String && data.startsWith("http")) {
                            // 忽略域名差异：同一资源不同 CDN 域名共享缓存（提升命中率）
                            data.substringAfter("://").substringAfter('/')
                        } else {
                            data.toString()
                        }
                    }
                )
            }
            .respectCacheHeaders(false)
            .build()
}
