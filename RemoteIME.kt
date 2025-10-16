// RemoteIME.kt
package com.remoteinput

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import kotlinx.coroutines.*
import java.net.ServerSocket
import java.net.Socket
import java.io.BufferedReader
import java.io.InputStreamReader

class RemoteIME : InputMethodService() {
    
    private var serverJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var statusText: TextView? = null
    
    override fun onCreateInputView(): View {
        val view = layoutInflater.inflate(R.layout.keyboard_view, null)
        statusText = view.findViewById(R.id.tvStatus)
        
        view.findViewById<Button>(R.id.btnSwitchIme).setOnClickListener {
            val imm = getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        }
        
        startServer()
        return view
    }
    
    private fun startServer() {
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                val serverSocket = ServerSocket(9999)
                withContext(Dispatchers.Main) {
                    statusText?.text = "远程输入法 - 等待连接..."
                }
                
                while (isActive) {
                    val client = serverSocket.accept()
                    handleClient(client)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    private suspend fun handleClient(socket: Socket) {
        withContext(Dispatchers.Main) {
            statusText?.text = "远程输入法 - 已连接"
        }
        
        try {
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            while (true) {
                val message = reader.readLine() ?: break
                
                withContext(Dispatchers.Main) {
                    when {
                        message.startsWith("TEXT:") -> {
                            val text = message.substring(5)
                            currentInputConnection?.commitText(text, 1)
                        }
                        message == "BACKSPACE" -> {
                            currentInputConnection?.deleteSurroundingText(1, 0)
                        }
                        message == "CLEAR" -> {
                            currentInputConnection?.deleteSurroundingText(1000, 0)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            withContext(Dispatchers.Main) {
                statusText?.text = "远程输入法 - 断开连接"
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        serverJob?.cancel()
        scope.cancel()
    }
}