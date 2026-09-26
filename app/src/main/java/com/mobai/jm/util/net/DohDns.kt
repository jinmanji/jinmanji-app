package com.mobai.jm.util.net

import android.util.LruCache
import com.mobai.jm.util.DiagLog
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** DNS 报文编解码（RFC 1035 wire format，够用即可） */
object DnsWire {
    data class Record(val type: Int, val ttl: Long, val rdata: ByteArray)
    data class Message(val rcode: Int, val records: List<Record>)

    fun buildQuery(name: String, type: Int): ByteArray {
        val id = Random.nextInt(0, 65536)
        val out = ArrayList<Byte>(64)
        fun w16(v: Int) { out.add((v shr 8).toByte()); out.add((v and 0xFF).toByte()) }
        w16(id); w16(0x0100) // RD
        w16(1); w16(0); w16(0); w16(0)
        name.split(".").filter { it.isNotBlank() }.forEach { part ->
            val b = part.toByteArray()
            out.add(b.size.toByte())
            b.forEach { out.add(it) }
        }
        out.add(0)
        w16(type); w16(1)
        return out.toByteArray()
    }

    fun readName(d: ByteArray, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var off = start
        var ret = -1
        var jumps = 0
        while (off < d.size) {
            val len = d[off].toInt() and 0xFF
            if (len == 0) {
                off += 1
                break
            }
            if ((len and 0xC0) == 0xC0) {
                if (ret < 0) ret = off + 2
                val ptr = ((len and 0x3F) shl 8) or (d[off + 1].toInt() and 0xFF)
                off = ptr
                jumps++
                if (jumps > 16) break
                continue
            }
            if (off + 1 + len > d.size) break
            sb.append(String(d, off + 1, len, Charsets.UTF_8)).append('.')
            off += 1 + len
        }
        return sb.toString().trimEnd('.') to (if (ret >= 0) ret else off)
    }

    fun parse(data: ByteArray): Message {
        if (data.size < 12) return Message(-1, emptyList())
        fun u16(o: Int) = ((data[o].toInt() and 0xFF) shl 8) or (data[o + 1].toInt() and 0xFF)
        val rcode = data[3].toInt() and 0x0F
        val qd = u16(4)
        val an = u16(6)
        var off = 12
        repeat(qd) {
            val (_, o) = readName(data, off)
            off = o + 4
        }
        val records = ArrayList<Record>(an)
        repeat(an) {
            if (off >= data.size) return@repeat
            val (_, o1) = readName(data, off)
            off = o1
            if (off + 10 > data.size) return@repeat
            val type = u16(off)
            val ttl = ((u16(off + 4).toLong()) shl 16) or u16(off + 6).toLong()
            val rdlen = u16(off + 8)
            val rdataStart = off + 10
            if (rdataStart + rdlen > data.size) return@repeat
            records.add(Record(type, ttl, data.copyOfRange(rdataStart, rdataStart + rdlen)))
            off = rdataStart + rdlen
        }
        return Message(rcode, records)
    }

    /** 从 HTTPS RR(type 65) 的 rdata 中提取 ech 参数（key=5），返回 ECHConfigList 原始字节 */
    fun extractEch(rdata: ByteArray): ByteArray? {
        runCatching {
            var off = 2 // priority
            val (_, o) = readName(rdata, off)
            off = o
            while (off + 4 <= rdata.size) {
                val key = ((rdata[off].toInt() and 0xFF) shl 8) or (rdata[off + 1].toInt() and 0xFF)
                val len = ((rdata[off + 2].toInt() and 0xFF) shl 8) or (rdata[off + 3].toInt() and 0xFF)
                off += 4
                if (off + len > rdata.size) break
                if (key == 5) return rdata.copyOfRange(off, off + len)
                off += len
            }
        }
        return null
    }
}

