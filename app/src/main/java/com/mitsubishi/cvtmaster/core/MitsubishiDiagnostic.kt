package com.mitsubishi.cvtmaster.core

import kotlin.math.abs

class MitsubishiDiagnostic {
    
    data class CVTHealthReport(
        val oilCondition: OilCondition,
        val beltWear: Float,
        val pressureDeviation: Float,
        val temperatureProfile: TemperatureProfile,
        val recommendedActions: List<String>
    )
    
    enum class OilCondition {
        GOOD, NEED_CHANGE, BURNT, CRITICAL
    }
    
    data class TemperatureProfile(
        val averageTemp: Float,
        val maxTemp: Float,
        val overheatEvents: Int
    )
    
    fun analyzeCVTHealth(
        oilDegradation: Int,
        cvtTemp: Float,
        primaryPressure: Float,
        secondaryPressure: Float,
        mileage: Int
    ): CVTHealthReport {
        val recommendations = mutableListOf<String>()
        
        // Анализ деградации масла (специфично для Mitsubishi)
        val oilCondition = when {
            oilDegradation > 210 -> {
                recommendations.add("КРИТИЧЕСКИ: Немедленная замена масла CVT!")
                recommendations.add("Используйте только Mitsubishi CVT Fluid J4")
                OilCondition.CRITICAL
            }
            oilDegradation > 180 -> {
                recommendations.add("СРОЧНО: Запланируйте замену масла в ближайшее время")
                OilCondition.NEED_CHANGE
            }
            oilDegradation > 100 -> {
                recommendations.add("Рекомендуется проверить состояние масла")
                OilCondition.NEED_CHANGE
            }
            else -> OilCondition.GOOD
        }
        
        // Анализ износа ремня на основе давления
        val pressureDiff = abs(primaryPressure - secondaryPressure)
        val pressureDeviation = when {
            pressureDiff > 1.5 -> {
                recommendations.add("Обнаружена большая разница давлений - возможен износ ремня")
                0.8f
            }
            pressureDiff > 1.0 -> {
                recommendations.add("Небольшая разница давлений - следите за состоянием")
                0.5f
            }
            else -> 0.2f
        }
        
        // Анализ перегрева (проблема Mitsubishi CVT)
        val tempProfile = when {
            cvtTemp > 120 -> {
                recommendations.add("КРИТИЧЕСКИ: Перегрев вариатора! Остановитесь и дайте остыть")
                TemperatureProfile(averageTemp = cvtTemp, maxTemp = cvtTemp, overheatEvents = 1)
            }
            cvtTemp > 105 -> {
                recommendations.add("ПРЕДУПРЕЖДЕНИЕ: Повышенная температура вариатора")
                TemperatureProfile(averageTemp = cvtTemp, maxTemp = cvtTemp, overheatEvents = 1)
            }
            else -> TemperatureProfile(averageTemp = cvtTemp, maxTemp = cvtTemp, overheatEvents = 0)
        }
        
        return CVTHealthReport(
            oilCondition = oilCondition,
            beltWear = pressureDeviation * 100,
            pressureDeviation = pressureDiff,
            temperatureProfile = tempProfile,
            recommendedActions = recommendations
        )
    }
    
    companion object {
        // Специфичные параметры для популярных моделей Mitsubishi
        val MODEL_SPECS = mapOf(
            "Outlander" to ModelSpec(
                cvtModel = "Jatco JF016E",
                oilCapacity = 7.1f,
                normalTempRange = 80f..95f,
                serviceIntervalKm = 60000
            ),
            "Lancer" to ModelSpec(
                cvtModel = "Jatco JF011E",
                oilCapacity = 7.0f,
                normalTempRange = 80f..95f,
                serviceIntervalKm = 60000
            ),
            "ASX" to ModelSpec(
                cvtModel = "Jatco JF015E",
                oilCapacity = 6.9f,
                normalTempRange = 80f..95f,
                serviceIntervalKm = 60000
            )
        )
    }
}

data class ModelSpec(
    val cvtModel: String,
    val oilCapacity: Float,
    val normalTempRange: ClosedFloatingPointRange<Float>,
    val serviceIntervalKm: Int
)
