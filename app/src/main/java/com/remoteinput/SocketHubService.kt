// 文件: app/src/main/java/com/remoteinput/SocketHubService.kt
package com.remoteinput

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.*

class SocketHubService : Service() {

    companion object {
        const val PORT = 10000
        private const val STATE = "STATE"   // STATE:IME_ACTIVE / STATE:IME_INACTIVE
        private const val TEXT = "TEXT"     // TEXT:<payload>
        private const val BACKSPACE = "BACKSPACE"
        private const val CLEAR = "CLEAR"
    }

    // 协程
    private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 本机接收器（IME落地/App落地）
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

    // 远端 IME 活跃状态（用于出站路由时可扩展；当前只按本地落地）
    private var remoteImeActive = false
    // 本机 IME 活跃状态（用于入站落地）
    private var localImeActive = false

    // 单会话
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
        ioScope.cancel(); mainScope.cancel()
    }

    // 对外API
    fun registerImeSink(sink: ImeSink?) { imeSink = sink }
    fun registerAppSink(sink: AppSink?) { appSink = sink }
    fun setImeActive(active: Boolean) {
        localImeActive = active
        // 同步我方 IME 状态给对端（可选）
        sendFrame("$STATE:${if (active) "IME_ACTIVE" else "IME_INACTIVE"}")
    }

    // 单击连接：一次拨号建立单会话；对端只需打开 App/IME 让 Service run 即可
    fun connect(ip: String) {
        if (isSelfIp(ip)) { notifyState("目标IP是本机IP"); return }
        ioScope.launch {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, PORT), 10_000)
                adoptSession(s, "出站")
                notifyState("已连接：$ip:$PORT")
                // 同步本地 IME 状态给对端（首包）
                sendFrame("$STATE:${if (localImeActive) "IME_ACTIVE" else "IME_INACTIVE"}")
            } catch (_: Exception) {
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

    fun sendText(text: String) = sendFrame("$TEXT:$text")
    fun sendBackspace() = sendFrame(BACKSPACE)
    fun sendClear() = sendFrame(CLEAR)

    // 服务器：常驻、只一份，不随 IME/Activity 销毁
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
                    notifyState("已连接：${client.inetAddress?.hostAddress}:$PORT")
                    // 同步我方 IME 状态给对端（首包）
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

    // 采用会话：有旧会话则关掉，避免重连风暴
    private fun adoptSession(sock: Socket, tag: String) {
        closeSession()
        sessionSocket = sock
        sessionWriter = PrintWriter(sock.getOutputStream(), true)
        readJob = ioScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), "UTF-8"))
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
        try { sessionWriter?.println(frame) } catch (_: Exception) { notifyState("发送失败") }
    }
    private fun dispatchIncoming(frame: String) {
        when {
            frame.startsWith("$STATE:") -> {
                remoteImeActive = frame.endsWith("IME_ACTIVE")
            }
            frame.startsWith("$TEXT:") -> {
                val payload = frame.removePrefix("$TEXT:")
                mainScope.launch {
                    if (imeSink?.isActive() == true) imeSink?.onText(payload)
                    else appSink?.onText(payload)
                }
            }
            frame == BACKSPACE -> mainScope.launch {
                if (imeSink?.isActive() == true) imeSink?.onBackspace()
                else appSink?.onBackspace()
            }
            frame == CLEAR -> mainScope.launch {
                if (imeSink?.isActive() == true) imeSink?.onClear()
                else appSink?.onClear()
            }
        }
    }

    private fun notifyState(state: String) {
        mainScope.launch { appSink?.onConnectionState(state) }
    }

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