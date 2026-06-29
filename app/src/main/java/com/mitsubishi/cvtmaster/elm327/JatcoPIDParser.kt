package com.mitsubishi.cvtmaster.elm327

import kotlin.math.roundToInt

data class JatcoCVTData(
    // Температурные параметры
    val oilTemperature: Float = 0.0f,           // °C
    val oilTemperature2: Float = 0.0f,          // °C (второй датчик)
    val engineCoolantTemp: Float = 0.0f,        // °C
    
    // Давления
    val primaryPressure: Float = 0.0f,          // MPa
    val secondaryPressure: Float = 0.0f,        // MPa
    val linePressure: Float = 0.0f,             // MPa
    val pilotPressure: Float = 0.0f,            // MPa
    
    // Состояние износа
    val oilDegradation: Int = 0,                // 0-255 (210+ = критично)
    val beltWearIndex: Int = 0,                 // 0-100%
    val clutchWearIndex: Int = 0,               // 0-100%
    
    // Динамические параметры
    val engineRPM: Int = 0,
    val primaryRPM: Int = 0,
    val secondaryRPM: Int = 0,
    val gearRatio: Float = 0.0f,
    val calculatedRatio: Float = 0.0f,
    
    // Состояния
    val torqueConverterLockup: TCCState = TCCState.UNLOCKED,
    val gearPosition: GearPosition = GearPosition.PARK,
    val stepMotorPosition: Int = 0,             // шаги
    val stepMotorTarget: Int = 0,               // шаги
    
    // Дополнительно
    val vehicleSpeed: Float = 0.0f,             // km/h
    val throttlePosition: Float = 0.0f,         // %
    val brakeSwitch: Boolean = false,
    val sportMode: Boolean = false,
    val errorFlags: Int = 0
)

enum class TCCState {
    UNLOCKED, SLIPPING, LOCKED, LOCKUP_OFF
}

enum class GearPosition {
    PARK, REVERSE, NEUTRAL, DRIVE, MANUAL, SPORT
}

class JatcoPIDParser {
    
    companion object {
        // ============================================
        // Jatco CVT Specific PIDs (CAN 29bit)
        // ============================================
        
        // Базовые PID для запросов
        const val PID_OIL_TEMP = "2105"
        const val PID_OIL_TEMP_2 = "2106" 
        const val PID_PRIMARY_PRESSURE = "2107"
        const val PID_SECONDARY_PRESSURE = "2108"
        const val PID_LINE_PRESSURE = "2109"
        const val PID_OIL_DEGRADATION = "210A"
        const val PID_ENGINE_RPM = "210C"
        const val PID_PRIMARY_RPM = "210D"
        const val PID_SECONDARY_RPM = "210E"
        const val PID_GEAR_RATIO = "210F"
        const val PID_STEP_MOTOR = "2110"
        const val PID_TCC_STATUS = "2111"
        const val PID_GEAR_POSITION = "2112"
        const val PID_VEHICLE_SPEED = "210D"  // Дублируется с OBD стандартным
        const val PID_BELT_WEAR = "2113"
        const val PID_CLUTCH_WEAR = "2114"
        const val PID_ERROR_FLAGS = "2115"
        const val PID_PILOT_PRESSURE = "2116"
        
        // Специальные сервисные PID
        const val PID_OIL_DEGRADATION_RESET = "3000"
        const val PID_CVT_RELEARN = "3001"
        const val PID_STEP_MOTOR_CALIBRATION = "3002"
        
        // CAN ID для разных блоков
        const val CAN_ID_TCM = 0x7E2  // Transmission Control Module
        const val CAN_ID_ECM = 0x7E0  // Engine Control Module
        
        // Формулы конвертации для Jatco
        fun parseOilTemp(bytes: ByteArray): Float {
            // Формула: (A * 0.75) - 40
            return if (bytes.size >= 2) {
                val raw = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                (raw * 0.75f) - 40f
            } else 0f
        }
        
        fun parsePressure(bytes: ByteArray): Float {
            // Формула: A * 0.01 (в MPa)
            return if (bytes.size >= 2) {
                val raw = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                raw * 0.01f
            } else 0f
        }
        
        fun parseDegradation(bytes: ByteArray): Int {
            // Прямое значение 0-255
            return if (bytes.isNotEmpty()) {
                bytes[0].toInt() and 0xFF
            } else 0
        }
        
        fun parseRPM(bytes: ByteArray): Int {
            // Формула: ((A*256)+B) / 4
            return if (bytes.size >= 2) {
                val raw = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                raw / 4
            } else 0
        }
        
        fun parseGearRatio(bytes: ByteArray): Float {
            // Формула: A/100
            return if (bytes.isNotEmpty()) {
                (bytes[0].toInt() and 0xFF) / 100f
            } else 0f
        }
        
        fun parseTCCStatus(bytes: ByteArray): TCCState {
            if (bytes.isEmpty()) return TCCState.UNLOCKED
            return when (bytes[0].toInt() and 0xFF) {
                0 -> TCCState.UNLOCKED
                1 -> TCCState.SLIPPING
                2 -> TCCState.LOCKED
                3 -> TCCState.LOCKUP_OFF
                else -> TCCState.UNLOCKED
            }
        }
        
        fun parseGearPosition(bytes: ByteArray): GearPosition {
            if (bytes.isEmpty()) return GearPosition.PARK
            return when (bytes[0].toInt() and 0xFF) {
                0 -> GearPosition.PARK
                1 -> GearPosition.REVERSE
                2 -> GearPosition.NEUTRAL
                3 -> GearPosition.DRIVE
                4 -> GearPosition.MANUAL
                5 -> GearPosition.SPORT
                else -> GearPosition.PARK
            }
        }
    }
    
