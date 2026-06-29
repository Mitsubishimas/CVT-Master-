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
    
    val bluetoothManager = BluetoothManager(application)
    val dataLogger = DataLogger(application)
    val diagnostic = MitsubishiDiagnostic()
    val pidParser = JatcoPIDParser()
    
    val connectionState = bluetoothManager.connectionState
    val discoveredDevices = bluetoothManager.discoveredDevices
    
    private val _cvtData = MutableStateFlow(JatcoCVTData())
    val cvtData: StateFlow<JatcoCVTData> = _cvtData
    
    private val _healthReport = MutableStateFlow<MitsubishiDiagnostic.CVTHealthReport?>(null)
    val healthReport: StateFlow<MitsubishiDiagnostic.CVTHealthReport?> = _healthReport
    
    private var collectionJob: Job? = null
    
    fun connectToDevice(address: String) {
        bluetoothManager.connectToDevice(address)
    }
    
    fun disconnect() {
        bluetoothManager.disconnect()
    }
    
    fun startScanning() {
        bluetoothManager.startScanning()
    }
    
    fun startDataCollection() {
        collectionJob?.cancel()
        collectionJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val response = bluetoothManager.sendRawCommand("01 00")
                    // Правильное имя метода: parseELM327Response
                    val data = pidParser.parseELM327Response(response)
                    
                    if (data != null) {
                        _cvtData.value = data
                        dataLogger.addEntry(data)
                        
                        _healthReport.value = diagnostic.analyzeCVTHealth(
                            oilDegradation = data.oilDegradation,
                            cvtTemp = data.oilTemperature,
                            primaryPressure = data.primaryPressure,
                            secondaryPressure = data.secondaryPressure,
                            mileage = 0
                        )
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Ignore collection errors
                }
                delay(500)
            }
        }
    }
    
    fun stopDataCollection() {
        collectionJob?.cancel()
    }
    
    fun resetOilDegradation() {
        viewModelScope.launch {
            val resetter = OilDegradationReset()
            resetter.resetJF011E(bluetoothManager)
        }
    }
    
    fun exportData() {
        viewModelScope.launch {
            dataLogger.exportToCSV()
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        bluetoothManager.cleanup()
    }
}
