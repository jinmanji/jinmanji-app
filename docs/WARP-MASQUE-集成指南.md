# WARP 注册 + 内置 MASQUE + SNI 伪装 · Android 移植参考文档

> 来源：禁漫姬（Jinmanji）v0.14.0 实机验证实现 ｜ 整理日期：2026-09
> 适用：Android（arm64）Java 项目移植 ｜ 组件：Cloudflare WARP API + usque 内核 + 本地 SOCKS5

---

## 0. TL;DR（60 秒版）

```
① 用随机 WG 占位公钥  POST api.cloudflareclient.com/v0a4471/reg        → 拿到 id / token / license
② 生成 EC P-256 密钥对，PATCH 同一地址 /reg/{id}（Bearer token）        → 切换为 MASQUE 模式，拿到端点与公钥
③ 把 SEC1 私钥 + 端点 + PEM 公钥写成本地 config.json
④ 启动内置的 usque 二进制：usque -c config.json socks -b 127.0.0.1 -p 18080 -s speed.cloudflare.com
⑤ 全 App 流量经 OkHttp/ProxySelector 走 127.0.0.1:18080（SOCKS5）
```

---

## 1. 系统架构

```
┌─────────── Android App ───────────┐
│  WarpRegistrar（本文档 §3）        │   注册/登记：HTTPS(可走DoH) → CF API
│        │ config.json              │
│  MasqueManager（本文档 §4）        │   进程管理/换线/校验
│        │ spawn                    │
│  libusque.so（usque v1.5.0, MIT） │   MASQUE(HTTP/3 CONNECT-IP) → CF 边缘
│        │ SOCKS5                   │
│  127.0.0.1:18080 ──► App 全部流量  │   OkHttp / HttpURLConnection / Coil…
└───────────────────────────────────┘
```

**核心事实**：WARP 的"注册"与"连接"是两件事。注册只做一次（得到账号与设备密钥），连接由内置 usque 内核完成；两者通过 `config.json` 衔接。

---

## 2. 前置知识

| 概念 | 说明 |
|---|---|
| WireGuard 模式 | `tunnel_type=wireguard`，密钥 curve25519；**我们不用**（Cloudflare 对 WG 模式风控更严） |
| MASQUE 模式 | `tunnel_type=masque`，设备密钥 **EC P-256**；HTTP/3 上的 CONNECT-IP（RFC 9484） |
| WG 占位公钥 | 注册第一步随便给一个 32 字节 base64 公钥，"模拟官方 App 首次注册"，随后会被 PATCH 覆盖 |
| 设备密钥 | EC P-256；**私钥必须是 SEC1 DER**（不是 PKCS8！见 §6 坑 2），公钥是 PKIX DER |
| 端点公钥 | 登记响应里 `peers[0].public_key`（PEM）；usque 用它做**证书/密钥 pinning**，防中间人 |
| 账号 token | 仅登记（PATCH）时用 `Authorization: Bearer <token>` |

---

## 3. WARP 账号注册（协议级细节）

### 3.1 基础信息

```
API Base   : https://api.cloudflareclient.com/v0a4471        ← 版本号必须带
请求头      :
  User-Agent        : WARP for Android
  CF-Client-Version : a-6.35-4471
  Content-Type      : application/json; charset=UTF-8
  Connection        : Keep-Alive
  Authorization     : Bearer <token>     ← 仅第二步 PATCH 需要
```

> 这两个头（UA + CF-Client-Version）是 Cloudflare 识别"官方 Android 客户端"的关键，缺失或版本过旧会被拒。`a-6.35-4471` 为实测可用的版本组合。

### 3.2 第一步：POST /reg（注册账号）

请求体（实测模板，逐字段说明）：

```json
{
  "key": "<32 随机字节的 Base64>",        // WG 占位公钥（后续会被 PATCH 替换）
  "install_id": "",
  "fcm_token": "",
  "tos": "2026-09-28T16:00:00.000+08:00", // 当前时间，格式 yyyy-MM-dd'T'HH:mm:ss.SSSXXX
  "model": "PC",
  "serial_number": "9f3a1c4b7e2d8a05",    // 8 随机字节 → 16 位十六进制
  "os_version": "",
  "key_type": "curve25519",
  "tunnel_type": "wireguard",
  "locale": "en_US"
}
```

响应关键字段：

```json
{
  "id": "c3e458xx-…",                 // 设备/账号 id（后续 PATCH 路径用）
  "token": "e8b23cxx-…",              // Bearer token
  "account": { "license": "6I2ym0xx-…" }   // WARP 许可证（可留空/后续升级 WARP+）
}
```

