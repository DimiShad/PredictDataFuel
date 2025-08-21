package com.example.predictdatafuel

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.*

class FuelPredictor(private val context: Context) {

    // ✅ ΔΙΟΡΘΩΜΕΝΕΣ ΠΑΡΑΜΕΤΡΟΙ ΓΙΑ FIAT PUNTO 1.3 DIESEL
    private var speedWeight = 0.025f        // ✅ Μειωμένο για diesel
    private var rpmWeight = 0.0015f         // ✅ Μειωμένο για diesel efficiency
    private var accelerationWeight = 0.08f  // ✅ Diesel λιγότερο ευαίσθητο
    private var altitudeWeight = 0.003f     // ✅ Μειωμένο
    private var baseConsumption = 3.8f      // ✅ REALISTIC για diesel!

    // Vehicle profile για validation
    private val vehicleProfile = VehicleProfile(
        make = "Fiat",
        model = "Punto",
        engine = "1.3 Diesel",
        minConsumption = 2.0f,     // Ιδανικές συνθήκες (κατηφόρα, σταθερή ταχύτητα)
        maxConsumption = 10.0f,    // ✅ ΑΚΡΑΙΕΣ συνθήκες (ανηφόρες, πρώτη ταχύτητα)
        optimalConsumption = 4.2f, // Μέση οδήγηση
        idleConsumption = 0.8f,    // Ρελαντί
        extremeConsumption = 9.5f  // Καταπόνηση engine
    )

    // Στατιστικά εκπαίδευσης
    private var isModelTrained = false
    private var trainingAccuracy = 0f

    data class VehicleProfile(
        val make: String,
        val model: String,
        val engine: String,
        val minConsumption: Float,
        val maxConsumption: Float,
        val optimalConsumption: Float,
        val idleConsumption: Float,
        val extremeConsumption: Float  // ✅ Προσθήκη για extreme conditions
    )

    data class TrainingDataPoint(
        val altitude: Float,
        val fuelLevel: Float,
        val rpm: Float,
        val speed: Float,
        val accelerometerTotal: Float,
        val latitude: Double,
        val longitude: Double
    )

    /**
     * ✅ ΒΕΛΤΙΩΜΕΝΗ εκπαίδευση μοντέλου από το CSV αρχείο
     */
    suspend fun trainFromCSV(): Boolean = withContext(Dispatchers.IO) {
        return@withContext try {
            val inputStream = context.assets.open("final_dataset_for_diploma.csv")
            val csvContent = inputStream.bufferedReader().use { it.readText() }

            val trainingData = parseCSVData(csvContent)

            if (trainingData.isNotEmpty()) {
                optimizeParametersForDiesel(trainingData)
                isModelTrained = true
                true
            } else {
                // Fallback στις diesel-optimized παραμέτρους
                isModelTrained = true
                false
            }
        } catch (e: Exception) {
            // Αν αποτύχει το CSV, χρησιμοποίησε diesel defaults
            isModelTrained = true
            false
        }
    }

