package com.mitsubishi.cvtmaster.elm327

import kotlinx.coroutines.delay

data class ResetResult(
    val success: Boolean,
    val message: String,
    val oldValue: Int = 0,
    val newValue: Int = 0
)

class OilDegradationReset {
    
    suspend fun resetJF011E(bluetoothManager: BluetoothManager): ResetResult {
        return try {
            bluetoothManager.sendRawCommand("AT SH 7E2")
            delay(200)
            bluetoothManager.sendRawCommand("22 10 00")
            delay(100)
            bluetoothManager.sendRawCommand("2E 10 00 FF FF FF FF")
            delay(200)
            val response = bluetoothManager.sendRawCommand("30 00 01")
            delay(300)
            
            if (response.contains("60") || response.contains("OK")) {
                ResetResult(true, "Счетчик деградации сброшен успешно")
            } else {
                ResetResult(false, "Ошибка доступа к блоку управления")
            }
        } catch (e: Exception) {
            ResetResult(false, "Ошибка: ${e.message}")
        } finally {
            bluetoothManager.sendRawCommand("AT SH 7DF")
        }
    }
    
    suspend fun resetJF016E(bluetoothManager: BluetoothManager): ResetResult {
        return try {
            bluetoothManager.sendRawCommand("AT SH 7E2")
            delay(100)
            bluetoothManager.sendRawCommand("10 03")
            delay(200)
            bluetoothManager.sendRawCommand("27 01")
            delay(100)
            bluetoothManager.sendRawCommand("27 02 FF FF")
            delay(200)
            bluetoothManager.sendRawCommand("31 01 FF 00")
            delay(200)
            val response = bluetoothManager.sendRawCommand("31 01 FF 01")
            delay(300)
            
            if (response.contains("71")) {
                ResetResult(true, "Счетчик деградации Outlander сброшен")
            } else {
                ResetResult(false, "Сброс не подтвержден")
            }
        } catch (e: Exception) {
            ResetResult(false, "Ошибка: ${e.message}")
        } finally {
            bluetoothManager.sendRawCommand("AT SH 7DF")
        }
    }
}
