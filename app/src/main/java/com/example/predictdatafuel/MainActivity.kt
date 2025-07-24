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
import kotlin.math.*

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
    private lateinit var btnExportCSV: Button
    private lateinit var btnLoadCSV: Button
    private lateinit var btnClearData: Button
    private lateinit var tvFileStatus: TextView
    private lateinit var btnTrainModel: Button
    private lateinit var btnModelInfo: Button
    private lateinit var tvCurrentPrediction: TextView
    private lateinit var tvModelStatus: TextView
    private lateinit var btnLoadRealData: Button
    private lateinit var btnAnalyzeData: Button
    private lateinit var btnCalculateConsumption: Button
    private lateinit var btnResetModel: Button

    // ΑΙΣΘΗΤΗΡΕΣ
    private lateinit var sensorManager: SensorManager
    private lateinit var locationManager: LocationManager
    private var accelerometer: Sensor? = null
    private var gyroscope: Sensor? = null
    private var magnetometer: Sensor? = null

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

    // GPS DIAGNOSTICS
    private var gpsFixTime = 0L
    private var gpsLocationCount = 0
    private var lastLocationTime = 0L
    private var gpsAccuracy = 0f

    // ORIENTATION CALCULATION
    private val rotationMatrix = FloatArray(9)
    private val orientationAngles = FloatArray(3)
    private val accelerometerReading = FloatArray(3)
    private val magnetometerReading = FloatArray(3)
    private var currentAzimuth = 0f

    // TIMER
    private val handler = Handler(Looper.getMainLooper())
    private var timeRunnable: Runnable? = null

    // CSV MANAGER, ML MODEL, REAL DATA LOADER
    private lateinit var csvManager: CSVManager
    private lateinit var mlModel: FuelPredictionModel
    private lateinit var realDataLoader: RealDataLoader
    private var realTrainingData = listOf<FuelPredictionModel.TrainingDataPoint>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        initSensors()
        initCSVManager()
        initMLModel()
        setupUI()
        requestPermissions()
        updateFileStatus()
        updateModelStatus()

        // GPS DIAGNOSTICS
        showGPSStatus()
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
        btnExportCSV = findViewById(R.id.btnExportCSV)
        btnLoadCSV = findViewById(R.id.btnLoadCSV)
        btnClearData = findViewById(R.id.btnClearData)
        tvFileStatus = findViewById(R.id.tvFileStatus)
        btnTrainModel = findViewById(R.id.btnTrainModel)
        btnModelInfo = findViewById(R.id.btnModelInfo)
        tvCurrentPrediction = findViewById(R.id.tvCurrentPrediction)
        tvModelStatus = findViewById(R.id.tvModelStatus)
        btnLoadRealData = findViewById(R.id.btnLoadRealData)
        btnAnalyzeData = findViewById(R.id.btnAnalyzeData)
        btnCalculateConsumption = findViewById(R.id.btnCalculateConsumption)
        btnResetModel = findViewById(R.id.btnResetModel)
    }

    private fun initSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        gyroscope = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        magnetometer = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)
    }

    private fun initCSVManager() {
        csvManager = CSVManager(this)
    }

    private fun initMLModel() {
        mlModel = FuelPredictionModel(this)
        realDataLoader = RealDataLoader(this)
    }

    private fun showGPSStatus() {
        val gpsEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)
        val networkEnabled = locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
        val hasPermissions = checkPermissions()

        val status = """
            📍 GPS ΔΙΑΓΝΩΣΤΙΚΑ:
            • GPS Provider: ${if (gpsEnabled) "✅ Ενεργό" else "❌ Απενεργοποιημένο"}
            • Network Provider: ${if (networkEnabled) "✅ Ενεργό" else "❌ Απενεργοποιημένο"}
            • Άδειες: ${if (hasPermissions) "✅ Εντάξει" else "❌ Απαιτούνται"}
            
            💡 Για να λειτουργήσει η ταχύτητα:
            1. Βγες ΕΞΩΤΕΡΙΚΑ (όχι σε κτίριο)
            2. Περίμενε 1-2 λεπτά για GPS lock
            3. Κινήσου με >5 km/h
        """.trimIndent()

        tvStatus.text = status
    }

    private fun setupUI() {
        btnStartStop.setOnClickListener {
            if (!isCollecting) {
                startCollection()
            } else {
                stopCollection()
            }
        }

        btnExportCSV.setOnClickListener { exportData() }
        btnLoadCSV.setOnClickListener { loadData() }
        btnClearData.setOnClickListener { clearAllData() }
        btnTrainModel.setOnClickListener { trainMLModel() }
        btnModelInfo.setOnClickListener { showModelInfo() }
        btnLoadRealData.setOnClickListener { loadRealDataset() }
        btnAnalyzeData.setOnClickListener { analyzeRealData() }
        btnCalculateConsumption.setOnClickListener { calculateRealConsumption() }
        btnResetModel.setOnClickListener { resetMLModel() }
    }

    private fun requestPermissions() {
        val permissions = arrayOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.WRITE_EXTERNAL_STORAGE,
            Manifest.permission.READ_EXTERNAL_STORAGE
        )

        val needPermissions = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needPermissions.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needPermissions.toTypedArray(), 1001)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001) {
            showGPSStatus() // Ενημέρωση status μετά τις άδειες
        }
    }

    private fun startCollection() {
        if (!checkPermissions()) {
            tvStatus.text = """
                ❌ ΑΠΑΙΤΟΥΝΤΑΙ ΑΔΕΙΕΣ GPS!
                
                Πήγαινε στις Ρυθμίσεις → Εφαρμογές → PredictDataFuel → Άδειες
                και ενεργοποίησε τη Θέση.
            """.trimIndent()
            return
        }

        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            tvStatus.text = """
                ❌ GPS ΑΠΕΝΕΡΓΟΠΟΙΗΜΕΝΟ!
                
                Πήγαινε στις Ρυθμίσεις → Θέση και ενεργοποίησε το GPS.
                Επίσης βγες εξωτερικά για καλύτερο σήμα.
            """.trimIndent()
            return
        }

        isCollecting = true
        startTime = System.currentTimeMillis()
        gpsFixTime = 0L
        gpsLocationCount = 0

        btnStartStop.text = "⏹️ ΔΙΑΚΟΠΗ ΣΥΛΛΟΓΗΣ"
        tvStatus.text = """
            🟡 ΕΝΑΡΞΗ ΣΥΛΛΟΓΗΣ...
            
            📍 Αναζήτηση GPS σήματος...
            ⏳ Βγες εξωτερικά και περίμενε 1-2 λεπτά
            🚗 Κινήσου για να δεις την ταχύτητα
        """.trimIndent()
        tvStatus.setBackgroundColor(0xFFFFEB3B.toInt())

        // ΕΝΑΡΞΗ ΑΙΣΘΗΤΗΡΩΝ
        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        magnetometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }

        // ΕΝΑΡΞΗ GPS ΜΕ ΔΙΑΓΝΩΣΤΙΚΑ
        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {

                // GPS Provider - primary
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        500,  // Κάθε 500ms
                        0f,   // Κάθε μέτρο
                        this
                    )
                }

                // Network Provider - backup
                if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                    locationManager.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        1000,
                        0f,
                        this
                    )
                }

                // Τελευταία γνωστή θέση
                val lastGPS = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                val lastNetwork = locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)

                val lastKnown = when {
                    lastGPS != null && (System.currentTimeMillis() - lastGPS.time) < 60000 -> lastGPS
                    lastNetwork != null && (System.currentTimeMillis() - lastNetwork.time) < 60000 -> lastNetwork
                    lastGPS != null -> lastGPS
                    lastNetwork != null -> lastNetwork
                    else -> null
                }

                lastKnown?.let {
                    currentLat = it.latitude
                    currentLon = it.longitude
                    currentSpeed = it.speed * 3.6f
                    currentAltitude = it.altitude
                    tvStatus.text = "🟡 Χρησιμοποιείται παλιά θέση GPS. Περιμένετε για νέο σήμα..."
                }
            }
        } catch (e: SecurityException) {
            tvStatus.text = "❌ Σφάλμα άδειας GPS: ${e.message}"
        }

        startTimer()
        startPeriodicCollection()
    }

    private fun stopCollection() {
        isCollecting = false

        btnStartStop.text = "▶️ ΕΝΑΡΞΗ ΣΥΛΛΟΓΗΣ"
        tvStatus.text = """
            ✅ ΣΥΛΛΟΓΗ ΤΕΛΕΙΩΣΕ!
            
            📦 Συλλέχθηκαν: ${dataList.size} δεδομένα
            📍 GPS updates: $gpsLocationCount
            ${if (gpsLocationCount == 0) "⚠️ Κανένα GPS update - δοκίμασε εξωτερικά!" else ""}
        """.trimIndent()
        tvStatus.setBackgroundColor(0xFFE8F5E8.toInt())

        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)
        timeRunnable?.let { handler.removeCallbacks(it) }
    }

    private fun startTimer() {
        timeRunnable = object : Runnable {
            override fun run() {
                if (isCollecting) {
                    val elapsed = (System.currentTimeMillis() - startTime) / 1000
                    val minutes = elapsed / 60
                    val seconds = elapsed % 60
                    tvTime.text = "⏱️ Χρόνος: %02d:%02d | GPS: $gpsLocationCount updates".format(minutes, seconds)
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
                    handler.postDelayed(this, 200)
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

        val prediction = mlModel.predict(dataPoint)

        runOnUiThread {
            tvCurrentPrediction.text = "🔮 Πρόβλεψη: ${String.format("%.1f", prediction)} L/100km"

            val color = when {
                prediction < 5f -> 0xFF4CAF50.toInt()
                prediction < 8f -> 0xFFFF9800.toInt()
                prediction < 12f -> 0xFFFF5722.toInt()
                else -> 0xFF9C27B0.toInt()
            }
            tvCurrentPrediction.setTextColor(color)
        }
    }

    private fun updateUI() {
        tvDataCount.text = "📦 Δεδομένα: ${dataList.size}"

        // ΠΡΟΣΘΗΚΗ GPS STATUS ΣΤΗ ΤΑΧΥΤΗΤΑ
        val speedText = if (gpsLocationCount == 0) {
            "🏃 Ταχύτητα: ${String.format("%.1f", currentSpeed)} km/h ⚠️ ΧΩΡ2Σ GPS"
        } else {
            "🏃 Ταχύτητα: ${String.format("%.1f", currentSpeed)} km/h ✅"
        }
        tvSpeed.text = speedText

        tvLocation.text = "📍 Θέση: ${String.format("%.6f", currentLat)}, ${String.format("%.6f", currentLon)}"
        tvAltitude.text = "🏔️ Υψόμετρο: ${String.format("%.1f", currentAltitude)} m"
        tvAccelerometer.text = "📈 Επιταχυνσιόμετρο: ${String.format("%.2f, %.2f, %.2f", accelX, accelY, accelZ)}"

        val compassDirection = when {
            currentAzimuth < 22.5 || currentAzimuth >= 337.5 -> "Β (${String.format("%.0f", currentAzimuth)}°)"
            currentAzimuth < 67.5 -> "ΒΑ (${String.format("%.0f", currentAzimuth)}°)"
            currentAzimuth < 112.5 -> "Α (${String.format("%.0f", currentAzimuth)}°)"
            currentAzimuth < 157.5 -> "ΝΑ (${String.format("%.0f", currentAzimuth)}°)"
            currentAzimuth < 202.5 -> "Ν (${String.format("%.0f", currentAzimuth)}°)"
            currentAzimuth < 247.5 -> "ΝΔ (${String.format("%.0f", currentAzimuth)}°)"
            currentAzimuth < 292.5 -> "Δ (${String.format("%.0f", currentAzimuth)}°)"
            else -> "ΒΔ (${String.format("%.0f", currentAzimuth)}°)"
        }
        tvGyroscope.text = "🧭 Κατεύθυνση: $compassDirection"
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event?.let {
            when (it.sensor.type) {
                Sensor.TYPE_ACCELEROMETER -> {
                    accelX = it.values[0]
                    accelY = it.values[1]
                    accelZ = it.values[2]
                    System.arraycopy(it.values, 0, accelerometerReading, 0, accelerometerReading.size)
                    updateOrientationAngles()
                }
                Sensor.TYPE_GYROSCOPE -> {
                    gyroX = it.values[0]
                    gyroY = it.values[1]
                    gyroZ = it.values[2]
                }
                Sensor.TYPE_MAGNETIC_FIELD -> {
                    System.arraycopy(it.values, 0, magnetometerReading, 0, magnetometerReading.size)
                    updateOrientationAngles()
                }
            }
        }
    }

    private fun updateOrientationAngles() {
        SensorManager.getRotationMatrix(rotationMatrix, null, accelerometerReading, magnetometerReading)
        SensorManager.getOrientation(rotationMatrix, orientationAngles)
        currentAzimuth = Math.toDegrees(orientationAngles[0].toDouble()).toFloat()
        if (currentAzimuth < 0) {
            currentAzimuth += 360f
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(location: Location) {
        gpsLocationCount++
        lastLocationTime = System.currentTimeMillis()
        gpsAccuracy = location.accuracy

        if (gpsFixTime == 0L) {
            gpsFixTime = System.currentTimeMillis()
        }

        currentLat = location.latitude
        currentLon = location.longitude
        currentSpeed = location.speed * 3.6f // m/s to km/h
        currentAltitude = location.altitude

        // GPS STATUS UPDATE
        if (isCollecting) {
            val timeSinceStart = (System.currentTimeMillis() - startTime) / 1000
            tvStatus.text = """
                🟢 GPS ΣΗΜΑ ΕΝΕΡΓΟ! 
                
                📍 Updates: $gpsLocationCount
                ⏰ GPS fix σε: ${(gpsFixTime - startTime) / 1000}s
                🎯 Ακρίβεια: ${String.format("%.1f", gpsAccuracy)}m
                🏃 Ταχύτητα: ${String.format("%.1f", currentSpeed)} km/h
                
                ${if (currentSpeed < 1f) "💡 Κινήσου για να δεις αλλαγή ταχύτητας!" else "✅ Η ταχύτητα καταγράφεται!"}
            """.trimIndent()
            tvStatus.setBackgroundColor(0xFF7BFF7B.toInt())
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
            stopCollection()
        }
    }

    // ΥΠΟΛΟΙΠΕΣ ΜΕΘΟΔΟΙ (CSV, ML) - ΙΔΙΕΣ ΜΕ ΠΡΙΝ
    private fun exportData() {
        if (dataList.isEmpty()) {
            tvFileStatus.text = "❌ Δεν υπάρχουν δεδομένα για εξαγωγή!"
            return
        }
        if (!checkStoragePermissions()) {
            tvFileStatus.text = "❌ Χρειάζονται άδειες αποθήκευσης!"
            return
        }
        val result = csvManager.exportToCSV(dataList)
        tvFileStatus.text = result
        updateFileStatus()
    }

    private fun loadData() {
        if (!checkStoragePermissions()) {
            tvFileStatus.text = "❌ Χρειάζονται άδειες αποθήκευσης!"
            return
        }
        val (loadedData, message) = csvManager.loadFromCSV()
        tvFileStatus.text = message
        if (loadedData.isNotEmpty()) {
            dataList.clear()
            dataList.addAll(loadedData)
            updateUI()
            tvStatus.text = "✅ Φορτώθηκαν ${loadedData.size} δεδομένα!"
        }
        updateFileStatus()
    }

    private fun clearAllData() {
        dataList.clear()
        val deleteResult = csvManager.deleteAllCSVs()
        updateUI()
        tvFileStatus.text = deleteResult
        tvStatus.text = "🗑️ Όλα τα δεδομένα καθαρίστηκαν!"
        updateFileStatus()
    }

    private fun updateFileStatus() {
        val csvFiles = csvManager.getCSVFiles()
        if (csvFiles.isEmpty()) {
            tvFileStatus.text = "📁 Αρχεία: Δεν υπάρχουν"
        } else {
            val latestFile = csvFiles.maxByOrNull { it.lastModified() }
            tvFileStatus.text = "📁 Τελευταίο: ${latestFile?.name ?: "Άγνωστο"}"
        }
    }

    private fun checkStoragePermissions(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
    }

    private fun trainMLModel() {
        if (dataList.isEmpty()) {
            tvModelStatus.text = "❌ Δεν υπάρχουν δεδομένα για εκπαίδευση!"
            return
        }
        tvModelStatus.text = "🔄 Εκπαίδευση σε εξέλιξη..."
        Thread {
            val result = mlModel.trainModel(dataList)
            runOnUiThread {
                tvModelStatus.text = "✅ Μοντέλο εκπαιδευμένο!"
                tvStatus.text = result
                updateModelStatus()
            }
        }.start()
    }

    private fun showModelInfo() {
        val info = mlModel.getModelInfo()
        tvStatus.text = info
        updateModelStatus()
    }

    private fun updateModelStatus() {
        val info = mlModel.getModelInfo()
        tvModelStatus.text = if (info.startsWith("✅")) {
            "✅ Μοντέλο έτοιμο"
        } else {
            "❌ Μοντέλο δεν έχει εκπαιδευτεί"
        }
    }

    private fun loadRealDataset() {
        tvModelStatus.text = "🔄 Φόρτωση πραγματικών δεδομένων..."
        Thread {
            val (data, message) = realDataLoader.loadRealTrainingData()
            realTrainingData = data
            runOnUiThread {
                tvStatus.text = message
                tvModelStatus.text = if (data.isNotEmpty()) {
                    "✅ Φορτώθηκαν ${data.size} πραγματικά δεδομένα!"
                } else {
                    "❌ Αποτυχία φόρτωσης"
                }
            }
        }.start()
    }

    private fun analyzeRealData() {
        if (realTrainingData.isEmpty()) {
            tvStatus.text = "❌ Φόρτωσε πρώτα τα πραγματικά δεδομένα!"
            return
        }
        val analysis = realDataLoader.analyzeData(realTrainingData)
        tvStatus.text = analysis
    }

    private fun calculateRealConsumption() {
        if (realTrainingData.isEmpty()) {
            tvStatus.text = "❌ Φόρτωσε πρώτα τα πραγματικά δεδομένα!"
            return
        }
        tvModelStatus.text = "🔄 Υπολογισμός κατανάλωσης..."
        Thread {
            val (consumptionData, analysisReport) = realDataLoader.calculateFuelConsumption(realTrainingData)
            runOnUiThread {
                if (consumptionData.isNotEmpty()) {
                    tvStatus.text = analysisReport
                    tvModelStatus.text = "✅ Υπολογίστηκε κατανάλωση για ${consumptionData.size} σημεία"
                } else {
                    tvStatus.text = analysisReport
                    tvModelStatus.text = "❌ Αδυναμία υπολογισμού"
                }
            }
        }.start()
    }

    private fun resetMLModel() {
        mlModel.resetModel()
        realTrainingData = emptyList()
        tvModelStatus.text = "🔄 Μοντέλο επαναφέρθηκε"
        tvCurrentPrediction.text = "🔮 Πρόβλεψη: -- L/100km"
        tvCurrentPrediction.setTextColor(0xFF666666.toInt())
        tvStatus.text = "🔄 Μοντέλο καθαρίστηκε. Ξεκινήστε από την αρχή."
        updateModelStatus()
    }
}
