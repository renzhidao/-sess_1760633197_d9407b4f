// 文件: app/src/main/java/com/remoteinput/RemoteIME.kt
package com.remoteinput

import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.TextView
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket

class RemoteIME : InputMethodService() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var serverJob: Job? = null
    private var statusTextView: TextView? = null

    // NSD 服务注册
    private var nsdManager: NsdManager? = null
    private var nsdRegListener: NsdManager.RegistrationListener? = null

    override fun onCreateInputView(): View {
        val keyboardView = layoutInflater.inflate(R.layout.keyboard_view, null)
        statusTextView = keyboardView.findViewById(R.id.tvStatus)

        val switchButton: Button = keyboardView.findViewById(R.id.btnSwitchIme)
        switchButton.setOnClickListener {
            (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }

        startServer()
        return keyboardView
    }

    private fun startServer() {
        serverJob?.cancel()
        serverJob = scope.launch {
            try {
                val serverSocket = ServerSocket(9999)
                withContext(Dispatchers.Main) {
                    statusTextView?.text = "等待连接..."
                    registerNsdService(9999)
                }

                while (currentCoroutineContext().isActive) {
                    val client = serverSocket.accept()
                    withContext(Dispatchers.Main) { statusTextView?.text = "已连接" }

                    val reader = BufferedReader(InputStreamReader(client.getInputStream()))
                    try {
                        while (currentCoroutineContext().isActive) {
                            val line = reader.readLine() ?: break
                            withContext(Dispatchers.Main) { processCommand(line) }
                        }
                    } finally {
                        withContext(Dispatchers.Main) { statusTextView?.text = "连接断开" }
                        client.close()
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { statusTextView?.text = "服务错误" }
            }
        }
    }

    private fun processCommand(command: String) {
        val ic = currentInputConnection ?: return
        when {
            command.startsWith("TEXT:") -> ic.commitText(command.removePrefix("TEXT:"), 1)
            command == "BACKSPACE" -> ic.deleteSurroundingText(1, 0)
        }
    }

    // —— NSD 注册/反注册 ——
    private fun registerNsdService(port: Int) {
        nsdManager = getSystemService(NsdManager::class.java)
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "RemoteIME-${android.os.Build.MODEL}"
            serviceType = "_remoteime._tcp."
            this.port = port
        }
        nsdRegListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) {}
            override fun onRegistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
            override fun onServiceUnregistered(info: NsdServiceInfo) {}
            override fun onUnregistrationFailed(info: NsdServiceInfo, errorCode: Int) {}
        }
        try {
            nsdManager?.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, nsdRegListener)
        } catch (_: Exception) {
            // 某些设备可能禁用 NSD，不影响核心功能
        }
    }

    private fun unregisterNsdService() {
        try {
            nsdRegListener?.let { nsdManager?.unregisterService(it) }
        } catch (_: Exception) {
        } finally {
            nsdRegListener = null
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterNsdService()
        serverJob?.cancel()
        scope.cancel()
    }
}