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
    private var appServerSocket: ServerSocket? = null
    private var appServerJob: Job? = null
    private var serverAcceptedWriter: PrintWriter? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastText = ""
    private var updatingFromRemote = false
    private var isConnected = false

    // Wi‑Fi 指定网络（优先尝试，失败回落普通直连）
    private var connectivityManager: ConnectivityManager? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null

    // 记住上次 IP（不开启自动连接，避免重启闪退/竞态）
    private val prefs by lazy { getSharedPreferences("remote_input", Context.MODE_PRIVATE) }
    private val PREF_LAST_IP = "last_ip"
    private val AUTO_CONNECT_ON_START = false          // 启动不自动连接
    private val AUTO_CONNECT_ON_REQ = false            // 收到握手后是否自动回连（默认不自动，避免误触/自连）

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
        btnConnect  = findViewById(R.id.btnConnect)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        etInput = findViewById(R.id.etInput)

        connectivityManager = getSystemService(ConnectivityManager::class.java)

        // 启动本机 APP 接收服务器（对端无需点连接也能互相发/握手）
        startAppReceiverServer()

        // 记住上次 IP（仅填充，不自动连接）
        prefs.getString(PREF_LAST_IP, null)?.let { last ->
            if (last.isNotBlank()) etServerIp.setText(last)
        }

        btnConnect.setOnClickListener {
            if (!isConnected && writerAppClient == null && writerIme == null) {
                val ip = etServerIp.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "请输入对方IP", Toast.LENGTH_SHORT).show()
                } else {
                    if (isSelfIp(ip)) {
                        Toast.makeText(this, "目标IP不能是本机IP", Toast.LENGTH_SHORT).show()
                        return@setOnClickListener
                    }
                    prefs.edit().putString(PREF_LAST_IP, ip).apply()
                    connectSequence(ip)
                }
            } else {
                disconnectAll()
            }
        }

        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
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

    // 连通策略：先 Wi‑Fi 指定网络（绕 VPN），失败再普通直连；顺序 IME→APP
    private fun connectSequence(ip: String) {
        runOnUiThread { tvConnectionStatus.text = "连接中：$ip" }
        connectPreferWifiThenDirect(ip, PORT_IME) {
            runOnUiThread { tvConnectionStatus.text = "IME失败，尝试APP…" }
            connectPreferWifiThenDirect(ip, PORT_APP, null)
        }
    }

    private fun connectPreferWifiThenDirect(ip: String, port: Int, onFail: (() -> Unit)? = null) {
        connectViaWifi(ip, port) {
            connectDirect(ip, port, onFail)
        }
    }

    // —— 指定 Wi‑Fi 的连接（尽量绕过 VPN），失败回落普通直连 ——
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
            override fun onUnavailable() { onFail?.invoke() }
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
            runOnUiThread { tvConnectionStatus.text = "请求 Wi‑Fi 网络…" }
        } catch (_: Exception) {
            onFail?.invoke()
        }
    }

    // —— 普通直连（作为回退） ——
    private fun connectDirect(ip: String, port: Int, onFail: (() -> Unit)? = null) {
        scope.launch {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)
                onConnectedSocket(s, ip, port)
            } catch (_: Exception) {
                onFail?.invoke()
            }
        }
    }

    private fun tryConnectWithNetwork(network: Network, ip: String, port: Int): Boolean {
        return try {
            val s = network.socketFactory.createSocket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)
            onConnectedSocket(s, ip, port)
            true
        } catch (_: Exception) {
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
                listenIncoming(s)
                sendReqConnectFrame()
            }
        }
    }

    private fun safeUnregister(cm: ConnectivityManager, cb: ConnectivityManager.NetworkCallback?) {
        try { if (cb != null) cm.unregisterNetworkCallback(cb) } catch (_: Exception) { }
        if (wifiCallback === cb) wifiCallback = null
    }

    // 建立到对方 APP(10001) 的客户端通道（反向发送）
    private fun ensureAppClientChannel(ip: String) {
        if (socketAppClient?.isConnected == true) return
        val net = lastNetwork
        scope.launch {
            try {
                val s = (net?.socketFactory?.createSocket() ?: Socket())
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, PORT_APP), CONNECTION_TIMEOUT)
                socketAppClient = s
                writerAppClient = PrintWriter(s.getOutputStream(), true)
                listenIncoming(s)
                sendReqConnectFrame()
            } catch (_: Exception) { }
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

    // 广播我的 IP 到所有通道（避免只发 IME 被忽略）
    private fun sendReqConnectFrame() {
        if (reqConnectSent) return
        val myIp = getLocalIpAddress() ?: return
        val frame = "$REQ_CONNECT:$myIp:$PORT_APP"
        listOfNotNull(writerIme, writerAppClient, serverAcceptedWriter).forEach { it.println(frame) }
        reqConnectSent = true
    }

    // 对端发来的回连请求：仅填 IP（默认不自动回连，避免误触/自连）
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

        if (AUTO_CONNECT_ON_REQ && !autoConnectTriggered && (writerIme == null && writerAppClient == null)) {
            autoConnectTriggered = true
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
        appServerJob?.cancel()
        appServerJob = scope.launch {
            try {
                try { appServerSocket?.close() } catch (_: Exception) {}
                appServerSocket = ServerSocket(PORT_APP)
                while (isActive) {
                    val client = appServerSocket!!.accept()
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
            try { appServerSocket?.close() } catch (_: Exception) {}
            appServerJob?.cancel()
            writerIme = null; socketIme = null
            writerAppClient = null; socketAppClient = null
            serverAcceptedWriter = null
            appServerSocket = null
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

    // 同时发给 IME / APP / 被动接入（有哪个通道就用哪个）
    private fun broadcast(message: String) {
        listOfNotNull(writerIme, writerAppClient, serverAcceptedWriter).forEach { it.println(message) }
    }

    // 自连防护：不允许把目标 IP 设为本机 IP
    private fun isSelfIp(ip: String): Boolean {
        val mine = getLocalIpAddress() ?: return false
        return ip == mine
    }
}