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
        // Κρατάμε την προηγούμενη τιμή
    }
}package com.example.predictdatafuel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlin.math.*

data class SensorDataPoint(
    val timestamp: Long,
    val accelerometerX: Float,
    val accelerometerY: Float,
    val accelerometerZ: Float,
    val magnetometerX: Float,         // Μαγνητόμετρο αντί για γυροσκόπιο
    val magnetometerY: Float,
    val magnetometerZ: Float,
    val compassHeading: Float,        // Μοίρες από τον Βορρά (0-360°)
    val latitude: Double,
    val longitude: Double,
    val speed: Float,                 // GPS ταχύτητα σε km/h
    val altitude: Double,
    val speedAccuracy: Float = 0f,    // Ακρίβεια ταχύτητας
    val bearing: Float = 0f           // Κατεύθυνση κίνησης από GPS
)

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    // UI ΣΤΟΙΧΕΙΑ
    private lateinit var tvStatus: TextView
    private lateinit var tvDataCount: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvFuelPrediction: TextView
    private lateinit var tvTripConsumption: TextView
    private lateinit var btnStartStop: Button

    // ΑΙΣΘΗΤΗΡΕΣ
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var accelerometer: Sensor? = null
    private var magnetometer: Sensor? = null              // Μαγνητόμετρο για πυξίδα

    // ΔΕΔΟΜΕΝΑ ΣΥΛΛΟΓΗΣ
    private var isCollecting = false
    private val tripData = mutableListOf<SensorDataPoint>()
    private var startTime = 0L

    // ΤΡΕΧΟΥΣΕΣ ΤΙΜΕΣ ΑΙΣΘΗΤΗΡΩΝ
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var magnetX = 0f                   // Μαγνητόμετρο
    private var magnetY = 0f
    private var magnetZ = 0f
    private var compassHeading = 0f            // Πυξίδα σε μοίρες (0° = Βορράς)
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentSpeed = 0f              // Ταχύτητα από GPS σε km/h
    private var currentAltitude = 0.0
    private var currentBearing = 0f            // Κατεύθυνση κίνησης
    private var speedAccuracy = 0f             // Ακρίβεια ταχύτητας
    private var hasGPSFix = false              // Κατάσταση GPS
    private var gpsUpdateCount = 0             // Μετρητής GPS updates

    // ΠΥΞΙΔΑ - Matrices για υπολογισμό προσανατολισμού
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private var hasAccelerometerData = false
    private var hasMagnetometerData = false

    // ML MODEL & API
    private lateinit var fuelPredictor: FuelPredictor
    private lateinit var apiClient: ApiClient

    // TRIP STATISTICS
    private var totalDistance = 0.0
    private var averageConsumption = 0.0
    private var previousLocation: Location? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initComponents()
        requestPermissions()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvDataCount = findViewById(R.id.tvDataCount)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvLocation = findViewById(R.id.tvLocation)
        tvFuelPrediction = findViewById(R.id.tvFuelPrediction)
        tvTripConsumption = findViewById(R.id.tvTripConsumption)
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
        // Αισθητήρες
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)  // Μαγνητόμετρο για πυξίδα

        // Έλεγχος διαθεσιμότητας αισθητήρων
        if (accelerometer == null) {
            tvStatus.text = "❌ Δεν υπάρχει επιταχυνσιόμετρο!"
        }
        if (magnetometer == null) {
            tvStatus.text = "❌ Δεν υπάρχει μαγνητόμετρο για πυξίδα!"
        }

        // ML Model - εκπαίδευση με το CSV
        fuelPredictor = FuelPredictor(this)

        // API Client για αποστολή δεδομένων
        apiClient = ApiClient()

        // Εκπαίδευση μοντέλου στο background
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

        // Έλεγχος διαθεσιμότητας αισθητήρων
        if (accelerometer == null) {
            tvStatus.text = "❌ Δεν υπάρχει επιταχυνσιόμετρο!"
            return
        }

        if (magnetometer == null) {
            tvStatus.text = "❌ Δεν υπάρχει μαγνητόμετρο για πυξίδα!"
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

        // Έναρξη GPS με βελτιωμένες ρυθμίσεις
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                // GPS Provider με υψηλή ακρίβεια για ταχύτητα
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        500,   // Κάθε 500ms για καλύτερη ταχύτητα
                        0.5f,  // Κάθε 0.5 μέτρο για ακρίβεια
                        this
                    )
                }

                // Network provider ως backup
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        2000,  // Λιγότερο συχνά για το network
                        5f,    // Μεγαλύτερη απόσταση
                        this
                    )
                }

                // Reset GPS counters
                gpsUpdateCount = 0
                hasGPSFix = false

            }
        } catch (e: SecurityException) {
            tvStatus.text = "❌ Σφάλμα GPS: ${e.message}"
        }

        startDataCollection()
    }

    private fun stopTrip() {
        isCollecting = false

        btnStartStop.text = "▶️ ΕΝΑΡΞΗ ΔΙΑΔΡΟΜΗΣ"
        tvStatus.text = "📊 Ανάλυση διαδρομής..."

        // Σταμάτημα αισθητήρων
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)

        // Αποστολή δεδομένων στη βάση
        if (tripData.isNotEmpty()) {
            CoroutineScope(Dispatchers.IO).launch {
                val success = apiClient.sendTripData(tripData, totalDistance, averageConsumption)
                withContext(Dispatchers.Main) {
                    if (success) {
                        tvStatus.text = "✅ Διαδρομή ολοκληρώθηκε και στάλθηκε!"
                    } else {
                        tvStatus.text = "⚠️ Διαδρομή ολοκληρώθηκε - πρόβλημα αποστολής"
                    }
                }
            }
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
            magnetometerX = magnetX,             // Μαγνητόμετρο
            magnetometerY = magnetY,
            magnetometerZ = magnetZ,
            compassHeading = compassHeading,     // Πυξίδα σε μοίρες
            latitude = currentLat,
            longitude = currentLon,
            speed = currentSpeed,                // GPS ταχύτητα
            altitude = currentAltitude,
            speedAccuracy = speedAccuracy,       // Ακρίβεια ταχύτητας
            bearing = currentBearing             // Κατεύθυνση
        )

        tripData.add(dataPoint)

        // Πρόβλεψη κατανάλωσης για αυτό το σημείο
        val instantConsumption = fuelPredictor.predictConsumption(dataPoint)

        // Ενημέρωση συνολικής κατανάλωσης διαδρομής
        if (tripData.size > 1) {
            updateTripStatistics()
        }

        runOnUiThread {
            // Ενημέρωση πρόβλεψης με χρωματισμό βάσει ταχύτητας και κατανάλωσης
            val speedStatus = when {
                !hasGPSFix -> "❌ ΧΩΡ2Σ GPS"
                currentSpeed < 1f -> "🛑 ΣΤΑΣΗ"
                currentSpeed < 20f -> "🚶 ΑΡΓΑ"
                currentSpeed < 50f -> "🚗 ΚΑΝΟΝΙΚΑ"
                currentSpeed < 90f -> "🏎️ ΓΡΗΓΟΡΑ"
                else -> "🚀 ΠΟΛΥ ΓΡΗΓΟΡΑ"
            }

            tvFuelPrediction.text = "⛽ ${String.format("%.1f", instantConsumption)} L/100km | $speedStatus"

            // Χρωματισμός βάσει κατανάλωσης
            val color = when {
                !hasGPSFix -> 0xFF757575.toInt()           // Γκρι - χωρίς GPS
                instantConsumption < 6f -> 0xFF4CAF50.toInt() // Πράσινο - οικονομικό
                instantConsumption < 9f -> 0xFFFF9800.toInt() // Πορτοκαλί - μέτριο
                instantConsumption < 12f -> 0xFFFF5722.toInt() // Κόκκινο - υψηλό
                else -> 0xFF9C27B0.toInt()                    // Μωβ - πολύ υψηλό
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

    private fun updateUI() {
        tvDataCount.text = "📊 Σημεία δεδομένων: ${tripData.size}"

        // Βελτιωμένη εμφάνιση ταχύτητας με στάτους
        val speedText = when {
            !hasGPSFix -> "🏃 Ταχύτητα: ❌ ΧΩΡΙΣ GPS"
            currentSpeed < 0.5f -> "🏃 Ταχύτητα: 🛑 ΣΤΑΣΗ (${String.format("%.1f", currentSpeed)} km/h)"
            speedAccuracy > 0 -> "🏃 Ταχύτητα: ${String.format("%.1f", currentSpeed)} km/h (±${String.format("%.1f", speedAccuracy * 3.6f)})"
            else -> "🏃 Ταχύτητα: ${String.format("%.1f", currentSpeed)} km/h"
        }
        tvSpeed.text = speedText

        // Χρωματισμός ταχύτητας
        val speedColor = when {
            !hasGPSFix -> 0xFFFF5722.toInt()      // Κόκκινο - χωρίς GPS
            currentSpeed < 1f -> 0xFF757575.toInt()  // Γκρι - στάση
            currentSpeed < 50f -> 0xFF4CAF50.toInt() // Πράσινο - κανονική
            currentSpeed < 90f -> 0xFFFF9800.toInt() // Πορτοκαλί - γρήγορη
            else -> 0xFFFF5722.toInt()               // Κόκκινο - πολύ γρήγορη
        }
        tvSpeed.setTextColor(speedColor)

        // Εμφάνιση θέσης με πυξίδα
        val compassDirection = getCompassDirection(compassHeading)
        tvLocation.text = "📍 ${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLon)}\n🧭 $compassDirection"
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

                    updateCompassHeading()
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    magnetX = it.values[0]
                    magnetY = it.values[1]
                    magnetZ = it.values[2]

                    // Αποθήκευση για υπολογισμό προσανατολισμού
                    System.arraycopy(it.values, 0, magnetometerReading, 0, magnetometerReading.size)
                    hasMagnetometerData = true

                    updateCompassHeading()
                }
            }
        }
    }

    /**
     * Υπολογισμός προσανατολισμού πυξίδας (0° = Βορράς)
     */
    private fun updateCompassHeading() {
        if (hasAccelerometerData && hasMagnetometerData) {
            // Υπολογισμός rotation matrix από επιταχυνσιόμετρο και μαγνητόμετρο
            val success = SensorManager.getRotationMatrix(
                rotationMatrix, null,
                accelerometerReading, magnetometerReading
            )

            if (success) {
                // Υπολογισμός προσανατολισμού
                SensorManager.getOrientation(rotationMatrix, orientationAngles)

                // Μετατροπή από radians σε degrees και κανονικοποίηση
                var azimuthInDegrees = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()

                // Κανονικοποίηση σε 0-360° (0° = Βορράς)
                compassHeading = if (azimuthInDegrees < 0) {
                    azimuthInDegrees + 360f
                } else {
                    azimuthInDegrees
                }

                // Εξομάλυνση για σταθερότητα (απλό φίλτρο)
                compassHeading = smoothCompassReading(compassHeading)
            }
        }
    }

    /**
     * Εξομάλυνση της ανάγνωσης πυξίδας για σταθερότητα
     */
    private var previousCompassHeading = -1f  // -1 σημαίνει μη αρχικοποιημένο

    private fun smoothCompassReading(newReading: Float): Float {
        val alpha = 0.3f // Παράγοντας εξομάλυνσης (0.0 = πλήρης εξομάλυνση, 1.0 = χωρίς εξομάλυνση)

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
                val accuracyText = when (accuracy) {
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "Υψηλή"
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "Μέτρια"
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "Χαμηλή"
                    else -> "Αναξιόπιστη"
                }
                if (isCollecting) {
                    runOnUiThread {
                        // Εμφάνιση προειδοποίησης αν η ακρίβεια είναι χαμηλή
                        if (accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE ||
                            accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW) {
                            tvStatus.text = "⚠️ Χαμηλή ακρίβεια πυξίδας - απομακρυνθείτε από μεταλλικά αντικείμενα"
                        }
                    }
                }
            }
        }
    }

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

    override fun onDestroy() {
        super.onDestroy()
        if (isCollecting) {
            stopTrip()
        }
    }
}