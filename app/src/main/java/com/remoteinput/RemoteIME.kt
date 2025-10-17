// header
package com.remoteinput

import android.content.*
import android.inputmethodservice.InputMethodService
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView

class RemoteIME : InputMethodService() {

```
private var statusTextView: TextView? = null
private var hub: SocketHubService? = null
private val TAG = "RIH-IME"

private val imeSink = object : SocketHubService.ImeSink {
    override fun onText(text: String) { Log.v(TAG, "commitText len=${text.length}"); currentInputConnection?.commitText(text, 1) }
    override fun onBackspace() { Log.v(TAG, "backspace"); currentInputConnection?.deleteSurroundingText(1, 0) }
    override fun onClear() { Log.v(TAG, "clear"); currentInputConnection?.deleteSurroundingText(1000, 1000) }
    override fun isActive(): Boolean = true
}

private val conn = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service as SocketHubService.LocalBinder
        hub = binder.getService()
        hub?.registerImeSink(imeSink)
        hub?.setImeActive(true)
        statusTextView?.text = "远程输入法 - 就绪"
        Log.i(TAG, "service connected")
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        try { hub?.registerImeSink(null) } catch (_: Exception) {}
        hub = null
        Log.w(TAG, "service disconnected")
    }
}

override fun onCreateInputView(): View {
    val v = layoutInflater.inflate(R.layout.keyboard_view, null)
    statusTextView = v.findViewById(R.id.tvStatus)
    v.findViewById<Button>(R.id.btnSwitchIme).setOnClickListener {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }
    val intent = Intent(this, SocketHubService::class.java)
    startService(intent)
    bindService(intent, conn, Context.BIND_AUTO_CREATE)
    Log.i(TAG, "onCreateInputView")
    return v
}

override fun onStartInputView(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
    super.onStartInputView(attribute, restarting)
    hub?.setImeActive(true)
    statusTextView?.text = "远程输入法 - 活跃"
    Log.i(TAG, "onStartInputView: active=true")
}

override fun onFinishInputView(finishingInput: Boolean) {
    super.onFinishInputView(finishingInput)
    hub?.setImeActive(false)
    statusTextView?.text = "远程输入法 - 非活跃"
    Log.i(TAG, "onFinishInputView: active=false")
}

override fun onDestroy() {
    super.onDestroy()
    try { hub?.registerImeSink(null) } catch (_: Exception) {}
    hub?.setImeActive(false)
    try { unbindService(conn) } catch (_: Exception) {}
    Log.i(TAG, "onDestroy")
}
```

```
private var statusTextView: TextView? = null
private var hub: SocketHubService? = null
private val TAG = "RIH-IME"

private val imeSink = object : SocketHubService.ImeSink {
    override fun onText(text: String) { Log.v(TAG, "commitText len=${text.length}"); currentInputConnection?.commitText(text, 1) }
    override fun onBackspace() { Log.v(TAG, "backspace"); currentInputConnection?.deleteSurroundingText(1, 0) }
    override fun onClear() { Log.v(TAG, "clear"); currentInputConnection?.deleteSurroundingText(1000, 1000) }
    override fun isActive(): Boolean = true
}

private val conn = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
        val binder = service as SocketHubService.LocalBinder
        hub = binder.getService()
        hub?.registerImeSink(imeSink)
        hub?.setImeActive(true)
        statusTextView?.text = "远程输入法 - 就绪"
        Log.i(TAG, "service connected")
    }
    override fun onServiceDisconnected(name: ComponentName?) {
        try { hub?.registerImeSink(null) } catch (_: Exception) {}
        hub = null
        Log.w(TAG, "service disconnected")
    }
}

override fun onCreateInputView(): View {
    val v = layoutInflater.inflate(R.layout.keyboard_view, null)
    statusTextView = v.findViewById(R.id.tvStatus)
    v.findViewById<Button>(R.id.btnSwitchIme).setOnClickListener {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
    }
    val intent = Intent(this, SocketHubService::class.java)
    startService(intent)
    bindService(intent, conn, Context.BIND_AUTO_CREATE)
    Log.i(TAG, "onCreateInputView")
    return v
}

override fun onStartInputView(attribute: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
    super.onStartInputView(attribute, restarting)
    hub?.setImeActive(true)
    statusTextView?.text = "远程输入法 - 活跃"
    Log.i(TAG, "onStartInputView: active=true")
}

override fun onFinishInputView(finishingInput: Boolean) {
    super.onFinishInputView(finishingInput)
    hub?.setImeActive(false)
    statusTextView?.text = "远程输入法 - 非活跃"
    Log.i(TAG, "onFinishInputView: active=false")
}

override fun onDestroy() {
    super.onDestroy()
    try { hub?.registerImeSink(null) } catch (_: Exception) {}
    hub?.setImeActive(false)
    try { unbindService(conn) } catch (_: Exception) {}
    Log.i(TAG, "onDestroy")
}
```

}