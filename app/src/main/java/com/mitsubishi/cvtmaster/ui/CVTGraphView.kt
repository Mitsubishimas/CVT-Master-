package com.mitsubishi.cvtmaster.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CVTGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Данные для графиков
    private val temperatureData = mutableListOf<Float>()
    private val pressureData = mutableListOf<Float>()
    private val rpmData = mutableListOf<Float>()
    private val degradationData = mutableListOf<Int>()
    
    // Максимальное количество точек на графике
    private val maxDataPoints = 100
    
    // Цвета для графиков
    private val temperatureColor = Color.rgb(255, 59, 48)    // Красный
    private val pressureColor = Color.rgb(0, 122, 255)       // Синий
    private val rpmColor = Color.rgb(52, 199, 89)            // Зеленый
    private val gridColor = Color.rgb(60, 60, 67)            // Сетка
    private val backgroundColor = Color.rgb(28, 28, 30)      // Фон
    private val textColor = Color.rgb(142, 142, 147)         // Текст
    
    // Кисти
    private val gridPaint = Paint().apply {
        color = gridColor
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    
    private val tempPaint = Paint().apply {
        color = temperatureColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    
    private val pressurePaint = Paint().apply {
        color = pressureColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val rpmPaint = Paint().apply {
        color = rpmColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = textColor
        textSize = 24f
        isAntiAlias = true
    }
    
    private val warningPaint = Paint().apply {
        color = Color.rgb(255, 149, 
cat > app/src/main/java/com/mitsubishi/cvtmaster/ui/CVTGraphView.kt << 'EOF'
package com.mitsubishi.cvtmaster.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class CVTGraphView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {
    
    // Данные для графиков
    private val temperatureData = mutableListOf<Float>()
    private val pressureData = mutableListOf<Float>()
    private val rpmData = mutableListOf<Float>()
    private val degradationData = mutableListOf<Int>()
    
    // Максимальное количество точек на графике
    private val maxDataPoints = 100
    
    // Цвета для графиков
    private val temperatureColor = Color.rgb(255, 59, 48)    // Красный
    private val pressureColor = Color.rgb(0, 122, 255)       // Синий
    private val rpmColor = Color.rgb(52, 199, 89)            // Зеленый
    private val gridColor = Color.rgb(60, 60, 67)            // Сетка
    private val backgroundColor = Color.rgb(28, 28, 30)      // Фон
    private val textColor = Color.rgb(142, 142, 147)         // Текст
    
    // Кисти
    private val gridPaint = Paint().apply {
        color = gridColor
        strokeWidth = 1f
        style = Paint.Style.STROKE
    }
    
    private val tempPaint = Paint().apply {
        color = temperatureColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    
    private val pressurePaint = Paint().apply {
        color = pressureColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val rpmPaint = Paint().apply {
        color = rpmColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = textColor
        textSize = 24f
        isAntiAlias = true
    }
    
    private val warningPaint = Paint().apply {
        color = Color.rgb(255, 149, 0)
        strokeWidth = 2f
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }
    
    fun addTemperatureData(temp: Float) {
        temperatureData.add(temp)
        if (temperatureData.size > maxDataPoints) {
            temperatureData.removeAt(0)
        }
        invalidate()
    }
    
    fun addPressureData(pressure: Float) {
        pressureData.add(pressure)
        if (pressureData.size > maxDataPoints) {
            pressureData.removeAt(0)
        }
        invalidate()
    }
    
    fun addRPMData(rpm: Int) {
        rpmData.add(rpm.toFloat())
        if (rpmData.size > maxDataPoints) {
            rpmData.removeAt(0)
        }
        invalidate()
    }
    
    fun addDegradationData(degradation: Int) {
        degradationData.add(degradation)
        if (degradationData.size > maxDataPoints) {
            degradationData.removeAt(0)
        }
    }
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        // Рисуем фон
        canvas.drawColor(backgroundColor)
        
        // Рисуем сетку
        drawGrid(canvas, width, height)
        
        // Рисуем данные
        if (temperatureData.size > 1) {
            drawDataLine(canvas, temperatureData, width, height, tempPaint, 0f, 150f)
        }
        
        if (pressureData.size > 1) {
            drawDataLine(canvas, pressureData, width, height, pressurePaint, 0f, 3f)
        }
        
        if (rpmData.size > 1) {
            drawDataLine(canvas, rpmData, width, height, rpmPaint, 0f, 8000f)
        }
        
        // Рисуем предупредительные линии
        drawWarningLines(canvas, width, height)
        
        // Легенда
        drawLegend(canvas, width)
    }
    
    private fun drawGrid(canvas: Canvas, width: Float, height: Float) {
        // Горизонтальные линии
        for (i in 0..4) {
            val y = (height / 5) * i
            canvas.drawLine(0f, y, width, y, gridPaint)
        }
        
        // Вертикальные линии
        for (i in 0..4) {
            val x = (width / 5) * i
            canvas.drawLine(x, 0f, x, height, gridPaint)
        }
    }
    
    private fun drawDataLine(
        canvas: Canvas,
        data: List<Float>,
        width: Float,
        height: Float,
        paint: Paint,
        minValue: Float,
        maxValue: Float
    ) {
        if (data.size < 2) return
        
        val path = Path()
        val stepX = width / (maxDataPoints - 1)
        val range = maxValue - minValue
        
        for (i in data.indices) {
            val x = i * stepX
            val normalizedValue = ((data[i] - minValue) / range).coerceIn(0f, 1f)
            val y = height - (normalizedValue * height * 0.9f) - (height * 0.05f)
            
            if (i == 0) {
                path.moveTo(x, y)
            } else {
                path.lineTo(x, y)
            }
        }
        
        canvas.drawPath(path, paint)
    }
    
    private fun drawWarningLines(canvas: Canvas, width: Float, height: Float) {
        // Линия перегрева (>110°C)
        val overheatY = height - ((110f / 150f) * height * 0.9f) - (height * 0.05f)
        canvas.drawLine(0f, overheatY, width, overheatY, warningPaint)
        
        // Текст предупреждения
        canvas.drawText("⚠ 110°C", 10f, overheatY - 10f, textPaint)
        
        // Линия критического давления (>1.5 MPa)
        val criticalPressureY = height - ((1.5f / 3f) * height * 0.9f) - (height * 0.05f)
        canvas.drawLine(0f, criticalPressureY, width, criticalPressureY, warningPaint)
    }
    
    private fun drawLegend(canvas: Canvas, width: Float) {
        val startX = 20f
        val startY = 40f
        
        // Температура
        canvas.drawLine(startX, startY, startX + 30, startY, tempPaint)
        canvas.drawText("Температура CVT", startX + 40, startY + 5, textPaint)
        
        // Давление
        val pressureY = startY + 30
        canvas.drawLine(startX, pressureY, startX + 30, pressureY, pressurePaint)
        canvas.drawText("Давление", startX + 40, pressureY + 5, textPaint)
        
        // RPM
        val rpmY = startY + 60
        canvas.drawLine(startX, rpmY, startX + 30, rpmY, rpmPaint)
        canvas.drawText("Обороты двигателя", startX + 40, rpmY + 5, textPaint)
    }
}
