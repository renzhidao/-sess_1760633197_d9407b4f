// 文件: app/src/main/java/com/remoteinput/InputSenderActivity.kt
package com.remoteinput

import android.content.*
import android.os.Bundle
import android.os.IBinder
import android.text.Editable
import android.text.TextWatcher
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*

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
            scope.launch {
                if (etServerIp.text.toString().trim() != ip) etServerIp.setText(ip)
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
            tvConnectionStatus.text = "已就绪（持续连接）"
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

        prefs.getString(PREF_LAST_IP, null)?.let { if (it.isNotBlank()) etServerIp.setText(it) }

        btnConnect.setOnClickListener {
            val ip = etServerIp.text.toString().trim()
            if (ip.isEmpty()) {
                Toast.makeText(this, "请输入对方IP", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            prefs.edit().putString(PREF_LAST_IP, ip).apply()
            tvConnectionStatus.text = "连接中：$ip"
            hub?.connectBoth(ip)
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
}