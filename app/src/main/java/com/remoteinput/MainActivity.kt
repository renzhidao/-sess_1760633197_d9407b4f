// 文件: app/src/main/java/com/remoteinput/MainActivity.kt
package com.remoteinput

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.remoteinput.R // <-- 修复：添加 R 类导入
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    
    private lateinit var tvIpAddress: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnReceiver: Button
    private lateinit var btnSender: Button
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        initViews()
        setupListeners()
        displayIpAddress()
    }
    
    private fun initViews() {
        tvIpAddress = findViewById(R.id.tvIpAddress)
        tvStatus = findViewById(R.id.tvStatus)
        btnReceiver = findViewById(R.id.btnReceiver)
        btnSender = findViewById(R.id.btnSender)
    }
    
    private fun setupListeners() {
        btnReceiver.setOnClickListener {
            openInputMethodSettings()
        }
        
        btnSender.setOnClickListener {
            startActivity(Intent(this, InputSenderActivity::class.java))
        }
    }
    
    private fun openInputMethodSettings() {
        val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
        startActivity(intent)
        Toast.makeText(
            this, 
            "请启用并选择'远程输入法'", 
            Toast.LENGTH_LONG
        ).show()
    }
    
    private fun displayIpAddress() {
        val ip = getLocalIpAddress()
        tvIpAddress.text = "本机IP: $ip"
    }
    
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (iface.isLoopback || !iface.isUp) continue
                
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        val hostAddress = addr.hostAddress
                        if (hostAddress != null && (hostAddress.startsWith("192.168") || hostAddress.startsWith("10."))) {
                            return hostAddress
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "获取失败"
    }
}