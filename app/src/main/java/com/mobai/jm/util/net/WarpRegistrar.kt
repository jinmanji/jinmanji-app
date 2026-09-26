package com.mobai.jm.util.net

import android.content.Context
import android.util.Base64
import com.mobai.jm.util.DiagLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Cloudflare WARP 账号注册 + MASQUE 设备密钥注册（Kotlin 移植自 usque 注册流程）。
 *
 * 关键点：
 *  - 注册/登记请求走【我们自己的 OkHttp】，因此开启 DoH（增强/完全模式）时注册全程经 DoH 解析 ✔
 *  - WG 占位公钥 = 32 随机字节的 base64（与 usque 行为一致，仅用于模拟官方 App 注册）
 *  - MASQUE 设备密钥 = EC P-256；私钥 SEC1 DER、公钥 PKIX DER（与 Go x509 编码一致）
 *  - API: v0a4471；注册 POST /reg → 登记 PATCH /reg/{id}（Bearer token）
 */
object WarpRegistrar {

    private const val API = "https://api.cloudflareclient.com/v0a4471"

    private val json = Json { ignoreUnknownKeys = true }
    private val pretty = Json { prettyPrint = true; ignoreUnknownKeys = true }

    private val headers = mapOf(
        "User-Agent" to "WARP for Android",
        "CF-Client-Version" to "a-6.35-4471",
        "Content-Type" to "application/json; charset=UTF-8",
        "Connection" to "Keep-Alive",
    )

    data class Result(val configJson: String, val summary: String)

    private fun client(context: Context): OkHttpClient {
        val b = OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(25, TimeUnit.SECONDS)
            .callTimeout(40, TimeUnit.SECONDS)
        NetTweaks.apply(b, context) // DoH（增强/完全）+ 实验特性全部生效
        return b.build()
    }

