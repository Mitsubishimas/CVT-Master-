package com.mitsubishi.cvtmaster.data

import android.content.Context
import android.os.Environment
import com.mitsubishi.cvtmaster.elm327.JatcoCVTData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val data: JatcoCVTData,
    val notes: String = ""
)

class DataLogger(private val context: Context) {
    
    private val logEntries = mutableListOf<LogEntry>()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.getDefault())
    
    companion object {
        const val LOG_DIR = "CVT_Master_Logs"
        const val CSV_HEADER = "Timestamp,OilTemp,PrimaryPressure,SecondaryPressure," +
                "OilDegradation,EngineRPM,GearRatio,BeltWear,TCC_Status,Gear,VehicleSpeed\n"
    }
    
    /**
     * Добавление записи в лог
     */
    fun addEntry(data: JatcoCVTData, notes: String = "") {
        logEntries.add(LogEntry(data = data, notes = notes))
        
        // Автосохранение при превышении 1000 записей
        if (logEntries.size >= 1000) {
            autoSave()
        }
    }
    
    /**
     * Автосохранение логов
     */
    private fun autoSave() {
        val logsDir = getLogsDirectory()
        if (logsDir != null) {
            val fileName = "autosave_${dateFormat.format(Date())}.csv"
            val file = File(logsDir, fileName)
            saveToCSV(file)
            logEntries.clear()
        }
    }
    
    /**
     * Экспорт в CSV
     */
    suspend fun exportToCSV(): File? {
        return withContext(Dispatchers.IO) {
            try {
                val logsDir = getLogsDirectory() ?: return@withContext null
                val fileName = "cvt_log_${dateFormat.format(Date())}.csv"
                val file = File(logsDir, fileName)
                saveToCSV(file)
                file
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    
    /**
     * Сохранение в CSV формат
     */
    private fun saveToCSV(file: File) {
        try {
            BufferedWriter(FileWriter(file)).use { writer ->
                writer.write(CSV_HEADER)
                
                for (entry in logEntries) {
                    val data = entry.data
                    val line = "${entry.timestamp}," +
                            "${data.oilTemperature}," +
                            "${data.primaryPressure}," +
                            "${data.secondaryPressure}," +
                            "${data.oilDegradation}," +
                            "${data.engineRPM}," +
                            "${data.gearRatio}," +
                            "${data.beltWearIndex}," +
                            "${data.torqueConverterLockup}," +
                            "${data.gearPosition}," +
                            "${data.vehicleSpeed}\n"
                    writer.write(line)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    /**
     * Экспорт в JSON (для детального анализа)
     */
    suspend fun exportToJSON(): File? {
        return withContext(Dispatchers.IO) {
            try {
                val logsDir = getLogsDirectory() ?: return@withContext null
                val fileName = "cvt_log_${dateFormat.format(Date())}.json"
                val file = File(logsDir, fileName)
                
                val jsonArray = JSONArray()
                
                for (entry in logEntries) {
                    val jsonObj = JSONObject().apply {
                        put("timestamp", entry.timestamp)
                        put("date", SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(Date(entry.timestamp)))
                        put("oilTemperature", entry.data.oilTemperature)
                        put("primaryPressure", entry.data.primaryPressure)
                        put("secondaryPressure", entry.data.secondaryPressure)
                        put("oilDegradation", entry.data.oilDegradation)
                        put("engineRPM", entry.data.engineRPM)
                        put("gearRatio", entry.data.gearRatio)
                        put("beltWearIndex", entry.data.beltWearIndex)
                        put("tccState", entry.data.torqueConverterLockup.name)
                        put("gearPosition", entry.data.gearPosition.name)
                        put("vehicleSpeed", entry.data.vehicleSpeed)
                        put("errorFlags", entry.data.errorFlags)
                        if (entry.notes.isNotEmpty()) {
                            put("notes", entry.notes)
                        }
                    }
                    jsonArray.put(jsonObj)
                }
                
                file.writeText(jsonArray.toString(2))
                file
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    
    /**
     * Экспорт полного отчета в PDF (текстовый)
     */
    suspend fun exportReport(): File? {
        return withContext(Dispatchers.IO) {
            try {
                val logsDir = getLogsDirectory() ?: return@withContext null
                val fileName = "cvt_report_${dateFormat.format(Date())}.txt"
                val file = File(logsDir, fileName)
                
                BufferedWriter(FileWriter(file)).use { writer ->
                    writer.write("=" .repeat(50) + "\n")
                    writer.write("CVT MASTER - ОТЧЕТ ДИАГНОСТИКИ\n")
                    writer.write("=" .repeat(50) + "\n")
                    writer.write("Дата: ${SimpleDateFormat("dd.MM.yyyy HH:mm").format(Date())}\n")
                    writer.write("Записей: ${logEntries.size}\n\n")
                    
                    // Статистика
                    if (logEntries.isNotEmpty()) {
                        val temps = logEntries.map { it.data.oilTemperature }
                        val pressures = logEntries.map { it.data.primaryPressure }
                        val degradations = logEntries.map { it.data.oilDegradation }
                        
                        writer.write("--- СТАТИСТИКА ---\n")
                        writer.write("Температура масла CVT:\n")
                        writer.write("  Мин: ${"%.1f".format(temps.minOrNull())}°C\n")
                        writer.write("  Макс: ${"%.1f".format(temps.maxOrNull())}°C\n")
                        writer.write("  Сред: ${"%.1f".format(temps.average())}°C\n\n")
                        
                        writer.write("Давление первичного вала:\n")
                        writer.write("  Мин: ${"%.2f".format(pressures.minOrNull())} MPa\n")
                        writer.write("  Макс: ${"%.2f".format(pressures.maxOrNull())} MPa\n")
                        writer.write("  Сред: ${"%.2f".format(pressures.average())} MPa\n\n")
                        
                        writer.write("Деградация масла: ${degradations.lastOrNull() ?: 0}%\n")
                        
                        if ((degradations.lastOrNull() ?: 0) > 180) {
                            writer.write("⚠️ ВНИМАНИЕ: Требуется замена масла CVT!\n")
                        }
                        
                        // События перегрева
                        val overheatEvents = temps.count { it > 110 }
                        if (overheatEvents > 0) {
                            writer.write("⚠️ Обнаружено $overheatEvents событий перегрева!\n")
                        }
                    }
                    
                    writer.write("\n--- ПОСЛЕДНИЕ ЗНАЧЕНИЯ ---\n")
                    logEntries.lastOrNull()?.let { entry ->
                        writer.write("Температура: ${entry.data.oilTemperature}°C\n")
                        writer.write("Давление: ${entry.data.primaryPressure} MPa\n")
                        writer.write("Обороты: ${entry.data.engineRPM} RPM\n")
                        writer.write("Передача: ${entry.data.gearPosition}\n")
                        writer.write("Блокировка ГТ: ${entry.data.torqueConverterLockup}\n")
                        writer.write("Износ ремня: ${entry.data.beltWearIndex}%\n")
                    }
                }
                
                file
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }
    
    /**
     * Получение директории для логов
     */
    private fun getLogsDirectory(): File? {
        return try {
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    Environment.DIRECTORY_DOCUMENTS
                ),
                LOG_DIR
            )
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir
        } catch (e: Exception) {
            // Fallback на внутреннее хранилище
            val dir = File(context.filesDir, LOG_DIR)
            if (!dir.exists()) {
                dir.mkdirs()
            }
            dir
        }
    }
    
    /**
     * Очистка логов
     */
    fun clearLogs() {
        logEntries.clear()
    }
    
    /**
     * Получение количества записей
     */
    fun getEntryCount(): Int = logEntries.size
}
