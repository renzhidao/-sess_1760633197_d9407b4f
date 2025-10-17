// 文件: app/src/main/java/com/remoteinput/InputSenderActivity.kt
package com.remoteinput

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

        // 启动本机 APP 接收服务器（对端无需点连接也能互相发/握手）
        startAppReceiverServer()

        btnConnect.setOnClickListener {
            if (!isConnected && writerAppClient == null && writerIme == null) {
                val ip = etServerIp.text.toString().trim()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "请输入对方IP", Toast.LENGTH_SHORT).show()
                } else {
                    // 直接连接（恢复之前稳定的直连方式）
                    connectDirectSequence(ip)
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

    // 一键直连顺序：先 IME(9999)，失败再 APP(10001)
    private fun connectDirectSequence(ip: String) {
        connectDirect(ip, PORT_IME) {
            connectDirect(ip, PORT_APP, null)
        }
    }

    // 直连（不再依赖 requestNetwork/自动发现）
    private fun connectDirect(ip: String, port: Int, onFail: (() -> Unit)? = null) {
        runOnUiThread { tvConnectionStatus.text = "直连中：$ip:$port" }
        scope.launch {
            try {
                val s = Socket()
                s.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)
                when (port) {
                    PORT_IME -> {
                        socketIme = s
                        writerIme = PrintWriter(s.getOutputStream(), true)
                        isConnected = true
                        withContext(Dispatchers.Main) {
                            tvConnectionStatus.text = "IME 已连接：$ip:$port"
                            btnConnect.text = "断开"
                        }
                        // 建立到对方 APP 的反向通道（只需一边点连接）
                        ensureAppClientChannel(ip)
                        listenIncoming(s)
                        sendReqConnectFrame()
                    }
                    PORT_APP -> {
                        socketAppClient = s
                        writerAppClient = PrintWriter(s.getOutputStream(), true)
                        withContext(Dispatchers.Main) {
                            tvConnectionStatus.text = "APP 已连接：$ip:$port"
                            btnConnect.text = "断开"
                        }
                        listenIncoming(s)
                        sendReqConnectFrame()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { tvConnectionStatus.text = "连接失败: ${e.message}" }
                onFail?.invoke()
            }
        }
    }

    // 选择一个可用的发送通道：IME优先，其次APP客户端，其次被动接入
    private fun outWriter(): PrintWriter? = writerIme ?: writerAppClient ?: serverAcceptedWriter

    // 建立到对方 APP(10001) 的客户端通道（反向发送），便于对方不点连接也能双向发
    private fun ensureAppClientChannel(ip: String) {
        if (socketAppClient?.isConnected == true) return
        scope.launch {
            try {
                val s = Socket()
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

    // 发送连接请求帧：REQ_CONNECT:<myIp>[:port]，对方自动填 IP 框并回连
    private fun sendReqConnectFrame() {
        if (reqConnectSent) return
        val myIp = getLocalIpAddress() ?: return
        outWriter()?.println("$REQ_CONNECT:$myIp:$PORT_APP")
        reqConnectSent = true
    }

    // 处理对端发来的连接请求：填充IP输入框并自动回连（先 IME，再 APP）
    private fun handleReqConnect(msg: String) {
        // REQ_CONNECT:<ip>[:port]
        val parts = msg.split(":")
        if (parts.size < 2) return
        val ip = parts[1]
        val port = parts.getOrNull(2)?.toIntOrNull()

        runOnUiThread {
            etServerIp.setText(ip)
            tvConnectionStatus.text = "收到回连请求：$ip${if (port != null) ":$port" else ""}"
        }

        if (!autoConnectTriggered && (writerIme == null && writerAppClient == null)) {
            autoConnectTriggered = true
            connectDirect(ip, PORT_IME) {
                connectDirect(ip, port ?: PORT_APP, null)
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