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
        const val PORT_IME = 9999
        const val PORT_APP = 10001

        private const val REQ_CONNECT = "REQ_CONNECT"   // REQ_CONNECT:<ip>
        private const val TEXT = "TEXT"                 // TEXT:<payload>
        private const val BACKSPACE = "BACKSPACE"
        private const val CLEAR = "CLEAR"
        private const val STATE = "STATE"               // STATE:IME_ACTIVE / STATE:IME_INACTIVE
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
        fun onHandshake(ip: String)
        fun onConnectionState(state: String)
        fun isActive(): Boolean
    }
    private var imeSink: ImeSink? = null
    private var appSink: AppSink? = null

    // 远端状态（用于出站路由：IME优先）
    private var remoteImeActive = false

    // 本机状态（用于入站落地：IME优先）
    private var localImeActive = false

    // 每个端口只保留一个“活跃连接”，入站/出站谁先连都行，后来的替换旧的
    private data class Channel(
        var socket: Socket? = null,
        var writer: PrintWriter? = null,
        var readJob: Job? = null
    )
    private val chIme = Channel()
    private val chApp = Channel()

    // 服务器（持久，和IME/Activity生命周期解耦）
    private var serverJobIme: Job? = null
    private var serverJobApp: Job? = null
    private var serverSocketIme: ServerSocket? = null
    private var serverSocketApp: ServerSocket? = null

    // Binder
    inner class LocalBinder : Binder() { fun getService(): SocketHubService = this@SocketHubService }
    override fun onBind(intent: Intent?): IBinder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        startServers()
    }
    override fun onDestroy() {
        super.onDestroy()
        stopServers()
        closeChannel(chIme)
        closeChannel(chApp)
        ioScope.cancel(); mainScope.cancel()
    }

    // 对外API
    fun registerImeSink(sink: ImeSink?) { imeSink = sink }
    fun registerAppSink(sink: AppSink?) { appSink = sink }
    fun setImeActive(active: Boolean) {
        localImeActive = active
        // 通知对端我们的 IME 活跃状态
        sendFrameAll("$STATE:${if (active) "IME_ACTIVE" else "IME_INACTIVE"}")
    }

    // 一次性连接对端两个端口（不用重连 IME）
    fun connectBoth(ip: String) {
        if (isSelfIp(ip)) { notifyState("目标IP不能是本机IP"); return }
        // 出站 IME
        ioScope.launch { connectOut(ip, PORT_IME, chIme) }
        // 出站 APP
        ioScope.launch { connectOut(ip, PORT_APP, chApp) }
    }
    fun disconnectAll() {
        closeChannel(chIme); closeChannel(chApp)
        notifyState("未连接")
    }

    fun sendText(text: String) = sendByRemoteState("$TEXT:$text")
    fun sendBackspace() = sendByRemoteState(BACKSPACE)
    fun sendClear() = sendByRemoteState(CLEAR)

    // 服务器启动（持久，不随IME/Activity销毁）
    private fun startServers() {
        if (serverJobIme == null || serverJobIme?.isActive == false) {
            serverJobIme = ioScope.launch {
                try {
                    serverSocketIme?.close(); serverSocketIme = ServerSocket(PORT_IME)
                    notifyState("IME服务监听 $PORT_IME")
                    while (isActive) acceptLoop(serverSocketIme!!, chIme, "IME")
                } catch (_: Exception) { notifyState("IME服务异常") }
            }
        }
        if (serverJobApp == null || serverJobApp?.isActive == false) {
            serverJobApp = ioScope.launch {
                try {
                    serverSocketApp?.close(); serverSocketApp = ServerSocket(PORT_APP)
                    notifyState("APP服务监听 $PORT_APP")
                    while (isActive) acceptLoop(serverSocketApp!!, chApp, "APP")
                } catch (_: Exception) { notifyState("APP服务异常") }
            }
        }
    }
    private fun stopServers() {
        try { serverSocketIme?.close() } catch (_: Exception) {}
        try { serverSocketApp?.close() } catch (_: Exception) {}
        serverSocketIme = null; serverSocketApp = null
        serverJobIme?.cancel(); serverJobIme = null
        serverJobApp?.cancel(); serverJobApp = null
    }
    private suspend fun acceptLoop(ss: ServerSocket, ch: Channel, tag: String) {
        val sock = ss.accept()
        setChannel(ch, sock, tag)
        notifyState("$tag 入站连接：${sock.inetAddress?.hostAddress}")
        // 主动发握手，让对端填 IP（像配对）
        sendFrameAll("$REQ_CONNECT:${getLocalIpAddress() ?: ""}")
        // 同步我方 IME 状态给对端
        sendFrameAll("$STATE:${if (localImeActive) "IME_ACTIVE" else "IME_INACTIVE"}")
    }

    // 出站连接（任一端都可以点连接）
    private fun connectOut(ip: String, port: Int, ch: Channel) {
        try {
            val s = Socket()
            s.tcpNoDelay = true; s.keepAlive = true
            s.connect(InetSocketAddress(ip, port), 10_000)
            setChannel(ch, s, if (port == PORT_IME) "IME" else "APP")
            notifyState("${if (port == PORT_IME) "IME" else "APP"} 出站连接：$ip")
            // 主动发握手
            sendFrameAll("$REQ_CONNECT:${getLocalIpAddress() ?: ""}")
            // 同步状态
            sendFrameAll("$STATE:${if (localImeActive) "IME_ACTIVE" else "IME_INACTIVE"}")
        } catch (_: Exception) {
            notifyState("${if (port == PORT_IME) "IME" else "APP"} 连接失败")
        }
    }

    private fun setChannel(ch: Channel, socket: Socket, tag: String) {
        closeChannel(ch)
        ch.socket = socket
        ch.writer = PrintWriter(socket.getOutputStream(), true)
        ch.readJob = ioScope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    dispatchIncoming(line!!)
                }
            } catch (_: Exception) {
            } finally {
                notifyState("$tag 连接断开")
                closeChannel(ch)
            }
        }
    }

    private fun closeChannel(ch: Channel) {
        try { ch.writer?.close() } catch (_: Exception) {}
        ch.writer = null
        try { ch.socket?.close() } catch (_: Exception) {}
        ch.socket = null
        ch.readJob?.cancel(); ch.readJob = null
    }

    // 入站帧落地（IME优先）
    private fun dispatchIncoming(frame: String) {
        when {
            frame.startsWith("$REQ_CONNECT:") -> {
                val ip = frame.substringAfter("$REQ_CONNECT:")
                mainScope.launch { appSink?.onHandshake(ip) }
            }
            frame.startsWith("$STATE:") -> {
                val s = frame.substringAfter("$STATE:")
                remoteImeActive = (s == "IME_ACTIVE")
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

    // 出站帧路由：远端 IME 活跃优先，其次 APP；两个通道都断了就提示未连接
    private fun sendByRemoteState(frame: String) {
        val okIme = chIme.writer?.let { try { it.println(frame); true } catch (_: Exception) { false } } ?: false
        val okApp = chApp.writer?.let { try { it.println(frame); true } catch (_: Exception) { false } } ?: false
        if (!okIme && !okApp) notifyState("未连接，发送失败")
    }
    private fun sendFrameAll(frame: String) {
        chIme.writer?.println(frame)
        chApp.writer?.println(frame)
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
                    if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress
                }
            }
            null
        } catch (_: Exception) { null }
    }

    private fun isSelfIp(ip: String): Boolean = getLocalIpAddress() == ip
}