// 文件: app/src/main/java/com/remoteinput/InputSenderActivity.kt
package com.remoteinput

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import kotlinx.coroutines.*
import java.io.PrintWriter
import java.net.Socket

class InputSenderActivity : AppCompatActivity() {
    
    private lateinit var etServerIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvConnectionStatus: TextView
    private lateinit var etInput: EditText
    
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastText = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_sender)
        
        etServerIp = findViewById(R.id.etServerIp)
        btnConnect = findViewById(R.id.btnConnect)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        etInput = findViewById(R.id.etInput)
        
        btnConnect.setOnClickListener {
            if (socket == null || socket!!.isClosed) {
                connect()
            } else {
                disconnect()
            }
        }
        
        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                handleTextChange(s.toString())
            }
        })
    }
    
    private fun connect() {
        val ip = etServerIp.text.toString().trim()
        if (ip.isEmpty()) return
        
        scope.launch {
            try {
                socket = Socket(ip, 9999)
                writer = PrintWriter(socket!!.getOutputStream(), true)
                withContext(Dispatchers.Main) {
                    tvConnectionStatus.text = "已连接"
                    btnConnect.text = "断开"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@InputSenderActivity, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
    
    private fun disconnect() {
        scope.launch {
            try {
                writer?.close()
                socket?.close()
            } finally {
                withContext(Dispatchers.Main) {
                    tvConnectionStatus.text = "未连接"
                    btnConnect.text = "连接"
                }
            }
        }
    }
    
    private fun handleTextChange(currentText: String) {
        scope.launch {
            if (currentText.length > lastText.length) {
                writer?.println("TEXT:" + currentText.last())
            } else if (currentText.length < lastText.length) {
                writer?.println("BACKSPACE")
            }
            lastText = currentText
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        scope.cancel()
    }
}