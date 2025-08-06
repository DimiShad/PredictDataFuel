package com.example.predictdatafuel

import android.util.Log
import com.google.gson.Gson
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

object SimpleRealtimeSender {
    private const val TAG = "SimpleRealtimeSender"
    private const val FLASK_URL = "http://83.212.80.156:5000/api/sendPhoneData"

    private val client = OkHttpClient()
    private val gson = Gson()

    fun sendNow(sensorData: SensorDataPoint, predictedConsumption: Float) {
        // Απλό map με τα δεδομένα
        val data = mapOf(
            "timestamp" to sensorData.timestamp,
            "accelerometer_x" to sensorData.accelerometerX,
            "accelerometer_y" to sensorData.accelerometerY,
            "accelerometer_z" to sensorData.accelerometerZ,
            "magnetometer_x" to sensorData.magnetometerX,
            "magnetometer_y" to sensorData.magnetometerY,
            "magnetometer_z" to sensorData.magnetometerZ,
            "compass_heading" to sensorData.compassHeading,
            "latitude" to sensorData.latitude,
            "longitude" to sensorData.longitude,
            "speed" to sensorData.speed,
            "altitude" to sensorData.altitude,
            "predicted_consumption" to predictedConsumption
        )

        val json = gson.toJson(data)
        val body = json.toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url(FLASK_URL)
            .post(body)
            .build()

        // Στέλνω χωρίς να περιμένω απάντηση
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                Log.d(TAG, "Failed to send: ${e.message}")
            }

            override fun onResponse(call: Call, response: Response) {
                if (response.isSuccessful) {
                    Log.d(TAG, "✅ Sent data")
                } else {
                    Log.d(TAG, "❌ Error: ${response.code}")
                }
                response.close()
            }
        })
    }
}