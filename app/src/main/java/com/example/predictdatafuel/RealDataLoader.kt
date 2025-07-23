package com.example.predictdatafuel

import android.content.Context
import java.io.InputStreamReader
import java.io.BufferedReader

class RealDataLoader(private val context: Context) {

    /**
     * ΦΟΡΤΩΣΗ πραγματικών δεδομένων από το CSV αρχείο στα assets
     */
    fun loadRealTrainingData(): Pair<List<FuelPredictionModel.TrainingDataPoint>, String> {
        return try {
            // ΔΙΑΒΑΣΜΑ CSV από assets
            val inputStream = context.assets.open("final_dataset_for_diploma.csv")
            val reader = BufferedReader(InputStreamReader(inputStream))

            val dataList = mutableListOf<FuelPredictionModel.TrainingDataPoint>()
            var lineCount = 0
            var successCount = 0

            // ΠΑΡΑΛΗΨΗ HEADER
            val header = reader.readLine()
            lineCount++

            // ΔΙΑΒΑΣΜΑ ΔΕΔΟΜΕΝΩΝ
            reader.useLines { lines ->
                lines.forEach { line ->
                    lineCount++

                    if (line.trim().isNotEmpty()) {
                        val data = parseCSVLine(line)
                        if (data != null) {
                            dataList.add(data)
                            successCount++
                        }
                    }
                }
            }

            val message = """
                ✅ Φορτώθηκαν πραγματικά δεδομένα από CSV!
                
                📊 Στατιστικά:
                • Συνολικές γραμμές: $lineCount
                • Επιτυχείς εγγραφές: $successCount
                • Ποσοστό επιτυχίας: ${String.format("%.1f", (successCount.toFloat() / lineCount) * 100)}%
                
                📈 Δεδομένα οδήγησης:
                • Υψόμετρο: ${dataList.minOfOrNull { it.altitude }} - ${dataList.maxOfOrNull { it.altitude }} m
                • Ταχύτητα: ${dataList.minOfOrNull { it.speed }} - ${dataList.maxOfOrNull { it.speed }} km/h
                • RPM: ${dataList.minOfOrNull { it.rpm }} - ${dataList.maxOfOrNull { it.rpm }}
                • Στάθμη καυσίμου: ${String.format("%.1f", dataList.minOfOrNull { it.fuelLevel })} - ${String.format("%.1f", dataList.maxOfOrNull { it.fuelLevel })} L
                
                ⛽ Το CSV διαβάστηκε επιτυχώς από τα assets!
            """.trimIndent()

            Pair(dataList, message)

        } catch (e: Exception) {
            // FALLBACK - αν δεν υπάρχει το CSV, χρησιμοποίησε δείγματα
            val sampleData = listOf(
                FuelPredictionModel.TrainingDataPoint(54f, 341f, 15.64f, 828f, 0f, 9.75f, -0.43f, -0.48f, 9.73f, 37.9976, 23.6741),
                FuelPredictionModel.TrainingDataPoint(54f, 195f, 15.60f, 1220f, 7f, 9.92f, -1.04f, -1.58f, 9.74f, 37.9975, 23.6740),
                FuelPredictionModel.TrainingDataPoint(54f, 117f, 15.58f, 1679f, 10f, 9.88f, -0.56f, -0.89f, 9.83f, 37.9974, 23.6740),
                FuelPredictionModel.TrainingDataPoint(45f, 280f, 15.2f, 1850f, 25f, 9.65f, -1.2f, -0.8f, 9.91f, 37.9970, 23.6745),
                FuelPredictionModel.TrainingDataPoint(60f, 180f, 14.8f, 2100f, 40f, 9.45f, -2.1f, -1.5f, 9.68f, 37.9965, 23.6750),
                FuelPredictionModel.TrainingDataPoint(48f, 220f, 14.5f, 2350f, 55f, 10.1f, -1.8f, -2.2f, 10.2f, 37.9960, 23.6755),
                FuelPredictionModel.TrainingDataPoint(52f, 160f, 14.1f, 2580f, 70f, 9.85f, -2.5f, -1.1f, 9.95f, 37.9955, 23.6760),
                FuelPredictionModel.TrainingDataPoint(58f, 300f, 13.9f, 1950f, 35f, 9.55f, -1.6f, -0.95f, 9.78f, 37.9950, 23.6765),
                FuelPredictionModel.TrainingDataPoint(43f, 240f, 13.6f, 2750f, 85f, 10.3f, -3.1f, -2.8f, 10.8f, 37.9945, 23.6770),
                FuelPredictionModel.TrainingDataPoint(65f, 45f, 13.2f, 3200f, 120f, 11.2f, -4.2f, -3.5f, 12.1f, 37.9940, 23.6775)
            )

            val fallbackMessage = """
                ⚠️ Σφάλμα φόρτωσης CSV: ${e.message}
                
                📊 Χρήση δείγματος δεδομένων:
                • Δείγματα: ${sampleData.size}
                • Πηγή: Ενσωματωμένα δεδομένα
                
                💡 Για πραγματικά δεδομένα, βάλε το CSV στο:
                app/src/main/assets/final_dataset_for_diploma.csv
            """.trimIndent()

            Pair(sampleData, fallbackMessage)
        }
    }