    /**
     * Парсинг полного ответа от ELM327
     */
    fun parseELM327Response(rawResponse: String): JatcoCVTData? {
        try {
            // Пример ответа: "7E8 06 41 00 BE 1F B8 13 AA"
            val lines = rawResponse.split("\r", "\n", ">")
            var cvtData = JatcoCVTData()
            
            for (line in lines) {
                val cleanLine = line.trim()
                if (cleanLine.isEmpty() || cleanLine.startsWith(">")) continue
                
                // Извлекаем данные из CAN фрейма
                val parts = cleanLine.split(" ")
                if (parts.size < 4) continue
                
                val canId = parts[0]  // 7E2 или 7E8
                val dataBytes = parts.drop(3)  // байты данных
                
                if (dataBytes.isEmpty()) continue
                
                // Конвертируем hex строки в ByteArray
                val bytes = dataBytes.map { it.toInt(16).toByte() }.toByteArray()
                
                // Определяем PID из второго байта
                val pid = if (parts.size > 3) parts[3] else ""
                
                // Парсим в зависимости от PID
                when (pid.uppercase()) {
                    PID_OIL_TEMP -> {
                        cvtData = cvtData.copy(
                            oilTemperature = parseOilTemp(bytes.drop(1).toByteArray())
                        )
                    }
                    PID_PRIMARY_PRESSURE -> {
                        cvtData = cvtData.copy(
                            primaryPressure = parsePressure(bytes.drop(1).toByteArray())
                        )
                    }
                    PID_SECONDARY_PRESSURE -> {
                        cvtData = cvtData.copy(
                            secondaryPressure = parsePressure(bytes.drop(1).toByteArray())
                        )
                    }
                    PID_OIL_DEGRADATION -> {
                        cvtData = cvtData.copy(
                            oilDegradation = parseDegradation(bytes.drop(1).toByteArray())
                        )
                    }
                    PID_ENGINE_RPM -> {
                        cvtData = cvtData.copy(
                            engineRPM = parseRPM(bytes.drop(1).toByteArray())
                        )
                    }
                    PID_STEP_MOTOR -> {
                        if (bytes.size > 1) {
                            cvtData = cvtData.copy(
                                stepMotorPosition = bytes[1].toInt() and 0xFF
                            )
                        }
                    }
                    PID_TCC_STATUS -> {
                        cvtData = cvtData.copy(
                            torqueConverterLockup = parseTCCStatus(bytes.drop(1).toByteArray())
                        )
                    }
                    PID_GEAR_POSITION -> {
                        cvtData = cvtData.copy(
                            gearPosition = parseGearPosition(bytes.drop(1).toByteArray())
                        )
                    }
                    PID_BELT_WEAR -> {
                        if (bytes.size > 1) {
                            cvtData = cvtData.copy(
                                beltWearIndex = bytes[1].toInt() and 0xFF
                            )
                        }
                    }
                }
            }
            
            // Рассчитываем передаточное отношение
            if (cvtData.primaryRPM > 0 && cvtData.secondaryRPM > 0) {
                cvtData = cvtData.copy(
                    calculatedRatio = cvtData.primaryRPM.toFloat() / cvtData.secondaryRPM.toFloat()
                )
            }
            
            return cvtData
            
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
    
    /**
     * Создание команды для запроса PID
     */
    fun createPIDRequest(pid: String, canId: Int = CAN_ID_TCM): String {
        return String.format("%04X 02 21 %02X 00 00 00 00", canId, pid.toInt(16))
    }
    
    /**
     * Создание сервисной команды
     */
    fun createServiceCommand(pid: String, data: ByteArray = byteArrayOf()): String {
        val canId = CAN_ID_TCM
        val header = String.format("%04X", canId)
        return "$header 02 30 ${pid} 00 00 00 00"
    }
}
