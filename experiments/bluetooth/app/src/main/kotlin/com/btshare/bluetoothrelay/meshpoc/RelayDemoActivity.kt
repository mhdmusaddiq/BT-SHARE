package com.btshare.bluetoothrelay.meshpoc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.atomic.AtomicBoolean
import java.util.ArrayDeque

/**
 * A SEPARATE launcher entry. Original B0 + two-phone audio POC MainActivity is untouched.
 * All role assignment is manual; three real phones are needed to claim B1/B2 PASS.
 */
@SuppressLint("MissingPermission")
class RelayDemoActivity : Activity(), RelayDemoEngine.Listener {
    private var adapter: BluetoothAdapter? = null
    private var engine: RelayDemoEngine? = null
    private var audio: RelayPcmAudio? = null
    private var devices: List<BluetoothDevice> = emptyList()
    private lateinit var deviceSpinner: Spinner
    private lateinit var stateView: TextView
    private lateinit var logView: TextView
    private lateinit var message: EditText
    private lateinit var sendAudio: Button
    private lateinit var playLocal: Button
    private val lines = ArrayDeque<String>()
    private val ended = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "BT-SHARE Relay Demo"
        setContentView(makeView())
        adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val bt = adapter
        if (bt == null) {
            status("Bluetooth unavailable")
            return
        }
        engine = RelayDemoEngine(bt, this)
        audio = RelayPcmAudio { log(it) }
        ensureBtPermission { refresh() }
    }

    private fun makeView(): View {
        val pad = (14f * resources.displayMetrics.density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        fun text(value: String, size: Float = 15f) = TextView(this).apply {
            this.text = value; textSize = size; setPadding(0, pad / 3, 0, pad / 3)
        }
        fun button(label: String, action: () -> Unit): Button = Button(this).apply {
            text = label; setOnClickListener { action() }
        }
        fun add(v: View) = container.addView(v, LinearLayout.LayoutParams(-1, -2))
        add(text("BT-SHARE: THREE-PHONE RELAY POC (v2)", 19f))
        add(text("Order: C Listen → B select C & Relay → A select B & Host. Use the same APK on all 3 phones."))
        stateView = text("IDLE", 16f); add(stateView)
        add(text("Paired next-hop device (B selects C; A selects B):"))
        deviceSpinner = Spinner(this); add(deviceSpinner)
        add(button("REFRESH PAIRED DEVICES") { ensureBtPermission { refresh() } })
        add(button("C — LISTEN AS RECEIVER") { ensureBtPermission { engine?.startListener() } })
        add(button("B — RELAY: CONNECT TO C + LISTEN FOR A") {
            ensureBtPermission {
                val peer = selectedPeer() ?: return@ensureBtPermission
                engine?.startRelay(peer)
            }
        })
        add(button("A — HOST: CONNECT TO B") {
            ensureBtPermission {
                val peer = selectedPeer() ?: return@ensureBtPermission
                engine?.startHost(peer)
            }
        })
        add(button("DISCONNECT / RESET ROLE") { engine?.reset(); status("IDLE: all links closed") })
        message = EditText(this).apply { hint = "Text packet to test A → B → C"; setSingleLine(true) }
        add(message)
        add(button("A: SEND TEST TEXT") {
            val value = message.text.toString()
            if (value.isNotBlank()) engine?.sendText(value)
        })
        add(text("AUDIO: 5-second mono 16kHz / 16-bit PCM (5 sec, 160 KB). NOT live streaming."))
        add(button("A: RECORD 5s VOICE") {
            ensureMicPermission {
                sendAudio.isEnabled = false
                audio?.recordFiveSeconds { ok -> runOnUiThread {
                    if (!ended.get()) {
                        playLocal.isEnabled = ok
                        sendAudio.isEnabled = ok
                    }
                } }
            }
        })
        playLocal = button("A: PLAY LOCAL RECORDING") {
            audio?.recordingCopy()?.let { audio?.play(it) }
        }.apply { isEnabled = false }
        add(playLocal)
        sendAudio = button("A: SEND AUDIO TO C THROUGH B") {
            audio?.recordingCopy()?.let { engine?.sendAudio(it) } ?: log("Record voice first")
        }.apply { isEnabled = false }
        add(sendAudio)
        add(button("STOP AUDIO") { audio?.stop() })
        add(button("COPY LOG FOR EVIDENCE") {
            val result = synchronized(lines) { lines.joinToString("\n") }
            (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                .setPrimaryClip(ClipData.newPlainText("BT-SHARE Relay POC log", result))
            Toast.makeText(this, "Log copied", Toast.LENGTH_SHORT).show()
        })
        add(text("LOG (last 200 lines):", 17f))
        logView = text("", 12f).apply { setTextIsSelectable(true); typeface = android.graphics.Typeface.MONOSPACE }
        add(logView)
        return ScrollView(this).apply { addView(container) }
    }

    private fun selectedPeer(): BluetoothDevice? {
        val selected = devices.getOrNull(deviceSpinner.selectedItemPosition)
        if (selected == null) log("Select a paired device first")
        return selected
    }

    private fun ensureBtPermission(action: () -> Unit) {
        val missing = if (Build.VERSION.SDK_INT >= 31) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
            ).filter {
                checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
            }.toTypedArray()
        } else {
            emptyArray()
        }
        if (missing.isNotEmpty()) {
            pendingAction = action
            requestPermissions(missing, 9001)
        } else {
            val bt = adapter
            if (bt?.isEnabled != true) {
                log("Enable Bluetooth in Android Settings before continuing")
            } else action()
        }
    }

    private fun ensureMicPermission(action: () -> Unit) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            pendingAction = action
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 9002)
        } else action()
    }

    private var pendingAction: (() -> Unit)? = null
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val action = pendingAction
        pendingAction = null
        if (requestCode in 9001..9002 && grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }) action?.invoke()
        else log("Required permission denied")
    }

    private fun refresh() {
        val bt = adapter ?: return
        devices = bt.bondedDevices.orEmpty().sortedWith(compareBy({ it.name ?: "" }, { it.address }))
        val options = devices.map { "${it.name ?: "Unnamed"} (${it.address.takeLast(5)})" }
        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item,
            if (options.isEmpty()) listOf("No paired devices") else options)
        log("Paired devices: ${devices.size}")
    }

    override fun log(line: String) {
        if (ended.get()) return
        runOnUiThread {
            if (ended.get()) return@runOnUiThread
            synchronized(lines) {
                if (lines.size >= 200) lines.removeFirst()
                lines.addLast(line)
                logView.text = lines.joinToString("\n")
            }
        }
    }

    override fun status(line: String) {
        if (ended.get()) return
        runOnUiThread { if (!ended.get()) stateView.text = line }
        log(line)
    }

    override fun audioReceived(audio: ByteArray) {
        log("C received complete PCM clip; playing on speaker")
        this.audio?.play(audio)
    }

    override fun onDestroy() {
        ended.set(true)
        audio?.close()
        engine?.close()
        super.onDestroy()
    }
}
