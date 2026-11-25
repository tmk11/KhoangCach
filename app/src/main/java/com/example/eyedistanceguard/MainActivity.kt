package com.example.eyedistanceguard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("monitor", MODE_PRIVATE) }
    private lateinit var preview: PreviewView
    private lateinit var distance: TextView
    private lateinit var status: TextView
    private lateinit var thresholdLabel: TextView
    private lateinit var slider: SeekBar
    private lateinit var calibrate: Button
    private lateinit var background: Button
    private lateinit var recover: Button
    private var service: MonitorService? = null
    private var bound = false
    private var visible = false

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (visible) connectOrExplain()
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (visible) startBackground()
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val monitor = (binder as MonitorService.LocalBinder).service
            service = monitor
            slider.progress = monitor.threshold - MIN_CM
            monitor.attach(preview.surfaceProvider, ::render)
        }
        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            render(Reading("Đang kết nối lại camera…"))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        preview = findViewById(R.id.previewView)
        distance = findViewById(R.id.distanceText)
        status = findViewById(R.id.statusText)
        thresholdLabel = findViewById(R.id.thresholdLabel)
        slider = findViewById(R.id.thresholdSeek)
        calibrate = findViewById(R.id.calibrate)
        background = findViewById(R.id.background)
        recover = findViewById(R.id.recover)
        val initial = prefs.getInt("threshold", DEFAULT_CM).coerceIn(MIN_CM, MAX_CM)
        slider.progress = initial - MIN_CM
        thresholdLabel.text = "Ngưỡng cảnh báo: $initial cm"
        slider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar, progress: Int, fromUser: Boolean) {
                val cm = MIN_CM + progress
                thresholdLabel.text = "Ngưỡng cảnh báo: $cm cm"
                if (fromUser) service?.changeThreshold(cm)
            }
            override fun onStartTrackingTouch(bar: SeekBar) {}
            override fun onStopTrackingTouch(bar: SeekBar) {}
        })
        calibrate.setOnClickListener { showCalibration() }
        background.setOnClickListener {
            if (service?.background == true) service?.stopBackground()
            else if (Build.VERSION.SDK_INT >= 33 && !granted(Manifest.permission.POST_NOTIFICATIONS) &&
                !prefs.getBoolean("askedNotifications", false)) {
                prefs.edit().putBoolean("askedNotifications", true).apply()
                notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else startBackground()
        }
        recover.setOnClickListener {
            if (granted(Manifest.permission.CAMERA)) service?.retryCamera()
            else if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA) ||
                !prefs.getBoolean("askedCamera", false)) askCamera()
            else startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        render(Reading("Đang mở camera…"))
    }

    override fun onStart() {
        super.onStart()
        visible = true
        connectOrExplain()
        if (!granted(Manifest.permission.CAMERA) && !prefs.getBoolean("askedCamera", false)) askCamera()
    }

    private fun askCamera() {
        prefs.edit().putBoolean("askedCamera", true).apply()
        cameraPermission.launch(Manifest.permission.CAMERA)
    }

    private fun connectOrExplain() {
        if (!granted(Manifest.permission.CAMERA)) {
            render(Reading("Cần quyền camera trước để đo khoảng cách."))
            recover.text = if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) "Cấp quyền camera" else "Mở cài đặt quyền camera"
            recover.visibility = View.VISIBLE
        } else if (!bound) {
            render(Reading("Đang mở camera…"))
            bound = bindService(Intent(this, MonitorService::class.java), connection, Context.BIND_AUTO_CREATE)
        }
    }

    override fun onStop() {
        visible = false
        if (bound) {
            service?.attach(null, null)
            unbindService(connection)
            bound = false
            service = null
        }
        super.onStop()
    }

    private fun startBackground() {
        if (service == null || !granted(Manifest.permission.CAMERA)) return
        try {
            ContextCompat.startForegroundService(this, Intent(this, MonitorService::class.java)
                .setAction(MonitorService.START_BACKGROUND))
        } catch (e: RuntimeException) {
            Toast.makeText(this, "Không thể bật theo dõi nền lúc này.", Toast.LENGTH_LONG).show()
        }
    }

    private fun showCalibration() {
        val dialog = AlertDialog.Builder(this).setTitle("Hiệu chuẩn ở $REFERENCE_CM cm")
            .setMessage("Dùng thước đặt mắt cách màn hình $REFERENCE_CM cm. Nhìn thẳng, giữ yên và để một khuôn mặt trong khung hình, rồi nhấn Lưu.")
            .setNegativeButton("Hủy", null).setPositiveButton("Lưu", null).create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (service?.calibrate() == true) {
                    dialog.dismiss()
                    Toast.makeText(this, "Đã lưu hiệu chuẩn", Toast.LENGTH_SHORT).show()
                } else dialog.setMessage("Chưa có mẫu ổn định. Giữ yên một khuôn mặt ở $REFERENCE_CM cm khoảng một giây, rồi nhấn Lưu lại.")
            }
        }
        dialog.show()
    }

    private fun render(reading: Reading) {
        val monitor = service
        val cm = reading.cm
        val threshold = monitor?.threshold ?: DEFAULT_CM
        distance.text = cm?.let { "${it.roundToInt()} cm" } ?: "-- cm"
        distance.setTextColor(when {
            cm == null -> -1
            cm < threshold -> 0xFFEF5350.toInt()
            cm < threshold + 10 -> 0xFFFFC107.toInt()
            else -> 0xFF66BB6A.toInt()
        })
        status.text = reading.message
        slider.isEnabled = monitor != null
        background.isEnabled = monitor != null && (!reading.cameraError || monitor.background)
        background.text = if (monitor?.background == true) "Dừng theo dõi nền" else "Theo dõi khi dùng ứng dụng khác"
        calibrate.isEnabled = cm != null
        recover.text = "Thử mở lại camera"
        recover.visibility = if (reading.cameraError) View.VISIBLE else View.GONE
    }
}
