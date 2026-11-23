package com.example.bluetoothdeduction.bluetooth

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

interface BluetoothTransportListener {
    fun onPeerConnected(peerId: String, deviceName: String)
    fun onPeerDisconnected(peerId: String)
    fun onMessage(peerId: String, type: String, payload: JSONObject)
    fun onTransportError(message: String, cause: Throwable? = null)
}

/**
 * Bluetooth Classic RFCOMM/SPP transport.
 * RFCOMM provides an ordered reliable byte stream. This class adds application
 * sequence numbers, ACKs, and retries for important messages. Each message is
 * one UTF-8 JSON object terminated by a newline.
 */
class BluetoothClassicTransport(
    context: Context,
    private val listener: BluetoothTransportListener
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        const val SERVICE_NAME = "DeductionGameSPP"
        const val SERVER_PEER_ID = "__server__"
        private const val RETRY_INTERVAL_MS = 1_800L
        private const val MAX_PENDING_MESSAGES = 256
        private const val MOVEMENT_INTERVAL_MS = 500L
    }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val io = Executors.newCachedThreadPool()
    private val retryExecutor: ScheduledExecutorService = Executors.newScheduledThreadPool(1)
    private val peers = ConcurrentHashMap<String, PeerConnection>()
    @Volatile private var serverSocket: BluetoothServerSocket? = null
    @Volatile private var stopped = false

    fun isBluetoothAvailable(): Boolean = adapter != null
    fun isBluetoothEnabled(): Boolean = adapter?.isEnabled == true

    /** Starts the RFCOMM server. Runtime BLUETOOTH_CONNECT permission is required on Android 12+. */
    fun startServer() {
        val bluetooth = adapter ?: throw IllegalStateException("هذا الجهاز لا يدعم Bluetooth")
        check(bluetooth.isEnabled) { "يرجى تشغيل Bluetooth أولاً" }
        stopped = false
        io.execute {
            try {
                val socket = bluetooth.listenUsingRfcommWithServiceRecord(SERVICE_NAME, SERVICE_UUID)
                serverSocket = socket
                while (!stopped) {
                    val accepted = socket.accept()
                    if (accepted != null) attach(accepted, randomPeerId())
                }
            } catch (t: Throwable) {
                if (!stopped) listener.onTransportError("تعذر تشغيل خادم Bluetooth", t)
            }
        }
    }

    /** Connects to a device that has already been paired in Android settings. */
    fun connect(device: BluetoothDevice) {
        val bluetooth = adapter ?: throw IllegalStateException("هذا الجهاز لا يدعم Bluetooth")
        check(bluetooth.isEnabled) { "يرجى تشغيل Bluetooth أولاً" }
        stopped = false
        io.execute {
            try {
                bluetooth.cancelDiscovery()
                val socket = device.createRfcommSocketToServiceRecord(SERVICE_UUID)
                socket.connect()
                attach(socket, SERVER_PEER_ID)
            } catch (t: Throwable) {
                listener.onTransportError("فشل الاتصال بجهاز Bluetooth", t)
            }
        }
    }

    fun sendTo(peerId: String, type: String, payload: JSONObject = JSONObject(), reliable: Boolean = true) {
        val peer = peers[peerId] ?: throw IllegalStateException("الاتصال غير موجود: $peerId")
        peer.send(type, payload, reliable)
    }

    fun broadcast(type: String, payload: JSONObject = JSONObject(), reliable: Boolean = true) {
        peers.values.forEach { peer ->
            runCatching { peer.send(type, payload, reliable) }
                .onFailure { listener.onTransportError("تعذر إرسال الرسالة إلى ${peer.peerId}", it) }
        }
    }

    /** Low-priority movement updates are intentionally throttled and unacknowledged. */
    fun sendMovement(peerId: String, x: Float, y: Float) {
        val payload = JSONObject().put("x", x.toDouble()).put("y", y.toDouble())
        peers[peerId]?.sendMovement(payload)
    }

    fun disconnectPeer(peerId: String) {
        peers[peerId]?.close()
    }

    fun stop() {
        stopped = true
        runCatching { serverSocket?.close() }
        serverSocket = null
        peers.values.toList().forEach { it.close() }
        peers.clear()
        retryExecutor.shutdownNow()
        io.shutdownNow()
    }

    private fun attach(socket: BluetoothSocket, peerId: String) {
        val name = runCatching { socket.remoteDevice.name }.getOrNull() ?: "Bluetooth player"
        val peer = PeerConnection(peerId, socket)
        peers.put(peerId, peer)?.close()
        listener.onPeerConnected(peerId, name)
        peer.start()
    }

    private fun randomPeerId(): String {
        val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private inner class PeerConnection(
        val peerId: String,
        private val socket: BluetoothSocket
    ) {
        private val lock = Any()
        private val sequence = AtomicLong(1L)
        private val pending = linkedMapOf<Long, String>()
        private var lastReceivedSequence = 0L
        @Volatile private var closed = false
        @Volatile private var lastMovementAt = 0L
        private val writer = OutputStreamWriter(socket.outputStream, Charsets.UTF_8)
        private val reader = BufferedReader(InputStreamReader(socket.inputStream, Charsets.UTF_8))
        private val retryTask = retryExecutor.scheduleWithFixedDelay(
            { retryPending() }, RETRY_INTERVAL_MS, RETRY_INTERVAL_MS, TimeUnit.MILLISECONDS
        )

        fun start() {
            io.execute {
                try {
                    while (!closed) {
                        val line = reader.readLine() ?: break
                        handleLine(line)
                    }
                } catch (t: Throwable) {
                    if (!closed) listener.onTransportError("انقطع تدفق بيانات Bluetooth", t)
                } finally {
                    close()
                }
            }
        }

        fun send(type: String, payload: JSONObject, reliable: Boolean) {
            check(!closed) { "الاتصال مغلق" }
            synchronized(lock) {
                val envelope = JSONObject().put("type", type).put("payload", payload)
                if (reliable) {
                    check(pending.size < MAX_PENDING_MESSAGES) { "قائمة الرسائل المعلقة ممتلئة" }
                    val seq = sequence.getAndIncrement()
                    envelope.put("seq", seq)
                    val line = envelope.toString()
                    pending[seq] = line
                    writeLine(line)
                } else {
                    writeLine(envelope.toString())
                }
            }
        }

        fun sendMovement(payload: JSONObject) {
            val now = System.currentTimeMillis()
            if (now - lastMovementAt < MOVEMENT_INTERVAL_MS || closed) return
            lastMovementAt = now
            runCatching { send("POSITION", payload, reliable = false) }
        }

        private fun handleLine(line: String) {
            val envelope = JSONObject(line)
            val ack = envelope.optLong("ack", 0L)
            if (ack > 0L) synchronized(lock) { pending.remove(ack) }

            val type = envelope.optString("type", "")
            if (type == "ACK") return

            val seq = envelope.optLong("seq", 0L)
            if (seq > 0L) {
                sendAck(seq)
                synchronized(lock) {
                    if (seq <= lastReceivedSequence) return
                    lastReceivedSequence = seq
                }
            }
            val payload = envelope.optJSONObject("payload") ?: JSONObject()
            listener.onMessage(peerId, type, payload)
        }

        private fun sendAck(seq: Long) {
            synchronized(lock) {
                if (!closed) {
                    val ack = JSONObject().put("type", "ACK").put("ack", seq)
                    writeLine(ack.toString())
                }
            }
        }

        private fun retryPending() {
            synchronized(lock) {
                if (closed || pending.isEmpty()) return
                pending.values.toList().forEach { line ->
                    runCatching { writeLine(line) }
                        .onFailure { listener.onTransportError("تعذرت إعادة إرسال رسالة", it) }
                }
            }
        }

        private fun writeLine(line: String) {
            writer.write(line)
            writer.write("\n")
            writer.flush()
        }

        fun close() {
            if (closed) return
            closed = true
            runCatching { retryTask.cancel(true) }
            runCatching { reader.close() }
            runCatching { writer.close() }
            runCatching { socket.close() }
            peers.remove(peerId, this)
            listener.onPeerDisconnected(peerId)
        }
    }

}
