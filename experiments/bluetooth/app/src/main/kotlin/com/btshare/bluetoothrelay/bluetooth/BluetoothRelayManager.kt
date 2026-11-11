package com.btshare.bluetoothrelay.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import com.btshare.bluetoothrelay.protocol.RelaySession
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executor
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@SuppressLint("MissingPermission")
class BluetoothRelayManager(
    private val adapter: BluetoothAdapter,
    private val callbackExecutor: Executor,
    private val listener: Listener,
) : AutoCloseable {
    interface Listener {
        fun onStateChanged(state: ConnectionState, detail: String)
        fun onMessageSent(sequence: Long, text: String)
        fun onMessageReceived(sequence: Long, text: String)
        fun onAcknowledgement(sequence: Long, roundTripNanos: Long)
        fun onNotice(message: String)
        fun onError(message: String, error: Throwable?)
    }

    private val connectionExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        NamedThreadFactory("bt-relay-connection"),
    )
    private val writerExecutor: ExecutorService = Executors.newSingleThreadExecutor(
        NamedThreadFactory("bt-relay-writer"),
    )
    private val lock = Any()
    private val closed = AtomicBoolean(false)

    @Volatile
    private var generation = 0L
    private var activeServerSocket: BluetoothServerSocket? = null
    private var activeSocket: BluetoothSocket? = null
    private var activeSession: RelaySession? = null

    fun startServer() {
        ensureOpen()
        val runId = replaceActiveConnection()
        emitState(ConnectionState.LISTENING, "Opening secure RFCOMM server")
        connectionExecutor.execute { runServer(runId) }
    }

    fun connect(device: BluetoothDevice) {
        ensureOpen()
        val runId = replaceActiveConnection()
        emitState(ConnectionState.CONNECTING, "Connecting to ${safeDeviceLabel(device)}")
        connectionExecutor.execute { runClient(runId, device) }
    }

    fun sendText(text: String) {
        ensureOpen()
        val sessionAndGeneration = synchronized(lock) {
            activeSession?.let { it to generation }
        }
        if (sessionAndGeneration == null) {
            emitError("Cannot send while no peer is connected", null)
            return
        }

        val (session, runId) = sessionAndGeneration
        writerExecutor.execute {
            try {
                val sequence = session.sendText(text)
                if (isActive(runId)) {
                    dispatch { listener.onMessageSent(sequence, text) }
                }
            } catch (error: Exception) {
                handleConnectionFailure(runId, "Send failed", error)
            }
        }
    }

    fun stop() {
        if (closed.get()) return
        synchronized(lock) {
            generation += 1
            closeActiveLocked()
        }
        emitState(ConnectionState.IDLE, "Stopped")
    }

    private fun runServer(runId: Long) {
        var serverSocket: BluetoothServerSocket? = null
        try {
            if (!isActive(runId)) return
            serverSocket = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            synchronized(lock) {
                if (!isActive(runId)) {
                    serverSocket.close()
                    return
                }
                activeServerSocket = serverSocket
            }
            emitState(ConnectionState.LISTENING, "Waiting for a paired peer")

            while (isActive(runId)) {
                val socket = serverSocket.accept()
                if (!isActive(runId)) {
                    socket.close()
                    break
                }
                runConnectedSocket(runId, socket, "Accepted ${safeDeviceLabel(socket.remoteDevice)}")
                if (isActive(runId)) {
                    emitState(ConnectionState.LISTENING, "Peer disconnected; waiting again")
                }
            }
        } catch (error: IOException) {
            if (isActive(runId)) {
                handleConnectionFailure(runId, "Secure RFCOMM server failed", error)
            }
        } finally {
            runCatching { serverSocket?.close() }
            synchronized(lock) {
                if (activeServerSocket === serverSocket) activeServerSocket = null
            }
        }
    }

    private fun runClient(runId: Long, device: BluetoothDevice) {
        var socket: BluetoothSocket? = null
        try {
            if (!isActive(runId)) return
            socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
            synchronized(lock) {
                if (!isActive(runId)) {
                    socket.close()
                    return
                }
                activeSocket = socket
            }
            socket.connect()
            if (!isActive(runId)) return
            runConnectedSocket(runId, socket, "Connected to ${safeDeviceLabel(device)}")
        } catch (error: IOException) {
            if (isActive(runId)) {
                handleConnectionFailure(runId, "Secure RFCOMM connection failed", error)
            }
        } finally {
            runCatching { socket?.close() }
            synchronized(lock) {
                if (activeSocket === socket) activeSocket = null
            }
            if (isActive(runId)) {
                emitState(ConnectionState.DISCONNECTED, "Connection ended")
            }
        }
    }

    private fun runConnectedSocket(runId: Long, socket: BluetoothSocket, detail: String) {
        val session = RelaySession(
            input = socket.inputStream,
            output = socket.outputStream,
            listener = sessionListener(runId),
        )
        synchronized(lock) {
            if (!isActive(runId)) {
                session.close()
                socket.close()
                return
            }
            activeSocket = socket
            activeSession = session
        }
        emitState(ConnectionState.CONNECTED, detail)

        try {
            session.readLoop()
        } catch (error: Exception) {
            if (isActive(runId)) {
                emitError("Connection read failed", error)
                emitState(ConnectionState.ERROR, error.message ?: "Read failure")
            }
        } finally {
            synchronized(lock) {
                if (activeSession === session) activeSession = null
                if (activeSocket === socket) activeSocket = null
            }
            session.close()
            runCatching { socket.close() }
            if (isActive(runId)) {
                emitState(ConnectionState.DISCONNECTED, "Peer disconnected")
            }
        }
    }

    private fun sessionListener(runId: Long) = object : RelaySession.Listener {
        override fun onMessageReceived(sequence: Long, text: String) {
            if (isActive(runId)) dispatch { listener.onMessageReceived(sequence, text) }
        }

        override fun onAcknowledgementReceived(sequence: Long, roundTripNanos: Long) {
            if (isActive(runId)) dispatch {
                listener.onAcknowledgement(sequence, roundTripNanos)
            }
        }

        override fun onUnexpectedAcknowledgement(sequence: Long) {
            if (isActive(runId)) dispatch {
                listener.onNotice("Ignored unexpected ACK for sequence $sequence")
            }
        }
    }

    private fun handleConnectionFailure(runId: Long, message: String, error: Exception) {
        if (!isActive(runId)) return
        emitError(message, error)
        emitState(ConnectionState.ERROR, error.message ?: message)
        synchronized(lock) {
            if (generation == runId) {
                runCatching { activeSession?.close() }
                runCatching { activeSocket?.close() }
                activeSession = null
                activeSocket = null
            }
        }
    }

    private fun replaceActiveConnection(): Long = synchronized(lock) {
        generation += 1
        closeActiveLocked()
        generation
    }

    private fun closeActiveLocked() {
        runCatching { activeSession?.close() }
        runCatching { activeSocket?.close() }
        runCatching { activeServerSocket?.close() }
        activeSession = null
        activeSocket = null
        activeServerSocket = null
    }

    private fun isActive(runId: Long): Boolean = !closed.get() && generation == runId

    private fun ensureOpen() {
        check(!closed.get()) { "BluetoothRelayManager is closed" }
    }

    private fun emitState(state: ConnectionState, detail: String) {
        dispatch { listener.onStateChanged(state, detail) }
    }

    private fun emitError(message: String, error: Throwable?) {
        dispatch { listener.onError(message, error) }
    }

    private fun dispatch(block: () -> Unit) {
        callbackExecutor.execute(block)
    }

    private fun safeDeviceLabel(device: BluetoothDevice): String =
        runCatching { "${device.name ?: "Unnamed device"} (${device.address})" }
            .getOrDefault("Paired Bluetooth device")

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            generation += 1
            closeActiveLocked()
        }
        emitState(ConnectionState.IDLE, "Closed")
        connectionExecutor.shutdownNow()
        writerExecutor.shutdownNow()
    }

    companion object {
        const val SERVICE_NAME = "BT-SHARE Bluetooth Relay B0"
        val SERVICE_UUID: UUID = UUID.fromString("7e57d004-2b97-4ea4-b45f-5f9c2a9a0f51")
    }
}

private class NamedThreadFactory(
    private val baseName: String,
) : ThreadFactory {
    private val threadNumber = AtomicInteger(1)

    override fun newThread(runnable: Runnable): Thread =
        Thread(runnable, "$baseName-${threadNumber.getAndIncrement()}").apply {
            isDaemon = true
        }
}