### 3.3 第二步：PATCH /reg/{id}（登记 MASQUE 设备密钥，切换模式）

> ⚠️ **必须用 PATCH 方法**（历史上用 POST 会失败——见 §6 坑 1）。

```json
{
  "key": "<EC P-256 公钥的 PKIX DER，Base64>",
  "key_type": "secp256r1",
  "tunnel_type": "masque",
  "name": "我的设备"
}
```

响应（节选）：

```json
{
  "config": {
    "peers": [{
      "endpoint": { "v4": "162.159.198.2:0", "v6": "[2606:4700:103::2]:0" },
      "public_key": "-----BEGIN PUBLIC KEY-----\nMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAE…\n-----END PUBLIC KEY-----"
    }],
    "interface": { "addresses": { "v4": "172.16.0.2", "v6": "2606:4700:110:…/128" } }
  }
}
```

**缺省兜底**（字段缺失时用，实测可连）：
- `endpoint.v4` → `162.159.198.2`
- `endpoint.v6` → `2606:4700:103::2`

### 3.4 EC P-256 密钥生成（Java 完整代码）

```java
import java.security.*;
import java.security.interfaces.*;
import java.security.spec.ECGenParameterSpec;

KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
kpg.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
KeyPair kp = kpg.generateKeyPair();

byte[] pubPKIX = kp.getPublic().getEncoded();          // PKIX DER（= Go MarshalPKIXPublicKey）
byte[] privSEC1 = ecSec1((ECPrivateKey) kp.getPrivate(),
                         (ECPublicKey) kp.getPublic()); // SEC1 DER（= Go MarshalECPrivateKey）

// 登记：Base64.encodeToString(pubPKIX, NO_WRAP)
// 配置：Base64.encodeToString(privSEC1, NO_WRAP)
```

**SEC1（EC PRIVATE KEY）手工构造**（无需 BouncyCastle，与 Go 字节级一致）：

```java
static byte[] ecSec1(ECPrivateKey priv, ECPublicKey pub) {
    byte[] s32 = be32(priv.getS().toByteArray());   // 标量，左补零到 32 字节
    byte[] x   = be32(pub.getW().getAffineX().toByteArray());
    byte[] y   = be32(pub.getW().getAffineY().toByteArray());
    byte[] point = concat(new byte[]{0x04}, x, y);  // 未压缩点 04||X||Y
    byte[] oid = {0x2A, (byte)0x86, 0x48, (byte)0xCE, (byte)0x3D, 0x03, 0x01, 0x07}; // prime256v1

    byte[] body = concat(
        der(0x02, new byte[]{0x01}),                        // version = 1
        der(0x04, s32),                                     // privateKey (OCTET STRING)
        der(0xA0, der(0x06, oid)),                          // [0] parameters
        der(0xA1, der(0x03, concat(new byte[]{0x00}, point))) // [1] publicKey (BIT STRING)
    );
    return der(0x30, body);                                 // SEQUENCE
}

static byte[] be32(byte[] v) {                  // 大端左补零至 32 字节（处理 BigInteger 符号位）
    byte[] out = new byte[32];
    int cp = Math.min(32, v.length);
    System.arraycopy(v, v.length - cp, out, 32 - cp, cp);
    return out;
}

static byte[] der(int tag, byte[] content) {    // 极简 DER TLV
    int len = content.length;
    byte[] lenBytes = len < 0x80 ? new byte[]{(byte) len}
        : len < 0x100 ? new byte[]{(byte) 0x81, (byte) len}
        : new byte[]{(byte) 0x82, (byte) (len >> 8), (byte) (len & 0xFF)};
    return concat(new byte[]{(byte) tag}, lenBytes, content);
}
```

### 3.5 端点地址清洗（坑位预警）

Cloudflare 返回形如 `1.2.3.4:0`、`[2606:4700::1]:0`、`[2606:4700::1]` 三种形式，**必须**清洗成纯主机：

```java
static String parseEndpointHost(String raw) {
    String s = raw.trim();
    if (s.startsWith("[")) {
        int end = s.indexOf(']');
        if (end > 1) return s.substring(1, end);        // [v6]:0 → v6
    }
    return (s.chars().filter(c -> c == ':').count() == 1)
        ? s.substring(0, s.indexOf(':'))                 // v4:port → v4
        : s;
}
```

### 3.6 生成 usque config.json

