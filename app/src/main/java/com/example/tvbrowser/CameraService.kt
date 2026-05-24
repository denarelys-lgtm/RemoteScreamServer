package com.example.tvbrowser

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.os.*
import android.util.Log
import android.util.Size
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class CameraService : LifecycleService() {

    companion object {
        private const val TAG = "CameraService"
        private const val PORT = 9002
        private const val NOTIFICATION_CHANNEL_ID = "cam_ch"
        private const val NOTIFICATION_ID = 1

        const val ACTION_CAMERA_AVAILABLE = "com.example.tvbrowser.CAMERA_AVAILABLE"
        const val ACTION_CAMERA_UNAVAILABLE = "com.example.tvbrowser.CAMERA_UNAVAILABLE"
        const val EXTRA_CAMERA_FACING = "camera_facing"

        @Volatile
        var latestFrameProvider: FrameProvider? = null

        internal val isStreaming = AtomicBoolean(false)
        @Volatile
        internal var isCameraAvailable = true

        fun updateCipherKey(key: SecretKeySpec) {}
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null

    private val netLock = Object()
    private var outStream: DataOutputStream? = null
    private var reconnectThread: Thread? = null

    private var currentFacing = CameraSelector.LENS_FACING_BACK
    private var clientIp = "127.0.0.1"

    private lateinit var cameraExecutor: ExecutorService

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        cameraExecutor = Executors.newSingleThreadExecutor()

        val prefs = getSharedPreferences("server_prefs", MODE_PRIVATE)
        clientIp = prefs.getString("client_ip", "127.0.0.1") ?: "127.0.0.1"

        latestFrameProvider = object : FrameProvider {
            override fun getLatestFrame(): ByteArray? = null
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceSimple()

        val facing = intent?.getIntExtra("FACING", CameraSelector.LENS_FACING_BACK) ?: CameraSelector.LENS_FACING_BACK
        currentFacing = facing

        Handler(Looper.getMainLooper()).post { startCameraX() }

        return START_STICKY
    }

    private fun startForegroundServiceSimple() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cámara Activa")
            .setContentText("Transmitiendo...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Cámara", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun startCameraX() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val selector = CameraSelector.Builder().requireLensFacing(currentFacing).build()

                imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis?.setAnalyzer(cameraExecutor) { image ->
                    processImage(image)
                    image.close()
                }

                cameraProvider?.unbindAll()
                
                // Usamos 'this' de forma segura ya que extendemos de LifecycleService
                cameraProvider?.bindToLifecycle(
                    this,
                    selector,
                    imageAnalysis
                )

                isStreaming.set(true)
                isCameraAvailable = true
                sendCameraAvailabilityToClient(true)
                startReconnectLoop()

                Log.i(TAG, "✅ CameraX iniciada correctamente!")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al iniciar CameraX", e)
                isCameraAvailable = false
                sendCameraAvailabilityToClient(false)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(image: ImageProxy) {
        try {
            // 💡 SOLUCIÓN A LAS RAYAS VERDES: Conversión correcta entrelazando planos YUV
            val nv21Bytes = yuv420ToNv21(image)

            val out = ByteArrayOutputStream()
            val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, image.width, image.height, null)
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 75, out)

            sendFrame(out.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Error procesando el frame de la cámara", e)
        }
    }

    // 💡 Método auxiliar para procesar y reconstruir los bytes de color reales
    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yBuffer = yPlane.buffer
        val uBuffer = uBuffer = uPlane.buffer
        val vBuffer = vBuffer = vPlane.buffer

        val ySize = yBuffer.remaining()
        
        // Estructura NV21: El tamaño total es Y (width * height) + submuestreo UV (width * height / 2)
        val nv21 = ByteArray(ySize + (width * height / 2))

        // 1. Copiar canal de brillo (Luminancia - Y)
        yBuffer.get(nv21, 0, ySize)

        // 2. Mapear y mezclar los canales de color (Croma - U y V)
        val vRowStride = vPlane.rowStride
        val uRowStride = uPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        val uPixelStride = uPlane.pixelStride

        var chromaOffset = ySize
        val chromaWidth = width / 2
        val chromaHeight = height / 2

        val vRow = ByteArray(vRowStride)
        val uRow = ByteArray(uRowStride)

        for (row in 0 until chromaHeight) {
            vBuffer.position(row * vRowStride)
            vBuffer.get(vRow, 0, Math.min(vRowStride, vBuffer.remaining()))

            uBuffer.position(row * uRowStride)
            uBuffer.get(uRow, 0, Math.min(uRowStride, uBuffer.remaining()))

            for (col in 0 until chromaWidth) {
                nv21[chromaOffset++] = vRow[col * vPixelStride]
                nv21[chromaOffset++] = uRow[col * uPixelStride]
            }
        }

        return nv21
    }

    private fun sendFrame(bytes: ByteArray) {
        synchronized(netLock) {
            try {
                outStream?.let {
                    it.writeInt(bytes.size)
                    it.write(bytes)
                    it.flush()
                }
            } catch (e: Exception) {
                outStream = null
            }
        }
    }

    private fun startReconnectLoop() {
        reconnectThread?.interrupt()
        reconnectThread = thread(start = true) {
            while (isStreaming.get()) {
                synchronized(netLock) {
                    if (outStream == null) {
                        try {
                            val socket = Socket(clientIp, PORT).apply { tcpNoDelay = true }
                            outStream = DataOutputStream(socket.getOutputStream())
                        } catch (_: Exception) {}
                    }
                }
                Thread.sleep(3500)
            }
        }
    }

    private fun sendCameraAvailabilityToClient(available: Boolean) {
        thread {
            try {
                Socket(clientIp, 9003).use { socket ->
                    val cmd = if (available) "CAMERA_AVAILABLE:$currentFacing" else "CAMERA_UNAVAILABLE:$currentFacing"
                    socket.getOutputStream().write((cmd + "\n").toByteArray())
                }
            } catch (_: Exception) {}
        }
    }

    override fun onDestroy() {
        isStreaming.set(false)
        reconnectThread?.interrupt()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        synchronized(netLock) {
            outStream?.close()
            outStream = null
        }
        super.onDestroy()
    }
}
