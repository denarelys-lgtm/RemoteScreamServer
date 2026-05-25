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
import android.view.Surface
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

        @Volatile
        private var currentBufferFrame: ByteArray? = null

        internal val isStreaming = AtomicBoolean(false)
        @Volatile
        internal var isCameraAvailable = true

        fun updateCipherKey(key: SecretKeySpec) {
            // Mantenido por compatibilidad estructural
        }
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
            override fun getLatestFrame(): ByteArray? = currentBufferFrame
        }
    }

    override fun onBind(intent: Intent): IBinder? {
        super.onBind(intent)
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ Abortando inicio de FGS: Permiso de cámara NO otorgado.")
            isCameraAvailable = false
            val clientFacingExtra = intent?.getIntExtra("FACING", 0) ?: 0
            broadcastCameraEvent(ACTION_CAMERA_UNAVAILABLE, if (clientFacingExtra == 1) 0 else 1)
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        val clientFacingExtra = intent?.getIntExtra("FACING", 0) ?: 0
        currentFacing = if (clientFacingExtra == 1) {
            CameraSelector.LENS_FACING_FRONT
        } else {
            CameraSelector.LENS_FACING_BACK
        }

        Handler(Looper.getMainLooper()).post { startCameraX() }

        return START_STICKY
    }

    private fun startForegroundCompat() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Streaming de Video")
            .setContentText("Transmitiendo cámara en vivo...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "⚠️ Fallo al asignar tipo de servicio. Forzando modo estándar.", e)
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID, 
                "Canal Cámara", 
                NotificationManager.IMPORTANCE_HIGH
            )
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
                    .setTargetRotation(Surface.ROTATION_0)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis?.setAnalyzer(cameraExecutor) { image ->
                    processImage(image)
                    image.close()
                }

                // Limpiar cualquier enlace anterior antes de cambiar de cámara
                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, selector, imageAnalysis)

                isStreaming.set(true)
                isCameraAvailable = true
                
                val systemFacingId = if (currentFacing == CameraSelector.LENS_FACING_FRONT) 0 else 1
                
                sendCameraAvailabilityToClient(true, if (currentFacing == CameraSelector.LENS_FACING_FRONT) 1 else 0)
                broadcastCameraEvent(ACTION_CAMERA_AVAILABLE, systemFacingId)
                
                // Reiniciar el bucle de conexión de forma segura
                startReconnectLoop()

                Log.i(TAG, "✅ CameraX reconfigurado con éxito.")

            } catch (e: Exception) {
                Log.e(TAG, "❌ Error al enlazar CameraX", e)
                isCameraAvailable = false
                val systemFacingId = if (currentFacing == CameraSelector.LENS_FACING_FRONT) 0 else 1
                
                sendCameraAvailabilityToClient(false, if (currentFacing == CameraSelector.LENS_FACING_FRONT) 1 else 0)
                broadcastCameraEvent(ACTION_CAMERA_UNAVAILABLE, systemFacingId)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun processImage(image: ImageProxy) {
        try {
            val nv21Bytes = yuv420ToNv21(image)
            val out = ByteArrayOutputStream()
            val yuvImage = YuvImage(nv21Bytes, ImageFormat.NV21, image.width, image.height, null)
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 75, out)
            
            val jpegBytes = out.toByteArray()
            currentBufferFrame = jpegBytes 
            
            sendFrame(jpegBytes)
        } catch (e: Exception) {
            Log.e(TAG, "Error en procesamiento de cuadro", e)
        }
    }

    private fun yuv420ToNv21(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val nv21 = ByteArray(width * height * 3 / 2)
        val yRowStride = yPlane.rowStride
        var nv21YOffset = 0
        
        if (yRowStride == width) {
            yBuffer.get(nv21, 0, width * height)
        } else {
            for (row in 0 until height) {
                yBuffer.position(row * yRowStride)
                yBuffer.get(nv21, nv21YOffset, width)
                nv21YOffset += width
            }
        }

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        var chromaOffset = width * height
        val chromaHeight = height / 2
        val chromaWidth = width / 2
        val vRow = ByteArray(vRowStride)

        for (row in 0 until chromaHeight) {
            vBuffer.position(row * vRowStride)
            val bytesToRead = Math.min(vRowStride, vBuffer.remaining())
            vBuffer.get(vRow, 0, bytesToRead)

            for (col in 0 until chromaWidth) {
                val pixelIndex = col * vPixelStride
                nv21[chromaOffset++] = vRow[pixelIndex]
                
                if (pixelIndex < bytesToRead) {
                    try {
                        nv21[chromaOffset++] = uBuffer.get(row * uPlane.rowStride + col * uPlane.pixelStride)
                    } catch (_: Exception) {
                        nv21[chromaOffset++] = 128.toByte()
                    }
                } else {
                    nv21[chromaOffset++] = 128.toByte()
                }
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
        // Interrumpir el hilo anterior de forma controlada
        reconnectThread?.interrupt()
        
        reconnectThread = thread(start = true) {
            try {
                while (isStreaming.get() && !Thread.currentThread().isInterrupted) {
                    synchronized(netLock) {
                        if (outStream == null) {
                            try {
                                val socket = Socket(clientIp, PORT).apply { tcpNoDelay = true }
                                outStream = DataOutputStream(socket.getOutputStream())
                            } catch (_: Exception) {}
                        }
                    }
                    // 🔥 CORREGIDO: Dormir el hilo capturando el InterruptedException de forma segura
                    Thread.sleep(3500)
                }
            } catch (e: InterruptedException) {
                Log.i(TAG, "🔄 Reconnect loop interrumpido correctamente para cambio de cámara.")
                // Salida limpia del hilo sin crasear la app
            }
        }
    }

    private fun sendCameraAvailabilityToClient(available: Boolean, facingId: Int) {
        thread {
            try {
                Socket(clientIp, 9003).use { socket ->
                    val cmd = if (available) "CAMERA_AVAILABLE:$facingId" else "CAMERA_UNAVAILABLE:$facingId"
                    socket.getOutputStream().write((cmd + "\n").toByteArray())
                }
            } catch (_: Exception) {}
        }
    }

    private fun broadcastCameraEvent(action: String, facingId: Int) {
        val intent = Intent(action).apply {
            putExtra(EXTRA_CAMERA_FACING, facingId)
        }
        sendBroadcast(intent)
    }

    override fun onDestroy() {
        isStreaming.set(false)
        reconnectThread?.interrupt()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        currentBufferFrame = null
        synchronized(netLock) {
            outStream?.close()
            outStream = null
        }
        super.onDestroy()
    }
}
