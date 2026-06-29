package com.mitsubishi.cvtmaster.elm327

import kotlinx.coroutines.delay

data class ResetResult(
    val success: Boolean,
    val message: String,
    val oldValue: Int = 0,
    val newValue: Int = 0
)

class OilDegradationReset {
    
    companion object {
        // Сервисные команды для разных моделей Mitsubishi
        const val SERVICE_MODE_ENTER = "AT SH 7E2"
        const val SERVICE_MODE_EXIT = "AT SH 7DF"
        
        // Специфичные команды для Jatco JF011E (Lancer, ASX)
        const val JF011E_SEED_REQUEST = "22 10 00"
        const val JF011E_KEY_SEND = "2E 10 00 FF FF FF FF"
        const val JF011E_RESET = "30 00 01"
        
        // Специфичные команды для Jatco JF016E (Outlander)
        const val JF016E_SERVICE_ACCESS = "27 01"
        const val JF016E_SECURITY_KEY = "27 02 FF FF"
        const val JF016E_RESET_DEGRADATION = "31 01 FF 00"
        const val JF016E_CONFIRM_RESET = "31 01 FF 01"
        
        // Специфичные команды для Jatco JF015E (новые модели)
        const val JF015E_EXTENDED_SESSION = "10 03"
        const val JF015E_RESET_COUNTER = "2C 10 01 00 00 00"
    }
    
    /**
     * Сброс счетчика для Lancer/ASX (JF011E)
     */
    suspend fun resetJF011E(bluetoothManager: BluetoothManager): ResetResult {
        try {
            // Получаем текущее значение
            val currentData = bluetoothManager.requestCVTData()
            val oldValue = currentData?.oilDegradation ?: 0
            
            // Шаг 1: Вход в сервисный режим
            bluetoothManager.sendCommandAndWait(SERVICE_MODE_ENTER)
            delay(200)
            
            // Шаг 2: Запрос Seed
            val seedResponse = bluetoothManager.sendCommandAndWait(JF011E_SEED_REQUEST)
            delay(100)
            
            if (seedResponse.contains("62")) {
                // Шаг 3: Отправка ключа
                bluetoothManager.sendCommandAndWait(JF011E_KEY_SEND)
                delay(200)
                
                // Шаг 4: Сброс счетчика
                val resetResponse = bluetoothManager.sendCommandAndWait(JF011E_RESET)
                delay(300)
                
                if (resetResponse.contains("60") || resetResponse.contains("OK")) {
                    // Проверяем новое значение
                    val newData = bluetoothManager.requestCVTData()
                    val newValue = newData?.oilDegradation ?: 0
                    
                    return ResetResult(
                        success = true,
                        message = "Счетчик деградации сброшен успешно",
                        oldValue = oldValue,
                        newValue = newValue
                    )
                }
            }
            
            return ResetResult(
                success = false,
                message = "Ошибка доступа к блоку управления",
                oldValue = oldValue
            )
            
        } catch (e: Exception) {
            return ResetResult(
                success = false,
                message = "Ошибка: ${e.message}"
            )
        } finally {
            // Выход из сервисного режима
            bluetoothManager.sendCommandAndWait(SERVICE_MODE_EXIT)
        }
    }
    
    /**
     * Сброс счетчика для Outlander (JF016E) - более сложная процедура
     */
    suspend fun resetJF016E(bluetoothManager: BluetoothManager): ResetResult {
        try {
            val currentData = bluetoothManager.requestCVTData()
            val oldValue = currentData?.oilDegradation ?: 0
            
            // Шаг 1: Вход в расширенную диагностическую сессию
            bluetoothManager.sendCommandAndWait("AT SH 7E2")
            delay(100)
            
            val sessionResponse = bluetoothManager.sendCommandAndWait(JF015E_EXTENDED_SESSION)
            delay(200)
            
            if (!sessionResponse.contains("50")) {
                return ResetResult(false, "Не удалось войти в сервисный режим")
            }
            
            // Шаг 2: Запрос доступа безопасности
            val seedResponse = bluetoothManager.sendCommandAndWait(JF016E_SERVICE_ACCESS)
            delay(100)
            
            // Шаг 3: Отправка ключа безопасности
            val keyResponse = bluetoothManager.sendCommandAndWait(JF016E_SECURITY_KEY)
            delay(200)
            
            if (!keyResponse.contains("67")) {
                return ResetResult(false, "Ошибка аутентификации")
            }
            
            // Шаг 4: Команда сброса деградации
            val resetResponse1 = bluetoothManager.sendCommandAndWait(JF016E_RESET_DEGRADATION)
            delay(200)
            
            // Шаг 5: Подтверждение сброса
            val resetResponse2 = bluetoothManager.sendCommandAndWait(JF016E_CONFIRM_RESET)
            delay(300)
            
            if (resetResponse2.contains("71")) {
                val newData = bluetoothManager.requestCVTData()
                return ResetResult(
                    success = true,
                    message = "Счетчик деградации Outlander сброшен",
                    oldValue = oldValue,
                    newValue = newData?.oilDegradation ?: 0
                )
            }
            
            return ResetResult(false, "Сброс не подтвержден", oldValue)
            
        } catch (e: Exception) {
            return ResetResult(false, "Ошибка: ${e.message}")
        } finally {
            // Возврат в обычный режим
            bluetoothManager.sendCommandAndWait("AT SH 7DF")
        }
    }
    
    /**
     * Автоматический выбор процедуры сброса
     */
    suspend fun autoReset(bluetoothManager: BluetoothManager, model: String): ResetResult {
        return when {
            model.contains("Outlander", true) -> resetJF016E(bluetoothManager)
            model.contains("Lancer", true) -> resetJF011E(bluetoothManager)
            model.contains("ASX", true) -> resetJF011E(bluetoothManager)
            else -> resetJF011E(bluetoothManager)  // По умолчанию JF011E
        }
    }
}
