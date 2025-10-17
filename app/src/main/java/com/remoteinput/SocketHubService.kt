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
        private const val PING = "PING"
        private const val PONG = "PONG"
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var serverSocket: ServerSocket? = null
    private var serverJob: Job? = null

    private var peerSocket: Socket? = null
    private var peerWriter: PrintWriter? = null
    private var peerReadJob: Job? = null
    private var heartbeatJob: Job? = null
    private var lastPongTime = 0L
    private var lastPeerIp: String? = null
    private var reconnectJob: Job? = null

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
        fun onHandshake(ip: String)
        fun onConnectionState(state: String)
        fun isActive(): Boolean
    }

    private var imeSink: ImeSink? = null
    private var appSink: AppSink? = null

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
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        closePeer()
        stopServer()
        scope.cancel()
        mainScope.cancel()
    }

    fun registerImeSink(sink: ImeSink?) { imeSink = sink }
    fun registerAppSink(sink: AppSink?) { appSink = sink }

    fun connect(ip: String) {
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            if (ip == getLocalIpAddress()) {
                notifyState("目标IP不能是本机IP")
                return@launch
            }
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, PORT), 10_000)
                setPeer(s)
                lastPeerIp = ip
                sendHandshake()
                notifyState("已连接：$ip:$PORT")
            } catch (_: Exception) {
                notifyState("连接失败")
            }
        }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        heartbeatJob?.cancel()
        scope.launch {
            closePeer()
            notifyState("未连接")
        }
    }

    fun sendText(text: String) = sendFrame("$TEXT:$text")
    fun sendBackspace() = sendFrame(BACKSPACE)
    fun sendClear() = sendFrame(CLEAR)

    private fun startServerIfNeeded() {
        if (serverJob?.isActive == true) return
        serverJob = scope.launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(PORT)
                notifyState("监听端口：$PORT")
                while (isActive) {
                    val client = serverSocket!!.accept()
                    setPeer(client)
                    lastPeerIp = client.inetAddress?.hostAddress
                    sendHandshake()
                    notifyState("已连接：${lastPeerIp}:$PORT")
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

    private fun setPeer(sock: Socket) {
        closePeer()
        peerSocket = sock
        peerWriter = PrintWriter(sock.getOutputStream(), true)
        // 读循环
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
                scheduleReconnect()
            }
        }
        // 心跳
        lastPongTime = System.currentTimeMillis()
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (isActive) {
                try {
                    peerWriter?.println(PING)
                } catch (_: Exception) {}
                delay(10_000)
                if (System.currentTimeMillis() - lastPongTime > 30_000) {
                    notifyState("心跳超时，断开")
                    closePeer()
                    scheduleReconnect()
                    break
                }
            }
        }
    }

    private fun closePeer() {
        heartbeatJob?.cancel(); heartbeatJob = null
        peerReadJob?.cancel(); peerReadJob = null
        try { peerWriter?.close() } catch (_: Exception) {}
        peerWriter = null
        try { peerSocket?.close() } catch (_: Exception) {}
        peerSocket = null
    }

    private fun scheduleReconnect() {
        val ip = lastPeerIp ?: return
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(2_000)
            notifyState("尝试重连：$ip")
            connect(ip)
        }
    }

    private fun sendFrame(frame: String) {
        try {
            peerWriter?.println(frame)
        } catch (_: Exception) {
            notifyState("发送失败")
        }
    }

    private fun dispatchIncoming(frame: String) {
        when {
            frame == PING -> sendFrame(PONG)
            frame == PONG -> lastPongTime = System.currentTimeMillis()
            frame.startsWith("$REQ_CONNECT:") -> {
                val ip = frame.substringAfter("$REQ_CONNECT:")
                mainScope.launch { appSink?.onHandshake(ip) }
            }
            frame.startsWith("$TEXT:") -> {
                val payload = frame.removePrefix("$TEXT:")
                routeText(payload)
            }
            frame == BACKSPACE -> routeBackspace()
            frame == CLEAR -> routeClear()
        }
    }

    private fun routeText(text: String) {
        mainScope.launch {
            when {
                imeSink?.isActive() == true -> imeSink?.onText(text)
                appSink?.isActive() == true -> appSink?.onText(text)
                else -> appSink?.onText(text) // 无活跃接收器时落到 App
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