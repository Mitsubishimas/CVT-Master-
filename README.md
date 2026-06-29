# CVT Master - Mitsubishi Edition

Специализированное диагностическое приложение для вариаторов Mitsubishi с поддержкой ELM327.

## 🚗 Поддерживаемые автомобили

### Полная поддержка:
- **Mitsubishi Outlander** (2007-2024) - Jatco JF016E
- **Mitsubishi Lancer** (2007-2017) - Jatco JF011E
- **Mitsubishi ASX** (2010-2024) - Jatco JF015E

### Частичная поддержка:
- Mitsubishi Delica D:5
- Mitsubishi Galant Fortis
- Mitsubishi RVR
- Nissan с вариаторами Jatco (совместимость частичная)

## ⚙️ Функциональность

### Диагностика:
- Чтение и сброс ошибок CVT (специфичные коды Mitsubishi)
- Мониторинг деградации масла в реальном времени
- Анализ давления первичного и вторичного валов
- Контроль температуры вариатора

### Сервисные функции:
- Сброс счетчика старения масла
- Адаптация CVT после замены масла
- Калибровка датчиков положения

### Мониторинг:
- Температура масла CVT
- Передаточное отношение
- Состояние блокировки гидротрансформатора
- Обороты двигателя

## 📱 Установка

```bash
git clone https://github.com/Mitsubishimas/CVT-Master-.git
cd CVT-Master-
# Открыть в Android Studio и собрать APK
# Установка Android SDK
sdkmanager "platforms;android-34" "build-tools;34.0.0"

# Сборка проекта
./gradlew assembleDebug

# Установка на устройство
adb install app/build/outputs/apk/debug/app-debug.apk

## Этап 7: Скрипт сборки для Codespaces

```bash
cat > setup_codespaces.sh << 'EOF'
#!/bin/bash
echo "🔧 Настройка окружения для CVT Master..."

# Установка необходимых пакетов
sudo apt-get update
sudo apt-get install -y default-jdk gradle android-sdk

# Установка переменных окружения
export ANDROID_HOME=/usr/lib/android-sdk
export PATH=$PATH:$ANDROID_HOME/tools:$ANDROID_HOME/platform-tools

# Права на выполнение gradlew
chmod +x gradlew

echo "✅ Окружение настроено!"
echo "📱 Для сборки выполните: ./gradlew assembleDebug"
