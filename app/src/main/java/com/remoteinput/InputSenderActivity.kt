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
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket

class InputSenderActivity : AppCompatActivity() {

    private lateinit var etServerIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvConnectionStatus: TextView
    private lateinit var etInput: EditText

    private var socket: Socket? = null                  // 主动连接的客户端 socket（用于发送）
    private var writer: PrintWriter? = null             // 主动连接的输出
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastText = ""
    private var isConnected = false
    private var updatingFromRemote = false

    // 强制走 Wi‑Fi
    private var connectivityManager: ConnectivityManager? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    // NSD 自动发现（两种服务）
    private var nsdManager: NsdManager? = null
    private var discoveryImeListener: NsdManager.DiscoveryListener? = null
    private var discoveryAppListener: NsdManager.DiscoveryListener? = null
    private var connectedOnce = false

    // 应用侧接收服务（当对方不是输入法，或只开 App 时也能收）
    private var appServerJob: Job? = null
    private var appServerSocket: ServerSocket? = null
    private var appRegListener: NsdManager.RegistrationListener? = null
    private var serverAcceptedWriter: PrintWriter? = null   // 有人连到本机 10001 时，向对方发送的 writer

    companion object {
        const val SERVER_PORT_IME = 9999          // 对方作为输入法时监听
        const val SERVER_PORT_APP = 10001         // 对方作为应用接收时监听
        const val CONNECTION_TIMEOUT = 10_000     // ms

        private const val NSD_TYPE_IME = "_remoteime._tcp."
        private const val NSD_TYPE_APP = "_remoteapp._tcp."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_sender)

        etServerIp = findViewById(R.id.etServerIp)
        btnConnect = findViewById(R.id.btnConnect)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        etInput = findViewById(R.id.etInput)

        connectivityManager = getSystemService(ConnectivityManager::class.java)
        nsdManager = getSystemService(NsdManager::class.java)

        // 启动本机应用接收服务并注册 NSD：无需对方点“连接”，一旦对方连上即可双向
        startAppReceiverServerAndRegister()