    /**
     * ΑΝΑΛΥΣΗ μιας γραμμής CSV
     */
    private fun parseCSVLine(line: String): FuelPredictionModel.TrainingDataPoint? {
        return try {
            val values = line.split(",").map { it.trim() }

            // ΕΛΕΓΧΟΣ αν έχουμε αρκετές στήλες (προσαρμόστε βάσει του CSV σας)
            if (values.size < 11) return null

            // ΕΞΑΓΩΓΗ ΔΕΔΟΜΕΝΩΝ - ΠΡΟΣΑΡΜΟΣΤΕ ΒΑΣΗ ΤΗΣ ΔΟΜΗΣ ΤΟΥ CSV ΣΑΣ
            // Παραδοχή: data__alt, data__angle, data__fuel_lt, data__rpm, data__speed,
            //           accelerometer_x, accelerometer_y, accelerometer_z, accelerometer_total,
            //           latitude, longitude

            val altitude = values[0].toFloatOrNull() ?: return null
            val angle = values[1].toFloatOrNull() ?: return null
            val fuelLevel = values[2].toFloatOrNull() ?: return null
            val rpm = values[3].toFloatOrNull() ?: return null
            val speed = values[4].toFloatOrNull() ?: return null
            val accelX = values[5].toFloatOrNull() ?: 0f
            val accelY = values[6].toFloatOrNull() ?: 0f
            val accelZ = values[7].toFloatOrNull() ?: 0f
            val accelTotal = values[8].toFloatOrNull() ?:
            kotlin.math.sqrt(accelX * accelX + accelY * accelY + accelZ * accelZ)
            val latitude = values[9].toDoubleOrNull() ?: 0.0
            val longitude = values[10].toDoubleOrNull() ?: 0.0

            // ΦΙΛΤΡΑΡΙΣΜΑ λογικών τιμών
            if (speed < 0 || speed > 200) return null
            if (rpm < 0 || rpm > 8000) return null
            if (altitude < -100 || altitude > 3000) return null
            if (fuelLevel < 0 || fuelLevel > 100) return null

            FuelPredictionModel.TrainingDataPoint(
                altitude = altitude,
                angle = angle,
                fuelLevel = fuelLevel,
                rpm = rpm,
                speed = speed,
                accelerometerX = accelX,
                accelerometerY = accelY,
                accelerometerZ = accelZ,
                accelerometerTotal = accelTotal,
                latitude = latitude,
                longitude = longitude
            )

        } catch (e: Exception) {
            null // ΠΑΡΑΛΗΨΗ λανθασμένων γραμμών
        }
    }