```json
{
  "private_key": "<SEC1 DER 的 Base64>",
  "endpoint_v4": "162.159.198.2",
  "endpoint_v6": "2606:4700:103::2",
  "endpoint_h2_v4": "162.159.198.2",
  "endpoint_h2_v6": "",
  "endpoint_pub_key": "-----BEGIN PUBLIC KEY-----\n…\n-----END PUBLIC KEY-----",
  "license": "<步骤 1 的 license>",
  "id": "<id>",
  "access_token": "<token>",
  "ipv4": "172.16.0.2",
  "ipv6": "2606:4700:110:…"
}
```

| 字段 | 来源 | 说明 |
|---|---|---|
| `private_key` | 自生成 | **SEC1** DER Base64（见 §6 坑 2） |
| `endpoint_v4/v6` | 登记响应 peers[0].endpoint | 清洗后的主机地址 |
| `endpoint_h2_v4/v6` | 固定 v4 / 手动 v6 | `--http2` 模式用；v6 留空即可 |
| `endpoint_pub_key` | 登记响应 peers[0].public_key | PEM，pinning 用 |
| `license` / `id` / `access_token` | 步骤 1 | 账号凭据（导入导出可整体搬运） |
| `ipv4/ipv6` | 登记响应 interface.addresses | 隧道内虚拟地址 |

### 3.7 注册链路走 DoH（可选但强烈建议）

CN 网络下 `api.cloudflareclient.com` 的 DNS 可能被污染。做法：给注册所用 OkHttpClient 挂一个自定义 `Dns`（先经 DoH 的 JSON API 解析域名，再返回 IP）：

```java
OkHttpClient client = new OkHttpClient.Builder()
    .dns(hostname -> {
        // GET https://1.1.1.1/dns-query?name=<hostname>&type=A  (Accept: application/dns-json)
        // 解析 Answer[].data 后返回 List<InetAddress>
        return dohResolve(hostname);
    })
    .build();
```

> 我们的完整实现还包含随机路径 DoH（`https://<随机子域>.gl.doh.tw/<随机路径>`，避免 `/dns-query` 特征）与失败降级策略；移植时可先用标准 DoH 起步。

---

## 4. usque 内核集成（Android 实操）

### 4.1 二进制来源与编译

- 上游：`github.com/Diniboy1123/usque`（MIT，v1.5.0 实测）
- 交叉编译（x86_64 Linux/Termux 均可）：

```bash
GOOS=android GOARCH=arm64 CGO_ENABLED=0 \
  go build -trimpath -ldflags "-s -w" \
  -o libusque.so .
```

产物约 19MB。**命名必须以 `lib` 开头、`.so` 结尾**（Android 才会把它当 native library 打包）。

### 4.2 打包进 APK（关键：可执行权限）

`app/src/main/jniLibs/arm64-v8a/libusque.so` + Gradle：

```gradle
android {
    packaging {
        jniLibs { useLegacyPackaging = true }   // = android:extractNativeLibs="true"
    }
}
```

**原理**：Android 10+ 禁止从应用数据目录 exec（W^X），但 `nativeLibraryDir` 例外。`useLegacyPackaging=true` 让安装时把 .so 解压到 `nativeLibraryDir`，运行期路径：

```java
File bin = new File(context.getApplicationInfo().nativeLibraryDir, "libusque.so");
```

### 4.3 启动参数（实测模板）

```java
ProcessBuilder pb = new ProcessBuilder(
    bin.getAbsolutePath(),
    "-c", configFile.getAbsolutePath(),   // config.json
    "socks",                              // 以 SOCKS5 代理方式暴露隧道
    "-b", "127.0.0.1",                    // 只绑定回环（安全）
    "-p", "18080",                        // 本地 SOCKS5 端口
    "-d", "1.1.1.1",                      // 隧道内 DNS #1
    "-d", "1.0.0.1",                      // #2
    "-d", "8.8.8.8",                      // #3
    "--dns-timeout", "10s",
    // "--http2",                        // TCP+TLS(HTTP/2) 回退模式时追加
    // "-s", "speed.cloudflare.com"      // SNI 伪装（见 §5）
);
pb.redirectErrorStream(true);
pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
Process p = pb.start();
```

其他有用开关（`usque socks --help` 摘录）：

| 参数 | 用途 |
|---|---|
| `--always-reconnect` | 断线后始终重连（含空闲）；保活用 |
| `-m 1280` | MTU（默认 1280） |
| `-k 30s` | MASQUE keepalive |
| `-r 1s` | 重连间隔 |
| `-i <n>` | 自定义初始包大小（PMTU 探测异常时用） |
| `-l` / `--system-dns` | DNS 不走隧道（**不要用**，会泄露+受污染） |
| `--insecure` | 关闭端点 pinning（**仅调试**） |
| `-6` | 仅 IPv6 |

