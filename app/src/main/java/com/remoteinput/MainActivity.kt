// 文件: app/src/main/java/com/remoteinput/MainActivity.kt
package com.remoteinput

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.remoteinput.databinding.ActivityMainBinding // <-- 修复：导入 View Binding 类
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    
    // 修复：使用 View Binding
    private lateinit var binding: ActivityMainBinding
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 修复：通过 View Binding 设置布局
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        setupListeners()
        displayIpAddress()
    }
    
    private fun setupListeners() {
        binding.btnReceiver.setOnClickListener {
            openInputMethodSettings()
        }
        
        binding.btnSender.setOnClickListener {
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
        binding.tvIpAddress.text = getString(R.string.ip_address, ip)
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