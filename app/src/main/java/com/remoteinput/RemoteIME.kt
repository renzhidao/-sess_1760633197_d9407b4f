// 文件: app/src/main/java/com/remoteinput/RemoteIME.kt
package com.remoteinput

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.inputmethodservice.InputMethodService
import android.os.IBinder
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView

class RemoteIME : InputMethodService() {

    private var statusTextView: TextView? = null

    // 绑定 Hub
    private var hub: SocketHubService? = null
    private val imeSink = object : SocketHubService.ImeSink {
        override fun onText(text: String) {
            currentInputConnection?.commitText(text, 1)
        }
        override fun onBackspace() {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
        override fun onClear() {
            currentInputConnection?.deleteSurroundingText(1000, 1000)
        }
        override fun isActive(): Boolean = true // IME 视图存在即认为可用（可按需更细）
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as SocketHubService.LocalBinder
            hub = binder.getService()
            hub?.registerImeSink(imeSink)
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            hub?.registerImeSink(null)
            hub = null
        }
    }

    override fun onCreateInputView(): View {
        val v = layoutInflater.inflate(R.layout.keyboard_view, null)
        statusTextView = v.findViewById(R.id.tvStatus)
        v.findViewById<Button>(R.id.btnSwitchIme).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
        // 绑定 Service
        val intent = Intent(this, SocketHubService::class.java)
        startService(intent)
        bindService(intent, conn, Context.BIND_AUTO_CREATE)
        statusTextView?.text = "远程输入法 - 已就绪（单端口）"
        return v
    }

    override fun onDestroy() {
        super.onDestroy()
        try { hub?.registerImeSink(null) } catch (_: Exception) {}
        try { unbindService(conn) } catch (_: Exception) {}
    }
}