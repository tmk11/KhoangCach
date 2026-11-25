package com.example.eyedistanceguard

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Size
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions

internal data class Reading(val message: String, val cm: Float? = null, val cameraError: Boolean = false)

class MonitorService : LifecycleService() {
    companion object {
        const val START_BACKGROUND = "startBackground"
        const val STOP_BACKGROUND = "stopBackground"
        private const val CHANNEL = "monitor"
    }

    inner class LocalBinder : Binder() { val service get() = this@MonitorService }
    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }
    private val timer = AlertTimer()
    private val samples = CalibrationWindow()
    private var tone: ToneGenerator? = null
    private val detector by lazy {
        FaceDetection.getClient(FaceDetectorOptions.Builder().setMinFaceSize(0.15f).build())
    }
    private var provider: ProcessCameraProvider? = null
    private var previewSurface: Preview.SurfaceProvider? = null
    private var analysis: ImageAnalysis? = null
    private var cameraState: androidx.lifecycle.LiveData<CameraState>? = null
    private var rotation = Surface.ROTATION_0
    private val orientation by lazy {
        object : OrientationEventListener(this) {
            override fun onOrientationChanged(degrees: Int) {
                if (degrees == ORIENTATION_UNKNOWN) return
                val next = when (degrees) {
                    in 45..134 -> Surface.ROTATION_270
                    in 135..224 -> Surface.ROTATION_180
                    in 225..314 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
                if (rotation != next) {
                    rotation = next
                    analysis?.targetRotation = next
                    resetTracking()
                }
            }
        }
    }
    private var generation = 0
    private var closed = false
    private var reading = Reading("Đang mở camera…")
    internal var listener: ((Reading) -> Unit)? = null
    internal var background = false
        private set
    internal var threshold = DEFAULT_CM
        private set
    private var scale = defaultScale

    override fun onCreate() {
        super.onCreate()
        threshold = prefs.getInt("threshold", DEFAULT_CM).coerceIn(MIN_CM, MAX_CM)
        scale = prefs.getFloat("scale", defaultScale)
        orientation.enable()
        retryCamera()
    }

    internal fun retryCamera() {
        if (provider != null) {
            bindCamera()
            return
        }
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            if (!closed) {
                try {
                    provider = future.get()
                    bindCamera()
                } catch (e: Exception) {
                    cameraFailed()
                }
            }
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return LocalBinder()
    }

    internal fun attach(surface: Preview.SurfaceProvider?, callback: ((Reading) -> Unit)?) {
        listener = callback
        if (previewSurface !== surface) {
            previewSurface = surface
            bindCamera()
        }
        listener?.invoke(reading)
    }

    private fun resetTracking() {
        samples.reset()
        timer.reset()
    }

    private fun display(value: Reading) {
        reading = value
        listener?.invoke(value)
    }

    private fun cameraFailed() {
        resetTracking()
        display(Reading("Không mở được camera trước. Kiểm tra camera và quyền truy cập rồi thử lại.", cameraError = true))
        if (background) stopBackground()
    }

    internal fun bindCamera() {
        val cameras = provider ?: return
        val token = ++generation
        resetTracking()
        cameraState?.removeObservers(this)
        cameras.unbindAll()
        try {
            val selector = CameraSelector.DEFAULT_FRONT_CAMERA
            if (!cameras.hasCamera(selector)) {
                cameraFailed()
                return
            }
            display(Reading("Đang tìm khuôn mặt…"))
            val stream = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setTargetRotation(rotation)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis = stream
            stream.setAnalyzer(ContextCompat.getMainExecutor(this)) { analyze(it, token) }
            val preview = previewSurface?.let { surface ->
                Preview.Builder().build().also { it.setSurfaceProvider(surface) }
            }
            val camera = if (preview == null) cameras.bindToLifecycle(this, selector, stream)
                else cameras.bindToLifecycle(this, selector, stream, preview)
            cameraState = camera.cameraInfo.cameraState.also { states ->
                states.observe(this) { state ->
                    if (state.error != null) cameraFailed()
                }
            }
        } catch (e: Exception) {
            cameraFailed()
        }
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(frame: ImageProxy, token: Int) {
        val media = frame.image
        if (closed || token != generation || media == null) {
            frame.close()
            return
        }
        val normalizer = maxOf(frame.width, frame.height).toFloat()
        val capturedAt = SystemClock.elapsedRealtime()
        try {
            detector.process(InputImage.fromMediaImage(media, frame.imageInfo.rotationDegrees))
                .addOnCompleteListener { task ->
                    try {
                        if (!closed && token == generation) {
                            val faces = if (task.isSuccessful) task.result else emptyList()
                            val width = faces.maxOfOrNull { it.boundingBox.width() } ?: 0
                            if (width <= 0 || SystemClock.elapsedRealtime() - capturedAt > 1000) {
                                resetTracking()
                                display(Reading(if (task.isSuccessful) "Không thấy khuôn mặt" else "Chưa phân tích được hình ảnh"))
                            } else {
                                val fraction = width / normalizer
                                if (faces.size == 1) samples.add(capturedAt, fraction) else samples.reset()
                                val cm = scale / fraction
                                if (timer.update(cm, threshold, capturedAt)) warn()
                                display(Reading(when {
                                    cm < threshold -> "Quá gần! Hãy giữ ít nhất $threshold cm"
                                    cm < threshold + 10 -> "Gần ngưỡng, hãy đẩy xa thêm chút"
                                    else -> "Khoảng cách trên ngưỡng đã đặt"
                                }, cm))
                            }
                        }
                    } finally {
                        frame.close()
                    }
                }
        } catch (e: RuntimeException) {
            frame.close()
            resetTracking()
            display(Reading("Chưa phân tích được hình ảnh"))
        }
    }

    internal fun calibrate(): Boolean {
        val calibrated = samples.scale(SystemClock.elapsedRealtime()) ?: return false
        scale = calibrated
        prefs.edit().putFloat("scale", scale).apply()
        timer.reset()
        return true
    }

    internal fun changeThreshold(value: Int) {
        threshold = value.coerceIn(MIN_CM, MAX_CM)
        prefs.edit().putInt("threshold", threshold).apply()
        timer.reset()
    }

    @Suppress("DEPRECATION")
    private fun warn() {
        if (tone == null) tone = runCatching { ToneGenerator(AudioManager.STREAM_ALARM, 80) }.getOrNull()
        tone?.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        val vibrator = getSystemService(Vibrator::class.java) ?: return
        if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 200, 150, 200), -1))
        else vibrator.vibrate(500)
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == START_BACKGROUND) {
            try {
                val manager = getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 26) {
                    manager.createNotificationChannel(NotificationChannel(CHANNEL, "Theo dõi khoảng cách", NotificationManager.IMPORTANCE_LOW))
                }
                val immutable = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), immutable)
                val stop = PendingIntent.getService(this, 1, Intent(this, MonitorService::class.java).setAction(STOP_BACKGROUND), immutable)
                val notification = NotificationCompat.Builder(this, CHANNEL)
                    .setSmallIcon(R.drawable.ic_launcher).setContentTitle("Đang theo dõi khoảng cách")
                    .setContentText("Camera trước đang hoạt động").setOngoing(true)
                    .setContentIntent(open).addAction(0, "Dừng theo dõi nền", stop).build()
                ServiceCompat.startForeground(this, 1, notification,
                    if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0)
                background = true
                listener?.invoke(reading)
            } catch (e: RuntimeException) {
                stopBackground()
                display(Reading("Không bật được chế độ nền. Hãy mở ứng dụng và thử lại."))
            }
        } else stopBackground()
        return START_NOT_STICKY
    }

    internal fun stopBackground() {
        background = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        listener?.invoke(reading)
    }

    override fun onDestroy() {
        closed = true
        orientation.disable()
        cameraState?.removeObservers(this)
        analysis?.clearAnalyzer()
        provider?.unbindAll()
        detector.close()
        tone?.release()
        super.onDestroy()
    }
}
