package com.example.predictdatafuel

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

// ΔΟΜΗ ΔΕΔΟΜΕΝΩΝ
data class SensorDataPoint(
    val timestamp: Long,
    val accelerometerX: Float,
    val accelerometerY: Float,
    val accelerometerZ: Float,
    val gyroscopeX: Float,
    val gyroscopeY: Float,
    val gyroscopeZ: Float,
    val latitude: Double,
    val longitude: Double,
    val speed: Float,
    val altitude: Double
)

class MainActivity : AppCompatActivity(), SensorEventListener, LocationListener {

    // UI ΣΤΟΙΧΕΙΑ
    private lateinit var tvStatus: TextView
    private lateinit var tvDataCount: TextView
    private lateinit var tvTime: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvLocation: TextView
    private lateinit var tvAltitude: TextView
    private lateinit var tvAccelerometer: TextView
    private lateinit var tvGyroscope: TextView
    private lateinit var btnStartStop: Button

    // ΑΙΣΘΗΤΗΡΕΣ
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null

    // ΔΕΔΟΜΕΝΑ
    private var isCollecting = false
    private val dataList = mutableListOf<SensorDataPoint>()
    private var startTime = 0L

    // ΤΡΕΧΟΥΣΕΣ ΤΙΜΕΣ ΑΙΣΘΗΤΗΡΩΝ
    private var accelX = 0f
    private var accelY = 0f
    private var accelZ = 0f
    private var gyroX = 0f
    private var gyroY = 0f
    private var gyroZ = 0f
    private var currentLat = 0.0
    private var currentLon = 0.0
    private var currentSpeed = 0f
    private var currentAltitude = 0.0

    // TIMER
    private val handler = Handler(Looper.getMainLooper())
    private var timeRunnable: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initSensors()
        setupUI()
        requestPermissions()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvStatus)
        tvDataCount = findViewById(R.id.tvDataCount)
        tvTime = findViewById(R.id.tvTime)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvLocation = findViewById(R.id.tvLocation)
        tvAltitude = findViewById(R.id.tvAltitude)
        tvAccelerometer = findViewById(R.id.tvAccelerometer)
        tvGyroscope = findViewById(R.id.tvGyroscope)
        btnStartStop = findViewById(R.id.btnStartStop)
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)

        // ΕΛΕΓΧΟΣ ΑΝ ΥΠΑΡΧΟΥΝ ΑΙΣΘΗΤΗΡΕΣ
        if (accelerometer == null) {
            tvStatus.text = "❌ Δεν βρέθηκε επιταχυνσιόμετρο!"
        }
        if (gyroscope == null) {
            tvGyroscope.text = "🌀 Γυροσκόπιο: Μη διαθέσιμο"
        }
    }

    private fun setupUI() {
        btnStartStop.setOnClickListener {
            if (!isCollecting) {
                startCollection()
            } else {
                stopCollection()
            }
        }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        val needPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needPermissions.toTypedArray(), 1001)
        }
    }

    private fun startCollection() {
        if (!checkPermissions()) {
            tvStatus.text = "❌ Χρειάζονται άδειες!"
            return
        }

        isCollecting = true
        startTime = System.currentTimeMillis()

        // UI ΑΛΛΑΓΕΣ
        btnStartStop.text = "⏹️ ΔΙΑΚΟΠΗ ΣΥΛΛΟΓΗΣ"
        tvStatus.text = "🟢 Συλλέγονται δεδομένα..."
        tvStatus.setBackgroundColor(0xFF7BFF7B.toInt())

        // ΕΝΑΡΞΗ ΑΙΣΘΗΤΗΡΩΝ
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        // ΕΝΑΡΞΗ GPS
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0.5f, this)
            }
        } catch (e: SecurityException) {
            tvStatus.text = "❌ Σφάλμα GPS: ${e.message}"
        }

        // ΕΝΑΡΞΗ TIMER & ΣΥΛΛΟΓΗΣ
        startTimer()
        startPeriodicCollection()
    }

    private fun stopCollection() {
        isCollecting = false

        // UI ΑΛΛΑΓΕΣ
        btnStartStop.text = "▶️ ΕΝΑΡΞΗ ΣΥΛΛΟΓΗΣ"
        tvStatus.text = "🔴 Αναμονή..."
        tvStatus.setBackgroundColor(0xFFFFECEC.toInt())

        // ΔΙΑΚΟΠΗ ΑΙΣΘΗΤΗΡΩΝ
        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)

        // ΔΙΑΚΟΠΗ TIMER
        timeRunnable?.let { handler.removeCallbacks(it) }

        tvStatus.text = "✅ Συλλέχθηκαν ${dataList.size} δεδομένα!"
    }

    private fun startTimer() {
        timeRunnable = object : Runnable {
            override fun run() {
                if (isCollecting) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    val minutes = elapsed / 60
                    val seconds = elapsed % 60
                    tvTime.text = "⏱️ Χρόνος: %02d:%02d".format(minutes, seconds)
                    handler.postDelayed(this, 1000)
                }
            }
        }
        handler.post(timeRunnable!!)
    }

    private fun startPeriodicCollection() {
        val collectRunnable = object : Runnable {
            override fun run() {
                if (isCollecting) {
                    collectDataPoint()
                    updateUI()
                    handler.postDelayed(this, 200) // ΚΑΘΕ 200ms = 5 ΦΟΡΕΣ ΤΟ ΔΕΥΤΕΡΟΛΕΠΤΟ
                }
            }
        }
        handler.post(collectRunnable)
    }

    private fun collectDataPoint() {
        val dataPoint = SensorDataPoint(
            timestamp = System.currentTimeMillis(),
            accelerometerX = accelX,
            accelerometerY = accelY,
            accelerometerZ = accelZ,
            gyroscopeX = gyroX,
            gyroscopeY = gyroY,
            gyroscopeZ = gyroZ,
            latitude = currentLat,
            longitude = currentLon,
            speed = currentSpeed,
            altitude = currentAltitude
        )

        dataList.add(dataPoint)
    }

    private fun updateUI() {
        tvDataCount.text = "📦 Δεδομένα: ${dataList.size}"
        tvSpeed.text = "🏃 Ταχύτητα: %.1f km/h".format(currentSpeed)
        tvLocation.text = "📍 Θέση: %.6f, %.6f".format(currentLat, currentLon)
        tvAltitude.text = "🏔️ Υψόμετρο: %.1f m".format(currentAltitude)
        tvAccelerometer.text = "📈 Επιταχυνσιόμετρο: %.2f, %.2f, %.2f".format(accelX, accelY, accelZ)
        tvGyroscope.text = "🌀 Γυροσκόπιο: %.2f, %.2f, %.2f".format(gyroX, gyroY, gyroZ)
    }

    // ΑΙΣΘΗΤΗΡΕΣ CALLBACK
    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelX = it.values[0]
                    accelY = it.values[1]
                    accelZ = it.values[2]
                }
                Sensor.TYPE_GYROSCOPE -> {
                    gyroX = it.values[0]
                    gyroY = it.values[1]
                    gyroZ = it.values[2]
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // GPS CALLBACK
    override fun onLocationChanged(location: Location) {
        currentLat = location.latitude
        currentLon = location.longitude
        currentSpeed = location.speed * 3.6f // m/s -> km/h
        currentAltitude = location.altitude
    }

    private fun checkPermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isCollecting) {
            stopCollection()
        }
    }
}