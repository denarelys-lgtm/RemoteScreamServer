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
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream

class WebServerService : Service() {

    companion object {
        private const val TAG = "WebServerService"
        private const val PORT = 8080
        private const val NOTIFICATION_ID = 3003
        private const val CHANNEL_ID = "web_server_channel"
    }

    private var server: AndroidWebServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceCompat()
        try {
            server = AndroidWebServer(PORT)
            server?.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "Servidor HTTP local iniciado en el puerto $PORT")
        } catch (e: IOException) {
            Log.e(TAG, "No se pudo iniciar el servidor web local", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    private fun startForegroundServiceCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Servidor Web de Soporte",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }

        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Servidor de transmisión local activo")
            .setContentText("Procesando peticiones HTTP en el puerto $PORT")
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        server?.stop()
        Log.d(TAG, "Servidor HTTP local detenido")
    }

    private inner class AndroidWebServer(port: Int) : NanoHTTPD(port) {
        
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            return when (uri) {
                "/camera" -> {
                    val frameBytes = CameraService.latestFrameProvider
                    if (frameBytes != null) {
                        generateMjpegResponse(frameBytes)
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Cámara no inicializada o sin frames")
                    }
                }
                "/screencast" -> {
                    val frameBytes = ScreenCastService.latestFrameProvider
                    if (frameBytes != null) {
                        generateMjpegResponse(frameBytes)
                    } else {
                        newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Pantalla no compartida o sin frames")
                    }
                }
                else -> newFixedLengthResponse(Response.Status.OK, MIME_HTML, "<h1>Servidor Remoto Activo</h1><p>Rutas: /camera o /screencast</p>")
            }
        }

        private fun generateMjpegResponse(frameBytes: ByteArray): Response {
            val pipedInputStream = PipedInputStream()
            val pipedOutputStream = PipedOutputStream()
            
            try {
                pipedInputStream.connect(pipedOutputStream)
            } catch (e: IOException) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "Error de buffer interno")
            }

            // Hilo secundario para inyectar de manera segura el flujo continuo MJPEG
            Thread {
                try {
                    val boundary = "---jpg_boundary---"
                    // Corrección estricta de la firma write() pasando un ByteArray explícito
                    val header = ("HTTP/1.0 200 OK\r\n" +
                            "Server: RemoteScream\r\n" +
                            "Connection: close\r\n" +
                            "Content-Type: multipart/x-mixed-replace;boundary=$boundary\r\n\r\n").toByteArray()
                    
                    pipedOutputStream.write(header)
                    pipedOutputStream.flush()

                    // Enviamos el frame actual disponible
                    val frameHeader = ("--$boundary\r\n" +
                            "Content-Type: image/jpeg\r\n" +
                            "Content-Length: ${frameBytes.size}\r\n\r\n").toByteArray()
                    
                    pipedOutputStream.write(frameHeader)
                    pipedOutputStream.write(frameBytes) // Envío del buffer directo
                    pipedOutputStream.write("\r\n".toByteArray())
                    pipedOutputStream.flush()
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Conexión de stream cerrada por el cliente HTTP")
                } finally {
                    try { pipedOutputStream.close() } catch (_: Exception) {}
                }
            }.start()

            val response = newChunkedResponse(Response.Status.OK, "multipart/x-mixed-replace; boundary=---jpg_boundary---", pipedInputStream)
            response.addHeader("Cache-Control", "no-store, no-cache, must-revalidate, pre-check=0, post-check=0, max-age=0")
            response.addHeader("Pragma", "no-cache")
            response.addHeader("Connection", "keep-alive")
            return response
        }
    }
}
