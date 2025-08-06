package com.example.predictdatafuel

import android.util.Log
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import java.io.IOException
import java.util.concurrent.TimeUnit

// ===== ORIGINAL DATA CLASSES (που χρησιμοποιεί το MainActivity) =====
data class TripSummaryData(
    val nickname: String,
    val totalDistance: Double,
    val averageSpeed: Double,
    val maxSpeed: Double,
    val averageConsumption: Double,
    val totalFuelUsed: Double,
    val duration: Long,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val dataPoints: Int,
    val timestamp: Long
)

// ===== FLASK API DATA CLASSES =====
data class FuelConsumptionResponse(
    val api_response: ApiResponseData
)

data class ApiResponseData(
    val data: List<VehicleData>,
    val msg: String?,
    val status: String
)

data class VehicleData(
    val alt: Int,
    val angle: Int,
    val dev_id: Int,
    val engine: Int,
    val ext_volt: Int,
    val fuel_lt: Double,
    val lat: Double,
    val lon: Double,
    val nickname: String,
    val odom: Long,
    val reg_id: Int,
    val rpm: Int,
    val signal: String,
    val speed: Int,
    val time: String
)

// Νέα data class για αποστολή trip data στο Flask
data class FlaskTripData(
    val vehicle_name: String,
    val total_distance_km: Double,
    val average_speed_kmh: Double,
    val max_speed_kmh: Double,
    val average_consumption_l100km: Double,
    val total_fuel_used_liters: Double,
    val duration_millis: Long,
    val start_latitude: Double,
    val start_longitude: Double,
    val end_latitude: Double,
    val end_longitude: Double,
    val total_data_points: Int,
    val completed_at: Long
)

// Response από Flask
data class FlaskResponse(
    val success: Boolean,
    val message: String,
    val data: Any? = null
)

// ===== FLASK API SERVICE =====
interface FlaskApiService {
    @GET("api/average-fuel-consumption")
    fun getAverageFuelConsumption(): Call<FuelConsumptionResponse>

    @POST("api/sendPhoneData")
    fun sendTripData(@Body tripData: FlaskTripData): Call<FlaskResponse>

    @POST("api/trip-data")
    fun sendTripDataAlternative(@Body tripData: FlaskTripData): Call<FlaskResponse>

}

// ===== FLASK CLIENT =====
object FlaskOnlyClient {
    private const val BASE_URL = "http://83.212.80.156:5000/"
    private const val TAG = "FlaskOnlyClient"

    private val loggingInterceptor = HttpLoggingInterceptor().apply {
        level = HttpLoggingInterceptor.Level.BODY
    }

