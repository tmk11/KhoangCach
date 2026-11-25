package com.example.eyedistanceguard

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.util.concurrent.Executors
import kotlin.math.tan

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var distanceText: TextView
    private lateinit var statusText: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private var thresholdCm = 30
    private var tooCloseSince = 0L
    private var lastBeepAt = 0L

    private val AVERAGE_FACE_WIDTH_CM = 14.5f
    private val HORIZONTAL_FOV_DEG = 60.0

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) startCamera()
        }

    private val detector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
                .setMinFaceSize(0.15f)
                .build()
        )
    }

    private val toneGenerator by lazy { ToneGenerator(AudioManager.STREAM_ALARM, 80) }
    private val vibrator by lazy { getSystemService(Vibrator::class.java) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        distanceText = findViewById(R.id.distanceText)
        statusText = findViewById(R.id.statusText)

        findViewById<SeekBar>(R.id.thresholdSeek).setOnSeekBarChangeListener(object :
            SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                thresholdCm = progress
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) {
            startCamera()
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
            analysis.setAnalyzer(executor, ::analyzeFrame)

            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis)
        }, ContextCompat.getMainExecutor(this))
    }

    private fun analyzeFrame(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image
        if (mediaImage == null) {
            imageProxy.close()
            return
        }
        val rotation = imageProxy.imageInfo.rotationDegrees
        val input = InputImage.fromMediaImage(mediaImage, rotation)
        detector.process(input)
            .addOnSuccessListener { faces ->
                val widest = faces.maxByOrNull { it.boundingBox.width() }?.boundingBox
                if (widest == null) {
                    runOnUiThread { distanceText.text = "-- cm"; statusText.text = "Không thấy khuôn mặt" }
                } else {
                    val rotatedWidth = if (rotation == 90 || rotation == 270) imageProxy.height else imageProxy.width
                    val distanceCm = estimateDistanceCm(widest, rotatedWidth)
                    onDistanceMeasured(distanceCm)
                }
            }
            .addOnCompleteListener { imageProxy.close() }
    }

    private fun estimateDistanceCm(faceBox: Rect, imageWidthPx: Int): Float {
        val focalPx = imageWidthPx / (2.0 * tan(Math.toRadians(HORIZONTAL_FOV_DEG) / 2.0))
        return AVERAGE_FACE_WIDTH_CM * focalPx.toFloat() / faceBox.width()
    }

    private fun onDistanceMeasured(distanceCm: Float) {
        runOnUiThread {
            val rounded = distanceCm.toInt()
            distanceText.text = "$rounded cm"
            val now = System.currentTimeMillis()
            if (distanceCm >= thresholdCm + 10) {
                tooCloseSince = 0L
                distanceText.setTextColor(0xFF4CAF50.toInt())
                statusText.text = "Khoảng cách an toàn cho đôi mắt 👍"
            } else if (distanceCm >= thresholdCm) {
                tooCloseSince = if (tooCloseSince == 0L) now else tooCloseSince
                distanceText.setTextColor(0xFFFFC107.toInt())
                statusText.text = "Sắp quá gần, hãy đẩy xa thêm chút"
            } else {
                tooCloseSince = if (tooCloseSince == 0L) now else tooCloseSince
                distanceText.setTextColor(0xFFF44336.toInt())
                statusText.text = "Quá gần mắt! Giữ khoảng cách tối thiểu $thresholdCm cm"
                if (now - tooCloseSince > 2000 && now - lastBeepAt > 1500) {
                    lastBeepAt = now
                    warnUser()
                }
            }
        }
    }

    private fun warnUser() {
        toneGenerator.startTone(ToneGenerator.TONE_PROP_BEEP2, 300)
        val pattern = longArrayOf(0, 200, 150, 200)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(500)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        executor.shutdown()
        detector.close()
        toneGenerator.release()
    }
}
