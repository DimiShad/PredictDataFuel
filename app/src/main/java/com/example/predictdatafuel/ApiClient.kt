package com.example.predictdatafuel

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class ApiClient {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    // ΘΑ ΑΛΛΑΞΕΙΣ ΑΥΤΟ ΜΕ ΤΗ ΔΙΚΗ ΣΟΥ ΔΙΕΥΘΥΝΣΗ API
    private val baseUrl = "YOUR_API_BASE_URL_HERE" // π.χ. "https://yourapp.com/api"

    /**
     * Αποστολή δεδομένων διαδρομής στη βάση δεδομένων
     */
    suspend fun sendTripData(
        tripData: List<SensorDataPoint>,
        totalDistance: Double,
        averageConsumption: Double
    ): Boolean = withContext(Dispatchers.IO) {

        return@withContext try {
            // Δημιουργία JSON payload
            val tripJson = createTripJson(tripData, totalDistance, averageConsumption)

            // HTTP Request
            val requestBody = tripJson.toString().toRequestBody("application/json".toMediaType())

            val request = Request.Builder()
                .url("$baseUrl/trips")
                .post(requestBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("Authorization", "Bearer YOUR_API_TOKEN") // Αν χρειάζεται
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                println("✅ Επιτυχής αποστολή δεδομένων διαδρομής")
                true
            } else {
                println("❌ Αποτυχία αποστολής: ${response.code} - ${response.message}")
                false
            }

        } catch (e: IOException) {
            println("❌ Σφάλμα δικτύου: ${e.message}")
            false
        } catch (e: Exception) {
            println("❌ Άγνωστο σφάλμα: ${e.message}")
            false
        }
    }

    /**
     * Δημιουργία JSON payload για την αποστολή
     */
    private fun createTripJson(
        tripData: List<SensorDataPoint>,
        totalDistance: Double,
        averageConsumption: Double
    ): JSONObject {

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val startTime = if (tripData.isNotEmpty()) Date(tripData.first().timestamp) else Date()
        val endTime = if (tripData.isNotEmpty()) Date(tripData.last().timestamp) else Date()

        // Βασικές πληροφορίες διαδρομής
        val tripInfo = JSONObject().apply {
            put("start_time", dateFormat.format(startTime))
            put("end_time", dateFormat.format(endTime))
            put("duration_minutes", calculateDurationMinutes(tripData))
            put("total_distance_km", String.format("%.2f", totalDistance))
            put("average_consumption_l100km", String.format("%.2f", averageConsumption))
            put("data_points_count", tripData.size)
        }

        // Στατιστικά διαδρομής
        val statistics = JSONObject().apply {
            put("max_speed", tripData.maxOfOrNull { it.speed } ?: 0f)
            put("avg_speed", if (tripData.isNotEmpty()) tripData.map { it.speed }.average() else 0.0)
            put("min_speed", tripData.minOfOrNull { it.speed } ?: 0f)
            put("max_altitude", tripData.maxOfOrNull { it.altitude } ?: 0.0)
            put("min_altitude", tripData.minOfOrNull { it.altitude } ?: 0.0)
            put("speed_changes", calculateSpeedChanges(tripData))        // Πόσες φορές άλλαξε ταχύτητα
            put("avg_acceleration", calculateAverageAcceleration(tripData)) // Μέση επιτάχυνση
        }

        // Δεδομένα αισθητήρων (δειγματοληψία κάθε 10 σημεία για μείωση μεγέθους)
        val sensorDataArray = JSONArray()
        tripData.forEachIndexed { index, dataPoint ->
            if (index % 10 == 0) { // Κάθε 10ο σημείο
                val sensorJson = JSONObject().apply {
                    put("timestamp", dataPoint.timestamp)
                    put("latitude", dataPoint.latitude)
                    put("longitude", dataPoint.longitude)
                    put("speed", dataPoint.speed)                    // GPS ταχύτητα
                    put("speed_accuracy", dataPoint.speedAccuracy)   // Ακρίβεια ταχύτητας
                    put("bearing", dataPoint.bearing)                // Κατεύθυνση κίνησης από GPS
                    put("compass_heading", dataPoint.compassHeading) // Πυξίδα σε μοίρες (0° = Βορράς)
                    put("altitude", dataPoint.altitude)
                    put("accelerometer_x", dataPoint.accelerometerX)
                    put("accelerometer_y", dataPoint.accelerometerY)
                    put("accelerometer_z", dataPoint.accelerometerZ)
                    put("magnetometer_x", dataPoint.magnetometerX)   // Μαγνητόμετρο
                    put("magnetometer_y", dataPoint.magnetometerY)
                    put("magnetometer_z", dataPoint.magnetometerZ)
                }
                sensorDataArray.put(sensorJson)
            }
        }

        // Συνολικό JSON
        return JSONObject().apply {
            put("trip_info", tripInfo)
            put("statistics", statistics)
            put("sensor_data", sensorDataArray)
            put("device_info", getDeviceInfo())
        }
    }

    /**
     * Υπολογισμός διάρκειας διαδρομής σε λεπτά
     */
    private fun calculateDurationMinutes(tripData: List<SensorDataPoint>): Int {
        return if (tripData.size >= 2) {
            val duration = tripData.last().timestamp - tripData.first().timestamp
            (duration / (1000 * 60)).toInt() // σε λεπτά
        } else {
            0
        }
    }

    /**
     * Πληροφορίες συσκευής
     */
    private fun getDeviceInfo(): JSONObject {
        return JSONObject().apply {
            put("device_model", android.os.Build.MODEL)
            put("android_version", android.os.Build.VERSION.RELEASE)
            put("app_version", "1.0.0")
            put("timestamp", System.currentTimeMillis())
        }
    }

    /**
     * Αποστολή real-time δεδομένων (αν χρειάζεται)
     */
    suspend fun sendRealtimeData(dataPoint: SensorDataPoint, consumption: Float): Boolean =
        withContext(Dispatchers.IO) {

            return@withContext try {
                val realtimeJson = JSONObject().apply {
                    put("timestamp", dataPoint.timestamp)
                    put("latitude", dataPoint.latitude)
                    put("longitude", dataPoint.longitude)
                    put("speed", dataPoint.speed)
                    put("predicted_consumption", consumption)
                    put("device_id", getDeviceId())
                }

                val requestBody = realtimeJson.toString().toRequestBody("application/json".toMediaType())

                val request = Request.Builder()
                    .url("$baseUrl/realtime")
                    .post(requestBody)
                    .addHeader("Content-Type", "application/json")
                    .build()

                val response = client.newCall(request).execute()
                response.isSuccessful

            } catch (e: Exception) {
                false
            }
        }

    /**
     * Υπολογισμός αλλαγών ταχύτητας
     */
    private fun calculateSpeedChanges(tripData: List<SensorDataPoint>): Int {
        var changes = 0
        for (i in 1 until tripData.size) {
            val speedDiff = kotlin.math.abs(tripData[i].speed - tripData[i-1].speed)
            if (speedDiff > 5f) { // Αλλαγή >5 km/h
                changes++
            }
        }
        return changes
    }

    /**
     * Υπολογισμός μέσης επιτάχυνσης
     */
    private fun calculateAverageAcceleration(tripData: List<SensorDataPoint>): Double {
        if (tripData.size < 2) return 0.0

        val accelerations = mutableListOf<Double>()
        for (i in 1 until tripData.size) {
            val current = tripData[i]
            val previous = tripData[i-1]

            val accelMagnitude = kotlin.math.sqrt(
                (current.accelerometerX * current.accelerometerX +
                        current.accelerometerY * current.accelerometerY +
                        current.accelerometerZ * current.accelerometerZ).toDouble()
            )
            accelerations.add(accelMagnitude)
        }

        return if (accelerations.isNotEmpty()) accelerations.average() else 0.0
    }

    /**
     * Μοναδικό ID συσκευής
     */
    private fun getDeviceId(): String {
        return "${android.os.Build.MODEL}_${android.os.Build.SERIAL}".hashCode().toString()
    }
}