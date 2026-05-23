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
        private const val SETUP_RETRY_DELAY = 5000L

        const val ACTION_CAMERA_AVAILABLE = "com.example.tvbrowser.CAMERA_AVAILABLE"
        const val ACTION_CAMERA_UNAVAILABLE = "com.example.tvbrowser.CAMERA_UNAVAILABLE"
        const val EXTRA_CAMERA_FACING = "camera_facing"

        @Volatile
        var latestFrameProvider: FrameProvider? = null

        // CIFRADO (necesario para WebServerService)
        private var cipher: Cipher? = null
        private var secretKey: SecretKeySpec? = null

        fun updateCipherKey(key: SecretKeySpec) {
            secretKey = key
            try {
                cipher = Cipher.getInstance("AES/CTR/NoPadding")
                val iv = ByteArray(16) { 0 }
                cipher?.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
            } catch (e: Exception) {
                Log.e(TAG, "Error inicializando cifrado", e)
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
    private var sensorOrientation: Int = 90

    private val busyLock = Object()
    private var isCameraBusy = false
    private var pendingFacing: Int? = null

    private lateinit var cameraHandlerThread: HandlerThread
    private lateinit var cameraHandler: Handler

    private var clientIp = "127.0.0.1"
    private var currentCameraId: String? = null

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            if (isCameraWeCareAbout(cameraId)) {
                isCameraAvailable = true
                sendCameraAvailabilityToClient(true)
                sendLocalBroadcast(ACTION_CAMERA_AVAILABLE, cameraFacing)
            }
        }

        override fun onCameraUnavailable(cameraId: String) {
            if (isCameraWeCareAbout(cameraId)) {
                isCameraAvailable = false
                sendCameraAvailabilityToClient(false)
                sendLocalBroadcast(ACTION_CAMERA_UNAVAILABLE, cameraFacing)
                cameraHandler.post { safeCloseCamera() }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
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

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        startForegroundService()

        val requestedFacing = intent?.getIntExtra("FACING", CameraCharacteristics.LENS_FACING_BACK) ?: CameraCharacteristics.LENS_FACING_BACK

        synchronized(busyLock) {
            if (isCameraBusy) {
                pendingFacing = requestedFacing
                return START_STICKY
            }
            cameraFacing = requestedFacing
        }

        cameraHandler.post { setupCamera() }
        return START_STICKY
    }

    private fun startForegroundService() {
        val notification = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cámara Activa")
            .setContentText("Transmitiendo...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
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

    private fun setupCamera() {
        synchronized(busyLock) { isCameraBusy = true }

        try {
            safeCloseCamera() // Cerrar cualquier cámara anterior primero

            val cameraId = cameraManager!!.cameraIdList.find { id ->
                cameraManager!!.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == cameraFacing
            } ?: run {
                onSetupFailed()
                return
            }

            currentCameraId = cameraId
            val characteristics = cameraManager!!.getCameraCharacteristics(cameraId)
            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90

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
            Log.e(TAG, "Error en setupCamera", e)
            onSetupFailed()
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
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                        isStreaming.set(true)
                        startImageAvailableListener()
                        startReconnectLoop()
                        synchronized(busyLock) { isCameraBusy = false }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error al iniciar preview", e)
                        safeCloseCamera()
                    }
                }

                override fun onConfigureFailed(session: CameraCaptureSession) {
                    Log.e(TAG, "Fallo en configuración de sesión")
                    safeCloseCamera()
                }
            }, cameraHandler)
        } catch (e: Exception) {
            Log.e(TAG, "Error creando CaptureSession", e)
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
            } catch (e: Exception) {
                Log.e(TAG, "Error procesando frame", e)
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

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)

            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val out = ByteArrayOutputStream(512 * 1024)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, width, height, null)
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 78, out)
            return out.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error YUV→JPEG", e)
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
                            val socket = Socket(clientIp, PORT).apply {
                                tcpNoDelay = true
                                soTimeout = 10000
                            }
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
        imageReader?.setOnImageAvailableListener(null, null)
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

        synchronized(busyLock) { isCameraBusy = false }
    }

    private fun onSetupFailed() {
        synchronized(busyLock) { isCameraBusy = false }
        // Reintentar si hay cambio pendiente
        val nextFacing = synchronized(busyLock) { pendingFacing?.also { pendingFacing = null } }
        if (nextFacing != null) {
            cameraFacing = nextFacing
            cameraHandler.postDelayed({ setupCamera() }, 800)
        }
    }

    // === MÉTODOS AUXILIARES ===
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

    private fun sendLocalBroadcast(action: String, facing: Int) {
        sendBroadcast(Intent(action).apply { putExtra(EXTRA_CAMERA_FACING, facing) })
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
