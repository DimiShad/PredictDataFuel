package com.example.predictdatafuel

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class CSVManager(private val context: Context) {

    // ΕΝΗΜΕΡΩΜΕΝΟ HEADER για μαγνητόμετρο αντί για γυροσκόπιο
    private val CSV_HEADER = "timestamp,accelerometer_x,accelerometer_y,accelerometer_z,magnetometer_x,magnetometer_y,magnetometer_z,compass_heading,latitude,longitude,speed,altitude,speed_accuracy,bearing"

    fun exportToCSV(dataList: List<SensorDataPoint>): String {
        return try {
            // ΔΗΜΙΟΥΡΓΙΑ ΦΑΚΕΛΟΥ
            val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "FuelData")
            if (!folder.exists()) {
                folder.mkdirs()
            }

            // ΟΝΟΜΑ ΑΡΧΕΙΟΥ ΜΕ TIMESTAMP
            val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
            val fileName = "fuel_data_${dateFormat.format(Date())}.csv"
            val file = File(folder, fileName)

            // ΓΡΑΨΙΜΟ CSV
            val writer = FileWriter(file)

            // HEADER
            writer.append(CSV_HEADER)
            writer.append("\n")

            // ΔΕΔΟΜΕΝΑ με νέα δομή SensorDataPoint
            for (data in dataList) {
                writer.append("${data.timestamp},")
                writer.append("${data.accelerometerX},")
                writer.append("${data.accelerometerY},")
                writer.append("${data.accelerometerZ},")
                writer.append("${data.magnetometerX},")        // Μαγνητόμετρο αντί για γυροσκόπιο
                writer.append("${data.magnetometerY},")
                writer.append("${data.magnetometerZ},")
                writer.append("${data.compassHeading},")       // Πυξίδα
                writer.append("${data.latitude},")
                writer.append("${data.longitude},")
                writer.append("${data.speed},")
                writer.append("${data.altitude},")
                writer.append("${data.speedAccuracy},")        // Νέα πεδία
                writer.append("${data.bearing}")
                writer.append("\n")
            }

            writer.close()

            "✅ Εξήχθησαν ${dataList.size} δεδομένα στο:\n${file.absolutePath}"

        } catch (e: IOException) {
            "❌ Σφάλμα εξαγωγής: ${e.message}"
        }
    }

    fun loadFromCSV(): Pair<List<SensorDataPoint>, String> {
        return try {
            val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "FuelData")

            if (!folder.exists() || folder.listFiles()?.isEmpty() == true) {
                return Pair(emptyList(), "❌ Δεν βρέθηκαν αρχεία CSV")
            }

            // ΒΡΕΣ ΤΟ ΠΙΟ ΠΡΟΣΦΑΤΟ ΑΡΧΕΙΟ
            val csvFiles = folder.listFiles { _, name -> name.endsWith(".csv") }
            if (csvFiles.isNullOrEmpty()) {
                return Pair(emptyList(), "❌ Δεν βρέθηκαν αρχεία CSV")
            }

            val latestFile = csvFiles.maxByOrNull { it.lastModified() }!!

            // ΔΙΑΒΑΣΜΑ CSV
            val lines = latestFile.readLines()
            if (lines.size < 2) {
                return Pair(emptyList(), "❌ Κενό αρχείο CSV")
            }

            val dataList = mutableListOf<SensorDataPoint>()

            // ΠΑΡΑΛΗΨΗ HEADER (γραμμή 0) ΚΑΙ ΔΙΑΒΑΣΜΑ ΔΕΔΟΜΕΝΩΝ
            for (i in 1 until lines.size) {
                val values = lines[i].split(",")

                // Έλεγχος για τον ελάχιστο αριθμό στηλών
                if (values.size >= 12) { // Τουλάχιστον τα βασικά πεδία
                    try {
                        val dataPoint = SensorDataPoint(
                            timestamp = values[0].toLongOrNull() ?: System.currentTimeMillis(),
                            accelerometerX = values[1].toFloatOrNull() ?: 0f,
                            accelerometerY = values[2].toFloatOrNull() ?: 0f,
                            accelerometerZ = values[3].toFloatOrNull() ?: 0f,
                            magnetometerX = values[4].toFloatOrNull() ?: 0f,     // Μαγνητόμετρο
                            magnetometerY = values[5].toFloatOrNull() ?: 0f,
                            magnetometerZ = values[6].toFloatOrNull() ?: 0f,
                            compassHeading = values[7].toFloatOrNull() ?: 0f,    // Πυξίδα
                            latitude = values[8].toDoubleOrNull() ?: 0.0,
                            longitude = values[9].toDoubleOrNull() ?: 0.0,
                            speed = values[10].toFloatOrNull() ?: 0f,
                            altitude = values[11].toDoubleOrNull() ?: 0.0,
                            speedAccuracy = if (values.size > 12) values[12].toFloatOrNull() ?: 0f else 0f,
                            bearing = if (values.size > 13) values[13].toFloatOrNull() ?: 0f else 0f
                        )
                        dataList.add(dataPoint)
                    } catch (e: NumberFormatException) {
                        // ΠΑΡΑΛΗΨΗ ΛΑΝΘΑΣΜΕΝΩΝ ΓΡΑΜΜΩΝ
                        continue
                    }
                }
            }

            Pair(dataList, "✅ Φορτώθηκαν ${dataList.size} δεδομένα από:\n${latestFile.name}")

        } catch (e: Exception) {
            Pair(emptyList(), "❌ Σφάλμα φόρτωσης: ${e.message}")
        }
    }

    fun getCSVFiles(): List<File> {
        val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "FuelData")
        return folder.listFiles { _, name -> name.endsWith(".csv") }?.toList() ?: emptyList()
    }

    fun deleteAllCSVs(): String {
        return try {
            val folder = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), "FuelData")
            val csvFiles = folder.listFiles { _, name -> name.endsWith(".csv") }

            if (csvFiles.isNullOrEmpty()) {
                "❌ Δεν βρέθηκαν αρχεία για διαγραφή"
            } else {
                val deletedCount = csvFiles.count { it.delete() }
                "✅ Διαγράφηκαν $deletedCount αρχεία CSV"
            }
        } catch (e: Exception) {
            "❌ Σφάλμα διαγραφής: ${e.message}"
        }
    }
}