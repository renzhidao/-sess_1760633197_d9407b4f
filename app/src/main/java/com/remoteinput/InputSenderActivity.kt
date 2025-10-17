// header
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
    private var connected = false

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
        override fun onConnectionState(state: String) {
            scope.launch {
                tvConnectionStatus.text = state
                connected = state.startsWith("已连接")
                btnConnect.text = if (connected) getString(R.string.disconnect) else getString(R.string.connect)
            }
        }
        override fun isActive(): Boolean = true
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SocketHubService.LocalBinder
            hub = binder.getService()
            hub?.registerAppSink(appSink)
            tvConnectionStatus.text = "已就绪（单端口、持久连接）"
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            try { hub?.registerAppSink(null) } catch (_: Exception) {}
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

        // 启动并绑定服务（常驻）
        val intent = Intent(this, SocketHubService::class.java)
        startService(intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)

        prefs.getString(PREF_LAST_IP, null)?.let { if (it.isNotBlank()) etServerIp.setText(it) }

        btnConnect.setOnClickListener {
            val ip = etServerIp.text.toString().trim()
            if (!connected) {
                if (ip.isEmpty()) { Toast.makeText(this, "请输入对方IP", Toast.LENGTH_SHORT).show(); return@setOnClickListener }
                prefs.edit().putString(PREF_LAST_IP, ip).apply()
                tvConnectionStatus.text = "连接中：$ip"
                hub?.connect(ip)
            } else {
                hub?.disconnect()
            }
        }

        // 精确差分：支持中间插入/替换/删除
        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                if (updatingFromRemote) return
                if (before > 0) repeat(before) { hub?.sendBackspace() }
                if (count > 0 && s != null) {
                    val inserted = s.subSequence(start, start + count).toString()
                    if (inserted.isNotEmpty()) hub?.sendText(inserted)
                }
            }
            override fun afterTextChanged(s: Editable?) { lastText = s?.toString() ?: "" }
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        try { hub?.registerAppSink(null) } catch (_: Exception) {}
        try { unbindService(conn) } catch (_: Exception) {}
        scope.cancel()
    }
}