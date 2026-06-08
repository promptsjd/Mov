package com.example.blocker

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Bundle
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        if (sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) == null) {
            showIncompatible()
            return
        }

        setContentView(R.layout.activity_main)
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    private fun updateServiceStatus() {
        val tvStatus  = findViewById<TextView>(R.id.tv_service_status) ?: return
        val btnEnable = findViewById<TextView>(R.id.btn_enable_service) ?: return

        if (isServiceEnabled()) {
            tvStatus.text = "Active"
            tvStatus.setTextColor(Color.parseColor("#4CAF50"))
            btnEnable.visibility = android.view.View.GONE
        } else {
            tvStatus.text = "Not enabled"
            tvStatus.setTextColor(Color.parseColor("#888888"))
            btnEnable.visibility = android.view.View.VISIBLE
            btnEnable.setOnClickListener {
                startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            }
        }
    }

    private fun isServiceEnabled(): Boolean {
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == packageName }
    }

    private fun showIncompatible() {
        setContentView(android.R.layout.simple_list_item_1)
        findViewById<TextView>(android.R.id.text1).apply {
            text = "This device doesn't have the sensors required to run MOV."
            setTextColor(Color.WHITE)
            textSize = 17f
            setPadding(64, 64, 64, 64)
            setBackgroundColor(Color.BLACK)
        }
    }
}
