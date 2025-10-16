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
    
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statusText: TextView? = null
    private var serverSocket: ServerSocket? = null
    private var currentClient: Socket? = null
    
    companion object {
        const val SERVER_PORT = 9999
    }
    
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        statusText = view.findViewById(R.id.tvStatus)
        
        val btnSwitch = view.findViewById<Button>(R.id.btnSwitchIme)
        btnSwitch.setOnClickListener {
            switchToNextInputMethod()
        }
        
        startServer()
        return view
    }
    
    private fun switchToNextInputMethod() {
        val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showInputMethodPicker()
    }
    
    private fun startServer() {
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                serverSocket?.close()
                serverSocket = ServerSocket(SERVER_PORT)
                
                updateStatus("等待连接...")
                
                while (isActive) {
                    try {
                        val client = serverSocket?.accept() ?: break
                        currentClient?.close()
                        currentClient = client
                        handleClient(client)
                    } catch (e: Exception) {
                        if (isActive) {
                            e.printStackTrace()
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                updateStatus("服务器启动失败")
            }
        }
    }
    
    private suspend fun handleClient(socket: Socket) {
        updateStatus("已连接: ${socket.inetAddress.hostAddress}")
        
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream(), "UTF-8"))
            
            while (isActive && !socket.isClosed) {
                val message = reader.readLine() ?: break
                processMessage(message)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            updateStatus("连接断开")
        }
    }
    
    private suspend fun processMessage(message: String) {
        withContext(Dispatchers.Main) {
            val ic = currentInputConnection ?: return@withContext
            
            when {
                message.startsWith("TEXT:") -> {
                    val text = message.substring(5)
                    ic.commitText(text, 1)
                }
                message == "BACKSPACE" -> {
                    ic.deleteSurroundingText(1, 0)
                }
                message == "ENTER" -> {
                    ic.commitText("\n", 1)
                }
                message == "CLEAR" -> {
                    ic.deleteSurroundingText(1000, 1000)
                }
                message.startsWith("DELETE:") -> {
                    val count = message.substring(7).toIntOrNull() ?: 1
                    ic.deleteSurroundingText(count, 0)
                }
            }
        }
    }
    
    private suspend fun updateStatus(status: String) {
        withContext(Dispatchers.Main) {
            statusText?.text = "远程输入法 - $status"
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        scope.launch {
            currentClient?.close()
            serverSocket?.close()
        }
        serverJob?.cancel()
        scope.cancel()
    }
}