package com.mitsubishi.cvtmaster.data

object MitsubishiDTC {
    val cvtCodes = mapOf(
        "P0705" to DTCInfo(
            "P0705",
            "Неисправность цепи датчика положения селектора",
            "Проверьте проводку и сам датчик селектора",
            Severity.HIGH
        ),
        "P0715" to DTCInfo(
            "P0715",
            "Неисправность датчика скорости первичного вала",
            "Замените датчик скорости входного вала",
            Severity.CRITICAL
        ),
        "P0720" to DTCInfo(
            "P0720",
            "Неисправность датчика скорости выходного вала",
            "Проверьте цепь датчика скорости",
            Severity.CRITICAL
        ),
        "P0725" to DTCInfo(
            "P0725",
            "Неисправность цепи датчика оборотов двигателя",
            "Типичная проблема для Mitsubishi Outlander",
            Severity.HIGH
        ),
        "P0730" to DTCInfo(
            "P0730",
            "Неправильное передаточное отношение",
            "Проверьте давление в вариаторе",
            Severity.CRITICAL
        ),
        "P0741" to DTCInfo(
            "P0741",
            "Неисправность блокировки гидротрансформатора",
            "Проверьте соленоид блокировки TCC",
            Severity.MEDIUM
        ),
        "P0778" to DTCInfo(
            "P0778",
            "Соленоид управления давлением - неисправность",
            "Проверьте соленоид контроля давления",
            Severity.CRITICAL
        ),
        "P0841" to DTCInfo(
            "P0841",
            "Датчик давления трансмиссионной жидкости",
            "Специфичная проблема для Mitsubishi Lancer CVT",
            Severity.HIGH
        ),
        "P0868" to DTCInfo(
            "P0868",
            "Низкое давление трансмиссионной жидкости",
            "Проверьте уровень и состояние масла CVT",
            Severity.CRITICAL
        ),
        "P0962" to DTCInfo(
            "P0962",
            "Соленоид управления давлением - низкое напряжение",
            "Проверьте электропитание соленоида",
            Severity.MEDIUM
        )
    )
    
    fun getDTCInfo(code: String): DTCInfo? {
        return cvtCodes[code]
    }
    
    fun searchDTC(description: String): List<DTCInfo> {
        return cvtCodes.values.filter { 
            it.description.contains(description, ignoreCase = true) 
        }
    }
}

data class DTCInfo(
    val code: String,
    val description: String,
    val recommendation: String,
    val severity: Severity
)

enum class Severity {
    LOW, MEDIUM, HIGH, CRITICAL
}