    private val okHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor)
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val api: FlaskApiService by lazy {
        retrofit.create(FlaskApiService::class.java)
    }

    // Μέθοδος για εύκολη αποστολή trip data
    fun sendTripToFlask(
        tripSummary: TripSummaryData,
        onSuccess: (FlaskResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        // Μετατροπή σε Flask format
        val flaskData = FlaskTripData(
            vehicle_name = tripSummary.nickname,
            total_distance_km = tripSummary.totalDistance,
            average_speed_kmh = tripSummary.averageSpeed,
            max_speed_kmh = tripSummary.maxSpeed,
            average_consumption_l100km = tripSummary.averageConsumption,
            total_fuel_used_liters = tripSummary.totalFuelUsed,
            duration_millis = tripSummary.duration,
            start_latitude = tripSummary.startLat,
            start_longitude = tripSummary.startLon,
            end_latitude = tripSummary.endLat,
            end_longitude = tripSummary.endLon,
            total_data_points = tripSummary.dataPoints,
            completed_at = tripSummary.timestamp
        )

        Log.d(TAG, "🚀 Sending trip data to Flask API")
        Log.d(TAG, "Data: ${Gson().toJson(flaskData)}")

        // Δοκιμή του κύριου endpoint
        api.sendTripData(flaskData).enqueue(object : Callback<FlaskResponse> {
            override fun onResponse(call: Call<FlaskResponse>, response: Response<FlaskResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    Log.i(TAG, "✅ Flask API SUCCESS!")
                    onSuccess(response.body()!!)
                } else {
                    Log.w(TAG, "❌ Primary endpoint failed: ${response.code()}")
                    // Δοκιμή εναλλακτικού endpoint
                    tryAlternativeEndpoint(flaskData, onSuccess, onError)
                }
            }

            override fun onFailure(call: Call<FlaskResponse>, t: Throwable) {
                Log.e(TAG, "❌ Primary endpoint network error: ${t.message}")
                // Δοκιμή εναλλακτικού endpoint
                tryAlternativeEndpoint(flaskData, onSuccess, onError)
            }
        })
    }

    private fun tryAlternativeEndpoint(
        flaskData: FlaskTripData,
        onSuccess: (FlaskResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "🔄 Trying alternative endpoint: /api/trip-data")

        api.sendTripDataAlternative(flaskData).enqueue(object : Callback<FlaskResponse> {
            override fun onResponse(call: Call<FlaskResponse>, response: Response<FlaskResponse>) {
                if (response.isSuccessful && response.body() != null) {
                    Log.i(TAG, "✅ Alternative endpoint SUCCESS!")
                    onSuccess(response.body()!!)
                } else {
                    val errorMsg = "Flask API Error: ${response.code()} - ${response.message()}"
                    val errorBody = response.errorBody()?.string()
                    Log.e(TAG, errorMsg)
                    Log.e(TAG, "Error body: $errorBody")
                    onError("$errorMsg\n$errorBody")
                }
            }

            override fun onFailure(call: Call<FlaskResponse>, t: Throwable) {
                val errorMsg = "Flask API Network Error: ${t.message}"
                Log.e(TAG, errorMsg)

                // Τελική προσπάθεια με raw HTTP
                tryRawHTTPCall(flaskData, onSuccess, onError)
            }
        })
    }

    private fun tryRawHTTPCall(
        flaskData: FlaskTripData,
        onSuccess: (FlaskResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        Log.d(TAG, "🔧 Trying raw HTTP call as final fallback...")

        val json = Gson().toJson(flaskData)
        val mediaType = "application/json; charset=utf-8".toMediaType()
        val requestBody = json.toRequestBody(mediaType)

        // Δοκιμή διαφορετικών endpoints
        val endpoints = listOf(
            "api/sendPhoneData",
            "api/trip-data",
            "api/data",
            "sendPhoneData",
            "trip-data"
        )

        tryRawEndpoints(endpoints, requestBody, onSuccess, onError)
    }

    private fun tryRawEndpoints(
        endpoints: List<String>,
        requestBody: RequestBody,
        onSuccess: (FlaskResponse) -> Unit,
        onError: (String) -> Unit
    ) {
        if (endpoints.isEmpty()) {
            onError("All Flask endpoints failed")
            return
        }

        val endpoint = endpoints.first()
        val remainingEndpoints = endpoints.drop(1)

        val request = Request.Builder()
            .url("$BASE_URL$endpoint")
            .post(requestBody)
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", "application/json")
            .build()

        Log.d(TAG, "🔄 Raw HTTP trying: $endpoint")

        okHttpClient.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: okhttp3.Call, e: IOException) {
                Log.e(TAG, "❌ Raw HTTP $endpoint failed: ${e.message}")
                // Δοκιμή επόμενου endpoint
                tryRawEndpoints(remainingEndpoints, requestBody, onSuccess, onError)
            }

            override fun onResponse(call: okhttp3.Call, response: okhttp3.Response) {
                val responseBody = response.body?.string()

                if (response.isSuccessful) {
                    Log.i(TAG, "✅ Raw HTTP SUCCESS at $endpoint!")
                    Log.d(TAG, "Response: $responseBody")

                    // Προσπάθεια parsing του response
                    try {
                        val flaskResponse = Gson().fromJson(responseBody, FlaskResponse::class.java)
                        onSuccess(flaskResponse)
                    } catch (e: Exception) {
                        // Αν το parsing αποτύχει, δημιουργούμε ένα default response
                        onSuccess(FlaskResponse(true, "Data sent successfully", responseBody))
                    }
                } else {
                    Log.e(TAG, "❌ Raw HTTP $endpoint error: ${response.code}")
                    Log.e(TAG, "Error body: $responseBody")

                    if (response.code == 404 && remainingEndpoints.isNotEmpty()) {
                        // Δοκιμή επόμενου endpoint
                        tryRawEndpoints(remainingEndpoints, requestBody, onSuccess, onError)
                    } else {
                        onError("Flask error ${response.code}: $responseBody")
                    }
                }
            }
        })
    }
}


// ===== BACKWARD COMPATIBILITY ALIASES =====
// Για να μην χαλάσουν οι παλιές αναφορές στον κώδικα
typealias ApiService = FlaskApiService
object RetrofitClient {
    val api: FlaskApiService get() = FlaskOnlyClient.api
}