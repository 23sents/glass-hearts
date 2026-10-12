package com.example.glasshearts

import android.Manifest
import android.app.Activity
import android.app.WallpaperManager
import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowManager

/**
 * Hosts the scene full-screen. Feeds it the accelerometer (parallax + shake) and the microphone
 * (blow / clap), plays the synthesised sounds and opens the live-wallpaper picker.
 */
class MainActivity : Activity(), SensorEventListener, SceneListener {
    private lateinit var scene: HeartScene
    private lateinit var sound: SoundFx
    private lateinit var mic: MicListener
    private var sensors: SensorManager? = null
    private var resumed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 28) {
            val lp = window.attributes
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            window.attributes = lp
        }
        sound = SoundFx(this)
        scene = HeartScene(this, this)
        mic = MicListener(scene.engine)
        setContentView(scene)
        sensors = getSystemService(SENSOR_SERVICE) as? SensorManager
        hideSystemUi()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        sensors?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensors?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        scene.start()
        if (hasMicPermission()) mic.start() else askForMicOnce()
    }

    override fun onPause() {
        resumed = false
        scene.stop()
        mic.stop()
        sensors?.unregisterListener(this)
        super.onPause()
    }

    override fun onDestroy() {
        sound.release()
        super.onDestroy()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemUi()
    }

    // ---------------------------------------------------------------- microphone permission
    private fun hasMicPermission() =
        checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Asks only once; if she declines, blow / clap simply stay off. */
    private fun askForMicOnce() {
        val prefs = getSharedPreferences("hearts", MODE_PRIVATE)
        if (prefs.getBoolean("mic_asked", false)) return
        prefs.edit().putBoolean("mic_asked", true).apply()
        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQ_MIC)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_MIC && resumed && hasMicPermission()) mic.start()
    }

    // ---------------------------------------------------------------- SceneListener
    override fun onSound(fx: Fx, volume: Float) = sound.play(fx, volume)

    override fun onOpenWallpaperPicker() {
        val component = ComponentName(this, HeartWallpaperService::class.java)
        try {
            startActivity(
                Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                    .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component)
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))
            } catch (e2: Exception) {
                // this device has no live-wallpaper support
            }
        }
    }

    // ---------------------------------------------------------------- sensors
    override fun onSensorChanged(event: SensorEvent) {
        val v = event.values
        scene.engine.setTilt(-v[0] / GRAVITY, v[1] / GRAVITY - 0.6f)
        scene.engine.onAcceleration(v[0], v[1], v[2])
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    }

    private companion object {
        const val GRAVITY = 9.81f
        const val REQ_MIC = 7
    }
}
