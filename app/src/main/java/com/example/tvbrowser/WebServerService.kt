package com.example.tvbrowser

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.Response
import fi.iki.elonen.NanoHTTPD.Response.Status
import kotlinx.coroutines.*
import java.io.File
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.Socket
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class WebServerService : Service() {

    private lateinit var webServer: WebServer
    private val serverScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        webServer = WebServer(this, 8080)
        serverScope.launch {
            try {
                webServer.start()
                Log.d("WebServer", "Servidor web iniciado en puerto 8080")
            } catch (e: IOException) {
                Log.e("WebServer", "Error al iniciar servidor web", e)
            }
        }
    }

    override fun onDestroy() {
        webServer.stop()
        serverScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    inner class WebServer(private val context: Context, port: Int) : NanoHTTPD(port) {

        private val authTokens = ConcurrentHashMap<String, Long>()
        private val prefs = context.getSharedPreferences("server_prefs", MODE_PRIVATE)

        private var panelPassword: String = prefs.getString("panel_password", "admin") ?: "admin".also {
            prefs.edit().putString("panel_password", "admin").apply()
        }
        private var streamPassword: String = prefs.getString("stream_password", "123456") ?: "123456".also {
            prefs.edit().putString("stream_password", "123456").apply()
        }

        private val activeMjpegClients = ConcurrentHashMap<String, Boolean>()

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method

            Log.d("WebServer", "Petición: $method $uri")

            when {
                uri == "/" || uri == "/index.html" -> return serveAsset("web/index.html", "text/html")
                uri == "/remote" || uri == "/remote.html" -> return serveAsset("web/remote.html", "text/html")
                uri == "/videos" || uri == "/videos.html" -> return serveAsset("web/videos.html", "text/html")
                uri.startsWith("/static/") -> {
                    val assetPath = "web${uri}"
                    val mime = when {
                        uri.endsWith(".css") -> "text/css"
                        uri.endsWith(".js") -> "application/javascript"
                        uri.endsWith(".png") -> "image/png"
                        else -> "application/octet-stream"
                    }
                    return serveAsset(assetPath, mime)
                }
            }

            when {
                uri == "/screen.mjpeg" -> return serveMJPEG(false)
                uri == "/camera.mjpeg" -> return serveMJPEG(true)
                uri == "/api/camera-status" -> {
                    val streaming = CameraService.isStreaming.get()
                    val available = CameraService.isCameraAvailable
                    return newFixedLengthResponse(Status.OK, "application/json", "{\"available\":$available,\"streaming\":$streaming}")
                }
                uri == "/api/videos" -> return handleListVideos()
                uri.startsWith("/api/video/") -> {
                    val filename = uri.substringAfter("/api/video/")
                    return serveVideo(filename)
                }
                uri == "/api/command" && method == Method.POST -> {
                    session.parseBody(HashMap())
                    return handleCommand(session)
                }
            }

            if (method == Method.POST) {
                try { session.parseBody(HashMap()) } catch (e: Exception) { Log.e("WebServer", "Error parse body", e) }
            }

            if (uri == "/api/login" && method == Method.POST) {
                return handleLogin(session)
            }

            if (uri == "/api/change-panel-password" && method == Method.POST) {
                if (!isAuthenticated(session)) return newFixedLengthResponse(Status.UNAUTHORIZED, "text/plain", "No autorizado")
                return handleChangePanelPassword(session)
            }
            if (uri == "/api/change-stream-password" && method == Method.POST) {
                if (!isAuthenticated(session)) return newFixedLengthResponse(Status.UNAUTHORIZED, "text/plain", "No autorizado")
                return handleChangeStreamPassword(session)
            }

            return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Not found")
        }

        private fun serveAsset(path: String, mime: String): Response {
            return try {
                val inputStream = context.assets.open(path)
                val bytes = inputStream.readBytes()
                inputStream.close()
                newFixedLengthResponse(Status.OK, mime, bytes.inputStream(), bytes.size.toLong())
            } catch (e: IOException) {
                newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "Asset not found")
            }
        }

        private fun isAuthenticated(session: IHTTPSession): Boolean {
            val cookieHeader = session.headers["cookie"]
            var token: String? = null
            if (cookieHeader != null) {
                val cookies = cookieHeader.split(";").associate {
                    val parts = it.trim().split("=")
                    parts[0] to (parts.getOrNull(1) ?: "")
                }
                token = cookies["auth_token"]
            }
            if (token == null) return false
            val expiry = authTokens[token] ?: return false
            if (System.currentTimeMillis() > expiry) {
                authTokens.remove(token)
                return false
            }
            return true
        }

        private fun handleLogin(session: IHTTPSession): Response {
            val password = session.parameters["password"]?.firstOrNull()?.trim() ?: ""
            if (password == panelPassword) {
                val token = generateToken()
                authTokens[token] = System.currentTimeMillis() + 3600_000
                val response = newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}")
                response.addHeader("Set-Cookie", "auth_token=$token; Path=/; HttpOnly")
                return response
            } else {
                return newFixedLengthResponse(Status.UNAUTHORIZED, "application/json", "{\"success\":false}")
            }
        }

        private fun handleCommand(session: IHTTPSession): Response {
            val cmd = session.parameters["cmd"]?.firstOrNull() ?: ""
            thread {
                try {
                    val clientIp = prefs.getString("client_ip", "127.0.0.1") ?: "127.0.0.1"
                    Socket(clientIp, 9001).use { socket ->
                        socket.tcpNoDelay = true
                        socket.getOutputStream().write((cmd + "\n").toByteArray())
                        socket.getOutputStream().flush()
                    }
                } catch (e: Exception) {
                    Log.e("WebServer", "Error enviando comando", e)
                }
            }
            return newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}")
        }

        private fun handleChangePanelPassword(session: IHTTPSession): Response {
            val newPassword = session.parameters["new_password"]?.firstOrNull()?.trim() ?: ""
            if (newPassword.isNotBlank()) {
                panelPassword = newPassword
                prefs.edit().putString("panel_password", newPassword).apply()
                return newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}")
            }
            return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", "{\"success\":false}")
        }

        private fun handleChangeStreamPassword(session: IHTTPSession): Response {
            val newPassword = session.parameters["new_password"]?.firstOrNull()?.trim() ?: ""
            if (newPassword.isNotBlank()) {
                streamPassword = newPassword
                prefs.edit().putString("stream_password", newPassword).apply()
                updateStreamCipherKey(newPassword)
                return newFixedLengthResponse(Status.OK, "application/json", "{\"success\":true}")
            }
            return newFixedLengthResponse(Status.BAD_REQUEST, "application/json", "{\"success\":false}")
        }

        private fun serveMJPEG(isCamera: Boolean): Response {
            val boundary = "mjpegboundary"
            val pipedOut = PipedOutputStream()
            val pipedIn = PipedInputStream(pipedOut)

            val clientId = "${System.currentTimeMillis()}-${Thread.currentThread().id}"
            activeMjpegClients[clientId] = true

            thread {
                try {
                    val frameProvider = if (isCamera) CameraService.latestFrameProvider else ScreenCastService.latestFrameProvider
                    while (activeMjpegClients[clientId] == true) {
                        val frame = frameProvider?.getLatestFrame()
                        if (frame != null) {
                            pipedOut.write("--$boundary\r\n".toByteArray())
                            pipedOut.write("Content-Type: image/jpeg\r\n".toByteArray())
                            pipedOut.write("Content-Length: ${frame.size}\r\n".toByteArray())
                            pipedOut.write("\r\n".toByteArray())
                            pipedOut.write(frame)
                            pipedOut.write("\r\n".toByteArray())
                            pipedOut.flush()
                        }
                        Thread.sleep(50)
                    }
                } catch (e: IOException) {
                } finally {
                    activeMjpegClients.remove(clientId)
                    try { pipedOut.close() } catch (e: IOException) {}
                }
            }

            val response = newChunkedResponse(Status.OK, "multipart/x-mixed-replace; boundary=$boundary", pipedIn)
            response.addHeader("Cache-Control", "no-cache")
            response.addHeader("Connection", "close")
            return response
        }

        private fun handleListVideos(): Response {
            val dir = File(context.filesDir, ".screenshare/videos")
            val files = dir.listFiles()?.filter { it.extension == "mp4" }?.sortedByDescending { it.lastModified() }
            val json = StringBuilder("[")
            files?.forEachIndexed { i, file ->
                if (i > 0) json.append(",")
                json.append("{\"name\":\"${file.name}\",\"date\":${file.lastModified()},\"size\":${file.length()}}")
            }
            json.append("]")
            return newFixedLengthResponse(Status.OK, "application/json", json.toString())
        }

        private fun serveVideo(filename: String): Response {
            val dir = File(context.filesDir, ".screenshare/videos")
            val file = File(dir, filename)
            if (!file.exists()) return newFixedLengthResponse(Status.NOT_FOUND, "text/plain", "No encontrado")
            return newChunkedResponse(Status.OK, "video/mp4", file.inputStream())
        }

        private fun generateToken(): String {
            val bytes = ByteArray(32)
            kotlin.random.Random.nextBytes(bytes)
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        }

        private fun updateStreamCipherKey(password: String) {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(password.toByteArray(Charsets.UTF_8))
            val keyBytes = hash.copyOf(16)
            val secretKey = SecretKeySpec(keyBytes, "AES")
            ScreenCastService.updateCipherKey(secretKey)
            CameraService.updateCipherKey(secretKey)
        }
    }
}