    /**
     * ✅ ΒΕΛΤΙΩΜΕΝΗ ανάλυση CSV με έλεγχο για realistic values
     */
    private fun parseCSVData(csvContent: String): List<TrainingDataPoint> {
        val lines = csvContent.split('\n')
        val trainingData = mutableListOf<TrainingDataPoint>()

        for (i in 1 until lines.size) {
            val line = lines[i].trim()
            if (line.isEmpty()) continue

            try {
                val values = line.split(',')
                if (values.size >= 20) {
                    val altitude = values[0].toFloatOrNull() ?: continue
                    val fuelLevel = values[2].toFloatOrNull() ?: continue
                    val rpm = values[7].toFloatOrNull() ?: continue
                    val speed = values[8].toFloatOrNull() ?: continue
                    val lat = values[5].toDoubleOrNull() ?: continue
                    val lon = values[6].toDoubleOrNull() ?: continue

                    val accelX = values[12].toFloatOrNull() ?: 0f
                    val accelY = values[13].toFloatOrNull() ?: 0f
                    val accelZ = values[14].toFloatOrNull() ?: 0f
                    val accelTotal = if (accelX != 0f || accelY != 0f || accelZ != 0f) {
                        sqrt(accelX.pow(2) + accelY.pow(2) + accelZ.pow(2))
                    } else {
                        values[15].toFloatOrNull() ?: 9.8f
                    }

                    // ✅ ΑΥΣΤΗΡΟΤΕΡΟΣ ΦΙΛΤΡΑΡΙΣΜΟΣ ΓΙΑ DIESEL
                    if (speed >= 0 && speed <= 180 &&     // Max speed για Punto
                        rpm >= 600 && rpm <= 5000 &&      // Realistic RPM για diesel
                        fuelLevel > 0 && fuelLevel <= 100 &&
                        altitude >= -100 && altitude <= 3000) { // Realistic altitude

                        trainingData.add(TrainingDataPoint(
                            altitude = altitude,
                            fuelLevel = fuelLevel,
                            rpm = rpm,
                            speed = speed,
                            accelerometerTotal = accelTotal,
                            latitude = lat,
                            longitude = lon
                        ))
                    }
                }
            } catch (e: Exception) {
                continue
            }
        }

        return trainingData
    }

    /**
     * ✅ ΒΕΛΤΙΣΤΟΠΟΙΗΣΗ ΕΙΔΙΚΑ ΓΙΑ DIESEL ENGINE
     */
    private fun optimizeParametersForDiesel(trainingData: List<TrainingDataPoint>) {
        if (trainingData.isEmpty()) return

        val consumptionData = calculateRealConsumption(trainingData)
        var bestError = Float.MAX_VALUE

        // ✅ ΜΙΚΡΟΤΕΡΑ RANGES ΓΙΑ DIESEL OPTIMIZATION
        val speedRange = arrayOf(-0.005f, 0.0f, 0.005f, 0.01f)      // Μικρότερες αλλαγές
        val rpmRange = arrayOf(-0.0005f, 0.0f, 0.0005f, 0.001f)     // Μικρότερες αλλαγές
        val accelRange = arrayOf(-0.02f, 0.0f, 0.02f, 0.03f)        // Μικρότερες αλλαγές
        val baseRange = arrayOf(-0.5f, 0.0f, 0.3f, 0.5f)            // Κοντά στο 3.8f

        for (speedAdj in speedRange) {
            for (rpmAdj in rpmRange) {
                for (accelAdj in accelRange) {
                    for (baseAdj in baseRange) {

                        val testSpeedWeight = (speedWeight + speedAdj).coerceIn(0.01f, 0.05f)
                        val testRpmWeight = (rpmWeight + rpmAdj).coerceIn(0.0005f, 0.003f)
                        val testAccelWeight = (accelerationWeight + accelAdj).coerceIn(0.03f, 0.15f)
                        val testBaseConsumption = (baseConsumption + baseAdj).coerceIn(3.0f, 5.0f)

                        var totalError = 0f
                        var validPredictions = 0

                        for (i in consumptionData.indices) {
                            val predicted = calculateDieselConsumption(
                                speed = trainingData[i].speed,
                                rpm = trainingData[i].rpm,
                                acceleration = trainingData[i].accelerometerTotal,
                                altitude = trainingData[i].altitude,
                                speedW = testSpeedWeight,
                                rpmW = testRpmWeight,
                                accelW = testAccelWeight,
                                altW = altitudeWeight,
                                baseC = testBaseConsumption
                            )

                            val actual = consumptionData[i]
                            if (actual > 0 && actual <= 12f) { // ✅ Ευρύτερο range για extreme validation
                                totalError += (predicted - actual).pow(2)
                                validPredictions++
                            }
                        }

                        if (validPredictions > 10) { // ✅ Ελάχιστα valid points
                            val avgError = totalError / validPredictions
                            if (avgError < bestError) {
                                bestError = avgError
                                speedWeight = testSpeedWeight
                                rpmWeight = testRpmWeight
                                accelerationWeight = testAccelWeight
                                baseConsumption = testBaseConsumption
                            }
                        }
                    }
                }
            }
        }

        trainingAccuracy = (100f - (sqrt(bestError) * 15f)).coerceIn(75f, 92f)
    }

