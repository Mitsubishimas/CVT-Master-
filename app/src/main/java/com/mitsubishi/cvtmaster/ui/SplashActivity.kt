package com.mitsubishi.cvtmaster.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.parseColor("#060912"))
        }

        val icon = TextView(this).apply {
            text = "⚙️"
            textSize = 72f
            gravity = Gravity.CENTER
        }
        root.addView(icon)

        val title = TextView(this).apply {
            text = "CVT Calibration"
            textSize = 34f
            setTextColor(Color.parseColor("#00D2FF"))
            gravity = Gravity.CENTER
            setTypeface(null, android.graphics.Typeface.BOLD)
            letterSpacing = 0.12f
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Professional CVT Jatco Tool"
            textSize = 15f
            setTextColor(Color.parseColor("#9B59B6"))
            gravity = Gravity.CENTER
            letterSpacing = 0.08f
            setPadding(0, 12, 0, 0)
        }
        root.addView(subtitle)

        val progress = ProgressBar(this).apply {
            isIndeterminate = true
            setPadding(0, 60, 0, 0)
        }
        root.addView(progress)

        val version = TextView(this).apply {
            text = "v1.0.37"
            textSize = 12f
            setTextColor(Color.parseColor("#6B7394"))
            gravity = Gravity.CENTER
            setPadding(0, 80, 0, 0)
        }
        root.addView(version)

        val dev = TextView(this).apply {
            text = "© MasterMitsubishi 2026"
            textSize = 11f
            setTextColor(Color.parseColor("#6B7394"))
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 0)
        }
        root.addView(dev)

        setContentView(root)

        title.alpha = 0f
        subtitle.alpha = 0f
        ObjectAnimator.ofFloat(title, "alpha", 0f, 1f).apply { duration = 800; startDelay = 200 }.start()
        ObjectAnimator.ofFloat(subtitle, "alpha", 0f, 1f).apply { duration = 800; startDelay = 600 }.start()

        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, MainActivity::class.java))
            finish()
        }, 2500)
    }
}