    suspend fun register(context: Context, deviceName: String): Result = withContext(Dispatchers.IO) {
        val rnd = SecureRandom()

        // ① 注册（WG 占位公钥）
        val wgPub = ByteArray(32).also { rnd.nextBytes(it) }
        val serial = ByteArray(8).also { rnd.nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val tos = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US).format(Date())
        val regBody = buildJsonObject {
            put("key", Base64.encodeToString(wgPub, Base64.NO_WRAP))
            put("install_id", "")
            put("fcm_token", "")
            put("tos", tos)
            put("model", "PC")
            put("serial_number", serial)
            put("os_version", "")
            put("key_type", "curve25519")
            put("tunnel_type", "wireguard")
            put("locale", "en_US")
        }
        DiagLog.d("warp: 注册账号…")
        val reg = post(context, "$API/reg", regBody.toString(), null)
        val id = reg["id"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("注册响应缺少 id")
        val token = reg["token"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("注册响应缺少 token")
        val license = reg["account"]?.jsonObject?.get("license")?.jsonPrimitive?.contentOrNull.orEmpty()
        DiagLog.d("warp: 账号注册成功 id=${id.take(8)}…，开始登记 MASQUE 设备密钥…")

        // ② 生成 EC P-256 设备密钥并登记（切换为 MASQUE 模式）
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), rnd)
        val kp = kpg.generateKeyPair()
        val pubDer = kp.public.encoded // PKIX DER（= Go MarshalPKIXPublicKey）
        val privSec1 = ecSec1(kp.private, kp.public) // SEC1 DER（= Go MarshalECPrivateKey，字节级一致）

        val upBody = buildJsonObject {
            put("key", Base64.encodeToString(pubDer, Base64.NO_WRAP))
            put("key_type", "secp256r1")
            put("tunnel_type", "masque")
            if (deviceName.isNotBlank()) put("name", deviceName)
        }
        val up = post(context, "$API/reg/$id", upBody.toString(), token, method = "PATCH")

        val config = up["config"]?.jsonObject ?: throw IllegalStateException("登记响应缺少 config")
        val peer = config["peers"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw IllegalStateException("登记响应缺少 peers")
        val epV4raw = peer["endpoint"]?.jsonObject?.get("v4")?.jsonPrimitive?.contentOrNull ?: "162.159.198.2:0"
        val epV6raw = peer["endpoint"]?.jsonObject?.get("v6")?.jsonPrimitive?.contentOrNull ?: ""
        val pubKeyPem = peer["public_key"]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalStateException("登记响应缺少 endpoint_pub_key")
        val addrs = config["interface"]?.jsonObject?.get("addresses")?.jsonObject
        val v4 = addrs?.get("v4")?.jsonPrimitive?.contentOrNull ?: ""
        val v6 = addrs?.get("v6")?.jsonPrimitive?.contentOrNull ?: ""

        val epV4 = parseEndpointHost(epV4raw)
        val epV6 = parseEndpointHost(epV6raw).ifBlank { "2606:4700:103::2" }

        val cfg = buildJsonObject {
            put("private_key", Base64.encodeToString(privSec1, Base64.NO_WRAP))
            put("endpoint_v4", epV4)
            put("endpoint_v6", epV6)
            put("endpoint_h2_v4", "162.159.198.2")
            put("endpoint_h2_v6", "")
            put("endpoint_pub_key", pubKeyPem)
            put("license", license)
            put("id", id)
            put("access_token", token)
            put("ipv4", v4)
            put("ipv6", v6)
        }

        DiagLog.d("warp: 登记完成 endpoint=$epV4 v4=$v4")
        Result(
            configJson = pretty.encodeToString(JsonObject.serializer(), cfg),
            summary = "id=${id.take(8)}… | IPv4=$v4 | 端点=$epV4 | license=${license.take(6)}…",
        )
    }

    private fun post(
        context: Context,
        url: String,
        body: String,
        bearer: String?,
        method: String = "POST",
    ): JsonObject {
        val b = Request.Builder()
            .url(url)
            .method(method, body.toRequestBody("application/json; charset=UTF-8".toMediaType()))
        headers.forEach { (k, v) -> b.header(k, v) }
        bearer?.let { b.header("Authorization", "Bearer $it") }
        client(context).newCall(b.build()).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: ${text.take(180)}")
            }
            return json.parseToJsonElement(text).jsonObject
        }
    }

    /** 端点地址提取：兼容 "1.2.3.4:0"、"[v6]:0"、"[v6]" 三种形式 */
    private fun parseEndpointHost(raw: String): String {
        val s = raw.trim()
        if (s.startsWith("[")) {
            val end = s.indexOf(']')
            if (end > 1) return s.substring(1, end)
        }
        return if (s.count { it == ':' } == 1) s.substringBefore(':') else s
    }

    /** 构造 SEC1（EC PRIVATE KEY DER，与 Go x509.MarshalECPrivateKey 字节级一致） */
    private fun ecSec1(privateKey: java.security.PrivateKey, publicKey: java.security.PublicKey): ByteArray {
        val ecPriv = privateKey as ECPrivateKey
        val ecPub = publicKey as java.security.interfaces.ECPublicKey
        val s32 = be32(ecPriv.s.toByteArray())
        val w = ecPub.w
        val point = byteArrayOf(0x04) + be32(w.affineX.toByteArray()) + be32(w.affineY.toByteArray())
        val oid = byteArrayOf(0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x03, 0x01, 0x07)
        val body = der(0x02, byteArrayOf(0x01)) +
            der(0x04, s32) +
            der(0xA0, der(0x06, oid)) +
            der(0xA1, der(0x03, byteArrayOf(0x00) + point))
        return der(0x30, body)
    }

    private fun be32(v: ByteArray): ByteArray {
        val out = ByteArray(32)
        val cp = minOf(32, v.size)
        System.arraycopy(v, v.size - cp, out, 32 - cp, cp)
        return out
    }

    private fun der(tag: Int, content: ByteArray): ByteArray {
        val len = content.size
        val lenBytes = when {
            len < 0x80 -> byteArrayOf(len.toByte())
            len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
            else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), (len and 0xFF).toByte())
        }
        return byteArrayOf(tag.toByte()) + lenBytes + content
    }
}
