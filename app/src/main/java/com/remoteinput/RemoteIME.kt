// 文件: app/src/main/java/com/remoteinput/RemoteIME.kt
package com.remoteinput

import android.inputmethodservice.InputMethodService
import android.util.Log
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
    private val TAG = "RemoteInput-IME"

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
                Log.d(TAG, "IME 服务已启动，监听 9999")

                while (currentCoroutineContext().isActive) {
                    val client = serverSocket.accept()
                    Log.d(TAG, "收到连接：${client.inetAddress?.hostAddress}:${client.port}")
                    withContext(Dispatchers.Main) { statusTextView?.text = "已连接" }

                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    try {
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            val msg = line!!
                            Log.d(TAG, "收到数据帧: ${msg.take(64)}")
                            withContext(Dispatchers.Main) { processCommand(msg) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "读写异常: ${e.message}", e)
                    } finally {
                        withContext(Dispatchers.Main) { statusTextView?.text = "连接断开" }
                        client.close()
                        Log.d(TAG, "连接关闭")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "IME 服务异常: ${e.message}", e)
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
            else -> Log.d(TAG, "忽略非文本帧: ${command.take(64)}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serverJob?.cancel()
        scope.cancel()
        Log.d(TAG, "IME 服务关闭")
    }
}