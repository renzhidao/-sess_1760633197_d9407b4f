// 文件: app/src/main/java/com/remoteinput/InputSenderActivity.kt
package com.remoteinput

import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
                if (AUTO_CONNECT_ON_START) {
                    // 避免 UI 还没起来，稍微延迟一下
                    etServerIp.post { connectSequence(last) }
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
                        delta > 0 -> broadcast("$TEXT:${currentText.substring(lastText.length)}")
                        delta < 0 -> repeat(-delta) { broadcast(BACKSPACE) }
                    }
                    lastText = currentText
                }
            }
        })
    }

    // 首选：Wi‑Fi 指定网络 → 失败再回落普通直连；顺序：IME → APP
    private fun connectSequence(ip: String) {
        tvConnectionStatus.text = "准备连接：$ip"
        // 优先 IME
        connectPreferWifiThenDirect(ip, PORT_IME) {
            // 再 APP
            connectPreferWifiThenDirect(ip, PORT_APP, null)
        }
    }

    private fun connectPreferWifiThenDirect(ip: String, port: Int, onFail: (() -> Unit)? = null) {
        // 先走 Wi‑Fi 网络（尽量绕过 VPN）
        connectViaWifi(ip, port) {
            // Wi‑Fi 不可用或失败 → 普通直连
            connectDirect(ip, port, onFail)
        }
    }

    // —— 指定 Wi‑Fi 的连接（尽量绕过 VPN） ——
    private fun connectViaWifi(ip: String, port: Int, onFail: (() -> Unit)? = null) {
        val cm = connectivityManager ?: return onFail?.invoke().let { Unit }

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        wifiCallback?.let { safeUnregister(cm, it) }

        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lastNetwork = network
                scope.launch {
                    val ok = tryConnectWithNetwork(network, ip, port)
                    if (!ok) onFail?.invoke()
                    wifiCallback?.let { safeUnregister(cm, it) }
                }
            }

            override fun onUnavailable() {
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
            tvConnectionStatus.text = "Wi‑Fi 连接请求中：$ip:$port"
        } catch (_: Exception) {
            onFail?.invoke()
        }
    }

    // —— 普通直连（作为回退） ——
    private fun connectDirect(ip: String, port: Int, onFail: (() -> Unit)? = null) {
        tvConnectionStatus.text = "直连中：$ip:$port"
        scope.launch {
            val ok = try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)
                onConnectedSocket(s, ip, port)
                true
            } catch (_: Exception) {
                onFail?.invoke()
                false
            }
        }
    }

    // —— 在指定 Network 上建 socket —— 
    private fun tryConnectWithNetwork(network: Network, ip: String, port: Int): Boolean {
        return try {
            val s = network.socketFactory.createSocket()
            s.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)
            onConnectedSocket(s, ip, port)
            true
        } catch (_: Exception) {
            false
        }
    }

    // 统一处理连接成功后的动作
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
                // 同时尝试建立 APP 通道，形成反向链路（只需一边点连接）
                ensureAppClientChannel(ip)
                listenIncoming(s)
                sendReqConnectFrame()
            }
            PORT_APP -> {
                socketAppClient = s
                writerAppClient = PrintWriter(s.getOutputStream(), true)
                runOnUiThread {
                    tvConnectionStatus.text = "APP 已连接：$ip:$port"
                    btnConnect.text = "断开"
                }
                listenIncoming(s)
                sendReqConnectFrame()
            }
        }
    }

    private fun safeUnregister(cm: ConnectivityManager, cb: ConnectivityManager.NetworkCallback?) {
        try { if (cb != null) cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        if (wifiCallback === cb) wifiCallback = null
    }

    // 建立到对方 APP(10001) 的客户端通道（反向发送），便于对方不点连接也能双向发
    private fun ensureAppClientChannel(ip: String) {
        if (socketAppClient?.isConnected == true) return
        val net = lastNetwork
        scope.launch {
            try {
                val s = (net?.socketFactory?.createSocket() ?: Socket())
                s.connect(InetSocketAddress(ip, PORT_APP), CONNECTION_TIMEOUT)
                socketAppClient = s
                writerAppClient = PrintWriter(s.getOutputStream(), true)
                listenIncoming(s)
                sendReqConnectFrame()
            } catch (_: Exception) {
                // 对方没开 APP 也无妨，仍可通过 IME 正常输入
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
                        handleReqConnect(msg)
                    } else {
                        applyRemoteMessage(msg)
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // 把我的 IP 广播给所有通道（IME/APP/被动接入），避免只发给 IME 被忽略
    private fun sendReqConnectFrame() {
        if (reqConnectSent) return
        val myIp = getLocalIpAddress() ?: return
        val frame = "$REQ_CONNECT:$myIp:$PORT_APP"
        // 广播到所有可用输出通道
        listOfNotNull(writerIme, writerAppClient, serverAcceptedWriter).forEach { it.println(frame) }
        reqConnectSent = true
    }

    // 对端发来的回连请求：只填 IP 输入框并自动回连（先 IME，再 APP），不写进文本框
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
            connectSequence(ip) // 再走一遍 IME→APP 的标准流程（带 Wi‑Fi 优先）
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
                while (isActive) {
                    val client = serverSocket.accept()
                    serverAcceptedWriter = PrintWriter(client.getOutputStream(), true)
                    launch {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                val msg = line!!
                                if (msg.startsWith("$REQ_CONNECT:")) {
                                    handleReqConnect(msg)
                                } else {
                                    applyRemoteMessage(msg)
                                }
                            }
                        } catch (_: Exception) { } finally {
                            try { client.close() } catch (_: Exception) {}
                            serverAcceptedWriter = null
                        }
                    }
                }
            } catch (_: Exception) { }
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
        listOfNotNull(writerIme, writerAppClient, serverAcceptedWriter).forEach { it.println(message) }
    }
}