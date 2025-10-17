// 文件: app/src/main/java/com/remoteinput/InputSenderActivity.kt
package com.remoteinput

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

    // 主动连接（可能连 IME:9999 或 APP:10001）
    private var socketIme: Socket? = null
    private var writerIme: PrintWriter? = null
    private var socketAppClient: Socket? = null
    private var writerAppClient: PrintWriter? = null

    // 被动接入（对方连到本机 APP:10001）
    private var serverAcceptedWriter: PrintWriter? = null

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastText = ""
    private var updatingFromRemote = false
    private var isConnected = false

    // Wi‑Fi 指定网络
    private var connectivityManager: ConnectivityManager? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null
    private var lastNetwork: Network? = null

    // 握手/自动回连控制
    private var reqConnectSent = false
    private var autoConnectTriggered = false

    companion object {
        const val PORT_IME = 9999
        const val PORT_APP = 10001
        const val CONNECTION_TIMEOUT = 10_000 // ms

        // 协议帧
        private const val REQ_CONNECT = "REQ_CONNECT"   // REQ_CONNECT:<ip>[:port]
        private const val TEXT = "TEXT"                 // TEXT:<content>
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

        // 启动本机 APP 接收服务器（对端无需点连接也能互相发/握手）
        startAppReceiverServer()

        btnConnect.setOnClickListener {
            if (!isConnected && writerAppClient == null && writerIme == null) {
                val ip = etServerIp.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "请输入对方IP", Toast.LENGTH_SHORT).show()
                } else {
                    // 优先连对方 IME（9999），成功后再建立 APP（10001）反向通道
                    connectViaWifi(ip, PORT_IME) {
                        // 对方不是输入法则尝试 APP
                        connectViaWifi(ip, PORT_APP, null)
                    }
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
                        delta > 0 -> {
                            val add = currentText.substring(lastText.length)
                            outWriter()?.println("$TEXT:$add")
                        }
                        delta < 0 -> {
                            repeat(-delta) { outWriter()?.println(BACKSPACE) }
                        }
                    }
                    lastText = currentText
                }
            }
        })
    }

    // 选择一个可用的发送通道：IME优先，其次APP客户端，其次被动接入
    private fun outWriter(): PrintWriter? = writerIme ?: writerAppClient ?: serverAcceptedWriter

    // —— 强制走 Wi‑Fi 的连接逻辑（绕过多数 VPN 路由） ——
    private fun connectViaWifi(ip: String, port: Int, onFail: (() -> Unit)? = null) {
        tvConnectionStatus.text = "连接中（$ip:$port，经 Wi‑Fi）…"
        val cm = connectivityManager ?: run {
            tvConnectionStatus.text = "系统不支持 ConnectivityManager"
            onFail?.invoke(); return
        }

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        wifiCallback?.let { safeUnregister(cm, it) }

        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                lastNetwork = network
                scope.launch {
                    try {
                        val s = network.socketFactory.createSocket()
                        s.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)
                        when (port) {
                            PORT_IME -> {
                                socketIme = s
                                writerIme = PrintWriter(s.getOutputStream(), true)
                                isConnected = true
                                withContext(Dispatchers.Main) {
                                    tvConnectionStatus.text = "已连接到 IME: $ip:$port"
                                    btnConnect.text = "断开"
                                }
                                // 建立到对方 APP 的通道，形成反向链路（只需一边点连接）
                                ensureAppClientChannel(ip)
                                // 监听来自对方的数据（IME端一般不回，但兼容）
                                listenIncoming(s)
                                // 发送带类型的连接请求（对方自动填入IP框并回连）
                                sendReqConnectFrame()
                            }
                            PORT_APP -> {
                                socketAppClient = s
                                writerAppClient = PrintWriter(s.getOutputStream(), true)
                                withContext(Dispatchers.Main) {
                                    tvConnectionStatus.text = "已连接到 APP: $ip:$port"
                                    btnConnect.text = "断开"
                                }
                                listenIncoming(s)
                                sendReqConnectFrame()
                            }
                        }
                    } catch (e: SecurityException) {
                        withContext(Dispatchers.Main) {
                            tvConnectionStatus.text = "无权限（需 CHANGE_NETWORK_STATE）"
                            Toast.makeText(this@InputSenderActivity, "缺少网络变更权限", Toast.LENGTH_LONG).show()
                        }
                        onFail?.invoke()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            tvConnectionStatus.text = "连接失败: ${e.message}"
                        }
                        onFail?.invoke()
                    } finally {
                        wifiCallback?.let { safeUnregister(cm, it) }
                    }
                }
            }

            override fun onUnavailable() {
                runOnUiThread {
                    tvConnectionStatus.text = "Wi‑Fi 网络不可用"
                    Toast.makeText(this@InputSenderActivity, "Wi‑Fi 不可用/被VPN限制", Toast.LENGTH_SHORT).show()
                }
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
                    if (!isConnected && writerAppClient == null && writerIme == null) {
                        withContext(Dispatchers.Main) { tvConnectionStatus.text = "连接超时" }
                        onFail?.invoke()
                    }
                }
            }
        } catch (e: Exception) {
            tvConnectionStatus.text = "请求 Wi‑Fi 失败: ${e.message}"
            onFail?.invoke()
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
                // 对方没开APP也无妨，仍可通过 IME 正常输入
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
                        handleReqConnect(msg)    // 不显示在输入框
                    } else {
                        applyRemoteMessage(msg)  // 文本/退格等显示在输入框
                    }
                }
            } catch (_: Exception) { }
        }
    }

    // 发送连接请求帧：REQ_CONNECT:<myIp>[:port]
    private fun sendReqConnectFrame() {
        if (reqConnectSent) return
        val myIp = getLocalIpAddress() ?: return
        outWriter()?.println("$REQ_CONNECT:$myIp:$PORT_APP")
        reqConnectSent = true
    }

    // 处理对端发来的连接请求：填充IP输入框并自动回连（先尝试 IME，再 APP）
    private fun handleReqConnect(msg: String) {
        // REQ_CONNECT:<ip>[:port]
        val parts = msg.split(":")
        if (parts.size < 2) return
        val ip = parts[1]
        // 可选端口
        val port = parts.getOrNull(2)?.toIntOrNull()

        runOnUiThread {
            // 显示在 IP 框，不显示在输入框
            etServerIp.setText(ip)
            tvConnectionStatus.text = "收到回连请求：$ip${if (port != null) ":$port" else ""}"
        }

        if (!autoConnectTriggered && (writerIme == null && writerAppClient == null)) {
            autoConnectTriggered = true
            // 先尝试回连对方 IME，再尝试 APP
            connectViaWifi(ip, PORT_IME) {
                connectViaWifi(ip, port ?: PORT_APP, null)
            }
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
                    // 读入对方发送内容并渲染
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
                        // 私网网段
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