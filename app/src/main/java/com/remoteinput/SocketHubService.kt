// header
package com.remoteinput

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Base64
import android.util.Log
import kotlinx.coroutines.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

class SocketHubService : Service() {

```
companion object {
    const val PORT = 10000
    private const val STATE = "STATE"        // STATE:IME_ACTIVE / STATE:IME_INACTIVE
    private const val TEXT_B64 = "TEXTB64"   // TEXTB64:<base64 payload>
    private const val BACKSPACE = "BACKSPACE"
    private const val CLEAR = "CLEAR"

    private const val TAG = "RIH-Hub"
}

private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

interface ImeSink {
    fun onText(text: String)
    fun onBackspace()
    fun onClear()
    fun isActive(): Boolean
}
interface AppSink {
    fun onText(text: String)
    fun onBackspace()
    fun onClear()
    fun onConnectionState(state: String)
    fun isActive(): Boolean
}

private var imeSink: ImeSink? = null
private var appSink: AppSink? = null

private var remoteImeActive = false
private var localImeActive = false

// 单会话
@Volatile private var sessionSocket: Socket? = null
@Volatile private var sessionWriter: PrintWriter? = null
private var readJob: Job? = null
private var serverJob: Job? = null
private var serverSocket: ServerSocket? = null

inner class LocalBinder : Binder() { fun getService(): SocketHubService = this@SocketHubService }
override fun onBind(intent: Intent?): IBinder = LocalBinder()

override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "onCreate: starting server...")
    startServer()
}

override fun onDestroy() {
    super.onDestroy()
    Log.i(TAG, "onDestroy: shutting down")
    closeSession("service destroy")
    stopServer()
    ioScope.cancel()
    mainScope.cancel()
}

fun registerImeSink(sink: ImeSink?) {
    imeSink = sink
    Log.i(TAG, "registerImeSink: ${sink != null}")
}
fun registerAppSink(sink: AppSink?) {
    appSink = sink
    Log.i(TAG, "registerAppSink: ${sink != null}")
}

fun setImeActive(active: Boolean) {
    localImeActive = active
    Log.i(TAG, "setImeActive -> $active")
    sendFrame("$STATE:${if (active) "IME_ACTIVE" else "IME_INACTIVE"}")
}

// 拨号：有会话就不重复连接（避免互踢）
fun connect(ip: String) {
    if (sessionSocket?.isConnected == true && sessionSocket?.isClosed == false) {
        Log.w(TAG, "connect: session already active, ignore dialing")
        notifyState("已连接：保持现有会话")
        return
    }
    ioScope.launch {
        try {
            Log.i(TAG, "connect: dialing $ip:$PORT ...")
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(ip, PORT), 10_000)
            adoptSession(s, "出站")
            notifyState("已连接：$ip:$PORT")
            sendFrame("$STATE:${if (localImeActive) "IME_ACTIVE" else "IME_INACTIVE"}")
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}", e)
            notifyState("连接失败")
        }
    }
}

fun disconnect() {
    ioScope.launch {
        Log.i(TAG, "disconnect: manual")
        closeSession("manual")
        notifyState("未连接")
    }
}

fun sendText(text: String) {
    val b64 = Base64.encodeToString(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    sendFrame("$TEXT_B64:$b64")
}
fun sendBackspace() = sendFrame(BACKSPACE)
fun sendClear() = sendFrame(CLEAR)

private fun startServer() {
    if (serverJob?.isActive == true) return
    serverJob = ioScope.launch {
        try {
            serverSocket?.close()
            serverSocket = ServerSocket(PORT)
            Log.i(TAG, "server started, listening on $PORT")
            notifyState("监听端口：$PORT")
            while (isActive) {
                val client = serverSocket!!.accept()
                val remoteIp = client.inetAddress?.hostAddress
                Log.i(TAG, "server accept inbound from $remoteIp")
                // 守护策略：已有会话则拒绝新入站，避免互踢
                if (sessionSocket?.isConnected == true && sessionSocket?.isClosed == false) {
                    Log.w(TAG, "inbound rejected (session already active). closing new socket.")
                    try { client.close() } catch (_: Exception) {}
                    continue
                }
                adoptSession(client, "入站")
                notifyState("已连接：$remoteIp:$PORT")
                sendFrame("$STATE:${if (localImeActive) "IME_ACTIVE" else "IME_INACTIVE"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "server error: ${e.message}", e)
            notifyState("服务异常")
        }
    }
}

private fun stopServer() {
    try { serverSocket?.close() } catch (_: Exception) {}
    serverSocket = null
    serverJob?.cancel(); serverJob = null
    Log.i(TAG, "server stopped")
}

@Synchronized
private fun adoptSession(sock: Socket, tag: String) {
    val had = sessionSocket != null
    if (had) Log.w(TAG, "adoptSession($tag): replacing existing session")
    closeSession("adopt:$tag")
    sessionSocket = sock.apply {
        tcpNoDelay = true
        keepAlive = true
    }
    sessionWriter = PrintWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)
    Log.i(TAG, "adoptSession($tag): session established to ${sock.inetAddress?.hostAddress}:${sock.port}")
    readJob = ioScope.launch {
        try {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val f = line!!
                val type = when {
                    f.startsWith("$STATE:") -> "STATE"
                    f.startsWith("$TEXT_B64:") -> "TEXT_B64"
                    else -> f
                }
                Log.v(TAG, "recv <$type> len=${f.length}")
                dispatchIncoming(f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "read loop error: ${e.message}", e)
        } finally {
            Log.w(TAG, "read loop finished ($tag)")
            notifyState("$tag 连接断开")
            closeSession("read-end:$tag")
        }
    }
}

@Synchronized
private fun closeSession(reason: String) {
    Log.i(TAG, "closeSession: reason=$reason")
    try { sessionWriter?.close() } catch (_: Exception) {}
    sessionWriter = null
    try { sessionSocket?.close() } catch (_: Exception) {}
    sessionSocket = null
    readJob?.cancel(); readJob = null
}

private fun sendFrame(frame: String) {
    val w = sessionWriter
    val pfx = when {
        frame.startsWith("$STATE:") -> "STATE"
        frame.startsWith("$TEXT_B64:") -> "TEXT_B64"
        else -> frame.take(16)
    }
    if (w == null) {
        Log.w(TAG, "send <$pfx> aborted: no active session")
        return
    }
    w.println(frame)
    if (w.checkError()) {
        Log.e(TAG, "send <$pfx> failed: writer error (peer probably closed)")
        notifyState("发送失败")
        closeSession("writer-error")
    } else {
        Log.v(TAG, "send <$pfx> ok")
    }
}

private fun dispatchIncoming(frame: String) {
    when {
        frame.startsWith("$STATE:") -> {
            val active = frame.endsWith("IME_ACTIVE")
            remoteImeActive = active
            Log.i(TAG, "remote IME state -> $active")
        }
        frame.startsWith("$TEXT_B64:") -> {
            val b64 = frame.removePrefix("$TEXT_B64:")
            val text = try {
                String(Base64.decode(b64, Base64.NO_WRAP), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "decode TEXT_B64 error: ${e.message}", e); ""
            }
            if (text.isEmpty()) return
            mainScope.launch {
                val routeIme = localImeActive && imeSink != null
                Log.d(TAG, "route TEXT to ${if (routeIme) "IME" else "APP"} (localImeActive=$localImeActive)")
                if (routeIme) imeSink?.onText(text) else appSink?.onText(text)
            }
        }
        frame == BACKSPACE -> mainScope.launch {
            val routeIme = localImeActive && imeSink != null
            Log.d(TAG, "route BACKSPACE to ${if (routeIme) "IME" else "APP"}")
            if (routeIme) imeSink?.onBackspace() else appSink?.onBackspace()
        }
        frame == CLEAR -> mainScope.launch {
            val routeIme = localImeActive && imeSink != null
            Log.d(TAG, "route CLEAR to ${if (routeIme) "IME" else "APP"}")
            if (routeIme) imeSink?.onClear() else appSink?.onClear()
        }
        else -> Log.w(TAG, "unknown frame: ${frame.take(64)}")
    }
}

@Suppress("unused")
private fun isSelfIp(ip: String): Boolean = getLocalIpAddress() == ip
private fun getLocalIpAddress(): String? {
    return try {
        val nets = NetworkInterface.getNetworkInterfaces()
        while (nets.hasMoreElements()) {
            val ni = nets.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress
            }
        }
        null
    } catch (_: Exception) { null }
}
```

```
companion object {
    const val PORT = 10000
    private const val STATE = "STATE"        // STATE:IME_ACTIVE / STATE:IME_INACTIVE
    private const val TEXT_B64 = "TEXTB64"   // TEXTB64:<base64 payload>
    private const val BACKSPACE = "BACKSPACE"
    private const val CLEAR = "CLEAR"

    private const val TAG = "RIH-Hub"
}

private val ioScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
private val mainScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

interface ImeSink {
    fun onText(text: String)
    fun onBackspace()
    fun onClear()
    fun isActive(): Boolean
}
interface AppSink {
    fun onText(text: String)
    fun onBackspace()
    fun onClear()
    fun onConnectionState(state: String)
    fun isActive(): Boolean
}

private var imeSink: ImeSink? = null
private var appSink: AppSink? = null

private var remoteImeActive = false
private var localImeActive = false

// 单会话
@Volatile private var sessionSocket: Socket? = null
@Volatile private var sessionWriter: PrintWriter? = null
private var readJob: Job? = null
private var serverJob: Job? = null
private var serverSocket: ServerSocket? = null

inner class LocalBinder : Binder() { fun getService(): SocketHubService = this@SocketHubService }
override fun onBind(intent: Intent?): IBinder = LocalBinder()

override fun onCreate() {
    super.onCreate()
    Log.i(TAG, "onCreate: starting server...")
    startServer()
}

override fun onDestroy() {
    super.onDestroy()
    Log.i(TAG, "onDestroy: shutting down")
    closeSession("service destroy")
    stopServer()
    ioScope.cancel()
    mainScope.cancel()
}

fun registerImeSink(sink: ImeSink?) {
    imeSink = sink
    Log.i(TAG, "registerImeSink: ${sink != null}")
}
fun registerAppSink(sink: AppSink?) {
    appSink = sink
    Log.i(TAG, "registerAppSink: ${sink != null}")
}

fun setImeActive(active: Boolean) {
    localImeActive = active
    Log.i(TAG, "setImeActive -> $active")
    sendFrame("$STATE:${if (active) "IME_ACTIVE" else "IME_INACTIVE"}")
}

// 拨号：有会话就不重复连接（避免互踢）
fun connect(ip: String) {
    if (sessionSocket?.isConnected == true && sessionSocket?.isClosed == false) {
        Log.w(TAG, "connect: session already active, ignore dialing")
        notifyState("已连接：保持现有会话")
        return
    }
    ioScope.launch {
        try {
            Log.i(TAG, "connect: dialing $ip:$PORT ...")
            val s = Socket()
            s.tcpNoDelay = true
            s.keepAlive = true
            s.connect(InetSocketAddress(ip, PORT), 10_000)
            adoptSession(s, "出站")
            notifyState("已连接：$ip:$PORT")
            sendFrame("$STATE:${if (localImeActive) "IME_ACTIVE" else "IME_INACTIVE"}")
        } catch (e: Exception) {
            Log.e(TAG, "connect failed: ${e.message}", e)
            notifyState("连接失败")
        }
    }
}

fun disconnect() {
    ioScope.launch {
        Log.i(TAG, "disconnect: manual")
        closeSession("manual")
        notifyState("未连接")
    }
}

fun sendText(text: String) {
    val b64 = Base64.encodeToString(text.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
    sendFrame("$TEXT_B64:$b64")
}
fun sendBackspace() = sendFrame(BACKSPACE)
fun sendClear() = sendFrame(CLEAR)

private fun startServer() {
    if (serverJob?.isActive == true) return
    serverJob = ioScope.launch {
        try {
            serverSocket?.close()
            serverSocket = ServerSocket(PORT)
            Log.i(TAG, "server started, listening on $PORT")
            notifyState("监听端口：$PORT")
            while (isActive) {
                val client = serverSocket!!.accept()
                val remoteIp = client.inetAddress?.hostAddress
                Log.i(TAG, "server accept inbound from $remoteIp")
                // 守护策略：已有会话则拒绝新入站，避免互踢
                if (sessionSocket?.isConnected == true && sessionSocket?.isClosed == false) {
                    Log.w(TAG, "inbound rejected (session already active). closing new socket.")
                    try { client.close() } catch (_: Exception) {}
                    continue
                }
                adoptSession(client, "入站")
                notifyState("已连接：$remoteIp:$PORT")
                sendFrame("$STATE:${if (localImeActive) "IME_ACTIVE" else "IME_INACTIVE"}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "server error: ${e.message}", e)
            notifyState("服务异常")
        }
    }
}

private fun stopServer() {
    try { serverSocket?.close() } catch (_: Exception) {}
    serverSocket = null
    serverJob?.cancel(); serverJob = null
    Log.i(TAG, "server stopped")
}

@Synchronized
private fun adoptSession(sock: Socket, tag: String) {
    val had = sessionSocket != null
    if (had) Log.w(TAG, "adoptSession($tag): replacing existing session")
    closeSession("adopt:$tag")
    sessionSocket = sock.apply {
        tcpNoDelay = true
        keepAlive = true
    }
    sessionWriter = PrintWriter(OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)
    Log.i(TAG, "adoptSession($tag): session established to ${sock.inetAddress?.hostAddress}:${sock.port}")
    readJob = ioScope.launch {
        try {
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val f = line!!
                val type = when {
                    f.startsWith("$STATE:") -> "STATE"
                    f.startsWith("$TEXT_B64:") -> "TEXT_B64"
                    else -> f
                }
                Log.v(TAG, "recv <$type> len=${f.length}")
                dispatchIncoming(f)
            }
        } catch (e: Exception) {
            Log.e(TAG, "read loop error: ${e.message}", e)
        } finally {
            Log.w(TAG, "read loop finished ($tag)")
            notifyState("$tag 连接断开")
            closeSession("read-end:$tag")
        }
    }
}

@Synchronized
private fun closeSession(reason: String) {
    Log.i(TAG, "closeSession: reason=$reason")
    try { sessionWriter?.close() } catch (_: Exception) {}
    sessionWriter = null
    try { sessionSocket?.close() } catch (_: Exception) {}
    sessionSocket = null
    readJob?.cancel(); readJob = null
}

private fun sendFrame(frame: String) {
    val w = sessionWriter
    val pfx = when {
        frame.startsWith("$STATE:") -> "STATE"
        frame.startsWith("$TEXT_B64:") -> "TEXT_B64"
        else -> frame.take(16)
    }
    if (w == null) {
        Log.w(TAG, "send <$pfx> aborted: no active session")
        return
    }
    w.println(frame)
    if (w.checkError()) {
        Log.e(TAG, "send <$pfx> failed: writer error (peer probably closed)")
        notifyState("发送失败")
        closeSession("writer-error")
    } else {
        Log.v(TAG, "send <$pfx> ok")
    }
}

private fun dispatchIncoming(frame: String) {
    when {
        frame.startsWith("$STATE:") -> {
            val active = frame.endsWith("IME_ACTIVE")
            remoteImeActive = active
            Log.i(TAG, "remote IME state -> $active")
        }
        frame.startsWith("$TEXT_B64:") -> {
            val b64 = frame.removePrefix("$TEXT_B64:")
            val text = try {
                String(Base64.decode(b64, Base64.NO_WRAP), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                Log.e(TAG, "decode TEXT_B64 error: ${e.message}", e); ""
            }
            if (text.isEmpty()) return
            mainScope.launch {
                val routeIme = localImeActive && imeSink != null
                Log.d(TAG, "route TEXT to ${if (routeIme) "IME" else "APP"} (localImeActive=$localImeActive)")
                if (routeIme) imeSink?.onText(text) else appSink?.onText(text)
            }
        }
        frame == BACKSPACE -> mainScope.launch {
            val routeIme = localImeActive && imeSink != null
            Log.d(TAG, "route BACKSPACE to ${if (routeIme) "IME" else "APP"}")
            if (routeIme) imeSink?.onBackspace() else appSink?.onBackspace()
        }
        frame == CLEAR -> mainScope.launch {
            val routeIme = localImeActive && imeSink != null
            Log.d(TAG, "route CLEAR to ${if (routeIme) "IME" else "APP"}")
            if (routeIme) imeSink?.onClear() else appSink?.onClear()
        }
        else -> Log.w(TAG, "unknown frame: ${frame.take(64)}")
    }
}

@Suppress("unused")
private fun isSelfIp(ip: String): Boolean = getLocalIpAddress() == ip
private fun getLocalIpAddress(): String? {
    return try {
        val nets = NetworkInterface.getNetworkInterfaces()
        while (nets.hasMoreElements()) {
            val ni = nets.nextElement()
            if (!ni.isUp || ni.isLoopback) continue
            val addrs = ni.inetAddresses
            while (addrs.hasMoreElements()) {
                val a = addrs.nextElement()
                if (!a.isLoopbackAddress && a is Inet4Address) return a.hostAddress
            }
        }
        null
    } catch (_: Exception) { null }
}
```

}