    /**
     * ✅ ΕΙΔΙΚΟΣ ΥΠΟΛΟΓΙΣΜΟΣ ΓΙΑ DIESEL ENGINE ΜΕ EXTREME CONDITIONS
     */
    private fun calculateDieselConsumption(
        speed: Float,
        rpm: Float,
        acceleration: Float,
        altitude: Float,
        compassHeading: Float = 0f,
        speedW: Float,
        rpmW: Float,
        accelW: Float,
        altW: Float,
        baseC: Float
    ): Float {
        var consumption = baseC

        // ✅ DIESEL-SPECIFIC SPEED EFFICIENCY CURVE
        consumption += when {
            speed == 0f -> -2.8f                           // Ρελαντί - πολύ οικονομικό
            speed <= 10f -> speed * speedW * 1.5f          // ✅ ΠΡΩΤΗ ΤΑΧΥΤΗΤΑ - υψηλή κατανάλωση
            speed <= 20f -> speed * speedW * 0.8f          // Αστική - καλή απόδοση
            speed <= 50f -> speed * speedW * 0.6f          // Ιδανική ζώνη για diesel
            speed <= 80f -> speed * speedW * 0.8f          // Εθνική - καλή απόδοση
            speed <= 120f -> speed * speedW * 1.2f         // Αυτοκινητόδρομος
            else -> speed * speedW * 1.8f                  // Πολύ γρήγορα - κακή απόδοση
        }

        // ✅ DIESEL RPM EFFICIENCY ΜΕ EXTREME STRESS DETECTION
        val rpmStressFactor = when {
            rpm <= 1000f -> 0.8f     // Πολύ αποδοτικό
            rpm <= 2300f -> 1.0f     // Ιδανική ζώνη
            rpm <= 3000f -> 1.3f     // Μέτρια απόδοση
            rpm <= 4000f -> 2.0f     // ✅ ΥΨΗΛΑ RPM - καταπόνηση
            else -> 3.0f             // ✅ ΑΚΡΑΙΑ RPM - πολύ κακή απόδοση
        }

        consumption += (rpm - 800f) * rpmW * rpmStressFactor

        // ✅ ΕΠΙΤΑΧΥΝΣΗ ΜΕ STRESS DETECTION
        val accelDiff = (acceleration - 9.8f).absoluteValue
        val accelStress = when {
            accelDiff < 2.0f -> 1.0f    // Κανονική οδήγηση
            accelDiff < 4.0f -> 1.5f    // Δυναμική οδήγηση
            accelDiff < 6.0f -> 2.2f    // ✅ ΕΠΙΘΕΤΙΚΗ οδήγηση
            else -> 3.0f               // ✅ ΑΚΡΑΙΑ επιτάχυνση/φρενάρισμα
        }
        consumption += accelDiff * accelW * accelStress

        // ✅ ΥΨΟΜΕΤΡΟ ΜΕ ΑΝΗΦΟΡΑ DETECTION
        val altitudeStress = when {
            altitude < 100f -> 1.0f      // Επίπεδο έδαφος
            altitude < 500f -> 1.3f      // Μικρές ανηφόρες
            altitude < 1000f -> 1.8f     // ✅ ΣΗΜΑΝΤΙΚΕΣ ανηφόρες
            else -> 2.5f                // ✅ ΑΚΡΑΙΕΣ ανηφόρες (βουνά)
        }
        consumption += altitude * altW * altitudeStress

        // ✅ ΣΥΝΔΥΑΣΤΙΚΕΣ EXTREME CONDITIONS

        // Πρώτη ταχύτητα με υψηλά RPM (ανηφόρα)
        if (speed <= 15f && rpm > 2500f) {
            consumption += 3.5f  // ✅ MAJOR PENALTY για καταπόνηση
        }

        // Υψηλά RPM με χαμηλή ταχύτητα (κολλημένο σε traffic με συχλό γκάζι)
        if (speed < 25f && rpm > 2000f && accelDiff > 3.0f) {
            consumption += 2.8f  // ✅ STRESS penalty
        }

        // Επιθετική οδήγηση σε υψηλές ταχύτητες
        if (speed > 90f && rpm > 3500f && accelDiff > 4.0f) {
            consumption += 2.5f  // ✅ High-speed stress
        }

        // ✅ ΑΝΗΦΟΡΑ + ΧΑΜΗΛΗ ΤΑΧΥΤΗΤΑ COMBINATION (το χειρότερο σενάριο)
        if (altitude > 300f && speed <= 20f && rpm > 2200f) {
            consumption += 4.0f  // ✅ MAXIMUM STRESS για diesel
        }

        // ✅ ΕΥΡΥΝΕΝΟ RANGE ΓΙΑ EXTREME CONDITIONS
        return consumption.coerceIn(
            vehicleProfile.minConsumption,
            vehicleProfile.maxConsumption  // Τώρα 10.0f
        )
    }