// DoH（DNS over HTTPS，RFC 8484 wire 格式）。
// 默认地址模板支持 * 通配（任意合法字符）。
class DohDns(
    urlTemplate: String,
) : Dns {

    companion object {
        private val cache = object : LruCache<String, Entry>(512) {
            override fun sizeOf(key: String, value: Entry): Int = 1
        }
        private val echCache = object : LruCache<String, EchEntry>(128) {
            override fun sizeOf(key: String, value: EchEntry): Int = 1
        }

        private class Entry(val addresses: List<ByteArray>, val expiresAt: Long)
        private class EchEntry(val config: ByteArray?, val expiresAt: Long)

        // 把模板展开为真实端点：子域与路径全部用随机串（避开 /dns-query 等标准路径特征）
        fun endpoint(template: String): String {
            fun rnd(n: Int) = (1..n).map { "abcdefghijklmnopqrstuvwxyz0123456789".random() }
                .joinToString("")

            var t = template.trim()
            if (t.isEmpty()) t = "https://*.gl.doh.tw/*"
            if (!t.startsWith("http")) t = "https://$t"
            if (t.contains("*")) {
                // 第一个 * = 随机子域，第二个 * = 随机路径
                t = t.replaceFirst("*", rnd(10))
                if (t.contains("*")) t = t.replaceFirst("*", rnd(6))
                // 通配符模板没有路径时，补随机路径；以 / 结尾时也补随机段
                if (!t.substringAfter("://").contains("/")) t = t.trimEnd('/') + "/" + rnd(6)
                if (t.endsWith("/")) t += rnd(6)
            } else if (!t.substringAfter("://").contains("/")) {
                t = t.trimEnd('/') + "/dns-query"
            }
            return t
        }

        fun clearCache() {
            cache.evictAll()
            echCache.evictAll()
        }

        /** 设置页"测试"按钮用：返回可读结果 */
        fun test(urlTemplate: String): String {
            val t0 = System.currentTimeMillis()
            val ep = endpoint(urlTemplate)
            return try {
                val dns = DohDns(urlTemplate)
                val ips = dns.lookup("www.cdnhjk.net")
                val ms = System.currentTimeMillis() - t0
                "✅ DoH 正常（${ms}ms）\n端点: $ep\nwww.cdnhjk.net → ${ips.joinToString(", ") { it.hostAddress ?: "?" }}"
            } catch (e: Exception) {
                "❌ DoH 失败：${e.message}\n端点: $ep"
            }
        }

        /** 查询某域名的 HTTPS RR 获取 ECH 配置（供 ECH 实验功能使用） */
        fun queryEchConfig(host: String): ByteArray? {
            val now = System.currentTimeMillis()
            echCache.get(host)?.let { if (it.expiresAt > now) return it.config }
            val cfg = runCatching {
                val client = client()
                val q = DnsWire.buildQuery(host, 65)
                val req = Request.Builder()
                    .url(endpoint(defaultTemplate))
                    .post(q.toRequestBody("application/dns-message".toMediaType()))
                    .header("User-Agent", "mobai/1.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) throw IllegalStateException("HTTP ${resp.code}")
                    val msg = DnsWire.parse(resp.body?.bytes() ?: throw IllegalStateException("空响应"))
                    msg.records.filter { it.type == 65 }.mapNotNull { DnsWire.extractEch(it.rdata) }.firstOrNull()
                }
            }.getOrNull()
            echCache.put(host, EchEntry(cfg, now + 30 * 60_000L))
            return cfg
        }

        // 供 ECH 查询使用的最近一次模板（NetTweaks 应用时更新）
        @Volatile
        var defaultTemplate: String = "https://*.gl.doh.tw/*"

        private val sharedClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(4, TimeUnit.SECONDS)
                .readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS)
                .build()
        }

        private fun client(): OkHttpClient = sharedClient
    }

    private val url: String = endpoint(urlTemplate)

    init {
        defaultTemplate = urlTemplate
    }

    override fun lookup(hostname: String): List<InetAddress> {
        val now = System.currentTimeMillis()
        cache.get(hostname)?.let { e ->
            if (e.expiresAt > now) {
                return e.addresses.map { InetAddress.getByAddress(hostname, it) }
            }
        }
        val v4 = query(hostname, 1)
        var addrs = v4
        var ttl = 60L
        if (addrs.isEmpty()) {
            addrs = query(hostname, 28)
        }
        if (addrs.isEmpty()) throw UnknownHostException("DoH 无记录: $hostname")
        cache.put(hostname, Entry(addrs, now + ttl * 1000))
        return addrs.map { InetAddress.getByAddress(hostname, it) }
    }

    private fun query(hostname: String, type: Int): List<ByteArray> {
        val q = DnsWire.buildQuery(hostname, type)
        val req = Request.Builder()
            .url(url)
            .post(q.toRequestBody("application/dns-message".toMediaType()))
            .header("User-Agent", "mobai/1.0")
            .build()
        sharedClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw IllegalStateException("DoH HTTP ${resp.code}")
            val data = resp.body?.bytes() ?: throw IllegalStateException("DoH 空响应")
            val msg = DnsWire.parse(data)
            if (msg.rcode != 0) throw UnknownHostException("DoH rcode=${msg.rcode}: $hostname")
            val want = if (type == 1) 4 else 16
            return msg.records.filter { it.type == type && it.rdata.size == want }.map { it.rdata }
        }
    }
}

/** DoH·增强：DoH 不可用时自动降级到系统 DNS（写 Warn 日志） */
class FallbackDns(private val primary: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = try {
        primary.lookup(hostname)
    } catch (e: Exception) {
        DiagLog.w("DoH 降级到系统 DNS: $hostname (${e.message})")
        Dns.SYSTEM.lookup(hostname)
    }
}

/** DoH·完全：DoH 不可用则直接终止连接（写 Error 日志） */
class StrictDns(private val primary: Dns) : Dns {
    override fun lookup(hostname: String): List<InetAddress> = try {
        primary.lookup(hostname)
    } catch (e: Exception) {
        DiagLog.e("DoH(完全)失败，连接终止: $hostname (${e.message})")
        throw e
    }
}
