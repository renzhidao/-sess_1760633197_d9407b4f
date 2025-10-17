// 文件: app/src/main/java/com/remoteinput/InputSenderActivity.kt
package com.remoteinput

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.*

class InputSenderActivity : AppCompatActivity() {

    private lateinit var etServerIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvConnectionStatus: TextView
    private lateinit var etInput: EditText

    // 主动连接（IME:9999 / APP:10001）
    private var socketIme: Socket? = null
    private var writerIme: PrintWriter? = null
    private var socketAppClient: Socket? = null
    private var writerAppClient: PrintWriter? = null

    // 被动接入（对方连到我 APP:10001）
    private var serverAcceptedWriter: PrintWriter? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastText = ""
    private var updatingFromRemote = false
    private var isConnected = false

    // Wi‑Fi 指定网络
    private var connectivityManager: ConnectivityManager? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null

    // 记住上次 IP / 自动重连
    private val prefs by lazy { getSharedPreferences("remote_input", Context.MODE_PRIVATE) }
    private val PREF_LAST_IP = "last_ip"
    private val AUTO_CONNECT_ON_START = true

    // 握手/自动回连控制
    private var reqConnectSent = false
    private var autoConnectTriggered = false

    // 日志
    private val TAG = "RemoteInput"
    private val uiLog = ArrayDeque<String>()
    private fun log(msg: String, t: Throwable? = null) {
        if (t != null) Log.e(TAG, msg, t) else Log.d(TAG, msg)
        runOnUiThread {
            // 只保留最近 10 行
            if (uiLog.size >= 10) uiLog.removeFirst()
            uiLog.addLast(msg)
            tvConnectionStatus.text = uiLog.joinToString("\n")
        }
    }

    companion object {
        const val PORT_IME = 9999
        const val PORT_APP = 10001
        const val CONNECTION_TIMEOUT = 10_000 // ms

        private const val REQ_CONNECT = "REQ_CONNECT"   // REQ_CONNECT:<ip>[:port]
        private const val TEXT = "TEXT"
        private const val BACKSPACE = "BACKSPACE"
        private const val CLEAR = "CLEAR"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_sender)

        etServerIp = findViewById(R.id.etServerIp)
        btnConnect = findViewById(R.id.btnConnect)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        etInput = findViewById(R.id.etInput)

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // 本机 APP 接收服务器（对端无需点连接也能互相发/握手）
        startAppReceiverServer()

        // 记住上次 IP + 可选自动重连
        prefs.getString(PREF_LAST_IP, null)?.let { last ->
            if (last.isNotBlank()) {
                etServerIp.setText(last)
                log("恢复上次 IP: $last")
                if (AUTO_CONNECT_ON_START) {
                    etServerIp.post {
                        log("自动重连流程开始")
                        connectSequence(last)
                    }
                }
            }
        }

