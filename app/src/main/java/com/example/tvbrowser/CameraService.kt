package com.example.remotescreamserver

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraService : LifecycleService() {

    private companion object {
        const val TAG = "CameraService"
        const val CAMERA_PORT = 9002
    }

    private lateinit var cameraExecutor: ExecutorService
    private var clientIp: String? = null
    private var currentFacing = CameraSelector.LENS_FACING_BACK
    
    private var socket: Socket? = null
    private var outputStream: OutputStream? = null
    private var isStreaming = false

    // Locks para evitar que el sistema duerma la CPU o el Wi-Fi
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        cameraExecutor = Executors.newSingleThreadExecutor()

        // Adquirir candados de persistencia absoluta
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RemoteScream::CameraWakeLock").apply {
            acquire(10 * 60 * 1000L /*10 minutos o indefinido*/)
        }

        val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "RemoteScream::CameraWifiLock").apply {
            acquire()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        
        clientIp = intent?.getStringExtra("CLIENT_IP")
        val facingExtra = intent?.getIntExtra("FACING", 0) ?: 0
        currentFacing = if (facingExtra == 1) CameraSelector.LENS_FACING_FRONT else CameraSelector.LENS_FACING_BACK

        if (!clientIp.isNullOrEmpty() && !isStreaming) {
            isStreaming = true
            startCameraStreaming()
        }

        // START_STICKY garantiza que si el sistema lo llega a matar por RAM, se reinicie solo
        return START_STICKY
    }

    private fun startCameraStreaming() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()

                // Solicitamos RGBA_8888 para evitar errores de codificación cromática (colores verdes/rotados)
                val imageAnalysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    enviarFrameAlCliente(imageProxy)
                }

                val cameraSelector = CameraSelector.Builder()
                    .requireLensFacing(currentFacing)
                    .build()

                cameraProvider.unbindAll()
                // Al heredar de LifecycleService, 'this' actúa como el LifecycleOwner persistente
                cameraProvider.bindToLifecycle(this, cameraSelector, imageAnalysis)

                // Notificar de vuelta al sistema local el estado real de la cámara activa
                val systemFacingId = if (currentFacing == CameraSelector.LENS_FACING_FRONT) 1 else 0
                val broadcastIntent = Intent("CAMERA_AVAILABLE").apply {
                    putExtra("FACING", systemFacingId)
                }
                sendBroadcast(broadcastIntent)

            } catch (e: Exception) {
                Log.e(TAG, "Error al inicializar CameraX: ${e.message}")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun enviarFrameAlCliente(imageProxy: ImageProxy) {
        try {
            if (socket == null || socket?.isClosed == true) {
                Log.d(TAG, "Conectando socket de cámara hacia el cliente $clientIp:$CAMERA_PORT...")
                socket = Socket(clientIp, CAMERA_PORT)
                outputStream = socket?.getOutputStream()
            }

            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap != null) {
                val baos = ByteArrayOutputStream()
                // Compresión balanceada para fluidez en tiempo real
                bitmap.compress(Bitmap.CompressFormat.JPEG, 60, baos)
                val bytes = baos.toByteArray()

                outputStream?.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
                outputStream?.write(bytes)
                outputStream?.flush()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error de red enviando frame: ${e.message}")
            cerrarSockets()
        } finally {
            imageProxy.close()
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        val buffer = imageProxy.planes[0].buffer
        val pixelStride = imageProxy.planes[0].pixelStride
        val rowStride = imageProxy.planes[0].rowStride
        val rowPadding = rowStride - pixelStride * imageProxy.width

        val bitmap = Bitmap.createBitmap(
            imageProxy.width + rowPadding / pixelStride,
            imageProxy.height,
            Bitmap.Config.ARGB_8888
        )
        buffer.rewind()
        bitmap.copyPixelsFromBuffer(buffer)

        // Corregir la rotación nativa del sensor antes de enviarlo
        val rotationDegrees = imageProxy.imageInfo.rotationDegrees
        return if (rotationDegrees != 0) {
            val matrix = Matrix().apply { 
                postRotate(rotationDegrees.toFloat())
                // Si es la cámara frontal, aplicar espejo horizontal nativo
                if (currentFacing == CameraSelector.LENS_FACING_FRONT) {
                    postScale(-1f, 1f)
                }
            }
            Bitmap.createBitmap(bitmap, 0, 0, imageProxy.width, imageProxy.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun cerrarSockets() {
        try {
            outputStream?.close()
            socket?.close()
        } catch (_: Exception) {}
        socket = null
        outputStream = null
    }

    override fun onDestroy() {
        isStreaming = false
        cerrarSockets()
        cameraExecutor.shutdown()
        
        if (wakeLock?.isHeld == true) wakeLock?.release()
        if (wifiLock?.isHeld == true) wifiLock?.release()
        
        super.onDestroy()
    }
}
