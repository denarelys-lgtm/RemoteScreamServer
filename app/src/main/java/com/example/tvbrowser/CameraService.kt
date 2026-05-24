package com.example.tvbrowser

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.hardware.camera2.*
import android.media.ImageReader
import android.os.*
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
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

        private var cipher: Cipher? = null
        private var secretKey: SecretKeySpec? = null

        fun updateCipherKey(key: SecretKeySpec) {
            secretKey = key
            try {
                cipher = Cipher.getInstance("AES/CTR/NoPadding")
                val iv = ByteArray(16) { 0 }
                cipher?.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
            } catch (e: Exception) {
                Log.e(TAG, "Error cifrado", e)
            }
        }

        internal val isStreaming = AtomicBoolean(false)
        @Volatile
        internal var isCameraAvailable = true
    }

    private val binder = LocalBinder()
    private var cameraDevice: CameraDevice? = null
    private var captureSession: CameraCaptureSession? = null
    private var imageReader: ImageReader? = null
    private var cameraManager: CameraManager? = null

    private val netLock = Object()
    private var outStream: DataOutputStream? = null
    private var reconnectThread: Thread? = null

    private var cameraFacing = CameraCharacteristics.LENS_FACING_BACK

    private lateinit var cameraHandlerThread: HandlerThread
    private lateinit var cameraHandler: Handler

    private var clientIp = "127.0.0.1"

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()   // ← Muy importante

        cameraHandlerThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraHandlerThread.looper)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager?.registerAvailabilityCallback(availabilityCallback, cameraHandler)

        val prefs = getSharedPreferences("server_prefs", MODE_PRIVATE)
        clientIp = prefs.getString("client_ip", "127.0.0.1") ?: "127.0.0.1"

        latestFrameProvider = object : FrameProvider {
            override fun getLatestFrame(): ByteArray? = null
        }
    }

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (isCameraWeCareAbout(cameraId)) {
                isCameraAvailable = true
                sendCameraAvailabilityToClient(true)
            }
        }

        override fun onCameraUnavailable(cameraId: String) {
            if (isCameraWeCareAbout(cameraId)) {
                isCameraAvailable = false
                sendCameraAvailabilityToClient(false)
                cameraHandler.post { safeCloseCamera() }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundServiceSafely()

        val requestedFacing = intent?.getIntExtra("FACING", CameraCharacteristics.LENS_FACING_BACK) ?: CameraCharacteristics.LENS_FACING_BACK
        cameraFacing = requestedFacing

        cameraHandler.post { setupCamera() }

        return START_STICKY
    }

    private fun startForegroundServiceSafely() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cámara Activa")
            .setContentText("Transmitiendo al cliente...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al iniciar foreground service", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "Cámara",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Canal para servicio de cámara"
                setShowBadge(false)
            }
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
    }

    private fun setupCamera() {
        safeCloseCamera()

        try {
            val cameraId = cameraManager!!.cameraIdList.find { id ->
                cameraManager!!.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == cameraFacing
            } ?: return

            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 5)

            cameraManager!!.openCamera(cameraId, object : CameraDevice.StateCallback() {
                override fun onOpened(camera: CameraDevice) {
                    cameraDevice = camera
                    startCaptureSession()
                }
                override fun onDisconnected(camera: CameraDevice) { safeCloseCamera() }
                override fun onError(camera: CameraDevice, error: Int) { safeCloseCamera() }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo cámara", e)
        }
    }

    private fun startCaptureSession() {
        val device = cameraDevice ?: return
        try {
            val surfaces = listOfNotNull(imageReader?.surface)

            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
            surfaces.forEach { requestBuilder.addTarget(it) }

            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                    isStreaming.set(true)
                    startImageAvailableListener()
                    startReconnectLoop()
                }
                override fun onConfigureFailed(session: CameraCaptureSession) {
                    safeCloseCamera()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            safeCloseCamera()
        }
    }

    private fun startImageAvailableListener() {
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isStreaming.get()) return@setOnImageAvailableListener
            val image = reader.acquireLatestImage() ?: return@setOnImageAvailableListener

            try {
                val jpegBytes = yuvImageToJpeg(image)
                if (jpegBytes != null) sendFrame(jpegBytes)
            } finally {
                image.close()
            }
        }, cameraHandler)
    }

    private fun yuvImageToJpeg(image: android.media.Image): ByteArray? {
        try {
            val width = image.width
            val height = image.height
            val planes = image.planes

            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val nv21 = ByteArray(yBuffer.remaining() + vBuffer.remaining() + uBuffer.remaining())

            yBuffer.get(nv21, 0, yBuffer.remaining())
            vBuffer.get(nv21, yBuffer.remaining(), vBuffer.remaining())
            uBuffer.get(nv21, yBuffer.remaining() + vBuffer.remaining(), uBuffer.remaining())

            val out = ByteArrayOutputStream()
            YuvImage(nv21, ImageFormat.NV21, width, height, null)
                .compressToJpeg(Rect(0, 0, width, height), 78, out)
            return out.toByteArray()
        } catch (e: Exception) {
            return null
        }
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
                Thread.sleep(4000)
            }
        }
    }

    private fun safeCloseCamera() {
        isStreaming.set(false)
        reconnectThread?.interrupt()

        try { captureSession?.close() } catch (_: Exception) {}
        try { cameraDevice?.close() } catch (_: Exception) {}
        try { imageReader?.close() } catch (_: Exception) {}

        synchronized(netLock) {
            outStream?.close()
            outStream = null
        }

        captureSession = null
        cameraDevice = null
        imageReader = null
    }

    private fun sendCameraAvailabilityToClient(available: Boolean) {
        thread {
            try {
                Socket(clientIp, 9003).use { socket ->
                    val cmd = if (available) "CAMERA_AVAILABLE:$cameraFacing" else "CAMERA_UNAVAILABLE:$cameraFacing"
                    socket.getOutputStream().write((cmd + "\n").toByteArray())
                }
            } catch (_: Exception) {}
        }
    }

    private fun isCameraWeCareAbout(cameraId: String): Boolean {
        return try {
            cameraManager?.getCameraCharacteristics(cameraId)?.get(CameraCharacteristics.LENS_FACING) == cameraFacing
        } catch (e: Exception) { false }
    }

    override fun onDestroy() {
        safeCloseCamera()
        cameraHandlerThread.quitSafely()
        super.onDestroy()
    }
}
