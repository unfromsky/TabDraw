package com.tabdraw.native_client

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.*
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * UdpTransmitter — 極速非阻塞 UDP 傳送層 + UDP 自動廣播搜尋 (Auto-Discovery)
 * 支援心跳超時檢測 (Heartbeat Timeout)、螢幕解析度動態適配與設備握手
 */
class UdpTransmitter(context: Context? = null) {

    companion object {
        private const val TAG = "UdpTransmitter"
        const val DEFAULT_IP   = "192.168.0.13"
        const val DEFAULT_PORT = 9999
        const val DISCOVERY_PORT = 9998
        private const val PREFS_NAME = "tabdraw_prefs"
        private const val KEY_IP = "last_ip"
        private const val KEY_PORT = "last_port"
        private const val TIMEOUT_MS = 2500L
    }

    private val prefs = context?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile var targetIp: String = prefs?.getString(KEY_IP, DEFAULT_IP) ?: DEFAULT_IP
    @Volatile var targetPort: Int  = prefs?.getInt(KEY_PORT, DEFAULT_PORT) ?: DEFAULT_PORT
    @Volatile var autoDiscovered: Boolean = false

    @Volatile var hostWidth: Int = 1920
    @Volatile var hostHeight: Int = 1080

    @Volatile private var lastBeaconTime: Long = 0L

    val isConnected: Boolean
        get() = (System.currentTimeMillis() - lastBeaconTime) < TIMEOUT_MS

    var onHostResolutionChanged: ((Int, Int) -> Unit)? = null
    var onConnectionStateChanged: ((Boolean, String, Int) -> Unit)? = null

    private val packetQueue = ArrayBlockingQueue<ByteArray>(4)
    private var socket: DatagramSocket? = null
    private var discoverySocket: DatagramSocket? = null

    private var sendJob: Job? = null
    private var discoveryJob: Job? = null
    private var heartbeatJob: Job? = null
    private var watchdogJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    // ── FPS 統計 ────────────────────────────────────────────────
    private val frameCount  = AtomicInteger(0)
    private val lastFpsTime = AtomicLong(System.currentTimeMillis())
    var fps: Int = 0
        private set

    private var cachedAddress: InetAddress? = null
    private var lastResolvedIp: String? = null

    // ── 啟動傳送與自動探索 ───────────────────────────────────────
    fun start() {
        startTransmitter()
        startDiscoveryListener()
        startHeartbeatLoop()
        startConnectionWatchdog()
    }

    private fun startTransmitter() {
        if (sendJob?.isActive == true) return
        try {
            socket = DatagramSocket()
        } catch (e: Exception) {
            Log.e(TAG, "Socket create failed: $e")
            return
        }

        sendJob = scope.launch {
            Log.i(TAG, "UDP transmitter started → $targetIp:$targetPort")
            while (isActive) {
                try {
                    val bytes = packetQueue.take()
                    trySend(bytes)
                    updateFps()
                } catch (e: InterruptedException) {
                    break
                } catch (e: CancellationException) {
                    break
                } catch (e: Exception) {
                    Log.w(TAG, "Transmit loop error: $e")
                }
            }
        }
    }

