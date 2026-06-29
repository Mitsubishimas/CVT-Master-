# Сохраняем классы приложения
-keep class com.mitsubishi.cvtmaster.** { *; }

# Сохраняем Bluetooth классы
-keep class android.bluetooth.** { *; }

# Сохраняем Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
