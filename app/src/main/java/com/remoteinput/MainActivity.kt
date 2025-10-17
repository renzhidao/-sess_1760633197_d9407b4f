// header
package com.remoteinput

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import java.net.Inet4Address
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

```
private val TAG = "RIH-Main"

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // 常驻启动服务，避免必须等 IME 弹起
    startService(Intent(this, SocketHubService::class.java))
    Log.i(TAG, "start service")

    val tvIpAddress: TextView = findViewById(R.id.tvIpAddress)
    val btnReceiver: Button = findViewById(R.id.btnReceiver)
    val btnSender: Button = findViewById(R.id.btnSender)

    val ip = getLocalIpAddress()
    tvIpAddress.text = "本机IP: $ip"
    Log.i(TAG, "local ip: $ip")

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
        Log.e(TAG, "getLocalIpAddress error: ${e.message}", e)
    }
    return "获取失败"
}
```

```
private val TAG = "RIH-Main"

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContentView(R.layout.activity_main)

    // 常驻启动服务，避免必须等 IME 弹起
    startService(Intent(this, SocketHubService::class.java))
    Log.i(TAG, "start service")

    val tvIpAddress: TextView = findViewById(R.id.tvIpAddress)
    val btnReceiver: Button = findViewById(R.id.btnReceiver)
    val btnSender: Button = findViewById(R.id.btnSender)

    val ip = getLocalIpAddress()
    tvIpAddress.text = "本机IP: $ip"
    Log.i(TAG, "local ip: $ip")

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
        Log.e(TAG, "getLocalIpAddress error: ${e.message}", e)
    }
    return "获取失败"
}
```

}

为什么“还是发送失败”？纯代码层面最常见的原因

- 会话被“互踢”导致写失败

旧逻辑里，每次入站/出站都会 adoptSession 并 close 旧会话；如果两端几乎同时连彼此，或已有会话时又来了新入站，会把对方现有连接踢掉。被踢一方下一次 println 就会 checkError=true，于是出现“发送失败”。
现在的“单会话守护策略”已修：有会话时拒绝新入站；出站重复拨号也被忽略。你能在日志里看到：inbound rejected (session already active) 或 session already active, ignore dialing。



- 旧逻辑里，每次入站/出站都会 adoptSession 并 close 旧会话；如果两端几乎同时连彼此，或已有会话时又来了新入站，会把对方现有连接踢掉。被踢一方下一次 println 就会 checkError=true，于是出现“发送失败”。

- 现在的“单会话守护策略”已修：有会话时拒绝新入站；出站重复拨号也被忽略。你能在日志里看到：inbound rejected (session already active) 或 session already active, ignore dialing。

- 写入错误不透明

之前 PrintWriter 只 println，不检查错误，catch 也很难触发。现在 sendFrame 对每帧都 log，且 checkError 后会记录 writer-error 并且 closeSession，定位明确。



- 之前 PrintWriter 只 println，不检查错误，catch 也很难触发。现在 sendFrame 对每帧都 log，且 checkError 后会记录 writer-error 并且 closeSession，定位明确。

- 路由不一致/IME 状态判定问题不会导致“发送失败”

这种只会“发成功但没落地”。现在日志会打印 route TEXT to IME/APP 以及 localImeActive，方便区分“发失败”和“发成功但没显示”。



- 这种只会“发成功但没落地”。现在日志会打印 route TEXT to IME/APP 以及 localImeActive，方便区分“发失败”和“发成功但没显示”。

看日志应该能快速定位

- “send <TEXT_B64> failed: writer error (peer probably closed)” 紧跟着对端会有 “read loop finished … / closeSession(writer-error)” 之类记录。再往上翻一两行，多半能看到“adoptSession(replacing existing)”或者“inbound rejected / ignore dialing”的相邻事件，印证会话抢占问题。

- “send … aborted: no active session” 说明发送时还没连上；再看对端是否“server started, listening on 10000 / 已连接”这些日志。

要是你把这四个文件替换掉再跑一轮，把 Logcat 里以 RIH- 开头的日志截几行给我，我就能在不谈环境的前提下，精确指出是哪一行代码触发了“发送失败”。