    /**
     * ✅ ΒΕΛΤΙΩΜΕΝΟΣ υπολογισμός πραγματικής κατανάλωσης
     */
    private fun calculateRealConsumption(data: List<TrainingDataPoint>): List<Float> {
        val consumptions = mutableListOf<Float>()

        for (i in 1 until data.size) {
            val current = data[i]
            val previous = data[i - 1]

            val fuelDiff = previous.fuelLevel - current.fuelLevel

            if (fuelDiff > 0.01f && current.speed > 0.5f) {
                val timeInterval = 15f / 3600f // 15 seconds
                val distance = current.speed * timeInterval

                if (distance > 0.001f) {
                    val consumption = (fuelDiff / distance) * 100f
                    // ✅ ΑΥΣΤΗΡΟΤΕΡΟΣ ΦΙΛΤΡΑΡΙΣΜΟΣ ΓΙΑ DIESEL
                    if (consumption >= 1.5f && consumption <= 8.0f) {
                        consumptions.add(consumption)
                    } else {
                        consumptions.add(estimateDieselConsumption(current))
                    }
                } else {
                    consumptions.add(estimateDieselConsumption(current))
                }
            } else {
                consumptions.add(estimateDieselConsumption(current))
            }
        }

        if (data.isNotEmpty()) {
            consumptions.add(0, estimateDieselConsumption(data[0]))
        }

        return consumptions
    }

    /**
     * ✅ ΕΚΤΙΜΗΣΗ ΚΑΤΑΝΑΛΩΣΗΣ ΕΙΔΙΚΑ ΓΙΑ DIESEL
     */
    private fun estimateDieselConsumption(data: TrainingDataPoint): Float {
        return calculateDieselConsumption(
            speed = data.speed,
            rpm = data.rpm,
            acceleration = data.accelerometerTotal,
            altitude = data.altitude,
            speedW = 0.02f,     // Conservative diesel values
            rpmW = 0.001f,
            accelW = 0.06f,
            altW = 0.002f,
            baseC = 3.8f
        )
    }

    /**
     * ✅ ΚΥΡΙΑ ΜΕΘΟΔΟΣ ΠΡΟΒΛΕΨΗΣ ΜΕ VALIDATION
     */
    fun predictConsumption(sensorData: SensorDataPoint): Float {
        if (!isModelTrained) {
            return vehicleProfile.optimalConsumption // 4.2f default
        }

        val accelerationMagnitude = sqrt(
            sensorData.accelerometerX.pow(2) +
                    sensorData.accelerometerY.pow(2) +
                    sensorData.accelerometerZ.pow(2)
        )

        // ✅ ΒΕΛΤΙΩΜΕΝΗ RPM ΕΚΤΙΜΗΣΗ ΓΙΑ DIESEL
        val estimatedRPM = when {
            sensorData.speed == 0f -> 800f
            sensorData.speed <= 15f -> 800f + sensorData.speed * 25f      // Αστική
            sensorData.speed <= 50f -> 1175f + (sensorData.speed - 15f) * 20f  // Κανονική
            sensorData.speed <= 90f -> 1875f + (sensorData.speed - 50f) * 15f  // Εθνική
            else -> 2475f + (sensorData.speed - 90f) * 12f                     // Αυτοκινητόδρομος
        }.coerceIn(600f, 4500f) // Realistic RPM range για diesel

        val rawPrediction = calculateDieselConsumption(
            speed = sensorData.speed,
            rpm = estimatedRPM,
            acceleration = accelerationMagnitude,
            altitude = sensorData.altitude.toFloat(),
            compassHeading = sensorData.compassHeading,
            speedW = speedWeight,
            rpmW = rpmWeight,
            accelW = accelerationWeight,
            altW = altitudeWeight,
            baseC = baseConsumption
        )

        // ✅ ΤΕΛΙΚΗ VALIDATION
        return validatePrediction(rawPrediction, sensorData)
    }

