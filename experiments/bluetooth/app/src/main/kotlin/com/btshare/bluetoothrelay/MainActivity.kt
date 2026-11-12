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
import com.btshare.bluetoothrelay.audio.VoiceAudioController
import com.btshare.bluetoothrelay.bluetooth.BluetoothRelayManager
import com.btshare.bluetoothrelay.bluetooth.ConnectionState
import com.btshare.bluetoothrelay.protocol.AudioReceiveSummary
import com.btshare.bluetoothrelay.protocol.AudioSendSummary
import com.btshare.bluetoothrelay.protocol.AudioStreamMetadata
import com.btshare.bluetoothrelay.protocol.AudioWireFormat
import java.util.Locale
import java.util.concurrent.Executor

@SuppressLint("MissingPermission")
class MainActivity : Activity(), BluetoothRelayManager.Listener, VoiceAudioController.Listener {
    private enum class PendingAction {
        NONE,
        REFRESH_DEVICES,
        START_SERVER,
        CONNECT,
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var relayManager: BluetoothRelayManager? = null
    private var voiceAudioController: VoiceAudioController? = null
    private var pairedDevices: List<BluetoothDevice> = emptyList()
    private var localRecording: ByteArray? = null
    private var connectionState = ConnectionState.IDLE
    private var recordingInProgress = false
    private var pendingPermissionAction = PendingAction.NONE
    private var pendingEnableAction = PendingAction.NONE

    private lateinit var stateText: TextView
    private lateinit var deviceSpinner: Spinner
    private lateinit var connectButton: Button
    private lateinit var listenButton: Button
    private lateinit var disconnectButton: Button
    private lateinit var messageInput: EditText
    private lateinit var sendButton: Button
    private lateinit var recordVoiceButton: Button
    private lateinit var playLocalButton: Button
    private lateinit var sendAudioButton: Button
    private lateinit var stopAudioButton: Button
    private lateinit var receiverPlaybackStatus: TextView
    private lateinit var eventLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContentView())

