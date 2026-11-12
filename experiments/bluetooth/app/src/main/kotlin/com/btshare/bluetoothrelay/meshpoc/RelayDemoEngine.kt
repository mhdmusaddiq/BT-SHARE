package com.btshare.bluetoothrelay.meshpoc

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.os.SystemClock
import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * A (host) -> B (relay, simultaneous incoming+outgoing sockets) -> C (listener).
 * Manual paired-device next-hop: a POC, NOT an automatic mesh/route-recovery system.
 * RFCOMM v2 uses a DIFFERENT UUID from the existing B0 text/audio POC.
 */
@SuppressLint("MissingPermission")
class RelayDemoEngine(
    private val adapter: BluetoothAdapter,
    private val listener: Listener,
) : AutoCloseable {
    enum class Role { IDLE, HOST, RELAY, LISTENER }
    interface Listener {
        fun log(line: String)
        fun status(line: String)
        fun audioReceived(audio: ByteArray)
    }

    private enum class Direction { UPSTREAM, DOWNSTREAM }
    private val pool = Executors.newCachedThreadPool(ThreadFactory { r ->
        Thread(r, "relay-demo-${WORKER_IDS.incrementAndGet()}").apply { isDaemon = true }
    })
    private val active = AtomicBoolean(false)
    private val stateLock = Any()
    private val guard = RelayDuplicateGuard(4096)
    private val assembler = PcmClipAssembler()
    private val originId = UUID.randomUUID()
    private val streamCounter = AtomicLong(1L)
    private val awaitingAck = ConcurrentHashMap<String, Long>()
    @Volatile private var role = Role.IDLE
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var connectingSocket: BluetoothSocket? = null
    private val epoch = AtomicLong(0L)
    @Volatile private var upstream: Link? = null
    @Volatile private var downstream: Link? = null
    @Volatile private var closed = false

    fun startListener() {
        reset()
        role = Role.LISTENER
        active.set(true)
        listener.status("C / LISTENER: waiting for B")
        val run = epoch.get()
        pool.execute { listenForUpstream(run) }
    }

    fun startRelay(nextHop: BluetoothDevice) {
        reset()
        role = Role.RELAY
        active.set(true)
        listener.status("B / RELAY: inbound listener + outbound connection")
        val run = epoch.get()
        pool.execute { listenForUpstream(run) }
        pool.execute { connectDownstream(run, nextHop) }
    }

    fun startHost(peer: BluetoothDevice) {
        reset()
        role = Role.HOST
        active.set(true)
        listener.status("A / HOST: connecting to relay")
        val run = epoch.get()
        pool.execute { connectDownstream(run, peer) }
    }

    fun sendText(text: String) {
        if (role != Role.HOST || !active.get()) { listener.log("Text send requires HOST mode"); return }
        val data = text.toByteArray(StandardCharsets.UTF_8)
        if (data.size !in 1..1024) { listener.log("Text must contain 1..1024 UTF-8 bytes"); return }
        val frame = RelayFrame(RelayFrameType.TEXT, UUID.randomUUID(), originId,
            streamCounter.getAndIncrement(), 1L, 4, 0, data)
        sendFromHost(frame)
    }

    fun sendAudio(clip: ByteArray) {
        if (role != Role.HOST || !active.get()) { listener.log("Audio send requires HOST mode"); return }
        if (clip.size != PcmClipProtocol.CLIP_BYTES) {
            listener.log("Invalid clip size, expected ${PcmClipProtocol.CLIP_BYTES} bytes"); return
        }
        val link = downstream
        if (link == null) { listener.log("No active HOST -> B socket"); return }
        val frames = PcmClipProtocol.frames(clip, UUID.randomUUID(), originId,
            streamCounter.getAndIncrement())
        // 42 frames fit the bounded 128-slot queue. Check capacity before any enqueues.
        if (!link.hasRoom(frames.size)) { listener.log("Audio queue busy; try again after it drains"); return }
        frames.forEach { frame -> sendFromHost(frame) }
        listener.log("HOST: queued ${frames.size} audio frames, ${clip.size} PCM bytes")
    }

    private fun sendFromHost(frame: RelayFrame) {
        val link = downstream ?: run { listener.log("Not connected to relay"); return }
        if (frame.type == RelayFrameType.TEXT || frame.type == RelayFrameType.AUDIO_END) {
            awaitingAck[ackKey(frame)] = SystemClock.elapsedRealtimeNanos()
        }
        if (!link.offer(frame)) {
            awaitingAck.remove(ackKey(frame))
            listener.log("TX queue full: frame ${frame.sequence} NOT queued")
        }
    }

    private fun isRun(run: Long): Boolean = active.get() && !closed && epoch.get() == run

    private fun listenForUpstream(run: Long) {
        var listening: BluetoothServerSocket? = null
        try {
            if (!isRun(run)) return
            val newServer = adapter.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
            listening = newServer
            synchronized(stateLock) { if (!isRun(run)) { newServer.close(); return }; server = newServer }
            listener.log("LISTENING: secure RFCOMM server ready")
            while (isRun(run)) {
                val socket = newServer.accept()
                if (!isRun(run)) { socket.close(); break }
                if (socket.remoteDevice.bondState != BluetoothDevice.BOND_BONDED) {
                    listener.log("Rejected unpaired inbound peer")
                    socket.close(); continue
                }
                if (upstream != null) {
                    listener.log("Only one incoming peer supported in this POC")
                    socket.close(); continue
                }
                attach(socket, Direction.UPSTREAM, run)
                listener.log("UPSTREAM CONNECTED")
            }
        } catch (e: Exception) {
            if (isRun(run)) listener.log("Incoming listener error: ${e.message}")
        } finally {
            runCatching { listening?.close() }
            synchronized(stateLock) { if (server === listening) server = null }
        }
    }

    private fun connectDownstream(run: Long, peer: BluetoothDevice) {
        var socket: BluetoothSocket? = null
        try {
            if (!isRun(run)) return
            val newSocket = peer.createRfcommSocketToServiceRecord(SERVICE_UUID)
            socket = newSocket
            // Closing from reset() is supported while connect() is blocking.
            synchronized(stateLock) {
                if (!isRun(run)) { newSocket.close(); return }
                connectingSocket = newSocket
            }
            adapter.cancelDiscovery()
            newSocket.connect()
            if (!isRun(run)) { newSocket.close(); return }
            synchronized(stateLock) { if (connectingSocket === newSocket) connectingSocket = null }
            attach(newSocket, Direction.DOWNSTREAM, run)
            listener.log("DOWNSTREAM CONNECTED")
            listener.status(if (role == Role.RELAY) "B: C connected; waiting for A incoming" else "A: connected to B")
        } catch (e: IOException) {
            if (isRun(run)) listener.log("OUTBOUND CONNECT FAILED: ${e.message} (C must Listen before B connects)")
            runCatching { socket?.close() }
        } catch (e: Exception) {
            if (isRun(run)) listener.log("Outbound error: ${e.message}")
            runCatching { socket?.close() }
        } finally {
            synchronized(stateLock) { if (connectingSocket === socket) connectingSocket = null }
        }
    }

    private fun attach(socket: BluetoothSocket, direction: Direction, run: Long) {
        val link = Link(socket, direction, run)
        synchronized(stateLock) {
            if (!isRun(run)) { link.close(); return }
            if (direction == Direction.UPSTREAM) upstream = link else downstream = link
        }
        pool.execute { link.writer() }
        pool.execute { link.reader() }
    }

    private fun onFrame(frame: RelayFrame, direction: Direction, run: Long) {
        if (!isRun(run)) return
        if (!guard.shouldProcess(frame)) {
            listener.log("DROP duplicate/expired: ${frame.type} stream=${frame.streamId} seq=${frame.sequence}")
            return
        }
        if (role == Role.RELAY) {
            if (frame.ttl <= 1 || frame.hops >= RelayWire.MAX_HOPS - 1) {
                listener.log("DROP TTL/hops exhausted at relay"); return
            }
            val outgoing = if (direction == Direction.UPSTREAM) downstream else upstream
            if (direction == Direction.DOWNSTREAM && frame.type != RelayFrameType.END_TO_END_ACK) {
                listener.log("DROP unexpected downstream DATA (static unidirectional POC)"); return
            }
            if (direction == Direction.UPSTREAM && frame.type == RelayFrameType.END_TO_END_ACK) {
                listener.log("DROP unexpected upstream ACK"); return
            }
            if (outgoing == null || !outgoing.offer(frame.forwarded())) {
                listener.log("FORWARD FAILED: peer absent or bounded queue full")
            } else {
                listener.log("RELAY ${if (direction == Direction.UPSTREAM) "A→C" else "C→A"} " +
                    "${frame.type} seq=${frame.sequence} ttl=${frame.ttl - 1}")
            }
            return
        }
        if (role == Role.HOST) {
            if (direction == Direction.DOWNSTREAM && frame.type == RelayFrameType.END_TO_END_ACK) {
                val start = awaitingAck.remove(ackKey(frame))
                if (start != null) {
                    val millis = (SystemClock.elapsedRealtimeNanos() - start) / 1_000_000.0
                    listener.log("E2E ACK seq=${frame.sequence} RTT=${"%.1f".format(java.util.Locale.US, millis)} ms")
                } else listener.log("Unexpected ACK seq=${frame.sequence}")
            }
            return
        }
        if (role != Role.LISTENER || direction != Direction.UPSTREAM) return
        when (frame.type) {
            RelayFrameType.TEXT -> {
                val text = try {
                    StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .decode(java.nio.ByteBuffer.wrap(frame.payload)).toString()
                } catch (e: Exception) { listener.log("Invalid UTF-8 text packet"); return }
                listener.log("C RECEIVED TEXT seq=${frame.sequence}: $text")
                ack(frame)
            }
            RelayFrameType.AUDIO_START, RelayFrameType.AUDIO_CHUNK, RelayFrameType.AUDIO_END -> {
                try {
                    val completed = assembler.accept(frame)
                    if (completed != null) {
                        listener.log("C RECEIVED AUDIO ${completed.size} bytes; checksum OK; 40 chunks")
                        listener.audioReceived(completed)
                        ack(frame)
                    }
                } catch (e: Exception) { listener.log("Audio reject: ${e.message}"); assembler.clear() }
            }
            RelayFrameType.END_TO_END_ACK -> Unit
        }
    }

    private fun ack(frame: RelayFrame) {
        val ack = frame.copy(type = RelayFrameType.END_TO_END_ACK, ttl = 4, hops = 0, payload = byteArrayOf())
        if (upstream?.offer(ack) != true) listener.log("ACK could not be queued")
    }

    private fun ackKey(frame: RelayFrame): String = "${frame.sessionId}/${frame.originId}/${frame.streamId}/${frame.sequence}"

    fun reset() {
        active.set(false)
        epoch.incrementAndGet()
        synchronized(stateLock) {
            runCatching { connectingSocket?.close() }; connectingSocket = null
            runCatching { server?.close() }; server = null
            upstream?.close(); upstream = null
            downstream?.close(); downstream = null
        }
        guard.clear()
        assembler.clear()
        awaitingAck.clear()
        role = Role.IDLE
    }

    override fun close() {
        closed = true
        reset()
        pool.shutdownNow()
    }

    private inner class Link(val socket: BluetoothSocket, val direction: Direction, val run: Long) : AutoCloseable {
        private val running = AtomicBoolean(true)
        private val queue = ArrayBlockingQueue<RelayFrame>(128)
        fun offer(frame: RelayFrame): Boolean = isRun(run) && running.get() && queue.offer(frame)
        fun hasRoom(count: Int): Boolean = queue.remainingCapacity() >= count
        fun reader() {
            try {
                val input = socket.inputStream
                while (isRun(run) && running.get()) {
                    val frame = RelayWire.read(input) ?: break
                    onFrame(frame, direction, run)
                }
            } catch (e: Exception) {
                if (isRun(run) && running.get()) listener.log("Socket read stopped: ${e.message}")
            } finally { close() }
        }
        fun writer() {
            try {
                val output = socket.outputStream
                while (isRun(run) && running.get()) {
                    val next = queue.poll(250, TimeUnit.MILLISECONDS) ?: continue
                    RelayWire.write(output, next)
                }
            } catch (e: Exception) {
                if (isRun(run) && running.get()) listener.log("Socket write stopped: ${e.message}")
            } finally { close() }
        }
        override fun close() {
            if (!running.compareAndSet(true, false)) return
            runCatching { socket.close() }
            synchronized(stateLock) {
                if (direction == Direction.UPSTREAM && upstream === this) upstream = null
                if (direction == Direction.DOWNSTREAM && downstream === this) downstream = null
            }
            if (isRun(run)) {
                listener.log("${direction.name} DISCONNECTED")
                listener.status("Link lost; stop and restart roles. No automatic failover in R1/R2.")
            }
        }
    }

    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("b73608b4-2599-4a45-9f4a-a987bc805372")
        const val SERVICE_NAME = "BT-SHARE Relay Demo POC v2"
        private val WORKER_IDS = AtomicLong(0)
    }
}
