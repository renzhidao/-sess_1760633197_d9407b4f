// 文件: app/src/main/java/com/remoteinput/InputSenderActivity.kt
package com.remoteinput

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.net.Inet4Address
import java.net.NetworkInterface

class InputSenderActivity : AppCompatActivity() {

    private lateinit var etServerIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvConnectionStatus: TextView
    private lateinit var etInput: EditText

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var updatingFromRemote = false
    private var lastText = ""

    private val prefs by lazy { getSharedPreferences("remote_input", Context.MODE_PRIVATE) }
    private val PREF_LAST_IP = "last_ip"

    private var hub: SocketHubService? = null
    private val appSink = object : SocketHubService.AppSink {
        override fun onText(text: String) {
            scope.launch {
                updatingFromRemote = true
                val pos = etInput.selectionStart.coerceAtLeast(0)
                etInput.text?.insert(pos, text)
                lastText = etInput.text?.toString() ?: ""
                updatingFromRemote = false
            }
        }
        override fun onBackspace() {
            scope.launch {
                updatingFromRemote = true
                val pos = etInput.selectionStart
                if (pos > 0) etInput.text?.delete(pos - 1, pos)
                lastText = etInput.text?.toString() ?: ""
                updatingFromRemote = false
            }
        }
        override fun onClear() {
            scope.launch {
                updatingFromRemote = true
                etInput.setText("")
                lastText = ""
                updatingFromRemote = false
            }
        }
        override fun onHandshake(ip: String) {
            // 填入 IP 框并自动回连（像配对一样）
            scope.launch {
                if (!isSelfIp(ip)) {
                    etServerIp.setText(ip)
                    tvConnectionStatus.text = "收到配对请求：$ip"
                    prefs.edit().putString(PREF_LAST_IP, ip).apply()
                    hub?.connect(ip)
                }
            }
        }
        override fun onConnectionState(state: String) {
            scope.launch { tvConnectionStatus.text = state }
        }
        override fun isActive(): Boolean = true
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SocketHubService.LocalBinder
            hub = binder.getService()
            hub?.registerAppSink(appSink)
            tvConnectionStatus.text = "已就绪（单端口配对）"
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            hub?.registerAppSink(null)
            hub = null
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_sender)

        etServerIp = findViewById(R.id.etServerIp)
        btnConnect  = findViewById(R.id.btnConnect)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        etInput = findViewById(R.id.etInput)

        val intent = Intent(this, SocketHubService::class.java)
        startService(intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)

        prefs.getString(PREF_LAST_IP, null)?.let { last ->
            if (last.isNotBlank()) etServerIp.setText(last)
        }

        btnConnect.setOnClickListener {
            val ip = etServerIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入对方IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (isSelfIp(ip)) {
                Toast.makeText(this, "目标IP不能是本机IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            tvConnectionStatus.text = "连接中：$ip:${SocketHubService.PORT}"
            prefs.edit().putString(PREF_LAST_IP, ip).apply()
            hub?.connect(ip)
        }

        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
            override fun afterTextChanged(s: Editable?) {
                if (updatingFromRemote) return
                val current = s?.toString() ?: ""
                val delta = current.length - lastText.length
                when {
                    delta > 0 -> hub?.sendText(current.substring(lastText.length))
                    delta < 0 -> repeat(-delta) { hub?.sendBackspace() }
                }
                lastText = current
            }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try { hub?.registerAppSink(null) } catch (_: Exception) {}
        try { unbindService(conn) } catch (_: Exception) {}
        scope.cancel()
    }

    private fun isSelfIp(ip: String): Boolean {
        val mine = getLocalIpAddress() ?: return false
        return mine == ip
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