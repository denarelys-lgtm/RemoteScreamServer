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
import android.media.MediaRecorder
import android.os.*
import android.util.Log
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
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
        private const val SETUP_RETRY_DELAY = 5_000L

        const val ACTION_CAMERA_AVAILABLE = "com.example.tvbrowser.CAMERA_AVAILABLE"
        const val ACTION_CAMERA_UNAVAILABLE = "com.example.tvbrowser.CAMERA_UNAVAILABLE"
        const val EXTRA_CAMERA_FACING = "camera_facing"

        @Volatile
        var latestFrameProvider: FrameProvider? = null

        private var cipher: Cipher? = null
        private var secretKey: SecretKeySpec? = null

        fun updateCipherKey(key: SecretKeySpec) {
            secretKey = key
            cipher = Cipher.getInstance("AES/CTR/NoPadding")
            val iv = ByteArray(16) { 0 }
            cipher?.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))
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
    private var mediaRecorder: MediaRecorder? = null
    private var recordingSurface: Surface? = null

    // Sincronización robusta para red
    private val netLock = Object()
    private var outStream: DataOutputStream? = null
    private var reconnectThread: Thread? = null

    private var cameraFacing = CameraCharacteristics.LENS_FACING_BACK
    private var sensorOrientation: Int = 0

    private val busyLock = Object()
    private var isCameraBusy = false
    private var pendingFacing: Int? = null

    private lateinit var cameraHandlerThread: HandlerThread
    private lateinit var cameraHandler: Handler
    private lateinit var wakeLock: PowerManager.WakeLock

    private var wasStreamingBeforeUnavailable = false
    private var currentCameraId: String? = null

    private var clientIp = "127.0.0.1"
    private var latestFrameBytes: ByteArray? = null

    // --- Vigilancia ---
    private val motionDetector = MotionDetector()
    private var motionDetectionEnabled = true
    private val recordingsDir: File by lazy {
        val dir = File(filesDir, ".screenshare/videos")
        if (!dir.exists()) dir.mkdirs()
        dir
    }
    private var lastMotionTime = 0L
    private val motionTimeoutMs = 5_000L
    private var isRecording = false
    private var currentRecordingFile: File? = null

    private var setupRetryRunnable: Runnable? = null

    private val availabilityCallback = object : CameraManager.AvailabilityCallback() {
        override fun onCameraAvailable(cameraId: String) {
            super.onCameraAvailable(cameraId)
            if (isCameraWeCareAbout(cameraId)) {
                isCameraAvailable = true
                sendCameraAvailabilityToClient(true)
                sendLocalBroadcast(ACTION_CAMERA_AVAILABLE, cameraFacing)
                if (wasStreamingBeforeUnavailable) {
                    wasStreamingBeforeUnavailable = false
                    cameraHandler.post { setupCamera() }
                }
            }
        }

        override fun onCameraUnavailable(cameraId: String) {
            super.onCameraUnavailable(cameraId)
            if (isCameraWeCareAbout(cameraId)) {
                isCameraAvailable = false
                wasStreamingBeforeUnavailable = isStreaming.get()
                sendCameraAvailabilityToClient(false)
                sendLocalBroadcast(ACTION_CAMERA_UNAVAILABLE, cameraFacing)
                cameraHandler.post { closeCurrentCamera() }
            }
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): CameraService = this@CameraService
    }

    override fun onCreate() {
        super.onCreate()
        cameraHandlerThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraHandlerThread.looper)

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "CameraService::WakeLock")
        wakeLock.acquire(10 * 60 * 1000L)

        cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cameraManager?.registerAvailabilityCallback(availabilityCallback, cameraHandler)

        val prefs = getSharedPreferences("server_prefs", MODE_PRIVATE)
        clientIp = prefs.getString("client_ip", "127.0.0.1") ?: "127.0.0.1"

        latestFrameProvider = object : FrameProvider {
            override fun getLatestFrame(): ByteArray? = latestFrameBytes
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            stopSelf()
            return START_NOT_STICKY
        }

        createNotificationChannel()
        val notification = createNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "No se pudo iniciar foreground con tipo CAMERA", e)
            stopSelf()
            return START_NOT_STICKY
        }

        val requestedFacing = intent?.getIntExtra("FACING", CameraCharacteristics.LENS_FACING_BACK) ?: CameraCharacteristics.LENS_FACING_BACK
        if (cameraDevice != null || isCameraBusy) {
            switchCamera(requestedFacing)
        } else {
            cameraFacing = requestedFacing
            setupCamera()
        }
        return START_STICKY
    }

    fun switchCamera(facing: Int) {
        synchronized(busyLock) {
            if (isCameraBusy) {
                pendingFacing = facing
                return
            }
            if (cameraFacing == facing && cameraDevice != null) return
            isCameraBusy = true
            cameraFacing = facing
        }
        cameraHandler.post {
            closeCurrentCamera()
            setupCamera()
        }
    }

    private fun isCameraWeCareAbout(cameraId: String): Boolean {
        return try {
            val characteristics = cameraManager?.getCameraCharacteristics(cameraId)
            characteristics?.get(CameraCharacteristics.LENS_FACING) == cameraFacing
        } catch (e: Exception) { false }
    }

    private fun sendCameraAvailabilityToClient(available: Boolean) {
        thread {
            try {
                Socket(clientIp, 9003).use { socket ->
                    val command = if (available) "CAMERA_AVAILABLE:$cameraFacing" else "CAMERA_UNAVAILABLE:$cameraFacing"
                    socket.getOutputStream().write((command + "\n").toByteArray())
                }
            } catch (e: Exception) { }
        }
    }

    private fun sendLocalBroadcast(action: String, facing: Int) {
        sendBroadcast(Intent(action).apply { putExtra(EXTRA_CAMERA_FACING, facing) })
    }

    private fun closeCurrentCamera() {
        try {
            isStreaming.set(false)
            imageReader?.setOnImageAvailableListener(null, null)
            reconnectThread?.interrupt()
            
            releaseMediaRecorder()
            
            captureSession?.close()
            cameraDevice?.close()
            imageReader?.close()
            
            synchronized(netLock) {
                outStream?.close()
                outStream = null
            }
            
            captureSession = null
            cameraDevice = null
            imageReader = null
        } catch (e: Exception) { }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(NOTIFICATION_CHANNEL_ID, "Cámara", NotificationManager.IMPORTANCE_LOW)
            (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(chan)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("Cámara")
            .setContentText("Iniciando...")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(title: String, text: String) {
        val notif = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(NOTIFICATION_ID, notif)
    }

    private fun setupCamera() {
        if (!isCameraAvailable) return
        setupRetryRunnable?.let { cameraHandler.removeCallbacks(it) }

        try {
            val cameraId = cameraManager!!.cameraIdList.find { id ->
                cameraManager!!.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING) == cameraFacing
            } ?: run { onCameraSetupFailed(); return }

            currentCameraId = cameraId
            imageReader = ImageReader.newInstance(640, 480, ImageFormat.YUV_420_888, 5)

            // CORRECCIÓN REDMI: Preparamos el MediaRecorder al arrancar para tener la superficie fija
            prepareMediaRecorder()

            if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                cameraManager!!.openCamera(cameraId, object : CameraDevice.StateCallback() {
                    override fun onOpened(camera: CameraDevice) {
                        cameraDevice = camera
                        startCaptureSession()
                    }
                    override fun onDisconnected(camera: CameraDevice) { closeCurrentCamera() }
                    override fun onError(camera: CameraDevice, error: Int) { onCameraSetupFailed() }
                }, cameraHandler)
            }
        } catch (e: Exception) { onCameraSetupFailed() }
    }

    private fun prepareMediaRecorder() {
        try {
            val file = File(recordingsDir, "vid_${System.currentTimeMillis()}.mp4")
            currentRecordingFile = file
            mediaRecorder = MediaRecorder().apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(640, 480)
                setVideoFrameRate(15)
                setVideoEncodingBitRate(2_000_000)
                setOutputFile(file.absolutePath)
                prepare()
            }
            recordingSurface = mediaRecorder!!.surface
        } catch (e: Exception) {
            Log.e(TAG, "Error preparando MediaRecorder inicial", e)
        }
    }

    private fun startCaptureSession() {
        val device = cameraDevice ?: return
        try {
            val surfaces = mutableListOf<Surface>()
            imageReader?.surface?.let { surfaces.add(it) }
            recordingSurface?.let { surfaces.add(it) }

            // TEMPLATE_RECORD optimiza el consumo energético al procesar ambos destinos en paralelo
            val requestBuilder = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
            surfaces.forEach { requestBuilder.addTarget(it) }

            device.createCaptureSession(surfaces, object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(session: CameraCaptureSession) {
                    captureSession = session
                    try {
                        session.setRepeatingRequest(requestBuilder.build(), null, cameraHandler)
                        onCameraReady()
                        
                        startImageAvailableListener()
                        startReconnectLoop()
                    } catch (e: Exception) { onCameraSetupFailed() }
                }
                override fun onConfigureFailed(session: CameraCaptureSession) { onCameraSetupFailed() }
            }, cameraHandler)
        } catch (e: Exception) { onCameraSetupFailed() }
    }

    private fun onCameraReady() {
        synchronized(busyLock) { isCameraBusy = false }
        updateNotification("Cámara activa", "Transmitiendo...")
        processPendingSwitch()
    }

    private fun onCameraSetupFailed() {
        synchronized(busyLock) { isCameraBusy = false }
        if (!processPendingSwitch()) {
            setupRetryRunnable = Runnable { setupCamera() }
            cameraHandler.postDelayed(setupRetryRunnable!!, SETUP_RETRY_DELAY)
        }
    }

    private fun processPendingSwitch(): Boolean {
        val next = synchronized(busyLock) { pendingFacing.also { pendingFacing = null } }
        if (next != null) { switchCamera(next); return true }
        return false
    }

    private fun startImageAvailableListener() {
        isStreaming.set(true)
        imageReader?.setOnImageAvailableListener({ reader ->
            if (!isStreaming.get()) return@setOnImageAvailableListener
            
            val image = try {
                reader.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener

            try {
                val yPlane = image.planes[0]
                val yBuffer = yPlane.buffer
                val yData = ByteArray(yBuffer.remaining())
                yBuffer.get(yData)

                if (motionDetectionEnabled) {
                    if (motionDetector.detectMotion(yData, image.width, image.height)) {
                        lastMotionTime = System.currentTimeMillis()
                        if (!isRecording) startRecording()
                    }
                    if (isRecording && System.currentTimeMillis() - lastMotionTime > motionTimeoutMs) {
                        stopRecording()
                    }
                }

                val jpegBytes = yuvImageToJpeg(image)
                if (jpegBytes != null) {
                    latestFrameBytes = jpegBytes
                    sendFrame(jpegBytes)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en procesamiento de frame", e)
            } finally {
                image.close() // Evita memory leaks críticos de hardware
            }
        }, cameraHandler)
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

    // CORRECCIÓN REDMI: No destruimos la sesión. El hardware escribe directamente sobre el flujo existente.
    private fun startRecording() {
        if (isRecording) return
        try {
            mediaRecorder?.start()
            isRecording = true
        } catch (e: Exception) { 
            Log.e(TAG, "Error al iniciar grabación", e)
            isRecording = false 
        }
    }

    private fun stopRecording() {
        if (!isRecording) return
        try {
            mediaRecorder?.stop()
            isRecording = false
            
            // Re-inicializamos el recorder de fondo sin alterar el ciclo de hardware de la cámara
            mediaRecorder?.reset()
            val file = File(recordingsDir, "vid_${System.currentTimeMillis()}.mp4")
            currentRecordingFile = file
            mediaRecorder?.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(640, 480)
                setVideoFrameRate(15)
                setVideoEncodingBitRate(2_000_000)
                setOutputFile(file.absolutePath)
                prepare()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error al resetear MediaRecorder de forma segura", e)
        }
    }

    private fun releaseMediaRecorder() {
        try {
            if (isRecording) {
                mediaRecorder?.stop()
                isRecording = false
            }
            mediaRecorder?.release()
            mediaRecorder = null
            recordingSurface = null
        } catch (e: Exception) { }
    }

    private fun startReconnectLoop() {
        reconnectThread = thread(start = true) {
            while (isStreaming.get()) {
                try {
                    var needsReconnect = false
                    synchronized(netLock) {
                        if (outStream == null) {
                            needsReconnect = true
                        }
                    }

                    if (needsReconnect) {
                        val socket = Socket(clientIp, PORT)
                        socket.tcpNoDelay = true
                        synchronized(netLock) {
                            outStream = DataOutputStream(socket.getOutputStream())
                        }
                    }
                    Thread.sleep(5000)
                } catch (e: Exception) { 
                    Thread.sleep(5000) 
                }
            }
        }
    }

    private fun yuvImageToJpeg(image: android.media.Image): ByteArray? {
        try {
            val planes = image.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer

            val nv21 = ByteArray(yBuffer.remaining() + uBuffer.remaining() + vBuffer.remaining())
            yBuffer.rewind()
            uBuffer.rewind()
            vBuffer.rewind()

            yBuffer.get(nv21, 0, yBuffer.remaining())
            vBuffer.get(nv21, yBuffer.remaining(), vBuffer.remaining())
            uBuffer.get(nv21, yBuffer.remaining() + vBuffer.remaining(), uBuffer.remaining())

            val out = ByteArrayOutputStream()
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
            yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 80, out)
            return out.toByteArray()
        } catch (e: Exception) { 
            return null 
        }
    }

    override fun onDestroy() {
        closeCurrentCamera()
        cameraHandlerThread.quitSafely()
        if (wakeLock.isHeld) wakeLock.release()
        super.onDestroy()
    }
}
