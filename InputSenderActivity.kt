// InputSenderActivity.kt
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
    
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var lastText = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_sender)
        
        val etServerIp = findViewById<EditText>(R.id.etServerIp)
        val btnConnect = findViewById<Button>(R.id.btnConnect)
        val tvStatus = findViewById<TextView>(R.id.tvConnectionStatus)
        val etInput = findViewById<EditText>(R.id.etInput)
        
        btnConnect.setOnClickListener {
            if (socket == null) {
                val ip = etServerIp.text.toString()
                if (ip.isEmpty()) {
                    Toast.makeText(this, "请输入IP地址", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                
                scope.launch {
                    try {
                        socket = Socket(ip, 9999)
                        writer = PrintWriter(socket!!.getOutputStream(), true)
                        
                        withContext(Dispatchers.Main) {
                            tvStatus.text = "已连接"
                            btnConnect.text = "断开"
                            Toast.makeText(this@InputSenderActivity, "连接成功", Toast.LENGTH_SHORT).show()
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            Toast.makeText(this@InputSenderActivity, "连接失败: ${e.message}", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
            } else {
                disconnect()
                tvStatus.text = "未连接"
                btnConnect.text = "连接"
            }
        }
        
        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                val currentText = s.toString()
                
                scope.launch {
                    writer?.let { w ->
                        when {
                            currentText.length < lastText.length -> {
                                // 删除操作
                                w.println("BACKSPACE")
                            }
                            currentText.length > lastText.length -> {
                                // 输入新字符
                                val newChar = currentText.substring(lastText.length)
                                w.println("TEXT:$newChar")
                            }
                        }
                        lastText = currentText
                    }
                }
            }
        })
    }
    
    private fun disconnect() {
        scope.launch {
            try {
                writer?.close()
                socket?.close()
                socket = null
                writer = null
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        scope.cancel()
    }
}