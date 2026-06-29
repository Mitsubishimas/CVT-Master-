package com.mitsubishi.cvtmaster

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class CVTMasterApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannels()
    }
    
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "Сервис CVT Master",
                NotificationManager.IMPORTANCE_LOW
            )
            
            val alertChannel = NotificationChannel(
                CHANNEL_ALERTS,
                "Предупреждения CVT",
                NotificationManager.IMPORTANCE_HIGH
            )
            
            manager.createNotificationChannel(serviceChannel)
            manager.createNotificationChannel(alertChannel)
        }
    }
    
    companion object {
        const val CHANNEL_SERVICE = "cvt_service"
        const val CHANNEL_ALERTS = "cvt_alerts"
        
        lateinit var instance: CVTMasterApplication
            private set
    }
}
