// MainActivity.kt
package com.remoteinput

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        findViewById<TextView>(R.id.tvIpAddress).text = "本机IP: ${getLocalIpAddress()}"
        
        findViewById<Button>(R.id.btnReceiver).setOnClickListener {
            // 启动输入法设置
            startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            Toast.makeText(this, "请选择'远程输入法'", Toast.LENGTH_LONG).show()
        }
        
        findViewById<Button>(R.id.btnSender).setOnClickListener {
            startActivity(Intent(this, InputSenderActivity::class.java))
        }
    }
    
    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                val addresses = iface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val addr = addresses.nextElement()
                    if (!addr.isLoopbackAddress && addr is Inet4Address) {
                        return addr.hostAddress ?: "未知"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "获取失败"
    }
}