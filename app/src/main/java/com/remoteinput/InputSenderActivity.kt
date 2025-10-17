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

    private var socket: Socket? = null
    private var writer: PrintWriter? = null
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

    // 应用侧接收服务（当对方不是输入法，也能把输入接过来）
    private var appServerJob: Job? = null
    private var appServerSocket: ServerSocket? = null
    private var appRegListener: NsdManager.RegistrationListener? = null

    companion object {
        const val SERVER_PORT_IME = 9999          // 对方作为输入法时监听的端口
        const val SERVER_PORT_APP = 10001         // 对方作为应用接收时监听的端口
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

        // 启动应用侧接收服务器 + NSD 注册（允许对方直接把输入发送到本机应用）
        startAppReceiverServerAndRegister()

        btnConnect.setOnClickListener {
            if (!isConnected) {
                val ip = etServerIp.text.toString().trim()
                if (ip.isEmpty()) {
                    tvConnectionStatus.text = "正在自动发现设备…"
                    startNsdDiscoveryDual()
                } else {
                    // 优先尝试连对方作为输入法的 9999，否则连应用 10001
                    connectViaWifi(ip, SERVER_PORT_IME) { // onFail fallback
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
                if (!isConnected || updatingFromRemote) return
                val currentText = s?.toString() ?: ""
                scope.launch {
                    if (currentText.length > lastText.length) {
                        writer?.println("TEXT:${currentText.substring(lastText.length)}")
                    } else if (currentText.length < lastText.length) {
                        val del = lastText.length - currentText.length
                        if (del == 1) writer?.println("BACKSPACE") else repeat(del) { writer?.println("BACKSPACE") }
                    }
                    lastText = currentText
                }
            }
        })
    }

    // —— 强制走 Wi‑Fi 的连接逻辑（绕过多数 VPN 路由） ——
    private fun connectViaWifi(ip: String, port: Int, onFail: (() -> Unit)? = null) {
        tvConnectionStatus.text = "连接中（Wi‑Fi $ip:$port）…"
        val cm = connectivityManager ?: run {
            tvConnectionStatus.text = "系统不支持 ConnectivityManager"
            onFail?.invoke()
            return
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
                            tvConnectionStatus.text = "已连接到: $ip:$port"
                            btnConnect.text = "断开"
                            etInput.isEnabled = true
                        }
                        stopNsd()
                        safeUnregisterCurrent(cm)

                        // 客户端连接也开启读入循环，实现真正双向
                        listenClientIncoming(s)
                    } catch (e: SecurityException) {
                        withContext(Dispatchers.Main) {
                            tvConnectionStatus.text = "无权限（需 CHANGE_NETWORK_STATE）"
                            Toast.makeText(this@InputSenderActivity, "缺少网络变更权限", Toast.LENGTH_LONG).show()
                        }
                        safeUnregisterCurrent(cm)
                        onFail?.invoke()
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            tvConnectionStatus.text = "连接失败: ${e.message}"
                        }
                        safeUnregisterCurrent(cm)
                        onFail?.invoke()
                    }
                }
            }

            override fun onUnavailable() {
                runOnUiThread {
                    tvConnectionStatus.text = "Wi‑Fi 网络不可用"
                    Toast.makeText(this@InputSenderActivity, "Wi‑Fi 不可用/被VPN限制", Toast.LENGTH_SHORT).show()
                }
                safeUnregisterCurrent(cm)
                onFail?.invoke()
            }
        }

        try {
            wifiCallback?.let { callback ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    cm.requestNetwork(req, callback, CONNECTION_TIMEOUT)
                } else {
                    cm.requestNetwork(req, callback)
                    scope.launch {
                        delay(CONNECTION_TIMEOUT.toLong())
                        if (!isConnected) {
                            withContext(Dispatchers.Main) { tvConnectionStatus.text = "连接超时" }
                            safeUnregisterCurrent(cm)
                            onFail?.invoke()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            tvConnectionStatus.text = "请求 Wi‑Fi 失败: ${e.message}"
            safeUnregisterCurrent(cm)
            onFail?.invoke()
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
        try {
            cm.unregisterNetworkCallback(cb)
        } catch (_: Exception) {
        } finally {
            if (wifiCallback === cb) wifiCallback = null
        }
    }

    // —— NSD 自动发现：同时发现 IME(9999) 与 APP(10001)，谁先解析成功连谁 ——
    private fun startNsdDiscoveryDual() {
        connectedOnce = false
        stopNsd()

        fun makeResolveListener(expectedType: String) = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                // 解析失败，另一路会继续
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                if (connectedOnce) return
                val host = serviceInfo.host?.hostAddress ?: return
                val port = serviceInfo.port
                connectedOnce = true
                runOnUiThread { tvConnectionStatus.text = "发现设备：$host:$port，连接中…" }
                stopNsd()
                connectViaWifi(host, port, null)
            }
        }

        val resolveIme = makeResolveListener(NSD_TYPE_IME)
        val resolveApp = makeResolveListener(NSD_TYPE_APP)

        discoveryImeListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // 过滤自身
                if (serviceInfo.serviceName?.startsWith("RemoteApp-") == true) return
                nsdManager?.resolveService(serviceInfo, resolveIme)
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsd() }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) { stopNsd() }
        }

        discoveryAppListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {}
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                // 过滤自身
                if (serviceInfo.serviceName?.startsWith("RemoteApp-") == true) return
                nsdManager?.resolveService(serviceInfo, resolveApp)
            }
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

    // —— 应用侧接收：开启本地 Server(10001) 并注册 NSD 服务，让对方可直连 ——
    private fun startAppReceiverServerAndRegister() {
        // 启动本地接收服务器
        appServerJob?.cancel()
        appServerJob = scope.launch {
            try {
                appServerSocket?.close()
                appServerSocket = ServerSocket(SERVER_PORT_APP)
                while (isActive) {
                    val client = appServerSocket!!.accept()
                    // 来一个连一个读
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
                message == "CLEAR" -> {
                    etInput.setText("")
                }
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
    }

    private fun disconnect() {
        scope.launch {
            try {
                writer?.close()
                socket?.close()
            } catch (_: Exception) {
            } finally {
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