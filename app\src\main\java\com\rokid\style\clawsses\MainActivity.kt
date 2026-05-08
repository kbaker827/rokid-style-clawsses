package com.rokid.style.clawsses

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class MainActivity : AppCompatActivity() {
    private lateinit var status: TextView

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        status.text = if (result[Manifest.permission.RECORD_AUDIO] == true) {
            startAssistant()
            "Listening service started"
        } else {
            "Microphone permission is required"
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        status = TextView(this).apply {
            text = "OpenClaw voice bridge ready"
            textSize = 18f
            setPadding(24, 24, 24, 24)
        }
        val start = Button(this).apply {
            text = "Start listening"
            setOnClickListener { ensurePermissionsThenStart() }
        }
        val stop = Button(this).apply {
            text = "Stop listening"
            setOnClickListener {
                startService(Intent(this@MainActivity, ClawssesService::class.java).apply {
                    action = ClawssesService.ACTION_STOP_LISTENING
                })
                status.text = "Listening stopped"
            }
        }
        val settings = Button(this).apply {
            text = "Settings"
            setOnClickListener { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) }
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            addView(status)
            addView(start)
            addView(stop)
            addView(settings)
        })
    }

    private fun ensurePermissionsThenStart() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        val missing = needed.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) startAssistant() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun startAssistant() {
        val intent = Intent(this, ClawssesService::class.java)
        ContextCompat.startForegroundService(this, intent)
        startService(Intent(this, ClawssesService::class.java).apply {
            action = ClawssesService.ACTION_START_LISTENING
        })
        status.text = "Listening service started"
    }
}