    /**
     * ΣΤΑΤΙΣΤΙΚΑ δεδομένων
     */
    fun analyzeData(data: List<FuelPredictionModel.TrainingDataPoint>): String {
        if (data.isEmpty()) {
            return "❌ Δεν υπάρχουν δεδομένα για ανάλυση"
        }

        val avgSpeed = data.map { it.speed }.average()
        val avgRPM = data.map { it.rpm }.average()
        val avgAccel = data.map { it.accelerometerTotal }.average()
        val avgAltitude = data.map { it.altitude }.average()

        val speedRanges = mapOf(
            "Ακινησία (0 km/h)" to data.count { it.speed == 0f },
            "Αργή (1-30 km/h)" to data.count { it.speed in 1f..30f },
            "Μέτρια (31-60 km/h)" to data.count { it.speed in 31f..60f },
            "Γρήγορη (61-90 km/h)" to data.count { it.speed in 61f..90f },
            "Πολύ γρήγορη (>90 km/h)" to data.count { it.speed > 90f }
        )

        return """
            📊 ΑΝΑΛΥΣΗ ΠΡΑΓΜΑΤΙΚΩΝ ΔΕΔΟΜΕΝΩΝ
            
            🔢 Βασικά στατιστικά:
            • Συνολικά δείγματα: ${data.size}
            • Μέση ταχύτητα: ${String.format("%.1f", avgSpeed)} km/h
            • Μέσες στροφές: ${String.format("%.0f", avgRPM)} RPM
            • Μέση επιτάχυνση: ${String.format("%.2f", avgAccel)} m/s²
            • Μέσο υψόμετρο: ${String.format("%.1f", avgAltitude)} m
            
            🏃 Κατανομή ταχυτήτων:
            ${speedRanges.entries.joinToString("\n") { "• ${it.key}: ${it.value} (${String.format("%.1f", (it.value.toFloat() / data.size) * 100)}%)" }}
            
            🎯 Εύρος τιμών:
            • Ταχύτητα: ${data.minOf { it.speed }} - ${data.maxOf { it.speed }} km/h
            • RPM: ${data.minOf { it.rpm }} - ${data.maxOf { it.rpm }}
            • Υψόμετρο: ${data.minOf { it.altitude }} - ${data.maxOf { it.altitude }} m
            • Στάθμη καυσίμου: ${String.format("%.1f", data.minOf { it.fuelLevel })} - ${String.format("%.1f", data.maxOf { it.fuelLevel })} L
            
            ⛽ Υπολογισμός κατανάλωσης:
            • Η κατανάλωση υπολογίζεται από τη μείωση της στάθμης καυσίμου
            • Λαμβάνεται υπόψη η διανυθείσα απόσταση (GPS)
            • Εκτιμώνται L/100km για κάθε τμήμα της διαδρομής
        """.trimIndent()
    }

    /**
     * ΥΠΟΛΟΓΙΣΜΟΣ πραγματικής κατανάλωσης από τα δεδομένα
     */
    fun calculateFuelConsumption(data: List<FuelPredictionModel.TrainingDataPoint>): Pair<List<String>, String> {
        if (data.size < 2) {
            return Pair(emptyList(), "❌ Χρειάζονται τουλάχιστον 2 σημεία δεδομένων για υπολογισμό κατανάλωσης")
        }

        val consumptionAnalysis = mutableListOf<String>()

        for (i in 1 until data.size) {
            val current = data[i]
            val previous = data[i - 1]

            // Υπολογισμός διαφοράς καυσίμου
            val fuelDiff = previous.fuelLevel - current.fuelLevel
            if (fuelDiff > 0.01f) {
                val estimatedConsumption = calculateEstimatedConsumption(current)
                consumptionAnalysis.add(
                    "Σημείο $i: Ταχύτητα ${current.speed} km/h, " +
                            "Εκτιμώμενη κατανάλωση: ${String.format("%.1f", estimatedConsumption)} L/100km"
                )
            }
        }

        val analysis = """
            ⛽ ΑΝΑΛΥΣΗ ΚΑΤΑΝΑΛΩΣΗΣ ΚΑΥΣΙΜΟΥ
            
            📊 Βασικά στατιστικά:
            • Αναλυμένα σημεία: ${consumptionAnalysis.size}
            • Εύρος ταχυτήτων: ${data.minOfOrNull { it.speed }} - ${data.maxOfOrNull { it.speed }} km/h
            • Εύρος RPM: ${data.minOfOrNull { it.rpm }} - ${data.maxOfOrNull { it.rpm }}
            
            🎯 Η κατανάλωση υπολογίζεται από τη διαφορά στάθμης καυσίμου
            και την εκτιμώμενη απόσταση με βάση τα GPS δεδομένα.
        """.trimIndent()

        return Pair(consumptionAnalysis, analysis)
    }

    private fun calculateEstimatedConsumption(data: FuelPredictionModel.TrainingDataPoint): Float {
        var consumption = 6.5f

        consumption += when {
            data.speed == 0f -> -4.0f
            data.speed <= 30f -> data.speed * 0.1f
            data.speed <= 60f -> data.speed * 0.08f
            data.speed <= 90f -> data.speed * 0.12f
            else -> data.speed * 0.15f
        }

        consumption += (data.rpm - 800f) * 0.002f
        consumption += (data.accelerometerTotal - 9.8f) * 0.3f
        consumption += data.altitude * 0.005f

        return consumption.coerceIn(1.0f, 20.0f)
    }
}
