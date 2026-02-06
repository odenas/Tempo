package com.example.tempo

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.gms.location.*
import com.example.tempo.R

data class Interval(
    val type: String,      // "High", "Low", or "Cooldown"
    val targetPace: Float, // min/mile
    val durationSeconds: Int
)

object PaceConverter {
    private const val METERS_IN_MILE = 1609.34f

    /** Converts m/s to decimal min/mile */
    fun msToPace(ms: Float): Float {
        if (ms < 0.1f) return 0f
        return METERS_IN_MILE / (ms * 60f)
    }

    /** Checks if current pace is within a 5% window of target */
    fun isWithinWindow(current: Float, target: Float, margin: Float = 0.05f): Boolean {
        if (current <= 0f) return false
        val diff = kotlin.math.abs(current - target)
        return (diff / target) <= margin
    }

    /** Formats decimal pace (8.5) to string (8:30) */
    fun formatPace(paceDecimal: Float): String {
        if (paceDecimal <= 0) return "--:--"
        val minutes = paceDecimal.toInt()
        val seconds = ((paceDecimal - minutes) * 60).toInt()
        return String.format("%d:%02d", minutes, seconds)
    }
}

class WorkoutManager(
    highPace: Float, highDur: Int,
    lowPace: Float, lowDur: Int,
    coolPace: Float, coolDur: Int,
    numIntervals: Int = 4
) {
    private val intervals = mutableListOf<Interval>()
    private var currentIndex = 0

    init {
        repeat(numIntervals) {
            intervals.add(Interval("High", highPace, highDur))
            intervals.add(Interval("Low", lowPace, lowDur))
        }
        intervals.add(Interval("Cooldown", coolPace, coolDur))
    }

    fun getCurrentInterval(): Interval? {
        return if (currentIndex < intervals.size) intervals[currentIndex] else null
    }

    fun nextInterval(): Interval? {
        currentIndex++
        return getCurrentInterval()
    }

    fun getProgressString(): String {
        return "Step ${currentIndex + 1} of ${intervals.size}"
    }

    fun isFinished(): Boolean = currentIndex >= intervals.size

    fun getShortStatusString(): String {
        val interval = getCurrentInterval() ?: return ""

        // Map full names to your short codes
        val code = when (interval.type) {
            "High" -> "Hi"
            "Low" -> "Lo"
            "Cooldown" -> "Cd"
            else -> ""
        }

        // Calculate interval number (1-based).
        // Since High/Low come in pairs, (index / 2) + 1 gives the component number.
        // For Cooldown, we can just show the code or 'Final'.
        val intervalNum = if (interval.type == "Cooldown") "" else "${(currentIndex / 2) + 1}"

        return "$intervalNum$code"
    }
    companion object {
        /**
         * Parses UI elements to create a WorkoutManager.
         * Pass the Activity's root layout or the Activity itself.
         */
        fun fromUI(activity: AppCompatActivity): WorkoutManager {
            // Finding views and parsing values in one place
            val hPace = activity.findViewById<EditText>(R.id.highPace).text.toString().toFloatOrNull() ?: 8.0f
            val hDur = activity.findViewById<EditText>(R.id.highDur).text.toString().toIntOrNull() ?: 240

            val lPace = activity.findViewById<EditText>(R.id.lowPace).text.toString().toFloatOrNull() ?: 12.0f
            val lDur = activity.findViewById<EditText>(R.id.lowDur).text.toString().toIntOrNull() ?: 180

            val cPace = activity.findViewById<EditText>(R.id.coolPace).text.toString().toFloatOrNull() ?: 15.0f
            val cDur = activity.findViewById<EditText>(R.id.coolDur).text.toString().toIntOrNull() ?: 300

            return WorkoutManager(hPace, hDur, lPace, lDur, cPace, cDur, 4)
        }
    }
}


class PaceActivity : AppCompatActivity() {
    private var intervalList = mutableListOf<Interval>()
    private var currentIntervalIndex = 0
    private var currentTimer: android.os.CountDownTimer? = null

    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var targetPace: Float = 10.0f // Default 10 min/mile
    private var currentPace: Float = 0f

    private var currentSpeed: Float = 0f

    private val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private val handler = Handler(Looper.getMainLooper())
    private var beatInterval: Long = 500
    private var isWithinTargetWindow: Boolean = false
    private val speedHistory = mutableListOf<Float>()
    private val SMOOTHING_WINDOW = 5 // Number of readings to average

    private var workoutManager: WorkoutManager? = null