        btnConnect.setOnClickListener {
            if (!isConnected && writerAppClient == null && writerIme == null) {
                val ip = etServerIp.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "请输入对方IP", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString(PREF_LAST_IP, ip).apply()
                    connectSequence(ip)
                }
            } else {
                disconnectAll()
            }
        }

        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingFromRemote) return
                val currentText = s?.toString() ?: ""
                scope.launch {
                    val delta = currentText.length - lastText.length
                    when {
                        delta > 0 -> broadcast("$TEXT:${currentText.substring(lastText.length)}").also {
                            log("发送 TEXT 增量: ${currentText.substring(lastText.length)}")
                        }
                        delta < 0 -> repeat(-delta) {
                            broadcast(BACKSPACE)
                        }.also { log("发送 BACKSPACE x${-delta}") }
                    }
                    lastText = currentText
                }
            }
        })
    }

    // 连通策略：先 Wi‑Fi 指定网络（绕 VPN），失败再普通直连；顺序 IME→APP
    private fun connectSequence(ip: String) {
        log("开始连接流程：$ip")
        connectPreferWifiThenDirect(ip, PORT_IME) {
            log("IME 失败，尝试 APP")
            connectPreferWifiThenDirect(ip, PORT_APP, null)
        }
    }

    private fun connectPreferWifiThenDirect(ip: String, port: Int, onFail: (() -> Unit)? = null) {
        connectViaWifi(ip, port) {
            log("Wi‑Fi 指定网络失败/不可用，切换普通直连：$ip:$port")
            connectDirect(ip, port, onFail)
        }
    }

    // —— 指定 Wi‑Fi 的连接（尽量绕过 VPN） ——
    private fun connectViaWifi(ip: String, port: Int, onFail: (() -> Unit)? = null) {
        val cm = connectivityManager ?: run { onFail?.invoke(); return }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        wifiCallback?.let { safeUnregister(cm, it) }
        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lastNetwork = network
                scope.launch {
                    log("Wi‑Fi 网络可用，尝试在 Wi‑Fi 上连接：$ip:$port")
                    val ok = tryConnectWithNetwork(network, ip, port)
                    if (!ok) onFail?.invoke()
                    wifiCallback?.let { safeUnregister(cm, it) }
                }
            }
            override fun onUnavailable() {
                log("Wi‑Fi 网络不可用")
                onFail?.invoke()
            }
        }

        try {
            val cb = wifiCallback as ConnectivityManager.NetworkCallback
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cm.requestNetwork(req, cb, CONNECTION_TIMEOUT)
            } else {
                cm.requestNetwork(req, cb)
                scope.launch {
                    delay(CONNECTION_TIMEOUT.toLong())
                    onFail?.invoke()
                    wifiCallback?.let { safeUnregister(cm, it) }
                }
            }
            log("已发出 Wi‑Fi 连接请求：$ip:$port")
        } catch (e: Exception) {
            log("Wi‑Fi 请求失败：${e.message}", e)
            onFail?.invoke()
        }
    }

    // —— 普通直连（作为回退） ——
    private fun connectDirect(ip: String, port: Int, onFail: (() -> Unit)? = null) {
        log("普通直连：$ip:$port")
        scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)
                onConnectedSocket(s, ip, port)
            } catch (e: Exception) {
                log("普通直连失败：${e.message}", e)
                onFail?.invoke()
            }
        }
    }

    private fun tryConnectWithNetwork(network: Network, ip: String, port: Int): Boolean {
        return try {
            val s = network.socketFactory.createSocket()
            s.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)
            onConnectedSocket(s, ip, port)
            true
        } catch (e: Exception) {
            log("在 Wi‑Fi 网络上连接失败：${e.message}", e)
            false
        }
    }

    private fun onConnectedSocket(s: Socket, ip: String, port: Int) {
        when (port) {
            PORT_IME -> {
                socketIme = s
                writerIme = PrintWriter(s.getOutputStream(), true)
                isConnected = true
                runOnUiThread {
                    tvConnectionStatus.text = "IME 已连接：$ip:$port"
                    btnConnect.text = "断开"
                }
                lastText = etInput.text?.toString() ?: ""
                log("IME 渠道建立成功，开始监听对端数据")
                ensureAppClientChannel(ip) // 建 APP 反向
                listenIncoming(s)
                sendReqConnectFrame()      // 广播握手帧
            }
            PORT_APP -> {
                socketAppClient = s
                writerAppClient = PrintWriter(s.getOutputStream(), true)
                runOnUiThread {
                    tvConnectionStatus.text = "APP 已连接：$ip:$port"
                    btnConnect.text = "断开"
                }
                log("APP 渠道建立成功，开始监听对端数据")
                listenIncoming(s)
                sendReqConnectFrame()
            }
        }
    }

    private fun safeUnregister(cm: ConnectivityManager, cb: ConnectivityManager.NetworkCallback?) {
        try { if (cb != null) cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        if (wifiCallback === cb) wifiCallback = null
    }

    // 建立到对方 APP(10001) 的客户端通道（反向发送）
    private fun ensureAppClientChannel(ip: String) {
        if (socketAppClient?.isConnected == true) return
        val net = lastNetwork
        scope.launch {
            try {
                val s = (net?.socketFactory?.createSocket() ?: Socket())
                s.connect(InetSocketAddress(ip, PORT_APP), CONNECTION_TIMEOUT)
                socketAppClient = s
                writerAppClient = PrintWriter(s.getOutputStream(), true)
                log("已补建 APP 反向通道")
                listenIncoming(s)
                sendReqConnectFrame()
            } catch (e: Exception) {
                log("APP 反向通道建立失败（对方未开应用接收？）：${e.message}")
            }
        }
    }

    // 监听来自对方的消息（IME或APP）
    private fun listenIncoming(sock: Socket) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), "UTF-8"))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val msg = line!!
                    if (msg.startsWith("$REQ_CONNECT:")) {
                        log("收到握手帧: $msg")
                        handleReqConnect(msg)
                    } else {
                        log("收到数据帧: ${msg.take(64)}")
                        applyRemoteMessage(msg)
                    }
                }
            } catch (e: Exception) {
                log("监听对端数据异常：${e.message}", e)
            }
        }
    }

    // 广播我的 IP 到所有通道（避免只发 IME 被忽略）
    private fun sendReqConnectFrame() {
        if (reqConnectSent) return
        val myIp = getLocalIpAddress() ?: run { log("发送握手帧失败：本机IP为空"); return }
        val frame = "$REQ_CONNECT:$myIp:$PORT_APP"
        var count = 0
        listOfNotNull(writerIme, writerAppClient, serverAcceptedWriter).forEach {
            it.println(frame); count++
        }
        log("握手帧广播完成，通道数=$count, 内容=$frame")
        reqConnectSent = true
    }

    // 对端发来的回连请求：填 IP 输入框并自动回连（先 IME，再 APP）
    private fun handleReqConnect(msg: String) {
        val parts = msg.split(":")
        if (parts.size < 2) return
        val ip = parts[1]
        val port = parts.getOrNull(2)?.toIntOrNull()
        runOnUiThread {
            etServerIp.setText(ip)
            tvConnectionStatus.text = "收到回连请求：$ip${if (port != null) ":$port" else ""}"
        }
        prefs.edit().putString(PREF_LAST_IP, ip).apply()

        if (!autoConnectTriggered && (writerIme == null && writerAppClient == null)) {
            autoConnectTriggered = true
            log("执行自动回连流程：$ip")
            connectSequence(ip)
        }
    }

    private suspend fun applyRemoteMessage(message: String) {
        withContext(Dispatchers.Main) {
            updatingFromRemote = true
            when {
                message.startsWith("$TEXT:") -> {
                    val text = message.removePrefix("$TEXT:")
                    etInput.text?.insert(etInput.selectionStart.coerceAtLeast(0), text)
                }
                message == BACKSPACE -> {
                    val start = etInput.selectionStart
                    if (start > 0) etInput.text?.delete(start - 1, start)
                }
                message == CLEAR -> etInput.setText("")
            }
            lastText = etInput.text?.toString() ?: ""
            updatingFromRemote = false
        }
    }

    // 本机 APP 接收服务器（对端无需点连接也能互相发/握手）
    private fun startAppReceiverServer() {
        scope.launch {
            try {
                val serverSocket = ServerSocket(PORT_APP)
                log("本机 APP 接收服务器已启动：$PORT_APP")
                while (isActive) {
                    val client = serverSocket.accept()
                    serverAcceptedWriter = PrintWriter(client.getOutputStream(), true)
                    log("有对端连入到本机 APP：${client.inetAddress?.hostAddress}:${client.port}")
                    launch {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val msg = line!!
                                if (msg.startsWith("$REQ_CONNECT:")) {
                                    log("本机 APP 收到握手帧: $msg")
                                    handleReqConnect(msg)
                                } else {
                                    log("本机 APP 收到数据帧: ${msg.take(64)}")
                                    applyRemoteMessage(msg)
                                }
                            }
                        } catch (e: Exception) {
                            log("本机 APP 接收通道异常：${e.message}", e)
                        } finally {
                            try { client.close() } catch (_: Exception) {}
                            log("本机 APP 接收通道关闭")
                            serverAcceptedWriter = null
                        }
                    }
                }
            } catch (e: Exception) {
                log("APP 接收服务器启动失败：${e.message}", e)
            }
        }
    }

    private fun disconnectAll() {
        scope.launch {
            try { writerIme?.close(); socketIme?.close() } catch (_: Exception) {}
            try { writerAppClient?.close(); socketAppClient?.close() } catch (_: Exception) {}
            writerIme = null; socketIme = null
            writerAppClient = null; socketAppClient = null
            serverAcceptedWriter = null
            reqConnectSent = false
            autoConnectTriggered = false
            withContext(Dispatchers.Main) {
                isConnected = false
                tvConnectionStatus.text = "未连接"
                btnConnect.text = "连接"
            }
            log("所有通道已断开，状态已重置")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectAll()
        scope.cancel()
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

    // 广播一条消息到所有可用通道
    private fun broadcast(message: String) {
        var count = 0
        listOfNotNull(writerIme, writerAppClient, serverAcceptedWriter).forEach {
            it.println(message); count++
        }
        log("广播消息: '${message.take(64)}' 到通道数=$count")
    }
}