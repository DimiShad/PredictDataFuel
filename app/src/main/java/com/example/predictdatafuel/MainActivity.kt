package com.example.predictdatafuel

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import kotlin.math.*

data class SensorDataPoint(
    val timestamp: Long,
    val accelerometerX: Float,
    val accelerometerY: Float,
    val accelerometerZ: Float,
    val magnetometerX: Float,
    val magnetometerY: Float,
    val magnetometerZ: Float,
    val compassHeading: Float,
    val latitude: Double,
    val longitude: Double,
    val speed: Float,
    val altitude: Double,
    val speedAccuracy: Float = 0f,
    val bearing: Float = 0f
)

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    // UI ΣΤΟΙΧΕΙΑ
    private lateinit var progressBar: ProgressBar
    private lateinit var tvStatus: TextView
    private lateinit var tvDataCount: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvFuelPrediction: TextView
    private lateinit var tvTripConsumption: TextView
    private lateinit var tvAcceleration: TextView
    private lateinit var tvCompass: TextView
    private lateinit var btnStartStop: Button

    // ΑΙΣΘΗΤΗΡΕΣ & LOCATION
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null

    // ΔΕΔΟΜΕΝΑ ΣΥΛΛΟΓΗΣ
    private var isCollecting = false
    private val tripData = mutableListOf<SensorDataPoint>()
    private var startTime = 0L

    // ΤΡΕΧΟΥΣΕΣ ΤΙΜΕΣ ΑΙΣΘΗΤΗΡΩΝ
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var magnetX = 0f
    private var magnetY = 0f
    private var magnetZ = 0f
    private var compassHeading = 0f
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentSpeed = 0f
    private var currentAltitude = 0.0
    private var currentBearing = 0f
    private var speedAccuracy = 0f
    private var hasGPSFix = false
    private var gpsUpdateCount = 0

    // ΠΥΞΙΔΑ - Matrices για υπολογισμό προσανατολισμού
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private var hasAccelerometerData = false
    private var hasMagnetometerData = false

    // ML MODEL & TRIP STATISTICS
    private lateinit var fuelPredictor: FuelPredictor
    private var totalDistance = 0.0
    private var averageConsumption = 0.0
    private var previousLocation: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Explicit cast για να λύσουμε το ambiguity
        setContentView(R.layout.activity_main as Int)

        initViews()
        initComponents()
        requestPermissions()

        // Φόρτωση τελευταίων δεδομένων από API
        loadLatestDataFromAPI()
    }

    private fun initViews() {
        progressBar = findViewById(R.id.progressBar)
        tvStatus = findViewById(R.id.tvStatus)
        tvDataCount = findViewById(R.id.tvDataCount)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvLocation = findViewById(R.id.tvLocation)
        tvFuelPrediction = findViewById(R.id.tvFuelPrediction)
        tvTripConsumption = findViewById(R.id.tvTripConsumption)
        tvAcceleration = findViewById(R.id.tvAcceleration)
        tvCompass = findViewById(R.id.tvCompass)
        btnStartStop = findViewById(R.id.btnStartStop)

        btnStartStop.setOnClickListener {
            if (!isCollecting) {
                startTrip()
            } else {
                stopTrip()
            }
        }
    }

    private fun initComponents() {
        // Location Services
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Αισθητήρες
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

        // Έλεγχος διαθεσιμότητας αισθητήρων
        if (accelerometer == null) {
            tvStatus.text = "❌ Δεν υπάρχει επιταχυνσιόμετρο!"
        }
        if (magnetometer == null) {
            tvStatus.text = "❌ Δεν υπάρχει μαγνητόμετρο για πυξίδα!"
        }

        // ML Model - εκπαίδευση με το CSV στο background
        fuelPredictor = FuelPredictor(this)
        CoroutineScope(Dispatchers.IO).launch {
            val success = fuelPredictor.trainFromCSV()
            withContext(Dispatchers.Main) {
                if (success) {
                    tvStatus.text = "✅ Μοντέλο εκπαιδευμένο και έτοιμο!"
                } else {
                    tvStatus.text = "⚠️ Πρόβλημα με την εκπαίδευση - χρήση βασικού μοντέλου"
                }
            }
        }
    }

    private fun startTrip() {
        if (!checkPermissions()) {
            tvStatus.text = "❌ Χρειάζονται άδειες GPS!"
            return
        }

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            tvStatus.text = "❌ Ενεργοποιήστε το GPS!"
            return
        }

        if (accelerometer == null || magnetometer == null) {
            tvStatus.text = "❌ Απαραίτητοι αισθητήρες δεν είναι διαθέσιμοι!"
            return
        }

        isCollecting = true
        startTime = System.currentTimeMillis()
        tripData.clear()
        totalDistance = 0.0
        previousLocation = null

        btnStartStop.text = "⏹️ ΤΕΡΜΑΤΙΣΜΟΣ ΔΙΑΔΡΟΜΗΣ"
        tvStatus.text = "🚗 Διαδρομή σε εξέλιξη..."

        // Έναρξη αισθητήρων
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // Έναρξη GPS
        startLocationUpdates()
        startDataCollection()
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        try {
            // GPS Provider με υψηλή ακρίβεια
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    500,   // Κάθε 500ms
                    0.5f,  // Κάθε 0.5 μέτρο
                    this
                )
            }

            // Network provider ως backup
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    2000,  // Λιγότερο συχνά
                    5f,    // Μεγαλύτερη απόσταση
                    this
                )
            }

            // Reset GPS counters
            gpsUpdateCount = 0
            hasGPSFix = false

        } catch (e: SecurityException) {
            tvStatus.text = "❌ Σφάλμα GPS: ${e.message}"
        }
    }

    private fun stopTrip() {
        isCollecting = false

        btnStartStop.text = "▶️ ΕΝΑΡΞΗ ΔΙΑΔΡΟΜΗΣ"
        tvStatus.text = "📊 Ανάλυση διαδρομής και αποστολή δεδομένων..."

        // Σταμάτημα αισθητήρων
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)

        // Υπολογισμός τελικής κατανάλωσης και αποστολή στο API
        if (tripData.isNotEmpty()) {
            calculateTripConsumptionAndSend()
        }
    }

    private fun startDataCollection() {
        val collectRunnable = object : Runnable {
            override fun run() {
                if (isCollecting) {
                    collectAndAnalyzeData()
                    updateUI()
                    Handler(Looper.getMainLooper()).postDelayed(this, 1000) // Κάθε δευτερόλεπτο
                }
            }
        }
        Handler(Looper.getMainLooper()).post(collectRunnable)
    }

    private fun collectAndAnalyzeData() {
        val dataPoint = SensorDataPoint(
            timestamp = System.currentTimeMillis(),
            accelerometerX = accelX,
            accelerometerY = accelY,
            accelerometerZ = accelZ,
            magnetometerX = magnetX,
            magnetometerY = magnetY,
            magnetometerZ = magnetZ,
            compassHeading = compassHeading,
            latitude = currentLat,
            longitude = currentLon,
            speed = currentSpeed,
            altitude = currentAltitude,
            speedAccuracy = speedAccuracy,
            bearing = currentBearing
        )

        tripData.add(dataPoint)

        // Πρόβλεψη κατανάλωσης για αυτό το σημείο
        val instantConsumption = fuelPredictor.predictConsumption(dataPoint)

        // ΜΟΝΟ ΑΥΤΗ Η ΓΡΑΜΜΗ - στέλνει τα δεδομένα στο Flask
        SimpleRealtimeSender.sendNow(dataPoint, instantConsumption)

        // Ενημέρωση συνολικής κατανάλωσης διαδρομής
        if (tripData.size > 1) {
            updateTripStatistics()
        }

        runOnUiThread {
            // Ενημέρωση πρόβλεψης
            val speedStatus = when {
                !hasGPSFix -> "❌ ΧΩΡΙΣ GPS"
                currentSpeed < 1f -> "🛑 ΣΤΑΣΗ"
                currentSpeed < 15f && instantConsumption > 8f -> "⛰️ ΑΝΗΦΟΡΑ/ΠΡΩΤΗ"
                currentSpeed < 20f -> "🚶 ΑΣΤΙΚΗ"
                currentSpeed < 50f -> "🚗 ΚΑΝΟΝΙΚΑ"
                currentSpeed < 90f -> "🏎️ ΓΡΗΓΟΡΑ"
                instantConsumption > 8f -> "🔥 EXTREME STRESS"
                else -> "🚀 ΠΟΛΥ ΓΡΗΓΟΡΑ"
            }

            tvFuelPrediction.text = "⛽ ${String.format("%.1f", instantConsumption)} L/100km | $speedStatus"

            // Χρωματισμός βάσει κατανάλωσης
            val color = when {
                !hasGPSFix -> 0xFF757575.toInt()
                instantConsumption < 4.0f -> 0xFF4CAF50.toInt()
                instantConsumption < 5.5f -> 0xFF8BC34A.toInt()
                instantConsumption < 7.0f -> 0xFFFF9800.toInt()
                instantConsumption < 9.0f -> 0xFFFF5722.toInt()
                else -> 0xFF9C27B0.toInt()
            }
            tvFuelPrediction.setTextColor(color)
        }
    }

    private fun updateTripStatistics() {
        // Υπολογισμός μέσης κατανάλωσης διαδρομής
        val consumptions = tripData.takeLast(10).map {
            fuelPredictor.predictConsumption(it)
        }
        averageConsumption = consumptions.average()

        // Εκτίμηση συνολικής κατανάλωσης
        val estimatedFuelUsed = (totalDistance / 100.0) * averageConsumption

        runOnUiThread {
            tvTripConsumption.text = """
                📍 Απόσταση: ${String.format("%.1f", totalDistance)} km
                ⛽ Μέση κατανάλωση: ${String.format("%.1f", averageConsumption)} L/100km
                🔋 Εκτιμώμενη χρήση: ${String.format("%.2f", estimatedFuelUsed)} L
            """.trimIndent()
        }
    }

    private fun calculateTripConsumptionAndSend() {
        if (tripData.isEmpty()) {
            tvStatus.text = "❌ Δεν υπάρχουν δεδομένα διαδρομής!"
            return
        }

        showLoading()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Υπολογισμός τελικής κατανάλωσης με βάση όλη τη διαδρομή
                val tripConsumptions = tripData.map { fuelPredictor.predictConsumption(it) }
                val finalAverageConsumption = tripConsumptions.average()
                val totalFuelUsed = (totalDistance / 100.0) * finalAverageConsumption

                // Δημιουργία δεδομένων για αποστολή στο API
                val tripSummary = TripSummaryData(
                    nickname = "FuelPredictApp",
                    totalDistance = totalDistance,
                    averageSpeed = if (tripData.isNotEmpty()) tripData.map { it.speed }.average() else 0.0,
                    maxSpeed = tripData.maxOfOrNull { it.speed }?.toDouble() ?: 0.0,
                    averageConsumption = finalAverageConsumption,
                    totalFuelUsed = totalFuelUsed,
                    duration = (tripData.lastOrNull()?.timestamp ?: 0L) - (tripData.firstOrNull()?.timestamp ?: 0L),
                    startLat = tripData.firstOrNull()?.latitude ?: 0.0,
                    startLon = tripData.firstOrNull()?.longitude ?: 0.0,
                    endLat = tripData.lastOrNull()?.latitude ?: 0.0,
                    endLon = tripData.lastOrNull()?.longitude ?: 0.0,
                    dataPoints = tripData.size,
                    timestamp = System.currentTimeMillis()
                )

                // Αποστολή στο API
                sendTripDataToAPI(tripSummary)

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    hideLoading()
                    tvStatus.text = "❌ Σφάλμα υπολογισμού: ${e.message}"
                    Log.e("MainActivity", "Calculation error", e)
                }
            }
        }
    }

    private suspend fun sendTripDataToAPI(tripSummary: TripSummaryData) {
        try {
            withContext(Dispatchers.Main) {
                showLoading()
            }

            // LOG τα δεδομένα πριν τα στείλεις
            Log.d("SendData", "=== SENDING TO FLASK API ===")
            Log.d("SendData", "Vehicle: ${tripSummary.nickname}")
            Log.d("SendData", "Distance: ${tripSummary.totalDistance} km")
            Log.d("SendData", "Avg Speed: ${tripSummary.averageSpeed} km/h")
            Log.d("SendData", "Avg Consumption: ${tripSummary.averageConsumption} L/100km")
            Log.d("SendData", "Start Location: ${tripSummary.startLat}, ${tripSummary.startLon}")
            Log.d("SendData", "End Location: ${tripSummary.endLat}, ${tripSummary.endLon}")
            Log.d("SendData", "Data Points: ${tripSummary.dataPoints}")
            Log.d("SendData", "Timestamp: ${tripSummary.timestamp}")

            // Στέλνουμε στο Flask API μόνο
            withContext(Dispatchers.IO) {
                FlaskOnlyClient.sendTripToFlask(
                    tripSummary = tripSummary,
                    onSuccess = { flaskResponse ->
                        runOnUiThread {
                            hideLoading()
                            Log.d("SendData", "✅ SUCCESS - Flask Response: ${flaskResponse.message}")

                            tvStatus.text = """
                        ✅ Διαδρομή αποθηκεύτηκε επιτυχώς στο Flask!
                        
                        📊 Αποθηκευμένα στοιχεία:
                        🚗 Όχημα: ${tripSummary.nickname}
                        📍 Απόσταση: ${String.format("%.2f", tripSummary.totalDistance)} km
                        ⛽ Μέση κατανάλωση: ${String.format("%.2f", tripSummary.averageConsumption)} L/100km
                        🏃 Μέση ταχύτητα: ${String.format("%.1f", tripSummary.averageSpeed)} km/h
                        📍 Από: ${String.format("%.6f, %.6f", tripSummary.startLat, tripSummary.startLon)}
                        📍 Έως: ${String.format("%.6f, %.6f", tripSummary.endLat, tripSummary.endLon)}
                        📊 Σημεία δεδομένων: ${tripSummary.dataPoints}
                        📁 Flask Message: ${flaskResponse.message}
                    """.trimIndent()

                            // Εμφάνιση Toast για confirmation
                            Toast.makeText(this@MainActivity, "✅ Δεδομένα στάλθηκαν στο Flask!", Toast.LENGTH_LONG).show()
                        }
                    },
                    onError = { errorMessage ->
                        runOnUiThread {
                            hideLoading()
                            Log.e("SendData", "❌ FAILED - $errorMessage")

                            tvStatus.text = "❌ Σφάλμα αποστολής στο Flask:\n$errorMessage"

                            // Εμφάνιση Toast για error
                            Toast.makeText(this@MainActivity, "❌ Αποτυχία αποστολής!", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                hideLoading()
                tvStatus.text = "❌ Σφάλμα: ${e.message}"
                Log.e("MainActivity", "API call failed", e)
            }
        }
    }

    // Φόρτωση τελευταίων δεδομένων από API (όπως στην άλλη εφαρμογή)
    private fun loadLatestDataFromAPI() {
        showLoading()

        // Χρησιμοποιούμε το FlaskOnlyClient αντί για το παλιό RetrofitClient
        FlaskOnlyClient.api.getAverageFuelConsumption().enqueue(object : Callback<FuelConsumptionResponse> {
            @SuppressLint("SetTextI18n")
            override fun onResponse(call: Call<FuelConsumptionResponse>, response: Response<FuelConsumptionResponse>) {
                hideLoading()
                if (response.isSuccessful) {
                    val fuelData = response.body()?.api_response?.data?.firstOrNull()
                    fuelData?.let {
                        // Εμφάνιση τελευταίων δεδομένων από Flask
                        tvStatus.text = """
                        📊 Τελευταία δεδομένα από Flask API:
                        🚗 Όχημα: ${it.nickname}
                        🏃 Ταχύτητα: ${it.speed} km/h
                        ⛽ Καύσιμα: ${it.fuel_lt} L
                        📍 Θέση: ${String.format("%.6f", it.lat)}, ${String.format("%.6f", it.lon)}
                        🏔️ Υψόμετρο: ${it.alt}m
                        ⏰ Χρόνος: ${it.time}
                        
                        🎯 Εφαρμογή συνδεδεμένη με Flask API μόνο!
                    """.trimIndent()

                        Log.d("MainActivity", "Flask API connected successfully")
                        Toast.makeText(this@MainActivity, "✅ Συνδέθηκε με Flask API", Toast.LENGTH_SHORT).show()
                    } ?: run {
                        tvStatus.text = "📊 Flask API συνδέθηκε, αλλά δεν υπάρχουν δεδομένα ακόμα"
                        Toast.makeText(this@MainActivity, "Flask API OK - No data yet", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    tvStatus.text = "⚠️ Πρόβλημα φόρτωσης από Flask: ${response.code()}"
                    Log.e("MainActivity", "Flask API Error: ${response.code()}")
                    Toast.makeText(this@MainActivity, "❌ Flask API Error: ${response.code()}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onFailure(call: Call<FuelConsumptionResponse>, t: Throwable) {
                hideLoading()
                tvStatus.text = "❌ Σφάλμα σύνδεσης με Flask: ${t.message}"
                Log.e("MainActivity", "Flask API Failure", t)
                Toast.makeText(this@MainActivity, "❌ Δεν μπορεί να συνδεθεί στο Flask", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun updateUI() {
        tvDataCount.text = "📊 Σημεία δεδομένων: ${tripData.size}"

        // Εμφάνιση ταχύτητας
        val speedText = when {
            !hasGPSFix -> "🏃 Ταχύτητα: ❌ ΧΩΡΙΣ GPS"
            currentSpeed < 0.5f -> "🏃 Ταχύτητα: 🛑 ΣΤΑΣΗ (${String.format("%.1f", currentSpeed)} km/h)"
            speedAccuracy > 0 -> "🏃 Ταχύτητα: ${String.format("%.1f", currentSpeed)} km/h (±${String.format("%.1f", speedAccuracy * 3.6f)})"
            else -> "🏃 Ταχύτητα: ${String.format("%.1f", currentSpeed)} km/h"
        }
        tvSpeed.text = speedText

        // Εμφάνιση θέσης με πυξίδα
        val compassDirection = getCompassDirection(compassHeading)
        tvLocation.text = "📍 ${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLon)}\n🧭 $compassDirection"

        // Εμφάνιση επιταχυνσιόμετρου
        tvAcceleration.text = "📈 Επιταχυνσιόμετρο: ${String.format("%.2f, %.2f, %.2f", accelX, accelY, accelZ)}"

        // Εμφάνιση μαγνητόμετρου
        tvCompass.text = "🧭 Πυξίδα: ${String.format("%.1f", compassHeading)}°"
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelX = it.values[0]
                    accelY = it.values[1]
                    accelZ = it.values[2]

                    // Αποθήκευση για υπολογισμό προσανατολισμού
                    System.arraycopy(it.values, 0, accelerometerReading, 0, accelerometerReading.size)
                    hasAccelerometerData = true

                    // Υπολογισμός πυξίδας μόνο αν έχουμε και τα δύο δεδομένα
                    if (hasMagnetometerData) {
                        updateCompassHeading()
                    }
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magnetX = it.values[0]
                    magnetY = it.values[1]
                    magnetZ = it.values[2]

                    // Αποθήκευση για υπολογισμό προσανατολισμού
                    System.arraycopy(it.values, 0, magnetometerReading, 0, magnetometerReading.size)
                    hasMagnetometerData = true

                    // Υπολογισμός πυξίδας μόνο αν έχουμε και τα δύο δεδομένα
                    if (hasAccelerometerData) {
                        updateCompassHeading()
                    }
                }
            }
        }
    }

    /**
     * Υπολογισμός προσανατολισμού πυξίδας (0° = Βορράς)
     */
    private fun updateCompassHeading() {
        try {
            // Υπολογισμός rotation matrix από επιταχυνσιόμετρο και μαγνητόμετρο
            val success = SensorManager.getRotationMatrix(
                rotationMatrix, null,
                accelerometerReading, magnetometerReading
            )

            if (success) {
                // Υπολογισμός προσανατολισμού
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                // Μετατροπή από radians σε degrees
                var azimuthInDegrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

                // Κανονικοποίηση σε 0-360° (0° = Βορράς)
                azimuthInDegrees = when {
                    azimuthInDegrees < 0 -> azimuthInDegrees + 360f
                    azimuthInDegrees >= 360f -> azimuthInDegrees - 360f
                    else -> azimuthInDegrees
                }

                // Εξομάλυνση για σταθερότητα
                compassHeading = smoothCompassReading(azimuthInDegrees)
            }
        } catch (e: Exception) {
            // Χειρισμός σφάλματος στον υπολογισμό πυξίδας
            Log.e("MainActivity", "Compass calculation error", e)
        }
    }

    /**
     * Εξομάλυνση της ανάγνωσης πυξίδας για σταθερότητα
     */
    private var previousCompassHeading = -1f  // -1 σημαίνει μη αρχικοποιημένο

    private fun smoothCompassReading(newReading: Float): Float {
        val alpha = 0.3f // Παράγοντας εξομάλυνσης

        return if (previousCompassHeading < 0f) {
            // Πρώτη ανάγνωση
            previousCompassHeading = newReading
            newReading
        } else {
            // Χειρισμός του προβλήματος με το wrap-around στους 360°/0°
            var diff = newReading - previousCompassHeading
            if (diff > 180f) diff -= 360f
            if (diff < -180f) diff += 360f

            var smoothed = previousCompassHeading + alpha * diff

            // Κανονικοποίηση στο εύρος 0-360°
            if (smoothed < 0f) smoothed += 360f
            if (smoothed >= 360f) smoothed -= 360f

            previousCompassHeading = smoothed
            smoothed
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onLocationChanged(location: Location) {
        gpsUpdateCount++
        hasGPSFix = true

        currentLat = location.latitude
        currentLon = location.longitude

        // ΒΕΛΤΙΩΜΕΝΗ ΔΙΑΧΕΙΡΙΣΗ ΤΑΧΥΤΗΤΑΣ
        if (location.hasSpeed()) {
            currentSpeed = location.speed * 3.6f // m/s to km/h
            speedAccuracy = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else 0f
        } else {
            // Αν δεν έχουμε άμεση ταχύτητα, υπολογίζουμε από την απόσταση
            previousLocation?.let { prevLoc ->
                val timeDiff = (location.time - prevLoc.time) / 1000.0 // seconds
                if (timeDiff > 0.5) { // Τουλάχιστον 0.5 δευτερόλεπτα διαφορά
                    val distance = location.distanceTo(prevLoc) // meters
                    currentSpeed = ((distance / timeDiff) * 3.6).toFloat() // km/h
                }
            }
        }

        currentAltitude = location.altitude

        // Κατεύθυνση κίνησης
        if (location.hasBearing()) {
            currentBearing = location.bearing
        }

        // Υπολογισμός απόστασης διαδρομής
        previousLocation?.let { prevLoc ->
            val distance = location.distanceTo(prevLoc) / 1000.0 // σε km
            if (distance > 0.001) { // Ελάχιστη απόσταση για αποφυγή noise
                totalDistance += distance
            }
        }
        previousLocation = location

        // Debug info για GPS
        if (isCollecting && gpsUpdateCount % 5 == 0) { // Κάθε 5 updates
            runOnUiThread {
                updateGPSStatus()
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // Χειρισμός αλλαγής ακρίβειας αισθητήρων
        when (sensor?.type) {
            Sensor.TYPE_MAGNETIC_FIELD -> {
                // Ενημέρωση για την ακρίβεια του μαγνητόμετρου
                if (isCollecting && (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                            accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW)) {
                    runOnUiThread {
                        tvStatus.text = "⚠️ Χαμηλή ακρίβεια πυξίδας - απομακρυνθείτε από μεταλλικά αντικείμενα"
                    }
                }
            }
        }
    }

    /**
     * Μετατροπή μοιρών σε κατεύθυνση πυξίδας
     */
    private fun getCompassDirection(degrees: Float): String {
        val normalizedDegrees = ((degrees % 360 + 360) % 360) // Κανονικοποίηση 0-360°

        return when {
            normalizedDegrees < 11.25f || normalizedDegrees >= 348.75f -> "Β ${String.format("%.0f", normalizedDegrees)}°"      // Βορράς
            normalizedDegrees < 33.75f -> "ΒΒΑ ${String.format("%.0f", normalizedDegrees)}°"   // Βορρά-Βορειοανατολικά
            normalizedDegrees < 56.25f -> "ΒΑ ${String.format("%.0f", normalizedDegrees)}°"    // Βορειοανατολικά
            normalizedDegrees < 78.75f -> "ΑΒΑ ${String.format("%.0f", normalizedDegrees)}°"   // Ανατολικά-Βορειοανατολικά
            normalizedDegrees < 101.25f -> "Α ${String.format("%.0f", normalizedDegrees)}°"     // Ανατολικά
            normalizedDegrees < 123.75f -> "ΑΝΑ ${String.format("%.0f", normalizedDegrees)}°"  // Ανατολικά-Νοτιοανατολικά
            normalizedDegrees < 146.25f -> "ΝΑ ${String.format("%.0f", normalizedDegrees)}°"   // Νοτιοανατολικά
            normalizedDegrees < 168.75f -> "ΝΝΑ ${String.format("%.0f", normalizedDegrees)}°"  // Νότια-Νοτιοανατολικά
            normalizedDegrees < 191.25f -> "Ν ${String.format("%.0f", normalizedDegrees)}°"     // Νότια
            normalizedDegrees < 213.75f -> "ΝΝΔ ${String.format("%.0f", normalizedDegrees)}°"  // Νότια-Νοτιοδυτικά
            normalizedDegrees < 236.25f -> "ΝΔ ${String.format("%.0f", normalizedDegrees)}°"   // Νοτιοδυτικά
            normalizedDegrees < 258.75f -> "ΔΝΔ ${String.format("%.0f", normalizedDegrees)}°"  // Δυτικά-Νοτιοδυτικά
            normalizedDegrees < 281.25f -> "Δ ${String.format("%.0f", normalizedDegrees)}°"     // Δυτικά
            normalizedDegrees < 303.75f -> "ΔΒΔ ${String.format("%.0f", normalizedDegrees)}°"  // Δυτικά-Βορειοδυτικά
            normalizedDegrees < 326.25f -> "ΒΔ ${String.format("%.0f", normalizedDegrees)}°"   // Βορειοδυτικά
            else -> "ΒΒΔ ${String.format("%.0f", normalizedDegrees)}°"                          // Βορρά-Βορειοδυτικά
        }
    }

    private fun updateGPSStatus() {
        val gpsStatus = when {
            !hasGPSFix -> "❌ ΧΩΡΙΣ GPS ΣΗΜΑ"
            gpsUpdateCount < 3 -> "🟡 GPS ΑΝΑΖΗΤΗΣΗ... (${gpsUpdateCount}/3)"
            currentSpeed < 0.5f -> "🟢 GPS ΕΝΕΡΓΟ - ΣΤΑΘΜΕΥΜΕΝΟ"
            else -> "🟢 GPS ΕΝΕΡΓΟ - ΣΕ ΚΙΝΗΣΗ"
        }

        if (isCollecting) {
            tvStatus.text = "🚗 Διαδρομή σε εξέλιξη | $gpsStatus"
        }
    }

    // UI Helper Methods
    private fun showLoading() {
        progressBar.visibility = View.VISIBLE
        btnStartStop.isEnabled = false
    }

    private fun hideLoading() {
        progressBar.visibility = View.GONE
        btnStartStop.isEnabled = true
    }

    // Permissions
    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.INTERNET
        )

        val needPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needPermissions.toTypedArray(), 1001)
        }
    }

    private fun checkPermissions(): Boolean {
        val fineLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarseLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        return fineLocation && coarseLocation
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadLatestDataFromAPI()
            } else {
                tvStatus.text = "❌ Απαιτούνται άδειες GPS για τη λειτουργία της εφαρμογής!"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isCollecting) {
            sensorManager.unregisterListener(this)
            locationManager.removeUpdates(this)
        }
    }
}