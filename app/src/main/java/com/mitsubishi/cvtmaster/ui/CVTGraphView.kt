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
    
    private val temperatureData = mutableListOf<Float>()
    private val pressureData = mutableListOf<Float>()
    private val maxDataPoints = 100
    
    private val backgroundColor = Color.rgb(28, 28, 30)
    private val gridColor = Color.rgb(60, 60, 67)
    private val temperatureColor = Color.rgb(255, 59, 48)
    private val pressureColor = Color.rgb(0, 122, 255)
    private val textColor = Color.rgb(142, 142, 147)
    
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
    }
    
    private val pressurePaint = Paint().apply {
        color = pressureColor
        strokeWidth = 3f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    
    private val textPaint = Paint().apply {
        color = textColor
        textSize = 24f
        isAntiAlias = true
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
    
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        
        val width = width.toFloat()
        val height = height.toFloat()
        
        canvas.drawColor(backgroundColor)
        drawGrid(canvas, width, height)
        
        if (temperatureData.size > 1) {
            drawDataLine(canvas, temperatureData, width, height, tempPaint, 0f, 150f)
        }
        
        if (pressureData.size > 1) {
            drawDataLine(canvas, pressureData, width, height, pressurePaint, 0f, 3f)
        }
        
        drawLegend(canvas)
    }
    
    private fun drawGrid(canvas: Canvas, width: Float, height: Float) {
        for (i in 0..4) {
            val y = (height / 5) * i
            canvas.drawLine(0f, y, width, y, gridPaint)
        }
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
    
    private fun drawLegend(canvas: Canvas) {
        val startX = 20f
        val startY = 40f
        
        canvas.drawLine(startX, startY, startX + 30, startY, tempPaint)
        canvas.drawText("Температура", startX + 40, startY + 5, textPaint)
        
        val pressureY = startY + 30
        canvas.drawLine(startX, pressureY, startX + 30, pressureY, pressurePaint)
        canvas.drawText("Давление", startX + 40, pressureY + 5, textPaint)
    }
}