**DNS 行为默认值（重要）**：不加 `-l` 时，SOCKS 里的域名解析在**隧道内**完成（由 `-d` 指定的 DNS 经隧道查询）——这正是绕过 DNS 污染的关键。

### 4.4 进程生命周期与"自动换线梯"

**换线梯**（我们的实现，4 条候选按序尝试）：

```
① QUIC + 主端点     ② TCP(--http2) + 主端点
③ QUIC + 备用 IP    ④ TCP(--http2) + 备用 IP
（上次成功的线路优先；设置里可固定"仅 QUIC / 仅 TCP"）
```

备用 IP 池（实测可用）：

```
162.159.192.1 / 188.114.97.1 / 162.159.195.1 / 162.159.193.1
```

每次尝试的流程（Java 移植伪码）：

```java
boolean runAttempt(File bin, File cfg, boolean tcp, String sni) {
    killPrevious();
    deleteLog();
    List<String> args = buildArgs(bin, cfg, tcp, sni);   // §4.3
    Process p = new ProcessBuilder(args)
        .redirectErrorStream(true)
        .redirectOutput(ProcessBuilder.Redirect.appendTo(logFile))
        .start();
    // 1) 等端口就绪（≤12s）
    if (!waitPort("127.0.0.1", 18080, 12_000)) { p.destroy(); return false; }
    // 2) 预热校验：真正过一次隧道才算成功；失败先 1.5s 后重试一次
    boolean ok = warmUp();
    if (!ok) { sleep(1500); ok = warmUp(); }
    if (!ok) { p.destroy(); return false; }
    return true;
}
```

**预热校验**（建议原样照搬，避免"端口开了但隧道没通"的假成功）：

```java
boolean warmUp() throws IOException {
    OkHttpClient c = new OkHttpClient.Builder()
        .proxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 18080)))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build();
    c.newCall(new Request.Builder().url("https://www.cloudflare.com/cdn-cgi/trace").build())
        .execute().close();
    return true;
}
```

**停止**：

```java
p.destroy();
if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly();
```

### 4.5 连通性验证（warp=on）

```java
// 经 SOCKS 请求 https://cloudflare.com/cdn-cgi/trace
String body = ...;                                  // 纯文本
// 期望含:  warp=on        （WARP+ 为 warp=plus）
String warp = body.lines().filter(l -> l.startsWith("warp=")).findFirst().orElse("warp=?");
```

### 4.6 让 App 全部流量走 SOCKS5

**方式 A：单客户端**（OkHttp）

```java
OkHttpClient c = new OkHttpClient.Builder()
    .proxy(new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("127.0.0.1", 18080)))
    .build();
```

**方式 B：全局 ProxySelector**（推荐，覆盖 HttpURLConnection/Coil/WebView 等）

```java
public class MasqueProxySelector extends ProxySelector {
    private final ProxySelector delegate = ProxySelector.getDefault();

    @Override
    public List<Proxy> select(URI uri) {
        if (MasqueManager.isRunning) {   // 静态易失标志，避免每请求读配置
            return List.of(new Proxy(Proxy.Type.SOCKS,
                new InetSocketAddress("127.0.0.1", 18080)));
        }
        return delegate != null ? delegate.select(uri)
                                : List.of(Proxy.NO_PROXY);
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        if (delegate != null) delegate.connectFailed(uri, sa, ioe);
    }
}
// 安装：ProxySelector.setDefault(new MasqueProxySelector());
// 记得在网络栈初始化前设置；隧道启停用 volatile 布尔切换
```

**DNS 说明**：Java/OkHttp 对 `Proxy.Type.SOCKS` 会以未解析地址发起连接（由 SOCKS 服务端即 usque 做解析）——这就是"隧道内远程 DNS"的来源，无需额外操作。

### 4.7 启动门控（可选最佳实践）

若产品要求"隧道就绪前不发起任何业务请求"：维护一个全局 `StartupGate`（状态：connecting / ready / failed），UI 层在 `ready` 前只显示加载页；隧道失败给出「重试 / 跳过」两个按钮。（禁漫姬 v0.9.2 起采用，体验显著。）

---

## 5. SNI 伪装

### 5.1 问题

usque 默认 SNI 为 `consumer-masque.cloudflareclient.com`——一个**特征明显的 WARP 域名**，网络侧可据此识别/干扰。

### 5.2 我们的策略（实测通过）

```java
// 默认伪装 SNI
private static final String DEFAULT_SNI = "speed.cloudflare.com";

// 用户可在设置中维护 SNI 列表；每次连接尝试从列表中随机抽取：
String sni = pool.isEmpty() ? DEFAULT_SNI : pool.get(random.nextInt(pool.size()));
// 启动参数追加： "-s", sni
```

