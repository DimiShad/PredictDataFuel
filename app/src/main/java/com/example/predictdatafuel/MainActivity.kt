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

    // CSV MANAGER
    private lateinit var csvManager: CSVManager

    // ML MODEL
    private lateinit var mlModel: FuelPredictionModel

    // REAL DATA LOADER
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

        if (accelerometer == null) {
            tvStatus.text = "❌ Δεν βρέθηκε επιταχυνσιόμετρο!"
        }
        if (gyroscope == null) {
            tvGyroscope.text = "🌀 Γυροσκόπιο: Μη διαθέσιμο"
        }
    }

    private fun initCSVManager() {
        csvManager = CSVManager(this)
    }

    private fun initMLModel() {
        mlModel = FuelPredictionModel(this)
        realDataLoader = RealDataLoader(this)
    }

    private fun setupUI() {
        btnStartStop.setOnClickListener {
            if (!isCollecting) {
                startCollection()
            } else {
                stopCollection()
            }
        }

        btnExportCSV.setOnClickListener {
            exportData()
        }

        btnLoadCSV.setOnClickListener {
            loadData()
        }

        btnClearData.setOnClickListener {
            clearAllData()
        }

        btnTrainModel.setOnClickListener {
            trainMLModel()
        }

        btnModelInfo.setOnClickListener {
            showModelInfo()
        }

        btnLoadRealData.setOnClickListener {
            loadRealDataset()
        }

        btnAnalyzeData.setOnClickListener {
            analyzeRealData()
        }

        btnCalculateConsumption.setOnClickListener {
            calculateRealConsumption()
        }

        btnResetModel.setOnClickListener {
            resetMLModel()
        }
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

    private fun startCollection() {
        if (!checkPermissions()) {
            tvStatus.text = "❌ Χρειάζονται άδειες!"
            return
        }

        isCollecting = true
        startTime = System.currentTimeMillis()

        btnStartStop.text = "⏹️ ΔΙΑΚΟΠΗ ΣΥΛΛΟΓΗΣ"
        tvStatus.text = "🟢 Συλλέγονται δεδομένα..."
        tvStatus.setBackgroundColor(0xFF7BFF7B.toInt())

        accelerometer?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        gyroscope?.let {
            sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }

        try {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 500, 0.5f, this)
            }
        } catch (e: SecurityException) {
            tvStatus.text = "❌ Σφάλμα GPS: ${e.message}"
        }

        startTimer()
        startPeriodicCollection()
    }

    private fun stopCollection() {
        isCollecting = false

        btnStartStop.text = "▶️ ΕΝΑΡΞΗ ΣΥΛΛΟΓΗΣ"
        tvStatus.text = "🔴 Αναμονή..."
        tvStatus.setBackgroundColor(0xFFFFECEC.toInt())

        sensorManager.unregisterListener(this)
        locationManager.removeUpdates(this)

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
        tvSpeed.text = "🏃 Ταχύτητα: %.1f km/h".format(currentSpeed)
        tvLocation.text = "📍 Θέση: %.6f, %.6f".format(currentLat, currentLon)
        tvAltitude.text = "🏔️ Υψόμετρο: %.1f m".format(currentAltitude)
        tvAccelerometer.text = "📈 Επιταχυνσιόμετρο: %.2f, %.2f, %.2f".format(accelX, accelY, accelZ)
        tvGyroscope.text = "🌀 Γυροσκόπιο: %.2f, %.2f, %.2f".format(gyroX, gyroY, gyroZ)
    }

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

    override fun onLocationChanged(location: Location) {
        currentLat = location.latitude
        currentLon = location.longitude
        currentSpeed = location.speed * 3.6f
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
