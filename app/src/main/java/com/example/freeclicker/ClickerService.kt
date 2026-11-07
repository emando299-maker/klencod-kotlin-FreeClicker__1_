package com.example.freeclicker

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.*
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.*
import android.view.accessibility.AccessibilityEvent
import android.widget.*
import kotlin.math.abs

class ClickerService : AccessibilityService() {
    enum class Type(val label: String) { TAP("Tap"), LONG("Long"), SWIPE("Swipe") }
    class Act(val type: Type, var x: Float, var y: Float, var x2: Float = 0f, var y2: Float = 0f)

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private val acts = mutableListOf<Act>()
    private val markers = mutableListOf<View>()
    private val presets = longArrayOf(0, 50, 100, 250, 500, 1000, 5000, 30000, 60000, 600000, 3600000, 86400000)
    private var type = Type.TAP
    private var running = false
    private var idx = 0
    private var rounds = 0
    private lateinit var panel: LinearLayout
    private lateinit var panelLp: WindowManager.LayoutParams
    private lateinit var playBtn: Button
    private lateinit var intervalBtn: Button
    private lateinit var info: TextView
    private val extras = mutableListOf<View>()

    private val prefs by lazy { getSharedPreferences("cfg", MODE_PRIVATE) }
    private val interval get() = prefs.getLong("interval", 500L).coerceIn(0L, 86400000L)
    private val pointDp get() = prefs.getInt("pointDp", 32)
    private val size get() = (pointDp * resources.displayMetrics.density).toInt()

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        when (key) {
            "pointDp" -> redraw()
            "interval" -> if (::intervalBtn.isInitialized) intervalBtn.text = fmt(interval)
            "compact" -> applyCompact()
        }
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { stop() }
    }

    override fun onServiceConnected() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildPanel()
        prefs.registerOnSharedPreferenceChangeListener(prefListener)
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
    override fun onAccessibilityEvent(e: AccessibilityEvent?) {}
    override fun onInterrupt() { stop() }
    override fun onDestroy() {
        stop(); clearMarkers()
        if (::panel.isInitialized) runCatching { wm.removeView(panel) }
        runCatching { prefs.unregisterOnSharedPreferenceChangeListener(prefListener) }
        runCatching { unregisterReceiver(screenOff) }
        super.onDestroy()
    }

    private fun fmt(ms: Long): String {
        if (ms == 0L) return "0ms"
        val h = ms / 3600000; val m = ms % 3600000 / 60000; val s = ms % 60000 / 1000; val r = ms % 1000
        return buildString { if (h > 0) append("${h}h"); if (m > 0) append("${m}m"); if (s > 0) append("${s}s"); if (r > 0) append("${r}ms") }
    }

    private fun lp(w: Int, h: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT
    ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    private fun buildPanel() {
        panel = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(0xE6222222.toInt()); setPadding(8, 8, 8, 8) }
        panelLp = lp(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, 40, 200)
        val drag = TextView(this).apply { text = "⠿"; textSize = 22f; setTextColor(Color.WHITE); setPadding(12, 0, 12, 0); gravity = Gravity.CENTER_VERTICAL }
        panel.addView(drag)
        fun btn(t: String, extra: Boolean = true, f: (Button) -> Unit) = Button(this).apply {
            text = t; textSize = 12f; minWidth = 0; minimumWidth = 0
            setOnClickListener { f(this) }
        }.also { panel.addView(it); if (extra) extras.add(it) }
        btn("👁", false) { prefs.edit().putBoolean("compact", !prefs.getBoolean("compact", false)).apply() }
        btn("＋") { addAct() }
        btn(type.label) { b -> type = Type.values()[(type.ordinal + 1) % 3]; b.text = type.label }
        intervalBtn = btn(fmt(interval)) { b ->
            val next = presets.firstOrNull { it > interval } ?: presets[0]
            prefs.edit().putLong("interval", next).apply()
        }
        playBtn = btn("▶", false) { if (running) stop() else start() }
        btn("🗑") { stop(); acts.clear(); redraw() }
        info = TextView(this).apply { setTextColor(Color.WHITE); setPadding(12, 0, 4, 0); gravity = Gravity.CENTER_VERTICAL }
        panel.addView(info); extras.add(info)
        var dx = 0f; var dy = 0f
        drag.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { dx = e.rawX - panelLp.x; dy = e.rawY - panelLp.y }
                MotionEvent.ACTION_MOVE -> { panelLp.x = (e.rawX - dx).toInt(); panelLp.y = (e.rawY - dy).toInt(); wm.updateViewLayout(panel, panelLp) }
            }
            true
        }
        wm.addView(panel, panelLp)
        applyCompact()
    }

    private fun applyCompact() {
        val c = prefs.getBoolean("compact", false)
        extras.forEach { it.visibility = if (c) View.GONE else View.VISIBLE }
        wm.updateViewLayout(panel, panelLp)
    }

    private fun addAct() {
        stop()
        val m = resources.displayMetrics
        val cx = m.widthPixels / 2f + acts.size * 20; val cy = m.heightPixels / 2f + acts.size * 20
        acts.add(if (type == Type.SWIPE) Act(type, cx - 150, cy, cx + 150, cy) else Act(type, cx, cy))
        redraw()
    }

    private fun clearMarkers() { markers.forEach { runCatching { wm.removeView(it) } }; markers.clear() }

    private fun redraw() {
        clearMarkers()
        acts.forEachIndexed { i, a ->
            val color = when (a.type) { Type.TAP -> 0xFF1E88E5.toInt(); Type.LONG -> 0xFF8E24AA.toInt(); Type.SWIPE -> 0xFF43A047.toInt() }
            addMarker("${i + 1}", color, a.x, a.y, { x, y -> a.x = x; a.y = y }) { acts.remove(a); redraw() }
            if (a.type == Type.SWIPE) addMarker("→${i + 1}", color, a.x2, a.y2, { x, y -> a.x2 = x; a.y2 = y }) { acts.remove(a); redraw() }
        }
        if (!running && ::info.isInitialized) info.text = if (acts.isEmpty()) "" else "${acts.size} pts"
        if (running) setMarkersTouchable(false)
    }

    private fun addMarker(label: String, color: Int, cx: Float, cy: Float, onMove: (Float, Float) -> Unit, onTap: () -> Unit) {
        val s = size
        val tv = TextView(this).apply {
            text = label; gravity = Gravity.CENTER; setTextColor(Color.WHITE); alpha = 0.85f
            textSize = if (pointDp < 24) 8f else 13f
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color); setStroke(2, Color.WHITE) }
        }
        val p = lp(s, s, (cx - s / 2).toInt(), (cy - s / 2).toInt())
        var sx = 0f; var sy = 0f; var ox = 0; var oy = 0; var moved = false
        tv.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> { sx = e.rawX; sy = e.rawY; ox = p.x; oy = p.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - sx; val dy = e.rawY - sy
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    if (moved) { p.x = ox + dx.toInt(); p.y = oy + dy.toInt(); wm.updateViewLayout(tv, p); onMove(p.x + s / 2f, p.y + s / 2f) }
                }
                MotionEvent.ACTION_UP -> if (!moved) onTap()
            }
            true
        }
        wm.addView(tv, p); markers.add(tv)
    }

    private fun setMarkersTouchable(on: Boolean) = markers.forEach {
        val p = it.layoutParams as WindowManager.LayoutParams
        p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() else p.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        it.alpha = if (on) 0.85f else 0.3f
        wm.updateViewLayout(it, p)
    }

    private fun start() {
        if (acts.isEmpty() || running) return
        running = true; idx = 0; rounds = 0
        playBtn.text = "■"; info.text = "3s…"
        setMarkersTouchable(false)
        handler.postDelayed({ step() }, 3000)
    }

    private fun stop() {
        if (!running) return
        running = false
        handler.removeCallbacksAndMessages(null)
        playBtn.text = "▶"; info.text = "${acts.size} pts · $rounds rounds"
        setMarkersTouchable(true)
    }

    private fun step() {
        if (!running || acts.isEmpty()) { stop(); return }
        fire(acts[idx]) {
            if (!running) return@fire
            idx = (idx + 1) % acts.size
            if (idx == 0) { rounds++; info.text = "$rounds rounds" }
            handler.postDelayed({ step() }, interval)
        }
    }

    private fun fire(a: Act, done: () -> Unit) {
        val path = Path().apply { moveTo(a.x, a.y); if (a.type == Type.SWIPE) lineTo(a.x2, a.y2) }
        val dur = when (a.type) { Type.TAP -> 50L; Type.LONG -> 800L; Type.SWIPE -> 400L }
        val g = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build()
        val ok = dispatchGesture(g, object : GestureResultCallback() {
            override fun onCompleted(d: GestureDescription?) { done() }
            override fun onCancelled(d: GestureDescription?) { done() }
        }, null)
        if (!ok) done()
    }
}
