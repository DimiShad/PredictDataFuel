package com.example.predictdatafuel

import retrofit2.Call
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

// ===== EXISTING DATA CLASSES (από την άλλη εφαρμογή σου) =====
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

// ===== NEW DATA CLASSES για αποστολή trip data =====
data class TripSummaryData(
    val nickname: String,
    val totalDistance: Double,           // Συνολική απόσταση σε km
    val averageSpeed: Double,            // Μέση ταχύτητα σε km/h
    val maxSpeed: Double,                // Μέγιστη ταχύτητα σε km/h
    val averageConsumption: Double,      // Μέση κατανάλωση σε L/100km (από ML)
    val totalFuelUsed: Double,           // Συνολική εκτιμώμενη χρήση καυσίμου σε L
    val duration: Long,                  // Διάρκεια διαδρομής σε ms
    val startLat: Double,                // Αρχική θέση - latitude
    val startLon: Double,                // Αρχική θέση - longitude
    val endLat: Double,                  // Τελική θέση - latitude
    val endLon: Double,                  // Τελική θέση - longitude
    val dataPoints: Int,                 // Αριθμός σημείων δεδομένων
    val timestamp: Long                  // Timestamp ολοκλήρωσης διαδρομής
)

data class TripDataPoint(
    val timestamp: Long,
    val accelerometerX: Float,
    val accelerometerY: Float,
    val accelerometerZ: Float,
    val magnetometerX: Float,
    val magnetometerY: Float,
    val magnetometerZ: Float,
    val compassHeading: Float,          // Πυξίδα σε μοίρες (0° = Βορράς)
    val latitude: Double,
    val longitude: Double,
    val speed: Float,                   // GPS ταχύτητα σε km/h
    val altitude: Double,
    val predictedConsumption: Float     // ML πρόβλεψη κατανάλωσης για αυτό το σημείο
)

data class TripUploadRequest(
    val tripSummary: TripSummaryData,
    val detailedData: List<TripDataPoint> = emptyList()  // Προαιρετικά αναλυτικά δεδομένα
)

data class ApiUploadResponse(
    val success: Boolean,
    val message: String,
    val tripId: String? = null
)

// ===== API SERVICE INTERFACE =====
interface ApiService {

    // EXISTING METHOD (από την άλλη εφαρμογή σου)
    @GET("api/average-fuel-consumption")
    fun getAverageFuelConsumption(): Call<FuelConsumptionResponse>

    // NEW METHODS για αποστολή trip data
    @POST("api/trip-data")
    suspend fun sendTripData(@Body tripData: TripSummaryData): Response<ApiUploadResponse>

    @POST("api/detailed-trip-data")
    suspend fun sendDetailedTripData(@Body tripRequest: TripUploadRequest): Response<ApiUploadResponse>

    // Προαιρετικά: Για real-time αποστολή δεδομένων κατά τη διάρκεια της διαδρομής
    @POST("api/realtime-data")
    suspend fun sendRealtimeData(@Body dataPoint: TripDataPoint): Response<ApiUploadResponse>
}

// ===== RETROFIT CLIENT =====
object RetrofitClient {
    private const val BASE_URL = "http://83.212.80.156:5000/" // Το ίδιο API που χρησιμοποιείς

    private val retrofit: Retrofit by lazy {
        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
    }

    val api: ApiService by lazy {
        retrofit.create(ApiService::class.java)
    }
}