    /**
     * 自動廣播搜尋：在背景監聽 9998 埠號
     * 收到電腦端廣播 "TABDRAW_HOST:<IP>:<PORT>:<WIDTH>x<HEIGHT>" 後更新心跳與尺寸
     */
    private fun startDiscoveryListener() {
        if (discoveryJob?.isActive == true) return
        discoveryJob = scope.launch {
            try {
                discoverySocket = DatagramSocket(DISCOVERY_PORT).apply {
                    broadcast = true
                    reuseAddress = true
                }
                val buffer = ByteArray(256)
                val packet = DatagramPacket(buffer, buffer.size)

                Log.i(TAG, "Auto-discovery listening on UDP $DISCOVERY_PORT...")
                while (isActive) {
                    discoverySocket?.receive(packet)
                    val msg = String(packet.data, 0, packet.length).trim()
                    if (msg.startsWith("TABDRAW_HOST:")) {
                        lastBeaconTime = System.currentTimeMillis()
                        val parts = msg.split(":")
                        if (parts.size >= 3) {
                            val hostIp = parts[1]
                            val hostPort = parts[2].toIntOrNull() ?: DEFAULT_PORT

                            // 解析電腦端目標螢幕解析度
                            if (parts.size >= 4) {
                                val resParts = parts[3].split("x")
                                if (resParts.size == 2) {
                                    val w = resParts[0].toIntOrNull() ?: 0
                                    val h = resParts[1].toIntOrNull() ?: 0
                                    if (w > 0 && h > 0 && (w != hostWidth || h != hostHeight)) {
                                        hostWidth = w
                                        hostHeight = h
                                        onHostResolutionChanged?.invoke(w, h)
                                    }
                                }
                            }

                            if (hostIp != targetIp || hostPort != targetPort) {
                                autoDiscovered = true
                                updateTarget(hostIp, hostPort)
                                Log.i(TAG, "Auto-discovered Host: $hostIp:$hostPort ($hostWidth x $hostHeight)")
                            } else {
                                autoDiscovered = true
                            }
                            onConnectionStateChanged?.invoke(true, targetIp, targetPort)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Discovery listener closed: $e")
            }
        }
    }

    /**
     * 連線狀態看門狗：若超過 2.5 秒未收到廣播心跳，立刻重置狀態為未連線
     */
    private fun startConnectionWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (isActive) {
                delay(1000)
                if (autoDiscovered && !isConnected) {
                    autoDiscovered = false
                    fps = 0
                    onConnectionStateChanged?.invoke(false, targetIp, targetPort)
                    Log.i(TAG, "Host heartbeat timed out. Marked as disconnected.")
                }
            }
        }
    }

    /**
     * 定時向電腦端發送設備註冊握手封包，讓電腦控制台知道當前是哪台平板連線
     */
    private fun startHeartbeatLoop() {
        if (heartbeatJob?.isActive == true) return
        heartbeatJob = scope.launch {
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            while (isActive) {
                try {
                    val info = "TABDRAW_CLIENT:$deviceModel:$targetPort"
                    val bytes = info.toByteArray(Charsets.UTF_8)
                    trySend(bytes)
                } catch (_: Exception) {}
                delay(2500)
            }
        }
    }

    fun stop() {
        sendJob?.cancel()
        sendJob = null
        socket?.close()
        socket = null

        discoveryJob?.cancel()
        discoveryJob = null
        discoverySocket?.close()
        discoverySocket = null

        heartbeatJob?.cancel()
        heartbeatJob = null

        watchdogJob?.cancel()
        watchdogJob = null

        Log.i(TAG, "UDP transmitter & discovery stopped")
    }

    fun send(packet: StylusPacket) {
        val bytes = packet.toBytes()
        if (!packetQueue.offer(bytes)) {
            packetQueue.poll()
            packetQueue.offer(bytes)
        }
    }

    private fun trySend(bytes: ByteArray) {
        try {
            if (cachedAddress == null || lastResolvedIp != targetIp) {
                cachedAddress = InetAddress.getByName(targetIp)
                lastResolvedIp = targetIp
            }
            val dp = DatagramPacket(bytes, bytes.size, cachedAddress, targetPort)
            socket?.send(dp)
        } catch (e: Exception) {
            Log.w(TAG, "Send failed: $e")
        }
    }

    private fun updateFps() {
        val count = frameCount.incrementAndGet()
        val now   = System.currentTimeMillis()
        val elapsed = now - lastFpsTime.get()
        if (elapsed >= 1000L) {
            fps = (count * 1000L / elapsed).toInt()
            frameCount.set(0)
            lastFpsTime.set(now)
        }
    }

    fun updateTarget(ip: String, port: Int) {
        targetIp   = ip
        targetPort = port
        cachedAddress = null
        prefs?.edit()?.putString(KEY_IP, ip)?.putInt(KEY_PORT, port)?.apply()
        onConnectionStateChanged?.invoke(isConnected, ip, port)
        Log.i(TAG, "Target updated & saved → $ip:$port")
    }
}