### 5.3 要点与事实

- **隧道对 SNI 不敏感**：实测任意 Cloudflare 系域名均可完成握手（SNI 只影响外层 TLS 表象，不参与隧道内寻址）。
- 建议用"不突兀"的 Cloudflare 域名：`speed.cloudflare.com`（默认）、`cloudflare.com`、`one.one.one.one`、`cdnjs.cloudflare.com`。
- **与 pinning 的关系**：usque 校验的是**端点公钥**（`endpoint_pub_key`），与 SNI 无关——换 SNI 不会削弱防中间人能力。
- **与 SNI 切片的区别**：应用直连场景的"SNI 切片/置空"在隧道模式下应自动跳过（流量已在隧道内加密），两者不要叠加。
- 每次连接（含自动换线）都重新抽取 SNI，避免固定值形成新特征。

---

## 6. 踩坑清单（血泪史，直接避雷）

| # | 现象 | 根因 | 修复 | 来源版本 |
|---|---|---|---|---|
| 1 | 注册 404/失败 | 设备密钥登记误用 POST | **必须 `PATCH /reg/{id}`** | 我们 v0.7.1 |
| 2 | usque 报 `Failed to get private key` | 私钥写成 PKCS8；Go 侧按 SEC1 解析 | 改为 **SEC1 DER**（§3.4 代码） | 我们 v0.7.2 |
| 3 | 端点字段残留 `]`，连接失败 | `"[v6]:0"` 去 `[` 后忘去 `]` | 统一 `parseEndpointHost()`（§3.5） | 我们 v0.9.3 |
| 4 | APK 装完 usque 无法执行 | Android 10+ 数据目录禁 exec | jniLibs + `useLegacyPackaging=true`（§4.2） | — |
| 5 | 偶发"端口开但请求全挂" | 隧道未真正就绪 | **预热校验**（§4.4），失败换线 | — |
| 6 | 隧道内首个请求很慢 | 隧道内 DNS 冷启动 | 预热一张小图/一次 HEAD，提前热 DNS | 我们 v0.9.5 |
| 7 | `--http2` 与 QUIC 语义混淆 | 两者是**传输回退**关系 | QUIC 优先，`--http2` 仅作 TCP 回退 | — |
| 8 | 注册接口被 DNS 污染 | 明文 DNS 查 api.cloudflareclient.com | 注册链路走 DoH（§3.7） | 我们 v0.7.0 |

---

## 7. 常量速查表

```
API Base           : https://api.cloudflareclient.com/v0a4471
UA                 : WARP for Android
CF-Client-Version  : a-6.35-4471
注册               : POST  /reg           （WG 占位公钥）
登记/切 MASQUE     : PATCH /reg/{id}      （Bearer token；secp256r1 + PKIX 公钥）
本地 SOCKS5        : 127.0.0.1:18080
备用端点 IP 池     : 162.159.192.1 / 188.114.97.1 / 162.159.195.1 / 162.159.193.1 / 162.159.198.2
默认 SNI（伪装值） : speed.cloudflare.com
usque 默认 SNI     : consumer-masque.cloudflareclient.com （不要用）
trace 验证         : https://cloudflare.com/cdn-cgi/trace  → warp=on
```

---

## 8. 移植对照（禁漫姬源码 → 你的项目）

| 禁漫姬文件（Kotlin） | 职责 | 移植优先级 |
|---|---|---|
| `util/net/WarpRegistrar.kt` | 注册 + 登记 + 生成 config.json | ★★★ 照抄逻辑/按本文 §3 重写 |
| `util/net/MasqueManager.kt` | 进程管理 / 换线梯 / 预热 / 验证 / ProxySelector | ★★★ |
| `util/net/DohDns.kt`、`NetTweaks.kt` | DoH（随机路径）接入 OkHttp | ★（可选强化） |
| `util/net/DelegatingSocket.kt` | SOCKS 连接包装（高级） | ☆ |
| 设置页（SNI 列表/传输模式/导入导出） | 运维入口 | ★ |

---

## 9. 合规与安全提醒

- 仅限个人学习/研究用途；遵守 Cloudflare ToS 与当地法律。
- `endpoint_pub_key` pinning 是防中间人的关键，**生产环境永远不要开 `--insecure`**。
- `config.json` 含账号凭据（token/license），按敏感数据存放（应用私有目录），导出功能需用户显式操作。
- 建议注册走 DoH、SOCKS 只绑回环、日志自行脱敏。
