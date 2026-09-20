package com.arti.gesturescroll

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

class GestureCameraService : LifecycleService() {
    private val analyzerExecutor = Executors.newSingleThreadExecutor()
    private val interpreter = GestureInterpreter()
    private val frameInFlight = AtomicBoolean(false)
    private val pendingBitmap = AtomicReference<Bitmap?>(null)

    private var recognizer: GestureRecognizer? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var lastTimestampMs = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        GestureRuntime.setRunning(true)

        try {
            setupRecognizer()
            startCamera()
        } catch (t: Throwable) {
            Log.e(TAG, "Failed to start gesture recognition", t)
            GestureRuntime.setError(t.message ?: "Не удалось запустить распознавание")
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return Service.START_NOT_STICKY
        }
        return super.onStartCommand(intent, flags, startId)
    }

    private fun setupRecognizer() {
        val baseOptions = BaseOptions.builder()
            .setModelAssetPath(MODEL_ASSET)
            .build()
        val options = GestureRecognizer.GestureRecognizerOptions.builder()
            .setBaseOptions(baseOptions)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setResultListener(this::onRecognitionResult)
            .setErrorListener { error ->
                Log.e(TAG, "MediaPipe error", error)
                GestureRuntime.setError(error.message ?: "Ошибка MediaPipe")
                finishFrame()
            }
            .build()
        recognizer = GestureRecognizer.createFromOptions(this, options)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setTargetResolution(Size(640, 480))
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analyzerExecutor, ::analyzeFrame)

                provider.unbindAll()
                provider.bindToLifecycle(
                    this,
                    CameraSelector.DEFAULT_FRONT_CAMERA,
                    analysis,
                )
            } catch (t: Throwable) {
                Log.e(TAG, "Camera binding failed", t)
                GestureRuntime.setError(t.message ?: "Не удалось открыть фронтальную камеру")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(image: ImageProxy) {
        if (!frameInFlight.compareAndSet(false, true)) {
            image.close()
            return
        }

        try {
            val source = image.toBitmap()
            val rotationDegrees = image.imageInfo.rotationDegrees
            image.close()

            val upright = rotateBitmap(source, rotationDegrees)
            if (upright !== source) source.recycle()
            pendingBitmap.set(upright)

            val now = SystemClock.uptimeMillis()
            val timestamp = if (now <= lastTimestampMs) lastTimestampMs + 1L else now
            lastTimestampMs = timestamp

            val mpImage = BitmapImageBuilder(upright).build()
            recognizer?.recognizeAsync(mpImage, timestamp)
                ?: finishFrame()
        } catch (t: Throwable) {
            image.close()
            Log.e(TAG, "Frame analysis failed", t)
            GestureRuntime.setError(t.message ?: "Ошибка обработки кадра")
            finishFrame()
        }
    }

    private fun onRecognitionResult(result: GestureRecognizerResult, ignored: com.google.mediapipe.framework.image.MPImage) {
        try {
            val category = result.gestures()
                .firstOrNull()
                ?.maxByOrNull { it.score() }
            val landmarks = result.landmarks().firstOrNull()
            val palmY = landmarks?.let { hand ->
                val indices = intArrayOf(0, 5, 9, 13, 17)
                indices.map { hand[it].y() }.average().toFloat()
            }

            val gestureName = category?.categoryName()
            val gestureScore = category?.score() ?: 0f
            val action = interpreter.onFrame(
                gestureName = gestureName,
                gestureScore = gestureScore,
                palmY = palmY,
                timestampMs = SystemClock.uptimeMillis(),
            )

            if (gestureName != null && gestureName != "None") {
                GestureRuntime.setLastGesture(displayGesture(gestureName))
            }

            when (action) {
                GestureInterpreter.Action.SWIPE_UP -> dispatch("↑") {
                    GestureAccessibilityService.swipeUp()
                }
                GestureInterpreter.Action.SWIPE_DOWN -> dispatch("↓") {
                    GestureAccessibilityService.swipeDown()
                }
                GestureInterpreter.Action.LIKE -> dispatch("👍") {
                    GestureAccessibilityService.like()
                }
                null -> Unit
            }
        } finally {
            finishFrame()
        }
    }

    private inline fun dispatch(label: String, block: () -> Boolean) {
        if (block()) {
            GestureRuntime.setLastGesture(label)
        } else {
            GestureRuntime.setError("Включите службу специальных возможностей HandScroll")
        }
    }

    private fun rotateBitmap(source: Bitmap, rotationDegrees: Int): Bitmap {
        if (rotationDegrees == 0) return source
        val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
        return Bitmap.createBitmap(
            source,
            0,
            0,
            source.width,
            source.height,
            matrix,
            true,
        )
    }

    private fun finishFrame() {
        pendingBitmap.getAndSet(null)?.let { bitmap ->
            if (!bitmap.isRecycled) bitmap.recycle()
        }
        frameInFlight.set(false)
    }

    private fun displayGesture(name: String): String = when (name) {
        "Thumb_Up" -> "👍"
        "Open_Palm" -> "Ладонь"
        "Closed_Fist" -> "Кулак"
        "Pointing_Up" -> "Указание"
        else -> name
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        )
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }

    private fun buildNotification(): android.app.Notification {
        val stopIntent = Intent(this, GestureCameraService::class.java).setAction(ACTION_STOP)
        val stopPendingIntent = PendingIntent.getService(
            this,
            0,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
        val openPendingIntent = openIntent?.let {
            PendingIntent.getActivity(
                this,
                1,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPendingIntent)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.notification_stop),
                stopPendingIntent,
            )
            .build()
    }

    override fun onDestroy() {
        cameraProvider?.unbindAll()
        recognizer?.close()
        recognizer = null
        finishFrame()
        analyzerExecutor.shutdownNow()
        interpreter.reset()
        GestureRuntime.setRunning(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_STOP = "com.arti.gesturescroll.STOP"
        private const val TAG = "GestureCameraService"
        private const val MODEL_ASSET = "gesture_recognizer.task"
        private const val CHANNEL_ID = "gesture_control"
        private const val NOTIFICATION_ID = 41
    }
}
