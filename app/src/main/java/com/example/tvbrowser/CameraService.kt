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

        @Volatile var latestFrameProvider: FrameProvider? = null
        @Volatile private var currentBufferFrame: ByteArray? = null
        internal val isStreaming = AtomicBoolean(false)
        @Volatile internal var isCameraAvailable = true

        fun updateCipherKey(key: SecretKeySpec) {}
    }

    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private val netLock = Object()
    private var outStream: DataOutputStream? = null
    private var reconnectThread: Thread? = null
    private var currentFacing = CameraSelector.LENS_FACING_BACK
    private var clientIp = "127.0.0.1"
    private var isForegroundAttached = false
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

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "❌ Permiso de cámara NO otorgado.")
            isCameraAvailable = false
            val clientFacingExtra = intent?.getIntExtra("FACING", 0) ?: 0
            broadcastCameraEvent(ACTION_CAMERA_UNAVAILABLE, if (clientFacingExtra == 1) 0 else 1)
            stopSelf()
            return START_NOT_STICKY
        }

        if (!isForegroundAttached) {
            startForegroundCompat()
        }

        val clientFacingExtra = intent?.getIntExtra("FACING", 0) ?: 0
        currentFacing = if (clientFacingExtra == 1) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

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
            isForegroundAttached = true
        } catch (e: SecurityException) {
            startForeground(NOTIFICATION_ID, notification)
            isForegroundAttached = true
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Canal Cámara", NotificationManager.IMPORTANCE_HIGH)
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

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(this, selector, imageAnalysis)
                isStreaming.set(true)
                isCameraAvailable = true
                val systemFacingId = if (currentFacing == CameraSelector.LENS_FACING_FRONT) 0 else 1
                sendCameraAvailabilityToClient(true, if (currentFacing == CameraSelector.LENS_FACING_FRONT) 1 else 0)
                broadcastCameraEvent(ACTION_CAMERA_AVAILABLE, systemFacingId)
                startReconnectLoop()
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
            val nv21Bytes = yuv420ToNv21Optimized(image)
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

    private fun yuv420ToNv21Optimized(image: ImageProxy): ByteArray {
        val width = image.width
        val height = image.height
        val yPlane = image.planes[0]
        val uPlane = image.planes[1]
        val vPlane = image.planes[2]

        val yBuffer = yPlane.buffer
        val uBuffer = uPlane.buffer
        val vBuffer = vPlane.buffer

        val ySize = width * height
        val nv21 = ByteArray(ySize + (width * height / 2))

        yBuffer.get(nv21, 0, ySize)

        val vRowStride = vPlane.rowStride
        val vPixelStride = vPlane.pixelStride
        var chromaOffset = ySize

        val rowBuffer = ByteArray(vRowStride)
        for (row in 0 until height / 2) {
            vBuffer.position(row * vRowStride)
            val remaining = vBuffer.remaining()
            val bytesToRead = if (vRowStride < remaining) vRowStride else remaining
            vBuffer.get(rowBuffer, 0, bytesToRead)
            
            for (col in 0 until width / 2) {
                val pixelIndex = col * vPixelStride
                nv21[chromaOffset++] = rowBuffer[pixelIndex] // V
                if (pixelIndex < bytesToRead) {
                    nv21[chromaOffset++] = uBuffer.get(row * uPlane.rowStride + col * uPlane.pixelStride) // U
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
        synchronized(netLock) {
            reconnectThread?.interrupt()
            outStream = null
        }
        reconnectThread = thread(start = true) {
            try {
                while (isStreaming.get() && !Thread.currentThread().isInterrupted) {
                    synchronized(netLock) {
                        if (outStream == null) {
                            try {
                                val socket = Socket(clientIp, PORT).apply { tcpNoDelay = true }
                                outStream = DataOutputStream(socket.getOutputStream())
                            } catch (_: Exception) {
                                outStream = null
                            }
                        }
                    }
                    Thread.sleep(3500)
                }
            } catch (_: InterruptedException) {}
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
        sendBroadcast(Intent(action).apply { putExtra(EXTRA_CAMERA_FACING, facingId) })
    }

    override fun onDestroy() {
        isStreaming.set(false)
        isForegroundAttached = false
        reconnectThread?.interrupt()
        cameraProvider?.unbindAll()
        cameraExecutor.shutdown()
        currentBufferFrame = null
        synchronized(netLock) {
            try { outStream?.close() } catch (_: Exception) {}
            outStream = null
        }
        super.onDestroy()
    }
}
