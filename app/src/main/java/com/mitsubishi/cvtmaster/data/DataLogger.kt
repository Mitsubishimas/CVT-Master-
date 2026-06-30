package com.mitsubishi.cvtmaster.data

import android.content.Context
import com.mitsubishi.cvtmaster.elm327.JatcoCVTData
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.*

class DataLogger(private val context: Context) {
    
    private val logEntries = mutableListOf<LogEntry>()
    private val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
    
    data class LogEntry(val timestamp: Long = System.currentTimeMillis(), val data: JatcoCVTData)
    
    fun addEntry(data: JatcoCVTData) {
        logEntries.add(LogEntry(data = data))
        if (logEntries.size > 5000) logEntries.removeAt(0)
    }
    
    fun exportToCSV(): File? {
        return try {
            val dir = File(context.filesDir, "logs")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, "cvt_data_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date()) + ".csv")
            FileWriter(file).use { w ->
                w.write("Timestamp,Temp,RPM,Speed,Throttle,Degradation,Pressure1,Pressure2\n")
                for (e in logEntries) {
                    w.write("${e.timestamp},${e.data.oilTemperature},${e.data.engineRPM},${e.data.vehicleSpeed},${e.data.throttlePosition},${e.data.oilDegradation},${e.data.primaryPressure},${e.data.secondaryPressure}\n")
                }
            }
            // Очищаем старые CSV (старше 7 дней)
            dir.listFiles()?.filter { it.name.endsWith(".csv") && it.lastModified() < System.currentTimeMillis() - 604800000 }?.forEach { it.delete() }
            file
        } catch (e: Exception) { null }
    }
    
    fun getEntryCount(): Int = logEntries.size
    fun clearLogs() { logEntries.clear() }
}
