package com.example.tvbrowser

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.*
import okio.ByteString
import java.util.concurrent.TimeUnit

class RelayService : Service() {

    companion object {
        private const val TAG = "RelayService"
        private const val RELAY_URL = "wss://webservice-1-v8s5.onrender.com"
        private const val NOTIFICATION_CHANNEL_ID = "relay_ch"
        private const val NOTIFICATION_ID = 3

        @Volatile
        private var instance: RelayService? = null

        fun getInstance(): RelayService? = instance
    }

    private var webSocket: WebSocket? = null
    private var roomId: String? = null
    private lateinit var wakeLock: PowerManager.WakeLock

    override fun onCreate() {
        super.onCreate()
        instance = this
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RelayService::WakeLock")
        wakeLock.acquire(10 * 60 * 1000L)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        roomId = intent?.getStringExtra("ROOM_ID") ?: return START_NOT_STICKY
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())
        connectToRelay()
        return START_STICKY
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Relay",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Conexión remota activa")
            .setContentText("Sala: $roomId")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .build()
    }

    private fun connectToRelay() {
        val client = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .build()

        val request = Request.Builder()
            .url("$RELAY_URL/?roomId=$roomId&role=server")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Conectado al relay. Sala: $roomId")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Comando recibido: $text")
                try {
                    java.net.Socket("127.0.0.1", 9001).use { socket ->
                        socket.tcpNoDelay = true
                        socket.getOutputStream().write((text + "\n").toByteArray())
                        socket.getOutputStream().flush()
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error enviando comando local", e)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e(TAG, "Fallo en WebSocket: ${t.message}")
                Thread.sleep(5000)
                connectToRelay()
            }
        })
    }

    fun sendFrame(type: String, bytes: ByteArray) {
        val header = "$type:\n".toByteArray()
        val packet = header + bytes
        if (webSocket?.send(ByteString.of(*packet)) == false) {
            Log.w(TAG, "No se pudo enviar frame")
        }
    }

    override fun onDestroy() {
        instance = null
        webSocket?.close(1000, "Servicio detenido")
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
