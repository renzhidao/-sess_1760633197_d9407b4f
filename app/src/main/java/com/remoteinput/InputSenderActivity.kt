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

    // 记住 IP + 去抖配对
    private val prefs by lazy { getSharedPreferences("remote_input", Context.MODE_PRIVATE) }
    private val PREF_LAST_IP = "last_ip"
    private val PREF_DEVICE_ID = "device_id"
    private val deviceId: Long by lazy {
        val exist = prefs.getLong(PREF_DEVICE_ID, 0L)
        if (exist != 0L) exist else (System.nanoTime() xor (Math.random() * Long.MAX_VALUE).toLong()).also {
            prefs.edit().putLong(PREF_DEVICE_ID, it).apply()
        }
    }
    private val AUTO_CONNECT_ON_START = false   // 不自动连，避免竞态
    private val AUTO_PAIR_ON_REQ = true         // 像配对一样：收到对端握手且胜出，再自动连

    companion object {
        const val PORT_IME = 9999
        const val PORT_APP = 10001
        const val CONNECTION_TIMEOUT = 10_000 // ms

        private const val REQ_CONNECT = "REQ_CONNECT"   // REQ_CONNECT:<ip>:<id>
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

        // 启动 APP 接收服务器
        startAppReceiverServer()

        // 回填 IP（默认不自动连）
        prefs.getString(PREF_LAST_IP, null)?.let { if (it.isNotBlank()) etServerIp.setText(it) }

        btnConnect.setOnClickListener {
            if (!isConnected && writerAppClient == null && writerIme == null) {
                val ip = etServerIp.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "请输入对方IP", Toast.LENGTH_SHORT).show()
                } else if (isSelfIp(ip)) {
                    Toast.makeText(this, "目标IP不能是本机IP", Toast.LENGTH_SHORT).show()
                } else {
                    prefs.edit().putString(PREF_LAST_IP, ip).apply()
                    tvStatus("连接中：$ip")
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
                val current = s?.toString() ?: ""
                scope.launch {
                    val delta = current.length - lastText.length
                    when {
                        delta > 0 -> broadcast("$TEXT:${current.substring(lastText.length)}")
                        delta < 0 -> repeat(-delta) { broadcast(BACKSPACE) }
                    }
                    lastText = current
                }
            }
        })
    }

    // 连接顺序：IME（9999）→ APP（10001），纯直连（最稳），IME优先保证任意App可输入
    private fun connectSequence(ip: String) {
        connectDirect(ip, PORT_IME) {
            connectDirect(ip, PORT_APP, null)
        }
    }

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
                    tvStatus("IME 已连接：$ip")
                    btnConnect.text = "断开"
                }
                lastText = etInput.text?.toString() ?: ""
                // 建 APP 反向，保证双向
                ensureAppClientChannel(ip)
                listenIncoming(s)
                sendReqConnect() // 广播握手
            }
            PORT_APP -> {
                socketAppClient = s
                writerAppClient = PrintWriter(s.getOutputStream(), true)
                runOnUiThread {
                    tvStatus("APP 已连接：$ip")
                    btnConnect.text = "断开"
                }
                listenIncoming(s)
                sendReqConnect()
            }
        }
    }

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
                sendReqConnect()
            } catch (_: Exception) { }
        }
    }

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
            } catch (_: Exception) {
                runOnUiThread { tvStatus("连接断开") }
            }
        }
    }

    // 广播“像配对”的握手：REQ_CONNECT:<ip>:<id>
    private fun sendReqConnect() {
        val ip = getLocalIpAddress() ?: return
        val frame = "$REQ_CONNECT:$ip:$deviceId"
        listOfNotNull(writerIme, writerAppClient, serverAcceptedWriter).forEach { it.println(frame) }
    }

    // 收到对端配对请求：填IP；若 AUTOPAIR 且我方ID更大（去抖规则）且未连接，则自动连
    private fun handleReqConnect(msg: String) {
        // REQ_CONNECT:<ip>:<id>
        val parts = msg.split(":")
        if (parts.size < 3) return
        val ip = parts[1]
        val otherId = parts[2].toLongOrNull() ?: 0L

        runOnUiThread {
            if (!isSelfIp(ip)) {
                etServerIp.setText(ip)
                tvStatus("收到配对请求：$ip")
                prefs.edit().putString(PREF_LAST_IP, ip).apply()
                if (AUTO_CONNECT_ON_START.not() && AUTO_PAIR_ON_REQ &&
                    writerIme == null && writerAppClient == null &&
                    deviceId > otherId) {
                    tvStatus("自动回连：$ip")
                    connectSequence(ip)
                }
            }
        }
    }

    private suspend fun applyRemoteMessage(message: String) {
        withContext(Dispatchers.Main) {
            updatingFromRemote = true
            when {
                message.startsWith("$TEXT:") -> {
                    val text = message.removePrefix("$TEXT:")
                    val pos = etInput.selectionStart.coerceAtLeast(0)
                    etInput.text?.insert(pos, text)
                }
                message == BACKSPACE -> {
                    val pos = etInput.selectionStart
                    if (pos > 0) etInput.text?.delete(pos - 1, pos)
                }
                message == CLEAR -> etInput.setText("")
            }
            lastText = etInput.text?.toString() ?: ""
            updatingFromRemote = false
        }
    }

    private fun startAppReceiverServer() {
        appServerJob?.cancel()
        appServerJob = scope.launch {
            try {
                try { appServerSocket?.close() } catch (_: Exception) {}
                appServerSocket = ServerSocket(PORT_APP)
                runOnUiThread { tvStatus("本机接收端已启动") }
                while (isActive) {
                    val client = appServerSocket!!.accept()
                    serverAcceptedWriter = PrintWriter(client.getOutputStream(), true)
                    // 读协程
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
            } catch (_: Exception) {
                runOnUiThread { tvStatus("接收端口被占用") }
            }
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
            isConnected = false
            runOnUiThread {
                tvStatus("未连接")
                btnConnect.text = "连接"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        disconnectAll()
        scope.cancel()
    }

    // ——— 辅助函数 ———
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

    private fun broadcast(message: String) {
        listOfNotNull(writerIme, writerAppClient, serverAcceptedWriter).forEach { it.println(message) }
    }

    private fun tvStatus(s: String) {
        tvConnectionStatus.text = s
    }
}