        btnConnect.setOnClickListener {
            if (!isConnected) {
                val ip = etServerIp.text.toString().trim()
                if (ip.isEmpty()) {
                    tvConnectionStatus.text = "正在自动发现设备…"
                    startNsdDiscoveryDual()
                } else {
                    // 优先连 IME（9999），失败再连 APP（10001）
                    connectViaWifi(ip, SERVER_PORT_IME) {
                        connectViaWifi(ip, SERVER_PORT_APP, null)
                    }
                }
            } else {
                disconnect()
            }
        }

        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (updatingFromRemote) return
                val currentText = s?.toString() ?: ""
                scope.launch {
                    val deltaAdd = currentText.length - lastText.length
                    when {
                        deltaAdd > 0 -> {
                            val add = currentText.substring(lastText.length)
                            // 优先用主动连接发送；如果没有，则用服务器被动接入的 writer（对方连上就有）
                            (writer ?: serverAcceptedWriter)?.println("TEXT:$add")
                        }
                        deltaAdd < 0 -> {
                            val del = -deltaAdd
                            val out = (writer ?: serverAcceptedWriter)
                            if (out != null) {
                                repeat(del) { out.println("BACKSPACE") }
                            }
                        }
                    }
                    lastText = currentText
                }
            }
        })
    }

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

        safeUnregisterCurrent(cm)

        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    try {
                        val s = network.socketFactory.createSocket()
                        s.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)
                        socket = s
                        writer = PrintWriter(s.getOutputStream(), true)
                        isConnected = true
                        withContext(Dispatchers.Main) {
                            tvConnectionStatus.text = "已连接: $ip:$port"
                            btnConnect.text = "断开"
                            etInput.isEnabled = true
                        }
                        stopNsd()
                        safeUnregisterCurrent(cm)

                        // 主动连接建立后，建立反向 APP 通道用于对方发送到我（只在连 IME 时启用）
                        if (port == SERVER_PORT_IME) {
                            ensureReverseAppChannel(ip)
                        }

                        // 客户端连接也开启读入循环，实现双向
                        listenClientIncoming(s)
                    } catch (e: SecurityException) {
                        withContext(Dispatchers.Main) {
                            tvConnectionStatus.text = "无权限（需 CHANGE_NETWORK_STATE）"
                            Toast.makeText(this@InputSenderActivity, "缺少网络变更权限", Toast.LENGTH_LONG).show()
                        }
                        safeUnregisterCurrent(cm); onFail?.invoke()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) { tvConnectionStatus.text = "连接失败: ${e.message}" }
                        safeUnregisterCurrent(cm); onFail?.invoke()
                    }
                }
            }

            override fun onUnavailable() {
                runOnUiThread {
                    tvConnectionStatus.text = "Wi‑Fi 网络不可用"
                    Toast.makeText(this@InputSenderActivity, "Wi‑Fi 不可用/被VPN限制", Toast.LENGTH_SHORT).show()
                }
                safeUnregisterCurrent(cm); onFail?.invoke()
            }
        }

        try {
            wifiCallback?.let { cb ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    cm.requestNetwork(req, cb, CONNECTION_TIMEOUT)
                } else {
                    cm.requestNetwork(req, cb)
                    scope.launch {
                        delay(CONNECTION_TIMEOUT.toLong())
                        if (!isConnected) {
                            withContext(Dispatchers.Main) { tvConnectionStatus.text = "连接超时" }
                            safeUnregisterCurrent(cm); onFail?.invoke()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            tvConnectionStatus.text = "请求 Wi‑Fi 失败: ${e.message}"
            safeUnregisterCurrent(cm); onFail?.invoke()
        }
    }

    // 连接对方 APP 接收端（10001），作为反向通道；这样只有一方点连接也能双向发
    private fun ensureReverseAppChannel(ip: String) {
        scope.launch {
            try {
                val s = Socket(ip, SERVER_PORT_APP)
                // 只读入对方从 APP 端发来的消息
                listenClientIncoming(s)
            } catch (_: Exception) {
                // 对方没开 APP 接收端也无妨
            }
        }
    }

    private fun listenClientIncoming(sock: Socket) {
        scope.launch {
            try {
                val reader = BufferedReader(InputStreamReader(sock.getInputStream(), "UTF-8"))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    applyRemoteMessage(line!!)
                }
            } catch (_: Exception) {
            }
        }
    }

    private fun safeUnregisterCurrent(cm: ConnectivityManager) {
        val cb = wifiCallback ?: return
        try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        finally { if (wifiCallback === cb) wifiCallback = null }
    }

    // —— NSD 自动发现：同时发现 IME(9999) 与 APP(10001)，谁先解析成功连谁 ——
    private fun startNsdDiscoveryDual() {
        connectedOnce = false
        stopNsd()

        fun makeResolveListener() = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (connectedOnce) return
                val host = serviceInfo.host?.hostAddress ?: return
                val port = serviceInfo.port
                connectedOnce = true
                runOnUiThread { tvConnectionStatus.text = "发现：$host:$port，连接中…" }
                stopNsd()
                connectViaWifi(host, port, null)
            }
        }

        val resolve = makeResolveListener()

        discoveryImeListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { nsdManager?.resolveService(serviceInfo, resolve) }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsd() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsd() }
        }

        discoveryAppListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) { nsdManager?.resolveService(serviceInfo, resolve) }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsd() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsd() }
        }

        try { nsdManager?.discoverServices(NSD_TYPE_IME, NsdManager.PROTOCOL_DNS_SD, discoveryImeListener) } catch (_: Exception) {}
        try { nsdManager?.discoverServices(NSD_TYPE_APP, NsdManager.PROTOCOL_DNS_SD, discoveryAppListener) } catch (_: Exception) {}
    }

    private fun stopNsd() {
        try { discoveryImeListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        try { discoveryAppListener?.let { nsdManager?.stopServiceDiscovery(it) } } catch (_: Exception) {}
        discoveryImeListener = null
        discoveryAppListener = null
    }

    // —— 应用侧接收：开启本地 Server(10001) 并注册 NSD 服务，让对方可直连；同时保留对接入端的 writer，实现反向发送 ——
    private fun startAppReceiverServerAndRegister() {
        // 启动本地接收服务器
        appServerJob?.cancel()
        appServerJob = scope.launch {
            try {
                appServerSocket?.close()
                appServerSocket = ServerSocket(SERVER_PORT_APP)
                while (isActive) {
                    val client = appServerSocket!!.accept()
                    // 保存给接入端的输出，用于本端输入时向对方发送（对方无需点击连接）
                    serverAcceptedWriter = PrintWriter(client.getOutputStream(), true)
                    // 读入对方发来的内容，渲染到本地输入框
                    launch {
                        try {
                            val reader = BufferedReader(InputStreamReader(client.getInputStream(), "UTF-8"))
                            var line: String?
                            while (reader.readLine().also { line = it } != null) {
                                applyRemoteMessage(line!!)
                            }
                        } catch (_: Exception) {
                        } finally {
                            try { client.close() } catch (_: Exception) {}
                            serverAcceptedWriter = null
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }

        // 注册 NSD 服务（应用接收）
        val info = NsdServiceInfo().apply {
            serviceName = "RemoteApp-${android.os.Build.MODEL}"
            serviceType = NSD_TYPE_APP
            port = SERVER_PORT_APP
        }
        appRegListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(serviceInfo: NsdServiceInfo) {}
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(serviceInfo: NsdServiceInfo) {}
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        try { nsdManager?.registerService(info, NsdManager.PROTOCOL_DNS_SD, appRegListener) } catch (_: Exception) {}
    }

    private suspend fun applyRemoteMessage(message: String) {
        withContext(Dispatchers.Main) {
            updatingFromRemote = true
            when {
                message.startsWith("TEXT:") -> {
                    val text = message.removePrefix("TEXT:")
                    etInput.text?.insert(etInput.selectionStart.coerceAtLeast(0), text)
                }
                message == "BACKSPACE" -> {
                    val start = etInput.selectionStart
                    if (start > 0) etInput.text?.delete(start - 1, start)
                }
                message == "CLEAR" -> etInput.setText("")
            }
            lastText = etInput.text?.toString() ?: ""
            updatingFromRemote = false
        }
    }

    private fun stopAppReceiver() {
        try { appRegListener?.let { nsdManager?.unregisterService(it) } } catch (_: Exception) {}
        appRegListener = null

        appServerJob?.cancel()
        try { appServerSocket?.close() } catch (_: Exception) {}
        appServerSocket = null
        serverAcceptedWriter = null
    }

    private fun disconnect() {
        scope.launch {
            try { writer?.close(); socket?.close() } catch (_: Exception) {}
            finally {
                socket = null
                writer = null
                isConnected = false
                lastText = etInput.text?.toString() ?: ""
                withContext(Dispatchers.Main) {
                    tvConnectionStatus.text = "未连接"
                    btnConnect.text = "连接"
                    etInput.isEnabled = true
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        connectivityManager?.let { cm -> safeUnregisterCurrent(cm) }
        stopNsd()
        stopAppReceiver()
        disconnect()
        scope.cancel()
    }
}