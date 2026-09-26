package com.mobai.jm.util.net

import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.Socket
import java.net.SocketAddress
import javax.net.SocketFactory

/** 通用委托 Socket（把调用透传给被包装的 socket） */
open class DelegatingSocket(private val d: Socket) : Socket() {
    override fun connect(endpoint: SocketAddress?) = d.connect(endpoint)
    override fun connect(endpoint: SocketAddress?, timeout: Int) = d.connect(endpoint, timeout)
    override fun bind(bindpoint: SocketAddress?) = d.bind(bindpoint)
    override fun getInetAddress(): InetAddress? = d.inetAddress
    override fun getLocalAddress(): InetAddress = d.localAddress
    override fun getLocalPort(): Int = d.localPort
    override fun getPort(): Int = d.port
    override fun getRemoteSocketAddress(): SocketAddress? = d.remoteSocketAddress
    override fun getLocalSocketAddress(): SocketAddress? = d.localSocketAddress
    override fun getChannel() = d.channel
    override fun getInputStream(): InputStream = d.getInputStream()
    override fun getOutputStream(): OutputStream = d.getOutputStream()
    override fun setTcpNoDelay(on: Boolean) { d.tcpNoDelay = on }
    override fun getTcpNoDelay(): Boolean = d.tcpNoDelay
    override fun setSoLinger(on: Boolean, linger: Int) { d.setSoLinger(on, linger) }
    override fun getSoLinger(): Int = d.soLinger
    override fun sendUrgentData(data: Int) = d.sendUrgentData(data)
    override fun setOOBInline(on: Boolean) { d.oobInline = on }
    override fun getOOBInline(): Boolean = d.oobInline
    override fun setSoTimeout(timeout: Int) { d.soTimeout = timeout }
    override fun getSoTimeout(): Int = d.soTimeout
    override fun setSendBufferSize(size: Int) { d.sendBufferSize = size }
    override fun getSendBufferSize(): Int = d.sendBufferSize
    override fun setReceiveBufferSize(size: Int) { d.receiveBufferSize = size }
    override fun getReceiveBufferSize(): Int = d.receiveBufferSize
    override fun setKeepAlive(on: Boolean) { d.keepAlive = on }
    override fun getKeepAlive(): Boolean = d.keepAlive
    override fun setReuseAddress(on: Boolean) { d.reuseAddress = on }
    override fun getReuseAddress(): Boolean = d.reuseAddress
    override fun close() = d.close()
    override fun shutdownInput() = d.shutdownInput()
    override fun shutdownOutput() = d.shutdownOutput()
    override fun toString(): String = d.toString()
    override fun isConnected(): Boolean = d.isConnected
    override fun isBound(): Boolean = d.isBound
    override fun isClosed(): Boolean = d.isClosed
    override fun isInputShutdown(): Boolean = d.isInputShutdown
    override fun isOutputShutdown(): Boolean = d.isOutputShutdown
}

/**
 * SNI 切片（ClientHello 分段发送）：
 * 把首个 TLS ClientHello 的第一次 write 拆成两段（前 splitPos 字节 → 延迟 → 其余），
 * 打散 TCP 分段，绕过"按 SNI 检测"的简单 DPI。
 */
class FragmentedSocketFactory(
    private val delegate: SocketFactory,
    private val splitPos: Int,
    private val delayMs: Long,
) : SocketFactory() {

    private fun wrap(s: Socket): Socket =
        if (splitPos in 1..4096) FragmentedSocket(s, splitPos, delayMs) else s

    override fun createSocket(): Socket = wrap(delegate.createSocket())
    override fun createSocket(host: String, port: Int): Socket = wrap(delegate.createSocket(host, port))
    override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
        wrap(delegate.createSocket(host, port, localHost, localPort))
    override fun createSocket(host: InetAddress, port: Int): Socket = wrap(delegate.createSocket(host, port))
    override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
        wrap(delegate.createSocket(address, port, localAddress, localPort))
}

private class FragmentedSocket(
    d: Socket,
    private val splitPos: Int,
    private val delayMs: Long,
) : DelegatingSocket(d) {
    private var wrapped: OutputStream? = null

    override fun getOutputStream(): OutputStream {
        wrapped?.let { return it }
        val w = FragmentedOutputStream(super.getOutputStream(), splitPos, delayMs)
        wrapped = w
        return w
    }
}

private class FragmentedOutputStream(
    private val base: OutputStream,
    private val splitPos: Int,
    private val delayMs: Long,
) : OutputStream() {
    private var first = true

    override fun write(b: Int) = base.write(b)

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (!first || len <= splitPos) {
            first = false
            base.write(b, off, len)
            return
        }
        first = false
        base.write(b, off, splitPos)
        base.flush()
        if (delayMs > 0) {
            try {
                Thread.sleep(delayMs)
            } catch (_: InterruptedException) {
            }
        }
        base.write(b, off + splitPos, len - splitPos)
        base.flush()
    }

    override fun flush() = base.flush()
    override fun close() = base.close()
}
