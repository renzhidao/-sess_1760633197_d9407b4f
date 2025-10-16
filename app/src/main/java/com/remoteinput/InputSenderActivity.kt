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
import java.io.PrintWriter
import java.net.InetSocketAddress
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

    // 强制走 Wi‑Fi
    private var connectivityManager: ConnectivityManager? = null
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    // NSD 自动发现
    private var nsdManager: NsdManager? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    companion object {
        const val SERVER_PORT = 9999
        const val CONNECTION_TIMEOUT = 10_000 // ms
        private const val NSD_TYPE = "_remoteime._tcp."
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

        btnConnect.setOnClickListener {
            if (!isConnected) {
                val ip = etServerIp.text.toString().trim()
                if (ip.isEmpty()) {
                    tvConnectionStatus.text = "正在自动发现设备…"
                    startNsdDiscovery()
                } else {
                    connectViaWifi(ip, SERVER_PORT)
                }
            } else {
                disconnect()
            }
        }

        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!isConnected) return
                val currentText = s?.toString() ?: ""
                scope.launch {
                    if (currentText.length > lastText.length) {
                        writer?.println("TEXT:${currentText.substring(lastText.length)}")
                    } else if (currentText.length < lastText.length) {
                        writer?.println("BACKSPACE")
                    }
                    lastText = currentText
                }
            }
        })
    }

    // —— 强制走 Wi‑Fi 的连接逻辑（绕过多数 VPN 路由） ——
    private fun connectViaWifi(ip: String, port: Int) {
        tvConnectionStatus.text = "连接中（Wi‑Fi）…"
        val cm = connectivityManager ?: run {
            tvConnectionStatus.text = "系统不支持 ConnectivityManager"
            return
        }

        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        // 清理旧回调
        wifiCallback?.let { safeUnregister(cm, it) }

        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                scope.launch {
                    try {
                        val s = network.socketFactory.createSocket()
                        s.connect(InetSocketAddress(ip, port), CONNECTION_TIMEOUT)
                        socket = s
                        writer = PrintWriter(s.getOutputStream(), true)
                        isConnected = true
                        withContext(Dispatchers.Main) {
                            tvConnectionStatus.text = "已连接到: $ip"
                            btnConnect.text = "断开"
                            etInput.isEnabled = true
                        }
                        // 连接成功后停止发现并注销回调
                        stopNsd()
                        safeUnregister(cm, cb)
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            tvConnectionStatus.text = "连接失败: ${e.message}"
                            Toast.makeText(this@InputSenderActivity, "连接失败: ${e.message}", Toast.LENGTH_LONG).show()
                        }
                        safeUnregister(cm, cb)
                    }
                }
            }

            override fun onUnavailable() {
                runOnUiThread {
                    tvConnectionStatus.text = "Wi‑Fi 网络不可用"
                    Toast.makeText(this@InputSenderActivity, "Wi‑Fi 不可用/被VPN限制", Toast.LENGTH_SHORT).show()
                }
                safeUnregister(cm, this)
            }
        }
        wifiCallback = cb

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                cm.requestNetwork(req, cb, CONNECTION_TIMEOUT)
            } else {
                // API 24–25 用两参重载 + 手动超时
                cm.requestNetwork(req, cb)
                scope.launch {
                    delay(CONNECTION_TIMEOUT.toLong())
                    if (!isConnected) {
                        withContext(Dispatchers.Main) { tvConnectionStatus.text = "连接超时" }
                        safeUnregister(cm, cb)
                    }
                }
            }
        } catch (e: Exception) {
            tvConnectionStatus.text = "请求 Wi‑Fi 失败: ${e.message}"
            safeUnregister(cm, cb)
        }
    }

    private fun safeUnregister(cm: ConnectivityManager, callback: ConnectivityManager.NetworkCallback?) {
        try {
            if (callback != null) cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        }
        if (wifiCallback === callback) wifiCallback = null
    }

    // —— NSD 自动发现 + 解析并连接 ——
    private fun startNsdDiscovery() {
        stopNsd()

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                runOnUiThread { tvConnectionStatus.text = "解析失败: $errorCode" }
            }
            override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                val host = serviceInfo.host?.hostAddress ?: return
                val port = serviceInfo.port
                runOnUiThread { tvConnectionStatus.text = "发现设备：$host，连接中…" }
                connectViaWifi(host, port)
            }
        }

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                runOnUiThread { tvConnectionStatus.text = "正在发现设备…" }
            }
            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                if (serviceInfo.serviceType == NSD_TYPE) {
                    nsdManager?.resolveService(serviceInfo, resolveListener)
                }
            }
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
            override fun onDiscoveryStopped(serviceType: String) {}
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                runOnUiThread { tvConnectionStatus.text = "发现失败: $errorCode" }
                stopNsd()
            }
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                stopNsd()
            }
        }

        try {
            nsdManager?.discoverServices(NSD_TYPE, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            tvConnectionStatus.text = "启动发现失败: ${e.message}"
        }
    }

    private fun stopNsd() {
        try {
            discoveryListener?.let { nsdManager?.stopServiceDiscovery(it) }
        } catch (_: Exception) {
        } finally {
            discoveryListener = null
        }
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
                lastText = ""
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
        connectivityManager?.let { cm -> safeUnregister(cm, wifiCallback) }
        stopNsd()
        disconnect()
        scope.cancel()
    }
}