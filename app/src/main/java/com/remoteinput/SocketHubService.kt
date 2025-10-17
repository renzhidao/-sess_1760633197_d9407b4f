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
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket

class SocketHubService : Service() {

    companion object {
        const val PORT = 10000

        private const val REQ_CONNECT = "REQ_CONNECT"   // REQ_CONNECT:<ip>
        private const val TEXT = "TEXT"                 // TEXT:<payload>
        private const val BACKSPACE = "BACKSPACE"
        private const val CLEAR = "CLEAR"
    }

    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 单端口服务器
    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    // 当前对等端连接（谁先连上/后连上，替换旧连接）
    private var peerSocket: Socket? = null
    private var peerWriter: PrintWriter? = null
    private var peerReadJob: Job? = null

    // 本机落地接收器：IME 和 APP（Activity）
    interface ImeSink {
        fun onText(text: String)
        fun onBackspace()
        fun onClear()
        fun isActive(): Boolean // 当前是否活跃（有输入焦点）
    }

    interface AppSink {
        fun onText(text: String)
        fun onBackspace()
        fun onClear()
        fun onHandshake(ip: String) // 收到对端IP，放到IP输入框
        fun onConnectionState(state: String) // 连接状态文案
        fun isActive(): Boolean
    }

    private var imeSink: ImeSink? = null
    private var appSink: AppSink? = null

    // Binder
    inner class LocalBinder : Binder() {
        fun getService(): SocketHubService = this@SocketHubService
    }

    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        startServerIfNeeded()
    }

    override fun onDestroy() {
        super.onDestroy()
        closePeer()
        stopServer()
        scope.cancel()
        mainScope.cancel()
    }

    // 对外API（Activity/IME 调用）
    fun registerImeSink(sink: ImeSink?) {
        imeSink = sink
    }

    fun registerAppSink(sink: AppSink?) {
        appSink = sink
    }

    fun setImeActive(active: Boolean) {
        // 可按需上报给对端 STATE 帧，这里先省略
    }

    fun setAppActive(active: Boolean) {
        // 可按需上报给对端 STATE 帧，这里先省略
    }

    fun connect(ip: String) {
        // 纯直连（稳定），避免任何绑定网络带来的权限问题
        scope.launch {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, PORT), 10_000)
                setPeer(s)
                sendHandshake()
                notifyState("已连接：$ip:$PORT")
            } catch (_: Exception) {
                notifyState("连接失败")
            }
        }
    }

    fun disconnect() {
        scope.launch {
            closePeer()
            notifyState("未连接")
        }
    }

    fun sendText(text: String) {
        sendFrame("$TEXT:$text")
    }

    fun sendBackspace() {
        sendFrame(BACKSPACE)
    }

    fun sendClear() {
        sendFrame(CLEAR)
    }

    // 内部：服务器启动/停止
    private fun startServerIfNeeded() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(PORT)
                notifyState("监听端口：$PORT")
                while (isActive) {
                    val client = serverSocket!!.accept()
                    // 来新连接就替换旧连接
                    setPeer(client)
                    sendHandshake()
                    notifyState("已连接：${client.inetAddress?.hostAddress}:$PORT")
                }
            } catch (_: Exception) {
                notifyState("服务异常")
            }
        }
    }

    private fun stopServer() {
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        serverJob = null
    }

    // 内部：设置对端连接
    private fun setPeer(sock: Socket) {
        closePeer()
        peerSocket = sock
        peerWriter = PrintWriter(sock.getOutputStream(), true)
        // 开读循环
        peerReadJob = scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), "UTF-8"))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    dispatchIncoming(line!!)
                }
            } catch (_: Exception) {
            } finally {
                notifyState("连接断开")
                closePeer()
            }
        }
    }

    private fun closePeer() {
        peerReadJob?.cancel()
        peerReadJob = null
        try { peerWriter?.close() } catch (_: Exception) {}
        peerWriter = null
        try { peerSocket?.close() } catch (_: Exception) {}
        peerSocket = null
    }

    // 内部：发送帧
    private fun sendFrame(frame: String) {
        try {
            peerWriter?.println(frame)
        } catch (_: Exception) {
            notifyState("发送失败")
        }
    }

    // 内部：分发远端帧
    private fun dispatchIncoming(frame: String) {
        when {
            frame.startsWith("$REQ_CONNECT:") -> {
                val ip = frame.substringAfter("$REQ_CONNECT:").substringBefore(":")
                mainScope.launch { appSink?.onHandshake(ip) }
            }
            frame.startsWith("$TEXT:") -> {
                val payload = frame.removePrefix("$TEXT:")
                routeText(payload)
            }
            frame == BACKSPACE -> routeBackspace()
            frame == CLEAR -> routeClear()
            else -> {
                // 其它扩展帧（HELLO/STATE）此处忽略
            }
        }
    }

    private fun routeText(text: String) {
        mainScope.launch {
            val delivered = if (imeSink?.isActive() == true) {
                imeSink?.onText(text); true
            } else if (appSink?.isActive() == true) {
                appSink?.onText(text); true
            } else {
                // 都不活跃，默认落到APP
                appSink?.onText(text); true
            }
            if (!delivered) {
                // 无接收器也无所谓
            }
        }
    }

    private fun routeBackspace() {
        mainScope.launch {
            if (imeSink?.isActive() == true) imeSink?.onBackspace()
            else appSink?.onBackspace()
        }
    }

    private fun routeClear() {
        mainScope.launch {
            if (imeSink?.isActive() == true) imeSink?.onClear()
            else appSink?.onClear()
        }
    }

    private fun sendHandshake() {
        val ip = getLocalIpAddress() ?: return
        sendFrame("$REQ_CONNECT:$ip")
    }

    private fun notifyState(state: String) {
        mainScope.launch { appSink?.onConnectionState(state) }
    }

    private fun getLocalIpAddress(): String? {
        return try {
            val nets = NetworkInterface.getNetworkInterfaces()
            while (nets.hasMoreElements()) {
                val ni = nets.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                val addrs = ni.inetAddresses
                while (addrs.hasMoreElements()) {
                    val a = addrs.nextElement()
                    if (!a.isLoopbackAddress && a is Inet4Address) {
                        val ip = a.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") ||
                            ip.matches(Regex("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*"))
                        ) return ip
                    }
                }
            }
            null
        } catch (_: Exception) { null }
    }
}