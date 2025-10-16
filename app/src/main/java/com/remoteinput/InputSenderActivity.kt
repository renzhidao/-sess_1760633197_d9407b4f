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
import com.remoteinput.R // <-- 修复：添加 R 类导入
import kotlinx.coroutines.*
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

class InputSenderActivity : AppCompatActivity() {
    
    private lateinit var etServerIp: EditText
    private lateinit var btnConnect: Button
    private lateinit var tvConnectionStatus: TextView
    private lateinit var etInput: EditText
    
    private var socket: Socket? = null
    private var writer: PrintWriter? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectionJob: Job? = null
    
    private var lastText = ""
    private var isConnected = false
    
    companion object {
        const val SERVER_PORT = 9999
        const val CONNECTION_TIMEOUT = 5000
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_input_sender)
        
        initViews()
        setupListeners()
    }
    
    private fun initViews() {
        etServerIp = findViewById(R.id.etServerIp)
        btnConnect = findViewById(R.id.btnConnect)
        tvConnectionStatus = findViewById(R.id.tvConnectionStatus)
        etInput = findViewById(R.id.etInput)
    }
    
    private fun setupListeners() {
        btnConnect.setOnClickListener {
            if (isConnected) {
                disconnect()
            } else {
                connect()
            }
        }
        
        etInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            
            override fun afterTextChanged(s: Editable?) {
                if (isConnected) {
                    handleTextChange(s.toString())
                }
            }
        })
    }
    
    private fun connect() {
        val ip = etServerIp.text.toString().trim()
        
        if (ip.isEmpty()) {
            Toast.makeText(this, "请输入IP地址", Toast.LENGTH_SHORT).show()
            return
        }
        
        if (!isValidIpAddress(ip)) {
            Toast.makeText(this, "IP地址格式错误", Toast.LENGTH_SHORT).show()
            return
        }
        
        connectionJob = scope.launch {
            try {
                updateUI("连接中...")
                
                socket = Socket()
                socket?.connect(InetSocketAddress(ip, SERVER_PORT), CONNECTION_TIMEOUT)
                
                writer = PrintWriter(
                    OutputStreamWriter(socket!!.getOutputStream(), "UTF-8"),
                    true
                )
                
                isConnected = true
                
                withContext(Dispatchers.Main) {
                    tvConnectionStatus.text = "已连接到: $ip"
                    btnConnect.text = "断开连接"
                    etInput.isEnabled = true
                    Toast.makeText(this@InputSenderActivity, "连接成功", Toast.LENGTH_SHORT).show()
                }
                
            } catch (e: Exception) {
                e.printStackTrace()
                isConnected = false
                
                withContext(Dispatchers.Main) {
                    tvConnectionStatus.text = "连接失败"
                    Toast.makeText(
                        this@InputSenderActivity,
                        "连接失败: ${e.message}",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }
    
    private fun disconnect() {
        connectionJob?.cancel()
        
        scope.launch {
            try {
                writer?.close()
                socket?.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            
            socket = null
            writer = null
            isConnected = false
            lastText = ""
            
            withContext(Dispatchers.Main) {
                tvConnectionStatus.text = "未连接"
                btnConnect.text = "连接"
                etInput.isEnabled = true
            }
        }
    }
    
    private fun handleTextChange(currentText: String) {
        scope.launch {
            try {
                writer?.let { w ->
                    when {
                        currentText.isEmpty() && lastText.isNotEmpty() -> {
                            w.println("CLEAR")
                        }
                        currentText.length < lastText.length -> {
                            val deleteCount = lastText.length - currentText.length
                            if (deleteCount == 1) {
                                w.println("BACKSPACE")
                            } else {
                                w.println("DELETE:$deleteCount")
                            }
                        }
                        currentText.length > lastText.length -> {
                            val newText = currentText.substring(lastText.length)
                            w.println("TEXT:$newText")
                        }
                    }
                    lastText = currentText
                }
            } catch (e: Exception) {
                e.printStackTrace()
                withContext(Dispatchers.Main) {
                    Toast.makeText(
                        this@InputSenderActivity,
                        "发送失败，连接可能已断开",
                        Toast.LENGTH_SHORT
                    ).show()
                    disconnect()
                }
            }
        }
    }
    
    private fun isValidIpAddress(ip: String): Boolean {
        val parts = ip.split(".")
        if (parts.size != 4) return false
        
        return parts.all { part ->
            part.toIntOrNull()?.let { it in 0..255 } ?: false
        }
    }
    
    private suspend fun updateUI(status: String) {
        withContext(Dispatchers.Main) {
            tvConnectionStatus.text = status
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        disconnect()
        scope.cancel()
    }
}