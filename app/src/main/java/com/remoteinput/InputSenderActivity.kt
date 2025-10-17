// 文件: app/src/main/java/com/remoteinput/InputSenderActivity.kt
package com.remoteinput

import android.content.Context
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
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

    // 记住上次 IP（不开启自动连接）
    private val prefs by lazy { getSharedPreferences("remote_input", Context.MODE_PRIVATE) }
    private val PREF_LAST_IP = "last_ip"
    private val AUTO_CONNECT_ON_START = false // 重要：不自动连接，避免闪退

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

    // 连接顺序：IME（9999）→ APP（10001），纯直连，避免任何 Wi‑Fi 绑定导致的 EPERM
    private fun connectSequence(ip: String) {
        tvConnectionStatus.text = "连接中：$ip"
        connectDirect(ip, PORT_IME) {
            tvConnectionStatus.text = "IME失败，尝试APP…"
            connectDirect(ip, PORT_APP, null)
        }
    }

    // 纯直连（稳定、简单，不触发 EPERM）
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

    // 补建到对方 APP(10001) 的客户端通道，便于对方不点连接也能双向发
    private fun ensureAppClientChannel(ip: String) {
        if (socketAppClient?.isConnected == true) return
        scope.launch {
            try {
                val s = Socket()
                s.tcpNoDelay = true
                s.keepAlive = true
                s.connect(InetSocketAddress(ip, PORT_APP), CONNECTION_TIMEOUT)
                socketAppClient = s
                writerAppClient = PrintWriter(s.getOutputStream(), true)
                listenIncoming(s)
                sendReqConnectFrame()
            } catch (_: Exception) {
                // 对方没开 APP 接收也无妨，仍可通过 IME 正常输入
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

    // 广播我的 IP 到所有通道（避免只发 IME 被忽略）
    private fun sendReqConnectFrame() {
        if (reqConnectSent) return
        val myIp = getLocalIpAddress() ?: return
        val frame = "$REQ_CONNECT:$myIp:$PORT_APP"
        listOfNotNull(writerIme, writerAppClient, serverAcceptedWriter).forEach { it.println(frame) }
        reqConnectSent = true
    }

    // 对端发来的回连请求：填 IP 输入框并自动回连（先 IME，再 APP）
    private fun handleReqConnect(msg: String) {
        // REQ_CONNECT:<ip>[:port]
        val parts = msg.split(":")
        if (parts.size < 2) return
        val ip = parts[1]
        val port = parts.getOrNull(2)?.toIntOrNull()

        runOnUiThread {
            etServerIp.setText(ip) // 显示在 IP 框，不写入文本框
            tvConnectionStatus.text = "收到回连请求：$ip${if (port != null) ":$port" else ""}"
        }
        prefs.edit().putString(PREF_LAST_IP, ip).apply()

        if (!autoConnectTriggered && (writerIme == null && writerAppClient == null)) {
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
                // 防止端口占用导致崩溃
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
            } catch (_: Exception) { /* 端口占用时不崩溃，静默失败即可 */ }
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
}