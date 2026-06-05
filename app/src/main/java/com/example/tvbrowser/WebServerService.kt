package com.example.tvbrowser

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.*
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.ConcurrentHashMap

class WebServerService : Service() {

    companion object {
        private const val TAG = "WebServerService"
        private const val PORT = 8080
        private const val NOTIFICATION_CHANNEL_ID = "web_server_ch"
        private const val NOTIFICATION_ID = 3
    }

    private var server: LocalHttpServer? = null
    private val serverScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val activeMjpegClients = ConcurrentHashMap<String, Boolean>()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        if (server == null) {
            try {
                server = LocalHttpServer(PORT)
                server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
                Log.d(TAG, "🌐 Servidor HTTP Local iniciado en el puerto $PORT")
            } catch (e: IOException) {
                Log.e(TAG, "❌ Error al iniciar el servidor HTTP", e)
            }
        }
        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Servidor Web Activo")
            .setContentText("Transmitiendo paneles de control locales...")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
        startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Servidor Web", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        serverScope.cancel()
        activeMjpegClients.clear()
        try {
            server?.stop()
            Log.d(TAG, "🌐 Servidor HTTP Local detenido de manera limpia.")
        } catch (_: Exception) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private inner class LocalHttpServer(port: Int) : NanoHTTPD(port) {
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return when {
                uri == "/camera" -> serveMJPEG(isCamera = true)
                uri == "/screencast" -> serveMJPEG(isCamera = false)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, NanoHTTPD.MIME_PLAINTEXT, "404 Not Found")
            }
        }

        private fun serveMJPEG(isCamera: Boolean): Response {
            val boundary = "mjpegboundary"
            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut)
            val clientId = "${System.currentTimeMillis()}-${Thread.currentThread().id}"
            activeMjpegClients[clientId] = true

            serverScope.launch(Dispatchers.IO) {
                try {
                    val frameProvider = if (isCamera) CameraService.latestFrameProvider else ScreenCastService.latestFrameProvider
                    while (activeMjpegClients[clientId] == true && coroutineContext.isActive) {
                        val frame = frameProvider?.getLatestFrame()
                        if (frame != null) {
                            try {
                                pipedOut.write("--$boundary\r\n".toByteArray())
                                pipedOut.write("Content-Type: image/jpeg\r\n".toByteArray())
                                pipedOut.write("Content-Length: ${frame.size}\r\n".toByteArray())
                                pipedOut.write("\r\n".toByteArray())
                                pipedOut.write(frame)
                                pipedOut.write("\r\n".toByteArray())
                                pipedOut.flush()
                            } catch (e: IOException) {
                                // Se genera cuando el navegador o reproductor remoto cierra el stream
                                break
                            }
                        }
                        delay(50) // Controla los cuadros por segundo del stream MJPEG (aprox 20 FPS)
                    }
                } catch (_: Exception) {
                } finally {
                    activeMjpegClients.remove(clientId)
                    try { pipedOut.close() } catch (_: IOException) {}
                }
            }

            val response = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=$boundary", pipedIn)
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "close")
            return response
        }
    }
}