        voiceAudioController = VoiceAudioController(
            callbackExecutor = Executor { action -> runOnUiThread(action) },
            listener = this,
        )

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
            text = "Voice proof of concept"
            textSize = 17f
            setPadding(0, spacing, 0, 0)
        })
        content.addView(TextView(this).apply {
            text = "16 kHz, mono, signed 16-bit little-endian PCM; target 5 seconds"
        })

        val recordingActions = horizontalRow()
        recordVoiceButton = Button(this).apply {
            text = "Record Voice"
            setOnClickListener { requestMicrophoneAndRecord() }
        }
        playLocalButton = Button(this).apply {
            text = "Play Local Recording"
            isEnabled = false
            setOnClickListener {
                localRecording?.let { voiceAudioController?.playLocal(it) }
            }
        }
        recordingActions.addView(recordVoiceButton, weightedButton())
        recordingActions.addView(playLocalButton, weightedButton())
        content.addView(recordingActions, matchWidthWrapHeight())

        val audioTransferActions = horizontalRow()
        sendAudioButton = Button(this).apply {
            text = "Send Audio"
            isEnabled = false
            setOnClickListener {
                val recording = localRecording
                if (recording == null) {
                    showMessage("Record a voice clip first")
                } else {
                    appendLog(
                        "TX AUDIO queued bytes=${recording.size} " +
                            "duration=${AudioWireFormat.durationMillis(recording.size.toLong())}ms",
                    )
                    relayManager?.sendAudio(recording)
                }
            }
        }
        stopAudioButton = Button(this).apply {
            text = "Stop Audio"
            setOnClickListener {
                voiceAudioController?.stopAll()
                relayManager?.stopAudio()
                receiverPlaybackStatus.text = "Receiver playback: stopped"
                appendLog("Audio stop requested")
            }
        }
        audioTransferActions.addView(sendAudioButton, weightedButton())
        audioTransferActions.addView(stopAudioButton, weightedButton())
        content.addView(audioTransferActions, matchWidthWrapHeight())

        receiverPlaybackStatus = TextView(this).apply {
            text = "Receiver playback: idle"
            setPadding(0, spacing, 0, spacing)
        }
        content.addView(receiverPlaybackStatus, matchWidthWrapHeight())

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

    private fun requestMicrophoneAndRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            voiceAudioController?.recordFiveSeconds()
            return
        }
        requestPermissions(
            arrayOf(Manifest.permission.RECORD_AUDIO),
            REQUEST_RECORD_AUDIO_PERMISSION,
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_BLUETOOTH_PERMISSION -> {
                val action = pendingPermissionAction
                pendingPermissionAction = PendingAction.NONE
                if (grantResults.isNotEmpty() &&
                    grantResults.all { it == PackageManager.PERMISSION_GRANTED }
                ) {
                    runBluetoothAction(action)
                } else {
                    updateState(ConnectionState.ERROR, "Nearby devices permission was denied")
                    appendLog("Permission denied; Bluetooth actions are unavailable")
                }
            }

            REQUEST_RECORD_AUDIO_PERMISSION -> {
                if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
                    voiceAudioController?.recordFiveSeconds()
                } else {
                    appendLog("Microphone permission denied; voice recording is unavailable")
                    showMessage("Microphone permission is required to record voice")
                }
            }
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
        connectionState = state
        updateState(state, detail)
        appendLog("$state: $detail")
        val connected = state == ConnectionState.CONNECTED
        sendButton.isEnabled = connected
        disconnectButton.isEnabled = state != ConnectionState.IDLE
        listenButton.isEnabled = !connected
        connectButton.isEnabled = !connected && pairedDevices.isNotEmpty()
        updateAudioControls()
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

    override fun onAudioSendFinished(summary: AudioSendSummary) {
        appendLog(
            "TX AUDIO ${summary.result} stream=${summary.streamId} chunks=${summary.chunkCount} " +
                "bytes=${summary.pcmBytes} duration=${summary.durationMillis}ms",
        )
    }

    override fun onAudioReceiveStarted(streamId: Long, metadata: AudioStreamMetadata) {
        receiverPlaybackStatus.text = "Receiver playback: buffering stream $streamId"
        appendLog(
            "RX AUDIO_START stream=$streamId rate=${metadata.sampleRateHz}Hz " +
                "channels=${metadata.channelCount} encoding=${metadata.encoding} " +
                "bytes=${metadata.declaredPcmBytes} chunkBytes=${metadata.chunkSizeBytes}",
        )
    }

    override fun onAudioReceiveFinished(summary: AudioReceiveSummary) {
        appendLog(
            "RX AUDIO_END ${summary.result} stream=${summary.streamId} " +
                "chunks=${summary.chunkCount} bytes=${summary.pcmBytes} " +
                "duration=${summary.durationMillis}ms missing=${summary.missingChunks} " +
                "duplicates=${summary.duplicateChunks} outOfOrder=${summary.outOfOrderChunks} " +
                "detail=${summary.detail}",
        )
    }

    override fun onAudioPlaybackStatus(message: String) {
        receiverPlaybackStatus.text = "Receiver playback: $message"
        appendLog("PLAYBACK: $message")
    }

    override fun onNotice(message: String) {
        appendLog("NOTICE: $message")
    }

    override fun onError(message: String, error: Throwable?) {
        appendLog("ERROR: $message${error?.message?.let { ": $it" } ?: ""}")
        showMessage(message)
    }

    override fun onRecordingStarted() {
        recordingInProgress = true
        updateAudioControls()
        appendLog("Recording started: target=5000ms format=16000Hz mono PCM16LE")
    }

    override fun onRecordingReady(pcmBytes: ByteArray, stoppedEarly: Boolean) {
        localRecording = pcmBytes
        recordingInProgress = false
        updateAudioControls()
        appendLog(
            "Recording ready bytes=${pcmBytes.size} " +
                "duration=${AudioWireFormat.durationMillis(pcmBytes.size.toLong())}ms " +
                "stoppedEarly=$stoppedEarly",
        )
    }

    override fun onLocalPlaybackStarted(pcmBytes: Int) {
        appendLog(
            "Local playback started bytes=$pcmBytes " +
                "duration=${AudioWireFormat.durationMillis(pcmBytes.toLong())}ms",
        )
    }

    override fun onLocalPlaybackFinished(
        pcmBytes: Int,
        stoppedEarly: Boolean,
        bufferUnderruns: Int,
    ) {
        appendLog(
            "Local playback finished bytes=$pcmBytes stoppedEarly=$stoppedEarly " +
                "underruns=$bufferUnderruns",
        )
    }

    override fun onAudioError(message: String, error: Throwable?) {
        recordingInProgress = false
        updateAudioControls()
        onError(message, error)
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
        recordVoiceButton.isEnabled = enabled
        playLocalButton.isEnabled = enabled && localRecording != null
        sendAudioButton.isEnabled = enabled && localRecording != null
        stopAudioButton.isEnabled = enabled
    }

    private fun updateAudioControls() {
        recordVoiceButton.isEnabled = !recordingInProgress
        playLocalButton.isEnabled = !recordingInProgress && localRecording != null
        sendAudioButton.isEnabled = !recordingInProgress && localRecording != null &&
            connectionState == ConnectionState.CONNECTED
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
        voiceAudioController?.close()
        voiceAudioController = null
        relayManager?.close()
        relayManager = null
        super.onDestroy()
    }

    companion object {
        private const val REQUEST_BLUETOOTH_PERMISSION = 1001
        private const val REQUEST_ENABLE_BLUETOOTH = 1002
        private const val REQUEST_RECORD_AUDIO_PERMISSION = 1003
    }
}
