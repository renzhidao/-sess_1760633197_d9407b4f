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

class RemoteIME : InputMethodService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var statusTextView: TextView? = null

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
                val serverSocket = ServerSocket(9999)
                withContext(Dispatchers.Main) { statusTextView?.text = "等待连接..." }

                while (currentCoroutineContext().isActive) {
                    val client = serverSocket.accept()
                    withContext(Dispatchers.Main) { statusTextView?.text = "已连接" }

                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    try {
                        while (currentCoroutineContext().isActive) {
                            val line = reader.readLine() ?: break
                            withContext(Dispatchers.Main) { processCommand(line) }
                        }
                    } finally {
                        withContext(Dispatchers.Main) { statusTextView?.text = "连接断开" }
                        client.close()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTextView?.text = "服务错误" }
            }
        }
    }

    private fun processCommand(command: String) {
        val ic = currentInputConnection ?: return
        when {
            command.startsWith("TEXT:") -> ic.commitText(command.removePrefix("TEXT:"), 1)
            command == "BACKSPACE" -> ic.deleteSurroundingText(1, 0)
            command == "CLEAR" -> ic.deleteSurroundingText(1000, 1000)
            else -> { /* 忽略未知控制帧 */ }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serverJob?.cancel()
        scope.cancel()
    }
}