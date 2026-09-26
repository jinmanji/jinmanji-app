# 第三方组件与致谢（THIRD-PARTY NOTICES）

禁漫姬（Jinmanji）站在许多优秀开源项目的肩膀上。谨此鸣谢：

## 一、直接依赖的库（经由 Gradle 引入）

| 组件 | 用途 | 许可证 |
|---|---|---|
| AndroidX / Jetpack Compose（BOM、Material3、Foundation、UI、activity-compose、core-ktx、material-icons） | UI 框架 | Apache-2.0 |
| Kotlin / kotlinx（stdlib、coroutines、serialization-json） | 语言与并发/序列化 | Apache-2.0 |
| OkHttp 4.12 | 网络栈 | Apache-2.0 |
| Coil 2.7（coil-compose） | 图片加载 | Apache-2.0 |
| Apache Commons Compress / Apache Commons IO | ZIP / 7z / tar 打包 | Apache-2.0 |
| XZ for Java（org.tukaani） | 7z LZMA2 压缩 | Public Domain |

## 二、内置的可执行组件（独立进程运行，经本地 SOCKS 通信，非链接关系）

| 组件 | 用途 | 许可证 | 来源 |
|---|---|---|---|
| tor 0.4.9.12（arm64 二进制） | Tor 匿名网络 | BSD-3-Clause | torproject.org（Android 构建取自 guardianproject/orbot 发布包） |
| lyrebird | obfs4 / WebTunnel 传输插件 | BSD-2-Clause（部分文件附 GPL-3.0，详见上游 LICENSE-GPL3.txt） | gitlab.torproject.org（本仓库自行编译） |
| snowflake-client | Snowflake 传输插件 | BSD-3-Clause | gitlab.torproject.org（本仓库自行编译） |
| github.com/wlynxg/anet | PT 网络接口枚举（本仓库打补丁后编入 PT 二进制） | BSD-3-Clause | github.com/wlynxg/anet |
| usque | Cloudflare WARP / MASQUE 客户端 | MIT | github.com/Diniboy1123/usque（本仓库自行编译） |

> 二次分发时请完整保留上述组件的版权声明与许可证文本。

## 三、协议逆向与实现思路参考（致谢）

- **hect0x7/JMComic-Crawler-Python**、**lanyeeee/jmcomic-downloader** —— 禁漫移动端 API 协议与图片乱序还原算法的社区知识
- **Orbot**（guardianproject）—— Android 平台 tor/PT 打包、控制端口引导进度监控实践
- **Mihon / Tachiyomi 与 keiyoushi 扩展（jinmantiantang）** —— 图片处理与流式解密思路
- **PixEz / Pixiv 系客户端** —— 预取、缓存分层、受限并发等图片加载工程经验
- Cloudflare WARP 生态的公开文档与社区讨论
