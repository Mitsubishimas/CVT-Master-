package com.mitsubishi.cvtmaster.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mitsubishi.cvtmaster.core.MitsubishiDiagnostic
import com.mitsubishi.cvtmaster.data.DataLogger
import com.mitsubishi.cvtmaster.elm327.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class MainViewModel(application: Application) : AndroidViewModel(application) {
    
    private val bluetoothManager = BluetoothManager(application)
    private val dataLogger = DataLogger(application)
    private val diagnostic = MitsubishiDiagnostic()
    private val pidParser = JatcoPIDParser()
    
    val connectionState = bluetoothManager.connectionState
    val discoveredDevices = bluetoothManager.discoveredDevices
    
    private val _cvtData = MutableStateFlow(JatcoCVTData())
    val cvtData: StateFlow<JatcoCVTData> = _cvtData
    
    private val _healthReport = MutableStateFlow<MitsubishiDiagnostic.CVTHealthReport?>(null)
    val healthReport: StateFlow<MitsubishiDiagnostic.CVTHealthReport?> = _healthReport
    
    private var dataCollectionJob: Job? = null
    
    fun startScanning() {
        bluetoothManager.startScanning()
    }
    
    fun connectToDevice(macAddress: String) {
        viewModelScope.launch {
            bluetoothManager.connectToDevice(macAddress)
            
            // Ожидаем подключения
            while (bluetoothManager.connectionState.value != BluetoothConnectionState.READY) {
                delay(100)
            }
            
            // Запускаем сбор данных
            startDataCollection()
        }
    }
    
    private fun startDataCollection() {
        dataCollectionJob?.cancel()
        dataCollectionJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val data = bluetoothManager.requestCVTData()
                    if (data != null) {
                        _cvtData.value = data
                        dataLogger.addEntry(data)
                        
                        // Анализируем состояние
                        val report = diagnostic.analyzeCVTHealth(
                            oilDegradation = data.oilDegradation,
                            cvtTemp = data.oilTemperature,
                            primaryPressure = data.primaryPressure,
                            secondaryPressure = data.secondaryPressure,
                            mileage = 0  // Здесь нужно получить пробег из ECM
                        )
                        _healthReport.value = report
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                
                delay(500)  // Запрос каждые 500мс
            }
        }
    }
    
    fun resetOilDegradation() {
        viewModelScope.launch {
            bluetoothManager.resetOilDegradation()
        }
    }
    
    fun exportLogs() {
        viewModelScope.launch {
            dataLogger.exportToCSV()
            dataLogger.exportToJSON()
            dataLogger.exportReport()
        }
    }
    
    fun disconnect() {
        dataCollectionJob?.cancel()
        bluetoothManager.disconnect()
    }
    
    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