    // 1. Define the Callback as a member variable
    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                location?.let {
                    // 1. Add new speed to history
                    speedHistory.add(it.speed)

                    // 2. Keep only the last X readings
                    if (speedHistory.size > SMOOTHING_WINDOW) {
                        speedHistory.removeAt(0)
                    }

                    // 3. Calculate average speed
                    currentSpeed = speedHistory.average().toFloat()

                    // 4. Update UI and Metronome
                    adjustBeatFrequency()
                }
            }
        }
    }
    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.entries.all { it.value }) {
            setupAppLogic()
        } else {
            Toast.makeText(this, "Location permission required!", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        checkAndRequestPermissions()
    }

    private fun checkAndRequestPermissions() {
        val hasFine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (hasFine) {
            setupAppLogic()
        } else {
            requestPermissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    private fun setupAppLogic() {
        val btnStart = findViewById<Button>(R.id.btnStart)
        val btnStop = findViewById<Button>(R.id.btnStop)

        btnStart.setOnClickListener {
            // 1. Capture user inputs from the UI
            workoutManager = WorkoutManager.fromUI(this)
            // 3. Reset the state index and start the hardware services
            currentIntervalIndex = 0
            startLocationUpdates()
            startMetronome()

            // 4. Update UI State
            btnStart.isEnabled = false

            // 5. Kick off the first interval
            startNextInterval()
        }

        btnStop.setOnClickListener {
            stopSession()
        }
    }
    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000).build()

        // Safety check for the compiler
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper())
        }
    }

    private fun adjustBeatFrequency() {
        val statusCircle = findViewById<View>(R.id.statusCircle)
        val textCurrentPace = findViewById<TextView>(R.id.textCurrentPace)
        val textIntervalStatus = findViewById<TextView>(R.id.textIntervalStatus)


        // 1. Use the static converter to get the pace
        currentPace = PaceConverter.msToPace(currentSpeed)

        // 2. Update the UI text using our formatting logic
        textCurrentPace.text = PaceConverter.formatPace(currentPace)
        // Inside adjustBeatFrequency
        textIntervalStatus.text = workoutManager?.getShortStatusString()

        if (currentSpeed < 0.1f) {
            statusCircle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GRAY)
            isWithinTargetWindow = false
            return
        }

        // 3. Check the "Sweet Spot" using the refactored logic
        isWithinTargetWindow = PaceConverter.isWithinWindow(currentPace, targetPace)

        if (isWithinTargetWindow) {
            statusCircle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.GREEN)
        } else {
            // 4. Handle color coding for off-pace
            if (currentPace > targetPace) {
                // Too slow (higher pace number)
                statusCircle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.RED)
                beatInterval = (beatInterval - 50).coerceAtLeast(200)
            } else {
                // Too fast (lower pace number)
                statusCircle.backgroundTintList = android.content.res.ColorStateList.valueOf(android.graphics.Color.CYAN)
                beatInterval = (beatInterval + 50).coerceAtMost(1000)
            }
        }
    }

    private fun startMetronome() {
        handler.post(object : Runnable {
            override fun run() {
                // Only beep if we are NOT in the 5% target window
                if (!isWithinTargetWindow) {
                    toneGen.startTone(ToneGenerator.TONE_PROP_BEEP, 100)
                }

                // Re-queue the next beat regardless so the rhythm is ready
                handler.postDelayed(this, beatInterval)
            }
        })
    }
    private fun startNextInterval() {
        val interval = workoutManager?.getCurrentInterval() ?: run {
            stopSession()
            return
        }

        val textIntervalStatus = findViewById<TextView>(R.id.textIntervalStatus)
        textIntervalStatus.text = workoutManager?.getShortStatusString()

        // Update target for metronome
        targetPace = interval.targetPace

        // Update UI using our Refactored classes
        val btnStart = findViewById<Button>(R.id.btnStart)
        btnStart.text = "${interval.type}: ${workoutManager?.getProgressString()}"

        toneGen.startTone(ToneGenerator.TONE_CDMA_PIP, 500)

        currentTimer?.cancel()
        currentTimer = object : android.os.CountDownTimer(interval.durationSeconds * 1000L, 1000) {
            override fun onTick(millisLeft: Long) { /* Update timer UI */ }

            override fun onFinish() {
                workoutManager?.nextInterval()
                startNextInterval()
            }
        }.start()
    }

    private fun stopSession() {
        currentTimer?.cancel() // Stop the countdown
        fusedLocationClient.removeLocationUpdates(locationCallback) // Stop GPS
        handler.removeCallbacksAndMessages(null) // Stop Metronome

        val btnStart = findViewById<Button>(R.id.btnStart)
        btnStart.isEnabled = true
        btnStart.text = "Start 4x4 HIIT"
    }}