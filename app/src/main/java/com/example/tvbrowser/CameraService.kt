package com.example.tvbrowser

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.Camera
import android.os.*
import android.util.Log
import androidx.core.app.NotificationCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.thread

class CameraService : Service() {

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

        fun updateCipherKey(key: SecretKeySpec) {
            // Mantener compatibilidad con WebServerService
        }

        internal val isStreaming = AtomicBoolean(false)
        @Volatile
        internal var isCameraAvailable = true
    }

    private var camera: Camera? = null
    private var currentFacing = Camera.CameraInfo.CAMERA_FACING_BACK

    private val netLock = Object()
    private var outStream: DataOutputStream? = null
    private var reconnectThread: Thread? = null

    private var clientIp = "127.0.0.1"

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val prefs = getSharedPreferences("server_prefs", MODE_PRIVATE)
        clientIp = prefs.getString("client_ip", "127.0.0.1") ?: "127.0.0.1"

        latestFrameProvider = object : FrameProvider {
            override fun getLatestFrame(): ByteArray? = null
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundSafely()

        val requestedFacing = intent?.getIntExtra("FACING", Camera.CameraInfo.CAMERA_FACING_BACK) ?: Camera.CameraInfo.CAMERA_FACING_BACK
        currentFacing = requestedFacing

        Handler(Looper.getMainLooper()).post { startCameraPreview() }

        return START_STICKY
    }

    private fun startForegroundSafely() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cámara Activa")
            .setContentText("Transmitiendo al cliente")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        try {
            startForeground(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            Log.e(TAG, "Error foreground", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Cámara", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun startCameraPreview() {
        try {
            releaseCamera()

            val cameraId = getCameraId(currentFacing)
            camera = Camera.open(cameraId)

            val params = camera?.parameters
            params?.setPreviewSize(640, 480)
            params?.previewFormat = ImageFormat.NV21
            camera?.parameters = params

            camera?.setPreviewCallback { data, cam ->
                if (data != null) processFrame(data, cam)
            }

            // Surface ficticia (necesaria para que funcione)
            val dummySurface = SurfaceView(this)
            camera?.setPreviewDisplay(dummySurface.holder)
            camera?.startPreview()

            isStreaming.set(true)
            sendCameraAvailabilityToClient(true)
            startReconnectLoop()

            Log.i(TAG, "Cámara ${if (currentFacing == Camera.CameraInfo.CAMERA_FACING_BACK) "trasera" else "frontal"} iniciada")

        } catch (e: Exception) {
            Log.e(TAG, "Error iniciando cámara", e)
            sendCameraAvailabilityToClient(false)
        }
    }

    private fun getCameraId(facing: Int): Int {
        val info = Camera.CameraInfo()
        for (i in 0 until Camera.getNumberOfCameras()) {
            Camera.getCameraInfo(i, info)
            if (info.facing == facing) return i
        }
        return 0
    }

    private fun processFrame(data: ByteArray, cam: Camera) {
        try {
            val params = cam.parameters
            val width = params.previewSize.width
            val height = params.previewSize.height

            val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 75, out)

            sendFrame(out.toByteArray())
        } catch (e: Exception) {}
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

    private fun releaseCamera() {
        isStreaming.set(false)
        try {
            camera?.setPreviewCallback(null)
            camera?.stopPreview()
            camera?.release()
        } catch (_: Exception) {}
        camera = null
    }

    override fun onDestroy() {
        releaseCamera()
        reconnectThread?.interrupt()
        synchronized(netLock) {
            outStream?.close()
            outStream = null
        }
        super.onDestroy()
    }
}
