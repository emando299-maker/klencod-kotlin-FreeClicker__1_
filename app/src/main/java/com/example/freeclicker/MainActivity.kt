package com.example.freeclicker

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.ViewGroup
import android.widget.*

class MainActivity : Activity() {
    private val MAX_MS = 24L * 3600 * 1000

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val prefs = getSharedPreferences("cfg", MODE_PRIVATE)
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 48, 48, 48) }
        fun label(t: String) = TextView(this).apply { text = t; textSize = 16f; setPadding(0, 32, 0, 8) }.also { col.addView(it) }

        label("1. Turn on Free Clicker in Accessibility settings, then open your target app and use the floating panel (＋ add point, ▶ start).")
        col.addView(Button(this).apply {
            text = "Open Accessibility settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        // --- Interval 0 .. 24h ---
        label("Click interval (0 to 24 hours)")
        val cur = prefs.getLong("interval", 500L)
        fun field(hnt: String, v: Long) = EditText(this).apply {
            setText(v.toString()); hint = hnt; gravity = Gravity.CENTER
            inputType = InputType.TYPE_CLASS_NUMBER
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val fh = field("h", cur / 3600000); val fm = field("m", cur % 3600000 / 60000)
        val fs = field("s", cur % 60000 / 1000); val fms = field("ms", cur % 1000)
        col.addView(LinearLayout(this).apply { listOf(fh, fm, fs, fms).forEach { addView(it) } })
        label("hours  |  minutes  |  seconds  |  ms")
        col.addView(Button(this).apply {
            text = "Save interval"
            setOnClickListener {
                fun n(e: EditText) = e.text.toString().toLongOrNull() ?: 0L
                val total = (n(fh) * 3600000 + n(fm) * 60000 + n(fs) * 1000 + n(fms)).coerceIn(0, MAX_MS)
                prefs.edit().putLong("interval", total).apply()
                fh.setText((total / 3600000).toString()); fm.setText((total % 3600000 / 60000).toString())
                fs.setText((total % 60000 / 1000).toString()); fms.setText((total % 1000).toString())
                Toast.makeText(this@MainActivity, "Saved (${total} ms)", Toast.LENGTH_SHORT).show()
            }
        })

        // --- Point size ---
        val sizeLabel = label("")
        val sb = SeekBar(this).apply {
            max = 52
            progress = prefs.getInt("pointDp", 32) - 12
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, u: Boolean) {
                    sizeLabel.text = "Point size: ${p + 12} dp"
                    prefs.edit().putInt("pointDp", p + 12).apply()
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })
        }
        sizeLabel.text = "Point size: ${sb.progress + 12} dp"
        col.addView(sb)

        // --- Compact panel ---
        label("Floating panel")
        col.addView(Switch(this).apply {
            text = "Compact panel (only drag handle, 👁 and ▶/■)"
            isChecked = prefs.getBoolean("compact", false)
            setOnCheckedChangeListener { _, c -> prefs.edit().putBoolean("compact", c).apply() }
        })
        setContentView(ScrollView(this).apply { addView(col) })
    }
}