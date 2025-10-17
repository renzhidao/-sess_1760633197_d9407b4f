// header
package com.remoteinput

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
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

        // 确保服务常驻启动（无需等 IME 弹出）
        startService(Intent(this, SocketHubService::class.java))

        val tvIpAddress: TextView = findViewById(R.id.tvIpAddress)
        val btnReceiver: Button = findViewById(R.id.btnReceiver)
        val btnSender: Button = findViewById(R.id.btnSender)

        tvIpAddress.text = "本机IP: ${getLocalIpAddress()}"

        btnReceiver.setOnClickListener {
            val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)
            startActivity(intent)
            Toast.makeText(this, "请启用并选择'远程输入法'", Toast.LENGTH_LONG).show()
        }

        btnSender.setOnClickListener {
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