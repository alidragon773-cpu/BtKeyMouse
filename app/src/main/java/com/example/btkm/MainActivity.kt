package com.example.btkm

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.*
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.*
import androidx.core.content.ContextCompat

@SuppressLint("MissingPermission", "ClickableViewAccessibility")
class MainActivity : android.app.Activity() {

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private lateinit var status: TextView
    private lateinit var spinner: Spinner
    private var paired: List<BluetoothDevice> = emptyList()
    private var buttons = 0

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            runOnUiThread { status.text = if (registered) "Готово. Выберите устройство и нажмите «Подключить»" else "Приложение не зарегистрировано" }
        }
        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            runOnUiThread {
                when (state) {
                    BluetoothProfile.STATE_CONNECTED -> { host = device; status.text = "Подключено: ${device?.name}" }
                    BluetoothProfile.STATE_CONNECTING -> status.text = "Подключение…"
                    else -> { if (host == device) host = null; status.text = "Отключено" }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
        } else initBluetooth()
    }

    override fun onRequestPermissionsResult(code: Int, p: Array<out String>, r: IntArray) {
        super.onRequestPermissionsResult(code, p, r)
        if (r.firstOrNull() == PackageManager.PERMISSION_GRANTED) initBluetooth()
        else status.text = "Нужно разрешение Bluetooth"
    }

    private fun initBluetooth() {
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) { status.text = "Включите Bluetooth"; return }
        paired = adapter.bondedDevices.toList()
        spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            paired.map { it.name ?: it.address })
        adapter.getProfileProxy(this, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                hid = proxy as BluetoothHidDevice
                val sdp = BluetoothHidDeviceAppSdpSettings(
                    "BT Keyboard & Mouse", "Phone as keyboard and mouse", "BtKM",
                    BluetoothHidDevice.SUBCLASS1_COMBO, HidDescriptor.DESCRIPTOR)
                hid?.registerApp(sdp, null, null, ContextCompat.getMainExecutor(this@MainActivity), callback)
            }
            override fun onServiceDisconnected(profile: Int) { hid = null }
        }, BluetoothProfile.HID_DEVICE)
    }

    // ---------- sending ----------
    private fun send(id: Int, data: ByteArray) {
        val h = hid ?: return
        val d = host ?: return
        h.sendReport(d, id, data)
    }

    private fun pressKey(usage: Int, mod: Int = 0) {
        send(HidDescriptor.ID_KEYBOARD, byteArrayOf(mod.toByte(), 0, usage.toByte(), 0, 0, 0, 0, 0))
        send(HidDescriptor.ID_KEYBOARD, ByteArray(8))
    }

    private fun typeText(s: String) {
        for (c in s) HidDescriptor.charToHid(c)?.let { pressKey(it.first, it.second) }
    }

    private fun mouse(dx: Int, dy: Int, wheel: Int = 0) {
        send(HidDescriptor.ID_MOUSE, byteArrayOf(buttons.toByte(),
            dx.coerceIn(-127, 127).toByte(), dy.coerceIn(-127, 127).toByte(),
            wheel.coerceIn(-127, 127).toByte()))
    }

    private fun click(mask: Int) {
        buttons = mask; mouse(0, 0)
        buttons = 0; mouse(0, 0)
    }

    // ---------- UI ----------
    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 24, 24, 24) }
        status = TextView(this).apply { text = "Инициализация…"; textSize = 16f }
        spinner = Spinner(this)
        val connect = Button(this).apply {
            text = "Подключить"
            setOnClickListener {
                val dev = paired.getOrNull(spinner.selectedItemPosition) ?: return@setOnClickListener
                hid?.connect(dev)
            }
        }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(spinner, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        row.addView(connect)

        val pad = View(this).apply { setBackgroundColor(Color.parseColor("#33888888")) }
        setupTouchpad(pad)

        val clicks = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        clicks.addView(btn("ЛКМ") { click(1) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        clicks.addView(btn("СКМ") { click(4) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        clicks.addView(btn("ПКМ") { click(2) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))

        val keys = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("Esc" to 0x29, "Tab" to 0x2B, "⌫" to 0x2A, "↵" to 0x28,
            "←" to 0x50, "↑" to 0x52, "↓" to 0x51, "→" to 0x4F).forEach { (t, u) ->
            keys.addView(btn(t) { pressKey(u) }, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f))
        }

        val input = EditText(this).apply { hint = "Текст для отправки (латиница)" }
        val sendBtn = btn("Отправить") { typeText(input.text.toString()); input.setText("") }

        root.addView(status); root.addView(row)
        root.addView(pad, LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
        root.addView(clicks); root.addView(keys); root.addView(input); root.addView(sendBtn)
        setContentView(root)
    }

    private fun btn(t: String, a: () -> Unit) = Button(this).apply {
        text = t; isAllCaps = false; setOnClickListener { a() }
    }

    private fun setupTouchpad(v: View) {
        var lx = 0f; var ly = 0f; var downT = 0L; var moved = 0f
        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; downT = e.eventTime; moved = 0f }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lx; val dy = e.y - ly
                    lx = e.x; ly = e.y; moved += Math.abs(dx) + Math.abs(dy)
                    if (e.pointerCount >= 2) mouse(0, 0, (-dy / 8).toInt())
                    else mouse((dx * 1.5f).toInt(), (dy * 1.5f).toInt())
                }
                MotionEvent.ACTION_UP -> if (e.eventTime - downT < 200 && moved < 20) click(1)
            }
            true
        }
    }

    override fun onDestroy() {
        hid?.unregisterApp()
        super.onDestroy()
    }
}