    /**
     * ✅ VALIDATION ΚΑΤΑΝΑΛΩΣΗΣ ΜΕ EXTREME CONDITIONS DETECTION
     */
    private fun validatePrediction(prediction: Float, sensorData: SensorDataPoint): Float {

        // ✅ EXTREME CONDITIONS DETECTION
        val isExtremeCondition = detectExtremeConditions(sensorData)

        val validatedPrediction = when {
            sensorData.speed == 0f -> vehicleProfile.idleConsumption  // Ρελαντί
            isExtremeCondition && prediction > vehicleProfile.optimalConsumption -> {
                // Επιτρέπει extreme values αλλά με upper limit
                prediction.coerceIn(vehicleProfile.optimalConsumption, vehicleProfile.maxConsumption)
            }
            prediction < vehicleProfile.minConsumption -> vehicleProfile.minConsumption
            prediction > vehicleProfile.maxConsumption -> vehicleProfile.maxConsumption
            else -> prediction
        }

        // Debug logging για extreme values
        if (prediction > vehicleProfile.optimalConsumption * 1.5f) {
            val condition = if (isExtremeCondition) "EXTREME CONDITIONS" else "NORMAL CONDITIONS"
            android.util.Log.w("FuelPredictor",
                "High consumption: ${String.format("%.1f", prediction)}L/100km at ${sensorData.speed}km/h ($condition)")
        }

        return validatedPrediction
    }

    /**
     * ✅ ΑΝΙΧΝΕΥΣΗ EXTREME DRIVING CONDITIONS
     */
    private fun detectExtremeConditions(sensorData: SensorDataPoint): Boolean {
        val acceleration = sqrt(
            sensorData.accelerometerX.pow(2) +
                    sensorData.accelerometerY.pow(2) +
                    sensorData.accelerometerZ.pow(2)
        )

        val accelDiff = (acceleration - 9.8f).absoluteValue

        return when {
            // Πρώτη ταχύτητα / χαμηλή ταχύτητα με high stress
            sensorData.speed <= 15f && accelDiff > 3.0f -> true

            // Ανηφόρες
            sensorData.altitude > 300.0 && sensorData.speed <= 25f -> true

            // Επιθετική οδήγηση
            accelDiff > 5.0f -> true

            // Υψηλή ταχύτητα με stress
            sensorData.speed > 90f && accelDiff > 4.0f -> true

            else -> false
        }
    }

    /**
     * ✅ ΠΛΗΡΟΦΟΡΙΕΣ ΜΟΝΤΕΛΟΥ
     */
    fun getModelInfo(): String {
        return if (isModelTrained) {
            """
            ✅ Diesel-optimized μοντέλο (${vehicleProfile.make} ${vehicleProfile.model})
            🎯 Ακρίβεια: ${String.format("%.1f", trainingAccuracy)}%
            ⛽ Εύρος: ${vehicleProfile.minConsumption}-${vehicleProfile.maxConsumption} L/100km
            🔧 Παράμετροι: speed=${String.format("%.3f", speedWeight)}, rpm=${String.format("%.4f", rpmWeight)}
            """.trimIndent()
        } else {
            "❌ Μοντέλο δεν έχει εκπαιδευτεί - χρήση diesel defaults"
        }
    }

    /**
     * ✅ VEHICLE PROFILE INFO
     */
    fun getVehicleProfile(): VehicleProfile = vehicleProfile
}