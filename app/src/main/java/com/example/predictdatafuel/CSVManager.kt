package com.example.predictdatafuel

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class CSVManager(private val context: Context) {

    private val CSV_HEADER = "timestamp,accelerometer_x,accelerometer_y,accelerometer_z,gyroscope_x,gyroscope_y,gyroscope_z,latitude,longitude,speed,altitude"

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

            // ΔΕΔΟΜΕΝΑ
            for (data in dataList) {
                writer.append("${data.timestamp},")
                writer.append("${data.accelerometerX},")
                writer.append("${data.accelerometerY},")
                writer.append("${data.accelerometerZ},")
                writer.append("${data.gyroscopeX},")
                writer.append("${data.gyroscopeY},")
                writer.append("${data.gyroscopeZ},")
                writer.append("${data.latitude},")
                writer.append("${data.longitude},")
                writer.append("${data.speed},")
                writer.append("${data.altitude}")
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
                if (values.size >= 11) {
                    try {
                        val dataPoint = SensorDataPoint(
                            timestamp = values[0].toLong(),
                            accelerometerX = values[1].toFloat(),
                            accelerometerY = values[2].toFloat(),
                            accelerometerZ = values[3].toFloat(),
                            gyroscopeX = values[4].toFloat(),
                            gyroscopeY = values[5].toFloat(),
                            gyroscopeZ = values[6].toFloat(),
                            latitude = values[7].toDouble(),
                            longitude = values[8].toDouble(),
                            speed = values[9].toFloat(),
                            altitude = values[10].toDouble()
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
