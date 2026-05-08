package com.rokid.style.clawsses

import android.os.Bundle
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = Prefs(this)

        val host = EditText(this).apply {
            hint = "OpenClaw host"
            setText(prefs.host)
        }
        val port = EditText(this).apply {
            hint = "OpenClaw port"
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(prefs.port.toString())
        }
        val token = EditText(this).apply {
            hint = "OpenClaw token"
            setText(prefs.token)
        }
        val eleven = EditText(this).apply {
            hint = "ElevenLabs API key"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.elevenLabsApiKey)
        }
        val voice = EditText(this).apply {
            hint = "ElevenLabs voice ID"
            setText(prefs.voiceId)
        }

        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 48, 24, 24)
            addView(TextView(this@SettingsActivity).apply {
                text = "Clawsses Settings"
                textSize = 20f
            })
            addView(host)
            addView(port)
            addView(token)
            addView(eleven)
            addView(voice)
            addView(Button(this@SettingsActivity).apply {
                text = "Save"
                setOnClickListener {
                    prefs.host = host.text.toString().trim()
                    prefs.port = port.text.toString().toIntOrNull() ?: 18789
                    prefs.token = token.text.toString().trim()
                    prefs.elevenLabsApiKey = eleven.text.toString().trim()
                    prefs.voiceId = voice.text.toString().trim()
                    Toast.makeText(this@SettingsActivity, "Settings saved", Toast.LENGTH_SHORT).show()
                    finish()
                }
            })
        })
    }
}
