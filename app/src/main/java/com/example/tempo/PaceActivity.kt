package com.example.tempo

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
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
    fun isWithinWindow(current: Float, target: Float, margin: Float): Boolean {
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

class Metronome(private val beatFrequencyStep: Long = 50L){
    private val toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
    private val handler = Handler(Looper.getMainLooper())
    private var beatInterval: Long = 1000
    private var isRunning = false
    var isMuted = false // Controlled by our 5% window logic
    private var currentTone = ToneGenerator.TONE_PROP_BEEP

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (!isMuted) toneGen.startTone(currentTone, 100)
            handler.postDelayed(this, beatInterval)
        }
    }

    fun setTone(toneType: Int) {
        currentTone = toneType
    }

    fun start() {
        if (isRunning) return
        isRunning = true
        handler.post(tickRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacksAndMessages(null)
    }

    fun updateBeepRegime(currentPace: Float, target: Float, withinWindow: Boolean){
        isMuted = withinWindow
        if(!withinWindow){
            if(currentPace > target){  // got to run faster
                setTone(ToneGenerator.TONE_CDMA_LOW_L)
                beatInterval = (beatInterval - beatFrequencyStep).coerceAtLeast(200)
            } else {
                setTone(ToneGenerator.TONE_SUP_PIP)
                beatInterval = (beatInterval + beatFrequencyStep).coerceAtMost(1000)
            }
        }
    }

    fun release() {
        stop()
        toneGen.release()
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
            intervals.add(Interval("Low", lowPace, lowDur))
            intervals.add(Interval("High", highPace, highDur))
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
class SpeedHistory(private val windowSize: Int) {
    private val history = mutableListOf<Float>()

    /** Adds a new raw m/s reading and returns the smoothed m/s average */
    fun addAndGetAverage(newSpeed: Float): Float {
        history.add(newSpeed)

        // Remove the oldest reading if we exceed the window size
        if (history.size > windowSize) {
            history.removeAt(0)
        }

        // Return the average of the current window
        return history.average().toFloat()
    }

    /** Clears the history (call this when a session stops or pauses) */
    fun clear() {
        history.clear()
    }
}
class PaceUIManager(activity: AppCompatActivity) {
    private val statusCircle: View = activity.findViewById(R.id.statusCircle)
    private val paceText: TextView = activity.findViewById(R.id.textCurrentPace)
    private val statusText: TextView = activity.findViewById(R.id.textIntervalStatus)
    private val progressBar: ProgressBar = activity.findViewById(R.id.intervalProgress)
    private val btnStart: Button = activity.findViewById(R.id.btnStart)
    private var lastStatus: String = ""
    fun updatePaceDisplay(pace: Float, target: Float, isWithinWindow: Boolean) {
        paceText.text = PaceConverter.formatPace(pace)

        val color = when {
            isWithinWindow -> Color.GREEN
            pace > target -> Color.RED  // Too slow
            else -> Color.CYAN         // Too fast
        }
        statusCircle.backgroundTintList = ColorStateList.valueOf(color)
    }

    fun updateIntervalProgress(progress: Int, status: String) {
        progressBar.progress = progress
        if (status != lastStatus) {
            statusText.text = status
            lastStatus = status
        }
    }
    fun resetToDefaultState() {
        paceText.text = "--:--"
        statusText.text = "Ready"
        progressBar.progress = 0

        // Reset circle to Grey
        statusCircle.backgroundTintList = ColorStateList.valueOf(Color.GRAY)
    }
    fun setRunningMode(isRunning: Boolean) {
        btnStart.isEnabled = !isRunning
        if (isRunning) btnStart.text = "Running..." else btnStart.text = "Start 4x4 HIIT"
        btnStart.alpha = if (isRunning) 0.5f else 1.0f
    }
}
class PaceActivity : AppCompatActivity() {
    private lateinit var uiManager: PaceUIManager
    private val metronome = Metronome()
    private var currentTimer: android.os.CountDownTimer? = null
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private var currentSpeed: Float = 0f
    private var workoutManager: WorkoutManager? = null
    private val speedHistory = SpeedHistory(windowSize = 5)
    // 1. Define the Callback as a member variable

    private val locationCallback = object : LocationCallback() {
        override fun onLocationResult(locationResult: LocationResult) {
            for (location in locationResult.locations) {
                location?.let {
                    // Feed raw speed into the history class and get the "clean" speed back
                    currentSpeed = speedHistory.addAndGetAverage(it.speed)
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
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        uiManager = PaceUIManager(this)
        uiManager.resetToDefaultState()
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
            workoutManager = WorkoutManager.fromUI(this)
            uiManager.setRunningMode(true)
            metronome.start()
            startLocationUpdates()
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
        val target = workoutManager?.getCurrentInterval()?.targetPace ?: return
        val currentPace = PaceConverter.msToPace(currentSpeed)
        val withinWindow = PaceConverter.isWithinWindow(currentPace, target, 0.05f)
        // The UI Manager handles all the colors and text IDs!
        uiManager.updatePaceDisplay(currentPace, target, withinWindow)
        // The Activity just handles the logic of the Metronome
        metronome.updateBeepRegime(currentPace, target, withinWindow)
    }
    private fun startNextInterval() {
        // 1. Get the current chunk of the workout
        val interval = workoutManager?.getCurrentInterval() ?: run {
            stopSession() // No more intervals? Shut it all down.
            return
        }

        //speedHistory.clear()
        metronome.start()  // We use the metronome's toneGen if public, or just start the metronome
        currentTimer?.cancel()
        currentTimer = object : android.os.CountDownTimer(interval.durationSeconds * 1000L, 100) {
            val totalTime = interval.durationSeconds * 1000L
            override fun onTick(millisUntilFinished: Long) {
                val progress = ((totalTime - millisUntilFinished).toFloat() / totalTime * 100).toInt()
                // UI Manager handles the text and progress bar updates
                uiManager.updateIntervalProgress(
                    progress = progress,
                    status = workoutManager?.getShortStatusString() ?: ""
                )
            }
            override fun onFinish() {
                uiManager.updateIntervalProgress(100, "Done")
                workoutManager?.nextInterval() // Move the state machine forward
                startNextInterval() // Loop to the next interval
            }
        }.start()
    }

    private fun stopSession() {
        // 1. Stop the State Machine (Timer)
        currentTimer?.cancel()
        currentTimer = null

        // 2. Stop the Audio Engine (Metronome)
        metronome.stop()

        // 3. Stop the Hardware (GPS)
        fusedLocationClient.removeLocationUpdates(locationCallback)

        // 4. Reset UI Elements
        uiManager.setRunningMode(false)
        uiManager.resetToDefaultState()
        Toast.makeText(this, "Workout Complete!", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        metronome.release()
    }

    override fun onPause() {
        super.onPause()
        // If the user leaves the app, we should probably stop the session
        // to save battery, unless you implement a Foreground Service later.
        stopSession()
    }
}