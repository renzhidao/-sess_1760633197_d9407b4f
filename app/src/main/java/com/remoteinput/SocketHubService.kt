// header
package com.remoteinput

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Base64
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class SocketHubService : Service() {

    companion object {
        const val PORT = 10000
        private const val STATE = "STATE"        // STATE:IME_ACTIVE / STATE:IME_INACTIVE
        private const val TEXT_B64 = "TEXTB64"   // TEXTB64:<base64 payload>
        private const val BACKSPACE = "BACKSPACE"
        private const val CLEAR = "CLEAR"
    }

    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    interface ImeSink {
        fun onText(text: String)
        fun onBackspace()
        fun onClear()
        fun isActive(): Boolean
    }
    interface AppSink {
        fun onText(text: String)
        fun onBackspace()
        fun onClear()
        fun onConnectionState(state: String)
        fun isActive(): Boolean
    }

    private var imeSink: ImeSink? = null
    private var appSink: AppSink? = null

    // 远端/本地 IME 状态
    private var remoteImeActive = false
    private var localImeActive = false

    // 单会话（不做多连接，避免互抢）
    private var serverJob: Job? = null
    private var serverSocket: ServerSocket? = null
    private var sessionSocket: Socket? = null
    private var sessionWriter: PrintWriter? = null
    private var readJob: Job? = null

    inner class LocalBinder : Binder() { fun getService(): SocketHubService = this@SocketHubService }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        startServer()
    }

    override fun onDestroy() {
        super.onDestroy()
        closeSession()
        stopServer()
        ioScope.cancel()
        mainScope.cancel()
    }

    fun registerImeSink(sink: ImeSink?) { imeSink = sink }
    fun registerAppSink(sink: AppSink?) { appSink = sink }

    fun setImeActive(active: Boolean) {
        localImeActive = active
        // 同步本地 IME 状态给对端
        sendFrame("$STATE:${if (active) "IME_ACTIVE" else "IME_INACTIVE"}")
    }

    // 拨号建立单会话；被动接入由 startServer 处理
    fun connect(ip: String) {
        ioScope.launch {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, PORT), 10_000)
                adoptSession(s, "出站")
                notifyState("已连接：${ip}:${PORT}")
                // 首包同步我方 IME 状态
                sendFrame("$STATE:${if (localImeActive) "IME_ACTIVE" else "IME_INACTIVE"}")
            } catch (e: Exception) {
                notifyState("连接失败")
            }
        }
    }

    fun disconnect() {
        ioScope.launch {
            closeSession()
            notifyState("未连接")
        }
    }

    // 发送 API（Base64，避免换行/控制字符问题）
    fun sendText(text: String) {
        val b64 = Base64.encodeToString(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
        sendFrame("$TEXT_B64:$b64")
    }
    fun sendBackspace() = sendFrame(BACKSPACE)
    fun sendClear() = sendFrame(CLEAR)

    // 服务器常驻（单连接）
    private fun startServer() {
        if (serverJob?.isActive == true) return
        serverJob = ioScope.launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(PORT)
                notifyState("监听端口：$PORT")
                while (isActive) {
                    val client = serverSocket!!.accept()
                    adoptSession(client, "入站")
                    notifyState("已连接：${client.inetAddress?.hostAddress}:${PORT}")
                    // 首包同步我方 IME 状态
                    sendFrame("$STATE:${if (localImeActive) "IME_ACTIVE" else "IME_INACTIVE"}")
                }
            } catch (_: Exception) {
                notifyState("服务异常")
            }
        }
    }
    private fun stopServer() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel(); serverJob = null
    }

    private fun adoptSession(sock: Socket, tag: String) {
        closeSession()
        sessionSocket = sock.apply {
            tcpNoDelay = true
            keepAlive = true
        }
        sessionWriter = PrintWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)
        readJob = ioScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    dispatchIncoming(line!!)
                }
            } catch (_: Exception) {
            } finally {
                notifyState("$tag 连接断开")
                closeSession()
            }
        }
    }
    private fun closeSession() {
        try { sessionWriter?.close() } catch (_: Exception) {}
        sessionWriter = null
        try { sessionSocket?.close() } catch (_: Exception) {}
        sessionSocket = null
        readJob?.cancel(); readJob = null
    }

    private fun sendFrame(frame: String) {
        val w = sessionWriter ?: return
        w.println(frame)
        if (w.checkError()) {
            notifyState("发送失败")
            closeSession()
        }
    }

    private fun dispatchIncoming(frame: String) {
        when {
            frame.startsWith("$STATE:") -> {
                remoteImeActive = frame.endsWith("IME_ACTIVE")
            }
            frame.startsWith("$TEXT_B64:") -> {
                val b64 = frame.removePrefix("$TEXT_B64:")
                val text = try {
                    String(Base64.decode(b64, Base64.NO_WRAP), StandardCharsets.UTF_8)
                } catch (_: Exception) { "" }
                if (text.isEmpty()) return
                mainScope.launch {
                    if (localImeActive && imeSink != null) imeSink?.onText(text)
                    else appSink?.onText(text)
                }
            }
            frame == BACKSPACE -> mainScope.launch {
                if (localImeActive && imeSink != null) imeSink?.onBackspace()
                else appSink?.onBackspace()
            }
            frame == CLEAR -> mainScope.launch {
                if (localImeActive && imeSink != null) imeSink?.onClear()
                else appSink?.onClear()
            }
        }
    }

    private fun notifyState(state: String) {
        mainScope.launch { appSink?.onConnectionState(state) }
    }

    @Suppress("unused")
    private fun isSelfIp(ip: String): Boolean = getLocalIpAddress() == ip
    private fun getLocalIpAddress(): String? {
        return try {
            val nets = NetworkInterface.getNetworkInterfaces()
            while (nets.hasMoreElements()) {
                val ni = nets.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }
}