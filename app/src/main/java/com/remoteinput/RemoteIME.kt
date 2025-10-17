// 文件: app/src/main/java/com/remoteinput/RemoteIME.kt
package com.remoteinput

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.net.Socket

class RemoteIME : InputMethodService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var statusTextView: TextView? = null
    private var serverSocket: ServerSocket? = null

    override fun onCreateInputView(): View {
        val keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null)
        statusTextView = keyboardView.findViewById(R.id.tvStatus)

        keyboardView.findViewById<Button>(R.id.btnSwitchIme).setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        startServer()
        return keyboardView
    }

    private fun startServer() {
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(9999)
                withContext(Dispatchers.Main) { statusTextView?.text = "等待连接..." }

                while (currentCoroutineContext().isActive) {
                    val client = serverSocket!!.accept()
                    withContext(Dispatchers.Main) { statusTextView?.text = "已连接" }
                    handleClient(client)
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) { statusTextView?.text = "服务错误" }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) {
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
            var message: String?
            while (reader.readLine().also { message = it } != null) {
                val ic = currentInputConnection ?: continue
                when {
                    message!!.startsWith("TEXT:") -> ic.commitText(message!!.removePrefix("TEXT:"), 1)
                    message == "BACKSPACE" -> ic.deleteSurroundingText(1, 0)
                    message == "CLEAR" -> ic.deleteSurroundingText(1000, 1000)
                }
            }
        } catch (_: Exception) {
        } finally {
            withContext(Dispatchers.Main) { statusTextView?.text = "连接断开" }
            try { socket.close() } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
        serverJob?.cancel()
        scope.cancel()
    }
}