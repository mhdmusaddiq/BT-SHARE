package com.btshare.bluetoothrelay

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.btshare.bluetoothrelay.bluetooth.BluetoothRelayManager
import com.btshare.bluetoothrelay.bluetooth.ConnectionState
import java.util.Locale
import java.util.concurrent.Executor

@SuppressLint("MissingPermission")
class MainActivity : Activity(), BluetoothRelayManager.Listener {
    private enum class PendingAction {
        NONE,
        REFRESH_DEVICES,
        START_SERVER,
        CONNECT,
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var relayManager: BluetoothRelayManager? = null
    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var pendingPermissionAction = PendingAction.NONE
    private var pendingEnableAction = PendingAction.NONE

    private lateinit var stateText: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var listenButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var eventLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())

        bluetoothAdapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val adapter = bluetoothAdapter
        if (adapter == null) {
            updateState(ConnectionState.ERROR, "Bluetooth is not supported on this device")
            setControlsEnabled(false)
            return
        }

        relayManager = BluetoothRelayManager(
            adapter = adapter,
            callbackExecutor = Executor { action -> runOnUiThread(action) },
            listener = this,
        )
        updateState(ConnectionState.IDLE, "Ready")
        requestOrRun(PendingAction.REFRESH_DEVICES)
    }

    private fun createContentView(): View {
        val density = resources.displayMetrics.density
        val padding = (16 * density).toInt()
        val spacing = (8 * density).toInt()

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding, padding, padding)
        }

        content.addView(TextView(this).apply {
            text = getString(R.string.app_name)
            textSize = 22f
        })
        stateText = TextView(this).apply {
            textSize = 16f
            setPadding(0, spacing, 0, spacing)
        }
        content.addView(stateText)

        content.addView(TextView(this).apply { text = "Paired device" })
        deviceSpinner = Spinner(this)
        content.addView(deviceSpinner, matchWidthWrapHeight())

        val deviceActions = horizontalRow()
        val refreshButton = Button(this).apply {
            text = "Refresh paired"
            setOnClickListener { requestOrRun(PendingAction.REFRESH_DEVICES) }
        }
        connectButton = Button(this).apply {
            text = "Connect"
            setOnClickListener { requestOrRun(PendingAction.CONNECT) }
        }
        deviceActions.addView(refreshButton, weightedButton())
        deviceActions.addView(connectButton, weightedButton())
        content.addView(deviceActions, matchWidthWrapHeight())

        val connectionActions = horizontalRow()
        listenButton = Button(this).apply {
            text = "Listen as server"
            setOnClickListener { requestOrRun(PendingAction.START_SERVER) }
        }
        disconnectButton = Button(this).apply {
            text = "Disconnect"
            isEnabled = false
            setOnClickListener { relayManager?.stop() }
        }
        connectionActions.addView(listenButton, weightedButton())
        connectionActions.addView(disconnectButton, weightedButton())
        content.addView(connectionActions, matchWidthWrapHeight())

        messageInput = EditText(this).apply {
            hint = "Message to peer"
            maxLines = 4
        }
        content.addView(messageInput, matchWidthWrapHeight())
        sendButton = Button(this).apply {
            text = "Send framed message"
            isEnabled = false
            setOnClickListener { sendCurrentMessage() }
        }
        content.addView(sendButton, matchWidthWrapHeight())

        content.addView(TextView(this).apply {
            text = "Event log"
            textSize = 17f
            setPadding(0, spacing, 0, spacing)
        })
        eventLog = TextView(this).apply {
            setTextIsSelectable(true)
            typeface = android.graphics.Typeface.MONOSPACE
        }
        content.addView(eventLog, matchWidthWrapHeight())

        return ScrollView(this).apply { addView(content) }
    }

    private fun horizontalRow() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    private fun matchWidthWrapHeight() = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT,
        LinearLayout.LayoutParams.WRAP_CONTENT,
    )

    private fun weightedButton() = LinearLayout.LayoutParams(
        0,
        LinearLayout.LayoutParams.WRAP_CONTENT,
        1f,
    )

    private fun requestOrRun(action: PendingAction) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingPermissionAction = action
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                REQUEST_BLUETOOTH_PERMISSION,
            )
            return
        }
        runBluetoothAction(action)
    }

    private fun runBluetoothAction(action: PendingAction) {
        val adapter = bluetoothAdapter ?: return
        if (!adapter.isEnabled) {
            pendingEnableAction = action
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQUEST_ENABLE_BLUETOOTH)
            return
        }

        when (action) {
            PendingAction.NONE -> Unit
            PendingAction.REFRESH_DEVICES -> refreshPairedDevices(adapter)
            PendingAction.START_SERVER -> relayManager?.startServer()
            PendingAction.CONNECT -> connectSelectedDevice()
        }
    }

    private fun refreshPairedDevices(adapter: BluetoothAdapter) {
        pairedDevices = adapter.bondedDevices
            .orEmpty()
            .sortedWith(compareBy({ it.name ?: "" }, { it.address }))
        val labels = pairedDevices.map { device ->
            "${device.name ?: "Unnamed device"} (${device.address})"
        }
        deviceSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_dropdown_item,
            if (labels.isEmpty()) listOf("No paired devices") else labels,
        )
        connectButton.isEnabled = labels.isNotEmpty()
        appendLog("Found ${pairedDevices.size} paired device(s)")
    }

    private fun connectSelectedDevice() {
        val position = deviceSpinner.selectedItemPosition
        val device = pairedDevices.getOrNull(position)
        if (device == null) {
            showMessage("Choose a paired device first")
            return
        }
        relayManager?.connect(device)
    }

    private fun sendCurrentMessage() {
        val text = messageInput.text.toString()
        if (text.isBlank()) {
            showMessage("Enter a non-blank message")
            return
        }
        relayManager?.sendText(text)
        messageInput.text.clear()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_BLUETOOTH_PERMISSION) return

        val action = pendingPermissionAction
        pendingPermissionAction = PendingAction.NONE
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
            runBluetoothAction(action)
        } else {
            updateState(ConnectionState.ERROR, "Nearby devices permission was denied")
            appendLog("Permission denied; Bluetooth actions are unavailable")
        }
    }

    @Deprecated("Uses the platform Bluetooth enable activity for minSdk compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_ENABLE_BLUETOOTH) return

        val action = pendingEnableAction
        pendingEnableAction = PendingAction.NONE
        if (resultCode == RESULT_OK) {
            runBluetoothAction(action)
        } else {
            updateState(ConnectionState.ERROR, "Bluetooth was not enabled")
        }
    }

    override fun onStateChanged(state: ConnectionState, detail: String) {
        updateState(state, detail)
        appendLog("$state: $detail")
        val connected = state == ConnectionState.CONNECTED
        sendButton.isEnabled = connected
        disconnectButton.isEnabled = state != ConnectionState.IDLE
        listenButton.isEnabled = !connected
        connectButton.isEnabled = !connected && pairedDevices.isNotEmpty()
    }

    override fun onMessageSent(sequence: Long, text: String) {
        appendLog("TX DATA seq=$sequence bytes=${text.toByteArray().size}")
    }

    override fun onMessageReceived(sequence: Long, text: String) {
        appendLog("RX DATA seq=$sequence text=$text")
    }

    override fun onAcknowledgement(sequence: Long, roundTripNanos: Long) {
        val milliseconds = roundTripNanos / 1_000_000.0
        appendLog(String.format(Locale.US, "RX ACK seq=%d RTT=%.3f ms", sequence, milliseconds))
    }

    override fun onNotice(message: String) {
        appendLog("NOTICE: $message")
    }

    override fun onError(message: String, error: Throwable?) {
        appendLog("ERROR: $message${error?.message?.let { ": $it" } ?: ""}")
        showMessage(message)
    }

    private fun updateState(state: ConnectionState, detail: String) {
        stateText.text = "State: $state\n$detail"
    }

    private fun setControlsEnabled(enabled: Boolean) {
        deviceSpinner.isEnabled = enabled
        connectButton.isEnabled = enabled
        listenButton.isEnabled = enabled
        disconnectButton.isEnabled = enabled
        messageInput.isEnabled = enabled
        sendButton.isEnabled = enabled
    }

    private fun appendLog(line: String) {
        if (eventLog.text.isEmpty()) {
            eventLog.text = line
        } else {
            eventLog.append("\n$line")
        }
    }

    private fun showMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        relayManager?.close()
        relayManager = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSION = 1001
        private const val REQUEST_ENABLE_BLUETOOTH = 1002
    }
}
